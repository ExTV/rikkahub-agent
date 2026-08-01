package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Reconciles a database file that was just restored from a backup so Room can open it.
 *
 * The fork added several tables (scheduled jobs, workflows, ssh hosts, telegram chats,
 * the agent-run ledger) on top of upstream RikkaHub, and it renumbered its Room schema so
 * the same version number no longer means the same thing in the two apps. A backup exported
 * from *upstream* RikkaHub therefore breaks a fresh restore in two ways:
 *  - it is missing the fork-only tables, so Room fails its integrity check or hits "no such
 *    table: scheduled_jobs" at first query (issue #8); and
 *  - it is stamped with upstream's user_version (24 for 2.4.x), which is LOWER than the
 *    fork's schema-equivalent version (27), so Room replays the fork's 24->25 / 25->26 /
 *    26->27 auto-migrations and re-ADDs columns the file already carries, crashing with
 *    "duplicate column name: custom_system_prompt" (issues #10, #11).
 * Either way the app crashes on the very first launch after the import.
 *
 * This step runs once, right after the restore writes `rikka_hub.db`, on the raw file before
 * Room touches it:
 *  - It creates any of the fork-only tables that are missing, empty, with the exact schema
 *    Room expects (copied verbatim from app/schemas/.../28.json), so the file looks like a
 *    clean agent install for those tables.
 *  - If the file is already at the fork's current schema (stamped at the matching version, or
 *    an upstream file whose shared tables already carry every modern column), it stamps Room's
 *    user_version and identity row to the fork's current values so Room opens the file with no
 *    migration. Without this Room either replays colliding migrations or rejects the foreign
 *    hash, even though every table is now present. The shared tables match column-for-column
 *    because the fork tracks upstream's schema, so trusting the hash is sound.
 *
 * If the backup is at an older version that is not yet schema-complete, Room runs its normal
 * migrations up to current and sets the identity itself; pre-creating the tables just lets
 * those migrations find them. Backups newer than the app are left untouched (Room reports the
 * downgrade).
 *
 * Best-effort: any failure here is logged and swallowed so a restore never half-breaks. The
 * worst case is the same pre-existing crash on next open, never data loss: there is no
 * destructive-migration fallback configured, so the restored rows always survive on disk.
 */
object ImportedDatabaseReconciler {

    private const val TAG = "DbReconciler"
    private const val DB_NAME = "rikka_hub"

    /**
     * Room's schema version and identity hash for [AppDatabase]. Both are copied verbatim
     * from app/schemas/me.rerere.rikkahub.data.db.AppDatabase/28.json (the identity hash also
     * appears in the generated AppDatabase_Impl RoomOpenDelegate). When the schema version is
     * bumped, update BOTH constants (and the table DDL below if the fork-only tables changed,
     * and MODERN_COLUMN_SENTINELS if newer conversation columns were added) or this
     * reconciliation will silently stop matching.
     */
    private const val EXPECTED_VERSION = 28
    private const val EXPECTED_IDENTITY_HASH = "d3949c752e5a34360f2dd618bfbc40cd"

    /**
     * Columns that a restored file must already have for its shared schema to be considered
     * byte-for-byte equal to the fork's current schema. They are exactly the columns the
     * fork's 24->25 / 25->26 / 26->27 auto-migrations add (custom_system_prompt at 25,
     * workspace_cwd at 26, folder_id at 27), so a file carrying all three would collide on
     * every one of those replays. Upstream 2.4.x carries all three; a genuine fork file below
     * v27 carries only a prefix, so it still migrates normally.
     */
    private val MODERN_COLUMN_SENTINELS = listOf("custom_system_prompt", "workspace_cwd", "folder_id")

