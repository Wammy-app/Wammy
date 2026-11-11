package eu.kanade.presentation.browse

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class UnifiedSearchRankingTest {

    private class FakeSource(
        override val id: Long,
        override val name: String = "Source $id",
    ) : Source {
        override suspend fun getPopularManga(page: Int): MangasPage = error("unused")
        override suspend fun getLatestUpdates(page: Int): MangasPage = error("unused")
        override suspend fun getSearchManga(
            page: Int,
            query: String,
            filters: eu.kanade.tachiyomi.source.model.FilterList,
        ): MangasPage = error("unused")

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = error("unused")

        override suspend fun getPageList(chapter: SChapter): List<Page> = error("unused")
    }

    private fun fakeManga(
        id: Long,
        source: Long = 1L,
        title: String = "Title $id",
        url: String = "/manga/$id",
        author: String? = null,
        artist: String? = null,
        rating: Double? = null,
    ): Manga = Manga.create().copy(
        id = id,
        source = source,
        title = title,
        url = url,
        author = author,
        artist = artist,
        memo = rating?.let { JsonObject(mapOf("rating" to JsonPrimitive(it))) } ?: JsonObject(emptyMap()),
    )

    @Test
    fun `calculateRelevanceScore ranks exact prefix acronym substring multiword author and partial matches`() {
        val exact = fakeManga(1, title = "Naruto")
        val prefix = fakeManga(2, title = "Naruto Shippuden")
        val acronym = fakeManga(3, title = "Hunter x Hunter")
        val substring = fakeManga(4, title = "Road to Naruto")
        val multiWordAll = fakeManga(5, title = "The Blue Lock Project")
        val authorMatch = fakeManga(6, title = "Something Else", author = "Kishimoto")
        val partialWord = fakeManga(7, title = "Blue Archive")
        val unrelated = fakeManga(8, title = "One Piece")

        assertEquals(100, calculateRelevanceScore(exact, "naruto"))
        assertEquals(90, calculateRelevanceScore(prefix, "naruto"))
        assertEquals(85, calculateRelevanceScore(acronym, "hxh"))
        assertEquals(80, calculateRelevanceScore(substring, "naruto"))
        assertEquals(75, calculateRelevanceScore(multiWordAll, "blue project"))
        assertEquals(60, calculateRelevanceScore(authorMatch, "kishimoto"))
        assertEquals(40, calculateRelevanceScore(partialWord, "blue project"))
        assertEquals(0, calculateRelevanceScore(unrelated, "naruto"))
        assertEquals(0, calculateRelevanceScore(exact, "   "))
    }

    @Test
    fun `rankUnifiedSearchResults sorts by relevance then non-adult then rating and deduplicates`() {
        val safeSource = FakeSource(1L)
        val adultSource = FakeSource(2L)

        val exactAdultHighRating = fakeManga(1, source = 2L, title = "Solo Leveling", url = "/a1", rating = 9.8)
        val exactSafeLowRating = fakeManga(2, source = 1L, title = "Solo Leveling", url = "/s1", rating = 8.1)
        val exactSafeHighRating = fakeManga(3, source = 1L, title = "Solo Leveling", url = "/s2", rating = 9.5)
        val duplicateSafe = fakeManga(99, source = 1L, title = "Solo Leveling", url = "/s2", rating = 1.0)
        val prefixSafe = fakeManga(4, source = 1L, title = "Solo Leveling Ragnarok", url = "/s3", rating = 10.0)

        val items = mapOf<Source, SearchItemResult>(
            adultSource to SearchItemResult.Success(listOf(exactAdultHighRating)),
            safeSource to SearchItemResult.Success(
                listOf(prefixSafe, exactSafeLowRating, exactSafeHighRating, duplicateSafe),
            ),
            FakeSource(3L) to SearchItemResult.Loading,
            FakeSource(4L) to SearchItemResult.Error(RuntimeException("network error")),
        )

        val ranked = rankUnifiedSearchResults(
            items = items,
            searchQuery = "solo leveling",
            isAdultSource = { it.id == adultSource.id },
        )

        assertEquals(listOf(3L, 2L, 1L, 4L), ranked.map { it.id })
    }
}
