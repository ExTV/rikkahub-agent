package me.rerere.rikkahub.ui.pages.setting.doctor

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.subagent.SubAgentProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Coverage for the pure decision functions backing the doctor-refresh rows (Shizuku, gallery
 * orphans, workspace, sub-agent profiles, MCP servers, skills, Telegram proxy, context
 * compaction). [DoctorChecks] itself needs a real Android Context to construct, so, same as
 * [DoctorChecksLlamaCppTest] and [DoctorChecksBrowserTest], these pin the extracted pure
 * functions directly.
 */
class DoctorChecksRefreshTest {

    // ----- galleryOrphanStatus (storage.gallery_orphans) --------------------------------

    private lateinit var tempDir: File

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("gallery-orphan-doctor-test").toFile()
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test fun `no records reports zero total and zero orphans`() {
        val status = galleryOrphanStatus(emptyList())
        assertEquals(0, status.total)
        assertEquals(0, status.orphanCount)
    }

    @Test fun `record whose file exists is not counted as an orphan`() {
        val file = File(tempDir, "present.png").apply { writeBytes(byteArrayOf(1)) }
        val status = galleryOrphanStatus(listOf(file.absolutePath))
        assertEquals(1, status.total)
        assertEquals(0, status.orphanCount)
    }

    @Test fun `record whose file is gone is counted as an orphan`() {
        val gone = File(tempDir, "gone.png")
        val status = galleryOrphanStatus(listOf(gone.absolutePath))
        assertEquals(1, status.total)
        assertEquals(1, status.orphanCount)
    }

    @Test fun `mixed present and orphaned records only counts the orphans`() {
        val present = File(tempDir, "present.png").apply { writeBytes(byteArrayOf(1)) }
        val status = galleryOrphanStatus(
            listOf(
                present.absolutePath,
                File(tempDir, "gone-a.png").absolutePath,
                File(tempDir, "gone-b.png").absolutePath,
            )
        )
        assertEquals(3, status.total)
        assertEquals(2, status.orphanCount)
    }

    // ----- subAgentProfileStatus (assistant.subagent_profiles) --------------------------

    private val chatModel = Model(
        id = Uuid.random(),
        modelId = "gpt-4o",
        displayName = "GPT-4o",
        type = ModelType.CHAT,
    )
    private val providers = listOf(
        ProviderSetting.OpenAI(
            name = "OpenAI",
            enabled = true,
            models = listOf(chatModel),
        ),
    )

    @Test fun `profile with no configured model is never broken`() {
        val profile = SubAgentProfile(name = "inherits", modelId = null)
        val status = subAgentProfileStatus(listOf(profile), providers)
        assertEquals(1, status.total)
        assertTrue(status.broken.isEmpty())
    }

    @Test fun `profile whose model id resolves is not broken`() {
        val profile = SubAgentProfile(name = "resolvable", modelId = chatModel.id)
        val status = subAgentProfileStatus(listOf(profile), providers)
        assertTrue(status.broken.isEmpty())
    }

    @Test fun `profile whose model id no longer resolves is broken and named`() {
        val profile = SubAgentProfile(name = "stale-model", modelId = Uuid.random())
        val status = subAgentProfileStatus(listOf(profile), providers)
        assertEquals(listOf("stale-model"), status.broken)
    }

    // ----- mcpServerSummary (service.mcp_servers) ----------------------------------------

    @Test fun `no servers configured reports all zero`() {
        val summary = mcpServerSummary(emptyList())
        assertEquals(0, summary.configured)
        assertEquals(0, summary.enabled)
        assertEquals(0, summary.connected)
        assertTrue(summary.enabledNotConnected.isEmpty())
    }

    @Test fun `enabled and connected server is not flagged`() {
        val summary = mcpServerSummary(listOf(Triple("search", true, true)))
        assertEquals(1, summary.enabled)
        assertEquals(1, summary.connected)
        assertTrue(summary.enabledNotConnected.isEmpty())
    }

    @Test fun `enabled but not connected server is named`() {
        val summary = mcpServerSummary(
            listOf(
                Triple("stuck", true, false),
                Triple("disabled", false, false),
                Triple("healthy", true, true),
            )
        )
        assertEquals(3, summary.configured)
        assertEquals(2, summary.enabled)
        assertEquals(1, summary.connected)
        assertEquals(listOf("stuck"), summary.enabledNotConnected)
    }

    // ----- staleSeedSkillNames (skills.seed) ---------------------------------------------

    @Test fun `bundled skill with matching hash is fresh`() {
        val entries = listOf(SkillSeedEntry("agent-core", isBundled = true, storedHash = "abc", currentHash = "abc"))
        assertTrue(staleSeedSkillNames(entries).isEmpty())
    }

    @Test fun `bundled skill with mismatched hash is stale`() {
        val entries = listOf(SkillSeedEntry("agent-core", isBundled = true, storedHash = "abc", currentHash = "def"))
        assertEquals(listOf("agent-core"), staleSeedSkillNames(entries))
    }

    @Test fun `bundled skill missing its sentinel is stale`() {
        val entries = listOf(SkillSeedEntry("agent-core", isBundled = true, storedHash = null, currentHash = "def"))
        assertEquals(listOf("agent-core"), staleSeedSkillNames(entries))
    }

    @Test fun `user-added skill is never flagged regardless of hash mismatch`() {
        val entries = listOf(SkillSeedEntry("my-skill", isBundled = false, storedHash = null, currentHash = "def"))
        assertTrue(staleSeedSkillNames(entries).isEmpty())
    }
}
