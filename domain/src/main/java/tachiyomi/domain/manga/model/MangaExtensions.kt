package tachiyomi.domain.manga.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val Manga.rating: Double?
    get() = (memo["rating"] as? JsonPrimitive)?.doubleOrNull
        ?: (memo["score"] as? JsonPrimitive)?.doubleOrNull

val Manga.views: Long?
    get() = (memo["views"] as? JsonPrimitive)?.longOrNull

val Manga.effectiveRating: Double?
    get() {
        rating?.let { return it }
        val desc = description ?: return null
        val match10 = Regex("""(?:Rating|Score|★)\s*:?\s*(\d+(?:\.\d+)?)\s*(?:/\s*10)?""", RegexOption.IGNORE_CASE).find(desc)
        if (match10 != null) {
            val num = match10.groupValues[1].toDoubleOrNull()
            if (num != null && num in 1.0..10.0) return num
        }
        val match5 = Regex("""(?:Rating|Score|★)\s*:?\s*(\d+(?:\.\d+)?)\s*/\s*5""", RegexOption.IGNORE_CASE).find(desc)
        if (match5 != null) {
            val num = match5.groupValues[1].toDoubleOrNull()
            if (num != null && num in 1.0..5.0) return num * 2.0
        }
        return null
    }

val Manga.effectiveViews: Long?
    get() {
        views?.let { return it }
        val desc = description ?: return null
        val match = Regex("""(?:Views|Reads|Follows)\s*:?\s*([\d,.]+\s*[kmb]?)""", RegexOption.IGNORE_CASE).find(desc)
        if (match != null) {
            return parseHumanCount(match.groupValues[1])
        }
        return null
    }

fun parseHumanCount(raw: String): Long? {
    val clean = raw.trim().replace(",", "")
    val multiplier = when {
        clean.endsWith("b", ignoreCase = true) -> 1_000_000_000.0
        clean.endsWith("m", ignoreCase = true) -> 1_000_000.0
        clean.endsWith("k", ignoreCase = true) -> 1_000.0
        else -> 1.0
    }
    val numStr = clean.trimEnd('k', 'K', 'm', 'M', 'b', 'B').trim()
    val num = numStr.toDoubleOrNull() ?: return null
    return (num * multiplier).toLong()
}
