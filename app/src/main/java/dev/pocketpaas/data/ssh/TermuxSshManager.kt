package dev.pocketpaas.data.ssh

import dev.pocketpaas.core.config.SshPolicy
import dev.pocketpaas.data.local.SshAuthorizedKeyEntity
import dev.pocketpaas.data.local.SshDao
import dev.pocketpaas.data.local.SshSettingsEntity
import dev.pocketpaas.data.local.toDomain
import dev.pocketpaas.data.local.toEntity
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.process.ManagedProcess
import dev.pocketpaas.domain.process.ProcessCommand
import dev.pocketpaas.domain.process.ProcessExecutor
import dev.pocketpaas.domain.process.ProcessRequest
import dev.pocketpaas.domain.process.ProcessResult
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshConnectionInfo
import dev.pocketpaas.domain.ssh.SshKeyFormat
import dev.pocketpaas.domain.ssh.SshManager
import dev.pocketpaas.domain.ssh.SshSettings
import dev.pocketpaas.domain.ssh.SshState
import dev.pocketpaas.domain.ssh.SshdArguments
import java.io.File
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Termux içinde OpenSSH sshd sürecini yönetir. Yetkili anahtarlar veritabanında tutulur ve
 * her değişiklikte `~/.ssh/authorized_keys` dosyasına tek parça yazılır. Süreç, ana sunucu
 * oturumu kapanınca durdurulur; kalıcı arka plan süreci bırakılmaz.
 */
