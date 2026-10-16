package eu.kanade.tachiyomi.source.builtin.model

import kotlinx.serialization.Serializable

@Serializable
enum class BuiltinEngineType {
    MADARA,
    MANGA_READER,
    MANGADEX,
    COMICK,
    MANGAPILL,
    WEBTOON,
    REIMANGA,
    COMIX,
}

@Serializable
data class BuiltinSourceRecord(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val lang: String = "en",
    val engineType: BuiltinEngineType,
    val isDefaultEnabled: Boolean = true,
)
