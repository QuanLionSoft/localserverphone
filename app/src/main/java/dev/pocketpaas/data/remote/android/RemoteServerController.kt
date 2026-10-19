package dev.pocketpaas.data.remote.android

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Call start from a visible Activity after the user chooses whether LAN access is allowed. */
@Singleton
class RemoteServerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runtime: RemoteServerRuntime,
    private val apiKeys: AndroidApiKeyStore,
) {
    val state: StateFlow<RemoteServerState> get() = runtime.state

    fun start(enableLanAccess: Boolean = false, startNetwork: Boolean = true) {
        context.startForegroundService(
            Intent(context, RemoteServerService::class.java)
                .setAction(RemoteServerService.ACTION_START)
                .putExtra(RemoteServerService.EXTRA_LAN_ACCESS, enableLanAccess)
                .putExtra(RemoteServerService.EXTRA_START_NETWORK, startNetwork),
        )
    }

    fun retryNetwork() {
        context.startForegroundService(
            Intent(context, RemoteServerService::class.java).setAction(RemoteServerService.ACTION_START_NETWORK),
        )
    }

    /** The service handles termination before it removes its foreground notification. */
    fun stop() {
        context.startForegroundService(
            Intent(context, RemoteServerService::class.java).setAction(RemoteServerService.ACTION_STOP),
        )
    }

    /** Key access is local UI only; it is never an HTTP route or a notification field. */
    suspend fun apiKey(): String = apiKeys.token()

}
