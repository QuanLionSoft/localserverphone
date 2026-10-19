package dev.pocketpaas.data.repository

import dev.pocketpaas.data.local.ActivityEventDao
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.repository.ActivityRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultActivityRepository @Inject constructor(
    private val dao: ActivityEventDao,
    private val clock: Clock,
) : ActivityRepository {
    override fun observeEvents(limit: Int): Flow<List<ActivityEvent>> {
        require(limit > 0) { "Event limit must be positive" }
        return dao.observeEvents(limit.coerceAtMost(MAXIMUM_QUERY_LIMIT)).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun record(
        category: ActivityCategory,
        level: ActivityLevel,
        message: String,
        detail: String?,
    ) {
        dao.record(
            category = category,
            level = level,
            message = sanitize(message, MAXIMUM_MESSAGE_CHARACTERS),
            detail = detail?.let { sanitize(it, MAXIMUM_DETAIL_CHARACTERS) },
            timestamp = clock.millis(),
            keep = RETENTION_LIMIT,
        )
    }

    override suspend fun recent(limit: Int): List<ActivityEvent> {
        require(limit > 0) { "Event limit must be positive" }
        return dao.recent(limit.coerceAtMost(MAXIMUM_QUERY_LIMIT)).map { it.toDomain() }
    }

    private fun sanitize(text: String, maximumCharacters: Int): String =
        text.replace('\u0000', '\uFFFD').lineSequence().joinToString(" ").trim().take(maximumCharacters)

    internal companion object {
        const val RETENTION_LIMIT = 500
        const val MAXIMUM_QUERY_LIMIT = 500
        const val MAXIMUM_MESSAGE_CHARACTERS = 512
        const val MAXIMUM_DETAIL_CHARACTERS = 2_048
    }
}
