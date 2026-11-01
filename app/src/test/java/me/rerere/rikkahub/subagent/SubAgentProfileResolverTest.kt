package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #36: subagent_dispatch's `agent` parameter resolves a named [SubAgentProfile] by
 * name. Pins the resolver's contract: exact / case-insensitive match, loud failure (never
 * silent parent-model inheritance) on an unknown or disabled name, and that disabled profiles
 * are invisible to resolution - the same discipline [SubAgentModelResolver] applies to
 * `model_id`.
 */
class SubAgentProfileResolverTest {

    private val researcher = SubAgentProfile(
        name = "Researcher",
        description = "Looks things up.",
        systemPrompt = "You are a careful researcher.",
        enabled = true,
    )
    private val disabledProfile = SubAgentProfile(
        name = "Retired",
        description = "No longer used.",
        systemPrompt = "You are retired.",
        enabled = false,
    )
    private val profiles = listOf(researcher, disabledProfile)

    @Test fun `null agent is not requested`() {
        val result = SubAgentProfileResolver.resolve(null, profiles)
        assertTrue(result is SubAgentProfileResolver.Result.NotRequested)
    }

    @Test fun `blank agent is not requested`() {
        val result = SubAgentProfileResolver.resolve("   ", profiles)
        assertTrue(result is SubAgentProfileResolver.Result.NotRequested)
    }

    @Test fun `resolves by exact name`() {
        val result = SubAgentProfileResolver.resolve("Researcher", profiles)
        val resolved = result as SubAgentProfileResolver.Result.Resolved
        assertEquals(researcher.id, resolved.profile.id)
    }

    @Test fun `resolves case-insensitively`() {
        val result = SubAgentProfileResolver.resolve("rEsEaRcHeR", profiles)
        val resolved = result as SubAgentProfileResolver.Result.Resolved
        assertEquals(researcher.id, resolved.profile.id)
    }

    @Test fun `unknown name fails loudly and lists valid names`() {
        val result = SubAgentProfileResolver.resolve("no-such-agent", profiles)
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("no-such-agent"))
        assertTrue(failed.message.contains("Researcher"))
        // A silent fallback to the parent model would look like a Resolved/NotRequested
        // result here - it must not.
    }

    @Test fun `disabled profile is not resolvable and is absent from the error's valid names`() {
        val result = SubAgentProfileResolver.resolve("Retired", profiles)
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("Retired"))
        assertTrue(!failed.message.contains("Researcher") || failed.message.indexOf("Retired") >= 0)
        // Precisely: the available-names list must not include the disabled profile's name.
        val availableSection = failed.message.substringAfter("Available:", missingDelimiterValue = "")
        assertTrue(!availableSection.contains("Retired"))
    }

    @Test fun `ambiguous name fails loudly and names the duplicate`() {
        val secondResearcher = SubAgentProfile(
            name = "researcher",
            description = "A second profile with the same name, different case.",
            systemPrompt = "You are also a researcher.",
            enabled = true,
        )
        val result = SubAgentProfileResolver.resolve("Researcher", listOf(researcher, secondResearcher))
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("Researcher"))
        assertTrue(failed.message.contains("researcher"))
        // An ambiguous name must never quietly resolve to whichever profile came first.
    }

    @Test fun `empty profile list fails with a message noting none are configured`() {
        val result = SubAgentProfileResolver.resolve("anything", emptyList())
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("no profiles are configured"))
    }

    @Test fun `enabledProfiles filters out disabled profiles`() {
        assertEquals(listOf(researcher), SubAgentProfileResolver.enabledProfiles(profiles))
    }

    // resolveSubAgentModel: the model_id vs agent precedence rule.

    @Test fun `resolveSubAgentModel - model_id wins over the profile's model when both resolve`() {
        val explicitModelId = Uuid.random()
        val profileModelId = Uuid.random()
        val profile = researcher.copy(modelId = profileModelId)

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Resolved(explicitModelId),
            profile,
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(explicitModelId), combined)
    }

    @Test fun `resolveSubAgentModel - a failed model_id is not papered over by the profile's model`() {
        val profile = researcher.copy(modelId = Uuid.random())
        val failure = SubAgentModelResolver.Result.Failed("model_id \"bogus\" did not match any model")

        val combined = resolveSubAgentModel(failure, profile)

        assertEquals(failure, combined)
    }

    @Test fun `resolveSubAgentModel - falls back to the profile's model when model_id was not given`() {
        val profileModelId = Uuid.random()
        val profile = researcher.copy(modelId = profileModelId)

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, profile)

        assertEquals(SubAgentModelResolver.Result.Resolved(profileModelId), combined)
    }

    @Test fun `resolveSubAgentModel - no agent and no model_id leaves the tool working exactly as before`() {
        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, profile = null)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }

    @Test fun `resolveSubAgentModel - a profile with no model set also inherits, exactly as before`() {
        val profileWithNoModel = researcher.copy(modelId = null)

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, profileWithNoModel)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }
}
