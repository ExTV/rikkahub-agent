package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.subagent.SubAgentProfile
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * #36: named sub-agent profiles - a name, description, custom system prompt and model,
 * so `subagent_dispatch` can be given a profile NAME instead of a model uuid. List/add/edit/
 * delete, mirroring the Lorebook/ModeInjection tabs in PromptPage. Model selection reuses the
 * shared [ModelSelector] component rather than a new picker.
 */
@Composable
fun SettingSubAgentsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profiles = settings.subAgents
    var expanded by rememberSaveable { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val editState = useEditState<SubAgentProfile> { edited ->
        val index = profiles.indexOfFirst { it.id == edited.id }
        val updated = if (index >= 0) {
            profiles.toMutableList().apply { set(index, edited) }
        } else {
            profiles + edited
        }
        vm.updateSettings(settings.copy(subAgents = updated))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_sub_agents_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .floatingToolbarVerticalNestedScroll(
                        expanded = expanded,
                        onExpand = { expanded = true },
                        onCollapse = { expanded = false },
                    ),
                contentPadding = innerPadding + PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (profiles.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.setting_sub_agents_page_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                } else {
                    items(profiles, key = { it.id }) { profile ->
                        SubAgentProfileCard(
                            profile = profile,
                            onEdit = { editState.open(profile) },
                            onDelete = { vm.updateSettings(settings.copy(subAgents = profiles - profile)) },
                        )
                    }
                }
            }

            HorizontalFloatingToolbar(
                expanded = expanded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset),
            ) {
                Button(onClick = { editState.open(SubAgentProfile()) }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Text(stringResource(R.string.setting_sub_agents_page_add))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            SubAgentProfileEditSheet(
                profile = state,
                providers = settings.providers,
                existingProfiles = profiles,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it },
            )
        }
    }
}

@Composable
private fun SubAgentProfileCard(
    profile: SubAgentProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.setting_sub_agents_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = profile.name.ifEmpty { stringResource(R.string.setting_sub_agents_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.description.isNotEmpty()) {
                        Text(
                            text = profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!profile.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.setting_sub_agents_page_disabled))
                        }
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.setting_sub_agents_page_edit))
                }
            }
        }
    }
}

@Composable
private fun SubAgentProfileEditSheet(
    profile: SubAgentProfile,
    providers: List<ProviderSetting>,
    existingProfiles: List<SubAgentProfile>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (SubAgentProfile) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    // Resolution matches profiles by name case-insensitively (SubAgentProfileResolver); saving
    // a second profile with a name that only differs by case would make dispatch ambiguous, so
    // reject it here rather than letting the ambiguity reach the resolver at dispatch time.
    val nameDuplicate = existingProfiles.any {
        it.id != profile.id && it.name.isNotBlank() && it.name.equals(profile.name, ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_sub_agents_page_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { onEdit(profile.copy(name = it)) },
                    label = { Text(stringResource(R.string.setting_sub_agents_page_name)) },
                    singleLine = true,
                    isError = nameDuplicate,
                    supportingText = if (nameDuplicate) {
                        { Text(stringResource(R.string.setting_sub_agents_page_name_duplicate)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = profile.description,
                    onValueChange = { onEdit(profile.copy(description = it)) },
                    label = { Text(stringResource(R.string.setting_sub_agents_page_description)) },
                    supportingText = { Text(stringResource(R.string.setting_sub_agents_page_description_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                FormItem(
                    label = { Text(stringResource(R.string.setting_sub_agents_page_enabled)) },
                    tail = {
                        Switch(
                            checked = profile.enabled,
                            onCheckedChange = { onEdit(profile.copy(enabled = it)) },
                        )
                    },
                )

                FormItem(
                    label = { Text(stringResource(R.string.setting_sub_agents_page_model)) },
                    description = { Text(stringResource(R.string.setting_sub_agents_page_model_desc)) },
                    content = {
                        ModelSelector(
                            modelId = profile.modelId,
                            providers = providers,
                            type = ModelType.CHAT,
                            allowClear = true,
                            onSelect = { model ->
                                // ModelSelector's clear button calls onSelect(Model()), whose
                                // default modelId is "" - no real model ever has a blank
                                // provider model id, so that's the clear signal.
                                onEdit(profile.copy(modelId = model.id.takeIf { model.modelId.isNotBlank() }))
                            },
                        )
                    },
                )

                OutlinedTextField(
                    value = profile.systemPrompt,
                    onValueChange = { onEdit(profile.copy(systemPrompt = it)) },
                    label = { Text(stringResource(R.string.setting_sub_agents_page_system_prompt)) },
                    supportingText = { Text(stringResource(R.string.setting_sub_agents_page_system_prompt_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    minLines = 4,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setting_sub_agents_page_cancel))
                }
                TextButton(onClick = onConfirm, enabled = profile.name.isNotBlank() && !nameDuplicate) {
                    Text(stringResource(R.string.setting_sub_agents_page_confirm))
                }
            }
        }
    }
}
