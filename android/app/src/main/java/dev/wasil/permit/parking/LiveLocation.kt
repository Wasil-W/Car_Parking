package dev.wasil.permit.parking

/**
 * How often the phone's position is sampled while the car's Bluetooth is up.
 *
 * Twenty seconds is a compromise, and both ends of it matter. Faster buys
 * nothing: the only sample that is ever used is the last one before the link
 * drops, so the question is "how far can the car travel between the final
 * sample and switching off", and at any speed you park at, twenty seconds is
 * metres. Slower starts to cost accuracy in exactly the case this exists for —
 * rolling into a garage, where fixes stop arriving the moment you are under
 * concrete and the last good one wants to be as late as possible.
 *
 * It is also deliberately not a location *stream*. A continuous high-accuracy
 * request for the length of every drive is the kind of thing that shows up in
 * the battery screen with the app's name next to it, and this is a permit app,
 * not a tracker.
 */
const val LIVE_POLL_INTERVAL_MS = 20_000L

/**
 * How old the last driving sample may be and still stand in for the parking
 * spot when the link drops.
 *
 * Three minutes, chosen against [LIVE_POLL_INTERVAL_MS] rather than in the
 * abstract: while sampling is working the last sample is twenty seconds old, so
 * anything past three minutes means roughly eight polls in a row returned
 * nothing, and a trail that has been dead that long is no longer evidence of
 * where the car stopped — it is evidence of where the phone last saw the sky.
 *
 * The direction of the risk is what sets the number. The trail is cleared when
 * the car connects, so the oldest thing it can hold is the *start* of this
 * drive — the driveway, the home zone. Sealing that would tell the app "you are
 * parked at home, no permit needed" while the car sits in town collecting a
 * fine. That is the same failure [CACHED_FIX_MAX_AGE_MS] exists to prevent, and
 * it is the expensive direction. Refusing a stale trail costs nothing: the app
 * falls back to asking, exactly as it did before any of this existed.
 *
 * One honest imprecision: a sample is stamped when it is *taken*, and what is
 * taken may be the phone's cached fix, already up to [CACHED_FIX_MAX_AGE_MS]
 * old. So the true worst case here is five minutes, not three. It is left that
 * way rather than tracked exactly, because the compounding only bites if
 * sampling dies in the final minutes of a drive, and a five-minute-old position
 * from a car that was still moving is not the failure this cap exists to catch
 * — that one is a fix from the *start* of the journey, which is far older than
 * either number.
 */
const val LIVE_FIX_MAX_AGE_MS = 3 * 60_000L

/**
 * A position captured while the car's Bluetooth was still **connected** — a
 * point on the drive, not a parking spot.
 *
 * The whole design of this class is one refusal: **it will not give you
 * coordinates.** [GeoPoint] goes in through [captured] and does not come back
 * out. There is no property, no getter, no `component1()`, no `copy()`. That is
 * why it is a hand-written class and not a `data class` — the generated
 * `component1()` would hand the point straight back and quietly undo the point
 * of the type.
 *
 * The reason is a promise made to Wasil and worth stating in code rather than
 * in a comment: *only the disconnect-moment location may ever trigger a zone
 * lookup, a permit switch, or a tariff purchase.* Everything on that path takes
 * a [ParkedFix]. So `zoneResolver.resolve(live)`, `confirmedPark(live)` and
 * `guardedClaim.claim(live)` are not bugs waiting to be noticed in review —
 * they do not compile, and neither does any attempt to unwrap one first.
 *
 * The single door between the two is [sealAtDisconnect], which refuses while
 * the link is still up. A poll that fires late, a worker that outlives the
 * drive, a race between the sampler and the receiver — none of them can reach
 * the claim path, because none of them can produce the type it takes.
 */
