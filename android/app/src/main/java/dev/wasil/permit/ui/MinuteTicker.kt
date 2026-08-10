package dev.wasil.permit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * The current minute, as state, so a screen that says "right now" keeps meaning
 * it.
 *
 * **Why this exists.** Every live tariff string in the app — "€1,72/h · until
 * 19:00", "Free · from ma 09:00", the permit strip's "Free now" — is computed
 * from a clock that was read **once**, when the screen entered composition, and
 * never again. `MapScreen` held it in `remember(highlight) { … }` keyed on the
 * highlighted area, and `TariffHit`, `ZonePolygon` and `LatLng` are all data
 * classes, so for a parked car that is not moving the key recomputes *equal*
 * every time and the `remember` never invalidates. There was no ticker anywhere
 * in `ui/`.
 *
 * So the chip could be arbitrarily old with nothing on it saying so. Open the
 * Map tab at 18:20 in an area charging from 19:00, put the phone away, walk
 * back at 19:20: the chip still reads "Free until 19:00" — the word that
 * authorises leaving the car, over a boundary that passed twenty minutes ago.
 * A stale reading is pixel-identical to a fresh one, because an absolute clock
 * time carries no self-check.
 *
 * That is the project's paid-for rule pointed at a screen rather than at the
 * wire: the expensive direction to be wrong in is the confident one, and "Free"
 * over a charging street is exactly it.
 *
 * **Ticks on the minute boundary, not on an interval.** Sleeping a fixed 30 or
 * 60 seconds leaves the displayed minute wrong for up to a minute after it
 * turns; sleeping to the top of the next minute means the string changes at the
 * moment the clock does. It costs one wake-up a minute while a screen using it
 * is composed, which is cheaper than the fixed interval it replaces would have
 * been.
 */
@Composable
fun rememberMinuteClock(): State<Calendar> {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = Calendar.getInstance()
            now = current
            val intoMinute = current.get(Calendar.SECOND) * 1000L + current.get(Calendar.MILLISECOND)
            delay(60_000L - intoMinute)
        }
    }
    return rememberUpdatedState(now)
}

/** Monday 0 — the order Amsterdam's own day ranges assume, not `Calendar`'s. */
fun Calendar.handoffDayIndex(): Int = (get(Calendar.DAY_OF_WEEK) + 5) % 7

/** Minutes since midnight, which is what the schedule engine speaks in. */
fun Calendar.minuteOfDay(): Int = get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
