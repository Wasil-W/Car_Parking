package dev.wasil.permit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.data.store.normalizePlate
import dev.wasil.permit.parking.GuardedClaim
import dev.wasil.permit.parking.GuardedResult
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkOutcome
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.other
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.SharedStateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A plate the permit can sit on. [car] is the identity; [label] is only ever
 * display text. They were previously matched by comparing label strings, which
 * meant a typo silently disabled the main button and — worse — mapped an
 * unrecognised label to Walid, lighting the wrong brother's arc.
 */
data class PlateOption(val label: String, val vrn: String, val car: MyCar)

data class BlockedInfo(
    val option: PlateOption,
    val otherLabel: String,
    val parkedAtMs: Long,
    val heartbeatAtMs: Long,
)

data class UiState(
    /**
     * Whether a permit account is stored. No longer a gate — it stopped being
     * one in v0.6.4, when "no permit" stopped being treated as broken setup.
     * It is read only to decide which of the three permit screens is true; see
     * [PermitView].
     */
    val needsSetup: Boolean = false,
    val loading: Boolean = false,
    val switching: String? = null,
    val activeVrn: String? = null,
    val options: List<PlateOption> = emptyList(),
    val message: String? = null,
    val otherStatus: String? = null,
    val blocked: BlockedInfo? = null,
)

class MainViewModel(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val guardedClaim: () -> GuardedClaim,
    private val sharedStore: () -> SharedStateStore,
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

    // The one place a MyCar is bound to its plate and its display name, so the
    // rest of the UI can match on the enum instead of on text.
    private fun PermitConfig.toOptions() = listOf(
        PlateOption(MyCar.WASIL.label(), wasilPlate, MyCar.WASIL),
        PlateOption(MyCar.WALID.label(), walidPlate, MyCar.WALID),
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
            _state.update { it.copy(otherStatus = loadOtherStatus()) }
        }
    }

    private suspend fun loadOtherStatus(): String? {
        val store = sharedStore()
        if (!store.configured) return null
        val label = stateStore.myCar?.other()?.label() ?: return null
        return runCatching {
            val other = store.readOther()
            val time = { ms: Long -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) }
            when {
                other == null -> "$label: no data yet"
                !other.parkedOutside -> "$label: not parked outside"
                System.currentTimeMillis() - other.heartbeatAtMs > ClaimGuard.STALE_AFTER_MS ->
                    "$label: parked (stale — last seen ${time(other.heartbeatAtMs)})"
                else -> "$label: parked outside since ${time(other.parkedAtMs)}"
            }
        }.getOrDefault("$label: status unavailable")
    }

    fun switchTo(option: PlateOption, force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(switching = option.vrn, blocked = null) }
            val target = if (option.label == "Wasil") MyCar.WASIL else MyCar.WALID
            when (val result = guardedClaim().claim(
                target = target, force = force, userInitiated = true)) {
                is GuardedResult.Blocked -> _state.update {
                    it.copy(switching = null, blocked = BlockedInfo(
                        option, result.otherLabel,
                        result.other.parkedAtMs, result.other.heartbeatAtMs))
                }
                is GuardedResult.Done -> when (val outcome = result.outcome) {
                    is ParkOutcome.Claimed -> _state.update {
                        it.copy(
                            switching = null, activeVrn = outcome.vrn,
                            message = listOfNotNull(
                                "Permit confirmed on ${option.label}'s car (${outcome.vrn})",
                                result.guardSkippedNote,
                            ).joinToString(" — "),
                        )
                    }
                    is ParkOutcome.MismatchDetected -> _state.update {
                        it.copy(
                            switching = null, activeVrn = outcome.serverVrn,
                            message = "The site still shows ${outcome.serverVrn ?: "no plate"} on the permit.",
                        )
                    }
                    // Not "finish setup": there is nothing unfinished about an
                    // install with no permit on it. The only honest reading of
                    // this outcome is that there is no permit to move, which is
                    // a fact about the account rather than a fault in the app.
                    ParkOutcome.NotConfigured -> _state.update {
                        it.copy(switching = null, message = "No permit added yet — add one in Settings.")
                    }
                    else -> _state.update {
                        it.copy(switching = null, message = "Switch failed. Permit NOT changed - retry.")
                    }
                }
            }
        }
    }

    fun confirmBlockedSwitch() {
        state.value.blocked?.let { switchTo(it.option, force = true) }
    }

    fun dismissBlocked() = _state.update { it.copy(blocked = null) }

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
