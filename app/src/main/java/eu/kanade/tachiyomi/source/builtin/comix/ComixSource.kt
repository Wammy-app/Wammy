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
    private val webViewHelper by lazy { ComixWebViewHelper(context) }

    @Volatile
    private var cipher: ComixCipher? = null

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

        val url = "$baseUrl/title${manga.url.removePrefix("/title")}"
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            """
            (function () {
                const payloadKey = '__comixDetailPayload';
                const capture = parsed => {
                    try {
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
                            if (key.includes('"detail"') || key.includes('"manga"')) {
                                if (capture(queries[key])) return window[payloadKey];
                            }
                        }
                    }
                } catch (e) {}
                return window[payloadKey] || null;
            })();
            """.trimIndent()
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
                return chapters.map { it.toSChapter(mangaSlug) }
                    .distinctBy { it.url }
                    .sortedByDescending { it.chapter_number }
            }
        }

        val url = "$baseUrl/title${manga.url.removePrefix("/title")}"
        val capture = webViewHelper.runWebViewCapture(
            url = url,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            """
            (function () {
                const payloadKey = '__comixChapterPayload';
                const capture = parsed => {
                    try {
                        if (parsed && parsed.result && Array.isArray(parsed.result.items)) {
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
                            if (key.includes('chapters')) {
                                if (capture(queries[key])) return window[payloadKey];
                            }
                        }
                    }
                } catch (e) {}

                if (window[payloadKey]) return window[payloadKey];
                if (window.__comixChapterCaptureInstalled) return null;
                window.__comixChapterCaptureInstalled = true;

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
                                if (u.includes('/chapters')) {
                                    response.clone().text().then(captureText).catch(() => {});
                                }
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                return window[payloadKey] || null;
            })();
            """.trimIndent()
        }

        capture.material?.takeIf { it.isValid() }?.let {
            cipher = ComixCipher(it)
        }

        return try {
            val detailsResponse = capture.payload.parseAs<ChapterDetailsResponse>()
            detailsResponse.result.items.map { it.toSChapter(mangaSlug) }
                .distinctBy { it.url }
                .sortedByDescending { it.chapter_number }
        } catch (_: Exception) {
            val list = capture.payload.parseAs<List<Chapter>>()
            list.map { it.toSChapter(mangaSlug) }
                .distinctBy { it.url }
                .sortedByDescending { it.chapter_number }
        }
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

        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl/${chapter.url.trimStart('/')}"
        val capture = webViewHelper.runWebViewCapture(
            url = chapterUrl,
            userAgent = headers["User-Agent"] ?: network.defaultUserAgentProvider(),
        ) { passPayloadName, _ ->
            """
            (function () {
                const payloadKey = '__comixPagePayload';
                const capture = parsed => {
                    try {
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
                    }
                } catch (e) {}

                if (window[payloadKey]) return window[payloadKey];
                if (window.__comixPageCaptureInstalled) return null;
                window.__comixPageCaptureInstalled = true;

                const originalFetch = window.fetch;
                if (typeof originalFetch === 'function') {
                    window.fetch = function () {
                        return originalFetch.apply(this, arguments).then(response => {
                            try {
                                response.clone().text().then(text => {
                                    try { capture(JSON.parse(text)); } catch (e) {}
                                }).catch(() => {});
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                return window[payloadKey] || null;
            })();
            """.trimIndent()
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
            comixJson.decodeFromJsonElement<T>(decoded)
        }.getOrElse {
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

    companion object {
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("""/(?:i5|si|i|sii|ii)/""")
    }
}
