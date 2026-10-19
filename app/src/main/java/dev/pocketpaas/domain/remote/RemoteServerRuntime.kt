package dev.pocketpaas.domain.remote

import kotlinx.coroutines.flow.StateFlow

enum class RemoteServerStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

data class RemoteServerState(
    val status: RemoteServerStatus = RemoteServerStatus.STOPPED,
    val bindHost: String? = null,
    val error: String? = null,
)

interface RemoteServerRuntime {
    val state: StateFlow<RemoteServerState>
    suspend fun start(bindHost: String)
    /** Starts Nginx and the tunnel only while an existing foreground session admits work. */
    suspend fun startNetwork()
    suspend fun stop()
    /** Cancels in-flight work and resets to STOPPED without triggering cold Termux recovery. */
    suspend fun forceReset() {}
}
