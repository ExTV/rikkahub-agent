package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.grok.GrokAccount
import me.rerere.rikkahub.data.grok.GrokAccountRepository
import me.rerere.rikkahub.data.grok.GrokOAuthManager
import me.rerere.rikkahub.data.grok.GrokOAuthStatus
import me.rerere.rikkahub.data.grok.GrokTokenStatus
import me.rerere.rikkahub.data.grok.GrokUsageWindow
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun GrokProviderConfigure(
    provider: ProviderSetting.Grok,
    onEdit: (ProviderSetting.Grok) -> Unit,
) {
    val repository = koinInject<GrokAccountRepository>()
    val oauthManager = koinInject<GrokOAuthManager>()
    val providerManager = koinInject<ProviderManager>()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val oauthStatus by oauthManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val canEnable = accounts.any { it.enabled && it.tokenStatus != GrokTokenStatus.INVALID }

    LaunchedEffect(oauthStatus) {
        when (val status = oauthStatus) {
            is GrokOAuthStatus.Success -> {
                runCatching {
                    providerManager.getProviderByType(provider).listModels(provider)
                }.onSuccess { models ->
                    onEdit(provider.copy(models = mergeGrokModels(provider.models, models)))
                }
                toaster.show(
                    context.getString(R.string.grok_oauth_success),
                    type = ToastType.Success,
                )
                oauthManager.consumeResult()
            }

            is GrokOAuthStatus.Error -> {
                toaster.show(status.message, type = ToastType.Error)
                oauthManager.consumeResult()
            }

            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = provider.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.grok_provider_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = oauthManager::startLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = oauthStatus !is GrokOAuthStatus.Starting &&
                oauthStatus !is GrokOAuthStatus.AwaitingApproval,
        ) {
            Text(stringResource(R.string.grok_sign_in))
        }

        when (val status = oauthStatus) {
            is GrokOAuthStatus.AwaitingApproval -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.grok_enter_code_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = status.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = status.verificationUri,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = oauthManager::cancel) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            else -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.grok_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (canEnable) {
                        stringResource(R.string.grok_round_robin_description)
                    } else {
                        stringResource(R.string.grok_sign_in_required)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copy(enabled = it)) },
                enabled = provider.enabled || canEnable,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.grok_accounts_count, accounts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = { scope.launch { repository.refreshAll() } },
                enabled = accounts.isNotEmpty(),
            ) {
                Text(stringResource(R.string.grok_check_status))
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.grok_no_accounts),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            accounts.forEach { account ->
                GrokAccountCard(
                    account = account,
                    onEnabledChange = { enabled ->
                        scope.launch { repository.setEnabled(account.id, enabled) }
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching { repository.refreshAccount(account.id) }
                                .onFailure {
                                    toaster.show(
                                        it.message ?: context.getString(R.string.grok_refresh_failed),
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    },
                    onReauthenticate = oauthManager::startLogin,
                    onDelete = {
                        scope.launch {
                            repository.delete(account.id)
                            if (repository.accounts.value.isEmpty() && provider.enabled) {
                                onEdit(provider.copy(enabled = false))
                            }
                        }
                    },
                )
            }
        }
    }
}

internal fun mergeGrokModels(existing: List<Model>, refreshed: List<Model>): List<Model> {
    val refreshedByModelId = refreshed.associateBy(Model::modelId)
    val merged = existing.map { model ->
        refreshedByModelId[model.modelId]?.let { refreshedModel ->
            model.copy(
                inputModalities = refreshedModel.inputModalities,
                outputModalities = refreshedModel.outputModalities,
                abilities = refreshedModel.abilities,
            )
        } ?: model
    }
    val existingModelIds = existing.mapTo(mutableSetOf(), Model::modelId)
    return merged + refreshed.filterNot { it.modelId in existingModelIds }
}

@Composable
private fun GrokAccountCard(
    account: GrokAccount,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReauthenticate: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.grok_remove_account_title)) },
            text = { Text(stringResource(R.string.grok_remove_account_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text(
                        stringResource(R.string.grok_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    if (account.email.isNotBlank()) {
                        Text(
                            account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = account.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Text(
                text = when (account.tokenStatus) {
                    GrokTokenStatus.AVAILABLE -> stringResource(R.string.grok_token_available)
                    GrokTokenStatus.EXPIRED -> stringResource(R.string.grok_token_expired)
                    GrokTokenStatus.INVALID -> stringResource(R.string.grok_token_unavailable)
                    GrokTokenStatus.UNKNOWN -> stringResource(R.string.grok_token_not_checked)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (account.tokenStatus) {
                    GrokTokenStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                    GrokTokenStatus.INVALID, GrokTokenStatus.EXPIRED -> MaterialTheme.colorScheme.error
                    GrokTokenStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            account.usage?.planName?.let { plan ->
                Text(
                    text = stringResource(R.string.grok_plan, plan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            account.usage?.weekly?.let { GrokUsageRow(window = it) }
            account.usage?.let { usage ->
                Text(
                    text = if (usage.onDemandCap > 0) {
                        stringResource(R.string.grok_on_demand_cap, formatCap(usage.onDemandCap))
                    } else {
                        stringResource(R.string.grok_on_demand_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.grok_refresh))
                }
                TextButton(onClick = onReauthenticate) {
                    Text(stringResource(R.string.grok_reauthenticate))
                }
                TextButton(onClick = { showDeleteConfirmation = true }) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun GrokUsageRow(window: GrokUsageWindow) {
    val remaining = (100.0 - window.usedPercent).coerceIn(0.0, 100.0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.grok_weekly_limit), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.grok_percent_remaining, remaining.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { (remaining / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        window.resetsAt?.let { epochSeconds ->
            Text(
                text = stringResource(
                    R.string.grok_resets_at,
                    GROK_RESET_FORMAT.format(Instant.ofEpochSecond(epochSeconds)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCap(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private val GROK_RESET_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
