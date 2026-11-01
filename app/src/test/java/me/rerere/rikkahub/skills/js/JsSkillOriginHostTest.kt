package me.rerere.rikkahub.skills.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSkillOriginHostTest {

    private val hostLabel = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    private fun label(host: String): String {
        assertTrue(
            "host must be a subdomain of the reserved asset domain: $host",
            host.endsWith(".appassets.androidplatform.net"),
        )
        return host.removeSuffix(".appassets.androidplatform.net")
    }

    @Test
    fun `deterministic for the same skill directory name`() {
        assertEquals(
            JsSkillRunner.skillOriginHost("qr-code"),
            JsSkillRunner.skillOriginHost("qr-code"),
        )
    }

    @Test
    fun `distinct hosts for distinct skill names`() {
        assertNotEquals(
            JsSkillRunner.skillOriginHost("qr-code"),
            JsSkillRunner.skillOriginHost("mood-tracker"),
        )
    }

    @Test
    fun `names that sanitize to the same slug still get distinct hosts`() {
        assertNotEquals(
            JsSkillRunner.skillOriginHost("My Skill"),
            JsSkillRunner.skillOriginHost("my-skill"),
        )
        assertNotEquals(
            JsSkillRunner.skillOriginHost("My Skill"),
            JsSkillRunner.skillOriginHost("my_skill"),
        )
    }

    @Test
    fun `host label is DNS-safe for hostile names`() {
        for (name in listOf(
            "qr-code",
            "My Skill",
            "----",
            "日本語スキル",
            "a".repeat(200),
            "..%2f..%2fescape",
            "UPPER_case.skill",
        )) {
            val l = label(JsSkillRunner.skillOriginHost(name))
            assertTrue("label not DNS-safe for '$name': $l", hostLabel.matches(l))
            assertTrue("label too long for '$name': $l", l.length <= 63)
        }
    }

    @Test
    fun `fully non-ascii name falls back to skill prefix but stays distinct by hash`() {
        val a = JsSkillRunner.skillOriginHost("日本語")
        val b = JsSkillRunner.skillOriginHost("中文技能")
        assertTrue(label(a).startsWith("skill-"))
        assertNotEquals(a, b)
    }
}
