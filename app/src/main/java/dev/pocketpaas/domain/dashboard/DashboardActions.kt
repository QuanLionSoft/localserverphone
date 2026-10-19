package dev.pocketpaas.domain.dashboard

import dev.pocketpaas.domain.remote.RemoteDeployRequest
import kotlinx.coroutines.flow.StateFlow

enum class DashboardOperationKind { SERVER_START, SERVER_STOP, NETWORK_START, DEPLOY, SITE_START, SITE_STOP }

data class DashboardOperationState(
    val id: Long = 0,
    val kind: DashboardOperationKind? = null,
    val siteId: Long? = null,
    val running: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
)

/** Application-owned commands survive Activity and ViewModel recreation. */
interface DashboardActions {
    val operation: StateFlow<DashboardOperationState>
    fun startServer(enableLanAccess: Boolean = false)
    fun stopServer()
    fun retryNetwork()
    fun startSite(id: Long)
    fun stopSite(id: Long)
    fun deploy(request: RemoteDeployRequest, uri: String)
    fun cancelOperation()
    fun dismissResult()
}
