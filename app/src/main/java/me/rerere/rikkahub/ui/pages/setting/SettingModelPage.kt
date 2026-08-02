package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = stringResource(R.string.setting_model_page_title_model_desc),
                modelId = settings.titleModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(titleModelId = null)) },
            )
        }
        item {
            SuggestionModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = stringResource(R.string.setting_model_page_compress_model_desc),
                modelId = settings.compressModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
            )
        }
        item {
            AutoCompactionSettingItem(settings = settings, vm = vm)
        }
        item {
            ResponseStreamRetrySettingItem(settings = settings, vm = vm)
        }
    }
}

@Composable
private fun ResponseStreamRetrySettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    var maxRetries by remember(settings.responseStreamMaxRetries) {
        mutableStateOf(settings.responseStreamMaxRetries.toString())
    }

    CardGroup {
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_response_stream_retries))
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setting_model_page_response_stream_retries_desc))
                    OutlinedTextField(
                        value = maxRetries,
                        onValueChange = { value ->
                            maxRetries = value.filter(Char::isDigit).take(2)
                        },
                        singleLine = true,
                        label = {
                            Text(stringResource(R.string.setting_model_page_response_stream_retries_input))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val normalized = maxRetries.toIntOrNull()
                                        ?.coerceIn(0, 10)
                                        ?: settings.responseStreamMaxRetries
                                    maxRetries = normalized.toString()
                                    if (normalized != settings.responseStreamMaxRetries) {
                                        vm.updateSettings(
                                            settings.copy(responseStreamMaxRetries = normalized)
                                        )
                                    }
                                }
                            },
                    )
                }
            },
        )
    }
}

