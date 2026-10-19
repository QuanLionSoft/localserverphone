package dev.pocketpaas.domain.dashboard

import dev.pocketpaas.domain.model.NetworkState
import kotlinx.coroutines.flow.StateFlow

interface DashboardNetworkState {
    val state: StateFlow<NetworkState>
}

data class PickedArchive(val uri: String, val name: String, val bytes: Long?)

interface DashboardDevice {
    suspend fun describeArchive(uri: String): PickedArchive
    suspend fun copySensitive(text: String)
}
