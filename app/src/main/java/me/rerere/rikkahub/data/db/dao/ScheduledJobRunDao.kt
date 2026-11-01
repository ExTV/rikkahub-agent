package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

@Dao
interface ScheduledJobRunDao {
    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun getRecent(jobId: String, limit: Int): List<ScheduledJobRunEntity>

    @Query("SELECT * FROM scheduled_job_runs WHERE finishedAtMs IS NULL AND startedAtMs < :stalenessMs")
    suspend fun getStranded(stalenessMs: Long): List<ScheduledJobRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ScheduledJobRunEntity)

    @Update
    suspend fun update(row: ScheduledJobRunEntity)

    /**
     * Trim history for a single job, retaining the [keep] most-recent successful rows and the
     * [keep] most-recent rows of every other outcome (both by startedAtMs). Runs at the end of
     * every fire so growth stays bounded, at no more than 2 * [keep] rows per job.
     *
     * The success bucket is separate because [countSuccessful] is what max_runs is enforced
     * against. A single newest-N window shared with failures meant a job that failed often
     * pushed its own successes out of history, so countSuccessful undercounted and a job with
     * max_runs set could never reach it and fired forever.
     */
    @Query(
        "DELETE FROM scheduled_job_runs WHERE jobId = :jobId " +
            "AND id NOT IN (SELECT id FROM scheduled_job_runs WHERE jobId = :jobId " +
            "AND outcome = 'success' ORDER BY startedAtMs DESC LIMIT :keep) " +
            "AND id NOT IN (SELECT id FROM scheduled_job_runs WHERE jobId = :jobId " +
            "AND outcome <> 'success' ORDER BY startedAtMs DESC LIMIT :keep)"
    )
    suspend fun trim(jobId: String, keep: Int)

    @Query("DELETE FROM scheduled_job_runs WHERE jobId = :jobId")
    suspend fun deleteAllForJob(jobId: String)

    /** Most-recent run row for a job, or null if none exists. Used for replay-idempotency check. */
    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getMostRecent(jobId: String): ScheduledJobRunEntity?

    /**
     * Count of rows with outcome='success' for a job.
     * Used as the authoritative source of max_runs progress — immune to replay race because
     * the row is inserted optimistically and updated to 'success' atomically before we read.
     */
    @Query("SELECT COUNT(*) FROM scheduled_job_runs WHERE jobId = :jobId AND outcome = 'success'")
    suspend fun countSuccessful(jobId: String): Int
}
