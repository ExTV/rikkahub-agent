package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for issues #10 / #11: restoring an *upstream* RikkaHub 2.4.1 backup into the
 * fork crashed on the first launch after the import with
 * "duplicate column name: custom_system_prompt".
 *
 * Upstream 2.4.1 stamps its database at user_version 24, but its ConversationEntity already
 * carries custom_system_prompt / workspace_cwd / folder_id (upstream added them at its own
 * earlier versions). The fork's shared-schema-equivalent version is 27, so an un-reconciled restore
 * makes Room replay the fork's 24 -> 25 auto-migration, which re-ADDs custom_system_prompt and
 * crashes. The fork's shared tables at v27 are byte-for-byte identical to upstream's at v24, so
 * [ImportedDatabaseReconciler] adds the fork's v28 compaction table, stamps the file to v28, and
 * skips the replay.
 *
 * The test builds a faithful upstream-2.4.1 file (fork v27 shared schema, upstream's version +
 * identity, fork-only tables removed) and asserts:
 *  - without reconcile, opening it through Room reproduces the reported duplicate-column crash;
 *  - after reconcile, Room opens it cleanly, the seeded conversation survives, and every
 *    fork-only table exists and starts empty.
 */
@RunWith(AndroidJUnit4::class)
class ImportedDatabaseReconcilerTest {

    private val TEST_DB = "reconciler-upstream-241-test"

    // Upstream RikkaHub 2.4.1's actual stamp: user_version 24 plus its own (foreign) identity.
    private val UPSTREAM_VERSION = 24
    private val UPSTREAM_IDENTITY = "0ea1aaebfa031c7995c45a1e35822e1a"

    private val FORK_ONLY_TABLES = listOf(
        "scheduled_jobs", "scheduled_job_runs", "ssh_hosts", "telegram_chats",
        "workflows", "workflow_runs", "agent_runs", "conversation_compaction",
    )

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun withoutReconcile_upstream241Backup_reproducesDuplicateColumnCrash() {
        createUpstream241Backup(conversationId = "c1")

        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .addMigrations(Migration_30_31)
            .build()
        try {
            // Forcing the db open replays the 24 -> 25 auto-migration; this is where the
            // reported crash fired.
            room.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM ConversationEntity").use { it.moveToFirst() }
            fail("expected Room to crash replaying the 24 -> 25 migration on an upstream 2.4.1 file")
        } catch (expected: Throwable) {
            val chain = generateSequence<Throwable>(expected) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
            assertTrue(
                "expected a duplicate-column failure on custom_system_prompt, got: $chain",
                chain.contains("custom_system_prompt") || chain.contains("duplicate column"),
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun afterReconcile_upstream241Backup_opensAndKeepsData() {
        createUpstream241Backup(conversationId = "c1")

        ImportedDatabaseReconciler.reconcileDatabaseFile(context.getDatabasePath(TEST_DB))

        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase // opens + validates now; no migration runs

            db.query("SELECT title FROM ConversationEntity WHERE id = 'c1'").use { c ->
                assertTrue("seeded conversation row should survive the restore", c.moveToFirst())
                assertEquals("hello", c.getString(0))
            }

            for (table in FORK_ONLY_TABLES) {
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    assertTrue("fork-only table $table should exist after reconcile", c.moveToFirst())
                    assertEquals("fork-only table $table should start empty", 0, c.getInt(0))
                }
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun afterReconcile_upstreamBackupMissingShellCompatibilityColumn_endsStampedWithColumn() {
        createUpstream25xBackup(conversationId = "c2", withShellCompatibilityColumn = false)

        ImportedDatabaseReconciler.reconcileDatabaseFile(context.getDatabasePath(TEST_DB))

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DB).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { raw ->
            assertEquals(
                "reconcile should stamp the file to EXPECTED_VERSION",
                ImportedDatabaseReconciler.EXPECTED_VERSION,
                raw.version,
            )
            raw.rawQuery("PRAGMA table_info(`workspaces`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val names = generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toList()
                assertEquals(
                    "shell_compatibility_mode should be backfilled exactly once",
                    1,
                    names.count { it == "shell_compatibility_mode" },
                )
            }
        }
    }

    @Test
    fun afterReconcile_upstreamBackupAlreadyHasShellCompatibilityColumn_isLeftIntact() {
        createUpstream25xBackup(conversationId = "c3", withShellCompatibilityColumn = true)

        ImportedDatabaseReconciler.reconcileDatabaseFile(context.getDatabasePath(TEST_DB))

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DB).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { raw ->
            assertEquals(ImportedDatabaseReconciler.EXPECTED_VERSION, raw.version)
            raw.rawQuery("PRAGMA table_info(`workspaces`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val names = generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toList()
                assertEquals(
                    "a pre-existing shell_compatibility_mode column must not be duplicated",
                    1,
                    names.count { it == "shell_compatibility_mode" },
                )
            }
        }
    }

    /**
     * Writes a file that looks like an upstream RikkaHub 2.5.x backup: start from a genuine fork
     * v31 database (Room creates every table and stamps the v31 identity), seed a conversation,
     * then downgrade the file on disk by dropping the fork-only tables, stamping a foreign
     * (upstream) version + identity, and optionally stripping `workspaces.shell_compatibility_mode`
     * to reproduce a restore that predates the reconciler's issue #105 fix.
     */
    private fun createUpstream25xBackup(conversationId: String, withShellCompatibilityColumn: Boolean) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        try {
            room.openHelper.writableDatabase.execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(conversationId, "hello", "[]", 1_000L, 1_000L),
            )
        } finally {
            room.close() // checkpoints WAL so the raw handle below sees a settled file
        }

        val dbFile = context.getDatabasePath(TEST_DB)
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            FORK_ONLY_TABLES.forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
            if (!withShellCompatibilityColumn) {
                // SQLite has no portable DROP COLUMN across the SQLite versions bundled with
                // every supported Android version, so rebuild the table without it instead.
                raw.execSQL("ALTER TABLE `workspaces` RENAME TO `workspaces_old`")
                raw.execSQL(
                    "CREATE TABLE `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL, " +
                        "`shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`last_access_at` INTEGER, `tool_approvals` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`))"
                )
                raw.execSQL(
                    "INSERT INTO `workspaces` (id, name, root, shell_status, created_at, updated_at, last_access_at, tool_approvals) " +
                        "SELECT id, name, root, shell_status, created_at, updated_at, last_access_at, tool_approvals FROM `workspaces_old`"
                )
                raw.execSQL("DROP TABLE `workspaces_old`")
            }
            raw.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf<Any?>(UPSTREAM_IDENTITY),
            )
            raw.version = UPSTREAM_VERSION // an upstream-style stamp, below EXPECTED_VERSION
        }
    }

    /**
     * Writes a file that looks exactly like an upstream RikkaHub 2.4.1 backup: start from a
     * genuine fork v28 database (Room creates every table and stamps the v28 identity), seed a
     * conversation, then downgrade the file on disk by dropping the fork-only tables and
     * stamping upstream's user_version + identity.
     */
    private fun createUpstream241Backup(conversationId: String) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        try {
            room.openHelper.writableDatabase.execSQL(
                "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(conversationId, "hello", "[]", 1_000L, 1_000L),
            )
        } finally {
            room.close() // checkpoints WAL so the raw handle below sees a settled file
        }

        val dbFile = context.getDatabasePath(TEST_DB)
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            FORK_ONLY_TABLES.forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
            raw.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf<Any?>(UPSTREAM_IDENTITY),
            )
            raw.version = UPSTREAM_VERSION // PRAGMA user_version = 24
        }
    }
}
