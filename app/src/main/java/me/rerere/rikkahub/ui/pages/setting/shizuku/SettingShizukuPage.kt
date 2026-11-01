package me.rerere.rikkahub.ui.pages.setting.shizuku

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.shizuku.ShizukuStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import rikka.shizuku.Shizuku

// `moe.shizuku.manager` is only the Java namespace / permission prefix, never an installed
// package; the real application id is `moe.shizuku.privileged.api` (verified with
// `pm list packages`).
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"

/**
 * Settings -> Shizuku. Mirrors the Termux settings page's structure: a status section with
 * tap actions (app installed, service running, permission granted), then a help section.
 * The permission is only ever requested from the explicit tap here, never at app start or
 * on first chat.
 */
@Composable
fun SettingShizukuPage() {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var status by remember { mutableStateOf(ShizukuManager.status(ctx)) }
    fun refresh() {
        status = ShizukuManager.status(ctx)
    }

    // Live updates: binder appearing/dying, plus the async result of a permission request.
    DisposableEffect(Unit) {
        val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
        val binderDead = Shizuku.OnBinderDeadListener { refresh() }
        val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refresh()
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            toaster.show(
                ctx.getString(
                    if (granted) R.string.setting_shizuku_toast_permission_granted
                    else R.string.setting_shizuku_toast_permission_denied
                ),
                type = if (granted) ToastType.Success else ToastType.Error,
            )
        }
        ShizukuManager.addBinderReceivedListener(binderReceived)
        ShizukuManager.addBinderDeadListener(binderDead)
        ShizukuManager.addRequestPermissionResultListener(permissionResult)
        onDispose {
            ShizukuManager.removeBinderReceivedListener(binderReceived)
            ShizukuManager.removeBinderDeadListener(binderDead)
            ShizukuManager.removeRequestPermissionResultListener(permissionResult)
        }
    }

    // Re-check on resume so returning from the Shizuku app (installed / service started)
    // updates the rows immediately.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_shizuku_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(
                title = { Text(stringResource(R.string.setting_shizuku_section_status)) },
            ) {
                val appInstalled = status != ShizukuStatus.NOT_INSTALLED
                val serviceRunning = status == ShizukuStatus.PERMISSION_DENIED || status == ShizukuStatus.READY
                val permissionGranted = status == ShizukuStatus.READY

                item(
                    onClick = {
                        runCatching {
                            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                            ctx.startActivity(
                                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ?: Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse(SHIZUKU_RELEASES_URL)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                            )
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_shizuku_status_app)) },
                    supportingContent = {
                        Text(
                            if (appInstalled) stringResource(R.string.setting_shizuku_status_app_installed)
                            else stringResource(R.string.setting_shizuku_status_app_missing)
                        )
                    },
                    leadingContent = {
                        StatusDot(color = if (appInstalled) StatusColor.Green else StatusColor.Red)
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_shizuku_status_service)) },
                    supportingContent = {
                        Text(
                            if (serviceRunning) stringResource(R.string.setting_shizuku_status_service_running)
                            else stringResource(R.string.setting_shizuku_status_service_not_running)
                        )
                    },
                    leadingContent = {
                        StatusDot(color = if (serviceRunning) StatusColor.Green else StatusColor.Red)
                    },
                )
                item(
                    onClick = {
                        if (!serviceRunning) {
                            toaster.show(
                                ctx.getString(R.string.setting_shizuku_toast_service_not_running),
                                type = ToastType.Error,
                            )
                        } else {
                            ShizukuManager.requestPermission()
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_shizuku_status_permission)) },
                    supportingContent = {
                        Text(
                            if (permissionGranted) stringResource(R.string.setting_shizuku_status_permission_granted)
                            else stringResource(R.string.setting_shizuku_status_permission_missing)
                        )
                    },
                    leadingContent = {
                        StatusDot(color = if (permissionGranted) StatusColor.Green else StatusColor.Red)
                    },
                )
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_shizuku_section_help)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_shizuku_help_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_shizuku_help_body)) },
                )
            }
        }
    }
}

private enum class StatusColor { Green, Red }

@Composable
private fun StatusDot(color: StatusColor) {
    val tint = when (color) {
        StatusColor.Green -> MaterialTheme.colorScheme.primary
        StatusColor.Red -> MaterialTheme.colorScheme.error
    }
    Canvas(
        modifier = Modifier
            .size(10.dp)
            .padding(end = 4.dp, top = 2.dp),
    ) {
        drawCircle(color = tint)
    }
}
