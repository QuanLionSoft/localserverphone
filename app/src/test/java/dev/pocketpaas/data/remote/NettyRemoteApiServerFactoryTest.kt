package dev.pocketpaas.data.remote

import dev.pocketpaas.core.config.RemoteApiPolicy
import dev.pocketpaas.core.config.ServerConfig
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteApiServer
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.remote.RemoteUploadStorage
import dev.pocketpaas.domain.repository.SiteRepository
import io.ktor.server.netty.Netty
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the actual production Netty listener through real loopback sockets. */
class NettyRemoteApiServerFactoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000)
    fun actualHttpAuthenticationBinaryDeployAndActionsReachTheProductionRoutes() = runBlocking {
        val fixture = Fixture()
        fixture.running {
            assertEquals(401, fixture.request("GET", "/api/sites", token = null).status)
            assertEquals(401, fixture.request("POST", "/api/deploy", token = "b".repeat(64), body = byteArrayOf(0)).status)
            assertEquals(0, fixture.repository.reads)
            assertEquals(0, fixture.operations.deploys)
            val listed = fixture.request("GET", "/api/sites")
            assertEquals(200, listed.status)
            assertEquals("no-store", listed.cacheControl)
            assertTrue(listed.body.contains("example.com"))
            val bytes = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte()) + "\r\n--wireX".toByteArray()
            val deployed = fixture.request("POST", "/api/deploy", body = multipart(bytes),
                contentType = "multipart/form-data; boundary=wire")
            assertEquals(deployed.body, 201, deployed.status)
            assertArrayEquals(bytes, fixture.operations.receivedArchive)
            assertEquals(RemoteDeployRequest(siteId = 1), fixture.operations.receivedRequest)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
            assertEquals(200, fixture.request("POST", "/api/sites/1/start", body = byteArrayOf()).status)
            assertEquals(1L, fixture.operations.started)
            assertEquals(200, fixture.request("POST", "/api/sites/1/stop", body = byteArrayOf()).status)
            assertEquals(1L, fixture.operations.stopped)
        }
    }

    @Test(timeout = 30_000)
    fun stopIsIdempotentClosesThePortAndTheSameServerCanRestart() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.server.stop()
            fixture.server.start()
            assertEquals(200, fixture.request("GET", "/api/sites").status)
            fixture.server.stop()
            fixture.server.stop()
            assertFalse(fixture.portOpen())
            fixture.server.start()
            assertEquals(200, fixture.request("GET", "/api/sites").status)
        } finally { fixture.server.stop() }
        assertFalse(fixture.portOpen())
    }

    @Test(timeout = 30_000)
    fun anOccupiedPortCannotLeakAnOwnerAndStartupCanRetryAfterThePortIsReleased() = runBlocking {
        val occupied = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val fixture = Fixture(port = occupied.localPort)
        try {
            assertTrue(runCatching { fixture.server.start() }.isFailure)
            fixture.server.stop()
            assertTrue(fixture.portOpen())
            occupied.close()
            fixture.server.start()
            assertEquals(200, fixture.request("GET", "/api/sites").status)
        } finally { occupied.close(); fixture.server.stop() }
        assertFalse(fixture.portOpen())
    }

    @Test(timeout = 30_000)
    fun coldStartupCleansOnlyGeneratedRegularArchiveFiles() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.uploads.mkdirs())
        val stale = File(fixture.uploads, "remote-upload-123456.zip").apply { writeText("interrupted") }
        val owner = File(fixture.uploads, "owner.zip").apply { writeText("keep") }
        val malformed = File(fixture.uploads, "remote-upload-owner.zip").apply { writeText("keep") }
        val directory = File(fixture.uploads, "remote-upload-77.zip").apply { assertTrue(mkdir()) }
        fixture.running {
            assertFalse(stale.exists())
            assertEquals("keep", owner.readText())
            assertEquals("keep", malformed.readText())
            assertTrue(directory.isDirectory)
        }
    }

    @Test(timeout = 30_000)
    fun symbolicLinkUploadRootIsRejectedBeforeBindingOrDeletingExternalFiles() = runBlocking {
        assumeTrue("POSIX symbolic link verification requires a POSIX host.", File.separatorChar == '/')
        val fixture = Fixture()
        val outside = temporary.newFolder("external")
        val protectedFile = File(outside, "remote-upload-123.zip").apply { writeText("owner") }
        Files.createSymbolicLink(fixture.uploads.toPath(), outside.toPath())
        try {
            assertTrue(runCatching { fixture.server.start() }.exceptionOrNull() is IOException)
            assertFalse(fixture.portOpen())
            assertEquals("owner", protectedFile.readText())
        } finally { fixture.server.stop() }
    }

    @Test(timeout = 30_000)
    fun anIncompleteUploadHitsItsDeadlineAndReleasesSpoolAndMutationAdmission() = runBlocking {
        val fixture = Fixture(policy = RemoteApiPolicy(uploadTimeoutMillis = 150))
        fixture.running {
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", fixture.port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write((
                        "POST /api/deploy HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $TOKEN\r\n" +
                            "Content-Type: multipart/form-data; boundary=wire\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n" +
                            "--wire\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"project.zip\"\r\n\r\npartial"
                        ).toByteArray())
                    socket.getOutputStream().flush()
                    val response = socket.getInputStream().bufferedReader().readText()
                    assertTrue(response, response.startsWith("HTTP/1.1 408"))
                }
            }
            assertEquals(0, fixture.operations.deploys)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
            assertEquals(200, fixture.request("POST", "/api/sites/1/start", body = byteArrayOf()).status)
        }
    }

    @Test(timeout = 30_000)
    fun serverStopCancelsAnActiveRequestAndPrunesItsPrivateArchiveBeforeRestart() = runBlocking {
        val fixture = Fixture()
        fixture.operations.entered = CompletableDeferred()
        fixture.operations.release = CompletableDeferred()
        try {
            fixture.server.start()
            coroutineScope {
                val request = async { runCatching {
                    fixture.request("POST", "/api/deploy", body = multipart(byteArrayOf(80, 75)),
                        contentType = "multipart/form-data; boundary=wire")
                } }
                withTimeout(5_000) { requireNotNull(fixture.operations.entered).await() }
                fixture.server.stop()
                withTimeout(5_000) {
                    while (!fixture.operations.cancelled.isCompleted || fixture.uploads.listFiles().orEmpty().isNotEmpty()) delay(20)
                }
                request.await()
                assertFalse(fixture.portOpen())
            }
            fixture.operations.release = null
            fixture.server.start()
            assertEquals(200, fixture.request("GET", "/api/sites").status)
        } finally {
            fixture.operations.release?.complete(Unit)
            fixture.server.stop()
        }
    }

    @Test(timeout = 30_000)
    fun partialUnauthenticatedHeadersExpireBeforeTheyCanOccupyAConnectionForever() = runBlocking {
        val fixture = Fixture(policy = RemoteApiPolicy(headerTimeoutMillis = 150))
        fixture.running {
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", fixture.port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write("GET /api/sites HTTP/1.1\r\nHost: local".toByteArray())
                    socket.getOutputStream().flush()
                    assertTrue("The absolute header deadline must close an incomplete request.", socket.readClosed())
                }
            }
            assertEquals(0, fixture.repository.reads)
            assertEquals(200, fixture.request("GET", "/api/sites").status)
        }
    }

    @Test(timeout = 30_000)
    fun unauthorizedDeclaredBodyIsRejectedWithoutWaitingForUploadAndTheConnectionCloses() = runBlocking {
        val fixture = Fixture(policy = RemoteApiPolicy(maximumConnections = 1, headerTimeoutMillis = 1_000))
        fixture.running {
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", fixture.port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write((
                        "POST /api/deploy HTTP/1.1\r\nHost: localhost\r\n" +
                            "Content-Type: multipart/form-data; boundary=wire\r\n" +
                            "Content-Length: 1073741824\r\nConnection: keep-alive\r\n\r\n"
                        ).toByteArray())
                    socket.getOutputStream().flush()
                    // readText returns only once the server closes, even though the declared body is withheld.
                    val response = socket.getInputStream().bufferedReader().readText()
                    assertTrue(response, response.startsWith("HTTP/1.1 401"))
                    assertTrue(response.lowercase().contains("connection: close"))
                }
            }
            assertEquals(0, fixture.operations.deploys)
            assertEquals(0, fixture.repository.reads)
            assertTrue(fixture.uploads.listFiles().orEmpty().isEmpty())
            val listed = withTimeout(5_000) {
                var response: Response? = null
                while (response == null) {
                    response = runCatching { fixture.request("GET", "/api/sites") }.getOrNull()
                    if (response == null) delay(20)
                }
                checkNotNull(response)
            }
            assertEquals(200, listed.status)
        }
    }

    @Test(timeout = 30_000)
    fun maximumConnectionsRejectsExcessSocketsAndReleasesTheSlotAfterTheActiveResponse() = runBlocking {
        val fixture = Fixture(policy = RemoteApiPolicy(maximumConnections = 1, headerTimeoutMillis = 5_000))
        fixture.operations.entered = CompletableDeferred()
        fixture.operations.release = CompletableDeferred()
        fixture.running {
            coroutineScope {
                val request = async { fixture.request("POST", "/api/deploy", body = multipart(byteArrayOf(80, 75)),
                    contentType = "multipart/form-data; boundary=wire") }
                withTimeout(5_000) { requireNotNull(fixture.operations.entered).await() }
                try {
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", fixture.port).use { socket ->
                            socket.soTimeout = 5_000
                            assertTrue("The additional TCP channel must be refused.", socket.readClosed())
                        }
                    }
                } finally { requireNotNull(fixture.operations.release).complete(Unit) }
                assertEquals(201, request.await().status)
            }
            val response = withTimeout(5_000) {
                var successful: Response? = null
                while (successful == null) {
                    successful = runCatching { fixture.request("GET", "/api/sites") }.getOrNull()
                    if (successful == null) delay(20)
                }
                checkNotNull(successful)
            }
            assertEquals(200, response.status)
        }
    }

    @Test(timeout = 30_000)
    fun completeHeadersCancelTheirDeadlineWhileALongDeploymentResponseRemainsOpen() = runBlocking {
        val fixture = Fixture(policy = RemoteApiPolicy(headerTimeoutMillis = 100))
        fixture.operations.entered = CompletableDeferred()
        fixture.operations.release = CompletableDeferred()
        fixture.running {
            coroutineScope {
                val request = async { fixture.request("POST", "/api/deploy", body = multipart(byteArrayOf(80, 75)),
                    contentType = "multipart/form-data; boundary=wire") }
                withTimeout(5_000) { requireNotNull(fixture.operations.entered).await() }
                try {
                    delay(300)
                    assertFalse("The completed-header deadline must not abort a running operation.", request.isCompleted)
                } finally { requireNotNull(fixture.operations.release).complete(Unit) }
                assertEquals(201, request.await().status)
            }
        }
    }

    private inner class Fixture(
        val port: Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort },
        policy: RemoteApiPolicy = RemoteApiPolicy(),
    ) {
        val uploads = File(temporary.newFolder(), "uploads")
        val repository = MemorySites()
        val operations = FakeOperations(repository)
        val server: RemoteApiServer = NettyRemoteApiServerFactory(Netty, ServerConfig(apiPort = port), policy,
            object : ApiKeyStore { override suspend fun token() = TOKEN }, repository, RemoteUploadStorage(uploads))
            .create("127.0.0.1", operations)
        suspend fun running(block: suspend () -> Unit) {
            try { server.start(); block() } finally { server.stop() }
        }
        suspend fun portOpen(): Boolean = withContext(Dispatchers.IO) {
            runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
        }
        suspend fun request(method: String, path: String, token: String? = TOKEN, body: ByteArray? = null,
            contentType: String? = null): Response = withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 2_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val content = (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                Response(status, content, connection.getHeaderField("Cache-Control"))
            } finally { connection.disconnect() }
        }
    }

    private class FakeOperations(private val repository: MemorySites) : RemoteSiteOperations {
        @Volatile var deploys = 0
        @Volatile var receivedArchive: ByteArray? = null
        @Volatile var receivedRequest: RemoteDeployRequest? = null
        @Volatile var started: Long? = null
        @Volatile var stopped: Long? = null
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun deploy(request: RemoteDeployRequest, archive: File): Site {
            deploys++
            receivedRequest = request
            receivedArchive = withContext(Dispatchers.IO) { archive.readBytes() }
            entered?.complete(Unit)
            try { release?.await() } finally { withContext(NonCancellable) { cancelled.complete(Unit) } }
            return repository.site.copy(status = SiteStatus.RUNNING)
        }
        override suspend fun start(siteId: Long): Site { started = siteId; return repository.site.copy(status = SiteStatus.RUNNING) }
        override suspend fun stop(siteId: Long): Site { stopped = siteId; return repository.site.copy(status = SiteStatus.STOPPED) }
    }

    private class MemorySites : SiteRepository {
        val site = Site(1, "example.com", 18000, ProjectType.NODEJS, null, null, SiteStatus.STOPPED, "", 1)
        @Volatile var reads = 0
        override val sites: StateFlow<List<Site>> = MutableStateFlow(listOf(site))
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Ready(listOf(site)))
        override fun observeSite(id: Long): Flow<Site?> = sites.map { values -> values.find { it.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = sites.map { values -> values.filter { it.status == status } }
        override suspend fun listSites(): List<Site> { reads++; return listOf(site) }
        override suspend fun getSite(id: Long): Site? = site.takeIf { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site = error("Not a persistence test")
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?): Unit = error("Not a persistence test")
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean = error("Not a persistence test")
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?): Unit = error("Not a persistence test")
        override suspend fun appendLogs(id: Long, text: String): Unit = error("Not a persistence test")
        override suspend fun clearLogs(id: Long): Unit = error("Not a persistence test")
        override suspend fun deleteSiteIfInactive(id: Long): Unit = error("Not a persistence test")
    }

    private data class Response(val status: Int, val body: String, val cacheControl: String?)

    companion object {
        private val TOKEN = "a".repeat(64)
        private fun multipart(archive: ByteArray) = (
            "--wire\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n{\"siteId\":1}\r\n" +
                "--wire\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"project.zip\"\r\nContent-Type: application/zip\r\n\r\n"
            ).toByteArray() + archive + "\r\n--wire--\r\n".toByteArray()
        private fun Socket.readClosed(): Boolean = try {
            getInputStream().read() == -1
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }
}
