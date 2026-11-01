package me.rerere.rikkahub.data.model

import kotlinx.serialization.decodeFromString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #28 persistence guard: [Conversation] gained a nullable, defaulted `chatModelId`
 * field. A Conversation JSON payload from before this field existed must still decode, with
 * chatModelId landing on null rather than failing or silently picking some other model.
 */
class ConversationChatModelIdTest {

    // Hand-written to mirror the pre-chatModelId shape - no "chatModelId" key present.
    private val oldConversationJson = """
        {
          "id": "0195e2a1-0000-7000-8000-000000000001",
          "assistantId": "0195e2a1-0000-7000-8000-000000000002",
          "title": "old conversation",
          "messageNodes": [],
          "chatSuggestions": [],
          "isPinned": false,
          "createAt": "2024-01-01T00:00:00Z",
          "updateAt": "2024-01-01T00:00:00Z",
          "customSystemPrompt": null,
          "modeInjectionIds": [],
          "lorebookIds": [],
          "workspaceCwd": null,
          "folderId": null
        }
    """.trimIndent()

    @Test
    fun `old conversation JSON without chatModelId decodes to null`() {
        val conversation = JsonInstant.decodeFromString<Conversation>(oldConversationJson)
        assertNull(conversation.chatModelId)
        assertEquals("old conversation", conversation.title)
    }
}
