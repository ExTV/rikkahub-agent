package me.rerere.rikkahub.subagent

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #28: subagent_dispatch's model_id used to be parsed, stored and echoed back but
 * never read - the sub-agent silently inherited the parent's model regardless of what was
 * passed. These tests pin the resolver's contract: uuid / modelId / displayName lookup, and
 * loud failure (not silent inheritance) on ambiguous or unknown input.
 */
class SubAgentModelResolverTest {

    private val gpt = Model(
        id = Uuid.random(),
        modelId = "gpt-4o",
        displayName = "GPT-4o",
        type = ModelType.CHAT,
    )
    private val claude = Model(
        id = Uuid.random(),
        modelId = "claude-3-5-sonnet",
        displayName = "Claude 3.5 Sonnet",
        type = ModelType.CHAT,
    )
    private val embedding = Model(
        id = Uuid.random(),
        modelId = "text-embedding-3",
        displayName = "Embedding",
        type = ModelType.EMBEDDING,
    )
    private val disabledProviderModel = Model(
        id = Uuid.random(),
        modelId = "disabled-model",
        displayName = "Disabled Model",
        type = ModelType.CHAT,
    )

    private val providers = listOf(
        ProviderSetting.OpenAI(
            name = "OpenAI",
            enabled = true,
            models = listOf(gpt, embedding),
        ),
        ProviderSetting.OpenAI(
            name = "Anthropic-ish",
            enabled = true,
            models = listOf(claude),
        ),
        ProviderSetting.OpenAI(
            name = "Disabled Provider",
            enabled = false,
            models = listOf(disabledProviderModel),
        ),
    )

    @Test fun `null model_id inherits parent model`() {
        val result = SubAgentModelResolver.resolve(null, providers)
        assertTrue(result is SubAgentModelResolver.Result.Inherit)
    }

    @Test fun `blank model_id inherits parent model`() {
        val result = SubAgentModelResolver.resolve("   ", providers)
        assertTrue(result is SubAgentModelResolver.Result.Inherit)
    }

    @Test fun `resolves by uuid`() {
        val result = SubAgentModelResolver.resolve(gpt.id.toString(), providers)
        val resolved = result as SubAgentModelResolver.Result.Resolved
        assertEquals(gpt.id, resolved.modelId)
    }

    @Test fun `resolves by modelId case-insensitively`() {
        val result = SubAgentModelResolver.resolve("Claude-3-5-Sonnet", providers)
        val resolved = result as SubAgentModelResolver.Result.Resolved
        assertEquals(claude.id, resolved.modelId)
    }

    @Test fun `resolves by displayName case-insensitively when it differs from modelId`() {
        // claude.displayName "Claude 3.5 Sonnet" differs from claude.modelId
        // "claude-3-5-sonnet", so this can only match via the displayName step.
        val result = SubAgentModelResolver.resolve("claude 3.5 sonnet", providers)
        val resolved = result as SubAgentModelResolver.Result.Resolved
        assertEquals(claude.id, resolved.modelId)
    }

    @Test fun `unknown model_id fails and lists available chat models`() {
        val result = SubAgentModelResolver.resolve("no-such-model", providers)
        val failed = result as SubAgentModelResolver.Result.Failed
        assertTrue(failed.message.contains("no-such-model"))
        assertTrue(failed.message.contains("GPT-4o (OpenAI)"))
        assertTrue(failed.message.contains("Claude 3.5 Sonnet (Anthropic-ish)"))
        // Neither the embedding model nor the disabled provider's model are valid options.
        assertTrue(!failed.message.contains("Embedding"))
        assertTrue(!failed.message.contains("Disabled Model"))
    }

    @Test fun `ambiguous display name fails and names every candidate`() {
        val dupe = Model(
            id = Uuid.random(),
            modelId = "dupe-id",
            displayName = "Shared Name",
            type = ModelType.CHAT,
        )
        val otherDupe = Model(
            id = Uuid.random(),
            modelId = "other-dupe-id",
            displayName = "Shared Name",
            type = ModelType.CHAT,
        )
        val ambiguousProviders = listOf(
            ProviderSetting.OpenAI(name = "A", enabled = true, models = listOf(dupe)),
            ProviderSetting.OpenAI(name = "B", enabled = true, models = listOf(otherDupe)),
        )
        val result = SubAgentModelResolver.resolve("Shared Name", ambiguousProviders)
        val failed = result as SubAgentModelResolver.Result.Failed
        assertTrue(failed.message.contains("Shared Name (A) -> ${dupe.id}"))
        assertTrue(failed.message.contains("Shared Name (B) -> ${otherDupe.id}"))
    }

    @Test fun `uuid of a disabled provider's model does not resolve`() {
        val result = SubAgentModelResolver.resolve(disabledProviderModel.id.toString(), providers)
        assertTrue(result is SubAgentModelResolver.Result.Failed)
    }

    @Test fun `uuid of an embedding model does not resolve`() {
        val result = SubAgentModelResolver.resolve(embedding.id.toString(), providers)
        assertTrue(result is SubAgentModelResolver.Result.Failed)
    }
}
