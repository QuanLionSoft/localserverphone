package dev.pocketpaas.data.dashboard.android

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.core.di.ApplicationScope
import dev.pocketpaas.core.di.IoDispatcher
import dev.pocketpaas.data.dashboard.DashboardCommandResult
import dev.pocketpaas.data.dashboard.DashboardOperationCoordinator
import dev.pocketpaas.data.remote.android.RemoteServerController
import dev.pocketpaas.domain.dashboard.DashboardActions
import dev.pocketpaas.domain.dashboard.DashboardOperationKind
import dev.pocketpaas.domain.remote.LocalSiteOperations
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** Owns local mutations for the whole application rather than the current screen. */
@Singleton
class AndroidDashboardActions @Inject constructor(
    private val controller: RemoteServerController,
    private val sites: LocalSiteOperations,
    @param:ApplicationContext private val context: Context,
    private val policy: DeploymentPolicy,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope applicationScope: CoroutineScope,
) : DashboardActions {
    private val coordinator = DashboardOperationCoordinator(applicationScope)
    override val operation = coordinator.operation

    override fun startServer(enableLanAccess: Boolean) = coordinator.submit(DashboardOperationKind.SERVER_START) {
        controller.start(enableLanAccess = enableLanAccess, startNetwork = true)
        DashboardCommandResult("Sunucu ve tünel için başlatma isteği gönderildi.")
    }

    override fun stopServer() = coordinator.submit(DashboardOperationKind.SERVER_STOP, replace = true) {
        controller.stop()
        DashboardCommandResult("Durdurma isteği gönderildi.")
    }

    override fun retryNetwork() = coordinator.submit(DashboardOperationKind.NETWORK_START) {
        controller.retryNetwork()
        DashboardCommandResult("Nginx ve tünel yeniden başlatılıyor.")
    }

    override fun startSite(id: Long) = coordinator.submit(DashboardOperationKind.SITE_START, id) {
        sites.start(id)
        DashboardCommandResult("Proje başlatıldı.")
    }

    override fun stopSite(id: Long) = coordinator.submit(DashboardOperationKind.SITE_STOP, id) {
        sites.stop(id)
        DashboardCommandResult("Proje durduruldu.")
    }

    override fun deploy(request: RemoteDeployRequest, uri: String) = coordinator.submit(DashboardOperationKind.DEPLOY, request.siteId) {
        val source = AndroidContentUriArchiveSource(context, Uri.parse(uri), policy, ioDispatcher)
        val deployed = sites.deploy(request, source)
        DashboardCommandResult("Proje yayınlandı.", siteId = deployed.id)
    }

    override fun cancelOperation() = coordinator.cancel()
    override fun dismissResult() = coordinator.dismissResult()
}
