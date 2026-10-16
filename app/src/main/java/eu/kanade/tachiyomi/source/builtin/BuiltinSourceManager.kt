package eu.kanade.tachiyomi.source.builtin

import android.content.Context
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.builtin.comick.ComickSource
import eu.kanade.tachiyomi.source.builtin.comix.ComixSource
import eu.kanade.tachiyomi.source.builtin.engine.MadaraEngineSource
import eu.kanade.tachiyomi.source.builtin.engine.MangaReaderEngineSource
import eu.kanade.tachiyomi.source.builtin.mangadex.MangaDexSource
import eu.kanade.tachiyomi.source.builtin.mangapill.MangaPillSource
import eu.kanade.tachiyomi.source.builtin.reimanga.ReiMangaSource
import eu.kanade.tachiyomi.source.builtin.webtoon.WebtoonsSource
import eu.kanade.tachiyomi.source.builtin.model.BuiltinEngineType
import eu.kanade.tachiyomi.source.builtin.model.BuiltinSourceRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.math.abs

/**
 * Manages native built-in sources and generic engines (Madara, MangaReader, MangaDex, ComicK, MangaPill).
 * Allows Wammy to work completely autonomously on first launch without requiring external APK extensions.
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
            name = "ComicK",
            baseUrl = "https://comick.dev",
            lang = "en",
            engineType = BuiltinEngineType.COMICK,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010003L,
            name = "MangaPill",
            baseUrl = "https://mangapill.com",
            lang = "en",
            engineType = BuiltinEngineType.MANGAPILL,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010004L,
            name = "Asura Comic",
            baseUrl = "https://asuracomic.net",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010005L,
            name = "Toonily",
            baseUrl = "https://toonily.com",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010006L,
            name = "Flame Comics",
            baseUrl = "https://flamecomics.xyz",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010007L,
            name = "ManhuaFast",
            baseUrl = "https://manhuafast.com",
            lang = "en",
            engineType = BuiltinEngineType.MADARA,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010008L,
            name = "RavenScans",
            baseUrl = "https://ravenscans.org",
            lang = "en",
            engineType = BuiltinEngineType.MANGA_READER,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010010L,
            name = "Webtoons",
            baseUrl = "https://www.webtoons.com",
            lang = "en",
            engineType = BuiltinEngineType.WEBTOON,
            isDefaultEnabled = true,
        ),
        BuiltinSourceRecord(
            id = 80010011L,
            name = "ReiManga",
            baseUrl = "https://reimanga.net",
            lang = "en",
            engineType = BuiltinEngineType.REIMANGA,
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
                BuiltinEngineType.COMICK -> ComickSource(id = record.id, lang = record.lang)
                BuiltinEngineType.MANGAPILL -> MangaPillSource(id = record.id, lang = record.lang)
                BuiltinEngineType.MADARA -> MadaraEngineSource(
                    id = record.id,
                    name = record.name,
                    baseUrl = record.baseUrl,
                    lang = record.lang,
                )
                BuiltinEngineType.MANGA_READER -> MangaReaderEngineSource(
                    id = record.id,
                    name = record.name,
                    baseUrl = record.baseUrl,
                    lang = record.lang,
                )
                BuiltinEngineType.WEBTOON -> WebtoonsSource(id = record.id, lang = record.lang)
                BuiltinEngineType.REIMANGA -> ReiMangaSource(id = record.id, lang = record.lang)
                BuiltinEngineType.COMIX -> ComixSource(id = record.id, lang = record.lang)
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
}
