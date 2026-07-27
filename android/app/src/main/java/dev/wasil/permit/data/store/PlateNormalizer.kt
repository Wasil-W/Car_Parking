package dev.wasil.permit.data.store

/** API rejects formatted plates: "RH-950-F" must be sent as "RH950F". */
fun normalizePlate(raw: String): String =
    raw.filter { it.isLetterOrDigit() }.uppercase()
