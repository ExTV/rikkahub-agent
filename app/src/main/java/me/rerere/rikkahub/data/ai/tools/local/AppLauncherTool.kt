package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.RikkaAccessibilityService

private suspend fun waitForForegroundPackage(
    expectedPkg: String,
    timeoutMs: Long = 2500,
    stepMs: Long = 150,
): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var current: String? = null
    while (System.currentTimeMillis() < deadline) {
        val svc = RikkaAccessibilityService.instance ?: return null
        current = svc.rootInActiveWindow?.packageName?.toString()
        if (current == expectedPkg) return current
        delay(stepMs)
    }
    return current
}

/**
 * Launches an installed app by package name. Useful when the LLM needs to bring a specific
 * app to the foreground before performing screen automation gestures (taps/swipes), or when
 * the user explicitly asks to "open Termux" / "open Settings" / etc.
 *
 * The companion list_installed_apps tool exposes available package names so the model does
 * not have to guess.
 */
fun launchAppTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "launch_app",
    description = """
        Open an installed app on the device by its package name (e.g. com.termux, com.android.settings).
        Returns {success: true} if the launch intent was dispatched. If you do not know the package name,
        first call list_installed_apps to discover available packages. The app is brought to the
        foreground; if the screen automation tools are enabled, they can then drive its UI
        (tap, swipe, read_window_tree).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Application package id, e.g. com.termux")
                })
            },
            required = listOf("package_name")
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        if (pkg.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "package_name is required") }.toString()
                )
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no_launch_intent")
                        put("package", pkg)
                        put("recovery", "Package may not be installed or has no launcher activity. Call list_installed_apps to verify.")
                    }.toString()
                )
            )
        }
        // Wake the screen if it's off; without this the activity launches behind the lock
        // screen and any subsequent read_window_tree call will see no_active_window. We do
        // not bypass a real PIN/biometric keyguard - that requires the user.
        val wasOff = !ScreenWaker.isInteractive(context)
        val woke = if (wasOff) ScreenWaker.wakeIfOff(context) else false
        val keyLocked = ScreenWaker.isKeyguardLocked(context)
        val keySecure = ScreenWaker.isKeyguardSecure(context)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = try {
            context.startActivity(intent)
            // Confirm the launched app actually took focus. If the user is physically
            // in another app (e.g. RikkaHub's own chat), the launch can be silently
            // ignored and subsequent screen-automation calls will loop on
            // wrong_foreground_app. Only meaningful when AccessibilityService is bound;
            // when unbound we cannot verify and report confirmed_foreground:false.
            val accessibilityRunning = RikkaAccessibilityService.instance != null
            val finalForeground: String? = if (accessibilityRunning && !keyLocked) {
                waitForForegroundPackage(pkg)
            } else null
            val confirmed = finalForeground == pkg
            if (accessibilityRunning && !keyLocked && !confirmed) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "launch_did_not_focus")
                            put("requested", pkg)
                            put("current_foreground", finalForeground.orEmpty())
                            put(
                                "recovery",
                                "The launch intent was dispatched but the OS did not move ${pkg} to the foreground within 2.5s. The user is likely actively viewing another app (often RikkaHub itself) — If the screen automation tools are enabled: do NOT pass package_name to read_window_tree on this turn. Either ask the user to switch to ${pkg}, or call read_window_tree with no package_name guard so you can see whatever IS currently on screen; otherwise ask the user to switch to the app manually."
                            )
                            if (wasOff) put("woke_screen", woke)
                        }.toString()
                    )
                )
            } else {
                AgentTurnTracker.recordNavigatedAway(pkg)
                AgentTurnTracker.touchPackage(pkg)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("package", pkg)
                            put("confirmed_foreground", confirmed)
                            if (!accessibilityRunning) {
                                put("note", "AccessibilityService not bound — could not verify foreground. Treat success as best-effort.")
                            }
                            if (wasOff) put("woke_screen", woke)
                            if (keyLocked) {
                                put("keyguard_locked", true)
                                put("keyguard_secure", keySecure)
                                if (keySecure) {
                                    put("warn", "Screen is woken but PIN/biometric keyguard is up. The user must unlock for the launched app to be visible and drivable.")
                                }
                            }
                            RikkaAccessibilityService.instance?.let { svc ->
                                put("after", screenStateJson(svc, screenChanged = null))
                            }
                        }.toString()
                    )
                )
            }
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "launch_failed")
                        put("reason", t.message ?: t::class.java.simpleName)
                    }.toString()
                )
            )
        }
        streamer.streamIfHeadless(invocationContext, "LaunchApp $pkg")
        result
    }
)

fun listInstalledAppsTool(context: Context): Tool = Tool(
    name = "list_installed_apps",
    description = "List installed apps as {label, package, version, has_launcher}. Default: launcher-visible only. With `filter` or `include_no_launcher=true`, also returns service-only addons (Termux:API, Termux:Boot, etc.) that have no app-drawer entry. Optional `include_permissions` adds each app's requested permissions.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring to match against label or package. Setting this auto-includes addon packages (no launcher activity).")
                })
                put("user_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true (default), only apps installed by the user; false includes system apps")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on the number of returned apps (default 200)")
                })
                put("include_no_launcher", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, also returns packages that have no launcher activity (service-only addons). Auto-true when filter is non-empty.")
                })
                put("include_permissions", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, each row also lists the permissions the app requests. Output grows a lot; combine with `filter` or a small `limit`.")
                })
            }
        )
    },
    execute = { input ->
        val filter = input.jsonObject["filter"]?.jsonPrimitive?.contentOrNull
            ?.lowercase()?.takeIf { it.isNotBlank() }
        val userOnly = input.jsonObject["user_only"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
        val includeNoLauncher = (input.jsonObject["include_no_launcher"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false) || filter != null
        val includePermissions = input.jsonObject["include_permissions"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false

        val pm = context.packageManager

        // Set of packages that DO have a launcher activity. Used to set has_launcher on each
        // row regardless of whether we walked them via the launcher query or the full
        // package list.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launcherResolved = pm.queryIntentActivities(launcherIntent, 0)
        val launcherPkgs = launcherResolved.mapNotNull { it.activityInfo?.packageName }.toHashSet()

        data class Row(
            val label: String,
            val pkg: String,
            val hasLauncher: Boolean,
            val version: String,
            val permissions: List<String>?,
        )

        fun versionOf(pkg: String): String =
            runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: ""

        fun permissionsOf(pkg: String): List<String>? =
            if (!includePermissions) null else runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
            }.getOrNull() ?: emptyList()

        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<String>()

        // 1) Walk launcher apps first so we get clean labels via ResolveInfo.loadLabel.
        for (info in launcherResolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in seen) continue
            seen.add(pkg)
            if (userOnly) {
                try {
                    val flags = pm.getApplicationInfo(pkg, 0).flags
                    val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdated = (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdated) continue
                } catch (_: PackageManager.NameNotFoundException) {
                    continue
                }
            }
            val label = info.loadLabel(pm).toString()
            if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) {
                continue
            }
            rows.add(Row(label, pkg, hasLauncher = true, version = versionOf(pkg), permissions = permissionsOf(pkg)))
            if (rows.size >= limit) break
        }

        // 2) If asked, also walk the full installed-package list and pick up addons without
        //    a launcher activity. Termux:API (com.termux.api), Termux:Boot, dialer addons,
        //    etc. only show up here.
        if (rows.size < limit && includeNoLauncher) {
            @Suppress("DEPRECATION")
            val all = try { pm.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }
            for (pkgInfo in all) {
                val pkg = pkgInfo.packageName
                if (pkg in seen) continue
                seen.add(pkg)
                val appInfo = pkgInfo.applicationInfo ?: continue
                if (userOnly) {
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdated = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdated) continue
                }
                val label = appInfo.loadLabel(pm).toString()
                if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) {
                    continue
                }
                rows.add(
                    Row(
                        label,
                        pkg,
                        hasLauncher = pkg in launcherPkgs,
                        version = versionOf(pkg),
                        permissions = permissionsOf(pkg),
                    )
                )
                if (rows.size >= limit) break
            }
        }

        rows.sortWith(compareBy({ it.label.lowercase() }, { it.pkg }))

        val payload = buildJsonObject {
            put("count", rows.size)
            put("apps", buildJsonArray {
                rows.forEach { row ->
                    addJsonObject {
                        put("label", row.label)
                        put("package", row.pkg)
                        put("version", row.version)
                        put("has_launcher", row.hasLauncher)
                        if (includePermissions) {
                            put("permissions", buildJsonArray {
                                row.permissions?.forEach { add(it) }
                            })
                        }
                    }
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Hand a URL to whatever app is registered as the system handler for it (browsers for
 * http/https, the dialer for tel:, the maps app for geo:, etc.). Massively cheaper than
 * driving Chrome's URL bar via accessibility set_text — for a "search hello in chrome"
 * request, just call open_url("https://www.google.com/search?q=hello") and the browser
 * lands directly on the results page.
 *
 * The package_name override exists for cases where the user explicitly names a non-default
 * browser; otherwise the system shows whatever default is configured.
 */
fun openUrlTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "open_url",
    description = """
        Open a URL in the system's default handler app (browser for http/https, dialer for
        tel:, maps for geo:, mailto: for email, etc.). Strongly preferred over
        launch_app + screen automation (when those tools are enabled) when the user asks you to "search X in chrome",
        "open google.com", "call this number", "show me this address on a map", or any
        request that maps cleanly to a URL — typing into a browser URL bar via accessibility
        is unreliable and slow. Optionally pass package_name to force a specific app
        (e.g. com.android.chrome) when multiple handlers exist. Auto-wakes the screen if
        it was off.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full URL with scheme. http(s):// for web, tel: for phone, geo:lat,lng for maps, mailto: for email.")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: force a specific handler app (e.g. com.android.chrome). Omit for system default.")
                })
            },
            required = listOf("url")
        )
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "url is required") }.toString()
                )
            )
        }
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val wasOff = !ScreenWaker.isInteractive(context)
        val woke = if (wasOff) ScreenWaker.wakeIfOff(context) else false
        val keyLocked = ScreenWaker.isKeyguardLocked(context)
        val keySecure = ScreenWaker.isKeyguardSecure(context)

        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        // Resolve before dispatch so we can return a useful error when no app handles the URL
        // scheme (e.g. mailto: without a mail client installed).
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "no_handler")
                        put("recovery", "No installed app handles this URL scheme. Try a different URL or install an appropriate app first.")
                        put("url", url)
                        if (pkg != null) put("package", pkg)
                    }.toString()
                )
            )
        }
        val result = try {
            context.startActivity(intent)
            val handlerPkg = resolved.activityInfo?.packageName
            AgentTurnTracker.recordNavigatedAway(handlerPkg)
            if (!handlerPkg.isNullOrBlank()) AgentTurnTracker.touchPackage(handlerPkg)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("url", url)
                        put("handler", resolved.activityInfo?.packageName ?: "")
                        if (wasOff) put("woke_screen", woke)
                        if (keyLocked) {
                            put("keyguard_locked", true)
                            put("keyguard_secure", keySecure)
                            if (keySecure) {
                                put("warn", "Screen is woken but PIN/biometric keyguard is up. The user must unlock for the URL handler to be visible.")
                            }
                        }
                    }.toString()
                )
            )
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "open_failed")
                        put("reason", t.message ?: t::class.java.simpleName)
                    }.toString()
                )
            )
        }
        streamer.streamIfHeadless(invocationContext, "OpenUrl ${url.take(60)}")
        result
    }
)

