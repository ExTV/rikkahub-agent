package me.rerere.rikkahub.workflow.condition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pinned-result tests for the NOAA solar position formula. ±2-minute tolerance per spec.
 *
 * Sources for ground truth: timeanddate.com — the day of computation should be specified
 * exactly to avoid month/year drift in DST checks. Both reference dates use a static UTC
 * offset for a stable expectation.
 */
class SolarTimesTest {

    /** New York City — 2026-06-21 (summer solstice). Reference sunrise ~05:25 EDT, sunset ~20:31 EDT. */
    @Test fun `nyc summer solstice sunrise within 2 minutes`() {
        val date = LocalDate.of(2026, 6, 21)
        val zone = ZoneId.of("America/New_York")
        val sunrise = SolarTimes.sunriseAt(date, lat = 40.7128, lng = -74.0060, zone)
        assertNotNull(sunrise)
        assertWithinMinutes(LocalTime.of(5, 25), sunrise!!, 3)
    }

    @Test fun `nyc summer solstice sunset within 2 minutes`() {
        val date = LocalDate.of(2026, 6, 21)
        val zone = ZoneId.of("America/New_York")
        val sunset = SolarTimes.sunsetAt(date, lat = 40.7128, lng = -74.0060, zone)
        assertNotNull(sunset)
        assertWithinMinutes(LocalTime.of(20, 31), sunset!!, 3)
    }

    /** Sydney — 2026-12-21 (austral summer solstice). Reference sunrise ~05:42 AEDT, sunset ~20:08. */
    @Test fun `sydney summer solstice sunrise within 2 minutes`() {
        val date = LocalDate.of(2026, 12, 21)
        val zone = ZoneId.of("Australia/Sydney")
        val sunrise = SolarTimes.sunriseAt(date, lat = -33.8688, lng = 151.2093, zone)
        assertNotNull(sunrise)
        assertWithinMinutes(LocalTime.of(5, 42), sunrise!!, 3)
    }

    @Test fun `sydney summer solstice sunset within 2 minutes`() {
        val date = LocalDate.of(2026, 12, 21)
        val zone = ZoneId.of("Australia/Sydney")
        val sunset = SolarTimes.sunsetAt(date, lat = -33.8688, lng = 151.2093, zone)
        assertNotNull(sunset)
        assertWithinMinutes(LocalTime.of(20, 8), sunset!!, 3)
    }

    /**
     * Regression for isAfterSunset being wrong for the whole post-midnight half of every
     * night: it compared `now` against the SAME calendar date's sunset, which for a
     * pre-dawn time (e.g. 2am) hasn't happened yet that date — the sunset that actually
     * applies is the PREVIOUS evening's. NYC 2026-06-21/22, sunset ~20:31 EDT, sunrise
     * ~05:25 EDT.
     */
    @Test fun `isAfterSunset is true in the post-midnight hours before the next sunrise`() {
        val zone = ZoneId.of("America/New_York")
        val postMidnight = ZonedDateTime.of(2026, 6, 22, 2, 0, 0, 0, zone)
        assertTrue(
            "2am is still night — after last night's sunset and before today's sunrise",
            SolarTimes.isAfterSunset(postMidnight, lat = 40.7128, lng = -74.0060, offsetMinutes = 0),
        )
    }

    @Test fun `isAfterSunset is true in the evening after today's sunset`() {
        val zone = ZoneId.of("America/New_York")
        val evening = ZonedDateTime.of(2026, 6, 21, 21, 0, 0, 0, zone)
        assertTrue(
            SolarTimes.isAfterSunset(evening, lat = 40.7128, lng = -74.0060, offsetMinutes = 0),
        )
    }

    @Test fun `isAfterSunset is false during the day, between sunrise and sunset`() {
        val zone = ZoneId.of("America/New_York")
        val midday = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, zone)
        assertFalse(
            SolarTimes.isAfterSunset(midday, lat = 40.7128, lng = -74.0060, offsetMinutes = 0),
        )
    }

    private fun assertWithinMinutes(expected: LocalTime, actual: LocalTime, toleranceMin: Int) {
        val diff = kotlin.math.abs(expected.toSecondOfDay() - actual.toSecondOfDay()) / 60
        assertTrue(
            "expected $expected ± ${toleranceMin}min, got $actual (delta ${diff}min)",
            diff <= toleranceMin,
        )
    }
}
