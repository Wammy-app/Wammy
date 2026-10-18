package eu.kanade.tachiyomi.source.builtin.comix

import android.content.Context

object ComixScripts {
    @Volatile
    private var siteApi: String? = null

    @Volatile
    private var chapterList: String? = null

    @Volatile
    private var chapterPages: String? = null

    @Volatile
    private var solveCaptcha: String? = null

    @Volatile
    private var browseManga: String? = null

    fun getSiteApi(context: Context): String = siteApi ?: synchronized(this) {
        siteApi ?: context.assets.open("comix/site_api.js").bufferedReader().use { it.readText() }
            .also { siteApi = it }
    }

    fun getBrowseManga(context: Context): String = browseManga ?: synchronized(this) {
        browseManga ?: context.assets.open("comix/browse_manga.js").bufferedReader().use { it.readText() }
            .also { browseManga = it }
    }

    fun getChapterList(context: Context): String = chapterList ?: synchronized(this) {
        chapterList ?: context.assets.open("comix/chapter_list.js").bufferedReader().use { it.readText() }
            .also { chapterList = it }
    }

    fun getChapterPages(context: Context): String = chapterPages ?: synchronized(this) {
        chapterPages ?: context.assets.open("comix/chapter_pages.js").bufferedReader().use { it.readText() }
            .also { chapterPages = it }
    }

    fun getSolveCaptcha(context: Context): String = solveCaptcha ?: synchronized(this) {
        solveCaptcha ?: context.assets.open("comix/solve_captcha.js").bufferedReader().use { it.readText() }
            .also { solveCaptcha = it }
    }
}
