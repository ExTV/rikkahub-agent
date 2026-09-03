package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_23_24

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_23_24)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Both steps below are best-effort FTS setup: a failure here (missing dict
                    // assets, FTS5 module unavailable, native lib not loaded yet) must degrade
                    // search, not crash every single app launch by throwing out of onOpen and
                    // failing the whole database open.
                    try {
                        val dictDir = SimpleDictManager.extractDict(context)
                        val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                        cursor.use {
                            if (it.moveToFirst()) {
                                val result = it.getString(0)
                                val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                                if (!success) {
                                    android.util.Log.e(
                                        "DataSourceModule",
                                        "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DataSourceModule", "onOpen: jieba_dict setup failed", e)
                    }

                    try {
                        db.execSQL(me.rerere.rikkahub.data.db.fts.MESSAGE_FTS_CREATE_SQL.trimIndent())
                    } catch (e: Exception) {
                        android.util.Log.e("DataSourceModule", "onOpen: message_fts table creation failed", e)
                    }
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
