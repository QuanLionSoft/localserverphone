package dev.pocketpaas.data.dashboard

import dev.pocketpaas.domain.dashboard.DashboardOperationKind
import dev.pocketpaas.domain.dashboard.DashboardOperationState
import dev.pocketpaas.domain.remote.RemoteErrorKind
import dev.pocketpaas.domain.remote.RemoteOperationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardCommandResult(val message: String, val siteId: Long? = null)

/** Application-scoped admission and result ownership shared by every dashboard instance. */
class DashboardOperationCoordinator(private val scope: CoroutineScope) {
    private val lock = Any()
    private var sequence = 0L
    private var active: Job? = null
    private val mutableOperation = MutableStateFlow(DashboardOperationState())
    val operation: StateFlow<DashboardOperationState> = mutableOperation.asStateFlow()

    fun submit(
        kind: DashboardOperationKind,
        siteId: Long? = null,
        replace: Boolean = false,
        action: suspend () -> DashboardCommandResult,
    ) {
        val job = synchronized(lock) {
            if (active?.isCompleted == false && !replace) return
            if (replace) active?.cancel()
            val id = ++sequence
            mutableOperation.value = DashboardOperationState(id, kind, siteId, running = true)
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = action()
                    finish(id, result.message, failed = false, resultSiteId = result.siteId)
                } catch (_: CancellationException) {
                    finish(id, "İşlem iptal edildi.", failed = false, cancelled = true)
                } catch (failure: Exception) {
                    finish(id, safeMessage(failure), failed = true)
                }
            }.also { created ->
                active = created
                // Cancellation can occur before a lazy coroutine enters its try/catch body.
                created.invokeOnCompletion { failure ->
                    if (failure is CancellationException) finish(id, "İşlem iptal edildi.", failed = false, cancelled = true)
                }
            }
        }
        job.start()
    }

    fun cancel() {
        synchronized(lock) { active?.cancel() }
    }

    fun dismissResult() {
        synchronized(lock) {
            if (!mutableOperation.value.running) mutableOperation.value = DashboardOperationState(id = mutableOperation.value.id)
        }
    }

    private fun finish(id: Long, message: String, failed: Boolean, cancelled: Boolean = false, resultSiteId: Long? = null) {
        synchronized(lock) {
            if (mutableOperation.value.id == id && mutableOperation.value.running) {
                mutableOperation.value = mutableOperation.value.copy(
                    running = false,
                    message = message,
                    failed = failed,
                    cancelled = cancelled,
                    siteId = resultSiteId ?: mutableOperation.value.siteId,
                )
                active = null
            }
        }
    }

    private fun safeMessage(failure: Exception): String = when ((failure as? RemoteOperationException)?.kind) {
        RemoteErrorKind.NOT_FOUND -> "Proje bulunamadı."
        RemoteErrorKind.CONFLICT -> "Projenin durumunu ve alan adını kontrol edin."
        RemoteErrorKind.UNAVAILABLE -> "Önce ana sunucuyu başlatın."
        RemoteErrorKind.FAILED, null -> "İşlem tamamlanamadı. Sunucu durumunu ve proje günlüklerini kontrol edin."
    }
}
