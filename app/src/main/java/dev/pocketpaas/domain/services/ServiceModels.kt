package dev.pocketpaas.domain.services

import kotlinx.coroutines.flow.StateFlow

enum class ServiceCategory { CORE, ACCESS, DATABASE }

enum class ManagedServiceStatus { UNKNOWN, STOPPED, STARTING, RUNNING, FAILED }

data class ManagedServiceState(
    val serviceKey: String,
    val displayName: String,
    val category: ServiceCategory,
    val installed: Boolean,
    val status: ManagedServiceStatus,
    val processId: Int?,
    val port: Int?,
    val lastError: String?,
    val updatedAt: Long,
)

interface ManagedServices {
    val services: StateFlow<List<ManagedServiceState>>
    suspend fun install(serviceKey: String): ManagedServiceState
    suspend fun start(serviceKey: String): ManagedServiceState
    suspend fun stop(serviceKey: String): ManagedServiceState
    suspend fun refresh()
}
