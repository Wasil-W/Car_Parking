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

/**
 * What "Sign in and find my cars" came back with.
 *
 * Four outcomes rather than "a list or null", and the split is the whole fix for
 * the defect that prompted it. Wasil, 2026-08-08: *"i entered incorrect
 * credentials and it still showed my cars."* Two things were wrong. The request
 * went out on a token from an earlier sign-in, so wrong credentials never
 * reached the site — that is [dev.wasil.permit.data.auth.TokenStore.clear]'s
 * half. And the return type could not have reported it if they had: `null` meant
 * *rejected*, *unreachable* and *no cars on the account* all at once, so the
 * screen said "Couldn't reach the permit site" for a wrong password.
 *
 * A person needs to be able to tell whether what they typed works. That is the
 * requirement, and one nullable list cannot meet it.
 */
sealed interface SignIn {
    /** Signed in, and the account lists these. */
    data class Cars(val plates: List<String>) : SignIn

    /** Signed in — the credentials are right — but the account covers no cars. */
    data object NoCars : SignIn

    /** The site refused the username and password. */
    data object Rejected : SignIn

    /** We never got an answer: no network, or the site is down. */
    data object Unreachable : SignIn
}

/**
 * What to put on screen under a sign-in that did not produce a list of cars, or
 * null when it did.
 *
 * Separate from the composable so the wording of the one message that matters —
 * "these credentials are wrong" — is pinned by a test rather than by whoever
 * next edits the layout.
 */
fun signInProblemText(result: SignIn): String? = when (result) {
    is SignIn.Cars -> null
    SignIn.NoCars ->
        "Signed in, but that account lists no cars. Enter the plates yourself."
    SignIn.Rejected ->
        "That username and password were refused. Nothing has been changed — " +
            "check them and try again, or enter the plates yourself."
    SignIn.Unreachable ->
        "Couldn't reach the permit site, so the username and password have not " +
            "been checked. Try again, or enter the plates yourself."
}

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
 * With more than two plates the app still stores two, and that is now a choice
 * rather than a limitation. The roster can hold any number; it is built from
 * *the plates the user picked*, not from every entry the account lists — and it
 * has to be, because an account can carry plates that are not in play.
 * `permit.py` records exactly that on Wasil's own permit: *"the third plate
 * belongs to an inactive vehicle and is deliberately not selectable."* Reading
 * `vrns` straight into the roster would put a car nobody drives on the screen
 * and, worse, tip the arity past two and drop both brothers' colours.
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
