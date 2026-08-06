package dev.wasil.permit.ui

/**
 * Turning the plates the permit account lists into the two the app stores.
 *
 * Setup used to ask the user to type both plates in by hand, while the account
 * had been telling the app what they were since v0.1 — `activePlate()` read the
 * whole list, took the one holding a session and discarded the rest. Wasil, on
 * being shown the form: *"instead of making myself put the licences show them
 * to me."*
 *
 * Kept apart from the composable so the awkward cases are pinned by tests
 * rather than discovered on a phone: an account with one plate, with three, or
 * with a plate the user has since removed.
 */

/** What the editor should show once the account has answered. */
sealed interface PlateChoice {
    /** Nothing usable came back — fall through to typing them in. */
    data object Manual : PlateChoice

    /**
     * The account lists exactly one plate. There is no second car to hand to,
     * and no choice to make: it is yours by elimination.
     */
    data class Only(val plate: String) : PlateChoice

    /** Two or more. The user says which is theirs; everything else is the other car. */
    data class Pick(val plates: List<String>) : PlateChoice
}

fun plateChoiceFor(accountPlates: List<String>): PlateChoice {
    val clean = accountPlates.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    return when (clean.size) {
        0 -> PlateChoice.Manual
        1 -> PlateChoice.Only(clean.first())
        else -> PlateChoice.Pick(clean)
    }
}

/**
 * The pair to store, given the whole list and the one the user claimed.
 *
 * Null until the answer is unambiguous, so the Save button stays disabled
 * rather than storing a guess — a wrong plate here means the permit moves to a
 * car that is not yours, which is the expensive direction.
 *
 * With more than two plates the app still stores two, because everything above
 * it is still built for two cars. That is a real limitation rather than a
 * hidden one: the roster refactor is the release that lifts it, and until then
 * picking the second explicitly beats guessing which of three the user meant.
 */
fun platePairFor(accountPlates: List<String>, mine: String?, theirs: String?): Pair<String, String>? {
    val clean = accountPlates.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    val m = mine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (m !in clean) return null
    val others = clean.filterNot { it == m }
    val t = theirs?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        // Exactly two plates settles the second without asking twice.
        ?: others.singleOrNull()
        ?: return null
    if (t !in clean || t == m) return null
    return m to t
}
