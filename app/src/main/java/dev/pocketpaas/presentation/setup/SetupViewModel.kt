package dev.pocketpaas.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketpaas.domain.setup.SetupPreferences
import dev.pocketpaas.domain.setup.SetupVerifier
import dev.pocketpaas.domain.setup.TermuxPermissionInspector
import dev.pocketpaas.domain.setup.TermuxPermissionState
import dev.pocketpaas.domain.setup.TermuxPermissionStatus
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val loading: Boolean = true,
    val completed: Boolean = false,
    val commandCopied: Boolean = false,
    val checking: Boolean = false,
    val verified: Boolean = false,
    val startRequest: Long? = null,
    val error: String? = null,
    val permissionStatus: TermuxPermissionStatus? = null,
    val inspectingPermission: Boolean = true,
    val permissionError: String? = null,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val preferences: SetupPreferences,
    private val verifier: SetupVerifier,
    private val permissionInspector: TermuxPermissionInspector,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupUiState())
    val state = mutableState.asStateFlow()
    private var check: Job? = null
    private var generation = 0L
    private var requestId = 0L
    private var saving = false
    private var permissionCheck: Job? = null

    init {
        refreshPermission()
        viewModelScope.launch {
            try {
                val completed = preferences.isCompleted()
                mutableState.update { it.copy(loading = false, completed = completed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "Kurulum bilgisi okunamadı; adımları yeniden kontrol edebilirsiniz.") }
            }
        }
    }

    fun commandCopied() { mutableState.update { it.copy(commandCopied = true, error = null) } }
    fun showError(message: String) { mutableState.update { it.copy(error = message) } }

    fun refreshPermission() {
        if (permissionCheck?.isActive == true) return
        mutableState.update { it.copy(inspectingPermission = true, permissionError = null) }
        permissionCheck = viewModelScope.launch {
            try {
                val status = permissionInspector.inspect()
                if (!status.granted) cancelCheck()
                mutableState.update { it.copy(permissionStatus = status) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cancelCheck()
                mutableState.update { it.copy(permissionStatus = null, permissionError = "İzin durumu okunamadı. İzni kontrol et düğmesine yeniden dokunun.") }
            } finally { mutableState.update { it.copy(inspectingPermission = false) } }
        }
    }

    fun checkAndStart() {
        if (check?.isActive == true) return
        if (state.value.inspectingPermission || state.value.permissionStatus?.granted != true) {
            showError(permissionMessage(state.value.permissionStatus))
            return
        }
        val currentGeneration = ++generation
        mutableState.update { it.copy(checking = true, verified = false, startRequest = null, error = null) }
        check = viewModelScope.launch {
            try {
                val status = permissionInspector.inspect()
                if (generation != currentGeneration) return@launch
                mutableState.update { it.copy(permissionStatus = status) }
                if (!status.granted) {
                    showError(permissionMessage(status))
                    return@launch
                }
                val result = verifier.verify()
                if (generation == currentGeneration) {
                    mutableState.update { current ->
                        if (result.ready) current.copy(verified = true, startRequest = ++requestId)
                        else current.copy(error = "Eksik araçlar: ${result.missingTools.joinToString(", ")}. Kurulum komutunu Termux'ta yeniden çalıştırın.")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (generation == currentGeneration) connectionError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == currentGeneration) connectionError()
            } finally {
                if (generation == currentGeneration) mutableState.update { it.copy(checking = false) }
            }
        }
    }

    /** Consumed once by the visible activity, before requesting notification permission. */
    fun consumeStartRequest(id: Long): Boolean {
        if (state.value.startRequest != id || !state.value.verified || state.value.permissionStatus?.granted != true) return false
        mutableState.update { it.copy(startRequest = null) }
        return true
    }

    fun cancelCheck() {
        generation++
        check?.cancel()
        mutableState.update { it.copy(checking = false, verified = false, startRequest = null) }
    }

    fun onHidden() {
        if (state.value.checking || state.value.startRequest != null) {
            cancelCheck()
            showError("Kontrol ekranı kapandığı için başlatma iptal edildi. Hazır olduğunuzda Kontrol et ve başlat düğmesine yeniden dokunun.")
        }
    }

    /** Called only after the foreground API and Nginx are actually running. */
    fun complete() {
        if (!state.value.verified || state.value.completed || saving) return
        saving = true
        viewModelScope.launch {
            try {
                preferences.markCompleted()
                mutableState.update { it.copy(completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Sunucu hazır; kurulum bilgisi kaydedilemedi. Paneli açabilirsiniz.") }
            } finally { saving = false }
        }
    }

    private fun connectionError() {
        mutableState.update { it.copy(error = "Termux bağlantısı kurulamadı. Termux'u açın, kurulum komutunun tamamlandığını ve komut iznini kontrol edip yeniden deneyin.") }
    }

    private fun permissionMessage(status: TermuxPermissionStatus?): String = when (status?.state) {
        TermuxPermissionState.PERMISSION_UNAVAILABLE -> "Bu Termux sürümü bağlantı iznini sunmuyor. İzin adımındaki uyumlu F-Droid/GitHub sürümü açıklamasını izleyin."
        TermuxPermissionState.TERMUX_MISSING -> "Önce Termux adımını tamamlayın."
        TermuxPermissionState.TERMUX_DISABLED -> "Termux veya komut servisi devre dışı. Termux uygulama ayarlarını kontrol edin."
        TermuxPermissionState.DENIED -> "Önce İzin adımında Pocket PaaS için Termux komut iznini açın."
        else -> "İzin kontrolünün tamamlanmasını bekleyin veya İzni kontrol et düğmesine dokunun."
    }
}
