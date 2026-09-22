package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_30_31"

/**
 * v30 → v31 (upstream 2.5.1's workspace "Shell compatibility mode" flag).
 *
 * Adds `shell_compatibility_mode` to `workspaces`. Hand-written rather than an AutoMigration
 * (issue #105): a database restored from an upstream 2.5.x backup can already carry this column
 * because [me.rerere.rikkahub.data.db.ImportedDatabaseReconciler] stamped it straight to v30
 * without it, so an unconditional `ADD COLUMN` crash-loops every launch with "duplicate column
 * name: shell_compatibility_mode". Checking `PRAGMA table_info` first makes the step idempotent.
 * The `ADD COLUMN` statement is byte-identical to what the old AutoMigration generated, so the
 * result still matches app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json.
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 30 to 31 (workspaces.shell_compatibility_mode)")
        db.beginTransaction()
        try {
            if (!hasColumn(db, "workspaces", "shell_compatibility_mode")) {
                db.execSQL(
                    "ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"
                )
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 30 to 31 success")
        } finally {
            db.endTransaction()
        }
    }
}

/** True if [table] has a column named [column]. */
private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
