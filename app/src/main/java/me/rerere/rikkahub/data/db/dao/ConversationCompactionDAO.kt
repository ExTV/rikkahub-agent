package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ConversationCompactionEntity

@Dao
interface ConversationCompactionDAO {
    @Query("SELECT * FROM conversation_compaction WHERE conversation_id = :conversationId")
    suspend fun getByConversationId(conversationId: String): ConversationCompactionEntity?

    /** Doctor support: how many conversations have ever been compacted. */
    @Query("SELECT COUNT(*) FROM conversation_compaction")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(compaction: ConversationCompactionEntity)

    @Query("DELETE FROM conversation_compaction WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)
}
