package dev.pocketpaas.data.repository

import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.data.local.PlatformSettingsDao
import dev.pocketpaas.data.local.PlatformSettingsEntity
import dev.pocketpaas.domain.model.PlatformInfo
import dev.pocketpaas.domain.repository.PlatformRepository
import dev.pocketpaas.domain.system.SystemManager
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultPlatformRepository @Inject constructor(
    private val settingsDao: PlatformSettingsDao,
    private val systemManager: SystemManager,
    private val config: ServerConfig,
) : PlatformRepository {
    private val initializationMutex = Mutex()

    override suspend fun initialize(): PlatformInfo = initializationMutex.withLock {
        val layout = systemManager.prepareLayout()
        val settings = settingsDao.getOrCreate(
            PlatformSettingsEntity(
                installationId = UUID.randomUUID().toString(),
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        PlatformInfo(
            installationId = settings.installationId,
            createdAtEpochMillis = settings.createdAtEpochMillis,
            layout = layout,
            capabilities = systemManager.inspectCapabilities(),
            apiPort = config.apiPort,
            nginxPort = config.nginxPort,
        )
    }
}