/**
 * Lists the activities a package declares, so the model can jump straight to a screen instead
 * of launching the app at its entry point and then driving the UI there with taps (issue #17).
 *
 * Only `exported` activities are reachable from another app; the flag is returned on every row
 * rather than filtered out, because a non-exported hit is still the answer to "does this screen
 * exist" and hiding it would make [launchActivityTool]'s refusal look arbitrary.
 */
fun listAppActivitiesTool(context: Context): Tool = Tool(
    name = "list_app_activities",
    description = """
        List the activities (screens) declared by one installed app, as
        {name, exported, label}. Use it to deep-link into a specific screen: pass a name from
        here to launch_activity. Only activities with exported=true can actually be launched.
        Call list_installed_apps first if you do not know the package name.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Application package id, e.g. com.android.settings")
                })
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional case-insensitive substring matched against the activity class name or label")
                })
                put("exported_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, return only launchable (exported) activities. Default false.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Cap on returned activities (default 100, max 500)")
                })
            },
            required = listOf("package_name")
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        if (pkg.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "package_name is required") }.toString()
                )
            )
        }
        val filter = input.jsonObject["filter"]?.jsonPrimitive?.contentOrNull
            ?.lowercase()?.takeIf { it.isNotBlank() }
        val exportedOnly = input.jsonObject["exported_only"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, 500) ?: 100

        val pm = context.packageManager
        val activities = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
                ).activities
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "package_not_found")
                        put("package", pkg)
                        put("recovery", "Call list_installed_apps to find the correct package id.")
                    }.toString()
                )
            )
        }

        val all = activities.orEmpty()
        val matched = all.filter { info ->
            if (exportedOnly && !info.exported) return@filter false
            if (filter == null) return@filter true
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull().orEmpty()
            info.name.lowercase().contains(filter) || label.lowercase().contains(filter)
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("package", pkg)
                    put("total", all.size)
                    put("matched", matched.size)
                    put("returned", minOf(matched.size, limit))
                    put("activities", buildJsonArray {
                        matched.take(limit).forEach { info ->
                            addJsonObject {
                                put("name", info.name)
                                put("exported", info.exported)
                                runCatching { info.loadLabel(pm).toString() }.getOrNull()
                                    ?.takeIf { it.isNotBlank() && it != info.name }
                                    ?.let { put("label", it) }
                            }
                        }
                    })
                }.toString()
            )
        )
    }
)

/**
 * Launches one specific activity of an installed app, so the model can land directly on the
 * screen it wants (issue #17) instead of opening the app and navigating.
 *
 * A non-exported activity throws [SecurityException] from [Context.startActivity]; that is
 * reported as a distinct outcome so the model stops retrying and picks another route rather
 * than reading it as a transient failure.
 */
fun launchActivityTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "launch_activity",
    description = """
        Open one specific screen (activity) of an installed app, e.g. package com.android.settings
        activity .wifi.WifiSettings. Call list_app_activities first to get valid activity names;
        only exported activities can be launched. Prefer launch_app when you just want the app's
        normal entry point.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Application package id, e.g. com.android.settings")
                })
                put("activity_name", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Activity class name from list_app_activities. A leading '.' is resolved against package_name.",
                    )
                })
            },
            required = listOf("package_name", "activity_name")
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
        val rawActivity = input.jsonObject["activity_name"]?.jsonPrimitive?.contentOrNull
        if (pkg.isNullOrBlank() || rawActivity.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "package_name and activity_name are required")
                    }.toString()
                )
            )
        }
        val activity = if (rawActivity.startsWith(".")) pkg + rawActivity else rawActivity

        // Same keyguard handling as launch_app: without waking, the activity starts behind the
        // lock screen and every follow-up screen-automation call sees no_active_window.
        val wasOff = !ScreenWaker.isInteractive(context)
        val woke = if (wasOff) ScreenWaker.wakeIfOff(context) else false
        val keyLocked = ScreenWaker.isKeyguardLocked(context)
        val keySecure = ScreenWaker.isKeyguardSecure(context)

        val result = try {
            context.startActivity(
                Intent()
                    .setClassName(pkg, activity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val accessibilityRunning = RikkaAccessibilityService.instance != null
            val finalForeground: String? = if (accessibilityRunning && !keyLocked) {
                waitForForegroundPackage(pkg)
            } else null
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("package", pkg)
                        put("activity", activity)
                        put("confirmed_foreground", finalForeground == pkg)
                        if (wasOff) put("woke_screen", woke)
                        if (keyLocked) {
                            put("keyguard_locked", true)
                            put("keyguard_secure", keySecure)
                            if (keySecure) {
                                put("warn", "Screen is woken but PIN/biometric keyguard is up. The user must unlock before this screen is visible.")
                            }
                        }
                        RikkaAccessibilityService.instance?.let { svc ->
                            put("after", screenStateJson(svc, screenChanged = null))
                        }
                    }.toString()
                )
            )
        } catch (e: SecurityException) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "activity_not_exported")
                        put("package", pkg)
                        put("activity", activity)
                        put("reason", e.message ?: "SecurityException")
                        put("recovery", "Android only allows launching activities declared exported=true. Call list_app_activities with exported_only=true and pick one of those, or use launch_app and, if the screen automation tools are enabled, navigate from there.")
                    }.toString()
                )
            )
        } catch (t: Throwable) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "launch_failed")
                        put("package", pkg)
                        put("activity", activity)
                        put("reason", t.message ?: t::class.java.simpleName)
                        put("recovery", "Verify the activity name with list_app_activities; the class must exist in this package.")
                    }.toString()
                )
            )
        }
        streamer.streamIfHeadless(invocationContext, "LaunchActivity $pkg/$activity")
        result
    }
)
