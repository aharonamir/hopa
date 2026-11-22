package com.hopa.backend.accounts

/**
 * E.164 normalization.
 *
 * The phone number is only ever a login lookup: it is normalized here, never
 * emitted on the wire, never put in a token and never logged. [normalize]
 * accepts a leading `+` or a leading `00` (both mean "international"), spaces
 * and dashes anywhere, and returns `+<digits>` when the result is 8-15 digits,
 * or null for anything else -- including letters, an empty string, or more
 * than one leading plus.
 */
object Phones {
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var text = raw
        if (text.startsWith("00")) {
            text = "+" + text.substring(2)
        }
        if (text.startsWith("+")) {
            text = text.substring(1)
        }
        val digits = text.filter { it != ' ' && it != '-' }
        if (digits.isEmpty()) return null
        if (digits.any { !it.isDigit() }) return null
        if (digits.length !in 8..15) return null
        return "+$digits"
    }
}
