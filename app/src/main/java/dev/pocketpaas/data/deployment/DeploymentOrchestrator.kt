package dev.pocketpaas.data.deployment

import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.core.config.ProcessPolicy
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.domain.deployment.DeploymentResult
import dev.pocketpaas.domain.deployment.DeploymentControl
import dev.pocketpaas.domain.deployment.DeploymentWorkspace
import dev.pocketpaas.domain.deployment.ProjectEntryPoint
import dev.pocketpaas.domain.deployment.ProjectMetadata
import dev.pocketpaas.domain.deployment.PreparedProject
import dev.pocketpaas.domain.deployment.RuntimeArchiveTransfer
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.network.SiteProxy
import dev.pocketpaas.domain.network.SiteProxySnapshot
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.repository.SiteRepository
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Serializes deployments and owns only backend handles created in this application session. */
@Singleton
class DeploymentOrchestrator @Inject constructor(
    private val shell: ProcessExecutor,
    private val archives: RuntimeArchiveTransfer,
    private val workspace: DeploymentWorkspace,
    private val proxy: SiteProxy,
    private val sites: SiteRepository,
    private val policy: DeploymentPolicy,
    private val processPolicy: ProcessPolicy,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : DeploymentControl {
    private val lifecycle = Mutex()
    private val active = mutableMapOf<Long, Backend>()
    private val pendingRollbacks = mutableMapOf<Long, String>()
    private val cleanupDebts = mutableMapOf<Long, PreparedProject>()

    init {
        require(policy.maximumBackendProcesses <= processPolicy.maxLongRunningProcesses - 2) {
            "Deployment capacity must reserve two daemons and one command slot for the network engine."
        }
    }

    override suspend fun deployProject(site: Site, archive: File): DeploymentResult = lifecycle.withLock {
        require(archive.isFile && archive.length() in 1..policy.maximumArchiveBytes) { "Invalid ZIP archive size." }
        val registered = requireInactive(site)
        requireCapacity(registered)
        val deploymentId = UUID.randomUUID().toString()
        var archivePath: String? = null
        var preparedAttempted = false
        var proxySnapshot: SiteProxySnapshot? = null
        var proxyAttempted = false
        var backend: Backend? = null
        var claimed = false
        var committed = false
        try {
            withContext(NonCancellable) {
                try { claim(registered); claimed = true } catch (failure: Throwable) {
                    if (failure !is ClaimRejected) claimed = isClaimPersisted(registered)
                    throw failure
                }
            }
            currentCoroutineContext().ensureActive()
            log(registered.id, "Deployment $deploymentId started.")
            proxySnapshot = proxy.snapshot(registered)
            if (proxySnapshot.configuration != null) {
                proxyAttempted = true
                proxy.restore(registered, SiteProxySnapshot(null))
            }
            archivePath = archives.upload(archive, deploymentId, policy.maximumArchiveBytes)
            preparedAttempted = true
            val prepared = workspace.prepare(registered, archivePath, deploymentId)
            // Dependencies must be installed at their final path: Python venvs contain absolute paths.
            workspace.activate(registered, prepared)
            installDependencies(registered, prepared.metadata)
            val metadata = workspace.inspect(registered)
            backend = startBackend(registered, metadata)
            backend?.let {
                requireValidPid(it)
                sites.updateRuntimeState(registered.id, SiteStatus.DEPLOYING, it.handle.processId)
                awaitBackendReady(registered, it)
            }
            proxy.ensureServerRunning()
            proxyAttempted = true
            proxy.configure(registered, metadata.rootDir)
            backend?.let { ensureAlive(it) }
            check(sites.compareAndSetRuntimeState(registered.id, SiteStatus.DEPLOYING, SiteStatus.RUNNING, backend?.handle?.processId)) {
                "The deployment lost its database state ownership."
            }
            committed = true
            backend?.let { watch(registered, it) }
            cleanupDebts[registered.id] = prepared
            // RUNNING is the commit point. Failure to prune the old backup is cleanup debt, not a rollback.
            val warnings = withContext(NonCancellable) {
                val warnings = finishCleanup(registered, prepared)
                bestEffortLog(registered.id, "Deployment is running.${warnings.joinToString(separator = "\n", prefix = if (warnings.isEmpty()) "" else "\n")}")
                warnings
            }
            DeploymentResult(registered.id, backend?.handle?.processId, metadata.rootDir, warnings)
        } catch (failure: Throwable) {
            if (!claimed || committed) throw failure
            withContext(NonCancellable) {
                var stopped = true
                backend?.let { owned ->
                    runCatching { stopBackend(registered.id, owned) }.exceptionOrNull()?.let {
                        stopped = false
                        suppress(failure, it)
                    }
                }
                if (stopped) {
                    var restored = !preparedAttempted
                    if (preparedAttempted) {
                        pendingRollbacks[registered.id] = deploymentId
                        runCatching { workspace.abort(registered, deploymentId) }.onSuccess {
                            restored = true
                            pendingRollbacks.remove(registered.id)
                        }
                            .exceptionOrNull()?.let { suppress(failure, it) }
                    }
                    if (proxyAttempted && proxySnapshot != null) {
                        val safeSnapshot = if (restored) checkNotNull(proxySnapshot) else SiteProxySnapshot(null)
                        runCatching { proxy.restore(registered, safeSnapshot) }.exceptionOrNull()?.let { suppress(failure, it) }
                    }
                } else if (preparedAttempted) {
                    pendingRollbacks[registered.id] = deploymentId
                }
                archivePath?.let { path -> runCatching { archives.delete(path) }.exceptionOrNull()?.let { suppress(failure, it) } }
                recordFailure(registered.id, failure, if (stopped) null else backend?.handle?.processId)
            }
            throw failure
        }
    }

    override suspend fun startSite(siteId: Long): DeploymentResult = lifecycle.withLock {
        val site = requireNotNull(sites.getSite(siteId)) { "Site $siteId does not exist." }
        val registered = requireInactive(site)
        requireCapacity(registered)
        var backend: Backend? = null
        var snapshot: SiteProxySnapshot? = null
        var proxyAttempted = false
        var claimed = false
        var committed = false
        try {
            withContext(NonCancellable) {
                try { claim(registered); claimed = true } catch (failure: Throwable) {
                    if (failure !is ClaimRejected) claimed = isClaimPersisted(registered)
                    throw failure
                }
            }
            currentCoroutineContext().ensureActive()
            snapshot = proxy.snapshot(registered)
            val metadata = workspace.inspect(registered)
            backend = startBackend(registered, metadata)
            backend?.let {
                requireValidPid(it)
                sites.updateRuntimeState(siteId, SiteStatus.DEPLOYING, it.handle.processId)
                awaitBackendReady(registered, it)
            }
            proxy.ensureServerRunning()
            proxyAttempted = true
            proxy.configure(registered, metadata.rootDir)
            backend?.let { ensureAlive(it) }
            check(sites.compareAndSetRuntimeState(siteId, SiteStatus.DEPLOYING, SiteStatus.RUNNING, backend?.handle?.processId)) {
                "The start operation lost its database state ownership."
            }
            committed = true
            backend?.let { watch(registered, it) }
            bestEffortLog(siteId, "Site started.")
            DeploymentResult(siteId, backend?.handle?.processId, metadata.rootDir)
        } catch (failure: Throwable) {
            if (!claimed || committed) throw failure
            withContext(NonCancellable) {
                var stopped = true
                backend?.let { owned -> runCatching { stopBackend(siteId, owned) }.exceptionOrNull()?.let {
                    stopped = false
                    suppress(failure, it)
                } }
                if (stopped && proxyAttempted && snapshot != null) {
                    runCatching { proxy.restore(registered, checkNotNull(snapshot)) }.exceptionOrNull()?.let { suppress(failure, it) }
                }
                recordFailure(siteId, failure, if (stopped) null else backend?.handle?.processId)
            }
            throw failure
        }
    }

    override suspend fun stopSite(siteId: Long) = lifecycle.withLock { stopSiteLocked(siteId) }

    suspend fun retryCleanup(siteId: Long): List<String> = lifecycle.withLock {
        val site = requireNotNull(sites.getSite(siteId)) { "Site $siteId does not exist." }
        val prepared = cleanupDebts[siteId] ?: return@withLock emptyList()
        withContext(NonCancellable) { finishCleanup(site, prepared) }
    }

    override suspend fun stopAll() = lifecycle.withLock {
        val failures = mutableListOf<Throwable>()
        sites.listSites().filter {
            it.status == SiteStatus.RUNNING || it.processId != null || active.containsKey(it.id) ||
                pendingRollbacks.containsKey(it.id) || cleanupDebts.containsKey(it.id)
        }.forEach {
            runCatching { stopSiteLocked(it.id) }.exceptionOrNull()?.let(failures::add)
        }
        if (failures.isNotEmpty()) {
            val failure = IOException("Some site runtimes could not be stopped.", failures.first())
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun stopSiteLocked(siteId: Long) = withContext(NonCancellable) {
        val site = requireNotNull(sites.getSite(siteId)) { "Site $siteId does not exist." }
        val backend = active[siteId]
        if (backend == null && site.processId != null) {
            throw IOException("This application session does not own the site's PID; wait for agent lease cleanup before recovery.")
        }
        try {
            backend?.let { stopBackend(siteId, it) }
            proxy.restore(site, SiteProxySnapshot(null))
            pendingRollbacks[siteId]?.let { deploymentId ->
                workspace.abort(site, deploymentId)
                pendingRollbacks.remove(siteId)
            }
            cleanupDebts[siteId]?.let { prepared ->
                // A committed project's journal must be completed, never rolled back. Retain
                // cleanup debt on failure so global Stop can retry even without a live PID.
                val warnings = finishCleanup(site, prepared)
                if (warnings.isNotEmpty()) {
                    bestEffortLog(siteId, warnings.joinToString("\n"))
                    throw IOException("Deployment cleanup is incomplete. Retry Stop.")
                }
            }
            sites.updatePublicUrl(siteId, null)
            sites.updateRuntimeState(siteId, SiteStatus.STOPPED, null)
            bestEffortLog(siteId, "Site stopped.")
        } catch (failure: Throwable) {
            recordFailure(siteId, failure, active[siteId]?.handle?.processId)
            throw failure
        }
    }

    private suspend fun requireInactive(requested: Site): Site {
        check(applicationScope.isActive) { "The application runtime scope has already stopped." }
        require(requested.domainName == SiteInput.normalizeDomainName(requested.domainName)) { "Site domain is not canonical." }
        val site = requireNotNull(sites.getSite(requested.id)) { "The site must be registered before deployment." }
        require(site.domainName == requested.domainName && site.internalPort == requested.internalPort && site.projectType == requested.projectType) {
            "The requested site identity is stale."
        }
        require(site.status == SiteStatus.STOPPED || site.status == SiteStatus.FAILED) { "Stop the site before deploying or starting it." }
        require(site.processId == null && active[site.id] == null) { "The site's previous process has not been confirmed stopped." }
        require(!pendingRollbacks.containsKey(site.id)) { "Retry stop to finish the site's pending deployment rollback." }
        require(!cleanupDebts.containsKey(site.id)) { "Retry deployment cleanup before replacing this site." }
        return site
    }

    private fun requireCapacity(site: Site) {
        if (site.projectType == dev.pocketpaas.domain.model.ProjectType.NODEJS || site.projectType == dev.pocketpaas.domain.model.ProjectType.DJANGO) {
            check(active.size < policy.maximumBackendProcesses) { "Backend capacity reached; stop a site before starting another." }
        }
    }

    private suspend fun claim(site: Site) {
        if (!sites.compareAndSetRuntimeState(site.id, site.status, SiteStatus.DEPLOYING, null)) throw ClaimRejected()
    }

    private suspend fun isClaimPersisted(site: Site): Boolean = runCatching {
        val stored = sites.getSite(site.id)
        stored?.status == SiteStatus.DEPLOYING && stored.processId == null && stored.domainName == site.domainName &&
            stored.internalPort == site.internalPort && stored.projectType == site.projectType
    }.getOrDefault(false)

    private suspend fun installDependencies(site: Site, metadata: ProjectMetadata) {
        val layout = shell.runtimeLayout()
        val workingDir = File(layout.projectsPath, site.domainName)
        when (metadata.entryPoint) {
            ProjectEntryPoint.NODEJS, ProjectEntryPoint.REACT -> {
                require(metadata.hasPackageJson) { "package.json is required." }
                val npm = shell.resolveBinary("npm")
                command(site.id, "npm install", listOf(npm, "install", "--no-audit", "--no-fund"), workingDir, policy.installTimeoutMillis)
                if (metadata.entryPoint == ProjectEntryPoint.REACT) {
                    command(site.id, "npm run build", listOf(npm, "run", "build"), workingDir, policy.buildTimeoutMillis)
                }
            }
            ProjectEntryPoint.DJANGO, ProjectEntryPoint.FLASK -> {
                require(metadata.hasRequirements) { "requirements.txt is required." }
                val python = shell.resolveBinary("python")
                command(site.id, "Create isolated Python environment", listOf(python, "-m", "venv", ".venv"), workingDir, policy.installTimeoutMillis)
                val isolatedPython = "${workingDir.path.replace('\\', '/')}/.venv/bin/python"
                command(site.id, "pip install requirements", listOf(isolatedPython, "-m", "pip", "install", "--disable-pip-version-check", "-r", "requirements.txt"), workingDir, policy.installTimeoutMillis)
            }
            ProjectEntryPoint.STATIC -> Unit
        }
    }

    private suspend fun command(id: Long, label: String, argv: List<String>, directory: File, timeoutMillis: Long) {
        log(id, "$label started.")
        val result = shell.execute(
            ProcessRequest(ProcessCommand.Arguments(argv), mapOf("CI" to "true", "PYTHONUNBUFFERED" to "1", "PIP_NO_INPUT" to "1"), directory),
            timeoutMillis,
        )
        if (result.stdout.isNotEmpty()) sites.appendLogs(id, "[stdout]\n${result.stdout}")
        if (result.stderr.isNotEmpty()) sites.appendLogs(id, "[stderr]\n${result.stderr}")
        if (result.stdoutTruncated || result.stderrTruncated) log(id, "$label output was truncated to the configured log tail.")
        if (!result.isSuccessful) throw IOException("$label failed (${result.termination}, exit=${result.exitCode}).")
        log(id, "$label completed.")
    }

    private suspend fun startBackend(site: Site, metadata: ProjectMetadata): Backend? {
        if (metadata.entryPoint == ProjectEntryPoint.STATIC || metadata.entryPoint == ProjectEntryPoint.REACT) return null
        requireCapacity(site)
        check(!isPortOpen(site.internalPort)) { "Site port is already occupied." }
        val layout = shell.runtimeLayout()
        val directory = File(layout.projectsPath, site.domainName)
        val python = "${layout.projectsPath}/${site.domainName}/.venv/bin/python"
        val arguments = when (metadata.entryPoint) {
            ProjectEntryPoint.NODEJS -> listOf(shell.resolveBinary("node"), "index.js")
            ProjectEntryPoint.DJANGO -> listOf(python, "manage.py", "runserver", "127.0.0.1:${site.internalPort}", "--noreload")
            ProjectEntryPoint.FLASK -> listOf(python, "-m", "flask", "--app", "app", "run", "--host", "127.0.0.1", "--port", site.internalPort.toString(), "--no-reload", "--no-debugger")
            ProjectEntryPoint.STATIC, ProjectEntryPoint.REACT -> error("Static projects do not need a backend process.")
        }
        val environment = mapOf("PORT" to site.internalPort.toString(), "HOST" to "127.0.0.1", "NODE_ENV" to "production", "PYTHONUNBUFFERED" to "1", "FLASK_DEBUG" to "0")
        val handle = shell.startProcess(arguments, environment, directory)
        val backend = Backend(handle, applicationScope.async(start = CoroutineStart.UNDISPATCHED) { handle.await() })
        active[site.id] = backend
        backend.logJob = applicationScope.launch {
            try {
                handle.logs.collect { output -> sites.appendLogs(site.id, "[${output.stream}] ${output.text}") }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                backend.logFailure.complete(failure)
            }
        }
        return backend
    }

    private fun requireValidPid(backend: Backend) {
        val pid = backend.handle.processId
        if (pid == null || pid <= 0) throw IOException("The runtime did not return a valid backend PID.")
    }

    private suspend fun awaitBackendReady(site: Site, backend: Backend) = withTimeout(policy.startupTimeoutMillis) {
        var readySince: Long? = null
        while (true) {
            ensureAlive(backend)
            if (isPortOpen(site.internalPort)) {
                val now = System.nanoTime()
                if (readySince == null) readySince = now
                if (now - checkNotNull(readySince) >= policy.stableReadinessMillis * 1_000_000) {
                    ensureAlive(backend)
                    return@withTimeout
                }
            } else readySince = null
            delay(policy.readinessPollMillis)
        }
    }

    private suspend fun ensureAlive(backend: Backend) {
        check(applicationScope.isActive) { "The application runtime scope has stopped." }
        if (backend.logFailure.isCompleted) throw IOException("Live process logs could not be persisted.", backend.logFailure.await())
        if (backend.completion.isCompleted) {
            val result = backend.completion.await()
            throw IOException("Backend exited before readiness (exit=${result.exitCode}).")
        }
    }

    private suspend fun isPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun watch(site: Site, backend: Backend) {
        backend.watchJob = applicationScope.launch {
            val failure = try {
                select<Throwable> {
                    backend.completion.onAwait { IOException("Backend exited unexpectedly (exit=${it.exitCode}).") }
                    backend.logFailure.onAwait { IOException("Live process logs could not be persisted.", it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (other: Throwable) { other }
            lifecycle.withLock {
                if (active[site.id] !== backend) return@withLock
                withContext(NonCancellable) {
                    var stopped = false
                    runCatching { stopBackend(site.id, backend, cancelWatcher = false) }.onSuccess { stopped = true }
                        .exceptionOrNull()?.let { suppress(failure, it) }
                    if (stopped) runCatching { proxy.restore(site, SiteProxySnapshot(null)) }.exceptionOrNull()?.let { suppress(failure, it) }
                    recordFailure(site.id, failure, if (stopped) null else backend.handle.processId)
                }
            }
        }
    }

    private suspend fun stopBackend(siteId: Long, backend: Backend, cancelWatcher: Boolean = true) {
        backend.handle.stop()
        if (active[siteId] === backend) active.remove(siteId)
        backend.logJob?.cancelAndJoin()
        if (cancelWatcher) backend.watchJob?.cancel()
    }

    private suspend fun recordFailure(id: Long, failure: Throwable, retainedPid: Int?) {
        runCatching { sites.updateRuntimeState(id, SiteStatus.FAILED, retainedPid?.takeIf { it > 0 }) }.exceptionOrNull()?.let { suppress(failure, it) }
        runCatching { sites.updatePublicUrl(id, null) }.exceptionOrNull()?.let { suppress(failure, it) }
        bestEffortLog(id, "Operation failed: ${description(failure)}${failure.suppressed.joinToString(separator = "", transform = { "\nCleanup: ${description(it)}" })}")
    }

    private suspend fun finishCleanup(site: Site, prepared: PreparedProject): List<String> {
        val warnings = mutableListOf<String>()
        runCatching { workspace.complete(site, prepared) }.exceptionOrNull()?.let {
            warnings += "Previous deployment cleanup failed: ${description(it)}"
        }
        runCatching { archives.delete(prepared.archivePath) }.exceptionOrNull()?.let {
            warnings += "Uploaded archive cleanup failed: ${description(it)}"
        }
        if (warnings.isEmpty()) cleanupDebts.remove(site.id)
        return warnings
    }

    private suspend fun log(id: Long, text: String) = sites.appendLogs(id, "[deployment] $text\n")
    private suspend fun bestEffortLog(id: Long, text: String) { runCatching { log(id, text) } }
    private fun description(failure: Throwable): String = (failure.message ?: failure.javaClass.simpleName).replace('\u0000', ' ').take(512)
    private fun suppress(primary: Throwable, secondary: Throwable) { if (primary !== secondary) primary.addSuppressed(secondary) }

    private class Backend(val handle: ManagedProcess, val completion: Deferred<ProcessResult>) {
        val logFailure = CompletableDeferred<Throwable>()
        var logJob: Job? = null
        var watchJob: Job? = null
    }

    private class ClaimRejected : IllegalStateException("Another operation already claimed this site.")
}
