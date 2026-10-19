package dev.pocketpaas.data.deployment

import com.sun.net.httpserver.HttpServer
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.domain.deployment.DeploymentWorkspace
import dev.pocketpaas.domain.deployment.PreparedProject
import dev.pocketpaas.domain.deployment.ProjectEntryPoint
import dev.pocketpaas.domain.deployment.ProjectMetadata
import dev.pocketpaas.domain.deployment.RuntimeArchiveTransfer
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.network.SiteProxy
import dev.pocketpaas.domain.network.SiteProxySnapshot
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessOutput
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.process.ProcessStream
import dev.pocketpaas.domain.process.ProcessTermination
import dev.pocketpaas.domain.repository.SiteRepository
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real loopback listeners exercise readiness; fixture processes isolate deployment failure paths. */
class DeploymentOrchestratorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test(timeout = 15_000)
    fun staticDeployAppliesOnlyAfterActivatingAndCommitsWithoutPid() = runBlocking {
        fixture {
            val site = addSite(ProjectType.STATIC)
            val result = engine.deployProject(site, archive)
            assertEquals(site.id, result.siteId)
            assertNull(result.processId)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertEquals(0, shell.started.size)
            assertEquals(emptyList<ProcessRequest>(), shell.executed)
            assertTrue(events.indexOf("activate:${site.id}") < events.indexOf("configure:${site.id}"))
            assertTrue(events.indexOf("server-ready") < events.indexOf("configure:${site.id}"))
            assertTrue(events.indexOf("configure:${site.id}") < events.indexOf("running:${site.id}"))
        }
    }

    @Test(timeout = 15_000)
    fun nodeDeployInstallsDependenciesThenStartsWithItsAllocatedPort() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            val result = engine.deployProject(site, archive)
            val start = shell.started.single()
            assertEquals(site.internalPort.toString(), start.environment["PORT"])
            assertTrue(start.arguments().first().endsWith("/node"))
            assertTrue(start.arguments().last().endsWith("index.js"))
            assertEquals(active(site), start.workingDir.portable())
            assertTrue(shell.executed.any { "install" in it.arguments() && it.arguments().first().endsWith("/npm") })
            assertNotNull(result.processId)
            assertEquals(result.processId, sites.getSite(site.id)?.processId)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            assertTrue(events.indexOf("activate:${site.id}") < events.indexOf("command:${site.id}"))
        }
    }

    @Test(timeout = 15_000)
    fun djangoDependenciesUseProjectVenvAndRunserverHasNoReloader() = runBlocking {
        fixture {
            val site = addSite(ProjectType.DJANGO)
            engine.deployProject(site, archive)
            val commands = shell.executed.map { it.arguments() }
            val venv = commands.indexOfFirst { "venv" in it && "-m" in it }
            val pip = commands.indexOfFirst { "pip" in it && "install" in it && "-r" in it }
            assertTrue(venv >= 0)
            assertTrue(pip > venv)
            assertTrue(commands[pip].first().contains(".venv"))
            assertTrue(commands[pip].last().endsWith("requirements.txt"))
            val start = shell.started.single()
            assertTrue(start.arguments().first().contains(".venv"))
            assertTrue(start.arguments().any { it.endsWith("manage.py") })
            assertTrue("runserver" in start.arguments())
            assertTrue("--noreload" in start.arguments())
            assertTrue("127.0.0.1:${site.internalPort}" in start.arguments())
            assertEquals(active(site), start.workingDir.portable())
        }
    }

    @Test(timeout = 15_000)
    fun reactSourceIsBuiltThenItsDistDirectoryIsServedWithoutBackend() = runBlocking {
        fixture {
            val site = addSite(ProjectType.REACT)
            val result = engine.deployProject(site, archive)
            val commands = shell.executed.map { it.arguments() }
            assertTrue(commands.any { "install" in it })
            assertTrue(commands.any { "run" in it && "build" in it })
            assertEquals("${active(site)}/dist", result.rootDir)
            assertEquals(result.rootDir, proxy.configurations[site.id])
            assertEquals(0, shell.started.size)
            assertNull(result.processId)
        }
    }

    @Test(timeout = 15_000)
    fun failedInstallNeverAppliesProxyAndRollsBackActivatedProject() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            shell.failInstall = true
            expectFailure { engine.deployProject(site, archive) }
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertFalse(events.contains("configure:${site.id}"))
            assertEquals(0, shell.started.size)
            assertTrue(workspace.aborted.contains(site.id))
            assertTrue(archives.deleted.isNotEmpty())
        }
    }

    @Test(timeout = 15_000)
    fun cancelledInstallRestoresFilesAndDoesNotLeaveDeployingRecord() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            shell.blockInstall = true
            val deployment = async { engine.deployProject(site, archive) }
            withTimeout(3_000) { shell.installEntered.await() }
            deployment.cancelAndJoin()
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertTrue(workspace.aborted.contains(site.id))
            assertFalse(events.contains("configure:${site.id}"))
            assertTrue(archives.deleted.isNotEmpty())
        }
    }

    @Test(timeout = 15_000)
    fun unknownPersistedPidCannotBeKilledOrRedeployed() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS).copy(status = SiteStatus.FAILED, processId = 876_543)
            sites.put(site)
            expectFailure { engine.deployProject(site, archive) }
            expectFailure { engine.startSite(site.id) }
            assertEquals(0, archives.uploads.size)
            assertEquals(0, shell.started.size)
            assertEquals(876_543, sites.getSite(site.id)?.processId)
        }
    }

    @Test(timeout = 20_000)
    fun sixthBackendFailsBeforeTransferAndLeavesControlCapacityReserved() = runBlocking {
        fixture {
            repeat(5) { engine.deployProject(addSite(ProjectType.NODEJS), archive) }
            val rejected = addSite(ProjectType.NODEJS)
            expectFailure { withTimeout(1_000) { engine.deployProject(rejected, archive) } }
            assertEquals(5, shell.started.size)
            assertEquals(5, archives.uploads.size)
            assertEquals(5, sites.sites.value.count { it.status == SiteStatus.RUNNING })
            assertTrue(sites.getSite(rejected.id)?.status != SiteStatus.DEPLOYING)
        }
    }

    @Test(timeout = 15_000)
    fun uncertainRunningCommitStopsChildAndRestoresPriorProxyAndFiles() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            proxy.configurations[site.id] = "previous-configuration"
            sites.failNextRunningCommit = true
            expectFailure { engine.deployProject(site, archive) }
            assertEquals("previous-configuration", proxy.configurations[site.id])
            assertTrue(workspace.aborted.contains(site.id))
            assertFalse(shell.processes.single().isAlive)
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
        }
    }

    @Test(timeout = 15_000)
    fun failedProxyApplyStopsNewChildBeforeRestoringProject() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            proxy.failConfigure = true
            expectFailure { engine.deployProject(site, archive) }
            assertFalse(shell.processes.single().isAlive)
            assertTrue(events.indexOf("stop:${site.id}") < events.indexOf("abort:${site.id}"))
            assertNull(proxy.configurations[site.id])
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
        }
    }

    @Test(timeout = 15_000)
    fun backendWithoutListeningPortCannotBePublishedAndIsStoppedOnTimeout() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            shell.provideListener = false
            expectFailure { engine.deployProject(site, archive) }
            assertFalse(shell.processes.single().isAlive)
            assertFalse(events.contains("configure:${site.id}"))
            assertTrue(workspace.aborted.contains(site.id))
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
        }
    }

    @Test(timeout = 15_000)
    fun postCommitBackupCleanupFailureIsWarningAndPreservesRunningSite() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            workspace.failComplete = true
            val result = engine.deployProject(site, archive)
            assertTrue(result.cleanupWarnings.isNotEmpty())
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            assertTrue(shell.processes.single().isAlive)
            assertFalse(workspace.aborted.contains(site.id))
            assertNotNull(proxy.configurations[site.id])
            val owned = shell.processes.single()
            workspace.failComplete = false
            assertEquals(emptyList<String>(), engine.retryCleanup(site.id))
            assertEquals(emptyList<String>(), engine.retryCleanup(site.id))
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            assertEquals(owned.processId, sites.getSite(site.id)?.processId)
            assertTrue(owned.isAlive)
            assertEquals(0, owned.stopCalls.get())
            engine.stopSite(site.id)
            val restarted = engine.startSite(site.id)
            assertTrue(restarted.processId != owned.processId)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
        }
    }

    @Test(timeout = 15_000)
    fun stoppingRetriesPostCommitCleanupSoDashboardCanRestartWithoutInternalCleanupApi() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            workspace.failComplete = true
            assertTrue(engine.deployProject(site, archive).cleanupWarnings.isNotEmpty())
            val committedProcess = shell.processes.single()
            workspace.failComplete = false

            engine.stopSite(site.id)

            assertFalse(committedProcess.isAlive)
            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertTrue(events.contains("complete:${site.id}"))
            assertFalse(workspace.aborted.contains(site.id))
            assertNull(proxy.configurations[site.id])
            engine.startSite(site.id)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
        }
    }

    @Test(timeout = 15_000)
    fun globalStopRetriesRetainedCleanupDebtForFailedStaticSiteWithoutAnyProcessHandle() = runBlocking {
        fixture {
            val site = addSite(ProjectType.STATIC)
            workspace.failComplete = true
            assertTrue(engine.deployProject(site, archive).cleanupWarnings.isNotEmpty())

            expectFailure { engine.stopSite(site.id) }

            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertTrue(shell.processes.isEmpty())
            assertNull(proxy.configurations[site.id])
            assertFalse(workspace.aborted.contains(site.id))
            workspace.failComplete = false

            engine.stopAll()

            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
            assertTrue(events.contains("complete:${site.id}"))
            assertFalse(workspace.aborted.contains(site.id))
            engine.startSite(site.id)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
        }
    }

    @Test(timeout = 15_000)
    fun postCommitCallerCancellationKeepsRunningAndDoesNotAbort() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            val gate = CompletableDeferred<Unit>()
            workspace.completeGate = gate
            val deployment = async { engine.deployProject(site, archive) }
            withTimeout(3_000) { workspace.completeEntered.await() }
            val process = shell.processes.single()
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            deployment.cancel()
            gate.complete(Unit)
            withTimeout(3_000) { deployment.join() }
            assertTrue(deployment.isCancelled)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            assertEquals(process.processId, sites.getSite(site.id)?.processId)
            assertTrue(process.isAlive)
            assertEquals(0, process.stopCalls.get())
            assertFalse(workspace.aborted.contains(site.id))
            assertNotNull(proxy.configurations[site.id])
            engine.stopSite(site.id)
            assertFalse(process.isAlive)
            assertEquals(1, process.stopCalls.get())
            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
        }
    }

    @Test(timeout = 15_000)
    fun stopAndStartReuseDeployedSourcesWithoutReinstallingDependencies() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            engine.deployProject(site, archive)
            val old = shell.processes.single()
            val installCount = shell.executed.size
            engine.stopSite(site.id)
            assertFalse(old.isAlive)
            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertNull(proxy.configurations[site.id])
            val next = engine.startSite(site.id)
            assertTrue(next.processId != old.processId)
            assertEquals(installCount, shell.executed.size)
            assertEquals(1, archives.uploads.size)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            old.exit(17)
            delay(75)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
            assertEquals(next.processId, sites.getSite(site.id)?.processId)
        }
    }

    @Test(timeout = 15_000)
    fun unexpectedBackendExitClearsPidAndPublicRoute() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            engine.deployProject(site, archive)
            shell.processes.single().exit(23)
            eventually { sites.sites.value.single().status == SiteStatus.FAILED }
            eventually { proxy.configurations[site.id] == null }
            assertNull(sites.getSite(site.id)?.processId)
            assertNull(sites.getSite(site.id)?.publicUrl)
        }
    }

    @Test(timeout = 15_000)
    fun liveLogStorageFailureStopsOwnedBackendAndMarksFailure() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            engine.deployProject(site, archive)
            val process = shell.processes.single()
            sites.failNextLog = true
            process.emit("live output after commit")
            eventually { !process.isAlive }
            eventually { sites.sites.value.single().status == SiteStatus.FAILED }
            assertNull(sites.getSite(site.id)?.processId)
        }
    }

    @Test(timeout = 15_000)
    fun uncertainStopRetainsOwnerAndFilesUntilExplicitRetry() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            engine.deployProject(site, archive)
            val process = shell.processes.single()
            process.failNextStop = true
            expectFailure { engine.stopSite(site.id) }
            assertTrue(process.isAlive)
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertEquals(process.processId, sites.getSite(site.id)?.processId)
            assertFalse(workspace.aborted.contains(site.id))
            engine.stopSite(site.id)
            assertFalse(process.isAlive)
            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
        }
    }

    @Test(timeout = 15_000)
    fun failedDeploymentWithUncertainStopCompletesRollbackAfterStopRetry() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            proxy.failConfigure = true
            shell.failFirstStop = true
            expectFailure { engine.deployProject(site, archive) }
            val process = shell.processes.single()
            assertTrue(process.isAlive)
            assertEquals(process.processId, sites.getSite(site.id)?.processId)
            assertFalse(workspace.aborted.contains(site.id))
            engine.stopSite(site.id)
            assertFalse(process.isAlive)
            assertTrue(workspace.aborted.contains(site.id))
            assertTrue(events.indexOf("stop:${site.id}") < events.indexOf("abort:${site.id}"))
            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertNull(proxy.configurations[site.id])
        }
    }

    @Test(timeout = 15_000)
    fun failedRollbackBlocksStartAndRedeployUntilStopRetryFinishesIt() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            shell.failInstall = true
            workspace.abortFailures.set(1)
            expectFailure { engine.deployProject(site, archive) }
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertFalse(workspace.aborted.contains(site.id))
            expectFailure { engine.startSite(site.id) }
            expectFailure { engine.deployProject(site, archive) }
            assertEquals(1, archives.uploads.size)
            engine.stopSite(site.id)
            assertTrue(workspace.aborted.contains(site.id))
            assertEquals(SiteStatus.STOPPED, sites.getSite(site.id)?.status)
            shell.failInstall = false
            engine.deployProject(site, archive)
            assertEquals(2, archives.uploads.size)
            assertEquals(SiteStatus.RUNNING, sites.getSite(site.id)?.status)
        }
    }

    @Test(timeout = 15_000)
    fun uncertainClaimWriteIsRecoveredBeforeAnyArchiveOrProcessOperation() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            sites.failNextClaimAfterWrite = true
            expectFailure { engine.deployProject(site, archive) }
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertEquals(0, archives.uploads.size)
            assertEquals(0, shell.started.size)
            assertFalse(workspace.aborted.contains(site.id))
            assertTrue(sites.getSite(site.id)?.logs.orEmpty().contains("Uncertain claim write failed"))
        }
    }

    @Test(timeout = 15_000)
    fun uncertainStartClaimWriteIsRecoveredWithoutLaunchingBackend() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            sites.failNextClaimAfterWrite = true
            expectFailure { engine.startSite(site.id) }
            assertEquals(SiteStatus.FAILED, sites.getSite(site.id)?.status)
            assertNull(sites.getSite(site.id)?.processId)
            assertEquals(0, shell.started.size)
            assertTrue(sites.getSite(site.id)?.logs.orEmpty().contains("Uncertain claim write failed"))
        }
    }

    @Test(timeout = 15_000)
    fun rejectedClaimCannotOverwriteStateCommittedByAnotherOperation() = runBlocking {
        fixture {
            val site = addSite(ProjectType.NODEJS)
            sites.rejectNextClaimWithConcurrentDeployment = true
            expectFailure { engine.deployProject(site, archive) }
            val stored = sites.getSite(site.id)
            assertEquals(SiteStatus.DEPLOYING, stored?.status)
            assertEquals(7_777, stored?.processId)
            assertEquals("external operation", stored?.logs)
            assertEquals(0, archives.uploads.size)
            assertEquals(0, shell.started.size)
            assertFalse(workspace.aborted.contains(site.id))
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(temporaryFolder.newFile("project.zip").also { it.writeText("fixture archive") })
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(3_000) { while (!predicate()) delay(5) }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable =
        checkNotNull(runCatching { block() }.exceptionOrNull()) { "Expected deployment operation to fail." }

    private class Fixture(val archive: File) {
        val events = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sites = MemorySites(events)
        val archives = MemoryArchives(events)
        val workspace = MemoryWorkspace(events)
        val proxy = MemoryProxy(events)
        val shell = FixtureExecutor(sites, workspace, events)
        val engine = DeploymentOrchestrator(shell, archives, workspace, proxy, sites,
            DeploymentPolicy(startupTimeoutMillis = 1_000, readinessPollMillis = 10, stableReadinessMillis = 30),
            ProcessPolicy(), scope)

        suspend fun addSite(type: ProjectType): Site = sites.createSite("site-${sites.sites.value.size + 1}.example", type)

        suspend fun close() {
            workspace.completeGate?.complete(Unit)
            shell.processes.forEach { it.failNextStop = false }
            runCatching { engine.stopAll() }
            shell.processes.forEach { runCatching { it.stop() } }
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    private class MemoryArchives(private val events: MutableList<String>) : RuntimeArchiveTransfer {
        val uploads = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<String>()
        override suspend fun upload(source: File, archiveId: String, maximumBytes: Long): String {
            require(source.length() <= maximumBytes)
            uploads.add(archiveId)
            events.add("upload:$archiveId")
            return "$ROOT/runtime/uploads/$archiveId.zip"
        }
        override suspend fun delete(archivePath: String) { deleted.add(archivePath) }
    }

    private class MemoryWorkspace(private val events: MutableList<String>) : DeploymentWorkspace {
        val aborted = CopyOnWriteArrayList<Long>()
        val built = ConcurrentHashMap.newKeySet<String>()
        val abortFailures = AtomicInteger()
        val completeEntered = CompletableDeferred<Unit>()
        @Volatile var completeGate: CompletableDeferred<Unit>? = null
        @Volatile var failComplete = false
        override suspend fun prepare(site: Site, archivePath: String, deploymentId: String): PreparedProject =
            PreparedProject(deploymentId, "$ROOT/htdocs/.deploy-$deploymentId", active(site),
                "$ROOT/htdocs/.backup-$deploymentId", archivePath, false, metadata(site))
        override suspend fun activate(site: Site, prepared: PreparedProject) { events.add("activate:${site.id}") }
        override suspend fun rollback(site: Site, prepared: PreparedProject) = abort(site, prepared.deploymentId)
        override suspend fun abort(site: Site, deploymentId: String) {
            if (abortFailures.getAndUpdate { count -> if (count > 0) count - 1 else 0 } > 0) {
                throw IOException("Project rollback failed.")
            }
            aborted.add(site.id)
            events.add("abort:${site.id}")
        }
        override suspend fun complete(site: Site, prepared: PreparedProject) {
            completeEntered.complete(Unit)
            completeGate?.await()
            if (failComplete) throw IOException("Backup cleanup failed.")
            events.add("complete:${site.id}")
        }
        override suspend fun inspect(site: Site): ProjectMetadata = metadata(site)
        private fun metadata(site: Site): ProjectMetadata = ProjectMetadata(
            rootDir = active(site) + if (site.projectType == ProjectType.REACT && site.domainName in built) "/dist" else "",
            entryPoint = ProjectEntryPoint.valueOf(site.projectType.name),
            hasRequirements = site.projectType == ProjectType.DJANGO,
            hasPackageJson = site.projectType == ProjectType.NODEJS || site.projectType == ProjectType.REACT,
        )
    }

    private class MemoryProxy(private val events: MutableList<String>) : SiteProxy {
        val configurations = ConcurrentHashMap<Long, String>()
        @Volatile var failConfigure = false
        override suspend fun ensureServerRunning() { events.add("server-ready") }
        override suspend fun snapshot(site: Site): SiteProxySnapshot = SiteProxySnapshot(configurations[site.id])
        override suspend fun configure(site: Site, rootDir: String) {
            configurations[site.id] = rootDir
            events.add("configure:${site.id}")
            if (failConfigure) throw IOException("Uncertain proxy apply failed.")
        }
        override suspend fun restore(site: Site, snapshot: SiteProxySnapshot) {
            val value = snapshot.configuration
            if (value == null) configurations.remove(site.id) else configurations[site.id] = value
            events.add("restore:${site.id}")
        }
    }

    private class FixtureExecutor(
        private val sites: MemorySites,
        private val workspace: MemoryWorkspace,
        private val events: MutableList<String>,
    ) : ProcessExecutor {
        val executed = CopyOnWriteArrayList<ProcessRequest>()
        val started = CopyOnWriteArrayList<ProcessRequest>()
        val processes = CopyOnWriteArrayList<FixtureProcess>()
        val installEntered = CompletableDeferred<Unit>()
        private val ids = AtomicInteger(4_000)
        @Volatile var failInstall = false
        @Volatile var blockInstall = false
        @Volatile var provideListener = true
        @Volatile var failFirstStop = false

        override suspend fun execute(request: ProcessRequest, timeoutMillis: Long): ProcessResult {
            require(timeoutMillis > 0)
            executed.add(request)
            val site = sites.listSites().first { request.workingDir.portable() == active(it) }
            events.add("command:${site.id}")
            val arguments = request.arguments()
            if ("install" in arguments) {
                installEntered.complete(Unit)
                if (blockInstall) CompletableDeferred<Unit>().await()
                if (failInstall) return processResult(1, ProcessTermination.EXITED, "dependency install failed")
            }
            if ("run" in arguments && "build" in arguments) workspace.built.add(site.domainName)
            return processResult(0, ProcessTermination.EXITED)
        }

        override suspend fun executeCommand(command: String, envVars: Map<String, String>, workingDir: File): ProcessResult =
            execute(ProcessRequest(ProcessCommand.Shell(command), envVars, workingDir), 1_000)

        override suspend fun startProcess(request: ProcessRequest): ManagedProcess {
            started.add(request)
            val site = sites.listSites().first { request.workingDir.portable() == active(it) }
            return FixtureProcess(ids.incrementAndGet(), site, events, provideListener).also {
                it.failNextStop = failFirstStop
                failFirstStop = false
                processes.add(it)
            }
        }

        override suspend fun startProcess(arguments: List<String>, envVars: Map<String, String>, workingDir: File): ManagedProcess =
            startProcess(ProcessRequest(ProcessCommand.Arguments(arguments), envVars, workingDir))

        override suspend fun resolveBinary(name: String): String = "/runtime/bin/$name"
        override suspend fun runtimeLayout(): RuntimeLayout = RuntimeLayout(ROOT, "$ROOT/htdocs", "$ROOT/nginx/nginx.conf",
            "$ROOT/nginx/conf.d", "$ROOT/runtime", "$ROOT/logs")
    }

    private class FixtureProcess(
        override val processId: Int,
        private val site: Site,
        private val events: MutableList<String>,
        provideListener: Boolean,
    ) : ManagedProcess {
        override val sessionId: String = UUID.randomUUID().toString()
        private val completion = CompletableDeferred<ProcessResult>()
        private val output = MutableSharedFlow<ProcessOutput>(replay = 128, extraBufferCapacity = 128)
        override val logs: SharedFlow<ProcessOutput> = output
        private val sequence = AtomicLong()
        val stopCalls = AtomicInteger()
        private val listener = if (provideListener) HttpServer.create(InetSocketAddress("127.0.0.1", site.internalPort), 0) else null
        @Volatile var failNextStop = false
        val isAlive: Boolean get() = !completion.isCompleted

        init {
            listener?.createContext("/") { exchange ->
                exchange.use {
                    val body = "ready".toByteArray()
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.use { response -> response.write(body) }
                }
            }
            listener?.start()
        }

        override suspend fun await(): ProcessResult = completion.await()
        override suspend fun stop(): ProcessResult {
            stopCalls.incrementAndGet()
            if (failNextStop) {
                failNextStop = false
                throw IOException("Uncertain backend stop failed.")
            }
            if (completion.complete(processResult(0, ProcessTermination.STOPPED).copy(processId = processId))) {
                listener?.stop(0)
                events.add("stop:${site.id}")
            }
            return completion.await()
        }
        fun exit(code: Int) {
            if (completion.complete(processResult(code, ProcessTermination.EXITED).copy(processId = processId))) listener?.stop(0)
        }
        fun emit(text: String) {
            check(output.tryEmit(ProcessOutput(sessionId, ProcessStream.STDOUT, text, sequence.incrementAndGet(), System.currentTimeMillis())))
        }
    }

    private class MemorySites(private val events: MutableList<String>) : SiteRepository {
        private val values = MutableStateFlow<List<Site>>(emptyList())
        override val sites: StateFlow<List<Site>> = values
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Loading)
        @Volatile var failNextRunningCommit = false
        @Volatile var failNextLog = false
        @Volatile var failNextClaimAfterWrite = false
        @Volatile var rejectNextClaimWithConcurrentDeployment = false
        override fun observeSite(id: Long): Flow<Site?> = values.map { list -> list.firstOrNull { it.id == id } }
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = values.map { list -> list.filter { it.status == status } }
        override suspend fun listSites(): List<Site> = values.value
        override suspend fun getSite(id: Long): Site? = values.value.firstOrNull { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site {
            val site = Site(values.value.size.toLong() + 1, domainName, freePort(), projectType,
                null, null, SiteStatus.STOPPED, "", 1)
            put(site)
            return site
        }
        fun put(site: Site) { values.update { list -> list.filterNot { it.id == site.id } + site } }
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) {
            update(id) { it.copy(status = status, processId = processId) }
            if (status == SiteStatus.RUNNING) {
                events.add("running:$id")
                if (failNextRunningCommit) {
                    failNextRunningCommit = false
                    throw IOException("Uncertain database running commit failed.")
                }
            }
        }
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?): Boolean {
            if (newStatus == SiteStatus.DEPLOYING && rejectNextClaimWithConcurrentDeployment) {
                rejectNextClaimWithConcurrentDeployment = false
                update(id) { it.copy(status = SiteStatus.DEPLOYING, processId = 7_777, logs = "external operation") }
                return false
            }
            if (getSite(id)?.status != expectedStatus) return false
            updateRuntimeState(id, newStatus, processId)
            if (newStatus == SiteStatus.DEPLOYING && failNextClaimAfterWrite) {
                failNextClaimAfterWrite = false
                throw IOException("Uncertain claim write failed.")
            }
            return true
        }
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) { update(id) { it.copy(publicUrl = publicUrl) } }
        override suspend fun appendLogs(id: Long, text: String) {
            if (failNextLog) {
                failNextLog = false
                throw IOException("Live log persistence failed.")
            }
            update(id) { it.copy(logs = it.logs + text) }
        }
        override suspend fun clearLogs(id: Long) { update(id) { it.copy(logs = "") } }
        override suspend fun deleteSiteIfInactive(id: Long) { values.update { list -> list.filterNot { it.id == id } } }
        private fun update(id: Long, transform: (Site) -> Site) {
            check(values.value.any { it.id == id })
            values.update { list -> list.map { if (it.id == id) transform(it) else it } }
        }
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }

    private companion object {
        const val ROOT = "/termux/home/.pocket-paas"
        fun active(site: Site): String = "$ROOT/htdocs/${site.domainName}"
        fun File.portable(): String = path.replace('\\', '/').removePrefix("C:")
        fun ProcessRequest.arguments(): List<String> = when (val value = command) {
            is ProcessCommand.Arguments -> value.values
            is ProcessCommand.Shell -> value.value.split(' ')
        }
        fun processResult(code: Int, termination: ProcessTermination, stderr: String = "") =
            ProcessResult(null, code, "", stderr, 1, termination, false, false)
    }
}
