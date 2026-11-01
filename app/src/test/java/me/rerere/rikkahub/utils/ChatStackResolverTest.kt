package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStackResolverTest {

    @Test
    fun `deep link present and differs from default pushes deep link on top`() {
        val result = resolveInitialChatStack(
            deepLinkConversationId = "deep-link-id",
            createNewOnStart = true,
            lastConversationId = null,
            newId = { "default-id" },
        )
        assertEquals(listOf("default-id", "deep-link-id"), result)
    }

    @Test
    fun `deep link present but equal to default does not duplicate`() {
        val result = resolveInitialChatStack(
            deepLinkConversationId = "same-id",
            createNewOnStart = false,
            lastConversationId = "same-id",
            newId = { "unused" },
        )
        assertEquals(listOf("same-id"), result)
    }

    @Test
    fun `no deep link and createNew true starts a fresh conversation`() {
        val result = resolveInitialChatStack(
            deepLinkConversationId = null,
            createNewOnStart = true,
            lastConversationId = "last-id",
            newId = { "new-id" },
        )
        assertEquals(listOf("new-id"), result)
    }

    @Test
    fun `no deep link and createNew false resumes last conversation`() {
        val result = resolveInitialChatStack(
            deepLinkConversationId = null,
            createNewOnStart = false,
            lastConversationId = "last-id",
            newId = { "new-id" },
        )
        assertEquals(listOf("last-id"), result)
    }

    @Test
    fun `no deep link, createNew false and no last conversation falls back to a new one`() {
        val result = resolveInitialChatStack(
            deepLinkConversationId = null,
            createNewOnStart = false,
            lastConversationId = null,
            newId = { "new-id" },
        )
        assertEquals(listOf("new-id"), result)
    }
}
