package dev.wasil.permit.parking

import dev.wasil.permit.data.api.PermitJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * How a park's obligation was met — the badge on a history record.
 *
 * This is the *settlement* half of the split, and it is deliberately not a zone
 * code. Q-Park's badge is a zone code because a zone code is what they sell;
 * ours answers "what covered this park", which is the question the app is built
 * around and the one history is the only place to see answered.
 *
 * [UNSETTLED] is the honest one, and the reason this feature is not blocked on
 * being able to pay: *the spot was charging and nothing covered it* is true and
 * checkable today. [UNKNOWN] is its opposite number and exists for the same
 * reason `parkedOutsideKnown` does — a park with no position resolved is a gap
 * in what we know, never a finding that nothing was owed.
 */
enum class Settlement { PERMIT, HOME, FREE_ZONE, FREE_STREET, UNSETTLED, UNKNOWN }

/**
 * One park, as the app knew it at the moment detection confirmed it.
 *
 * [endedAtMs] is null until something *observed* the park ending — the car's
 * Bluetooth coming back up, which is the only end this app ever sees. It is
 * never inferred from the next park's start: "the car had left by 18:10" is a
 * different statement from "the car left at 17:48", and only one of them is
 * something we watched happen. A record with no end says "from 09:12" rather
 * than inventing the other half of a range.
 *
 * No coordinates. [place] is the geocoded name, resolved once at write time
 * exactly as zone labels already are, so the log reads offline afterwards and
 * holds nothing a position could be recovered from.
 */
@Serializable
data class ParkRecord(
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val settlement: Settlement = Settlement.UNKNOWN,
    /** Only ever set when [settlement] is [Settlement.PERMIT] — a record belongs to a car. */
    val holder: MyCar? = null,
    /** "Molenwijk · Computerweg", from the same geocoder the map header uses. Null when it failed. */
    val place: String? = null,
    /** What the spot cost when you parked: "€3,01/h · until 19:00". Null outside a paid area. */
    val rateText: String? = null,
    /**
     * The obligation, kept beside the settlement rather than folded into it.
     *
     * True when the spot was charging, false when it was not, **null when no
     * zone was resolved at all** — the same three-way distinction
     * `parkedOutsideKnown` exists for. It earns its place the moment the permit
     * is handed away mid-park: without it, re-badging the record would have to
     * guess whether the spot charged, and the guess would be exactly the kind
     * this project pays fines for.
     */
    val paid: Boolean? = null,
)

/**
 * How many parks the log keeps.
 *
 * Two parks a day is about 20 weeks of history, which is far longer than anyone
 * scrolls and still a few tens of kilobytes of JSON in SharedPreferences. The
 * cap exists because an append-only store with no cap is a leak with a slow
 * fuse, not because anything here is expensive.
 */
const val PARK_LOG_CAP = 300

/**
 * The log as a single string, oldest first, trimmed to [PARK_LOG_CAP].
 *
 * Kept apart from the store for the same reason [PendingDecisionRecord] is: the
 * encode/decode round trip — including the cap and the corrupt-reads-as-empty
 * rule — is plain Kotlin and can be tested on the JVM without an emulator.
 */
fun encodeParkLog(records: List<ParkRecord>): String =
    PermitJson.encodeToString(records.takeLast(PARK_LOG_CAP))

/**
 * Reads back what [encodeParkLog] wrote. Anything unparseable — a half-written
 * value, a shape from an older install — reads as an empty log rather than
 * throwing. Losing history is a nuisance; crashing on the History tab is a bug.
 */
fun decodeParkLog(raw: String?): List<ParkRecord> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { PermitJson.decodeFromString<List<ParkRecord>>(raw) }
        .getOrDefault(emptyList())
        .takeLast(PARK_LOG_CAP)
}

/** Appends one park, dropping the oldest once the log is full. */
fun List<ParkRecord>.withPark(record: ParkRecord): List<ParkRecord> =
    (this + record).takeLast(PARK_LOG_CAP)

/**
 * Closes the newest record, if it is still open.
 *
 * Idempotent on purpose: the caller is a worker that re-runs every twenty
 * seconds for the length of a drive, and only its first run has anything to
 * say. A close that arrives before the record's own start is dropped rather
 * than stored — a negative duration is a clock going backwards, not a park.
 */
fun List<ParkRecord>.withOpenParkClosed(endedAtMs: Long): List<ParkRecord> {
    val last = lastOrNull() ?: return this
    if (last.endedAtMs != null || endedAtMs < last.startedAtMs) return this
    return dropLast(1) + last.copy(endedAtMs = endedAtMs)
}

/**
 * Re-badges the newest record once something settles it after the fact.
 *
 * Detection writes what was true when it finished, and for a paid spot with
 * auto-claim off that is [Settlement.UNSETTLED] — nothing had covered it yet.
 * Tapping "Claim" on the notification a minute later makes that stale, and a
 * history that still says "Unsettled" for a park the permit was moved onto is
 * exactly the sort of quietly wrong record this log exists to avoid.
 *
 * Only an open record is re-badged. A park that has already ended is finished
 * business and a claim now belongs to the next one.
 */
fun List<ParkRecord>.withOpenParkSettled(
    settlement: Settlement,
    holder: MyCar?,
): List<ParkRecord> {
    val last = lastOrNull() ?: return this
    if (last.endedAtMs != null) return this
    return dropLast(1) + last.copy(settlement = settlement, holder = holder)
}

/**
 * The other direction: the permit was handed to the other car while this one
 * was still parked, so this park is no longer covered by it.
 *
 * What it becomes is decided by [ParkRecord.paid] and nothing else. A spot we
 * knew was charging becomes [Settlement.UNSETTLED]; one where no zone ever
 * resolved becomes [Settlement.UNKNOWN], because "the permit left" is not
 * evidence that anything was owed. Only a record that currently claims the
 * permit is touched — a park at home stays a park at home no matter where the
 * permit goes.
 */
fun List<ParkRecord>.withOpenParkUncovered(): List<ParkRecord> {
    val last = lastOrNull() ?: return this
    if (last.endedAtMs != null || last.settlement != Settlement.PERMIT) return this
    return dropLast(1) + last.copy(
        settlement = if (last.paid == true) Settlement.UNSETTLED else Settlement.UNKNOWN,
        holder = null,
    )
}

/**
 * Every park, and what it cost. Append-only, device-local, and never shared —
 * a log of where a car has been is the single most sensitive thing this app
 * could hold, and the shared room has no field to put it in.
 */
interface ParkLogStore {
    /** Oldest first, as stored. The screen reverses it. */
    fun all(): List<ParkRecord>
    fun record(record: ParkRecord)
    fun closeOpen(endedAtMs: Long)
    fun settleOpen(settlement: Settlement, holder: MyCar?)
    fun uncoverOpen()
}
