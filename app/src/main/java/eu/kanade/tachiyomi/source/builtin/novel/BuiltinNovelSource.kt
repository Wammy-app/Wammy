package eu.kanade.tachiyomi.source.builtin.novel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * Common base class for built-in autonomous novel sources in Wammy.
 * Integrates directly with Wammy's Novel Reader and Text Renderer.
 */
abstract class BuiltinNovelSource : HttpSource() {

    override val isNovelSource: Boolean = true
    override val lang: String = "en"

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = resolveUrl(manga.url)
        return GET(url, headers)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = resolveUrl(manga.url)
        return GET(url, headers)
    }

    /**
     * Resolves a URL that may be absolute or relative into a fully qualified URL.
     * Prevents double-domain concatenation errors like "https://domain.comhttps://domain.com/path".
     */
    protected fun resolveUrl(pathOrUrl: String): String {
        return when {
            pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
            pathOrUrl.startsWith("/") -> "$baseUrl$pathOrUrl"
            else -> "$baseUrl/$pathOrUrl"
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString()
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Novel sources use fetchPageText")
    }

    /**
     * Sanitizes and cleans raw HTML chapter text into structured paragraphs
     * for Wammy's NovelPageLoader and NovelTextRenderer.
     */
    protected fun cleanHtmlContent(element: Element): String {
        // Clone element so modifications don't alter the original DOM tree
        val clone = element.clone()

        // Strip known ads, scripts, trackers, navigation buttons, and social widgets
        clone.select(
            "script, style, iframe, link, button, input, textarea, form, noscript, " +
                ".ad, .ads, .advertisement, [id*='ad-'], [class*='ad-'], [id*='google_ads'], " +
                ".report-container, .comments-widget, .chapternav, .navbar, .header, .footer, " +
                ".social-share, .watermark, .share-buttons, .vote-wrapper, .prevchap, .nextchap, " +
                ".printfooter, #catlinks, .navbox, .vertical-navbox, .thumb, .infobox, .gallery, " +
                "table:has(a:contains(Return to)), td:contains(Return to)",
        ).remove()

        // Extract paragraphs if available
        val paragraphs = clone.select("p").filter { it.text().isNotBlank() }
        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n\n") { p ->
                "<p>${p.html().trim()}</p>"
            }
        }

        // Fallback: handle text divided by <br> or raw lines
        val text = clone.html()
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n") { "<p>$it</p>" }

        return text.ifBlank { "<p>${clone.text()}</p>" }
    }
}
