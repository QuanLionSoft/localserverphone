package dev.pocketpaas.data.remote

import dev.pocketpaas.domain.backup.BackupRestoreSummary
import dev.pocketpaas.domain.backup.PlatformBackupManager
import dev.pocketpaas.domain.backup.SignedBackupEnvelope
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.model.DeploymentStatus
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ManagedServices
import dev.pocketpaas.domain.services.ServiceCategory
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshConnectionInfo
import dev.pocketpaas.domain.ssh.SshKeyFormat
import dev.pocketpaas.domain.ssh.SshManager
import dev.pocketpaas.domain.ssh.SshSettings
import dev.pocketpaas.domain.ssh.SshState
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteApiExtendedRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `ssh routes support status start stop settings and authorized key management`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }

        // Unauthorized rejected
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/ssh").status)

        // Initial status
        val initial = client.get("/api/ssh") { authorize() }
        assertEquals(HttpStatusCode.OK, initial.status)
        assertTrue(initial.bodyAsText().contains("\"port\":8022"))

        // Start SSH
        val started = client.post("/api/ssh/start") { authorize() }
        assertEquals(HttpStatusCode.OK, started.status)
        assertTrue(started.bodyAsText().contains("\"status\":\"RUNNING\""))

        // Update settings
        val updated = client.post("/api/ssh/settings") {
            authorize()
            setBody("{\"port\":8025,\"passwordAuthEnabled\":true,\"listenLan\":true}")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(updated.bodyAsText().contains("\"port\":8025"))

        // Add key
        val validKey = sampleEd25519Key("ci-runner")
        val added = client.post("/api/ssh/keys") {
            authorize()
            setBody("{\"name\":\"ci-runner\",\"publicKey\":\"$validKey\"}")
        }
        assertEquals(HttpStatusCode.Created, added.status)
        assertTrue(added.bodyAsText().contains("ci-runner"))

        // Delete key
        val afterDelete = client.delete("/api/ssh/keys/1") { authorize() }
        assertEquals(HttpStatusCode.OK, afterDelete.status)
        assertTrue(afterDelete.bodyAsText().contains("\"authorizedKeyCount\":0"))

        // Stop SSH
        val stopped = client.post("/api/ssh/stop") { authorize() }
        assertEquals(HttpStatusCode.OK, stopped.status)
        assertTrue(stopped.bodyAsText().contains("\"status\":\"STOPPED\""))
    }

    @Test
    fun `services routes list install start stop and reject externally managed services`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }

        val listed = client.get("/api/services") { authorize() }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue(listed.bodyAsText().contains("postgresql"))

        val installed = client.post("/api/services/postgresql/install") { authorize() }
        assertEquals(HttpStatusCode.OK, installed.status)
        assertTrue(installed.bodyAsText().contains("\"installed\":true"))

        val started = client.post("/api/services/postgresql/start") { authorize() }
        assertEquals(HttpStatusCode.OK, started.status)
        assertTrue(started.bodyAsText().contains("\"status\":\"RUNNING\""))

        val stopped = client.post("/api/services/postgresql/stop") { authorize() }
        assertEquals(HttpStatusCode.OK, stopped.status)
        assertTrue(stopped.bodyAsText().contains("\"status\":\"STOPPED\""))

        // Externally managed service (nginx) rejected
        val external = client.post("/api/services/nginx/start") { authorize() }
        assertEquals(HttpStatusCode.BadRequest, external.status)
        assertTrue(external.bodyAsText().contains("externally_managed_service"))
    }

    @Test
    fun `activity deployments log export and backup routes work end to end`() = testApplication {
        val fixture = Fixture()
        application { installRemoteApi(fixture.dependencies()) }

        val activityResp = client.get("/api/activity?level=INFO&category=SSH&q=anahtar") { authorize() }
        assertEquals(HttpStatusCode.OK, activityResp.status)
        assertTrue(activityResp.bodyAsText().contains("SSH anahtarı eklendi"))

        val deployResp = client.get("/api/deployments?limit=10&q=demo") { authorize() }
        assertEquals(HttpStatusCode.OK, deployResp.status)
        assertTrue(deployResp.bodyAsText().contains("demo.example"))

        val csvExport = client.get("/api/logs/export?format=csv") { authorize() }
        assertEquals(HttpStatusCode.OK, csvExport.status)
        assertTrue(csvExport.bodyAsText().contains("# ACTIVITY EVENTS"))

        val backupExport = client.get("/api/backup/export") { authorize() }
        assertEquals(HttpStatusCode.OK, backupExport.status)
        assertTrue(backupExport.bodyAsText().contains("HMAC-SHA256"))

        val backupImport = client.post("/api/backup/import") {
            authorize()
            setBody("{\"envelopeJson\":\"{\\\"version\\\":1}\",\"requireSignature\":true}")
        }
        assertEquals(HttpStatusCode.OK, backupImport.status)
        assertTrue(backupImport.bodyAsText().contains("\"restoredSshKeys\":2"))
    }

    private inner class Fixture {
        val uploads = File(temporary.newFolder(), "uploads")
        val ssh = FakeSshManager()
        val services = FakeManagedServices()
        val activity = FakeActivityRepository()
        val deployments = FakeDeploymentHistoryRepository()
        val backup = FakeBackupManager()

        fun dependencies() = RemoteApiDependencies(
            sites = StubSites(),
            operations = StubOperations(),
            keys = object : ApiKeyStore { override suspend fun token() = TOKEN },
            uploadDirectory = uploads,
            ssh = ssh,
            services = services,
            activity = activity,
            deployments = deployments,
            backup = backup,
        )
    }

    private class FakeSshManager : SshManager {
        private val keys = mutableListOf<SshAuthorizedKey>()
        override val state = MutableStateFlow(SshState())
        override suspend fun start() {
            state.value = state.value.copy(status = NetworkServiceStatus.RUNNING, processId = 999)
        }
        override suspend fun stop() {
            state.value = state.value.copy(status = NetworkServiceStatus.STOPPED, processId = null)
        }
        override suspend fun updateSettings(settings: SshSettings) {
            state.value = state.value.copy(settings = settings)
        }
        override suspend fun addAuthorizedKey(name: String, publicKey: String): SshAuthorizedKey {
            val parsed = SshKeyFormat.parse(publicKey)
            val entry = SshAuthorizedKey(
                id = (keys.size + 1).toLong(),
                name = name,
                publicKey = parsed.line,
                fingerprint = parsed.fingerprint,
                enabled = true,
                createdAt = 1000L,
            )
            keys += entry
            state.value = state.value.copy(authorizedKeyCount = keys.size)
            return entry
        }
        override suspend fun removeAuthorizedKey(id: Long) {
            keys.removeAll { it.id == id }
            state.value = state.value.copy(authorizedKeyCount = keys.size)
        }
        override suspend fun listAuthorizedKeys(): List<SshAuthorizedKey> = keys.toList()
        override suspend fun connectionInfo(): SshConnectionInfo =
            SshConnectionInfo("u0_a123", "127.0.0.1", state.value.settings.port, "ssh -p ${state.value.settings.port} u0_a123@127.0.0.1")
        override suspend fun refresh() = Unit
    }

    private class FakeManagedServices : ManagedServices {
        override val services = MutableStateFlow(
            listOf(
                ManagedServiceState(
                    serviceKey = "postgresql",
                    displayName = "PostgreSQL",
                    category = ServiceCategory.DATABASE,
                    installed = false,
                    status = ManagedServiceStatus.STOPPED,
                    processId = null,
                    port = 5432,
                    lastError = null,
                    updatedAt = 100L,
                ),
            ),
        )
        override suspend fun install(serviceKey: String): ManagedServiceState = update(serviceKey) {
            it.copy(installed = true)
        }
        override suspend fun start(serviceKey: String): ManagedServiceState = update(serviceKey) {
            it.copy(installed = true, status = ManagedServiceStatus.RUNNING, processId = 2000)
        }
        override suspend fun stop(serviceKey: String): ManagedServiceState = update(serviceKey) {
            it.copy(status = ManagedServiceStatus.STOPPED, processId = null)
        }
        override suspend fun refresh() = Unit

        private fun update(key: String, transform: (ManagedServiceState) -> ManagedServiceState): ManagedServiceState {
            val current = services.value.first { it.serviceKey == key }
            val next = transform(current)
            services.value = services.value.map { if (it.serviceKey == key) next else it }
            return next
        }
    }

    private class FakeActivityRepository : ActivityRepository {
        private val items = listOf(
            ActivityEvent(1, ActivityCategory.SSH, ActivityLevel.INFO, "SSH anahtarı eklendi", null, 1000L),
            ActivityEvent(2, ActivityCategory.DATABASE, ActivityLevel.ERROR, "Redis hatası", null, 2000L),
        )
        override fun observeEvents(limit: Int): Flow<List<ActivityEvent>> = MutableStateFlow(items)
        override suspend fun record(category: ActivityCategory, level: ActivityLevel, message: String, detail: String?) = Unit
        override suspend fun recent(limit: Int): List<ActivityEvent> = items.take(limit)
    }

    private class FakeDeploymentHistoryRepository : DeploymentHistoryRepository {
        private val items = listOf(
            DeploymentRecord(1, 1, "demo.example", ProjectType.NODEJS, DeploymentStatus.SUCCEEDED, "demo.zip", 1024L, null, 500L, 600L),
        )
        override fun observeRecent(limit: Int): Flow<List<DeploymentRecord>> = MutableStateFlow(items)
        override suspend fun record(record: DeploymentRecord) = Unit
        override suspend fun recent(limit: Int): List<DeploymentRecord> = items.take(limit)
    }

    private class FakeBackupManager : PlatformBackupManager {
        override suspend fun exportSnapshot(signWithApiKey: Boolean): SignedBackupEnvelope =
            SignedBackupEnvelope(version = 1, algorithm = "HMAC-SHA256", exportedAt = 1000L, payloadJson = "{}", signatureHex = "a".repeat(64))
        override suspend fun exportEnvelopeJson(signWithApiKey: Boolean): String = "{}"
        override suspend fun importEnvelopeJson(rawJson: String, requireSignature: Boolean): BackupRestoreSummary =
            BackupRestoreSummary(schemaVersion = 4, restoredSshSettings = true, restoredSshKeys = 2, restoredServices = 1, signatureVerified = requireSignature, restoredAt = 2000L)
    }

    private class StubOperations : RemoteSiteOperations {
        override suspend fun deploy(request: RemoteDeployRequest, archive: File): Site =
            Site(1, "demo.example", 18000, ProjectType.NODEJS, null, null, SiteStatus.RUNNING, "", 1)
        override suspend fun start(siteId: Long): Site =
            Site(siteId, "demo.example", 18000, ProjectType.NODEJS, 123, null, SiteStatus.RUNNING, "", 1)
        override suspend fun stop(siteId: Long): Site =
            Site(siteId, "demo.example", 18000, ProjectType.NODEJS, null, null, SiteStatus.STOPPED, "", 1)
    }

    private class StubSites : SiteRepository {
        override val sites: StateFlow<List<Site>> = MutableStateFlow(emptyList())
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Ready(emptyList()))
        override fun observeSite(id: Long): Flow<Site?> = MutableStateFlow(null)
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = MutableStateFlow(emptyList())
        override suspend fun listSites(): List<Site> = emptyList()
        override suspend fun getSite(id: Long): Site? = null
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site =
            Site(1, domainName, 18000, projectType, null, null, SiteStatus.STOPPED, "", 1)
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) = Unit
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean = true
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) = Unit
        override suspend fun appendLogs(id: Long, text: String) = Unit
        override suspend fun clearLogs(id: Long) = Unit
        override suspend fun deleteSiteIfInactive(id: Long) = Unit
    }

    private companion object {
        val TOKEN = "a".repeat(64)
        fun io.ktor.client.request.HttpRequestBuilder.authorize() {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
        }
        fun sampleEd25519Key(comment: String): String {
            val typeBytes = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
            val keyBytes = ByteArray(32) { (it + 3).toByte() }
            val buffer = ByteArray(4 + typeBytes.size + keyBytes.size)
            buffer[0] = (typeBytes.size shr 24).toByte()
            buffer[1] = (typeBytes.size shr 16).toByte()
            buffer[2] = (typeBytes.size shr 8).toByte()
            buffer[3] = typeBytes.size.toByte()
            typeBytes.copyInto(buffer, 4)
            keyBytes.copyInto(buffer, 4 + typeBytes.size)
            return "ssh-ed25519 ${Base64.getEncoder().encodeToString(buffer)} $comment"
        }
    }
}
