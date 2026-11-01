package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the Shizuku integration currently stands. */
enum class ShizukuStatus {
    /** The Shizuku manager app is not installed. */
    NOT_INSTALLED,

    /** Installed, but its privileged service (started from the app, or paired over
     *  wireless debugging on Android 11+) is not running, no live binder. */
    NOT_RUNNING,

    /** The binder is alive but the user has not granted RikkaHub the Shizuku permission. */
    PERMISSION_DENIED,

    /** Binder alive and permission granted: shizuku_exec can bind the user service. */
    READY,
}

/**
 * Pure state -> [ShizukuStatus] -> structured-error mapping. Kept free of the real
 * [rikka.shizuku.Shizuku] SDK (which needs a live binder to answer any of these questions)
 * so the mapping itself is unit-testable without a device running Shizuku.
 */
object ShizukuStatusMapper {

    fun compute(installed: Boolean, binderAlive: Boolean, permissionGranted: Boolean): ShizukuStatus = when {
        // Binder-first: a live pingBinder() proves Shizuku is actually running, so it must
        // never be reported as NOT_INSTALLED. Sui hands out the same binder with no Shizuku
        // app installed at all, and Android 11+ package visibility can hide the package from
        // getPackageInfo() even when it is present. The package check is only trusted as the
        // no-binder signal, so the tap-to-download hint still appears when nothing is running.
        !binderAlive && !installed -> ShizukuStatus.NOT_INSTALLED
        !binderAlive -> ShizukuStatus.NOT_RUNNING
        !permissionGranted -> ShizukuStatus.PERMISSION_DENIED
        else -> ShizukuStatus.READY
    }

    /** Structured tool-result error for every non-[ShizukuStatus.READY] state, or null when ready. */
    fun errorFor(status: ShizukuStatus): JsonObject? = when (status) {
        ShizukuStatus.READY -> null
        ShizukuStatus.NOT_INSTALLED -> buildJsonObject {
            put("error", "shizuku_not_installed")
            put(
                "recovery",
                "Install Shizuku from https://github.com/RikkaApps/Shizuku/releases/latest , start its service, then grant " +
                    "RikkaHub permission from Settings -> Shizuku."
            )
        }

        ShizukuStatus.NOT_RUNNING -> buildJsonObject {
            put("error", "shizuku_not_running")
            put(
                "recovery",
                "Shizuku is installed but its service isn't running. Start it from the Shizuku " +
                    "app (or re-pair wireless debugging on Android 11+), then retry."
            )
        }

        ShizukuStatus.PERMISSION_DENIED -> buildJsonObject {
            put("error", "shizuku_permission_denied")
            put(
                "recovery",
                "Grant RikkaHub the Shizuku permission from Settings -> Shizuku, then retry."
            )
        }
    }
}
