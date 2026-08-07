package dev.wasil.permit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.PermitKind
import dev.wasil.permit.data.api.rejectedCredentials
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
    /**
     * True when the last read of the permit site did not get through, so
     * [activeVrn] is remembered rather than current.
     *
     * Kept as a flag beside the value rather than as a nullable value, because
     * the two questions are genuinely separate: "who holds it" and "how sure are
     * we". Collapsing them is what produced the reported behaviour — a failed
     * read left `activeVrn` null, the card said "No plate active", and the app
     * had stated as a finding what was only a gap.
     */
    val permitReadFailed: Boolean = false,
    /** When [activeVrn] was last actually read from the site. 0 = never. */
    val activeVrnReadAtMs: Long = 0L,
)

class MainViewModel(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val guardedClaim: () -> GuardedClaim,
    private val sharedStore: () -> SharedStateStore,
    /**
     * Throws away the JWT held in memory, so the next call has to sign in again.
     *
     * A lambda rather than the [dev.wasil.permit.data.auth.TokenStore] itself:
     * this class has no other business with the auth package, and the only thing
     * it ever needs to say is "that session is no longer the one I mean". See
     * [findPlates] for the defect that made it necessary.
     *
     * Deliberately not defaulted to `{}`. A default here would let the whole
     * defect back in by omission, silently, in whichever call site forgot it —
     * and the symptom is a wrong password that looks like success.
     */
    private val forgetSession: () -> Unit,
    /** Stamps when the site was last read. Injected so a test can hold the clock still. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val config = credentialStore.load()
        if (config == null) {
            _state.update { it.copy(needsSetup = true) }
        } else {
            // The last holder is put on screen before the first read, not after
            // it fails. On a cold start with the site down the old code had
            // nothing at all to show, so the card rendered its blank default —
            // which is a statement, and a false one.
            _state.update {
                it.copy(
                    roster = config.roster,
                    activeVrn = stateStore.lastKnownHolderVrn,
                    activeVrnReadAtMs = stateStore.lastKnownHolderAtMs,
                )
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.activePlate() }
                .onSuccess { active ->
                    rememberHolder(active)
                    _state.update {
                        it.copy(
                            loading = false,
                            activeVrn = active,
                            permitReadFailed = false,
                            activeVrnReadAtMs = stateStore.lastKnownHolderAtMs,
                        )
                    }
                }
                .onFailure { e ->
                    // activeVrn is deliberately left standing. A failed read
                    // says nothing about where the permit is, and replacing a
                    // known holder with null would be the app inventing a
                    // finding out of its own inability to look — the same
                    // mistake as publishing "not parked outside" on a failed
                    // GPS read. The card keeps what it had and admits its age;
                    // see permitFreshnessNote.
                    _state.update {
                        it.copy(
                            loading = false,
                            permitReadFailed = true,
                            message = "Couldn't load permit state: ${e.message}",
                        )
                    }
                }
            _state.update { it.copy(otherStatus = loadOtherStatus()) }
        }
    }

    /**
     * Writes down what the site just said, so a later failure has something true
     * to fall back on.
     *
     * Called only from the two places that have genuinely just read the holder —
     * a refresh and a confirmed switch — and from nowhere that merely believes
     * it knows. A store of "last known" that anything may write to is a store of
     * guesses within one release.
     */
    private fun rememberHolder(vrn: String?) {
        stateStore.lastKnownHolderVrn = vrn
        stateStore.lastKnownHolderAtMs = nowMs()
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
                        // A confirmed switch is a read of the site as much as a
                        // write — the repository re-reads the holder before
                        // calling it Claimed — so it refreshes the fallback too.
                        rememberHolder(outcome.vrn)
                        it.copy(
                            switching = null, activeVrn = outcome.vrn,
                            permitReadFailed = false,
                            activeVrnReadAtMs = stateStore.lastKnownHolderAtMs,
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
     * Signs in — genuinely — and reports every plate the permit account covers.
     *
     * The credentials have to be stored before the call, because
     * `PermitAuthenticator` reads them from the store to obtain a token; there
     * is no way to authenticate a request with credentials held only in a text
     * field.
     *
     * **Two things were wrong with that, and both cost the same defect.** Wasil,
     * 2026-08-08: *"i entered incorrect credentials and it still showed my
     * cars."*
     *
     * - The session outlived the credentials. [TokenStore] holds a JWT in memory
     *   with nothing tying it to the username it was issued for, so the request
     *   went out on the *previous* sign-in, succeeded against the *previous*
     *   account, and returned its cars. Nothing the user typed was ever sent.
     *   [forgetSession] is what makes the button do what its label says: with no
     *   token to attach, the call 401s and the authenticator has to log in with
     *   what was just typed — which is where wrong credentials fail.
     * - The old note here said storing a wrong pair was *"harmless and
     *   recoverable"*. That was true only because nothing could tell it was
     *   wrong. It is not harmless: a working permit that is re-typed with a
     *   slip leaves the app holding credentials it cannot use, and every
     *   background claim from then on is signing in with them. So a rejected
     *   sign-in **puts the previous pair back**, and says so on screen.
     *
     * Plates are returned exactly as the account spells them. Normalising is
     * [saveSetup]'s job, and doing it twice in two places is how the two
     * eventually disagree.
     */
    suspend fun findPlates(username: String, password: String): SignIn {
        val existing = credentialStore.load()
        credentialStore.save(
            PermitConfig(
                username = username.trim(),
                password = password,
                roster = credentialStore.roster(),
                permitKind = existing?.permitKind ?: PermitKind.UNKNOWN,
            ),
        )
        // After the save and immediately before the call, so the login that the
        // 401 provokes reads the pair that was just typed.
        forgetSession()

        val attempt = runCatching { repository.product() }
        val product = attempt.getOrElse { error ->
            // Whatever we just wrote is unproven, so it does not get to stay.
            // Restoring rather than clearing: someone re-typing their password
            // in an app that already worked must not end up worse off than
            // before they touched it.
            restore(existing)
            return if (rejectedCredentials(error)) SignIn.Rejected else SignIn.Unreachable
        }
        // Signed in, so the credentials are right and stay — the account simply
        // has nothing to list, which the editor answers by asking for the plates
        // by hand rather than by calling the sign-in a failure.
        if (product.plates.isEmpty()) return SignIn.NoCars
        // The one moment the app learns what kind of permit this is, recorded
        // here because it is the one call that has just read the account. It
        // arrives in the same response as the plates and used to be discarded
        // with the rest of the object — see ClientProductResponse.name for what
        // is being read and how unverified that guess still is.
        credentialStore.load()?.let { credentialStore.save(it.copy(permitKind = product.kind)) }
        return SignIn.Cars(product.plates)
    }

    /**
     * Puts back whatever was stored before a sign-in that failed — including
     * *nothing*, which is why this clears rather than returning early. An
     * install with no permit that tries one and is refused must be left with no
     * permit, not with the pair that was refused.
     */
    private fun restore(previous: PermitConfig?) {
        forgetSession()
        if (previous == null) credentialStore.clear() else credentialStore.save(previous)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
