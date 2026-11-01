package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the max_runs-above-100 bug: boundsExpired() derives a job's progress from
 * ScheduledJobRunRepository.countSuccessful(), which only counts rows that survived
 * runRepo.trim(). Trimming to a flat 100 regardless of job.maxRuns silently caps the
 * achievable success count at 100 — a job configured for e.g. 150 runs would fire forever,
 * since countSuccessful() can never exceed the trim floor.
 *
 * [historyRetentionFor] is the fix: the retained row count must never fall below
 * job.maxRuns, so trim() cannot erase the evidence boundsExpired() needs to stop the job.
 */
class CronJobWorkerHistoryRetentionTest {

    @Test
    fun `no maxRuns keeps the default 100-row baseline`() {
        assertEquals(100, historyRetentionFor(maxRuns = null))
    }

    @Test
    fun `maxRuns under the baseline does not shrink retention below 100`() {
        assertEquals(100, historyRetentionFor(maxRuns = 50))
    }

    @Test
    fun `maxRuns at the baseline keeps 100`() {
        assertEquals(100, historyRetentionFor(maxRuns = 100))
    }

    @Test
    fun `maxRuns above 100 widens retention so countSuccessful can still reach it`() {
        assertEquals(
            "trimming to 100 rows would permanently cap countSuccessful() at 100, " +
                "making max_runs=150 unreachable",
            150,
            historyRetentionFor(maxRuns = 150),
        )
    }

    @Test
    fun `an unbounded maxRuns is capped instead of growing retention forever`() {
        assertEquals(
            "without a ceiling, trim(keep = maxRuns) retains the job's run history " +
                "essentially forever for a very large max_runs",
            MAX_HISTORY_RETENTION,
            historyRetentionFor(maxRuns = Int.MAX_VALUE),
        )
    }

    @Test
    fun `maxRuns exactly at the ceiling is not further reduced`() {
        assertEquals(MAX_HISTORY_RETENTION, historyRetentionFor(maxRuns = MAX_HISTORY_RETENTION))
    }
}
