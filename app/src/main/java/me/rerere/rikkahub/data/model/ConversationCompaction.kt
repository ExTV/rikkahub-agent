package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid

data class ConversationCompaction(
    val conversationId: Uuid,
    val summary: String,
    val tailStartNodeId: Uuid?,
    val sourceEndNodeId: Uuid,
    val summaryModelId: Uuid,
    val isAuto: Boolean,
    val sourceTokenEstimate: Int,
    val createdAt: Instant,
)
