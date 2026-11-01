package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_15_16"

val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 15 to 16 (eager tool message migration)")
        DatabaseMigrationTracker.onMigrationStart(15, 16)
        db.beginTransaction()
        try {
            // Get all distinct conversation IDs
            val convCursor = db.query("SELECT DISTINCT conversation_id FROM message_node")
            val conversationIds = mutableListOf<String>()
            while (convCursor.moveToNext()) {
                conversationIds.add(convCursor.getString(0))
            }
            convCursor.close()

            var updatedConversations = 0
            var skippedConversations = 0

            for (conversationId in conversationIds) {
                // Load all nodes for this conversation ordered by node_index
                val nodeCursor = db.query(
                    "SELECT id, messages, node_index, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
                    arrayOf(conversationId)
                )

                val rows = mutableListOf<ToolNodeMigrationRow>()
                var hasUnparsableRow = false
                while (nodeCursor.moveToNext()) {
                    val id = nodeCursor.getString(0)
                    val messagesJson = nodeCursor.getString(1)
                    val selectIndex = nodeCursor.getInt(3)
                    runCatching {
                        val messages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                        rows.add(ToolNodeMigrationRow(id, messages, selectIndex))
                    }.onFailure {
                        hasUnparsableRow = true
                        Log.w(TAG, "migrate: failed to parse messages for node $id, conversation $conversationId will be left untouched", it)
                    }
                }
                nodeCursor.close()

                if (rows.isEmpty()) {
                    if (hasUnparsableRow) skippedConversations++
                    continue
                }

                // migrateToolNodes is treated as fallible: this whole loop runs inside one big
                // transaction, so a single conversation's transform throwing must not abort it
                // and roll back every conversation already migrated.
                val migrated = try {
                    migrateConversationNodes(rows, hasUnparsableRow)
                } catch (e: Exception) {
                    Log.e(TAG, "migrate: failed to migrate conversation $conversationId, leaving it untouched", e)
                    null
                }

                if (migrated == null) {
                    if (hasUnparsableRow) skippedConversations++
                    continue
                }

                // Delete old nodes and re-insert migrated ones with corrected node_index
                db.execSQL("DELETE FROM message_node WHERE conversation_id = ?", arrayOf(conversationId))
                migrated.forEachIndexed { index, row ->
                    val messagesJson = JsonInstant.encodeToString(row.messages)
                    db.execSQL(
                        "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>(row.id, conversationId, index, messagesJson, row.selectIndex)
                    )
                }
                updatedConversations++
            }

            db.setTransactionSuccessful()
            Log.i(
                TAG,
                "migrate: migrate from 15 to 16 success ($updatedConversations conversations updated, " +
                    "$skippedConversations skipped due to unparsable rows)"
            )
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
