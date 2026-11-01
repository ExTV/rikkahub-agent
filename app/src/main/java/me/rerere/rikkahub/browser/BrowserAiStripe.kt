package me.rerere.rikkahub.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CancelCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R

/**
 * Bottom AI-status stripe. Hidden entirely when the AI hasn't recorded any actions yet
 * so the WebView gets the full bottom of the screen for the user's manual browsing.
 *
 * Redesign (this pass): the trail is now a task-scoped feed of [BrowserAiAction] entries
 * rather than raw English strings — outcome-aware (RUNNING/OK/FAILED), localized at
 * render time via [actionSentence], and live: the headline shows a spinner while the
 * newest entry is RUNNING and a compact Stop button while [BrowserController.taskActiveFlow]
 * is true.
 */
@Composable
fun BrowserAiStripe(onStopAi: () -> Unit) {
    val actions by BrowserController.recentActionsFlow().collectAsStateWithLifecycle()
    val taskActive by BrowserController.taskActiveFlow().collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    if (actions.isEmpty()) return

    // Ticks every 10s so the relative-time badges below stay fresh instead of freezing at
    // the value computed when the actions list last changed.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }

    val newest = actions.first()
    val newestSentence = actionSentence(newest)
    val headlineText = stringResource(R.string.browser_ai_stripe_active, newestSentence)
    val isRunning = newest.outcome == BrowserAiActionOutcome.RUNNING
    // DONE already implies the task window cleared (browser_done); hide Stop there even
    // if taskActive hasn't flipped yet on this recomposition.
    val showStop = taskActive && newest.kind != BrowserAiActionKind.DONE

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = headlineText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showStop) {
                    TextButton(onClick = onStopAi) {
                        Text(
                            text = stringResource(R.string.browser_ai_stripe_stop),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    text = if (expanded) {
                        stringResource(R.string.browser_ai_stripe_collapse)
                    } else {
                        stringResource(R.string.browser_ai_stripe_expand)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded && actions.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(actions, key = { it.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ActionOutcomeIcon(
                                outcome = entry.outcome,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(14.dp),
                            )
                            Text(
                                text = "${entry.step}. ${actionSentence(entry)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatRelativeActionTime(entry.atMs, nowMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.size(8.dp)) }
                }
            }
        }
    }
}

/** Small outcome glyph for the expanded list — a spinner while RUNNING, check/cross otherwise. */
@Composable
private fun ActionOutcomeIcon(outcome: BrowserAiActionOutcome, modifier: Modifier = Modifier) {
    when (outcome) {
        BrowserAiActionOutcome.RUNNING -> CircularProgressIndicator(
            modifier = modifier,
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        BrowserAiActionOutcome.OK -> Icon(
            imageVector = HugeIcons.CheckmarkCircle01,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.primary,
        )
        BrowserAiActionOutcome.FAILED -> Icon(
            imageVector = HugeIcons.CancelCircle,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Localize a [BrowserAiAction] into a human sentence. [BrowserAiAction.detail] is truncated
 * to 80 chars before it's spliced into the template (the plan's render-time cap — the
 * underlying stored detail is untouched so a longer detail isn't lost, just not fully shown).
 * FAILED entries are additionally wrapped in [R.string.browser_ai_action_failed].
 */
@Composable
private fun actionSentence(action: BrowserAiAction): String {
    val detail = action.detail?.take(80).orEmpty()
    val base = when (action.kind) {
        BrowserAiActionKind.OPEN -> stringResource(R.string.browser_ai_action_open, detail)
        BrowserAiActionKind.CLICK -> stringResource(R.string.browser_ai_action_click, detail)
        BrowserAiActionKind.TYPE -> stringResource(R.string.browser_ai_action_type, detail)
        BrowserAiActionKind.SCROLL -> stringResource(R.string.browser_ai_action_scroll, detail)
        BrowserAiActionKind.SUBMIT -> stringResource(R.string.browser_ai_action_submit, detail)
        BrowserAiActionKind.SELECT -> stringResource(R.string.browser_ai_action_select, detail)
        BrowserAiActionKind.KEY -> stringResource(R.string.browser_ai_action_key, detail)
        BrowserAiActionKind.JS -> stringResource(R.string.browser_ai_action_js)
        BrowserAiActionKind.SCREENSHOT -> stringResource(R.string.browser_ai_action_screenshot)
        BrowserAiActionKind.READ -> stringResource(R.string.browser_ai_action_read, detail)
        BrowserAiActionKind.BACK -> stringResource(R.string.browser_ai_action_back)
        BrowserAiActionKind.FORWARD -> stringResource(R.string.browser_ai_action_forward)
        BrowserAiActionKind.DONE -> stringResource(R.string.browser_ai_action_done, detail)
        BrowserAiActionKind.STOPPED -> stringResource(R.string.browser_ai_action_stopped)
    }
    return if (action.outcome == BrowserAiActionOutcome.FAILED) {
        stringResource(R.string.browser_ai_action_failed, base)
    } else {
        base
    }
}

/**
 * Compact, unlocalized relative-time badge ("12s", "3m", "1h") for an action's [atMs] against
 * [nowMs] (defaults to now; parameterised so it's a pure function for unit tests). Pure
 * top-level fun — no Compose/Android dependency — so it stays JVM-testable.
 */
internal fun formatRelativeActionTime(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val deltaSec = ((nowMs - atMs) / 1000).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> "${deltaSec}s"
        deltaSec < 3600 -> "${deltaSec / 60}m"
        else -> "${deltaSec / 3600}h"
    }
}
