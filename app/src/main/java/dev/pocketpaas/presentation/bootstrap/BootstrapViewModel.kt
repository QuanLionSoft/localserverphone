package dev.pocketpaas.presentation.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketpaas.domain.model.PlatformInfo
import dev.pocketpaas.domain.usecase.InitializePlatform
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BootstrapState {
    data object Loading : BootstrapState
    data class Ready(val platform: PlatformInfo) : BootstrapState
    data object Failed : BootstrapState
}

@HiltViewModel
class BootstrapViewModel @Inject constructor(
    private val initializePlatform: InitializePlatform,
) : ViewModel() {
    private val mutableState = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val state: StateFlow<BootstrapState> = mutableState.asStateFlow()
    private var initialization: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (initialization?.isActive == true) return
        initialization = viewModelScope.launch {
            mutableState.value = BootstrapState.Loading
            mutableState.value = try {
                BootstrapState.Ready(initializePlatform())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                BootstrapState.Failed
            }
        }
    }
}
