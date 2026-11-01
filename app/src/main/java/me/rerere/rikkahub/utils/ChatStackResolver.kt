package me.rerere.rikkahub.utils

/**
 * Resolves the ordered conversation ids for RouteActivity's initial chat back stack.
 *
 * Pure decision logic extracted out of `RouteActivity.AppRoutes()` so it can be unit-tested
 * without Android. When a notification (e.g. a cron job's "generation done" notification)
 * cold-starts the app with a `conversationId` deep link, the deep-linked conversation is pushed
 * on top of the normal default/home conversation - mirroring `RouteActivity.onNewIntent`'s push
 * semantics - so Back returns to a normal home chat instead of exiting the app. When there is no
 * deep link, this falls back to the existing `create_new_conversation_on_start` /
 * `lastConversationId` default behavior.
 *
 * @param deepLinkConversationId the `conversationId` launch intent extra, if any.
 * @param createNewOnStart the `create_new_conversation_on_start` preference.
 * @param lastConversationId the persisted `lastConversationId` preference, if any.
 * @param newId generates a fresh conversation id (e.g. `Uuid.random().toString()`).
 * @return the conversation ids for the initial back stack, in order (bottom to top).
 */
fun resolveInitialChatStack(
    deepLinkConversationId: String?,
    createNewOnStart: Boolean,
    lastConversationId: String?,
    newId: () -> String,
): List<String> {
    val defaultId = if (createNewOnStart) newId() else lastConversationId ?: newId()
    return if (deepLinkConversationId != null && deepLinkConversationId != defaultId) {
        listOf(defaultId, deepLinkConversationId)
    } else {
        listOf(defaultId)
    }
}
