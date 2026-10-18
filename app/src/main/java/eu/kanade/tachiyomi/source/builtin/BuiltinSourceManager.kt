package eu.kanade.tachiyomi.source.builtin

import android.content.Context
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.builtin.atsumaru.AtsumaruSource
import eu.kanade.tachiyomi.source.builtin.bbato.BbatoSource
import eu.kanade.tachiyomi.source.builtin.comick.ComickSource
import eu.kanade.tachiyomi.source.builtin.comix.ComixSource
import eu.kanade.tachiyomi.source.builtin.engine.MadaraEngineSource
import eu.kanade.tachiyomi.source.builtin.engine.MangaThemesiaEngineSource
import eu.kanade.tachiyomi.source.builtin.mangadex.MangaDexSource
import eu.kanade.tachiyomi.source.builtin.mangakakalot.MangakakalotSource
import eu.kanade.tachiyomi.source.builtin.manhuaplus.ManhuaPlusSource
import eu.kanade.tachiyomi.source.builtin.webtoon.WebtoonsSource
import eu.kanade.tachiyomi.source.builtin.weebcentral.WeebCentralSource
import eu.kanade.tachiyomi.source.builtin.model.BuiltinEngineType
import eu.kanade.tachiyomi.source.builtin.model.BuiltinSourceRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.math.abs

/**
 * Manages native built-in sources for Wammy.
 * Powers the 12 core manga sources out of the box with zero APK installation required.
 */
class BuiltinSourceManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val prefs by lazy {
        context.getSharedPreferences("builtin_sources_prefs", Context.MODE_PRIVATE)
    }

    private val customSourcesFile by lazy {
        File(context.filesDir, "custom_builtin_sources.json")
    }

    private val _builtinSources = MutableStateFlow<List<CatalogueSource>>(emptyList())
    val builtinSources: StateFlow<List<CatalogueSource>> = _builtinSources.asStateFlow()

    private val defaultPresets = listOf(
        BuiltinSourceRecord(
            id = 80010001L,
            name = "MangaDex",
            baseUrl = "https://mangadex.org",
            lang = "en",
            engineType = BuiltinEngineType.MANGADEX,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010002L,
            name = "Mangakakalot",
            baseUrl = "https://www.mangakakalove.com",
            lang = "en",
            engineType = BuiltinEngineType.MANGAKAKALOT,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010003L,
            name = "ManhuaFast",
            baseUrl = "https://www.mangaread.org",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010004L,
            name = "Novelmic",
            baseUrl = "https://www.zinmanga.net",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010005L,
            name = "WeebCentral",
            baseUrl = "https://weebcentral.com",
            lang = "en",
            engineType = BuiltinEngineType.WEEBCENTRAL,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010006L,
            name = "Yaksha Scans",
            baseUrl = "https://yakshacomics.com",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010007L,
            name = "Arena Scans",
            baseUrl = "https://arenascan.com",
            lang = "en",
            engineType = BuiltinEngineType.MANGATHEMESIA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010008L,
            name = "Aster Scans",
            baseUrl = "https://asterscans.com",
            lang = "en",
            engineType = BuiltinEngineType.MANGATHEMESIA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010009L,
            name = "Atsumaru",
            baseUrl = "https://atsu.moe",
            lang = "en",
            engineType = BuiltinEngineType.ATSUMARU,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010010L,
            name = "Bbato",
            baseUrl = "https://bato1.com",
            lang = "en",
            engineType = BuiltinEngineType.BBATO,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010011L,
            name = "Comic Asura",
            baseUrl = "https://comicasura.net",
            lang = "en",
            engineType = BuiltinEngineType.MANGATHEMESIA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010012L,
            name = "Comix",
            baseUrl = "https://comix.to",
            lang = "en",
            engineType = BuiltinEngineType.COMIX,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010013L,
            name = "ManhuaPlus",
            baseUrl = "https://manhuaplus.org",
            lang = "en",
            engineType = BuiltinEngineType.MANHUAPLUS,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010014L,
            name = "ComicK",
            baseUrl = "https://comick.art",
            lang = "en",
            engineType = BuiltinEngineType.COMICK,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010015L,
            name = "Webtoons",
            baseUrl = "https://www.webtoons.com",
            lang = "en",
            engineType = BuiltinEngineType.WEBTOONS,
            isDefaultEnabled = true,
        ),
    )

    init {
        scope.launch {
            loadSources()
        }
    }

    fun loadSources() {
        val disabledIds = prefs.getStringSet("disabled_builtin_ids", emptySet()) ?: emptySet()
        val customRecords = loadCustomRecords()

        val allRecords = defaultPresets + customRecords
        val activeSources = allRecords
            .filterNot { it.id.toString() in disabledIds }
            .mapNotNull(::instantiateSource)

        _builtinSources.value = activeSources
        logcat(LogPriority.INFO) { "Loaded ${activeSources.size} built-in autonomous sources" }
    }

    private fun instantiateSource(record: BuiltinSourceRecord): CatalogueSource? {
        return try {
            when (record.engineType) {
                BuiltinEngineType.MANGADEX -> MangaDexSource(id = record.id, lang = record.lang)
                BuiltinEngineType.MANGAKAKALOT -> MangakakalotSource(id = record.id, baseUrl = record.baseUrl, lang = record.lang)
                BuiltinEngineType.WEEBCENTRAL -> WeebCentralSource(id = record.id, lang = record.lang)
                BuiltinEngineType.ATSUMARU -> AtsumaruSource(id = record.id, lang = record.lang)
                BuiltinEngineType.BBATO -> BbatoSource(id = record.id, lang = record.lang)
                BuiltinEngineType.MADARA -> MadaraEngineSource(
                    id = record.id,
                    name = record.name,
                    baseUrl = record.baseUrl,
                    lang = record.lang,
                )
                BuiltinEngineType.MANGATHEMESIA -> MangaThemesiaEngineSource(
                    id = record.id,
                    name = record.name,
                    baseUrl = record.baseUrl,
                    lang = record.lang,
                )
                BuiltinEngineType.COMIX -> ComixSource(id = record.id, lang = record.lang)
                BuiltinEngineType.MANHUAPLUS -> ManhuaPlusSource(id = record.id, baseUrl = record.baseUrl, lang = record.lang)
                BuiltinEngineType.COMICK -> ComickSource(id = record.id, baseUrl = record.baseUrl, lang = record.lang)
                BuiltinEngineType.WEBTOONS -> WebtoonsSource(id = record.id, baseUrl = record.baseUrl, lang = record.lang)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to instantiate built-in source: ${record.name}" }
            null
        }
    }

    fun toggleSource(id: Long, enabled: Boolean) {
        val disabledIds = (prefs.getStringSet("disabled_builtin_ids", emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) {
            disabledIds.remove(id.toString())
        } else {
            disabledIds.add(id.toString())
        }
        prefs.edit().putStringSet("disabled_builtin_ids", disabledIds).apply()
        loadSources()
    }

    fun addCustomSource(
        name: String,
        baseUrl: String,
        engine: BuiltinEngineType,
        lang: String = "en",
    ): Result<CatalogueSource> {
        return try {
            val cleanUrl = baseUrl.trim().removeSuffix("/")
            val generatedId = 80020000L + abs(cleanUrl.hashCode().toLong() % 100000L)
            val record = BuiltinSourceRecord(
                id = generatedId,
                name = name.trim(),
                baseUrl = cleanUrl,
                lang = lang.trim(),
                engineType = engine,
                isDefaultEnabled = true,
            )

            val existing = loadCustomRecords().toMutableList()
            existing.removeAll { it.id == record.id || it.baseUrl == record.baseUrl }
            existing.add(record)
            saveCustomRecords(existing)

            loadSources()
            val created = instantiateSource(record)
                ?: return Result.failure(Exception("Failed to instantiate engine"))
            Result.success(created)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun removeCustomSource(id: Long) {
        val existing = loadCustomRecords().toMutableList()
        existing.removeAll { it.id == id }
        saveCustomRecords(existing)
        loadSources()
    }

    private fun loadCustomRecords(): List<BuiltinSourceRecord> {
        if (!customSourcesFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<BuiltinSourceRecord>>(customSourcesFile.readText())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read custom builtin sources" }
            emptyList()
        }
    }

    private fun saveCustomRecords(records: List<BuiltinSourceRecord>) {
        try {
            customSourcesFile.writeText(json.encodeToString(records))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save custom builtin sources" }
        }
    }

    companion object {
        fun isBuiltin(sourceId: Long): Boolean {
            return (sourceId in 80010001L..80010015L) || sourceId >= 80020000L
        }

        fun getIconRes(sourceId: Long, sourceName: String? = null): Int? {
            return when (sourceId) {
                80010001L -> R.drawable.source_mangadex
                80010002L -> R.drawable.source_mangakakalot
                80010003L -> R.drawable.source_manhuafast
                80010004L -> R.drawable.source_novelmic
                80010005L -> R.drawable.source_weebcentral
                80010006L -> R.drawable.source_yakshascans
                80010007L -> R.drawable.source_arenascans
                80010008L -> R.drawable.source_asterscans
                80010009L -> R.drawable.source_atsumaru
                80010010L -> R.drawable.source_bbato
                80010011L -> R.drawable.source_comicasura
                80010012L -> R.drawable.source_comix
                80010013L -> R.drawable.source_manhuaplus
                80010014L -> R.drawable.source_comick
                80010015L -> R.drawable.source_webtoons
                else -> when (sourceName?.lowercase()?.trim()) {
                    "mangadex" -> R.drawable.source_mangadex
                    "mangakakalot" -> R.drawable.source_mangakakalot
                    "manhuafast" -> R.drawable.source_manhuafast
                    "novelmic" -> R.drawable.source_novelmic
                    "weebcentral" -> R.drawable.source_weebcentral
                    "yaksha scans", "yaksha comics", "yakshascans" -> R.drawable.source_yakshascans
                    "arena scans", "arenascans" -> R.drawable.source_arenascans
                    "aster scans", "asterscans" -> R.drawable.source_asterscans
                    "atsumaru" -> R.drawable.source_atsumaru
                    "bbato", "bato" -> R.drawable.source_bbato
                    "comic asura", "asura comic", "asura scans", "comicasura" -> R.drawable.source_comicasura
                    "comix" -> R.drawable.source_comix
                    "manhuaplus", "manhua plus" -> R.drawable.source_manhuaplus
                    "comick", "comic k" -> R.drawable.source_comick
                    "webtoons", "webtoon", "line webtoon" -> R.drawable.source_webtoons
                    else -> null
                }
            }
        }
    }
}
