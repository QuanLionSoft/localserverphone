package dev.pocketpaas.data.repository

import dev.pocketpaas.data.local.DeploymentRecordDao
import dev.pocketpaas.data.local.DeploymentRecordEntity
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultDeploymentHistoryRepository @Inject constructor(
    private val dao: DeploymentRecordDao,
) : DeploymentHistoryRepository {
    override fun observeRecent(limit: Int): Flow<List<DeploymentRecord>> {
        require(limit > 0) { "History limit must be positive" }
        return dao.observeRecent(limit.coerceAtMost(MAXIMUM_QUERY_LIMIT)).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun record(record: DeploymentRecord) {
        val id = dao.record(
            DeploymentRecordEntity(
                siteId = record.siteId,
                domainName = record.domainName.take(MAXIMUM_DOMAIN_CHARACTERS),
                projectType = record.projectType,
                status = record.status,
                archiveName = record.archiveName?.replace('\u0000', '\uFFFD')?.take(MAXIMUM_MESSAGE_CHARACTERS),
                archiveSizeBytes = record.archiveSizeBytes,
                message = record.message?.replace('\u0000', '\uFFFD')?.take(MAXIMUM_MESSAGE_CHARACTERS),
                startedAt = record.startedAt,
                finishedAt = record.finishedAt,
            ),
            keep = RETENTION_LIMIT,
        )
        require(id > 0) { "Deployment record was not persisted" }
    }

    override suspend fun recent(limit: Int): List<DeploymentRecord> {
        require(limit > 0) { "History limit must be positive" }
        return dao.recent(limit.coerceAtMost(MAXIMUM_QUERY_LIMIT)).map { it.toDomain() }
    }

    internal companion object {
        const val RETENTION_LIMIT = 200
        const val MAXIMUM_QUERY_LIMIT = 200
        const val MAXIMUM_DOMAIN_CHARACTERS = 253
        const val MAXIMUM_MESSAGE_CHARACTERS = 512
    }
}
