package me.rerere.document

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EpubParser.parse() itself goes through org.xmlpull.v1.XmlPullParserFactory.newInstance(),
 * which throws "not mocked" under this module's plain JUnit testDebugUnitTest (no Robolectric,
 * no bundled kxml2/xmlpull implementation on the test classpath) regardless of whether the fix
 * below is applied, so it cannot be exercised end to end here. escapeUnresolvableEntities is
 * the actual fix for the entity-truncation defect (an unresolvable named HTML entity making
 * parser.next() throw partway through a chapter, silently dropping the rest of it) and is a
 * plain string function, so it is tested directly.
 */
class EpubParserTest {
    @Test
    fun `disarms a named HTML entity the XML parser cannot resolve`() {
        val input = "before&nbsp;middle&mdash;after"

        val result = escapeUnresolvableEntities(input)

        assertEquals("before&amp;nbsp;middle&amp;mdash;after", result)
    }

    @Test
    fun `disarms a bare ampersand that is not part of any entity`() {
        val input = "Fish & Chips"

        val result = escapeUnresolvableEntities(input)

        assertEquals("Fish &amp; Chips", result)
    }

    @Test
    fun `leaves the 5 predefined XML entities untouched`() {
        val input = "&amp;&lt;&gt;&quot;&apos;"

        val result = escapeUnresolvableEntities(input)

        assertEquals(input, result)
    }

    @Test
    fun `leaves numeric character references untouched`() {
        val input = "caf&#233; and &#x2014;"

        val result = escapeUnresolvableEntities(input)

        assertEquals(input, result)
    }
}
