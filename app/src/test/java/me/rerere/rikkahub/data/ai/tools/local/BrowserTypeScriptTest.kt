package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the JS payload browser_type dispatches ([buildTypeScript]). Pure string
 * builder, unit-testable without a WebView — same rationale as
 * [me.rerere.rikkahub.browser.BrowserWaitForPredicateTest]'s sibling for browser_wait_for.
 *
 * The fix under test: React (and similar frameworks) install their own setter on the
 * DOM node instance to track "last known value", so a plain `el.value = ...` assignment
 * is invisible to them — the rendered input silently keeps its old value even though the
 * tool reports success. [buildTypeScript] must therefore route the write through the
 * *native* setter on the element's prototype (bypassing the instance-level tracker) and
 * only fall back to the plain assignment when no such setter exists.
 *
 * This only pins the JS shape; whether it actually fixes a live React input is a
 * device-only fact (WebView execution isn't available on the JVM).
 */
class BrowserTypeScriptTest {

    @Test
    fun `uses the native prototype value setter as the primary path`() {
        val js = buildTypeScript("#q", "hello", clear = true)
        assertTrue("reads the prototype's value descriptor",
            js.contains("Object.getOwnPropertyDescriptor(proto, 'value')"))
        assertTrue("invokes the native setter via .call so `this` is the real element",
            js.contains("desc.set.call(node, value)"))
    }

    @Test
    fun `falls back to plain assignment when no native setter is found`() {
        val js = buildTypeScript("#q", "hello", clear = true)
        assertTrue("keeps the simple path as a fallback", js.contains("el.value = next"))
        // The fallback must only fire when setNativeValue() reports failure.
        assertTrue(js.contains("if (!setNativeValue(el, next))"))
    }

    @Test
    fun `dispatches both input and change events after the write`() {
        val js = buildTypeScript("#q", "hello", clear = true)
        assertTrue(js.contains("new Event('input', {bubbles:true})"))
        assertTrue(js.contains("new Event('change', {bubbles:true})"))
    }

    @Test
    fun `clear true drops the prior value before appending`() {
        val js = buildTypeScript("#q", "hi", clear = true)
        assertTrue("clear flag threads through to the ternary", js.contains("(true ? '' : (el.value || ''))"))
    }

    @Test
    fun `clear false appends onto the existing value`() {
        val js = buildTypeScript("#q", "hi", clear = false)
        assertTrue(js.contains("(false ? '' : (el.value || ''))"))
    }

    @Test
    fun `contenteditable path is preserved for non-value elements`() {
        val js = buildTypeScript(".editor", "note", clear = true)
        assertTrue(js.contains("el.isContentEditable"))
        assertTrue(js.contains("el.textContent"))
    }

    @Test
    fun `selector and text are JSON-escaped into the payload`() {
        val js = buildTypeScript("""input[name="q"]""", "quote\" and \\backslash", clear = false)
        // jsString round-trips through JsonPrimitive — escaped quotes/backslashes must not
        // break out of the JS string literal.
        assertTrue(js.contains("""querySelector("input[name=\"q\"]")"""))
        assertTrue(js.contains("quote\\\" and \\\\backslash"))
    }

    @Test
    fun `reports selector_not_found when the element is missing`() {
        val js = buildTypeScript("#missing", "x", clear = true)
        assertTrue(js.contains("selector_not_found"))
    }

    @Test
    fun `wraps execution so a thrown JS error surfaces as js_failed`() {
        val js = buildTypeScript("#q", "x", clear = true)
        assertTrue("execution is guarded by a try/catch shell", js.contains("catch(e)"))
        assertTrue(js.contains("error:'js_failed'"))
    }
}
