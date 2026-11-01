package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ShizukuStatusMapper]: the pure state -> [ShizukuStatus] -> structured-error
 * mapping used by both `shizuku_exec` (to fail with a recovery hint) and the Settings ->
 * Shizuku page (to render the status rows). Live acquisition of `installed` / `binderAlive` /
 * `permissionGranted` from the real Shizuku SDK can only be verified on a device with Shizuku
 * actually running, see [ShizukuManager].
 */
class ShizukuStatusMapperTest {

    @Test
    fun `not installed only applies when there is no live binder`() {
        assertEquals(
            ShizukuStatus.NOT_INSTALLED,
            ShizukuStatusMapper.compute(installed = false, binderAlive = false, permissionGranted = false),
        )
    }

    @Test
    fun `a live binder is never reported as not_installed, even if the package looks absent`() {
        // Sui hands out the same binder with no Shizuku app installed at all, and Android 11+
        // package visibility can hide the package from getPackageInfo() even when it is
        // present. The binder is the ground truth; the package check only matters when there
        // is no binder to ask.
        assertEquals(
            ShizukuStatus.READY,
            ShizukuStatusMapper.compute(installed = false, binderAlive = true, permissionGranted = true),
        )
        assertEquals(
            ShizukuStatus.PERMISSION_DENIED,
            ShizukuStatusMapper.compute(installed = false, binderAlive = true, permissionGranted = false),
        )
    }

    @Test
    fun `installed but no binder is not_running`() {
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            ShizukuStatusMapper.compute(installed = true, binderAlive = false, permissionGranted = false),
        )
        // permissionGranted can't be trusted without a binder, but the mapping still prefers
        // not_running over pretending the (stale) permission signal matters.
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            ShizukuStatusMapper.compute(installed = true, binderAlive = false, permissionGranted = true),
        )
    }

    @Test
    fun `binder alive but permission not granted is permission_denied`() {
        assertEquals(
            ShizukuStatus.PERMISSION_DENIED,
            ShizukuStatusMapper.compute(installed = true, binderAlive = true, permissionGranted = false),
        )
    }

    @Test
    fun `installed plus binder plus permission is ready`() {
        assertEquals(
            ShizukuStatus.READY,
            ShizukuStatusMapper.compute(installed = true, binderAlive = true, permissionGranted = true),
        )
    }

    @Test
    fun `ready maps to no error`() {
        assertNull(ShizukuStatusMapper.errorFor(ShizukuStatus.READY))
    }

    @Test
    fun `every non-ready status maps to a distinct structured error with a recovery hint`() {
        val nonReady = listOf(
            ShizukuStatus.NOT_INSTALLED,
            ShizukuStatus.NOT_RUNNING,
            ShizukuStatus.PERMISSION_DENIED,
        )
        val errors = nonReady.map { status ->
            val error = ShizukuStatusMapper.errorFor(status)!!
            assertTrue("recovery" in error)
            assertTrue(error["recovery"]!!.jsonPrimitive.content.isNotBlank())
            error["error"]!!.jsonPrimitive.content
        }
        assertEquals(errors.toSet().size, errors.size) // no two statuses collapse to the same error code
        assertEquals("shizuku_not_installed", ShizukuStatusMapper.errorFor(ShizukuStatus.NOT_INSTALLED)!!["error"]!!.jsonPrimitive.content)
        assertEquals("shizuku_not_running", ShizukuStatusMapper.errorFor(ShizukuStatus.NOT_RUNNING)!!["error"]!!.jsonPrimitive.content)
        assertEquals("shizuku_permission_denied", ShizukuStatusMapper.errorFor(ShizukuStatus.PERMISSION_DENIED)!!["error"]!!.jsonPrimitive.content)
    }
}
