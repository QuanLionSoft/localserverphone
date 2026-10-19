package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel

@Entity(
    tableName = "activity_events",
    indices = [Index(value = ["timestamp"])],
)
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: ActivityCategory,
    val level: ActivityLevel,
    val message: String,
    val detail: String? = null,
    val timestamp: Long,
)
