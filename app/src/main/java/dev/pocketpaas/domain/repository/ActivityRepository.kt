package dev.pocketpaas.domain.repository

import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun observeEvents(limit: Int = 200): Flow<List<ActivityEvent>>
    suspend fun record(category: ActivityCategory, level: ActivityLevel, message: String, detail: String? = null)
    suspend fun recent(limit: Int): List<ActivityEvent>
    suspend fun clearAll() = Unit
}
