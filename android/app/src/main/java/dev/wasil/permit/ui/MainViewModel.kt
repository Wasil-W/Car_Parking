package dev.wasil.permit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.data.store.normalizePlate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlateOption(val label: String, val vrn: String)

data class UiState(
    val needsSetup: Boolean = false,
    val loading: Boolean = false,
    val switching: String? = null,
    val activeVrn: String? = null,
    val options: List<PlateOption> = emptyList(),
    val message: String? = null,
)

class MainViewModel(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val config = credentialStore.load()
        if (config == null) {
            _state.update { it.copy(needsSetup = true) }
        } else {
            _state.update { it.copy(options = config.toOptions()) }
            refresh()
        }
    }

    private fun PermitConfig.toOptions() = listOf(
        PlateOption("Wasil", wasilPlate),
        PlateOption("Walid", walidPlate),
    )

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.activePlate() }
                .onSuccess { active ->
                    _state.update { it.copy(loading = false, activeVrn = active) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, message = "Couldn't load permit state: ${e.message}")
                    }
                }
        }
    }

    fun switchTo(option: PlateOption) {
        viewModelScope.launch {
            _state.update { it.copy(switching = option.vrn) }
            runCatching { repository.switchTo(option.vrn) }
                .onSuccess { result ->
                    when (result) {
                        is PermitRepository.SwitchResult.Confirmed -> _state.update {
                            it.copy(
                                switching = null,
                                activeVrn = result.activeVrn,
                                message = "Permit confirmed on ${option.label}'s car (${result.activeVrn})",
                            )
                        }
                        is PermitRepository.SwitchResult.Mismatch -> _state.update {
                            it.copy(
                                switching = null,
                                activeVrn = result.serverActiveVrn,
                                message = "WARNING: server reports ${result.serverActiveVrn ?: "no plate"} active - check the website!",
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(switching = null, message = "Switch failed: ${e.message}. Permit NOT changed - retry.")
                    }
                }
        }
    }

    fun saveSetup(username: String, password: String, wasilPlate: String, walidPlate: String) {
        val config = PermitConfig(
            username = username.trim(),
            password = password,
            wasilPlate = normalizePlate(wasilPlate),
            walidPlate = normalizePlate(walidPlate),
        )
        credentialStore.save(config)
        _state.update { it.copy(needsSetup = false, options = config.toOptions()) }
        refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
