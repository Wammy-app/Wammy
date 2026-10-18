package eu.kanade.tachiyomi.source.builtin.comix

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.challenge.BossChallengeCoordinator
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * Built-in native Comix source (comix.to).
 * Supports authenticated signed API requests with automated WebView cipher extraction
 * and 5x5 tile descrambler for chapter reading pages.
 */
@Suppress("DEPRECATION")
class ComixSource(
    override val id: Long = 80010012L,
    override val lang: String = "en",
) : HttpSource() {

    override val name: String = "Comix"
    override val baseUrl: String = "https://comix.to"
    override val supportsLatest: Boolean = true

    private val context: Context get() = Injekt.get<Application>()
    private val preferences by lazy {
        context.getSharedPreferences("source_80010012", Context.MODE_PRIVATE)
    }
    private val webViewHelper by lazy {
        ComixWebViewHelper(context) { material ->
            logcat(LogPriority.INFO) { "ComixSource: Received cipher material via bridge" }
            cipher = ComixCipher(material)
        }
    }

    private var cipher: ComixCipher?
        get() {
            if (cachedCipher != null) return cachedCipher
            val savedJson = preferences.getString("cipher_material_v1", null)
            if (!savedJson.isNullOrBlank()) {
                val material = runCatching { comixJson.decodeFromString<CipherMaterial>(savedJson) }.getOrNull()
                if (material != null && material.isValid()) {
                    logcat(LogPriority.INFO) { "ComixSource: Restored cipher material from preferences" }
                    cachedCipher = ComixCipher(material)
                    return cachedCipher
                }
            }
            return null
        }
        set(value) {
            cachedCipher = value
            if (value != null) {
                runCatching {
                    preferences.edit().putString("cipher_material_v1", comixJson.encodeToString(value.material)).apply()
                    logcat(LogPriority.INFO) { "ComixSource: Persisted cipher material to preferences" }
                }
            } else {
                preferences.edit().remove("cipher_material_v1").apply()
            }
        }

    private suspend fun ensureClearance(targetUrl: String = baseUrl, force: Boolean = false) {
        val coordinator = BossChallengeCoordinator.instance ?: return
        val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }

        if (!force && coordinator.hasClearance(targetUrl)) {
            return
        }

        coordinator.resolveAsync(targetUrl, headersMap, force = force)
    }

    private suspend fun fetchDocumentWithClearance(url: String): Document {
        ensureClearance(url, force = false)
        val request = GET(url, headers)
        val response = runCatching { client.newCall(request).execute() }.getOrNull()
        if (response != null && response.isSuccessful) {
            val doc = response.use { Jsoup.parse(it.body.string(), url) }
            val title = doc.title()
            if (!title.contains("Just a moment", ignoreCase = true) &&
                !title.contains("Attention Required", ignoreCase = true) &&
                !title.contains("Third-party app", ignoreCase = true) &&
                !title.contains("Cloudflare", ignoreCase = true)
            ) {
                return doc
            }
        }
        throw Exception("OkHttp blocked by Cloudflare for $url, falling back to WebView")
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(Descrambler.interceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val isImage = url.contains(SCRAMBLE_PATH_FALLBACK_REGEX) || request.url.host.contains("static.comix")
                if (!isImage) {
                    return@addInterceptor chain.proceed(request)
                }

                var response = proceedWithRetry(chain, request)
                if (response.isSuccessful) return@addInterceptor response

                val fallbacks = listOf("/i5/", "/si/", "/i/", "/sii/", "/ii/")
                    .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                    .filter { it != url }

                if (fallbacks.isEmpty()) return@addInterceptor response

                for (fallbackUrl in fallbacks) {
                    response.close()
                    response = proceedWithRetry(chain, request.newBuilder().url(fallbackUrl).build())
                    if (response.isSuccessful) break
                }
                response
            }
            .build()
    }

    private fun proceedWithRetry(chain: Interceptor.Chain, request: Request): Response {
        var response = chain.proceed(request)
        if (response.isSuccessful) return response

        for (attempt in 1..10) {
            if (response.code !in SERVER_ERROR_CODES && response.code != 404) break

            response.close()
            runCatching { Thread.sleep(1500) }

            val urlBuilder = request.url.newBuilder()
                .setQueryParameter("r", attempt.toString())

            if (!request.url.queryParameterNames.contains("8")) {
                urlBuilder.addQueryParameter("8", null)
            }

            val retryUrl = urlBuilder.build()
            response = chain.proceed(request.newBuilder().url(retryUrl).build())
            if (response.isSuccessful) break
        }

        return response
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", network.defaultUserAgentProvider())
        .set("Accept", "*/*")
        .set("Referer", "$baseUrl/")

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        val urlWithoutFragment = imageUrl.substringBefore('#')
        val requestHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .removeAll("Origin")
            .removeAll("Referer")
            .build()
        return GET(urlWithoutFragment, requestHeaders)
    }

    // =========================== Popular ===========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[score]", "desc")
            addQueryParameter("page", page.toString())
        }.build()
        return getMangaListFromBrowse(url)
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { getPopularManga(page) }
    }

    // =========================== Latest ===========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[chapter_updated_at]", "desc")
            addQueryParameter("page", page.toString())
        }.build()
        return getMangaListFromBrowse(url)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { getLatestUpdates(page) }
    }

    // =========================== Search ===========================

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            if (query.isNotBlank()) {
                addQueryParameter("keyword", query.trim())
            }
            addQueryParameter("page", page.toString())
        }.build()
        return getMangaListFromBrowse(url)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { getSearchManga(page, query, filters) }
    }

    private suspend fun getMangaListFromBrowse(url: HttpUrl): MangasPage {
        if (cipher != null) {
            getSigned<SearchResponse>("/api/v1/manga", nativeMangaParams(url))?.let { response ->
                val mangaList = response.result.items.map { it.toBasicSManga() }
                if (mangaList.isNotEmpty()) {
                    return MangasPage(mangaList, response.result.hasNextPage())
                }
            }
        }

        val document = try {
            fetchDocumentWithClearance(url.toString())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ComixSource: fetchDocumentWithClearance failed: ${e.message}" }
            null
        }

        val searchResponse = document?.extractBrowseResponse()
        if (searchResponse != null && searchResponse.result.items.isNotEmpty()) {
            return MangasPage(
                searchResponse.result.items.map { it.toBasicSManga() },
                searchResponse.result.hasNextPage(),
            )
        }

        val expectedKeyword = JSONObject.quote(
            url.queryParameter("q") ?: url.queryParameter("keyword").orEmpty(),
        )

        val capture = webViewHelper.runWebViewCapture(
            url = url.toString(),
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
            initialHtml = null,
            timeout = 30.seconds,
            initializationScript = """
                (function () {
                    const key = 'settings_v2';
                    let settings = {};
                    try {
                        settings = JSON.parse(localStorage.getItem(key) || '{}');
                    } catch (e) {}
                    settings.state = {
                        ...(settings.state || {}),
                        contentFilter: 'pornographic'
                    };
                    if (settings.version === undefined) settings.version = 0;
                    localStorage.setItem(key, JSON.stringify(settings));
                })();
            """.trimIndent(),
            pollScript = { passPayloadName ->
                """
                    (function () {
                        const payloadKey = '__comixBrowsePayload';
                        const expectedKeyword = $expectedKeyword;
                        const capture = (parsed, allowEmpty = false) => {
                            try {
                                if (parsed && Array.isArray(parsed.items)) {
                                    parsed = { result: parsed };
                                }
                                if (
                                    parsed &&
                                    parsed.result &&
                                    Array.isArray(parsed.result.items) &&
                                    (allowEmpty || parsed.result.items.length > 0)
                                ) {
                                    window[payloadKey] = JSON.stringify(parsed);
                                    window.$passPayloadName(window[payloadKey]);
                                    return true;
                                }
                            } catch (e) {}
                            return false;
                        };

                        if (window[payloadKey]) return window[payloadKey];

                        try {
                            const raw = document.querySelector('script#initial-data')?.textContent;
                            const queries = raw && JSON.parse(raw).queries;
                            if (queries) Object.values(queries).some(capture);
                        } catch (e) {}

                        if (window[payloadKey]) return window[payloadKey];
                        if (window.__comixBrowseCaptureInstalled) return null;
                        window.__comixBrowseCaptureInstalled = true;

                        const captureText = text => {
                            try {
                                if (text) capture(JSON.parse(text), true);
                            } catch (e) {}
                        };

                        const shouldCaptureUrl = rawUrl => {
                            try {
                                const url = new URL(rawUrl || '', window.location.origin);
                                if (!url.pathname.includes('/api/v1/manga')) return false;
                                if (!expectedKeyword) return true;
                                return url.searchParams.get('keyword') === expectedKeyword;
                            } catch (e) {
                                return false;
                            }
                        };

                        const originalFetch = window.fetch;
                        if (typeof originalFetch === 'function') {
                            window.fetch = function () {
                                return originalFetch.apply(this, arguments).then(response => {
                                    try {
                                        const url = response && response.url || '';
                                        if (shouldCaptureUrl(url)) {
                                            response.clone().text().then(captureText).catch(() => {});
                                        }
                                    } catch (e) {}
                                    return response;
                                });
                            };
                        }

                        const originalOpen = XMLHttpRequest.prototype.open;
                        const originalSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function (method, url) {
                            this.__comixBrowseUrl = String(url || '');
                            return originalOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send = function () {
                            this.addEventListener('load', function () {
                                try {
                                    if (shouldCaptureUrl(this.__comixBrowseUrl)) {
                                        captureText(this.responseText);
                                    }
                                } catch (e) {}
                            });
                            return originalSend.apply(this, arguments);
                        };

                        const originalParse = JSON.parse;
                        const proxiedParse = new Proxy(originalParse, {
                            apply(target, thisArg, args) {
                                const parsed = Reflect.apply(target, thisArg, args);
                                if (!expectedKeyword) capture(parsed);
                                return parsed;
                            }
                        });
                        JSON.parse = proxiedParse;
                        return window[payloadKey] || null;
                    })();
                """.trimIndent()
            },
            pageScript = null,
        )

        val browseResponse = runCatching { capture.payload.parseAs<SearchResponse>() }.getOrNull()
            ?: runCatching {
                val json = comixJson.parseToJsonElement(capture.payload).jsonObject
                extractBrowseFromJson(json)
            }.getOrNull()
            ?: runCatching {
                val doc = Jsoup.parse(capture.payload, url.toString())
                extractBrowseFromJson(doc.extractInitialData())
            }.getOrNull()
            ?: throw Exception("Could not parse manga list from browse")

        return MangasPage(
            browseResponse.result.items.map { it.toBasicSManga() },
            browseResponse.result.hasNextPage(),
        )
    }

    private fun nativeMangaParams(url: HttpUrl): Map<String, List<String>> = buildMap {
        url.queryParameterNames.forEach { name ->
            val values = url.queryParameterValues(name).filterNotNull()
            if (values.isNotEmpty()) {
                put(
                    name,
                    if (name == "content_rating") values.flatMap { it.split(',') } else values,
                )
            }
        }
        putIfAbsent("limit", listOf("28"))
    }

    private fun Document.extractBrowseResponse(): SearchResponse? {
        val root = runCatching { extractInitialData() }.getOrNull() ?: return null
        return extractBrowseFromJson(root)
    }

    private fun extractBrowseFromJson(root: JsonObject): SearchResponse? {
        logcat(LogPriority.INFO) { "ComixSource: extractBrowseFromJson root keys: ${root.keys.joinToString()}" }

        val candidates = mutableListOf<JsonElement>()
        root["list"]?.let { candidates.add(it) }
        root["page"]?.let { candidates.add(it) }

        val queries = root["queries"]?.jsonObject
            ?: root["state"]?.jsonObject?.get("queries")?.jsonObject
        if (queries != null) {
            logcat(LogPriority.INFO) { "ComixSource: queries keys: ${queries.keys.joinToString()}" }
            candidates.addAll(queries.values)
        }

        for ((idx, rawElement) in candidates.withIndex()) {
            var el: JsonElement = rawElement
            val obj = el as? JsonObject
            if (obj != null && "state" in obj) {
                el = obj["state"]?.jsonObject?.get("data") ?: el
            }
            val dataObj = el as? JsonObject
            if (dataObj != null && "data" in dataObj) {
                el = dataObj["data"] ?: el
            }

            logcat(LogPriority.INFO) { "ComixSource: candidate [$idx]: ${el.toString().take(250)}" }

            // 1. Try SearchResponse
            val direct = runCatching { comixJson.decodeFromJsonElement<SearchResponse>(el) }
                .onFailure { logcat(LogPriority.INFO, it) { "ComixSource: SearchResponse decode error: ${it.message}" } }
                .getOrNull()
            if (direct != null && direct.result.items.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Extracted SearchResponse with ${direct.result.items.size} items" }
                return direct
            }

            // 2. Try Items wrapped in SearchResponse
            val items = runCatching { comixJson.decodeFromJsonElement<SearchResponse.Items>(el) }
                .onFailure { logcat(LogPriority.INFO, it) { "ComixSource: SearchResponse.Items decode error: ${it.message}" } }
                .getOrNull()
            if (items != null && items.items.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Extracted Items with ${items.items.size} items" }
                return SearchResponse(items)
            }

            // 3. Try List<Manga>
            val list = runCatching { comixJson.decodeFromJsonElement<List<Manga>>(el) }
                .onFailure { logcat(LogPriority.INFO, it) { "ComixSource: List<Manga> decode error: ${it.message}" } }
                .getOrNull()
            if (list != null && list.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Extracted List<Manga> with ${list.size} items" }
                return SearchResponse(SearchResponse.Items(items = list))
            }
        }
        logcat(LogPriority.WARN) { "ComixSource: No candidate could be parsed as Manga list" }
        return null
    }

    private fun Document.extractInitialData(): JsonObject {
        val script = selectFirst("script#initial-data")
            ?: throw Exception("Could not find script#initial-data in page")
        val content = script.data().ifEmpty { script.html() }.ifEmpty { script.text() }
        val json = comixJson.parseToJsonElement(content).jsonObject
        logcat(LogPriority.INFO) { "ComixSource: initial-data root keys=${json.keys.joinToString()}" }
        return json
    }

    private fun Document.extractInitialQueries(): JsonObject {
        val json = extractInitialData()
        val queries = json["queries"]?.jsonObject
            ?: json["state"]?.jsonObject?.get("queries")?.jsonObject
        return queries ?: JsonObject(emptyMap())
    }

    // =========================== Details ===========================

    override fun getMangaUrl(manga: SManga): String {
        val cleanUrl = manga.url.removePrefix("/title")
        return "$baseUrl/title$cleanUrl"
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val mangaUrl = getMangaUrl(manga)
        val document = try {
            fetchDocumentWithClearance(mangaUrl)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ComixSource: fetchDocumentWithClearance failed in getMangaDetails: ${e.message}" }
            null
        }

        val detail = runCatching {
            val root = document?.extractInitialData() ?: return@runCatching null
            extractMangaDetailFromJson(root)
        }.getOrNull()

        if (detail != null) {
            logcat(LogPriority.INFO) { "ComixSource: Loaded manga details directly from initial queries for ${manga.url}" }
            return detail.toSManga().apply {
                this.url = manga.url
            }
        }

        val capture = webViewHelper.runWebViewCapture(
            url = mangaUrl,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
            initialHtml = null,
            timeout = 30.seconds,
            pageScript = null,
            pollScript = null,
        )

        val json = try {
            comixJson.parseToJsonElement(capture.payload).jsonObject
        } catch (_: Exception) {
            val doc = Jsoup.parse(capture.payload, mangaUrl)
            doc.extractInitialData()
        }

        val parsedManga = extractMangaDetailFromJson(json)
            ?: throw Exception("Could not extract manga detail for ${manga.url}")

        return parsedManga.toSManga().apply { this.url = manga.url }
    }

    private fun extractMangaDetailFromJson(root: JsonObject): Manga? {
        val queries = root["queries"]?.jsonObject
            ?: root["state"]?.jsonObject?.get("queries")?.jsonObject
        if (queries != null) {
            val entry = queries.entries.firstOrNull { it.key.contains("\"detail\"") }
            if (entry != null) {
                var el: JsonElement = entry.value
                val obj = el as? JsonObject
                if (obj != null && "state" in obj) {
                    el = obj["state"]?.jsonObject?.get("data") ?: el
                }
                val dataObj = el as? JsonObject
                if (dataObj != null && "data" in dataObj) {
                    el = dataObj["data"] ?: el
                }
                val parsed = runCatching { el.parseAs<Manga>() }.getOrNull()
                if (parsed != null && parsed.title.isNotEmpty()) {
                    logcat(LogPriority.INFO) { "ComixSource: Extracted full manga detail from queries: ${parsed.title}" }
                    return parsed
                }
            }
        }

        for (key in listOf("manga", "detail", "comic", "page", "data")) {
            val candidate = root[key] ?: continue
            var el: JsonElement = candidate
            val obj = el as? JsonObject
            if (obj != null && "data" in obj) {
                el = obj["data"] ?: el
            }
            val parsed = runCatching { el.parseAs<Manga>() }.getOrNull()
            if (parsed != null && parsed.title.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Extracted manga directly from root '$key' object: ${parsed.title}" }
                return parsed
            }
        }
        return null
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        runBlocking { getMangaDetails(manga) }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        var updatedManga = manga
        var updatedChapters = chapters

        if (fetchDetails) {
            try {
                updatedManga = getMangaDetails(manga)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "ComixSource: getMangaDetails failed in getMangaUpdate: ${e.message}" }
            }
        }

        if (fetchChapters) {
            try {
                updatedChapters = getChapterList(manga)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "ComixSource: getChapterList failed in getMangaUpdate: ${e.message}" }
                if (updatedChapters.isEmpty()) throw e
            }
        }

        SMangaUpdate(updatedManga, updatedChapters)
    }

    // =========================== Chapters ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val mangaSlug = manga.url.removePrefix("/title/").removePrefix("/")
        val mangaId = manga.mangaId()
            ?: mangaSlug.substringBefore('-').takeIf { it.isNotEmpty() }
            ?: throw Exception("Could not find manga ID for $mangaSlug")

        // 1. Try native signed requests if cipher is already available
        if (cipher != null) {
            val nativeChapters = getNativeChapterList(mangaSlug, mangaId)
            if (!nativeChapters.isNullOrEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Loaded ${nativeChapters.size} chapters via signed API for $mangaId" }
                return selectChapters(nativeChapters)
            }
        }

        // 2. Fetch page HTML with clearance
        val mangaUrl = getMangaUrl(manga)
        val document = try {
            fetchDocumentWithClearance(mangaUrl)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ComixSource: fetchDocumentWithClearance failed: ${e.message}" }
            null
        }

        // Fast-path: Check if chapters exist in initial-data queries or root keys
        val fastChapters = runCatching {
            val root = document?.extractInitialData() ?: return@runCatching null
            val candidates = mutableListOf<Pair<String, JsonElement>>()
            root["chapters"]?.let { candidates.add("root.chapters" to it) }
            root["chapterList"]?.let { candidates.add("root.chapterList" to it) }
            root["list"]?.let { candidates.add("root.list" to it) }

            val queries = root["queries"]?.jsonObject
                ?: root["state"]?.jsonObject?.get("queries")?.jsonObject
            if (queries != null) {
                queries.entries.forEach { (k, v) -> candidates.add(k to v) }
            }

            for ((key, raw) in candidates) {
                if (key.contains("chapter", ignoreCase = true) || key.startsWith("root.")) {
                    var el: JsonElement = raw
                    val obj = el as? JsonObject
                    if (obj != null && "state" in obj) el = obj["state"]?.jsonObject?.get("data") ?: el
                    val dataObj = el as? JsonObject
                    if (dataObj != null && "data" in dataObj) el = dataObj["data"] ?: el

                    val list = runCatching {
                        comixJson.decodeFromJsonElement<ChapterDetailsResponse>(el).result.items
                    }.getOrNull() ?: runCatching {
                        comixJson.decodeFromJsonElement<List<Chapter>>(el)
                    }.getOrNull()

                    if (!list.isNullOrEmpty()) {
                        logcat(LogPriority.INFO) { "ComixSource: Found ${list.size} chapters in initial data ($key) for $mangaId" }
                        return@runCatching list.map { it.toSChapter(mangaSlug) }
                    }
                }
            }
            null
        }.getOrNull()

        if (!fastChapters.isNullOrEmpty()) {
            return selectChapters(fastChapters)
        }

        val siteApiJs = ComixScripts.getSiteApi(context)
        val chapterListJs = ComixScripts.getChapterList(context)
        val chapterPath = "/title/$mangaId-"

        logcat(LogPriority.INFO) { "ComixSource: Running WebView chapter extractor with Keiyoushi touch engine for mangaId=$mangaId" }

        val capture = webViewHelper.runWebViewCapture(
            url = mangaUrl,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
            initialHtml = null,
            timeout = 120.seconds,
            pageScript = { passPayloadName, _, touchName ->
                "($chapterListJs)($siteApiJs, [], ${JSONObject.quote(mangaId)}, ${JSONObject.quote(chapterPath)}, $MAX_CHAPTER_PAGES, window.$touchName, window.$passPayloadName);"
            },
        )

        capture.material?.takeIf { it.isValid() }?.let {
            logcat(LogPriority.INFO) { "ComixSource: Captured cipher during chapter extraction!" }
            cipher = ComixCipher(it)
        }

        val chapterList = capture.payload.parseAs<ChapterList>()
        if (chapterList.chapters.isNotEmpty()) {
            logcat(LogPriority.INFO) { "ComixSource: Successfully extracted ${chapterList.chapters.size} chapters for $mangaId (complete=${chapterList.complete})" }
            return selectChapters(chapterList.chapters.map { it.toSChapter(mangaSlug) })
        }

        if (!chapterList.complete && chapterList.error != null) {
            logcat(LogPriority.WARN) { "ComixSource: Chapter list error: ${chapterList.error}" }
            throw Exception(chapterList.error)
        }

        if (cipher != null) {
            val nativeChapters = getNativeChapterList(mangaSlug, mangaId)
            if (!nativeChapters.isNullOrEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Loaded ${nativeChapters.size} chapters via newly captured signed API for $mangaId" }
                return selectChapters(nativeChapters)
            }
        }

        return emptyList()
    }

    private suspend fun getNativeChapterList(mangaSlug: String, mangaId: String): List<SChapter>? {
        if (cipher == null) return null
        val chapters = mutableListOf<Chapter>()
        var page = 1
        while (page <= MAX_CHAPTER_PAGES) {
            val response = getSigned<ChapterDetailsResponse>(
                "/api/v1/manga/$mangaId/chapters",
                mapOf(
                    "limit" to listOf("100"),
                    "order[number]" to listOf("desc"),
                    "page" to listOf(page.toString()),
                ),
            ) ?: return null
            chapters += response.result.items
            if (!response.result.hasNextPage() || response.result.items.isEmpty()) break
            page++
        }
        return chapters.map { it.toSChapter(mangaSlug) }
    }

    private fun Document.extractMangaId(): String? {
        val queries = runCatching { extractInitialQueries() }.getOrNull() ?: return null
        val detail = queries.entries.firstOrNull { it.key.contains("\"detail\"") }?.value?.jsonObject
        return detail?.get("hid")?.jsonPrimitive?.content
            ?: queries.keys.firstNotNullOfOrNull { key ->
                Regex("""\["manga","detail","([^"]+)"\]""").find(key)?.groupValues?.get(1)
            }
    }

    private fun selectChapters(
        allChapters: List<SChapter>,
        shouldDeduplicate: Boolean = true,
        scanlatorBlacklist: Set<String> = emptySet(),
    ): List<SChapter> {
        val uniqueChapters = allChapters.distinctBy { it.url }
        val filteredChapters = if (scanlatorBlacklist.isEmpty()) {
            uniqueChapters
        } else {
            uniqueChapters.filter { chapter ->
                chapter.scanlator.orEmpty().trim().lowercase() !in scanlatorBlacklist &&
                    chapter.groupId()?.toString() !in scanlatorBlacklist
            }
        }

        val finalChapters = if (shouldDeduplicate) {
            val chapterMap = LinkedHashMap<Float, SChapter>()
            deduplicateChapters(chapterMap, filteredChapters)
            chapterMap.values.toList()
        } else {
            filteredChapters
        }

        return finalChapters.sortedByDescending { it.chapter_number }
    }

    private fun deduplicateChapters(
        chapterMap: LinkedHashMap<Float, SChapter>,
        items: List<SChapter>,
    ) {
        for (ch in items) {
            val key = ch.chapter_number
            val current = chapterMap[key]
            if (current == null) {
                chapterMap[key] = ch
            } else {
                val newIsOfficial = ch.isOfficial()
                val currentIsOfficial = current.isOfficial()
                val newIsOfficialGroup = ch.groupId() == OFFICIAL_GROUP_ID
                val currentIsOfficialGroup = current.groupId() == OFFICIAL_GROUP_ID

                val better = when {
                    newIsOfficial && !currentIsOfficial -> true
                    !newIsOfficial && currentIsOfficial -> false
                    newIsOfficialGroup && !currentIsOfficialGroup -> true
                    !newIsOfficialGroup && currentIsOfficialGroup -> false
                    else -> when {
                        ch.votes() > current.votes() -> true
                        ch.votes() < current.votes() -> false
                        else -> (ch.chapterId() ?: 0) > (current.chapterId() ?: 0)
                    }
                }
                if (better) chapterMap[key] = ch
            }
        }
    }

    private fun SChapter.votes(): Int = memo[CHAPTER_VOTES_MEMO]?.int ?: 0
    private fun SChapter.isOfficial(): Boolean = memo[CHAPTER_OFFICIAL_MEMO]?.booleanOrNull == true
    private fun SChapter.groupId(): Int? = memo[CHAPTER_GROUP_ID_MEMO]?.int

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        runBlocking { getChapterList(manga) }
    }

    // =========================== Pages ===========================

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) {
        chapter.url
    } else {
        "$baseUrl/${chapter.url.trimStart('/')}"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.chapterId() ?: throw Exception("Could not find chapter ID")

        // 1. Fast path: If cipher is already available, load pages directly via signed API
        if (cipher != null) {
            val response = getSigned<ChapterResponse>("/api/v1/chapters/$chapterId", emptyMap())
            if (response != null && response.result.pages.items.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Loaded ${response.result.pages.items.size} pages via signed API for chapter $chapterId" }
                return buildPages(response)
            }
        }

        val chapterUrl = getChapterUrl(chapter)
        val document = try {
            fetchDocumentWithClearance(chapterUrl)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ComixSource: fetchDocumentWithClearance failed in getPageList: ${e.message}" }
            null
        }

        // 2. Fast path: Check document initial-data queries or root keys
        val pageFromInitialData = runCatching {
            val root = document?.extractInitialData() ?: return@runCatching null
            val candidates = mutableListOf<JsonElement>()
            root["chapter"]?.let { candidates.add(it) }
            root["pages"]?.let { candidates.add(it) }
            root["page"]?.let { candidates.add(it) }

            val queries = root["queries"]?.jsonObject
                ?: root["state"]?.jsonObject?.get("queries")?.jsonObject
            if (queries != null) candidates.addAll(queries.values)

            candidates.firstNotNullOfOrNull { value ->
                runCatching {
                    var el: JsonElement = value
                    val obj = el as? JsonObject
                    if (obj != null && "state" in obj) {
                        el = obj["state"]?.jsonObject?.get("data") ?: el
                    }
                    val dataObj = el as? JsonObject
                    if (dataObj != null && "data" in dataObj) {
                        el = dataObj["data"] ?: el
                    }
                    val resp = el.parseAs<ChapterResponse>()
                    if (resp.result.pages.items.isNotEmpty()) resp else null
                }.getOrNull()
            }
        }.getOrNull()

        if (pageFromInitialData != null) {
            logcat(LogPriority.INFO) { "ComixSource: Found ${pageFromInitialData.result.pages.items.size} pages in initial-data queries" }
            return buildPages(pageFromInitialData)
        }

        // 3. Fallback: Run Keiyoushi chapter_pages.js in WebView
        val siteApiJs = ComixScripts.getSiteApi(context)
        val chapterPagesJs = ComixScripts.getChapterPages(context)

        val capture = webViewHelper.runWebViewCapture(
            url = chapterUrl,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
            initialHtml = null,
            timeout = 60.seconds,
            pageScript = { passPayloadName, rejectName, _ ->
                "($chapterPagesJs)($siteApiJs, $chapterId, window.$passPayloadName, window.$rejectName);"
            },
        )

        capture.material?.takeIf { it.isValid() }?.let {
            logcat(LogPriority.INFO) { "ComixSource: Captured cipher from page extraction" }
            cipher = ComixCipher(it)
        }

        val chapterPages = capture.payload.parseAs<ChapterPages>()
        return buildPages(chapterPages)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        runBlocking { getPageList(chapter) }
    }

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("Not used with modern coroutine getPageList")

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")

    // =========================== Helpers ===========================

    private fun buildPages(pages: ChapterPages): List<Page> {
        val base = pages.baseUrl.trimEnd('/')
        return pages.items.mapIndexed { index, img ->
            val full = (if (img.url.startsWith("http")) img.url else "$base/${img.url.trimStart('/')}").toHttpUrl()
            val isV3 = img.s == 1 || "v3" in full.queryParameterNames
            val isLegacyScramble = !isV3 && (index + 1) % 4 == 0
            val url = full.newBuilder().apply {
                if (isV3 && "v3" !in full.queryParameterNames) {
                    addQueryParameter("v3", null)
                }
                val altHosts = pages.altHosts(full.host, index)
                if (altHosts.isNotEmpty()) fragment("alt=" + altHosts.joinToString(","))
            }.build().toString()
            val finalUrl = if (isLegacyScramble) "$url#scrambled" else url
            Page(index, imageUrl = finalUrl)
        }
    }

    private fun buildPages(response: ChapterResponse): List<Page> {
        val pages = response.result.pages
        val chapterPages = ChapterPages(
            baseUrl = pages.baseUrl,
            items = pages.items.map { ChapterPages.PageDto(url = it.url, s = it.s) },
        )
        return buildPages(chapterPages)
    }

    private fun SManga.mangaId(): String? = memo[MANGA_ID_MEMO]?.jsonPrimitive?.content
        ?: getMangaUrl(this).toHttpUrlOrNull()
            ?.pathSegments
            ?.getOrNull(1)
            ?.substringBefore('-')
            ?.takeIf { it.isNotEmpty() }

    private fun SChapter.chapterId(): Int? = memo[CHAPTER_ID_MEMO]?.int
        ?: getChapterUrl(this).toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            ?.substringBefore("-chapter-")
            ?.toIntOrNull()

    private suspend inline fun <reified T> getSigned(
        path: String,
        params: Map<String, List<String>>,
    ): T? {
        val currentCipher = cipher ?: return null
        return runCatching {
            val entries = canonicalEntries(params)
            val query = entries.joinToString("&") { (name, value) ->
                "$name=${value.trim()}"
            }
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(path.trimStart('/'))
                .apply {
                    entries.forEach { (name, value) -> addQueryParameter(name, value) }
                    addQueryParameter("_", currentCipher.sign(path, query))
                }
                .build()
            val apiHeaders = headers.newBuilder()
                .set("Accept", "application/json, text/plain, */*")
                .set("Referer", "$baseUrl/browse")
                .set("Sec-Fetch-Dest", "empty")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Site", "same-origin")
                .removeAll("Upgrade-Insecure-Requests")
                .build()
            val request = GET(url.toString(), apiHeaders)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val raw = response.body.string()
            val root = comixJson.parseToJsonElement(raw)
            val decoded = if (root is JsonObject && "e" in root) {
                val enc = comixJson.decodeFromJsonElement<EncryptedResponse>(root)
                comixJson.parseToJsonElement(currentCipher.decrypt(enc.e))
            } else {
                root
            }
            logcat(LogPriority.INFO) { "ComixSource: Decrypted $path payload" }
            comixJson.decodeFromJsonElement<T>(decoded)
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "ComixSource: getSigned failed for path $path: ${e.message}" }
            if (cipher === currentCipher) cipher = null
            null
        }
    }

    private fun canonicalEntries(params: Map<String, List<String>>): List<Pair<String, String>> = buildList {
        params.toSortedMap().forEach { (rawName, values) ->
            val name = rawName.removeSuffix("[]")
            if (values.size == 1 && !rawName.endsWith("[]")) {
                add(name to values.single())
            } else {
                values.forEachIndexed { index, value -> add("$name[$index]" to value) }
            }
        }
    }

    companion object {
        @Volatile
        var cachedCipher: ComixCipher? = null
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("""/(?:i5|si|i|sii|ii)/""")
        private val SERVER_ERROR_CODES = 500..599
        private const val MAX_CHAPTER_PAGES = 200
        private const val OFFICIAL_GROUP_ID = 10702
    }
}
