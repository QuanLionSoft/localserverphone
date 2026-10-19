package dev.pocketpaas.data.remote

import dev.pocketpaas.domain.deployment.DeploymentControl
import dev.pocketpaas.domain.deployment.DeploymentResult
import dev.pocketpaas.domain.deployment.ProjectArchiveSource
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.network.ManagedNetwork
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteApiServer
import dev.pocketpaas.domain.remote.RemoteApiServerFactory
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.remote.RemoteSiteOperations
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.runtime.RuntimeRecovery
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteServerRuntimeManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun apiStartsLocallyAfterOneRecoveryWithoutEagerNginxOrTunnel() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        assertEquals(listOf("recover", "key", "create:127.0.0.1", "http-start"), fixture.events)
        assertEquals(RemoteServerStatus.RUNNING, fixture.runtime.state.value.status)
        assertEquals(0, fixture.network.starts)
        fixture.runtime.start("127.0.0.1")
        assertEquals(1, fixture.created.size)
        assertEquals(1, fixture.recoveries)
    }

    @Test
    fun shutdownClosesHttpBeforeChildrenAndRestartKeepsRecoveryResult() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("0.0.0.0")
        fixture.events.clear()
        fixture.runtime.stop()
        assertEquals(listOf("http-stop", "deployment-stop-all", "network-stop-all"), fixture.events)
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
        fixture.runtime.stop()
        assertEquals(1, fixture.network.stops)
        fixture.runtime.start("127.0.0.1")
        assertEquals(1, fixture.recoveries)
        assertEquals(2, fixture.created.size)
    }

    @Test
    fun bindingChangeRequiresStopAndUnknownAddressIsRejected() = runTest {
        val fixture = Fixture()
        assertTrue(runCatching { fixture.runtime.start("::1") }.exceptionOrNull() is IllegalArgumentException)
        fixture.runtime.start("127.0.0.1")
        assertKind(RemoteErrorKind.CONFLICT) { fixture.runtime.start("0.0.0.0") }
        assertEquals("127.0.0.1", fixture.runtime.state.value.bindHost)
    }

    @Test
    fun failedHttpStartupStopsAllChildrenAndReturnsSanitizedError() = runTest {
        val fixture = Fixture()
        fixture.startFailure = IOException("secret-value-and-private-path")
        val failure = assertKind(RemoteErrorKind.FAILED) { fixture.runtime.start("127.0.0.1") }
        assertNull(failure.cause)
        assertFalse(fixture.runtime.state.value.error.orEmpty().contains("secret"))
        assertEquals(1, fixture.created.single().stops)
        assertEquals(1, fixture.deployment.stopAllCalls)
        assertEquals(1, fixture.network.stops)
        assertEquals(RemoteServerStatus.FAILED, fixture.runtime.state.value.status)
        fixture.runtime.stop()
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
    }

    @Test
    fun failedHttpCleanupRetainsOwnerAndStillAttemptsEveryChildCleanup() = runTest {
        val fixture = Fixture()
        fixture.startFailure = IOException("start failed")
        fixture.stopFailure = IOException("stop failed")
        assertKind(RemoteErrorKind.FAILED) { fixture.runtime.start("127.0.0.1") }
        val retained = fixture.created.single()
        assertEquals(1, retained.stops)
        assertEquals(1, fixture.network.stops)
        assertEquals(1, fixture.deployment.stopAllCalls)
        assertKind(RemoteErrorKind.CONFLICT) { fixture.runtime.start("127.0.0.1") }
        retained.stopFailure = null
        fixture.runtime.stop()
        assertEquals(2, retained.stops)
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
        assertEquals(1, fixture.created.size)
    }

    @Test
    fun cancellationDuringStartupClosesApiAndReturnsStoppedWhenCleanupSucceeds() = runTest {
        val fixture = Fixture()
        val starting = CompletableDeferred<Unit>()
        fixture.onStart = { starting.complete(Unit); awaitCancellation() }
        val launch = launch { fixture.runtime.start("127.0.0.1") }
        starting.await()
        launch.cancelAndJoin()
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
        assertEquals(1, fixture.created.single().stops)
        assertEquals(1, fixture.network.stops)
    }

    @Test
    fun failedRecoveryDoesNotTouchStaleChildrenAndStopCanRetryRecovery() = runTest {
        val fixture = Fixture()
        fixture.recoveryFailures = 1
        assertKind(RemoteErrorKind.FAILED) { fixture.runtime.start("127.0.0.1") }
        assertTrue(fixture.created.isEmpty())
        assertEquals(0, fixture.deployment.stopAllCalls)
        assertEquals(0, fixture.network.stops)
        fixture.runtime.stop()
        assertEquals(2, fixture.recoveries)
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
        fixture.runtime.start("127.0.0.1")
        assertEquals(2, fixture.recoveries)
    }

    @Test
    fun forceResetClearsFailedStateWithoutSpinningTermuxRecovery() = runTest {
        val fixture = Fixture()
        fixture.recoveryFailures = 5
        assertKind(RemoteErrorKind.FAILED) { fixture.runtime.start("127.0.0.1") }
        assertEquals(RemoteServerStatus.FAILED, fixture.runtime.state.value.status)
        assertEquals(1, fixture.recoveries)
        fixture.runtime.forceReset()
        assertEquals(1, fixture.recoveries)
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
        assertNull(fixture.runtime.state.value.error)
    }

    @Test
    fun failedNetworkShutdownKeepsFailedUntilRetry() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        fixture.network.failure = IOException("network process uncertain")
        assertKind(RemoteErrorKind.FAILED) { fixture.runtime.stop() }
        assertEquals(RemoteServerStatus.FAILED, fixture.runtime.state.value.status)
        assertKind(RemoteErrorKind.CONFLICT) { fixture.runtime.start("127.0.0.1") }
        fixture.network.failure = null
        fixture.runtime.stop()
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
        assertEquals(2, fixture.network.stops)
        assertEquals(2, fixture.deployment.stopAllCalls)
    }

    @Test
    fun stopCancelsActiveAndQueuedMutationBeforeWaitingForCleanup() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val first = fixture.repository.add("first.test")
        val second = fixture.repository.add("second.test")
        val entered = CompletableDeferred<Unit>()
        val rolledBack = CompletableDeferred<Unit>()
        fixture.deployment.onDeploy = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    fixture.events.add("deployment-rollback")
                    rolledBack.complete(Unit)
                }
            }
        }
        val active = async { fixture.runtime.deploy(RemoteDeployRequest(siteId = first.id), archive()) }
        entered.await()
        val queued = async { fixture.runtime.start(second.id) }
        yield()
        fixture.runtime.stop()
        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertTrue(rolledBack.isCompleted)
        assertEquals(0, fixture.deployment.startCalls)
        assertTrue(fixture.events.indexOf("deployment-rollback") < fixture.events.indexOf("deployment-stop-all"))
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
    }

    @Test
    fun operationsAreUnavailableOutsideRunningSession() = runTest {
        val fixture = Fixture()
        val site = fixture.repository.add("stopped.test")
        assertKind(RemoteErrorKind.UNAVAILABLE) { fixture.runtime.start(site.id) }
        assertKind(RemoteErrorKind.UNAVAILABLE) { fixture.runtime.stop(site.id) }
        assertKind(RemoteErrorKind.UNAVAILABLE) { fixture.runtime.deploy(RemoteDeployRequest(siteId = site.id), archive()) }
        assertEquals(0, fixture.deployment.startCalls)
    }

    @Test
    fun missingAndActiveSitesReturnTypedErrorsBeforeDeployment() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        assertKind(RemoteErrorKind.NOT_FOUND) { fixture.runtime.start(999) }
        assertKind(RemoteErrorKind.NOT_FOUND) { fixture.runtime.stop(-1) }
        val site = fixture.repository.add("running.test").copy(status = SiteStatus.RUNNING, processId = 12)
        fixture.repository.replace(site)
        assertKind(RemoteErrorKind.CONFLICT) { fixture.runtime.start(site.id) }
        assertKind(RemoteErrorKind.CONFLICT) { fixture.runtime.deploy(RemoteDeployRequest(siteId = site.id), archive()) }
        assertEquals(0, fixture.deployment.startCalls)
        assertEquals(0, fixture.deployment.deployCalls)
    }

    @Test
    fun newDeploymentNormalizesIdentityAndReturnsUpdatedRepositoryState() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val deployed = fixture.runtime.deploy(
            RemoteDeployRequest(domainName = "Example.TEST.", projectType = ProjectType.STATIC),
            archive(),
        )
        assertEquals("example.test", deployed.domainName)
        assertEquals(SiteStatus.RUNNING, deployed.status)
        assertKind(RemoteErrorKind.CONFLICT) {
            fixture.runtime.deploy(RemoteDeployRequest(domainName = "example.test", projectType = ProjectType.STATIC), archive())
        }
        assertEquals(1, fixture.deployment.deployCalls)
    }

    @Test
    fun startAndStopReturnRefreshedSites() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val site = fixture.repository.add("node.test", ProjectType.NODEJS)
        assertEquals(SiteStatus.RUNNING, fixture.runtime.start(site.id).status)
        val stopped = fixture.runtime.stop(site.id)
        assertEquals(SiteStatus.STOPPED, stopped.status)
        assertNull(stopped.processId)
    }

    @Test
    fun sixtyFifthRegisteredSiteIsRejectedBeforeCreationOrDeployment() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        repeat(64) { fixture.repository.add("registered-$it.test") }
        assertKind(RemoteErrorKind.CONFLICT) {
            fixture.runtime.deploy(RemoteDeployRequest(domainName = "extra.test", projectType = ProjectType.STATIC), archive())
        }
        assertEquals(64, fixture.repository.listSites().size)
        assertEquals(0, fixture.deployment.deployCalls)
    }

    @Test
    fun unexpectedDeploymentFailureCannotExposeOriginalMessage() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val site = fixture.repository.add("broken.test")
        fixture.deployment.onDeploy = { _, _ -> throw IOException("secret-token-private-path") }
        val failure = assertKind(RemoteErrorKind.FAILED) {
            fixture.runtime.deploy(RemoteDeployRequest(siteId = site.id), archive())
        }
        assertEquals("FAILED", failure.message)
        assertNull(failure.cause)
        assertEquals(RemoteServerStatus.RUNNING, fixture.runtime.state.value.status)
    }

    @Test
    fun mutationsAreSerializedAcrossDifferentSites() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val first = fixture.repository.add("serial-first.test")
        val second = fixture.repository.add("serial-second.test")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.deployment.onDeploy = { site, _ ->
            entered.complete(Unit)
            release.await()
            fixture.repository.updateRuntimeState(site.id, SiteStatus.RUNNING, null)
            DeploymentResult(site.id, null, "/runtime/${site.domainName}")
        }
        val active = async { fixture.runtime.deploy(RemoteDeployRequest(siteId = first.id), archive()) }
        entered.await()
        val waiting = async { fixture.runtime.start(second.id) }
        yield()
        assertEquals(0, fixture.deployment.startCalls)
        release.complete(Unit)
        active.await()
        waiting.await()
        assertEquals(1, fixture.deployment.startCalls)
    }

    @Test
    fun networkFailureKeepsLocalApiUsableAndExplicitRetrySucceeds() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        fixture.network.onStart = { throw IOException("private-cloudflare-diagnostics") }
        assertKind(RemoteErrorKind.FAILED) { fixture.runtime.startNetwork() }
        assertEquals(RemoteServerStatus.RUNNING, fixture.runtime.state.value.status)
        assertEquals(0, fixture.created.single().stops)
        fixture.network.onStart = {}
        fixture.runtime.startNetwork()
        assertEquals(2, fixture.network.starts)
        assertEquals(1, fixture.recoveries)
    }

    @Test
    fun stopCancelsNetworkStartupBeforeClosingOwnedProcesses() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val entered = CompletableDeferred<Unit>()
        fixture.network.onStart = {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { fixture.events.add("network-start-cleanup") }
            }
        }
        val starting = async { fixture.runtime.startNetwork() }
        entered.await()
        fixture.runtime.stop()
        assertTrue(starting.isCancelled)
        assertTrue(fixture.events.indexOf("network-start-cleanup") < fixture.events.indexOf("network-stop-all"))
        assertEquals(RemoteServerStatus.STOPPED, fixture.runtime.state.value.status)
    }

    @Test
    fun localArchiveAcquisitionIsNotStartedBeforeForegroundAdmission() = runTest {
        val fixture = Fixture()
        var opened = false
        val source = object : ProjectArchiveSource {
            override suspend fun <T> useArchive(action: suspend (File) -> T): T {
                opened = true
                return action(archive())
            }
        }
        assertKind(RemoteErrorKind.UNAVAILABLE) {
            fixture.runtime.deploy(RemoteDeployRequest(domainName = "local.test", projectType = ProjectType.STATIC), source)
        }
        assertFalse(opened)
        assertTrue(fixture.repository.listSites().isEmpty())
    }

    @Test
    fun stopCancelsProviderAcquisitionAndWaitsForSourceCleanup() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        val entered = CompletableDeferred<Unit>()
        val source = object : ProjectArchiveSource {
            override suspend fun <T> useArchive(action: suspend (File) -> T): T {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { fixture.events.add("source-cleanup") }
                }
            }
        }
        val copying = async {
            fixture.runtime.deploy(RemoteDeployRequest(domainName = "local.test", projectType = ProjectType.STATIC), source)
        }
        entered.await()
        fixture.runtime.stop()
        assertTrue(copying.isCancelled)
        assertEquals(0, fixture.deployment.deployCalls)
        assertTrue(fixture.events.indexOf("source-cleanup") < fixture.events.indexOf("deployment-stop-all"))
        assertTrue(fixture.repository.listSites().isEmpty())
    }

    @Test
    fun localArchiveSourceIsReleasedOnDeploymentFailureAndDoesNotStopApi() = runTest {
        val fixture = Fixture()
        fixture.runtime.start("127.0.0.1")
        fixture.deployment.onDeploy = { _, _ -> throw IOException("installation failed") }
        val sourceFile = archive()
        var released = false
        val source = object : ProjectArchiveSource {
            override suspend fun <T> useArchive(action: suspend (File) -> T): T =
                try { action(sourceFile) } finally { released = true; sourceFile.delete() }
        }
        assertKind(RemoteErrorKind.FAILED) {
            fixture.runtime.deploy(RemoteDeployRequest(domainName = "failed-local.test", projectType = ProjectType.STATIC), source)
        }
        assertTrue(released)
        assertFalse(sourceFile.exists())
        assertEquals(RemoteServerStatus.RUNNING, fixture.runtime.state.value.status)
        assertEquals(1, fixture.deployment.deployCalls)
    }

    private fun archive(): File = temporary.newFile().apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }

    private suspend fun assertKind(kind: RemoteErrorKind, action: suspend () -> Unit): RemoteOperationException {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a typed operation failure", failure is RemoteOperationException)
        return (failure as RemoteOperationException).also { assertEquals(kind, it.kind) }
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val repository = FakeRepository()
        val network = FakeNetwork(events)
        val deployment = FakeDeployment(repository, events)
        val created = mutableListOf<FakeApi>()
        var recoveries = 0
        var recoveryFailures = 0
        var startFailure: IOException? = null
        var stopFailure: IOException? = null
        var onStart: suspend () -> Unit = {}
        val runtime = RemoteServerRuntimeManager(
            factory = object : RemoteApiServerFactory {
                override fun create(bindHost: String, operations: RemoteSiteOperations): RemoteApiServer {
                    events.add("create:$bindHost")
                    return FakeApi(events, startFailure, stopFailure, onStart).also(created::add)
                }
            },
            keys = object : ApiKeyStore {
                override suspend fun token(): String { events.add("key"); return "a".repeat(64) }
            },
            recovery = RuntimeRecovery {
                recoveries++
                events.add("recover")
                if (recoveryFailures-- > 0) throw IOException("recovery failed")
            },
            network = network,
            deployment = deployment,
            sites = repository,
        )
    }

    private class FakeApi(
        private val events: MutableList<String>,
        private val startFailure: IOException?,
        var stopFailure: IOException?,
        private val onStart: suspend () -> Unit,
    ) : RemoteApiServer {
        var stops = 0
        override suspend fun start() { events.add("http-start"); startFailure?.let { throw it }; onStart() }
        override suspend fun stop() { stops++; events.add("http-stop"); stopFailure?.let { throw it } }
    }

    private class FakeNetwork(private val events: MutableList<String>) : ManagedNetwork {
        var starts = 0
        var stops = 0
        var failure: IOException? = null
        var onStart: suspend () -> Unit = {}
        override suspend fun startAll() { starts++; onStart() }
        override suspend fun stopAll() { stops++; events.add("network-stop-all"); failure?.let { throw it } }
    }

    private class FakeDeployment(
        private val sites: FakeRepository,
        private val events: MutableList<String>,
    ) : DeploymentControl {
        var deployCalls = 0
        var startCalls = 0
        var stopAllCalls = 0
        var onDeploy: suspend (Site, File) -> DeploymentResult = { site, _ ->
            sites.updateRuntimeState(site.id, SiteStatus.RUNNING, null)
            DeploymentResult(site.id, null, "/runtime/${site.domainName}")
        }
        override suspend fun deployProject(site: Site, archive: File): DeploymentResult {
            deployCalls++
            return onDeploy(site, archive)
        }
        override suspend fun startSite(siteId: Long): DeploymentResult {
            startCalls++
            sites.updateRuntimeState(siteId, SiteStatus.RUNNING, 100)
            return DeploymentResult(siteId, 100, "/runtime/project")
        }
        override suspend fun stopSite(siteId: Long) { sites.updateRuntimeState(siteId, SiteStatus.STOPPED, null) }
        override suspend fun stopAll() { stopAllCalls++; events.add("deployment-stop-all") }
    }

    private class FakeRepository : SiteRepository {
        private val rows = MutableStateFlow(emptyList<Site>())
        override val sites: StateFlow<List<Site>> = rows
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Ready(emptyList()))
        fun add(domain: String, type: ProjectType = ProjectType.STATIC): Site = Site(
            rows.value.size.toLong() + 1, domain, 12000 + rows.value.size, type, null, null, SiteStatus.STOPPED, "", 1,
        ).also { rows.value += it }
        fun replace(site: Site) { rows.value = rows.value.map { if (it.id == site.id) site else it } }
        override fun observeSite(id: Long): Flow<Site?> = rows.map { sites -> sites.firstOrNull { it.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = rows.map { sites -> sites.filter { it.status == status } }
        override suspend fun listSites(): List<Site> = rows.value
        override suspend fun getSite(id: Long): Site? = rows.value.firstOrNull { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site = add(domainName, projectType)
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) {
            replace(requireNotNull(getSite(id)).copy(status = status, processId = processId))
        }
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean {
            if (getSite(id)?.status != expectedStatus) return false
            updateRuntimeState(id, newStatus, processId)
            return true
        }
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) { replace(requireNotNull(getSite(id)).copy(publicUrl = publicUrl)) }
        override suspend fun appendLogs(id: Long, text: String) { replace(requireNotNull(getSite(id)).let { it.copy(logs = it.logs + text) }) }
        override suspend fun clearLogs(id: Long) { replace(requireNotNull(getSite(id)).copy(logs = "")) }
        override suspend fun deleteSiteIfInactive(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    }
}
