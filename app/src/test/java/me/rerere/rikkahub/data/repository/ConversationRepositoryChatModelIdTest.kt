package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Task 5 (#28 follow-up): [ConversationEntity] has no column for `Conversation.chatModelId`
 * unless [encodeChatModelId] / [decodeChatModelId] - the functions
 * [ConversationRepository.conversationToConversationEntity] and
 * [ConversationRepository.conversationEntityToConversation] delegate to for that field - are
 * wired up. Exercised directly here rather than through a full [ConversationRepository]
 * instance: the repository's other constructor dependencies (AppDatabase, FilesManager,
 * MessageFtsManager, four Room DAOs) need either Robolectric or a mocking library, neither of
 * which this module has; these two functions are the entire persistence-relevant surface for
 * the field, so testing them directly gives equivalent coverage of the Room round trip without
 * adding either.
 */
class ConversationRepositoryChatModelIdTest {

    @Test
    fun `a set chatModelId round-trips through the stored column`() {
        val id = Uuid.parse("0195e2a1-0000-7000-8000-000000000003")
        val stored = encodeChatModelId(id)
        assertEquals(id.toString(), stored)
        assertEquals(id, decodeChatModelId(stored))
    }

    @Test
    fun `null chatModelId round-trips as null`() {
        val stored = encodeChatModelId(null)
        assertEquals("", stored)
        assertNull(decodeChatModelId(stored))
    }

    @Test
    fun `a malformed stored value decodes to null instead of throwing`() {
        assertNull(decodeChatModelId("not-a-uuid"))
    }
}
