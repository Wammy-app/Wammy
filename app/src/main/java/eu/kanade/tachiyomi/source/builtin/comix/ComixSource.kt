package eu.kanade.tachiyomi.source.builtin.comix

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.network.challenge.BossChallengeCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.URLEncoder

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
    private val webViewHelper by lazy {
        ComixWebViewHelper(context) { material ->
            logcat(LogPriority.INFO) { "ComixSource: Received cipher material via bridge" }
            cipher = ComixCipher(material)
        }
    }

    private var cipher: ComixCipher?
        get() = cachedCipher
        set(value) {
            cachedCipher = value
        }

    private suspend fun ensureClearance(force: Boolean = false) {
        val coordinator = BossChallengeCoordinator.instance ?: return
        val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }

        if (force || !coordinator.hasClearance(baseUrl)) {
            coordinator.resolveAsync(baseUrl, headersMap, force = true)
            return
        }

        val isBlocked = withContext(Dispatchers.IO) {
            try {
                val req = GET(baseUrl, headers)
                val resp = client.newCall(req).execute()
                val code = resp.code
                resp.close()
                code == 403 || code == 503
            } catch (_: Exception) {
                !coordinator.hasClearance(baseUrl)
            }
        }

        if (isBlocked) {
            coordinator.resolveAsync(baseUrl, headersMap, force = true)
        }
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(Descrambler.interceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                var response = chain.proceed(request)
                if (response.isSuccessful) return@addInterceptor response

                val url = request.url.toString()
                val fallbacks = listOf("/i5/", "/si/", "/i/", "/sii/", "/ii/")
                    .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                    .filter { it != url }

                if (fallbacks.isEmpty()) return@addInterceptor response

                for (fallbackUrl in fallbacks) {
                    response.close()
                    response = chain.proceed(request.newBuilder().url(fallbackUrl).build())
                    if (response.isSuccessful) break
                }
                response
            }
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "*/*")

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        val urlWithoutFragment = imageUrl.substringBefore('#')
        val imageHost = urlWithoutFragment.toHttpUrlOrNull()?.host.orEmpty()
        val isScrambled = imageUrl.contains("#scrambled")
        val isV3 = urlWithoutFragment.toHttpUrlOrNull()?.queryParameterNames?.contains("v3") == true
        val isLegacyScramble = isScrambled && !isV3
        val baseUrlHost = baseUrl.toHttpUrl().host
        val requestHeaders = if (
            imageHost.isNotEmpty() &&
            !imageHost.endsWith(baseUrlHost) &&
            !isLegacyScramble
        ) {
            headersBuilder()
                .removeAll("Origin")
                .build()
        } else {
            headers
        }
        return GET(urlWithoutFragment, requestHeaders)
    }

    // =========================== Popular ===========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val params = mapOf(
            "order[score]" to listOf("desc"),
            "page" to listOf(page.toString()),
            "limit" to listOf("28"),
        )
        getSigned<SearchResponse>("/api/v1/manga", params)?.let { response ->
            val mangaList = response.result.items.map { it.toBasicSManga() }
            if (mangaList.isNotEmpty()) {
                return MangasPage(mangaList, response.result.hasNextPage())
            }
        }

        ensureClearance()
        val url = "$baseUrl/browse?order[score]=desc&page=$page"
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            createBrowseScript(passPayloadName)
        }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        val searchResponse = capture.payload.parseAs<SearchResponse>()
        val mangaList = searchResponse.result.items.map { it.toBasicSManga() }
        return MangasPage(mangaList, searchResponse.result.hasNextPage())
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { getPopularManga(page) }
    }

    // =========================== Latest ===========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val params = mapOf(
            "order[chapter_updated_at]" to listOf("desc"),
            "page" to listOf(page.toString()),
            "limit" to listOf("28"),
        )
        getSigned<SearchResponse>("/api/v1/manga", params)?.let { response ->
            val mangaList = response.result.items.map { it.toBasicSManga() }
            if (mangaList.isNotEmpty()) {
                return MangasPage(mangaList, response.result.hasNextPage())
            }
        }

        ensureClearance()
        val url = "$baseUrl/browse?order[chapter_updated_at]=desc&page=$page"
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            createBrowseScript(passPayloadName)
        }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        val searchResponse = capture.payload.parseAs<SearchResponse>()
        val mangaList = searchResponse.result.items.map { it.toBasicSManga() }
        return MangasPage(mangaList, searchResponse.result.hasNextPage())
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { getLatestUpdates(page) }
    }

    // =========================== Search ===========================

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val params = mapOf(
            "keyword" to listOf(query.trim()),
            "page" to listOf(page.toString()),
            "limit" to listOf("28"),
        )
        getSigned<SearchResponse>("/api/v1/manga", params)?.let { response ->
            val mangaList = response.result.items.map { it.toBasicSManga() }
            if (mangaList.isNotEmpty()) {
                return MangasPage(mangaList, response.result.hasNextPage())
            }
        }

        ensureClearance()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$baseUrl/browse?keyword=$encoded&page=$page"
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            createBrowseScript(passPayloadName)
        }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        val searchResponse = capture.payload.parseAs<SearchResponse>()
        val mangaList = searchResponse.result.items.map { it.toBasicSManga() }
        return MangasPage(mangaList, searchResponse.result.hasNextPage())
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        runBlocking { getSearchManga(page, query, filters) }
    }

    // =========================== Details ===========================

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val mangaId = manga.mangaId()
        if (mangaId != null) {
            getSigned<SingleMangaResponse>("/api/v1/manga/$mangaId", emptyMap())?.let { response ->
                return response.result.toSManga().apply {
                    this.url = manga.url
                }
            }
        }

        ensureClearance()
        val slug = manga.url.removePrefix("/title/").removePrefix("/title").removePrefix("/")
        val url = "$baseUrl/title/$slug"
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            createDetailScript(passPayloadName)
        }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        return try {
            val response = capture.payload.parseAs<SingleMangaResponse>()
            response.result.toSManga().apply { this.url = manga.url }
        } catch (_: Exception) {
            val mangaDto = capture.payload.parseAs<Manga>()
            mangaDto.toSManga().apply { this.url = manga.url }
        }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        runBlocking { getMangaDetails(manga) }
    }

    // =========================== Chapters ===========================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val mangaSlug = manga.url.removePrefix("/title/").removePrefix("/")
        val mangaId = manga.mangaId()

        if (mangaId != null) {
            val chapters = mutableListOf<Chapter>()
            var page = 1
            while (page <= 10) {
                val response = getSigned<ChapterDetailsResponse>(
                    "/api/v1/manga/$mangaId/chapters",
                    mapOf(
                        "limit" to listOf("100"),
                        "order[number]" to listOf("desc"),
                        "page" to listOf(page.toString()),
                    ),
                ) ?: break
                chapters += response.result.items
                if (!response.result.hasNextPage() || response.result.items.isEmpty()) break
                page++
            }
            if (chapters.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Loaded ${chapters.size} chapters via signed API for $mangaId" }
                return chapters.map { it.toSChapter(mangaSlug) }
                    .distinctBy { it.url }
                    .sortedByDescending { it.chapter_number }
            }
        }

        ensureClearance()
        val url = "$baseUrl/title/$mangaSlug"
        logcat(LogPriority.INFO) { "ComixSource: Running WebView capture for chapters at $url" }
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            createChapterScript(passPayloadName)
        }

        logcat(LogPriority.INFO) { "ComixSource: Chapter capture payload length=${capture.payload.length}, preview=${capture.payload.take(200)}" }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        val chaptersFromCapture = try {
            val detailsResponse = capture.payload.parseAs<ChapterDetailsResponse>()
            detailsResponse.result.items.map { it.toSChapter(mangaSlug) }
        } catch (_: Exception) {
            try {
                val list = capture.payload.parseAs<List<Chapter>>()
                list.map { it.toSChapter(mangaSlug) }
            } catch (_: Exception) {
                try {
                    val items = capture.payload.parseAs<ChapterDetailsResponse.Items>()
                    items.items.map { it.toSChapter(mangaSlug) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        if (chaptersFromCapture.isNotEmpty()) {
            return chaptersFromCapture.distinctBy { it.url }
                .sortedByDescending { it.chapter_number }
        }

        if (cipher != null && mangaId != null) {
            val chapters = mutableListOf<Chapter>()
            var page = 1
            while (page <= 10) {
                val response = getSigned<ChapterDetailsResponse>(
                    "/api/v1/manga/$mangaId/chapters",
                    mapOf(
                        "limit" to listOf("100"),
                        "order[number]" to listOf("desc"),
                        "page" to listOf(page.toString()),
                    ),
                ) ?: break
                chapters += response.result.items
                if (!response.result.hasNextPage() || response.result.items.isEmpty()) break
                page++
            }
            if (chapters.isNotEmpty()) {
                logcat(LogPriority.INFO) { "ComixSource: Loaded ${chapters.size} chapters via signed API after capture for $mangaId" }
                return chapters.map { it.toSChapter(mangaSlug) }
                    .distinctBy { it.url }
                    .sortedByDescending { it.chapter_number }
            }
        }

        return emptyList()
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        runBlocking { getChapterList(manga) }
    }

    // =========================== Pages ===========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.chapterId()
        if (chapterId != null) {
            getSigned<ChapterResponse>("/api/v1/chapters/$chapterId", emptyMap())?.let { response ->
                return buildPages(response)
            }
        }

        ensureClearance()
        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl/${chapter.url.trimStart('/')}"
        val capture = webViewHelper.runWebViewCapture(
            url = chapterUrl,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            createPageScript(passPayloadName)
        }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        val pageResponse = capture.payload.parseAs<ChapterResponse>()
        return buildPages(pageResponse)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        runBlocking { getPageList(chapter) }
    }

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("Not used with modern coroutine getPageList")

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used with direct Page image URLs")

    // =========================== Helpers ===========================

    private fun buildPages(response: ChapterResponse): List<Page> {
        val pages = response.result.pages
        val base = pages.baseUrl.trimEnd('/')

        return pages.items.mapIndexed { index, img ->
            val full = if (img.url.startsWith("http")) img.url else "$base/${img.url.trimStart('/')}"
            val isV3 = img.s == 1 || full.contains("?v3")
            val isLegacyScramble = !isV3 && (index + 1) % 4 == 0
            val url = when {
                isV3 -> full.toHttpUrl().newBuilder().apply {
                    if (!full.toHttpUrl().queryParameterNames.contains("v3")) {
                        addQueryParameter("v3", null)
                    }
                }.build().toString()
                isLegacyScramble -> "$full#scrambled"
                else -> full
            }
            Page(index, imageUrl = url)
        }
    }

    private fun SManga.mangaId(): String? = memo[MANGA_ID_MEMO]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?: url.substringBefore('-').removePrefix("/title/").removePrefix("/").takeIf { it.isNotBlank() }

    private fun SChapter.chapterId(): Int? = memo[CHAPTER_ID_MEMO]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
        ?: Regex("""/(\d+)-chapter-""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""/title/[^/]+/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

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
            val request = GET(url.toString(), headers)
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
            logcat(LogPriority.INFO) { "ComixSource: Decrypted $path payload: $decoded" }
            comixJson.decodeFromJsonElement<T>(decoded)
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "ComixSource: getSigned failed for path $path: ${e.message}" }
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

    private fun createBrowseScript(passPayloadName: String): String = """
        (function () {
            const payloadKey = '__comixBrowsePayload';
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

            const originalFetch = window.fetch;
            if (typeof originalFetch === 'function') {
                window.fetch = function () {
                    return originalFetch.apply(this, arguments).then(response => {
                        try {
                            const url = response && response.url || '';
                            if (url.includes('/api/v1/manga')) {
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
                        if (this.__comixBrowseUrl && this.__comixBrowseUrl.includes('/api/v1/manga')) {
                            captureText(this.responseText);
                        }
                    } catch (e) {}
                });
                return originalSend.apply(this, arguments);
            };

            const originalParse = JSON.parse;
            JSON.parse = new Proxy(originalParse, {
                apply(target, thisArg, args) {
                    const parsed = Reflect.apply(target, thisArg, args);
                    capture(parsed);
                    return parsed;
                }
            });
            return window[payloadKey] || null;
        })();
    """.trimIndent()

    private fun createDetailScript(passPayloadName: String): String = """
        (function () {
            const payloadKey = '__comixDetailPayload';
            const capture = parsed => {
                try {
                    if (!parsed) return false;
                    if (parsed.state && parsed.state.data) parsed = parsed.state.data;
                    if (parsed.data && (parsed.data.result || parsed.data.hid || parsed.data.title)) parsed = parsed.data;
                    if (parsed && (parsed.result || parsed.hid)) {
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
                if (queries) {
                    for (const key of Object.keys(queries)) {
                        if (key.includes('detail')) {
                            if (capture(queries[key])) return window[payloadKey];
                        }
                    }
                    for (const key of Object.keys(queries)) {
                        if (key.includes('/api/v1/manga/')) {
                            if (capture(queries[key])) return window[payloadKey];
                        }
                    }
                }
            } catch (e) {}

            if (window[payloadKey]) return window[payloadKey];
            if (window.__comixDetailCaptureInstalled) return null;
            window.__comixDetailCaptureInstalled = true;

            const captureText = text => {
                try {
                    if (text) capture(JSON.parse(text));
                } catch (e) {}
            };

            const originalFetch = window.fetch;
            if (typeof originalFetch === 'function') {
                window.fetch = function () {
                    return originalFetch.apply(this, arguments).then(response => {
                        try {
                            const u = response && response.url || '';
                            if (u.includes('/api/v1/manga/')) {
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
                this.__comixUrl = String(url || '');
                return originalOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
                this.addEventListener('load', function () {
                    try {
                        if (this.__comixUrl && this.__comixUrl.includes('/api/v1/manga/')) {
                            captureText(this.responseText);
                        }
                    } catch (e) {}
                });
                return originalSend.apply(this, arguments);
            };

            const originalParse = JSON.parse;
            JSON.parse = new Proxy(originalParse, {
                apply(target, thisArg, args) {
                    const parsed = Reflect.apply(target, thisArg, args);
                    capture(parsed);
                    return parsed;
                }
            });

            return window[payloadKey] || null;
        })();
    """.trimIndent()

    private fun createChapterScript(passPayloadName: String): String = """
        (function () {
            const payloadKey = '__comixChapterPayload';
            const capture = (parsed, allowEmpty = false) => {
                try {
                    if (!parsed) return false;
                    if (parsed.state && parsed.state.data) parsed = parsed.state.data;
                    if (parsed.data && (parsed.data.result || parsed.data.items)) parsed = parsed.data;
                    if (parsed && Array.isArray(parsed.result)) {
                        parsed = { result: { items: parsed.result } };
                    }
                    if (parsed && Array.isArray(parsed.items)) {
                        parsed = { result: parsed };
                    }
                    if (parsed && Array.isArray(parsed)) {
                        parsed = { result: { items: parsed } };
                    }
                    if (
                        parsed &&
                        parsed.result &&
                        Array.isArray(parsed.result.items)
                    ) {
                        const items = parsed.result.items;
                        if (items.length > 0) {
                            const first = items[0];
                            const isChapter = first && (
                                first.chap !== undefined ||
                                first.number !== undefined ||
                                first.chapter !== undefined ||
                                first.name !== undefined ||
                                first.id !== undefined ||
                                (first.title !== undefined && first.hid !== undefined && (first.manga_id !== undefined || first.mangaId !== undefined))
                            );
                            if (!isChapter) return false;
                        } else if (!allowEmpty) {
                            return false;
                        }
                        window[payloadKey] = JSON.stringify(parsed);
                        window.$passPayloadName(window[payloadKey]);
                        return true;
                    }
                } catch (e) {}
                return false;
            };

            if (window[payloadKey]) return window[payloadKey];

            window.__wammyLog && window.__wammyLog('createChapterScript running, href=' + window.location.href);

            try {
                const raw = document.querySelector('script#initial-data')?.textContent;
                const queries = raw && JSON.parse(raw).queries;
                if (queries) {
                    const keys = Object.keys(queries);
                    window.__wammyLog && window.__wammyLog('initial-data queries keys: ' + keys.join(' | '));
                    for (const key of keys) {
                        if (key.includes('chapters') || key.includes('chapter')) {
                            window.__wammyLog && window.__wammyLog('matched chapter query key: ' + key);
                            if (capture(queries[key])) {
                                window.__wammyLog && window.__wammyLog('successfully captured query: ' + key);
                                return window[payloadKey];
                            }
                        }
                    }
                } else {
                    window.__wammyLog && window.__wammyLog('no initial-data or queries found in DOM');
                }
            } catch (e) {
                window.__wammyLog && window.__wammyLog('error in initial-data parsing: ' + e);
            }

            try {
                window.scrollTo(0, 1500);
                setTimeout(() => window.scrollTo(0, 3000), 400);
            } catch (e) {}

            if (window[payloadKey]) return window[payloadKey];
            if (window.__comixChapterCaptureInstalled) return null;
            window.__comixChapterCaptureInstalled = true;

            const captureText = text => {
                try {
                    window.__wammyLog && window.__wammyLog('captureText called, len=' + (text ? text.length : 0));
                    if (text) capture(JSON.parse(text), true);
                } catch (e) {}
            };

            const originalFetch = window.fetch;
            if (typeof originalFetch === 'function') {
                window.fetch = function () {
                    try {
                        const u = arguments[0] && (typeof arguments[0] === 'string' ? arguments[0] : arguments[0].url) || '';
                        window.__wammyLog && window.__wammyLog('fetch called: ' + u);
                    } catch (e) {}
                    return originalFetch.apply(this, arguments).then(response => {
                        try {
                            const u = response && response.url || '';
                            if (u.includes('/chapters')) {
                                window.__wammyLog && window.__wammyLog('fetch intercepted /chapters: ' + u);
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
                this.__comixUrl = String(url || '');
                window.__wammyLog && window.__wammyLog('XHR open: ' + this.__comixUrl);
                return originalOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
                this.addEventListener('load', function () {
                    try {
                        if (this.__comixUrl && this.__comixUrl.includes('/chapters')) {
                            window.__wammyLog && window.__wammyLog('XHR loaded /chapters');
                            captureText(this.responseText);
                        }
                    } catch (e) {}
                });
                return originalSend.apply(this, arguments);
            };

            const originalParse = JSON.parse;
            JSON.parse = new Proxy(originalParse, {
                apply(target, thisArg, args) {
                    const parsed = Reflect.apply(target, thisArg, args);
                    try {
                        if (parsed && typeof parsed === 'object') {
                            const keys = Object.keys(parsed);
                            if (keys.includes('items') || keys.includes('result') || keys.includes('state')) {
                                window.__wammyLog && window.__wammyLog('JSON.parse parsed object keys: ' + keys.join(','));
                            }
                        }
                    } catch (e) {}
                    capture(parsed);
                    return parsed;
                }
            });

            return window[payloadKey] || null;
        })();
    """.trimIndent()

    private fun createPageScript(passPayloadName: String): String = """
        (function () {
            const payloadKey = '__comixPagePayload';
            const capture = parsed => {
                try {
                    if (!parsed) return false;
                    if (parsed.state && parsed.state.data) parsed = parsed.state.data;
                    if (parsed.data && (parsed.data.result || parsed.data.pages)) parsed = parsed.data;
                    if (parsed && parsed.result && parsed.result.pages) {
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
                if (queries) {
                    for (const key of Object.keys(queries)) {
                        if (key.includes('chapter') || key.includes('pages')) {
                            if (capture(queries[key])) return window[payloadKey];
                        }
                    }
                    Object.values(queries).some(capture);
                }
            } catch (e) {}

            if (window[payloadKey]) return window[payloadKey];
            if (window.__comixPageCaptureInstalled) return null;
            window.__comixPageCaptureInstalled = true;

            const captureText = text => {
                try {
                    if (text) capture(JSON.parse(text));
                } catch (e) {}
            };

            const originalFetch = window.fetch;
            if (typeof originalFetch === 'function') {
                window.fetch = function () {
                    return originalFetch.apply(this, arguments).then(response => {
                        try {
                            const u = response && response.url || '';
                            if (u.includes('/chapters/') || u.includes('/pages')) {
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
                this.__comixUrl = String(url || '');
                return originalOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
                this.addEventListener('load', function () {
                    try {
                        if (this.__comixUrl && (this.__comixUrl.includes('/chapters/') || this.__comixUrl.includes('/pages'))) {
                            captureText(this.responseText);
                        }
                    } catch (e) {}
                });
                return originalSend.apply(this, arguments);
            };

            const originalParse = JSON.parse;
            JSON.parse = new Proxy(originalParse, {
                apply(target, thisArg, args) {
                    const parsed = Reflect.apply(target, thisArg, args);
                    capture(parsed);
                    return parsed;
                }
            });

            return window[payloadKey] || null;
        })();
    """.trimIndent()

    companion object {
        @Volatile
        var cachedCipher: ComixCipher? = null
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("""/(?:i5|si|i|sii|ii)/""")
    }
}
