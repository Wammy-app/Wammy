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
