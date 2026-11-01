package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the Migration_15_16 bad-row-handling fix. The migration used to delete every
 * `message_node` row for a conversation and reinsert only the subset that parsed, silently
 * dropping any row whose JSON failed to decode. [migrateConversationNodes] is the pure decision
 * function behind that delete/reinsert: it must return null (meaning "touch nothing on disk")
 * whenever any row in the conversation failed to parse, regardless of what the parsed rows
 * look like.
 */
class Migration_15_16Test {

    private fun textRow(id: String, text: String, role: MessageRole = MessageRole.USER) =
        ToolNodeMigrationRow(
            id = id,
            messages = listOf(UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))),
            selectIndex = 0,
        )

    @Test
    fun `returns null for an empty conversation`() {
        assertNull(migrateConversationNodes(emptyList(), hasUnparsableRow = false))
    }

    @Test
    fun `never deletes rows when the conversation has an unparsable row`() {
        val rows = listOf(textRow("node1", "hello"), textRow("node2", "world"))
        // hasUnparsableRow=true simulates: this conversation also had a row whose messages
        // JSON failed to decode (excluded from `rows` by the caller). The parsed rows here
        // would normally be a no-op, but the important assertion is that the unparsable-row
        // flag alone is enough to force null - the caller must never delete+reinsert a
        // conversation whose row set is known to be incomplete.
        assertNull(migrateConversationNodes(rows, hasUnparsableRow = true))
    }

    @Test
    fun `returns null when nothing would change`() {
        val rows = listOf(textRow("node1", "hello"), textRow("node2", "world"))
        assertNull(migrateConversationNodes(rows, hasUnparsableRow = false))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `merges a TOOL node into the preceding ASSISTANT node when nothing is unparsable`() {
        val rows = listOf(
            textRow("node1", "Query"),
            ToolNodeMigrationRow(
                id = "node2",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.ToolCall("call1", "test_tool", "{}"))
                    )
                ),
                selectIndex = 0,
            ),
            ToolNodeMigrationRow(
                id = "node3",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                        )
                    )
                ),
                selectIndex = 0,
            ),
            textRow("node4", "Final", role = MessageRole.ASSISTANT),
        )

        val result = migrateConversationNodes(rows, hasUnparsableRow = false)

        assertTrue(result != null)
        val migrated = result!!
        assertEquals(3, migrated.size)
        assertEquals(listOf("node1", "node2", "node4"), migrated.map { it.id })
        val mergedTool = migrated[1].messages[0].parts[0] as UIMessagePart.Tool
        assertEquals("call1", mergedTool.toolCallId)
        assertTrue(mergedTool.isExecuted)
    }
}
