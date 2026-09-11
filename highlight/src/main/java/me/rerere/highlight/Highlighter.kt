package me.rerere.highlight

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

/**
 * Below this length, highlighting runs synchronously during composition - it's cheap enough that
 * doing it off-thread would just add a frame of plain text for no benefit.
 */
private const val SYNC_HIGHLIGHT_LENGTH = 4096

/**
 * Hard cap for pathologically large blocks (hundreds of KB) where even off-thread highlighting
 * isn't worth the CPU. Deliberately far above what a normal streamed code block reaches, and
 * applies the same way regardless of whether the message is still streaming or complete.
 */
private const val MAX_HIGHLIGHT_LENGTH = 200_000

/**
 * How a code block of [codeLength] characters should be highlighted.
 *
 * A fixed length cap that silently falls back to plain text as soon as a block crosses it would
 * make ordinary, still-growing code blocks (a few hundred lines) go from highlighted while
 * streaming to unhighlighted once they settle past the cutoff. Only [Skip] drops colors; medium
 * and large blocks still get highlighted, just off the main thread so they don't jank scrolling.
 */
internal sealed interface HighlightStrategy {
    data object Synchronous : HighlightStrategy
    data object Asynchronous : HighlightStrategy
    data object Skip : HighlightStrategy
}

internal fun highlightStrategyFor(codeLength: Int): HighlightStrategy = when {
    codeLength > MAX_HIGHLIGHT_LENGTH -> HighlightStrategy.Skip
    codeLength > SYNC_HIGHLIGHT_LENGTH -> HighlightStrategy.Asynchronous
    else -> HighlightStrategy.Synchronous
}

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = engine.supports(language)
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalCodeHighlighter.current
    fun highlight() = buildAnnotatedString {
        highlighter.highlight(code, language).forEach { token ->
            buildHighlightText(token, colors)
        }
    }

    val annotatedString = when (highlightStrategyFor(code.length)) {
        HighlightStrategy.Skip -> remember(code) { AnnotatedString(code) }
        HighlightStrategy.Synchronous -> remember(code, language, colors, highlighter) { highlight() }
        HighlightStrategy.Asynchronous -> {
            val plain = remember(code) { AnnotatedString(code) }
            produceState(initialValue = plain, code, language, colors, highlighter) {
                // produceState's initialValue is only honored the very first time this call site
                // enters this branch (it backs a key-less remembered state), so on later
                // recompositions - e.g. code growing while still streaming - the state would stay
                // pinned to the previous, shorter highlight result until this coroutine's
                // withContext wins the race against the next code change. Set it to the current
                // plain text up front so the displayed text always tracks the live, growing code
                // while the highlighted version catches up in the background.
                value = plain
                value = withContext(Dispatchers.Default) { highlight() }
            }.value
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
