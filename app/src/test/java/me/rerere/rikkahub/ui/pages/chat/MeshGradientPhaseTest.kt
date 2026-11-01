package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/**
 * Coverage for [phaseAtElapsed], which replaced the per-frame
 * `rememberInfiniteTransition` + `tween` phases with a throttled time source (see
 * MeshGradientBackground.kt). Asserts it reproduces the same range endpoints and
 * period as the old `infiniteRepeatable(tween(durationMillis * loops, LinearEasing))`
 * animating from 0f to 2*PI*loops with RepeatMode.Restart.
 */
class MeshGradientPhaseTest {

    @Test fun `starts at zero`() {
        assertEquals(0f, phaseAtElapsed(0f, durationMillis = 5_500, loops = 20), 1e-4f)
    }

    @Test fun `reaches the tween target just before restart`() {
        val durationMillis = 5_500
        val loops = 20
        val totalMillis = durationMillis.toFloat() * loops
        val target = (2f * PI.toFloat()) * loops

        val justBeforeWrap = phaseAtElapsed(totalMillis - 1f, durationMillis, loops)

        assertEquals(target, justBeforeWrap, 0.01f)
    }

    @Test fun `restarts to zero exactly at the period`() {
        val durationMillis = 7_000
        val loops = 1
        val totalMillis = durationMillis.toFloat() * loops

        assertEquals(0f, phaseAtElapsed(totalMillis, durationMillis, loops), 1e-3f)
    }

    @Test fun `is periodic with period durationMillis times loops`() {
        val durationMillis = 8_500
        val loops = 10
        val totalMillis = durationMillis.toFloat() * loops
        val t = 12_345f

        val a = phaseAtElapsed(t, durationMillis, loops)
        val b = phaseAtElapsed(t + totalMillis, durationMillis, loops)

        assertEquals(a, b, 1e-2f)
    }

    @Test fun `halfway through the period is half the target`() {
        val durationMillis = 6_200
        val loops = 10
        val totalMillis = durationMillis.toFloat() * loops
        val target = (2f * PI.toFloat()) * loops

        val half = phaseAtElapsed(totalMillis / 2f, durationMillis, loops)

        assertEquals(target / 2f, half, 0.01f)
    }
}
