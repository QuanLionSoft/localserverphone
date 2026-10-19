package dev.pocketpaas.domain.model

enum class NetworkServiceStatus { STOPPED, STARTING, RUNNING, FAILED }

data class NetworkState(
    val nginxStatus: NetworkServiceStatus = NetworkServiceStatus.STOPPED,
    val tunnelStatus: NetworkServiceStatus = NetworkServiceStatus.STOPPED,
    val nginxProcessId: Int? = null,
    val tunnelProcessId: Int? = null,
    val publicUrl: String? = null,
    val lastError: String? = null,
)
