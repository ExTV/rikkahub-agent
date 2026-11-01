package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "conversation_compaction",
    primaryKeys = ["conversation_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ConversationCompactionEntity(
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("tail_start_node_id")
    val tailStartNodeId: String?,
    @ColumnInfo("source_end_node_id")
    val sourceEndNodeId: String,
    @ColumnInfo("summary_model_id")
    val summaryModelId: String,
    @ColumnInfo("is_auto")
    val isAuto: Boolean,
    @ColumnInfo("source_token_estimate")
    val sourceTokenEstimate: Int,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
