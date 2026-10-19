package dev.pocketpaas.data.services

import dev.pocketpaas.core.config.ServicePolicy
import dev.pocketpaas.data.local.ServiceStateDao
import dev.pocketpaas.data.local.ServiceStateEntity
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ManagedServices
import dev.pocketpaas.domain.services.ServiceCatalog
import dev.pocketpaas.domain.services.ServiceCategory
import dev.pocketpaas.domain.services.ServiceDefinition
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Termux paket yöneticisiyle kurulan PostgreSQL/Redis/MariaDB servislerini başlatır ve durdurur.
 * Nginx, tünel ve sshd ayrı yöneticilerin sorumluluğundadır; burada yalnız paket kurulumu görünür.
 * Veri dizinleri Termux çalışma kökü altındadır ve süreçler aynı Termux UID'sinde çalışır.
 */
class TermuxServiceManager(
    private val shell: ProcessExecutor,
    private val dao: ServiceStateDao,
    private val activity: ActivityRepository,
    private val runtime: RemoteServerRuntime,
    private val policy: ServicePolicy,
    private val clock: Clock,
    private val applicationScope: CoroutineScope,
) : ManagedServices {
    private val lifecycle = Mutex()
    private val handles = mutableMapOf<String, ManagedProcess>()
    private val watchers = mutableMapOf<String, Job>()
    private val mutableServices = MutableStateFlow(ServiceCatalog.definitions.map { it.defaultState() })
    override val services: StateFlow<List<ManagedServiceState>> = mutableServices.asStateFlow()

    init {
        applicationScope.launch {
            dao.observeServices().collect { rows ->
                merge(rows.map { it.toDomain() })
            }
        }
        applicationScope.launch {
            runtime.state.collect { server ->
                if (server.status != RemoteServerStatus.RUNNING && handles.isNotEmpty()) {
                    runCatching { stopAllForSessionEnd() }
                }
            }
        }
    }

    override suspend fun install(serviceKey: String): ManagedServiceState {
        val definition = ServiceCatalog.require(serviceKey)
        updateLocal(serviceKey) { it.copy(status = ManagedServiceStatus.STARTING, lastError = null) }
        try {
            val command = listOf("pkg", "install", "-y") + definition.packages
            val result = executeArguments(command, policy.installTimeoutMillis)
            if (!result.isSuccessful) {
                throw IOException("Paket kurulumu başarısız: ${result.stderr.take(240)}")
            }
            val installed = probeInstalled(definition)
            persist(definition, installed = installed, status = ManagedServiceStatus.STOPPED, processId = null, lastError = null)
            withContext(NonCancellable) {
                runCatching {
                    activity.record(
                        ActivityCategory.SERVICE,
                        ActivityLevel.INFO,
                        "${definition.displayName} kuruldu.",
                        definition.packages.joinToString(),
                    )
                }
            }
            return requireState(serviceKey)
        } catch (cancelled: CancellationException) {
            updateLocal(serviceKey) { it.copy(status = ManagedServiceStatus.STOPPED) }
            throw cancelled
        } catch (failure: Exception) {
            persistFailure(definition, failure)
            throw failure
        }
    }

    override suspend fun start(serviceKey: String): ManagedServiceState {
        val definition = ServiceCatalog.require(serviceKey)
        require(!definition.externallyManaged) {
            "${definition.displayName} bu alandan yönetilmez; ilgili kartı kullanın."
        }
        lifecycle.withLock {
            if (handles.containsKey(serviceKey)) return requireState(serviceKey)
            val runningDatabases = handles.keys.count { key ->
                ServiceCatalog.find(key)?.category == ServiceCategory.DATABASE
            }
            require(runningDatabases < policy.maximumRunningDatabaseServices) {
                "Aynı anda en fazla ${policy.maximumRunningDatabaseServices} veritabanı servisi çalışabilir"
            }
            updateLocal(serviceKey) { it.copy(status = ManagedServiceStatus.STARTING, lastError = null) }
            try {
                if (!probeInstalled(definition)) {
                    throw IOException("${definition.displayName} kurulu değil; önce Kur düğmesini kullanın.")
                }
                val dataDir = prepareDataDirectory(definition)
                runInitCommands(definition, dataDir)
                val binary = shell.resolveBinary(definition.startCommand.first())
                val arguments = listOf(binary) + definition.render(definition.startCommand.drop(1), dataDir.absolutePath)
                val layout = shell.runtimeLayout()
                val process = shell.startProcess(arguments, emptyMap(), File(layout.runtimePath))
                handles[serviceKey] = process
                watchers[serviceKey] = watch(serviceKey, definition, process)
                awaitReadiness(definition, process)
                persist(definition, installed = true, status = ManagedServiceStatus.RUNNING, processId = process.processId, lastError = null)
                withContext(NonCancellable) {
                    runCatching {
                        activity.record(
                            ActivityCategory.SERVICE,
                            ActivityLevel.INFO,
                            "${definition.displayName} başlatıldı${definition.port?.let { " (:$it)" } ?: ""}.",
                            null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                cleanupFailedStart(serviceKey, definition, null)
                throw cancelled
            } catch (failure: Exception) {
                cleanupFailedStart(serviceKey, definition, description(failure))
                throw failure
            }
        }
        return requireState(serviceKey)
    }

    override suspend fun stop(serviceKey: String): ManagedServiceState {
        val definition = ServiceCatalog.require(serviceKey)
        lifecycle.withLock {
            val process = handles.remove(serviceKey)
            watchers.remove(serviceKey)?.cancel()
            var stopFailure: String? = null
            if (process != null) {
                try {
                    process.stop()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    stopFailure = description(failure)
                }
            }
            persist(
                definition,
                installed = probeInstalled(definition),
                status = ManagedServiceStatus.STOPPED,
                processId = null,
                lastError = stopFailure,
            )
            if (process != null) {
                withContext(NonCancellable) {
                    runCatching {
                        activity.record(ActivityCategory.SERVICE, ActivityLevel.INFO, "${definition.displayName} durduruldu.", stopFailure)
                    }
                }
            }
        }
        return requireState(serviceKey)
    }

    override suspend fun refresh() {
        lifecycle.withLock {
            for (definition in ServiceCatalog.definitions) {
                val installed = runCatching { probeInstalled(definition) }.getOrDefault(false)
                val running = handles[definition.serviceKey]
                persist(
                    definition,
                    installed = installed,
                    status = if (running != null) ManagedServiceStatus.RUNNING else ManagedServiceStatus.STOPPED,
                    processId = running?.processId,
                    lastError = null,
                )
            }
        }
    }

    private suspend fun stopAllForSessionEnd() {
        for (serviceKey in handles.keys.toList()) {
            runCatching { stop(serviceKey) }
        }
    }

    private suspend fun prepareDataDirectory(definition: ServiceDefinition): File {
        val layout = shell.runtimeLayout()
        val dataDir = File(File(layout.rootPath, "services"), definition.serviceKey)
        val result = executeShell("mkdir -p \"${dataDir.absolutePath}\" && chmod 700 \"${dataDir.absolutePath}\"")
        if (!result.isSuccessful) {
            throw IOException("Veri dizini hazırlanamadı: ${result.stderr.take(200)}")
        }
        return dataDir
    }

    private suspend fun runInitCommands(definition: ServiceDefinition, dataDir: File) {
        val marker = definition.initMarkerRelativePath ?: return
        val probe = executeShell("test -f \"${File(dataDir, marker).absolutePath}\"")
        if (probe.isSuccessful) return
        for (command in definition.initCommands) {
            val arguments = definition.render(command, dataDir.absolutePath)
            val result = executeArguments(arguments, policy.installTimeoutMillis)
            if (!result.isSuccessful) {
                throw IOException("${definition.displayName} veri dizini hazırlanamadı: ${result.stderr.take(240)}")
            }
        }
    }

    private suspend fun awaitReadiness(definition: ServiceDefinition, process: ManagedProcess) {
        val port = definition.port ?: return
        val ready = withTimeoutOrNull(policy.startupTimeoutMillis) {
            while (!isPortOpen(port)) {
                delay(policy.readinessPollMillis)
            }
            true
        }
        if (ready != true) {
            runCatching { process.stop() }
            handles.remove(definition.serviceKey)
            watchers.remove(definition.serviceKey)?.cancel()
            throw IOException("${definition.displayName} :$port üzerinde hazır olmadı; süreç günlüklerini kontrol edin.")
        }
    }

    private suspend fun isPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun watch(serviceKey: String, definition: ServiceDefinition, process: ManagedProcess): Job =
        applicationScope.launch {
            val failure = try {
                process.await()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
            lifecycle.withLock {
                if (handles[serviceKey] === process) {
                    handles.remove(serviceKey)
                    watchers.remove(serviceKey)?.cancel()
                    val message = failure?.let { description(it) }
                    persist(
                        definition,
                        installed = true,
                        status = if (failure == null) ManagedServiceStatus.STOPPED else ManagedServiceStatus.FAILED,
                        processId = null,
                        lastError = message,
                    )
                    withContext(NonCancellable) {
                        runCatching {
                            activity.record(
                                ActivityCategory.SERVICE,
                                if (failure == null) ActivityLevel.WARNING else ActivityLevel.ERROR,
                                if (failure == null) {
                                    "${definition.displayName} kendiliğinden sonlandı."
                                } else {
                                    "${definition.displayName} çalışırken durdu."
                                },
                                message,
                            )
                        }
                    }
                }
            }
        }

    private suspend fun cleanupFailedStart(serviceKey: String, definition: ServiceDefinition, message: String?) {
        withContext(NonCancellable) {
            val process = handles.remove(serviceKey)
            watchers.remove(serviceKey)?.cancel()
            if (process != null) {
                runCatching { process.stop() }
            }
            persist(
                definition,
                installed = runCatching { probeInstalled(definition) }.getOrDefault(true),
                status = ManagedServiceStatus.FAILED,
                processId = null,
                lastError = message,
            )
            runCatching {
                activity.record(ActivityCategory.SERVICE, ActivityLevel.ERROR, "${definition.displayName} başlatılamadı.", message)
            }
        }
    }

    private suspend fun probeInstalled(definition: ServiceDefinition): Boolean {
        if (definition.versionCommand.isEmpty()) return false
        return executeArguments(definition.versionCommand, policy.probeTimeoutMillis).isSuccessful
    }

    private suspend fun executeArguments(arguments: List<String>, timeoutMillis: Long): ProcessResult {
        val layout = shell.runtimeLayout()
        return shell.execute(
            ProcessRequest(ProcessCommand.Arguments(arguments), emptyMap(), File(layout.runtimePath)),
            timeoutMillis,
        )
    }

    private suspend fun executeShell(command: String): ProcessResult {
        val layout = shell.runtimeLayout()
        return shell.execute(
            ProcessRequest(ProcessCommand.Shell(command), emptyMap(), File(layout.runtimePath)),
            policy.probeTimeoutMillis,
        )
    }

    private suspend fun persist(
        definition: ServiceDefinition,
        installed: Boolean,
        status: ManagedServiceStatus,
        processId: Int?,
        lastError: String?,
    ) {
        dao.upsert(
            ServiceStateEntity(
                serviceKey = definition.serviceKey,
                displayName = definition.displayName,
                category = definition.category,
                installed = installed,
                status = status,
                processId = processId,
                port = definition.port,
                lastError = lastError,
                updatedAt = clock.millis(),
            ),
        )
    }

    private suspend fun persistFailure(definition: ServiceDefinition, failure: Throwable) {
        val message = description(failure)
        persist(
            definition,
            installed = runCatching { probeInstalled(definition) }.getOrDefault(false),
            status = ManagedServiceStatus.FAILED,
            processId = null,
            lastError = message,
        )
        withContext(NonCancellable) {
            runCatching {
                activity.record(ActivityCategory.SERVICE, ActivityLevel.ERROR, "${definition.displayName} işlemi başarısız.", message)
            }
        }
    }

    private fun updateLocal(serviceKey: String, transform: (ManagedServiceState) -> ManagedServiceState) {
        mutableServices.update { list -> list.map { if (it.serviceKey == serviceKey) transform(it) else it } }
    }

    private fun merge(stored: List<ManagedServiceState>) {
        val byKey = stored.associateBy { it.serviceKey }
        mutableServices.value = ServiceCatalog.definitions.map { definition ->
            val row = byKey[definition.serviceKey]
            val live = handles[definition.serviceKey]
            if (row == null) {
                definition.defaultState()
            } else {
                row.copy(
                    status = if (live != null) ManagedServiceStatus.RUNNING else row.status,
                    processId = live?.processId,
                )
            }
        }
    }

    private suspend fun requireState(serviceKey: String): ManagedServiceState {
        val row = dao.getService(serviceKey)?.toDomain()
        val live = handles[serviceKey]
        return row?.copy(
            status = if (live != null) ManagedServiceStatus.RUNNING else row.status,
            processId = live?.processId,
        ) ?: ServiceCatalog.require(serviceKey).defaultState()
    }

    private fun ServiceDefinition.defaultState(): ManagedServiceState = ManagedServiceState(
        serviceKey = serviceKey,
        displayName = displayName,
        category = category,
        installed = false,
        status = ManagedServiceStatus.UNKNOWN,
        processId = null,
        port = port,
        lastError = null,
        updatedAt = 0,
    )

    private fun description(failure: Throwable): String =
        (failure.message ?: failure.javaClass.simpleName).replace('\u0000', ' ').take(256)
}
