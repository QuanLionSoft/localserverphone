package dev.pocketpaas.data.repository

import dev.pocketpaas.data.local.PlatformSettingsDao
import dev.pocketpaas.data.local.PlatformSettingsEntity
import dev.pocketpaas.domain.model.PlatformInfo
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.repository.PlatformRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultServerStateRepositoryTest {
    @Test
    fun masterUrlPersistsAndClearsWithoutReplacingInstallationIdentity() = runTest {
        val dao = MemoryDao()
        val repository = DefaultServerStateRepository(dao, platform(dao))
        repository.updatePublicUrl(" https://gateway.trycloudflare.com ")
        assertEquals("https://gateway.trycloudflare.com", repository.publicUrl.first())
        assertEquals("installation", dao.getSettings()?.installationId)
        repository.updatePublicUrl(null)
        assertNull(repository.publicUrl.first())
        assertEquals(42L, dao.getSettings()?.createdAtEpochMillis)
    }

    @Test
    fun invalidUrlCannotCreateOrModifyPersistentState() = runTest {
        val dao = MemoryDao()
        val repository = DefaultServerStateRepository(dao, platform(dao))
        assertTrue(runCatching { repository.updatePublicUrl("https://user:secret@example.com") }.exceptionOrNull() is IllegalArgumentException)
        assertNull(dao.getSettings())
    }

    @Test
    fun missingMasterRowCannotSilentlyAcceptAnUpdate() = runTest {
        val dao = MemoryDao()
        val repository = DefaultServerStateRepository(dao, object : PlatformRepository {
            override suspend fun initialize(): PlatformInfo = info
        })
        assertTrue(runCatching { repository.updatePublicUrl(null) }.exceptionOrNull() is IllegalStateException)
    }

    private fun platform(dao: MemoryDao) = object : PlatformRepository {
        override suspend fun initialize(): PlatformInfo {
            dao.getOrCreate(PlatformSettingsEntity(installationId = "installation", createdAtEpochMillis = 42))
            return info
        }
    }

    private class MemoryDao : PlatformSettingsDao() {
        private var settings: PlatformSettingsEntity? = null
        private val url = MutableStateFlow<String?>(null)
        override suspend fun getSettings(): PlatformSettingsEntity? = settings
        override suspend fun insertIfAbsent(settings: PlatformSettingsEntity): Long {
            if (this.settings != null) return -1
            this.settings = settings
            return 1
        }
        override fun observePublicUrl(): Flow<String?> = url
        override suspend fun updatePublicUrl(url: String?): Int {
            val existing = settings ?: return 0
            settings = existing.copy(publicUrl = url)
            this.url.value = url
            return 1
        }
    }

    companion object {
        private val info = PlatformInfo(
            "installation", 42,
            RuntimeLayout("/runtime", "/runtime/htdocs", "/runtime/nginx/nginx.conf", "/runtime/nginx/conf.d", "/runtime/tmp", "/runtime/logs"),
            RuntimeCapabilities(true, true, listOf("arm64-v8a")), 8080, 8081,
        )
    }
}
