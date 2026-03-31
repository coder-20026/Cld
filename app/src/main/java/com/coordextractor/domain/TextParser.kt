package com.coordextractor.domain

/**
 * TextParser — OCR text se coordinates extract karta hai.
 *
 * Input:  "Mar 28, 2026 10:36am 22.1234N 71.1234E Borsad Gujarat"
 * Output: "22.1234,71.1234"
 *
 * S → negative latitude, W → negative longitude
 */
object TextParser {

    /**
     * Primary regex: handles both spaced and no-space variants
     *   22.1234N 71.1234E  ✅
     *   22.1234N71.1234E   ✅
     *   22.1234°N 71.1234°E ✅
     */
    private val COORD_REGEX = Regex(
        """(\d{1,3}(?:\.\d+)?)\s*°?\s*([NSns])\s*,?\s*(\d{1,3}(?:\.\d+)?)\s*°?\s*([EWew])"""
    )

    /**
     * OCR misread corrections — common character confusions
     */
    private val OCR_FIXES = mapOf(
        // Letter → digit
        "O" to "0", "o" to "0",
        "l" to "1", "I" to "1", "i" to "1",
        // Cyrillic lookalikes for directions
        "Ν" to "N", "И" to "N", "ñ" to "N",
        "Ε" to "E", "е" to "E", "Е" to "E",
        "Ѕ" to "S", "§" to "S",
        "Ш" to "W", "ш" to "W"
    )

    /**
     * Main entry point
     * @param rawText  ML Kit se aaya hua raw OCR text
     * @return         "lat,lon" format string ya null agar match na mile
     */
    fun extractCoordinates(rawText: String): ParseResult? {
        val cleanText = preprocess(rawText)
        val match = COORD_REGEX.find(cleanText) ?: return null

        val latVal = match.groupValues[1].toDoubleOrNull() ?: return null
        val latDir = match.groupValues[2].uppercase()
        val lonVal = match.groupValues[3].toDoubleOrNull() ?: return null
        val lonDir = match.groupValues[4].uppercase()

        // Sanity check: valid range
        if (latVal > 90.0 || lonVal > 180.0) return null

        val lat = if (latDir == "S") -latVal else latVal
        val lon = if (lonDir == "W") -lonVal else lonVal

        val rawMatch = match.value.trim()
        val formatted = "${formatDecimal(lat)},${formatDecimal(lon)}"

        return ParseResult(
            rawMatch = rawMatch,
            formatted = formatted,
            latitude = lat,
            longitude = lon
        )
    }

    /**
     * OCR corrections + whitespace normalization
     */
    private fun preprocess(text: String): String {
        // Normalize whitespace
        var result = text.replace(Regex("\\s+"), " ").trim()
        // Apply common OCR fixes only in non-digit, non-dot, non-direction context
        // We carefully replace only isolated chars, not inside numbers
        result = fixDirectionChars(result)
        return result
    }

    /**
     * Direction character correction:
     * Only replaces direction letters (N/S/E/W) that appear right after a decimal number
     */
    private fun fixDirectionChars(text: String): String {
        // Replace Cyrillic/lookalike direction chars that appear after digits
        var result = text
        // After a digit or decimal, fix direction
        result = result.replace(Regex("(?<=\\d)(Ν|И|ñ)")) { "N" }
        result = result.replace(Regex("(?<=\\d)(Ε|е|Е)")) { "E" }
        result = result.replace(Regex("(?<=\\d)(Ѕ|§)")) { "S" }
        result = result.replace(Regex("(?<=\\d)(Ш|ш)")) { "W" }
        return result
    }

    /**
     * Format decimal: remove trailing zeros, max 6 decimal places
     */
    private fun formatDecimal(value: Double): String {
        return "%.6f".format(value).trimEnd('0').trimEnd('.')
    }
}

/**
 * Result data class
 */
data class ParseResult(
    val rawMatch: String,    // Original matched text: "22.1234N 71.1234E"
    val formatted: String,   // Final output: "22.1234,71.1234"
    val latitude: Double,
    val longitude: Double
)