class LiveLocation private constructor(
    private val fix: GeoPoint,
    val capturedAtMs: Long,
) {
    companion object {
        /** A sample taken while driving. The only way in. */
        fun captured(fix: GeoPoint, atMs: Long): LiveLocation = LiveLocation(fix, atMs)

        /**
         * Reads back what [encode] wrote, or null for anything unparseable —
         * a missing value, a half-written record, a format from an older
         * install. A trail we cannot read is treated as no trail at all, which
         * lands on the safe side: the app asks rather than guesses.
         */
        fun decode(raw: String?): LiveLocation? {
            val parts = raw?.split(SEPARATOR) ?: return null
            if (parts.size != 4) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lng = parts[1].toDoubleOrNull() ?: return null
            val accuracy = parts[2].toFloatOrNull() ?: return null
            val atMs = parts[3].toLongOrNull() ?: return null
            return LiveLocation(GeoPoint(lat, lng, accuracy), atMs)
        }

        private const val SEPARATOR = "|"
    }

    /**
     * The trail has to survive the drive, and the drive spans several separate
     * worker runs — so it lives in SharedPreferences. That would normally mean
     * exposing lat/lng for the store to write, which is exactly what this class
     * refuses to do, so the store is handed an opaque string instead and never
     * learns what is in it. [decode] is the only way back, and it returns a
     * [LiveLocation] rather than a point, so the round trip cannot be used to
     * launder a driving position into the claim path.
     */
    fun encode(): String = listOf(fix.lat, fix.lng, fix.accuracyM, capturedAtMs)
        .joinToString(SEPARATOR)

    /**
     * The one and only door from a driving position to one the claim path will
     * accept — and it is closed unless the car has actually disconnected.
     *
     * Returns null when [linkStillUp], which is the structural half of the
     * promise: a sampler that fires late, or a worker still running after the
     * link came back, gets nothing rather than a position the app would act on.
     * Also returns null once the sample is past [LIVE_FIX_MAX_AGE_MS], or if
     * the clock has moved backwards — a negative age is not "brand new".
     */
    fun sealAtDisconnect(linkStillUp: Boolean, nowMs: Long): ParkedFix? {
        if (linkStillUp) return null
        if (nowMs - capturedAtMs !in 0..LIVE_FIX_MAX_AGE_MS) return null
        return ParkedFix.atDisconnect(fix)
    }

    override fun equals(other: Any?): Boolean = other is LiveLocation &&
        other.fix == fix && other.capturedAtMs == capturedAtMs

    override fun hashCode(): Int = 31 * fix.hashCode() + capturedAtMs.hashCode()

    /**
     * Deliberately says nothing about where the car is. A driving position in a
     * log line or a crash report is the one place this data has no business
     * being, and a generated `toString()` would put it there by default.
     */
    override fun toString(): String = "LiveLocation(capturedAtMs=$capturedAtMs)"
}

/**
 * A position the app is allowed to **act** on: resolve a zone, switch a permit,
 * buy a tariff.
 *
 * The name is the contract. Every parameter on the claim path is this type, so
 * "could a driving position end up here?" is answered by the compiler rather
 * than by reading the call chain. Two things produce one, and both are the
 * moment the car's link dropped: a fresh fix taken right then, and
 * [LiveLocation.sealAtDisconnect].
 */
@JvmInline
value class ParkedFix private constructor(val point: GeoPoint) {
    companion object {
        /**
         * A fix taken at the moment the car disconnected — either straight from
         * the GPS or the phone's recent cached one.
         *
         * Public because the detection worker legitimately needs it; that is not
         * the hole it looks like. The guarantee never depended on this
         * constructor being unreachable — it depends on there being no way to
         * get a [GeoPoint] out of a [LiveLocation] to hand it.
         */
        fun atDisconnect(point: GeoPoint): ParkedFix = ParkedFix(point)
    }
}

/**
 * Turns the drive that just ended into a position the claim path may act on,
 * or null when there is nothing trustworthy to hand it.
 *
 * This is the repair to the bug that made the app ask about the permit at home
 * and show no car on the map at the same time. Both came from one null: parking
 * in a garage, or after the phone had sat still, left `currentLocation()` with
 * nothing to return, and with no position the app could not tell home from a
 * paid street so it asked — at home, where a fix fails most and the question is
 * least wanted. The drive to the house produced positions the whole way; this
 * is what keeps the last of them.
 */
fun sealAtDisconnect(connected: Boolean, last: LiveLocation?, nowMs: Long): ParkedFix? =
    last?.sealAtDisconnect(linkStillUp = connected, nowMs = nowMs)
