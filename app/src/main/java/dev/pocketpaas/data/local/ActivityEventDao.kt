package dev.pocketpaas.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ActivityEventDao {
    @Query("SELECT * FROM activity_events ORDER BY id DESC LIMIT :limit")
    abstract fun observeEvents(limit: Int): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events ORDER BY id DESC LIMIT :limit")
    abstract suspend fun recent(limit: Int): List<ActivityEventEntity>

    @Query("DELETE FROM activity_events")
    abstract suspend fun deleteAll(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(event: ActivityEventEntity): Long

    @Query("DELETE FROM activity_events WHERE id NOT IN (SELECT id FROM activity_events ORDER BY id DESC LIMIT :keep)")
    protected abstract suspend fun pruneBeyond(keep: Int): Int

    /** Ekleme ve budama tek transaction içinde; kayıt asla sessizce kaybolmaz. */
    @Transaction
    open suspend fun record(
        category: ActivityCategory,
        level: ActivityLevel,
        message: String,
        detail: String?,
        timestamp: Long,
        keep: Int,
    ): Long {
        require(keep > 0) { "Retention limit must be positive" }
        val id = insertEvent(
            ActivityEventEntity(
                category = category,
                level = level,
                message = message,
                detail = detail,
                timestamp = timestamp,
            ),
        )
        pruneBeyond(keep)
        return id
    }
}
