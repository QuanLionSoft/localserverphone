package dev.pocketpaas.data.repository

import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.data.local.PlatformSettingsDao
import dev.pocketpaas.data.local.PlatformSettingsEntity
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.system.SystemManager
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultPlatformRepositoryTest {
    @Test
    fun concurrentInitializationKeepsOneIdentityAndSerializesRuntimePreparation() = runTest {
        val settingsDao = InMemorySettingsDao()
        val systemManager = RecordingSystemManager()
        val repository = DefaultPlatformRepository(settingsDao, systemManager, ServerConfig())

        val results = List(32) { async { repository.initialize() } }.awaitAll()

        assertEquals(1, results.map { it.installationId }.distinct().size)
        assertEquals(1, results.map { it.createdAtEpochMillis }.distinct().size)
        assertEquals(1, systemManager.maximumConcurrentOperations)
        assertEquals(1, settingsDao.insertedCandidates)
        assertEquals(results.first().installationId, settingsDao.getSettings()?.installationId)
        assertTrue(results.all { it.layout == systemManager.layout })
        assertTrue(results.all { it.capabilities == systemManager.capabilities })
    }

    @Test
    fun repositoryRecreationPreservesPreviouslyPersistedIdentity() = runTest {
        val settingsDao = InMemorySettingsDao(
            PlatformSettingsEntity(installationId = "existing-installation", createdAtEpochMillis = 42L),
        )
        val config = ServerConfig(apiPort = 9090, nginxPort = 9091)

        val first = DefaultPlatformRepository(settingsDao, RecordingSystemManager(), config).initialize()
        val afterRecreation = DefaultPlatformRepository(settingsDao, RecordingSystemManager(), config).initialize()

        assertEquals("existing-installation", first.installationId)
        assertEquals(first.installationId, afterRecreation.installationId)
        assertEquals(42L, afterRecreation.createdAtEpochMillis)
        assertEquals(9090, afterRecreation.apiPort)
        assertEquals(9091, afterRecreation.nginxPort)
        assertEquals(0, settingsDao.insertedCandidates)
    }

    @Test
    fun failedRuntimePreparationCanBeRetriedWithoutCreatingPrematureSettings() = runTest {
        val settingsDao = InMemorySettingsDao()
        val systemManager = RecordingSystemManager(failNextPreparation = true)
        val repository = DefaultPlatformRepository(settingsDao, systemManager, ServerConfig())

        try {
            repository.initialize()
            fail("Runtime storage failure should be propagated")
        } catch (exception: IOException) {
            assertEquals("Runtime storage unavailable", exception.message)
        }
        assertEquals(0, settingsDao.insertedCandidates)

        val result = repository.initialize()

        assertNotNull(settingsDao.getSettings())
        assertEquals(result.installationId, settingsDao.getSettings()?.installationId)
        assertEquals(1, settingsDao.insertedCandidates)
    }

    private class InMemorySettingsDao(
        initialSettings: PlatformSettingsEntity? = null,
    ) : PlatformSettingsDao() {
        private val persistenceMutex = Mutex()
        private var settings = initialSettings
        private val publicUrl = MutableStateFlow(initialSettings?.publicUrl)
        var insertedCandidates: Int = 0
            private set

        override suspend fun getSettings(): PlatformSettingsEntity? = persistenceMutex.withLock { settings }

        override fun observePublicUrl(): Flow<String?> = publicUrl

        override suspend fun updatePublicUrl(url: String?): Int = persistenceMutex.withLock {
            val current = settings ?: return@withLock 0
            settings = current.copy(publicUrl = url)
            publicUrl.value = url
            1
        }

        override suspend fun insertIfAbsent(settings: PlatformSettingsEntity): Long = persistenceMutex.withLock {
            if (this.settings != null) {
                -1L
            } else {
                this.settings = settings
                insertedCandidates++
                settings.id.toLong()
            }
        }
    }

    private class RecordingSystemManager(
        var failNextPreparation: Boolean = false,
    ) : SystemManager {
        val layout = RuntimeLayout(
            rootPath = "/private/pocket-paas",
            projectsPath = "/private/pocket-paas/htdocs",
            nginxConfigPath = "/private/pocket-paas/nginx/nginx.conf",
            nginxHostsPath = "/private/pocket-paas/nginx/conf.d",
            runtimePath = "/private/pocket-paas/runtime",
            logsPath = "/private/pocket-paas/logs",
        )
        val capabilities = RuntimeCapabilities(
            termuxInstalled = true,
            termuxCommandPermissionGranted = false,
            supportedAbis = listOf("arm64-v8a"),
        )
        private var activeOperations: Int = 0
        var maximumConcurrentOperations: Int = 0
            private set

        override suspend fun prepareLayout(): RuntimeLayout = recordOperation {
            if (failNextPreparation) {
                failNextPreparation = false
                throw IOException("Runtime storage unavailable")
            }
            layout
        }

        override suspend fun inspectCapabilities(): RuntimeCapabilities = recordOperation { capabilities }

        private suspend fun <T> recordOperation(result: () -> T): T {
            activeOperations++
            maximumConcurrentOperations = maxOf(maximumConcurrentOperations, activeOperations)
            return try {
                delay(10)
                result()
            } finally {
                activeOperations--
            }
        }
    }
}
