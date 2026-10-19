package dev.pocketpaas.data.process.termux

import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.process.LaunchedProcess
import dev.pocketpaas.domain.process.ProcessLauncher
import dev.pocketpaas.domain.process.ProcessRequest
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shell execution happens in Termux, never by executing another Android UID's files. */
class TermuxProcessLauncher(
    private val bridge: TermuxBridge,
    private val applicationScope: CoroutineScope,
) : ProcessLauncher {
    override suspend fun launch(request: ProcessRequest): LaunchedProcess {
        currentCoroutineContext().ensureActive()
        applicationScope.ensureActive()
        // A accepted POST is not reversible by coroutine cancellation. Always obtain its ID
        // before honouring cancellation so the remote child can be explicitly terminated.
        val ticket = withContext(NonCancellable) { bridge.create(request) }
        try {
            currentCoroutineContext().ensureActive()
            applicationScope.ensureActive()
            val process = RemoteProcess(ticket, bridge, applicationScope)
            return LaunchedProcess(process, ticket.pid, process::terminate)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { bridge.stop(ticket, 500L) }.exceptionOrNull()?.let(failure::addSuppressed)
                bridge.releaseAfterFailure(ticket)
            }
            throw failure
        }
    }

    override suspend fun resolveBinary(name: String): String = bridge.resolveBinary(name)

    override suspend fun runtimeLayout(): RuntimeLayout {
        val json = bridge.layoutJson()
        fun path(field: String): String {
            val value = json.requiredString(field)
            if (!value.startsWith("/") || value.contains('\u0000') || value.split('/').any { it == ".." }) {
                throw IOException("Termux returned an invalid runtime layout path.")
            }
            return value
        }
        val root = path("rootPath")
        fun child(field: String): String = path(field).also {
            if (!it.startsWith("$root/")) throw IOException("Termux runtime path is outside its declared root.")
        }
        return RuntimeLayout(
            rootPath = root,
            projectsPath = child("projectsPath"),
            nginxConfigPath = child("nginxConfigPath"),
            nginxHostsPath = child("nginxHostsPath"),
            runtimePath = child("runtimePath"),
            logsPath = child("logsPath"),
        )
    }
}
