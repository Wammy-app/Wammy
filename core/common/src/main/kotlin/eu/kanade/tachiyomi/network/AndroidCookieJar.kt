package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    private val manager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()

        cookies.forEach { manager.setCookie(urlString, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val urlString = url.toString()
        var cookies = manager.getCookie(urlString)

        // Fallback for www vs non-www domain matching, ensuring Cloudflare cf_clearance is never missed
        if (cookies.isNullOrBlank() || !cookies.contains("cf_clearance")) {
            val host = url.host
            val altHost = if (host.startsWith("www.")) host.removePrefix("www.") else "www.$host"
            val altCookies = manager.getCookie("${url.scheme}://$altHost/")
                ?: manager.getCookie("${url.scheme}://$host/")
            if (!altCookies.isNullOrBlank()) {
                cookies = if (cookies.isNullOrBlank()) altCookies else "$cookies; $altCookies"
            }
        }

        return if (!cookies.isNullOrBlank()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        } else {
            emptyList()
        }
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val urlString = url.toString()
        val cookies = manager.getCookie(urlString) ?: return 0

        fun List<String>.filterNames(): List<String> {
            return if (cookieNames != null) {
                this.filter { it in cookieNames }
            } else {
                this
            }
        }

        return cookies.split(";")
            .map { it.substringBefore("=") }
            .filterNames()
            .onEach { manager.setCookie(urlString, "$it=;Max-Age=$maxAge") }
            .count()
    }

    fun removeAll() {
        manager.removeAllCookies {}
    }
}
