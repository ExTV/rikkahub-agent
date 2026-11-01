package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/**
 * Pure helpers for ChatService.generateTitle. Extracted so a title always ends up set,
 * even when the fast model is unavailable, fails, or returns nothing usable.
 */
internal const val TITLE_FALLBACK_MAX_CHARS = 60

/** First non-blank line of the first USER message, trimmed and capped; null when none. */
internal fun titleFallbackFrom(messages: List<UIMessage>): String? {
    val firstUserMessage = messages.firstOrNull { it.role == MessageRole.USER } ?: return null
    val firstNonBlankLine = firstUserMessage.toText()
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return null
    return firstNonBlankLine
        .replace(Regex("\\s+"), " ")
        .take(TITLE_FALLBACK_MAX_CHARS)
}

/** Background generation must not clobber a title the user or an earlier run already set. */
internal fun shouldWriteTitle(force: Boolean, storedTitle: String): Boolean = force || storedTitle.isBlank()
