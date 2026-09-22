package me.rerere.rikkahub.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure SQL-list constants behind [ImportedDatabaseReconciler].
 *
 * [ImportedDatabaseReconciler.reconcileDatabaseFile] itself opens a real
 * `android.database.sqlite.SQLiteDatabase`, which is not available in this repo's plain JVM
 * unit tests (no Robolectric), so it cannot be exercised here. What *is* pure, plain Kotlin is
 * [ImportedDatabaseReconciler.BACKFILL_INDEX_DDL] and the [ImportedDatabaseReconciler.EXPECTED_VERSION]
 * / [ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH] constants it stamps a reconciled file with.
 *
 * Issue #60: restoring an official upstream backup (db stamped at v24) and letting the
 * reconciler jump it straight to [ImportedDatabaseReconciler.EXPECTED_VERSION] skipped the
 * fork's 27->28 auto-migration entirely, so `ConversationEntity` ended up with no indices at
 * all (`Found: indices = {}`) even though Room's compiled schema for v30 expects two. This test
 * pins the fix: every index that migration would have added is present in the backfill list,
 * every statement is idempotent.
 *
 * Issue #105: EXPECTED_VERSION / EXPECTED_IDENTITY_HASH used to be literals (stale at 30, one
 * schema bump behind AppDatabase's real version 31), so nothing caught the drift. Instead of
 * literals, this now reads the highest-numbered exported schema JSON directly and asserts the
 * reconciler constants match it, so a future schema bump that forgets to update the reconciler
 * fails this test instead of silently breaking restores.
 */
class ImportedDatabaseReconcilerTest {

    private fun findSchemaDir(): File {
        val candidates = listOf(
            File("schemas/me.rerere.rikkahub.data.db.AppDatabase"),
            File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not find the AppDatabase schema export directory, tried: $candidates")
    }

    private fun latestSchemaFile(): File {
        val dir = findSchemaDir()
        return dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
            ?: error("no schema JSON files found under ${dir.absolutePath}")
    }

    private fun latestSchemaDatabaseObject(): JsonObject {
        val file = latestSchemaFile()
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root["database"]!!.jsonObject
    }

    @Test
    fun `expected version matches the newest exported schema`() {
        val database = latestSchemaDatabaseObject()
        val schemaVersion = database["version"]!!.jsonPrimitive.int
        assertEquals(schemaVersion, ImportedDatabaseReconciler.EXPECTED_VERSION)
    }

    @Test
    fun `expected identity hash matches the newest exported schema`() {
        val database = latestSchemaDatabaseObject()
        val schemaHash = database["identityHash"]!!.jsonPrimitive.content
        assertEquals(schemaHash, ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH)
    }

    @Test
    fun `expected version matches the newest schema file's own name`() {
        val file = latestSchemaFile()
        val nameVersion = file.nameWithoutExtension.toInt()
        assertEquals(nameVersion, ImportedDatabaseReconciler.EXPECTED_VERSION)
    }

    @Test
    fun `expected version matches the version declared in AppDatabase`() {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            // Cheap-read only: skip rather than fail the build if the source layout moves.
            return
        }
        val declaredVersion = Regex("""version\s*=\s*(\d+)""").find(file.readText())
            ?.groupValues?.get(1)?.toInt()
        assertEquals(declaredVersion, ImportedDatabaseReconciler.EXPECTED_VERSION)
    }

    @Test
    fun `backfills both ConversationEntity indices Room's 27-28 migration adds`() {
        val ddl = ImportedDatabaseReconciler.BACKFILL_INDEX_DDL
        assertTrue(ddl.any {
            it.contains("index_ConversationEntity_assistant_id_is_pinned_update_at") &&
                it.contains("ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)")
        })
        assertTrue(ddl.any {
            it.contains("index_ConversationEntity_is_pinned_update_at") &&
                it.contains("ON `ConversationEntity` (`is_pinned`, `update_at`)")
        })
    }

    @Test
    fun `backfills the MemoryEntity and scheduled-job indices from the same migration`() {
        val ddl = ImportedDatabaseReconciler.BACKFILL_INDEX_DDL
        assertTrue(ddl.any { it.contains("index_MemoryEntity_assistant_id") })
        assertTrue(ddl.any { it.contains("index_scheduled_jobs_enabled") })
        assertTrue(ddl.any { it.contains("index_scheduled_job_runs_jobId_startedAtMs") })
        assertTrue(ddl.any { it.contains("index_scheduled_job_runs_jobId_outcome") })
    }

    @Test
    fun `every backfill statement is idempotent`() {
        ImportedDatabaseReconciler.BACKFILL_INDEX_DDL.forEach {
            assertTrue("not idempotent: $it", it.startsWith("CREATE INDEX IF NOT EXISTS"))
        }
    }
}