class TermuxSshManager(
    private val shell: ProcessExecutor,
    private val dao: SshDao,
    private val activity: ActivityRepository,
    private val runtime: RemoteServerRuntime,
    private val policy: SshPolicy,
    private val clock: Clock,
    private val applicationScope: CoroutineScope,
) : SshManager {
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow(SshState())
    override val state: StateFlow<SshState> = mutableState.asStateFlow()
    private var handle: ManagedProcess? = null
    private var watcher: Job? = null

    init {
        applicationScope.launch {
            dao.observeSettings().collect { entity ->
                if (entity != null) {
                    mutableState.update { it.copy(settings = entity.toDomain()) }
                }
            }
        }
        applicationScope.launch {
            dao.observeAuthorizedKeys().collect { keys ->
                mutableState.update { it.copy(authorizedKeyCount = keys.size) }
            }
        }
        applicationScope.launch {
            runtime.state.collect { server ->
                if (server.status != RemoteServerStatus.RUNNING && handle != null) {
                    runCatching { stop() }
                }
            }
        }
    }

    override suspend fun start() {
        lifecycle.withLock { startLocked() }
    }

    override suspend fun stop() {
        lifecycle.withLock { stopLocked() }
    }

    override suspend fun updateSettings(settings: SshSettings) {
        require(policy.isAllowedPort(settings.port)) {
            "SSH port must be unprivileged and must not collide with the API or Nginx ports"
        }
        lifecycle.withLock {
            val wasRunning = handle != null
            if (wasRunning) stopLocked()
            dao.upsertSettings(settings.toEntity(clock.millis()).copy(id = SshSettingsEntity.SINGLETON_ID))
            mutableState.update { it.copy(settings = settings.copy(updatedAt = clock.millis())) }
            if (wasRunning) startLocked()
        }
        withContext(NonCancellable) {
            runCatching {
                activity.record(
                    ActivityCategory.SSH,
                    ActivityLevel.INFO,
                    "SSH ayarları güncellendi (:${settings.port}).",
                    "Parola ile giriş: ${settings.passwordAuthEnabled}, LAN: ${settings.listenLan}",
                )
            }
        }
    }

    override suspend fun addAuthorizedKey(name: String, publicKey: String): SshAuthorizedKey {
        val validatedName = SshKeyFormat.validateName(name)
        val parsed = SshKeyFormat.parse(publicKey)
        val stored = lifecycle.withLock {
            require(dao.countAuthorizedKeys() < policy.maximumAuthorizedKeys) {
                "Authorized key limit is reached"
            }
            require(dao.findByFingerprint(parsed.fingerprint) == null) {
                "This public key is already authorized"
            }
            val id = dao.insertAuthorizedKey(
                SshAuthorizedKeyEntity(
                    name = validatedName,
                    publicKey = parsed.line,
                    fingerprint = parsed.fingerprint,
                    enabled = true,
                    createdAt = clock.millis(),
                ),
            )
            val persisted = checkNotNull(dao.getAuthorizedKey(id)) { "Authorized key could not be persisted" }
            materializeAuthorizedKeysLocked()
            persisted
        }
        withContext(NonCancellable) {
            runCatching {
                activity.record(
                    ActivityCategory.SECURITY,
                    ActivityLevel.INFO,
                    "SSH anahtarı eklendi: $validatedName",
                    parsed.fingerprint,
                )
            }
        }
        return stored.toDomain()
    }

    override suspend fun removeAuthorizedKey(id: Long) {
        val removed = lifecycle.withLock {
            val existing = dao.getAuthorizedKey(id) ?: throw NoSuchElementException("SSH key $id does not exist")
            dao.deleteAuthorizedKey(id)
            materializeAuthorizedKeysLocked()
            existing
        }
        withContext(NonCancellable) {
            runCatching {
                activity.record(
                    ActivityCategory.SECURITY,
                    ActivityLevel.WARNING,
                    "SSH anahtarı kaldırıldı: ${removed.name}",
                    removed.fingerprint,
                )
            }
        }
    }

    override suspend fun listAuthorizedKeys(): List<SshAuthorizedKey> =
        dao.getAuthorizedKeys().map { it.toDomain() }

    override suspend fun connectionInfo(): SshConnectionInfo {
        val settings = ensureSettings()
        val user = whoami()
        val host = if (settings.listenLan) detectLanAddress() else "127.0.0.1"
        val target = host ?: "TELEFON_IP"
        return SshConnectionInfo(
            user = user,
            host = host,
            port = settings.port,
            command = "ssh -p ${settings.port} $user@$target",
        )
    }

    override suspend fun refresh() {
        val settings = ensureSettings()
        if (runtime.state.value.status != RemoteServerStatus.RUNNING) return
        val fingerprint = settings.hostKeyFingerprint ?: runCatching { hostKeyFingerprint() }.getOrNull()
        if (fingerprint != null && fingerprint != settings.hostKeyFingerprint) {
            dao.updateHostKeyFingerprint(fingerprint, clock.millis())
        }
        val command = runCatching { connectionInfo().command }.getOrNull()
        mutableState.update {
            it.copy(
                settings = ensureSettings(),
                connectionCommand = command,
            )
        }
    }

    private suspend fun startLocked() {
        if (handle != null) return
        mutableState.update { it.copy(status = NetworkServiceStatus.STARTING, lastError = null) }
        try {
            val settings = ensureSettings()
            require(policy.isAllowedPort(settings.port)) { "Configured SSH port is not allowed" }
            shell.runtimeLayout()
            ensureHostKeys()
            val fingerprint = hostKeyFingerprint()
            dao.updateHostKeyFingerprint(fingerprint, clock.millis())
            val sshd = shell.resolveBinary("sshd")
            val arguments = listOf(sshd) + SshdArguments.build(
                port = settings.port,
                passwordAuthEnabled = settings.passwordAuthEnabled,
                listenLan = settings.listenLan,
            )
            val layout = shell.runtimeLayout()
            val process = shell.startProcess(arguments, emptyMap(), File(layout.runtimePath))
            handle = process
            watcher = watch(process)
            val command = runCatching { connectionInfo().command }.getOrNull()
            mutableState.update {
                it.copy(
                    status = NetworkServiceStatus.RUNNING,
                    processId = process.processId,
                    settings = ensureSettings(),
                    connectionCommand = command,
                    lastError = null,
                )
            }
            withContext(NonCancellable) {
                runCatching {
                    activity.record(
                        ActivityCategory.SSH,
                        ActivityLevel.INFO,
                        "SSH sunucusu başlatıldı (:${settings.port}).",
                        fingerprint,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(status = NetworkServiceStatus.STOPPED, processId = null) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(
                    status = NetworkServiceStatus.FAILED,
                    processId = null,
                    lastError = description(failure),
                )
            }
            withContext(NonCancellable) {
                runCatching {
                    activity.record(
                        ActivityCategory.SSH,
                        ActivityLevel.ERROR,
                        "SSH sunucusu başlatılamadı.",
                        description(failure),
                    )
                }
            }
            throw failure
        }
    }

    private suspend fun stopLocked() {
        val owned = handle
        watcher?.cancel()
        watcher = null
        handle = null
        var stopFailure: String? = null
        if (owned != null) {
            try {
                owned.stop()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                stopFailure = description(failure)
            }
        }
        mutableState.update {
            it.copy(
                status = NetworkServiceStatus.STOPPED,
                processId = null,
                lastError = stopFailure,
            )
        }
        if (owned != null) {
            withContext(NonCancellable) {
                runCatching { activity.record(ActivityCategory.SSH, ActivityLevel.INFO, "SSH sunucusu durduruldu.", stopFailure) }
            }
        }
    }

    private fun watch(process: ManagedProcess): Job = applicationScope.launch {
        val failure = try {
            process.await()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        lifecycle.withLock {
            if (handle === process) {
                handle = null
                watcher = null
                val message = failure?.let { description(it) }
                mutableState.update {
                    it.copy(
                        status = if (failure == null) NetworkServiceStatus.STOPPED else NetworkServiceStatus.FAILED,
                        processId = null,
                        lastError = message,
                    )
                }
                withContext(NonCancellable) {
                    runCatching {
                        activity.record(
                            ActivityCategory.SSH,
                            if (failure == null) ActivityLevel.WARNING else ActivityLevel.ERROR,
                            if (failure == null) "SSH sunucusu kendiliğinden sonlandı." else "SSH sunucusu çalışırken durdu.",
                            message,
                        )
                    }
                }
            }
        }
    }

    private suspend fun ensureSettings(): SshSettings {
        val stored = dao.getOrCreateSettings(
            SshSettingsEntity(
                id = SshSettingsEntity.SINGLETON_ID,
                enabled = false,
                port = policy.defaultPort,
                passwordAuthEnabled = false,
                listenLan = false,
                hostKeyFingerprint = null,
                updatedAt = clock.millis(),
            ),
        )
        val settings = stored.toDomain()
        mutableState.update { it.copy(settings = settings) }
        return settings
    }

    private suspend fun ensureHostKeys() {
        val result = executeShell("ssh-keygen -A", emptyMap())
        if (!result.isSuccessful) {
            throw IOException("SSH host keys could not be prepared: ${result.stderr.take(200)}")
        }
    }

    private suspend fun hostKeyFingerprint(): String? {
        val result = executeShell("ssh-keygen -lf \"\$PREFIX/etc/ssh/ssh_host_ed25519_key.pub\"", emptyMap())
        if (!result.isSuccessful) return null
        return FINGERPRINT_PATTERN.find(result.stdout)?.value
    }

    private suspend fun materializeAuthorizedKeysLocked() {
        val content = dao.getAuthorizedKeys()
            .filter { it.enabled }
            .joinToString(separator = "\n", postfix = "\n") { it.publicKey }
        val result = executeShell(
            "mkdir -p \"\$HOME/.ssh\" && chmod 700 \"\$HOME/.ssh\" && " +
                "printf '%s' \"\$POCKETPAAS_AUTHORIZED_KEYS\" > \"\$HOME/.ssh/authorized_keys\" && " +
                "chmod 600 \"\$HOME/.ssh/authorized_keys\"",
            mapOf("POCKETPAAS_AUTHORIZED_KEYS" to content),
        )
        if (!result.isSuccessful) {
            throw IOException("authorized_keys could not be written: ${result.stderr.take(200)}")
        }
    }

    private suspend fun whoami(): String {
        val result = executeShell("whoami", emptyMap())
        return result.stdout.trim().takeIf { it.isNotEmpty() && result.isSuccessful } ?: "u0_aXXX"
    }

    private suspend fun detectLanAddress(): String? {
        val result = executeShell("ip -4 -o addr show scope global", emptyMap())
        if (!result.isSuccessful) return null
        return IPV4_PATTERN.find(result.stdout)?.value
    }

    private suspend fun executeShell(command: String, env: Map<String, String>): ProcessResult {
        val layout = shell.runtimeLayout()
        return shell.execute(
            ProcessRequest(ProcessCommand.Shell(command), env, File(layout.runtimePath)),
            policy.probeTimeoutMillis,
        )
    }

    private fun description(failure: Throwable): String =
        (failure.message ?: failure.javaClass.simpleName).replace('\u0000', ' ').take(256)

    private companion object {
        val FINGERPRINT_PATTERN = Regex("SHA256:[A-Za-z0-9+/]+")
        val IPV4_PATTERN = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    }
}
