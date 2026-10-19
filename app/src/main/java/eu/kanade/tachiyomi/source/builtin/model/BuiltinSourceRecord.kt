package eu.kanade.tachiyomi.source.builtin.model

import kotlinx.serialization.Serializable

@Serializable
enum class BuiltinEngineType {
    MADARA,
    MANGADEX,
    MANGAKAKALOT,
    WEEBCENTRAL,
    MANGATHEMESIA,
    ATSUMARU,
    BBATO,
    MANHUAPLUS,
    COMICK,
    WEBTOONS,
    FREEWEBNOVEL,
    NOVELFIRE,
    NOVELBUDDY,
    WUXIABOX,
    WUXIACLICK,
    NOVELCOOL,
    BAKATSUKI,
    MVLEMPYR,
    NOVELBIN,
    WUXIAWORLD,
    NOVELHALL,
    LIGHTNOVELWORLD,
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
