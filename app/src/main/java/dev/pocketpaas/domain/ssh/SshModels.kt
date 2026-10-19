package dev.pocketpaas.domain.ssh

import dev.pocketpaas.domain.model.NetworkServiceStatus
import kotlinx.coroutines.flow.StateFlow

data class SshSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val passwordAuthEnabled: Boolean = false,
    val listenLan: Boolean = false,
    val hostKeyFingerprint: String? = null,
    val updatedAt: Long = 0,
) {
    companion object {
        const val DEFAULT_PORT = 8022
    }
}

data class SshAuthorizedKey(
    val id: Long,
    val name: String,
    val publicKey: String,
    val fingerprint: String,
    val enabled: Boolean,
    val createdAt: Long,
)

data class SshConnectionInfo(
    val user: String,
    val host: String?,
    val port: Int,
    val command: String,
)

data class SshState(
    val status: NetworkServiceStatus = NetworkServiceStatus.STOPPED,
    val processId: Int? = null,
    val settings: SshSettings = SshSettings(),
    val authorizedKeyCount: Int = 0,
    val connectionCommand: String? = null,
    val lastError: String? = null,
)

interface SshManager {
    val state: StateFlow<SshState>
    suspend fun start()
    suspend fun stop()
    suspend fun updateSettings(settings: SshSettings)
    suspend fun addAuthorizedKey(name: String, publicKey: String): SshAuthorizedKey
    suspend fun removeAuthorizedKey(id: Long)
    suspend fun listAuthorizedKeys(): List<SshAuthorizedKey>
    suspend fun connectionInfo(): SshConnectionInfo
    suspend fun refresh()
}