@Composable
private fun AutoCompactionSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    var threshold by remember(settings.autoCompactionThresholdPercent) {
        mutableFloatStateOf(settings.autoCompactionThresholdPercent.toFloat())
    }
    var tokenThreshold by remember(settings.autoCompactionThresholdTokensK) {
        mutableStateOf(settings.autoCompactionThresholdTokensK.toString())
    }
    var compactionTargetTokensK by remember(settings.contextCompactionTargetTokensK) {
        mutableStateOf(settings.contextCompactionTargetTokensK?.toString().orEmpty())
    }
    var keepRecentToolCalls by remember(settings.autoCompactionKeepRecentToolCalls) {
        mutableStateOf(settings.autoCompactionKeepRecentToolCalls.toString())
    }

    CardGroup {
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_enable_auto_compaction))
            },
            supportingContent = {
                Text(stringResource(R.string.setting_model_page_enable_auto_compaction_desc))
            },
            trailingContent = {
                Switch(
                    checked = settings.enableAutoCompaction,
                    onCheckedChange = {
                        vm.updateSettings(settings.copy(enableAutoCompaction = it))
                    },
                )
            },
        )
        if (settings.enableAutoCompaction) {
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_auto_compaction_threshold))
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.setting_model_page_auto_compaction_threshold_desc))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val modes = listOf(
                                AutoCompactionThresholdMode.PERCENT to
                                    stringResource(R.string.setting_model_page_auto_compaction_mode_percent),
                                AutoCompactionThresholdMode.TOKENS to
                                    stringResource(R.string.setting_model_page_auto_compaction_mode_tokens),
                            )
                            modes.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                    selected = settings.autoCompactionThresholdMode == mode,
                                    onClick = {
                                        if (settings.autoCompactionThresholdMode != mode) {
                                            vm.updateSettings(settings.copy(autoCompactionThresholdMode = mode))
                                        }
                                    },
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        if (settings.autoCompactionThresholdMode == AutoCompactionThresholdMode.PERCENT) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Slider(
                                    value = threshold,
                                    onValueChange = { value ->
                                        threshold = (value / 5f).toInt().times(5).toFloat()
                                    },
                                    onValueChangeFinished = {
                                        vm.updateSettings(
                                            settings.copy(
                                                autoCompactionThresholdPercent = threshold.toInt()
                                            )
                                        )
                                    },
                                    valueRange = 5f..95f,
                                    steps = 17,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${threshold.toInt()}%")
                            }
                        } else {
                            OutlinedTextField(
                                value = tokenThreshold,
                                onValueChange = { value ->
                                    tokenThreshold = value.filter(Char::isDigit).take(7)
                                },
                                singleLine = true,
                                suffix = { Text("k") },
                                label = {
                                    Text(stringResource(R.string.setting_model_page_auto_compaction_tokens))
                                },
                                supportingText = {
                                    Text(stringResource(R.string.setting_model_page_auto_compaction_tokens_desc))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused) {
                                            val parsed = tokenThreshold.toIntOrNull()
                                            if (parsed == null) {
                                                tokenThreshold = settings.autoCompactionThresholdTokensK.toString()
                                            } else {
                                                val normalized = parsed.coerceIn(1, Int.MAX_VALUE / 1_000)
                                                tokenThreshold = normalized.toString()
                                                if (normalized != settings.autoCompactionThresholdTokensK) {
                                                    vm.updateSettings(
                                                        settings.copy(autoCompactionThresholdTokensK = normalized)
                                                    )
                                                }
                                            }
                                        }
                                    },
                            )
                        }
                    }
                },
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.setting_model_page_auto_compaction_keep_tool_calls))
                },
                supportingContent = {
                    OutlinedTextField(
                        value = keepRecentToolCalls,
                        onValueChange = { value ->
                            keepRecentToolCalls = value.filter(Char::isDigit).take(4)
                        },
                        singleLine = true,
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.setting_model_page_auto_compaction_keep_tool_calls_desc
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val normalized = keepRecentToolCalls.toIntOrNull()
                                        ?.coerceIn(0, 1_000)
                                        ?: settings.autoCompactionKeepRecentToolCalls
                                    keepRecentToolCalls = normalized.toString()
                                    if (normalized != settings.autoCompactionKeepRecentToolCalls) {
                                        vm.updateSettings(
                                            settings.copy(autoCompactionKeepRecentToolCalls = normalized)
                                        )
                                    }
                                }
                            },
                    )
                },
            )
        }
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_model_page_context_compaction_target))
            },
            supportingContent = {
                OutlinedTextField(
                    value = compactionTargetTokensK,
                    onValueChange = { value ->
                        compactionTargetTokensK = value.filter(Char::isDigit).take(7)
                    },
                    singleLine = true,
                    suffix = { Text("k") },
                    supportingText = {
                        Text(stringResource(R.string.setting_model_page_context_compaction_target_desc))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                val normalized = compactionTargetTokensK.toIntOrNull()
                                    ?.coerceIn(1, Int.MAX_VALUE / 1_000)
                                val normalizedValue = normalized?.toString().orEmpty()
                                compactionTargetTokensK = normalizedValue
                                if (normalized != settings.contextCompactionTargetTokensK) {
                                    vm.updateSettings(
                                        settings.copy(contextCompactionTargetTokensK = normalized)
                                    )
                                }
                            }
                        },
                )
            },
        )
    }
}

@Composable
private fun SuggestionModelSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    val title = stringResource(R.string.setting_model_page_suggestion_model)
    val state = rememberModelListState(
        modelId = settings.suggestionModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                trailingContent = {
                    Switch(
                        checked = settings.enableSuggestion,
                        onCheckedChange = {
                            vm.updateSettings(settings.copy(enableSuggestion = it))
                        }
                    )
                },
            )
            if (settings.enableSuggestion) {
                item(
                    onClick = { state.open() },
                    headlineContent = { Text(title) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = state.currentModel?.displayName
                                    ?: stringResource(R.string.model_list_select_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentModel != null) {
                                IconButton(
                                    onClick = { vm.updateSettings(settings.copy(suggestionModelId = null)) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.setting_model_page_suggestion_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) })
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onClear != null && state.currentModel != null) {
                            IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                                Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
