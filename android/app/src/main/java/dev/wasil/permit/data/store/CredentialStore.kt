package dev.wasil.permit.data.store

import dev.wasil.permit.data.api.PermitKind
import dev.wasil.permit.parking.Roster

/**
 * The permit account, as this phone knows it.
 *
 * The two plate fields are gone. `wasilPlate` and `walidPlate` were the last
 * place the app hard-coded two brothers into a data type, and they carried a
 * quiet defect with them: the editor asks for "your plate" and "the other car's
 * plate", but stored the first as the *Wasil* slot whichever phone was typing —
 * so on Walid's phone the two were swapped, and a claim moved the permit to the
 * wrong car. The roster stores cars, and [dev.wasil.permit.parking.ParkStateStore.thisPhoneDrives]
 * says which one is this phone's, so there is no longer a slot to put a plate
 * in the wrong one of.
 */
data class PermitConfig(
    val username: String,
    val password: String,
    /** Which cars the permit covers. Seeded, then replaced by what the account says. */
    val roster: Roster = Roster.SEED,
    /**
     * What kind of permit this is.
     *
     * [PermitKind.UNKNOWN] until an account has been read *and* said so, which
     * as of this release is never — see [dev.wasil.permit.data.api.ClientProductResponse.name].
     * Unknown is treated as the restricted kind everywhere it is read, because
     * that is the direction that cannot cost a fine.
     */
    val permitKind: PermitKind = PermitKind.UNKNOWN,
)

interface CredentialStore {
    fun load(): PermitConfig?
    fun save(config: PermitConfig)
    fun clear()
}

/**
 * The roster to work from, permit or no permit.
 *
 * An install with no permit account still has cars — it just does not know
 * their plates yet — and setup has to be able to ask which one this phone
 * drives before anyone has signed in to anything. Falling back to the seed here
 * rather than making every caller handle a null is what keeps
 * [dev.wasil.permit.parking.ParkStateStore.thisPhoneDrives] resolvable at all
 * times, including straight after "Remove permit".
 */
fun CredentialStore.roster(): Roster = load()?.roster ?: Roster.SEED
