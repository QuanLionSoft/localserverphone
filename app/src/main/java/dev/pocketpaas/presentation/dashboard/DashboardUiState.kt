package dev.pocketpaas.presentation.dashboard

import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus

data class ProjectDraft(
    val domainName: String = "",
    val projectType: ProjectType = ProjectType.STATIC,
    val zipUri: String? = null,
    val zipName: String? = null,
    val error: String? = null,
)

data class DashboardNotice(val id: Long, val text: String)

data class DashboardUiState(
    val runtime: RemoteServerState = RemoteServerState(),
    val network: NetworkState = NetworkState(),
    val sites: List<Site> = emptyList(),
    val loadingSites: Boolean = true,
    val siteReadFailed: Boolean = false,
    val initializing: Boolean = true,
    val setupFailed: Boolean = false,
    val capabilities: RuntimeCapabilities? = null,
    val apiPort: Int = 8080,
    val nginxPort: Int = 8081,
    val showAddProject: Boolean = false,
    val draft: ProjectDraft = ProjectDraft(),
    val selectedSiteId: Long? = null,
    val operationBusy: Boolean = false,
    val operationDescription: String? = null,
    val lanAccess: Boolean = false,
    val notice: DashboardNotice? = null,
) {
    val selectedSite: Site? get() = sites.firstOrNull { it.id == selectedSiteId }
    val serverRunning: Boolean get() = runtime.status == RemoteServerStatus.RUNNING
    val canManageSites: Boolean get() = serverRunning && !operationBusy && !siteReadFailed && !loadingSites
    val setupReady: Boolean get() = capabilities?.let { it.termuxInstalled && it.termuxCommandPermissionGranted } == true
}
