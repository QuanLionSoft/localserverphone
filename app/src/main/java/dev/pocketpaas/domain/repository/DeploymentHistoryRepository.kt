package dev.pocketpaas.domain.repository

import dev.pocketpaas.domain.model.DeploymentRecord
import kotlinx.coroutines.flow.Flow

interface DeploymentHistoryRepository {
    fun observeRecent(limit: Int = 50): Flow<List<DeploymentRecord>>
    suspend fun record(record: DeploymentRecord)
    suspend fun recent(limit: Int): List<DeploymentRecord>
}
