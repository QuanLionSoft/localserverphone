package dev.pocketpaas.data.remote

import dev.pocketpaas.domain.deployment.DeploymentControl
import dev.pocketpaas.domain.deployment.ProjectArchiveSource
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.model.SiteStillActiveException
import dev.pocketpaas.domain.network.ManagedNetwork
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.LocalSiteOperations
import dev.pocketpaas.domain.remote.RemoteApiServer
import dev.pocketpaas.domain.remote.RemoteApiServerFactory
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.runtime.RuntimeRecovery
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One foreground-owned session: admission closes before HTTP and child processes are stopped. */
@Singleton
class RemoteServerRuntimeManager @Inject constructor(
    private val factory: RemoteApiServerFactory,
    private val keys: ApiKeyStore,
    private val recovery: RuntimeRecovery,
    private val network: ManagedNetwork,
    private val deployment: DeploymentControl,
    private val sites: SiteRepository,
) : RemoteServerRuntime, LocalSiteOperations {
    private val lifecycle = Mutex()
    private val mutations = Mutex()
    private val operationLock = Any()
    private val operations = mutableSetOf<Job>()
    private var activeOperationJob: Job? = null
    private var lifecycleJob: Job? = null
    private val mutableState = MutableStateFlow(RemoteServerState())
    override val state: StateFlow<RemoteServerState> = mutableState.asStateFlow()
    private var server: RemoteApiServer? = null
    private var recovered = false

    override suspend fun start(bindHost: String) = lifecycle.withLock {
        val currentJob = currentCoroutineContext().job
        synchronized(operationLock) { lifecycleJob = currentJob }
        try {
            require(bindHost == "127.0.0.1" || bindHost == "0.0.0.0") { "Unsupported API binding." }
            if (state.value.status == RemoteServerStatus.RUNNING) {
                if (state.value.bindHost != bindHost) fail(RemoteErrorKind.CONFLICT)
                return@withLock
            }
            if (state.value.status != RemoteServerStatus.STOPPED || server != null) fail(RemoteErrorKind.CONFLICT)
            mutableState.value = RemoteServerState(RemoteServerStatus.STARTING, bindHost)
            try {
                recoverIfNeeded()
                check(keys.token().matches(Regex("[0-9a-f]{64}"))) { "Invalid remote API key." }
                val created = factory.create(bindHost, this)
                server = created
                created.start()
                currentCoroutineContext().ensureActive()
                mutableState.value = RemoteServerState(RemoteServerStatus.RUNNING, bindHost)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    val httpFailed = shutdownHttp()
                    val childrenFailed = shutdownChildren()
                    val incomplete = httpFailed || childrenFailed
                    mutableState.value = RemoteServerState(
                        status = if (failure is CancellationException && !incomplete) RemoteServerStatus.STOPPED else RemoteServerStatus.FAILED,
                        bindHost = bindHost.takeIf { incomplete },
                        error = if (incomplete) INCOMPLETE_SHUTDOWN else if (failure !is CancellationException) START_FAILED else null,
                    )
                }
                if (failure is CancellationException) throw failure
                if (failure !is Exception) throw failure
                fail(RemoteErrorKind.FAILED)
            }
        } finally {
            synchronized(operationLock) {
                if (lifecycleJob === currentJob) lifecycleJob = null
            }
        }
    }

    override suspend fun stop() = lifecycle.withLock {
        val currentJob = currentCoroutineContext().job
        synchronized(operationLock) { lifecycleJob = currentJob }
        try {
            if (state.value.status == RemoteServerStatus.STOPPED && server == null) return@withLock
            val bindHost = state.value.bindHost
            val pending = synchronized(operationLock) {
                mutableState.value = RemoteServerState(RemoteServerStatus.STOPPING, bindHost)
                (listOfNotNull(activeOperationJob) + operations).distinct()
            }
            pending.forEach { it.cancel(CancellationException("The remote server is stopping.")) }
            withContext(NonCancellable) {
                // Cancel handlers before stopping Netty. Waiting for the mutation mutex first could
                // leave the HTTP shutdown waiting for a handler whose deployment is still running.
                val httpFailed = shutdownHttp()
                val childrenFailed = mutations.withLock children@ {
                    if (!recovered) {
                        try {
                            recoverIfNeeded()
                        } catch (_: Exception) {
                            return@children true
                        }
                    }
                    shutdownChildren()
                }
                if (httpFailed || childrenFailed) {
                    mutableState.value = RemoteServerState(RemoteServerStatus.FAILED, bindHost, INCOMPLETE_SHUTDOWN)
                    fail(RemoteErrorKind.FAILED)
                }
                mutableState.value = RemoteServerState()
            }
        } finally {
            synchronized(operationLock) {
                if (lifecycleJob === currentJob) lifecycleJob = null
            }
        }
    }

    override suspend fun forceReset() {
        val pending = synchronized(operationLock) {
            (listOfNotNull(lifecycleJob, activeOperationJob) + operations).distinct()
        }
        pending.forEach { it.cancel(CancellationException("Force reset requested.")) }
        lifecycle.withLock {
            withContext(NonCancellable) {
                runCatching { shutdownHttp() }
                server = null
                mutations.withLock {
                    if (recovered) {
                        runCatching { shutdownChildren() }
                    }
                }
                mutableState.value = RemoteServerState()
            }
        }
    }

    override suspend fun startNetwork(): Unit = mutate { network.startAll() }

    override suspend fun deploy(request: RemoteDeployRequest, archive: File): Site = mutate {
        deployArchive(request, archive)
    }

    override suspend fun deploy(request: RemoteDeployRequest, source: ProjectArchiveSource): Site = mutate {
        source.useArchive { archive -> deployArchive(request, archive) }
    }

    private suspend fun deployArchive(request: RemoteDeployRequest, archive: File): Site {
        require(archive.isFile && archive.length() > 0) { "A nonempty archive is required." }
        val site = if (request.siteId != null) {
            require(request.domainName == null && request.projectType == null) { "Site identity is ambiguous." }
            inactiveSite(request.siteId)
        } else {
            val domain = SiteInput.normalizeDomainName(requireNotNull(request.domainName))
            val type = requireNotNull(request.projectType)
            val registered = sites.listSites()
            if (registered.size >= MAXIMUM_REGISTERED_SITES || registered.any { it.domainName == domain }) {
                fail(RemoteErrorKind.CONFLICT)
            }
            sites.createSite(domain, type)
        }
        deployment.deployProject(site, archive)
        return resultSite(site.id)
    }

    override suspend fun start(siteId: Long): Site = mutate {
        inactiveSite(siteId)
        deployment.startSite(siteId)
        resultSite(siteId)
    }

    override suspend fun stop(siteId: Long): Site = mutate {
        requiredSite(siteId)
        deployment.stopSite(siteId)
        resultSite(siteId)
    }

    private suspend fun <T> mutate(action: suspend () -> T): T = coroutineScope {
        val ownJob = currentCoroutineContext().job
        synchronized(operationLock) {
            if (state.value.status != RemoteServerStatus.RUNNING) fail(RemoteErrorKind.UNAVAILABLE)
            operations.add(ownJob)
        }
        try {
            mutations.withLock {
                currentCoroutineContext().ensureActive()
                synchronized(operationLock) {
                    if (state.value.status != RemoteServerStatus.RUNNING) fail(RemoteErrorKind.UNAVAILABLE)
                    activeOperationJob = ownJob
                }
                action()
            }
        } catch (failure: Throwable) {
            when (failure) {
                is CancellationException -> throw failure
                is RemoteOperationException -> throw failure
                is SiteNotFoundException -> fail(RemoteErrorKind.NOT_FOUND)
                is SiteStillActiveException, is IllegalArgumentException -> fail(RemoteErrorKind.CONFLICT)
                is Exception -> fail(RemoteErrorKind.FAILED)
                else -> throw failure
            }
        } finally {
            synchronized(operationLock) {
                operations.remove(ownJob)
                if (activeOperationJob === ownJob) activeOperationJob = null
            }
        }
    }

    private suspend fun recoverIfNeeded() {
        if (!recovered) {
            recovery.recover()
            recovered = true
        }
    }

    private suspend fun requiredSite(siteId: Long): Site {
        if (siteId <= 0) fail(RemoteErrorKind.NOT_FOUND)
        return sites.getSite(siteId) ?: fail(RemoteErrorKind.NOT_FOUND)
    }

    private suspend fun inactiveSite(siteId: Long): Site = requiredSite(siteId).also { site ->
        if (site.status !in setOf(SiteStatus.STOPPED, SiteStatus.FAILED) || site.processId != null) {
            fail(RemoteErrorKind.CONFLICT)
        }
    }

    private suspend fun resultSite(siteId: Long): Site = sites.getSite(siteId) ?: fail(RemoteErrorKind.FAILED)

    private suspend fun shutdownHttp(): Boolean {
        val owned = server ?: return false
        return try {
            owned.stop()
            if (server === owned) server = null
            false
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun shutdownChildren(): Boolean {
        if (!recovered) return false
        var failed = false
        try { deployment.stopAll() } catch (_: Exception) { failed = true }
        try { network.stopAll() } catch (_: Exception) { failed = true }
        return failed
    }

    private fun fail(kind: RemoteErrorKind): Nothing = throw RemoteOperationException(kind)

    companion object {
        private const val MAXIMUM_REGISTERED_SITES = 64
        private const val START_FAILED = "The remote server could not be started."
        private const val INCOMPLETE_SHUTDOWN = "Server shutdown is incomplete. Retry Stop."
    }
}
