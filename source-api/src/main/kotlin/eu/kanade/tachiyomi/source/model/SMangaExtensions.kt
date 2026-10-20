package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

var SManga.rating: Double?
    get() = (memo["rating"] as? JsonPrimitive)?.doubleOrNull
        ?: (memo["score"] as? JsonPrimitive)?.doubleOrNull
    set(value) {
        val map = memo.toMutableMap()
        if (value != null) {
            map["rating"] = JsonPrimitive(value)
        } else {
            map.remove("rating")
        }
        memo = JsonObject(map)
    }

var SManga.views: Long?
    get() = (memo["views"] as? JsonPrimitive)?.longOrNull
    set(value) {
        val map = memo.toMutableMap()
        if (value != null) {
            map["views"] = JsonPrimitive(value)
        } else {
            map.remove("views")
        }
        memo = JsonObject(map)
    }

var SManga.viewsString: String?
    get() = views?.toString()
    set(value) {
        views = if (value.isNullOrBlank()) null else parseHumanCount(value)
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
