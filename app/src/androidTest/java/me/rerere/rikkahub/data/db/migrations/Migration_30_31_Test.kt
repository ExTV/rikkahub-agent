package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v30 -> v31 ([Migration_30_31]).
 *
 * Issue #105: a database restored from an upstream 2.5.x backup can already carry
 * `workspaces.shell_compatibility_mode` (stamped v30 by `ImportedDatabaseReconciler` before it
 * learned about the column). The old `AutoMigration(from = 30, to = 31)` unconditionally
 * re-ADDed the column and crashed with "duplicate column name". This asserts:
 *  - a plain v30 database (no column yet) gains it after migrating to 31;
 *  - a v30 database whose `workspaces` table already has the column migrates to 31 without
 *    throwing, and does not end up with a duplicate column.
 */
@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    private val TEST_DB = "migration-30-31-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate30To31_plainDatabase_addsShellCompatibilityModeColumn() {
        helper.createDatabase(TEST_DB, 30).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 31, true, Migration_30_31)

        val cursor = db.query("SELECT * FROM workspaces LIMIT 0")
        val columns = cursor.columnNames.toList()
        cursor.close()

        assertTrue("workspaces should gain shell_compatibility_mode", columns.contains("shell_compatibility_mode"))
        db.close()
    }

    @Test
    fun migrate30To31_columnAlreadyPresent_migratesWithoutDuplicateColumnError() {
        helper.createDatabase(TEST_DB, 30).apply {
            // Simulates a database restored from an upstream 2.5.x backup that
            // ImportedDatabaseReconciler stamped straight to v30 before it already carried this
            // column (issue #105): the column exists even though the file is stamped at v30.
            execSQL("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 31, true, Migration_30_31)

        val cursor = db.query("SELECT * FROM workspaces LIMIT 0")
        val occurrences = cursor.columnNames.count { it == "shell_compatibility_mode" }
        cursor.close()

        assertTrue("shell_compatibility_mode should appear exactly once, not duplicated", occurrences == 1)
        db.close()
    }
}