    private const val CONTEXT_COMPACTION_DDL =
        "CREATE TABLE IF NOT EXISTS `conversation_compaction` (`conversation_id` TEXT NOT NULL, `summary` TEXT NOT NULL, `tail_start_node_id` TEXT, `source_end_node_id` TEXT NOT NULL, `summary_model_id` TEXT NOT NULL, `is_auto` INTEGER NOT NULL, `source_token_estimate` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /**
     * Fork-only tables absent from an upstream backup, with their exact v25 create + index
     * statements. Every statement is IF NOT EXISTS so running it against a genuine agent
     * backup (where the tables already exist) is a no-op.
     */
    private val FORK_ONLY_DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `scheduled_jobs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT, `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER, `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER, `mode` TEXT NOT NULL DEFAULT 'llm', `actionsJson` TEXT, `cronExpression` TEXT, `timezone` TEXT, `startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER, `runsSoFar` INTEGER NOT NULL DEFAULT 0, `catchup` TEXT NOT NULL DEFAULT 'fire_once', `description` TEXT, `tags` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `scheduled_job_runs` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, `mode` TEXT NOT NULL, `scheduledAtMs` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `outcome` TEXT NOT NULL, `conversationId` TEXT, `errorMessage` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `ssh_hosts` (`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, `password` TEXT, `privateKey` TEXT, `passphrase` TEXT, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        "CREATE TABLE IF NOT EXISTS `telegram_chats` (`chatId` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastMessageAtMs` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `enabled` INTEGER NOT NULL DEFAULT 1, `definitionJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `lastRunStatus` TEXT, `lastRunError` TEXT, `runsTodayCount` INTEGER NOT NULL DEFAULT 0, `runsTodayDate` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `workflow_runs` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` TEXT NOT NULL, `firedAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `errorMessage` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_firedAtMs` ON `workflow_runs` (`workflowId`, `firedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `domain_id` TEXT NOT NULL, `parent_run_id` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `finished_at_ms` INTEGER, `last_error` TEXT, `metadata_json` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)",
    )

    /**
     * Call after a restore has written the database file, and only when the restore actually
     * included the database. Safe to call when the file is a genuine agent backup (every
     * statement is idempotent) or when the file does not exist (no-op).
     */
    fun reconcile(context: Context) {
        reconcileDatabaseFile(context.getDatabasePath(DB_NAME))
    }

    /**
     * Testable core of [reconcile]: operate on the raw db file at [dbFile] directly, so a test
     * can exercise it against a temp file instead of the app's live `rikka_hub` database.
     */
    internal fun reconcileDatabaseFile(dbFile: File) {
        if (!dbFile.exists()) {
            Log.i(TAG, "reconcile: no database file at ${dbFile.absolutePath}, skipping")
            return
        }
        try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                val version = db.version // PRAGMA user_version
                if (version > EXPECTED_VERSION) {
                    Log.w(TAG, "reconcile: backup db version $version is newer than $EXPECTED_VERSION; leaving untouched")
                    return
                }

                // A file whose shared schema already matches the fork's current schema must not
                // be migrated: Room would replay the fork's 24->27 auto-migrations and re-ADD
                // columns the file already has, crashing with "duplicate column name" (an
                // upstream 2.4.x backup stamps user_version 24 but carries every modern column;
                // see issues #10, #11). Detect that case by the sentinel columns those very
                // migrations add.
                val alreadyCurrent = MODERN_COLUMN_SENTINELS.all {
                    hasColumn(db, "ConversationEntity", it)
                }

                db.beginTransaction()
                try {
                    FORK_ONLY_DDL.forEach(db::execSQL)

                    if (version == EXPECTED_VERSION || alreadyCurrent) {
                        // v28 adds this table through Room's 27->28 auto-migration. Imported
                        // upstream databases and genuine v27 fork databases are stamped straight
                        // to v28 here, so create the table before installing the v28 identity.
                        db.execSQL(CONTEXT_COMPACTION_DDL)
                        // No migration should run: the file is either already stamped at the
                        // fork's version, or it is an upstream file whose shared schema already
                        // matches it. Point Room's identity row and user_version at the fork so
                        // the integrity check passes now that every fork-only table is present.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                            arrayOf(EXPECTED_IDENTITY_HASH),
                        )
                        if (version != EXPECTED_VERSION) {
                            // PRAGMA user_version is transactional (SQLiteOpenHelper sets it the
                            // same way inside its own upgrade transaction), so this commits or
                            // rolls back atomically with the DDL and identity row above.
                            db.version = EXPECTED_VERSION
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                Log.i(TAG, "reconcile: reconciled imported db (version=$version, alreadyCurrent=$alreadyCurrent)")
            }
        } catch (t: Throwable) {
            // Never let reconciliation break the restore. Worst case is the pre-existing
            // behaviour (a crash on next open); the user's rows are still on disk.
            Log.w(TAG, "reconcile: failed to reconcile imported db", t)
        }
    }

    /** True if [table] exists and has a column named [column]. Best-effort; false on error. */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                if (nameIndex >= 0) {
                    while (!found && cursor.moveToNext()) {
                        found = cursor.getString(nameIndex) == column
                    }
                }
                found
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hasColumn: failed to inspect $table.$column", t)
            false
        }
    }
}
