package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.telegram.TelegramBotConfig
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Global settings for the Telegram bot — bot token, default chat, whitelist, start/stop.
 *
 * "Enabled" here means BOTH "I want the bot running right now" AND "auto-start on device boot"
 * (CronBootReceiver checks this flag and re-launches the service after reboot). The single
 * toggle covers both, matching how the WebServer page works.
 */
@Composable
fun SettingTelegramPage() {
    val prefs: TelegramBotPreferences = koinInject()
    val cfg by prefs.flow.collectAsStateWithLifecycle(initialValue = TelegramBotConfig())
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val noTokenToast = stringResource(R.string.setting_page_telegram_no_token_toast)

    var tokenText by remember(cfg.token) { mutableStateOf(cfg.token) }
    var defaultChatText by remember(cfg.defaultChatId) {
        mutableStateOf(cfg.defaultChatId?.toString() ?: "")
    }
    var whitelistText by remember(cfg.whitelist) {
        mutableStateOf(cfg.whitelist.sorted().joinToString(","))
    }
    var tokenVisible by remember { mutableStateOf(false) }
    var proxyHostText by remember(cfg.proxyHost) { mutableStateOf(cfg.proxyHost) }
    var proxyPortText by remember(cfg.proxyPort) {
        mutableStateOf(if (cfg.proxyPort == 0) "" else cfg.proxyPort.toString())
    }
    var proxyUsernameText by remember(cfg.proxyUsername) { mutableStateOf(cfg.proxyUsername) }
    var proxyPasswordText by remember(cfg.proxyPassword) { mutableStateOf(cfg.proxyPassword) }
    var proxyPasswordVisible by remember { mutableStateOf(false) }
    val serviceRunning = TelegramBotService.isRunning

    fun startService() {
        scope.launch {
            prefs.update { it.copy(enabled = true) }
            TelegramBotService.start(context)
        }
    }

    fun stopService() {
        scope.launch {
            prefs.update { it.copy(enabled = false) }
            TelegramBotService.stop(context)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_telegram)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (cfg.token.isBlank()) {
                        toaster.show(noTokenToast)
                        return@ExtendedFloatingActionButton
                    }
                    if (cfg.enabled) stopService() else startService()
                },
                icon = {
                    Icon(
                        imageVector = if (cfg.enabled) HugeIcons.Stop else HugeIcons.Play,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        if (cfg.enabled) stringResource(R.string.setting_page_telegram_stop)
                        else stringResource(R.string.setting_page_telegram_start)
                    )
                },
                containerColor = if (cfg.enabled) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_token)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_telegram_token_desc)) },
                        trailingContent = {
                            TextField(
                                value = tokenText,
                                onValueChange = { value ->
                                    tokenText = value.trim()
                                    scope.launch {
                                        prefs.update { it.copy(token = tokenText) }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                        Icon(
                                            imageVector = if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = stringResource(if (tokenVisible) R.string.accessibility_hide_password else R.string.accessibility_show_password),
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.width(220.dp),
                                enabled = !cfg.enabled,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_default_chat)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_telegram_default_chat_desc)) },
                        trailingContent = {
                            TextField(
                                value = defaultChatText,
                                onValueChange = { value ->
                                    val cleaned = value.filter { it.isDigit() || it == '-' }
                                    defaultChatText = cleaned
                                    val parsed = cleaned.toLongOrNull()
                                    scope.launch {
                                        prefs.update { it.copy(defaultChatId = parsed) }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(160.dp),
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_whitelist)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_telegram_whitelist_desc)) },
                        trailingContent = {
                            TextField(
                                value = whitelistText,
                                onValueChange = { value ->
                                    whitelistText = value
                                    val ids = value.split(",")
                                        .mapNotNull { it.trim().toLongOrNull() }
                                        .toSet()
                                    scope.launch {
                                        prefs.update { it.copy(whitelist = ids) }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(220.dp),
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                text = if (cfg.enabled && serviceRunning)
                                    stringResource(R.string.setting_page_telegram_status_running)
                                else if (cfg.enabled)
                                    stringResource(R.string.setting_page_telegram_status_starting)
                                else
                                    stringResource(R.string.setting_page_telegram_status_stopped),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (cfg.enabled && serviceRunning)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_telegram_boot_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            // Proxy for the bot's own network calls only (never the shared app-wide client).
            // Locked while the bot is running: the OkHttp clients are built once per
            // TelegramBotClient instance, so a proxy change only takes effect on restart.
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_proxy_enabled)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_telegram_proxy_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = cfg.proxyEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch { prefs.update { it.copy(proxyEnabled = checked) } }
                                },
                                enabled = !cfg.enabled,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_proxy_type)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_page_telegram_proxy_type_desc))
                                SingleChoiceSegmentedButtonRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                ) {
                                    listOf("SOCKS5", "HTTP").forEachIndexed { index, type ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                            selected = cfg.proxyType == type,
                                            onClick = {
                                                scope.launch { prefs.update { it.copy(proxyType = type) } }
                                            },
                                            enabled = !cfg.enabled,
                                        ) {
                                            Text(type)
                                        }
                                    }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_proxy_host)) },
                        trailingContent = {
                            TextField(
                                value = proxyHostText,
                                onValueChange = { value ->
                                    proxyHostText = value.trim()
                                    scope.launch { prefs.update { it.copy(proxyHost = proxyHostText) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(200.dp),
                                enabled = !cfg.enabled,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_proxy_port)) },
                        trailingContent = {
                            TextField(
                                value = proxyPortText,
                                onValueChange = { value ->
                                    val cleaned = value.filter { it.isDigit() }.take(5)
                                    proxyPortText = cleaned
                                    scope.launch {
                                        prefs.update { it.copy(proxyPort = cleaned.toIntOrNull() ?: 0) }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(120.dp),
                                enabled = !cfg.enabled,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_proxy_username)) },
                        trailingContent = {
                            TextField(
                                value = proxyUsernameText,
                                onValueChange = { value ->
                                    proxyUsernameText = value
                                    scope.launch { prefs.update { it.copy(proxyUsername = proxyUsernameText) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(200.dp),
                                enabled = !cfg.enabled,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_telegram_proxy_password)) },
                        trailingContent = {
                            TextField(
                                value = proxyPasswordText,
                                onValueChange = { value ->
                                    proxyPasswordText = value
                                    scope.launch { prefs.update { it.copy(proxyPassword = proxyPasswordText) } }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = if (proxyPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { proxyPasswordVisible = !proxyPasswordVisible },
                                        enabled = !cfg.enabled,
                                    ) {
                                        Icon(
                                            imageVector = if (proxyPasswordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = stringResource(if (proxyPasswordVisible) R.string.accessibility_hide_password else R.string.accessibility_show_password),
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.width(200.dp),
                                enabled = !cfg.enabled,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            )
                        },
                    )
                }
            }

            // Battery-optimization whitelist row. On OEM-aggressive ROMs the bot only stays
            // responsive when the screen is off if the app is exempted from Doze.
            item {
                BatteryOptimizationCard()
            }
        }
    }
}

/**
 * Surfaces whether the app is whitelisted from Doze battery optimizations and offers a
 * one-tap deep-link to the system prompt. Re-checks state on every onResume so returning
 * from system settings updates the row instantly.
 */
@Composable
private fun BatteryOptimizationCard() {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val whitelisted = remember(resumeTick) {
        me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
            .ignoresBatteryOptimizations(ctx)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* result ignored - re-checked via ON_RESUME */ }

    CardGroup(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        item(
            onClick = {
                if (whitelisted) {
                    toaster.show(ctx.getString(R.string.setting_page_telegram_battery_already_ok))
                    return@item
                }
                val intent = me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
                    .requestIgnoreBatteryOptimizationsIntent(ctx)
                try {
                    launcher.launch(intent)
                } catch (_: Throwable) {
                    // Some OEMs reject ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; fall back
                    // to the system-wide list page where the user can find the app manually.
                    launcher.launch(
                        me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
                            .batteryOptimizationsListIntent()
                    )
                }
            },
            headlineContent = {
                Text(
                    text = if (whitelisted)
                        stringResource(R.string.setting_page_telegram_battery_ok)
                    else
                        stringResource(R.string.setting_page_telegram_battery_needed),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (whitelisted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.setting_page_telegram_battery_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
