package dev.wasil.permit.data.api

/**
 * What kind of permit the account holds — and therefore whether Amsterdam's
 * exception areas bind it.
 *
 * The app had no concept of a permit type at all. It assumed "there is a
 * permit, permits work", which is true of Wasil's visitor permit and
 * unverified for anybody else. `parkeerzones_uitzondering` lists 66 areas —
 * Haarlemmerdijk, Javastraat, PC Hooftstraat and other shopping streets —
 * that state *"uw parkeervergunning geldt niet van ma t/m za 9.00 tot 18.00
 * uur"*. Those bind **regular resident permits**. A visitor permit has access
 * at all times. So the type is the single fact that decides whether those 66
 * areas are a hazard or noise, and the app could not name it.
 *
 * See `docs/TIMELINE.md`, Planned 1.
 */
enum class PermitKind {
    /** Bezoekersvergunning. Valid at all times; the exception areas do not apply. */
    VISITOR,

    /** Bewonersvergunning. The 66 exception areas apply, ma–za 09:00–18:00. */
    RESIDENT,

    /**
     * Nobody has said.
     *
     * The honest default, and the reason it is treated as restricted below: a
     * permit we cannot name might be the restricted kind, and assuming
     * otherwise is the direction that costs a fine. Same rule as
     * `parkedOutsideKnown` — a gap is not a finding.
     */
    UNKNOWN,
}

/**
 * Whether Amsterdam's 66 exception areas bind this permit.
 *
 * [PermitKind.UNKNOWN] answers **true**, deliberately. "We do not know" is not
 * "you are covered", and the expensive direction to be wrong in is the one that
 * tells someone their permit works where it does not.
 */
val PermitKind.boundByExceptionAreas: Boolean
    get() = this != PermitKind.VISITOR

/**
 * The kind a product name states, or [PermitKind.UNKNOWN] when it states
 * nothing this recognises.
 *
 * Matched against the council's own vocabulary rather than against anything
 * observed: `bezoeker` is what Amsterdam calls a visitor permit and `bewoner` a
 * resident one, and both words appear in the product names on
 * amsterdam.nl/parkeren. Anything else — including a name in a language or a
 * spelling not covered here — falls through to UNKNOWN and is therefore treated
 * as restricted, which is the safe direction.
 *
 * **This has never been run against a real response.** No captured
 * `getClientProduct` body exists; see [ClientProductResponse.name] for what is
 * actually being read and why that guess is free.
 */
fun permitKindFor(productName: String?): PermitKind {
    val text = productName?.lowercase()?.takeIf { it.isNotBlank() } ?: return PermitKind.UNKNOWN
    return when {
        "bezoeker" in text || "visitor" in text -> PermitKind.VISITOR
        "bewoner" in text || "resident" in text -> PermitKind.RESIDENT
        else -> PermitKind.UNKNOWN
    }
}
