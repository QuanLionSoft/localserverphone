package dev.pocketpaas.data.repository

import dev.pocketpaas.data.local.PlatformSettingsDao
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.repository.PlatformRepository
import dev.pocketpaas.domain.repository.ServerStateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class DefaultServerStateRepository @Inject constructor(
    private val settingsDao: PlatformSettingsDao,
    private val platformRepository: PlatformRepository,
) : ServerStateRepository {
    override val publicUrl: Flow<String?> = settingsDao.observePublicUrl()

    override suspend fun updatePublicUrl(url: String?) {
        val normalized = SiteInput.normalizePublicUrl(url)
        platformRepository.initialize()
        check(settingsDao.updatePublicUrl(normalized) == 1) { "The master server state could not be persisted." }
    }
}
