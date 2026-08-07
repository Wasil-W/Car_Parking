package dev.wasil.permit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.PermitKind
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.data.store.normalizePlate
import dev.wasil.permit.data.store.roster
import dev.wasil.permit.parking.GuardedClaim
import dev.wasil.permit.parking.GuardedResult
import dev.wasil.permit.parking.ParkOutcome
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.Vehicle
import dev.wasil.permit.parking.rosterFrom
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

data class BlockedInfo(
    val target: Vehicle,
    val otherLabel: String,
    val parkedAtMs: Long,
    val heartbeatAtMs: Long,
    /** False when the other phone never worked out where it parked — see [blockedBody]. */
    val known: Boolean = true,
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
    /**
     * The cars, in slot order.
     *
     * This replaced `options: List<PlateOption>`. `PlateOption` existed only to
     * carry a label, a plate and an enum around together, which is what
     * [Vehicle] already is — and the label half of it was load-bearing in a way
     * it should never have been: `switchTo` decided which car to claim for by
     * comparing the label against the literal string "Wasil", so any other
     * label claimed for Walid.
     */
    val roster: Roster = Roster.SEED,
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
            _state.update { it.copy(roster = config.roster) }
            refresh()
        }
    }

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
        val label = credentialStore.roster().other(stateStore.thisPhoneDrives)?.name ?: return null
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

    fun switchTo(target: Vehicle, force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(switching = target.plate, blocked = null) }
            // The id goes straight through. It used to be recovered from the
            // button's own label by string comparison — `if (label == "Wasil")`
            // — so a label that was not one of those two words claimed for
            // Walid, on a screen that had just been told which car was meant.
            when (val result = guardedClaim().claim(
                target = target.id, force = force, userInitiated = true)) {
                is GuardedResult.Blocked -> _state.update {
                    it.copy(switching = null, blocked = BlockedInfo(
                        target, result.otherLabel,
                        result.other.parkedAtMs, result.other.heartbeatAtMs,
                        known = result.other.parkedOutsideKnown))
                }
                is GuardedResult.Done -> when (val outcome = result.outcome) {
                    is ParkOutcome.Claimed -> _state.update {
                        it.copy(
                            switching = null, activeVrn = outcome.vrn,
                            message = listOfNotNull(
                                "Permit confirmed on ${target.name}'s car (${outcome.vrn})",
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
        state.value.blocked?.let { switchTo(it.target, force = true) }
    }

    fun dismissBlocked() = _state.update { it.copy(blocked = null) }

    /**
     * Stores the permit account, and the cars it covers.
     *
     * [myPlate] and [theirPlate] are what the editor asked for — *your* plate
     * and *the other car's* — and they are no longer stored as "the Wasil slot"
     * and "the Walid slot". That mapping was a latent defect: the editor
     * relabelled its two fields in v0.6.5 while the storage kept the old slot
     * names, so on Walid's phone "your plate" landed in Wasil's slot and a
     * claim would have moved the permit to the wrong car.
     *
     * The roster instead orders the two by plate — the one thing both phones
     * read from the same account and therefore agree on without talking — and
     * [ParkStateStore.thisPhoneDrives] records which of them is this one's.
     */
    fun saveSetup(username: String, password: String, myPlate: String, theirPlate: String) {
        val existing = credentialStore.load()
        val roster = rosterFrom(listOf(myPlate, theirPlate), credentialStore.roster())
        val config = PermitConfig(
            username = username.trim(),
            password = password,
            roster = roster,
            // Carried, never invented. [findPlates] is the only thing that can
            // raise it, because it is the only thing that has read the account.
            permitKind = existing?.permitKind ?: PermitKind.UNKNOWN,
        )
        credentialStore.save(config)
        // Which of the two is this phone's, said once, here. Without it a
        // second phone would keep pointing at whichever slot it picked during
        // first run, which after a re-sort need not be its own car any more.
        roster.vehicles.firstOrNull { it.plate == normalizePlate(myPlate) }
            ?.let { stateStore.thisPhoneDrives = it.id }
        _state.update { it.copy(needsSetup = false, roster = config.roster) }
        refresh()
    }

    /**
     * Signs in and reports every plate the permit account covers, or null if it
     * could not ask.
     *
     * The credentials have to be stored before the call, because
     * `PermitAuthenticator` reads them from the store to obtain a token — there
     * is no way to authenticate a request with credentials held only in a text
     * field. Storing a username and password that turn out to be wrong is
     * harmless and recoverable: nothing acts on a permit account until a plate
     * has been chosen, and Settings can remove it.
     *
     * Plates are returned exactly as the account spells them. Normalising is
     * [saveSetup]'s job, and doing it twice in two places is how the two
     * eventually disagree.
     */
    suspend fun findPlates(username: String, password: String): List<String>? {
        val existing = credentialStore.load()
        credentialStore.save(
            PermitConfig(
                username = username.trim(),
                password = password,
                roster = credentialStore.roster(),
                permitKind = existing?.permitKind ?: PermitKind.UNKNOWN,
            ),
        )
        val product = runCatching { repository.product() }.getOrNull() ?: return null
        if (product.plates.isEmpty()) return null
        // The one moment the app learns what kind of permit this is, recorded
        // here because it is the one call that has just read the account. It
        // arrives in the same response as the plates and used to be discarded
        // with the rest of the object — see ClientProductResponse.name for what
        // is being read and how unverified that guess still is.
        credentialStore.load()?.let { credentialStore.save(it.copy(permitKind = product.kind)) }
        return product.plates
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
