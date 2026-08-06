package dev.wasil.permit.ui

/**
 * What the map is centred on, and what the next tap of the focus button will
 * centre it on.
 *
 * One button rather than two. v0.6.4 shipped "centre on me" and "frame both" as
 * separate circles, and they read as the same control — Wasil, within a day:
 * *"2 buttons in map i am uncertain about. They seem to do the same thing. both
 * seem to locate my car and show it."* He is right: when the car is parked near
 * you, centring and framing produce almost the same picture, so two buttons ask
 * the user to predict a difference they cannot see.
 *
 * Cycling is the pattern every maps app uses for exactly this reason, and it
 * turns "which of these two do I want" into "tap until it looks right".
 */
enum class MapFocus {
    /** Centred on the phone. */
    ME,

    /** Zoomed to fit the phone and the car together. */
    BOTH,

    /** Centred on the parked car. */
    CAR,
}

/**
 * The next focus, skipping any state that has nothing to show.
 *
 * With no car there is nothing to frame and nothing to centre on, so the button
 * only ever means [MapFocus.ME] — which is still worth having, because panning
 * away from yourself is otherwise a one-way trip. With no position of our own,
 * the same argument runs the other way.
 *
 * Returns the current focus unchanged when nothing else is available, so the
 * button becomes a no-op rather than a lie about where it took you.
 */
fun nextFocus(current: MapFocus, hasCar: Boolean, hasMe: Boolean): MapFocus {
    val available = buildList {
        if (hasMe) add(MapFocus.ME)
        if (hasCar && hasMe) add(MapFocus.BOTH)
        if (hasCar) add(MapFocus.CAR)
    }
    if (available.isEmpty()) return current
    val at = available.indexOf(current)
    // An unavailable current focus (the car pin was cleared while centred on
    // it) falls to the first available rather than to nothing.
    if (at < 0) return available.first()
    return available[(at + 1) % available.size]
}

/**
 * [current], or the first focus that is actually reachable when it is not.
 *
 * The cycle only advances on a tap, so a focus chosen when a car pin existed
 * survives the pin being cleared — and the button then advertises a move it
 * cannot make. Seen on screen: with nothing parked, the button offered "frame
 * the car and me" over an empty map. Correcting it at render time rather than
 * only on tap keeps the icon honest about what the next tap will do.
 */
fun focusOrFallback(current: MapFocus, hasCar: Boolean, hasMe: Boolean): MapFocus {
    val reachable = when (current) {
        MapFocus.ME -> hasMe
        MapFocus.BOTH -> hasCar && hasMe
        MapFocus.CAR -> hasCar
    }
    if (reachable) return current
    return when {
        hasMe -> MapFocus.ME
        hasCar -> MapFocus.CAR
        else -> current
    }
}
