package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine

@Composable
fun ToolsPickerButton(
    enableSearch: Boolean,
    enableImageGen: Boolean = false,
    settings: Settings,
    modifier: Modifier = Modifier,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onToggleImageGen: (Boolean) -> Unit = {},
    selectedAspectRatio: String = "auto",
    onSelectAspectRatio: (String) -> Unit = {},
    model: Model?,
) {
    var showSheet by remember { mutableStateOf(false) }
    val isAnyToolActive = enableSearch || enableImageGen || model?.tools?.contains(BuiltInTools.Search) == true

    ToggleSurface(
        modifier = modifier,
        checked = isAnyToolActive,
        onClick = {
            showSheet = true
        }
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = HugeIcons.Tools,
                contentDescription = "Tools",
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            var selectingSearchProvider by remember { mutableStateOf(false) }

            if (selectingSearchProvider) {
                SearchProviderPickerView(
                    settings = settings,
                    onUpdateSearchService = { index ->
                        onUpdateSearchService(index)
                        selectingSearchProvider = false
                    },
                    onBack = { selectingSearchProvider = false }
                )
            } else {
                ToolsMainSheetContent(
                    enableSearch = enableSearch,
                    enableImageGen = enableImageGen,
                    settings = settings,
                    model = model,
                    selectedAspectRatio = selectedAspectRatio,
                    onUpdateSearchMode = onUpdateSearchMode,
                    onToggleImageGen = onToggleImageGen,
                    onSelectAspectRatio = onSelectAspectRatio,
                    onSelectSearchProvider = { selectingSearchProvider = true },
                    onDismiss = { showSheet = false }
                )
            }
        }
    }
}

@Composable
private fun ToolsMainSheetContent(
    enableSearch: Boolean,
    enableImageGen: Boolean,
    settings: Settings,
    model: Model?,
    selectedAspectRatio: String,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onToggleImageGen: (Boolean) -> Unit,
    onSelectAspectRatio: (String) -> Unit,
    onSelectSearchProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nav = LocalNavController.current
    val currentSearchService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    val provider = model?.findProvider(settings.providers)
    val supportsBuiltInSearch = provider is ProviderSetting.Google ||
        provider is ProviderSetting.OpenAI && provider.useResponseApi
    val hasBuiltInSearchEnabled = model?.tools?.contains(BuiltInTools.Search) == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Assistant Tools",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = {
                onDismiss()
                nav.navigate(Screen.SettingSearch)
            }) {
                Icon(HugeIcons.Settings03, contentDescription = null)
            }
        }

        // Section 1: Web Search
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(HugeIcons.GlobalSearch, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Web Search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (enableSearch) "Active — ${currentSearchService?.displayName ?: "Default"}" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = enableSearch,
                        onCheckedChange = { checked ->
                            onUpdateSearchMode(if (checked) SearchMode.LOCAL else SearchMode.OFF)
                        }
                    )
                }

                if (enableSearch) {
                    TextButton(
                        onClick = onSelectSearchProvider,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Change Provider (${currentSearchService?.displayName ?: "Default"})")
                        Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Section 2: Studio Image Generation
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(HugeIcons.Image02, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Generate Image", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (enableImageGen) "Aspect Ratio: $selectedAspectRatio" else "Autonomous studio image generator",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = enableImageGen,
                        onCheckedChange = onToggleImageGen
                    )
                }

                if (enableImageGen) {
                    Text("Aspect Ratio Override:", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val ratios = listOf("auto", "1:1", "16:9", "9:16", "2:3", "3:2")
                        ratios.forEach { ratio ->
                            FilterChip(
                                selected = selectedAspectRatio == ratio,
                                onClick = { onSelectAspectRatio(ratio) },
                                label = { Text(ratio) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchProviderPickerView(
    settings: Settings,
    onUpdateSearchService: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.search_picker_select_provider),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            columns = GridCells.Adaptive(150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(settings.searchServices, key = { _, service -> service.id }) { index, service ->
                val containerColor = animateColorAsState(
                    if (settings.searchServiceSelected == index) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
                val textColor = animateColorAsState(
                    if (settings.searchServiceSelected == index) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = containerColor.value,
                        contentColor = textColor.value,
                    ),
                    onClick = {
                        onUpdateSearchService(index)
                    },
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AutoAIIcon(
                            name = service.displayName,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = service.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            SearchAbilityTagLine(
                                options = service,
                                modifier = Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}
