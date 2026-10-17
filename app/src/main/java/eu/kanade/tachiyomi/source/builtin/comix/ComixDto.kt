package eu.kanade.tachiyomi.source.builtin.comix

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

val comixJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

inline fun <reified T> String.parseAs(): T = comixJson.decodeFromString(this)
inline fun <reified T> JsonElement.parseAs(): T = comixJson.decodeFromJsonElement<T>(this)

const val MANGA_ID_MEMO = "mangaId"
const val CHAPTER_ID_MEMO = "chapterId"
const val CHAPTER_VOTES_MEMO = "votes"
const val CHAPTER_OFFICIAL_MEMO = "official"
const val CHAPTER_GROUP_ID_MEMO = "groupId"

@Serializable
class Term(
    val title: String,
)

@Serializable
class TagSearchResponse(
    val result: List<TagSearchHit> = emptyList(),
)

@Serializable
class TagSearchHit(val id: Int)

@Serializable
class Manga(
    val hid: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    @SerialName("alt_titles") val altTitlesOld: List<String> = emptyList(),
    val synopsis: String? = null,
    val type: String = "",
    val poster: Poster? = null,
    val status: String = "",
    val contentRating: String = "safe",
    val authors: List<Term>? = null,
    @SerialName("author") val authorOld: List<Term>? = null,
    val artists: List<Term>? = null,
    @SerialName("artist") val artistOld: List<Term>? = null,
    val genres: List<Term>? = null,
    @SerialName("genre") val genreOld: List<Term>? = null,
    val tags: List<Term>? = null,
    @SerialName("theme") val themeOld: List<Term>? = null,
    val demographics: List<Term>? = null,
    @SerialName("demographic") val demographicOld: List<Term>? = null,
    val formats: List<Term>? = null,
    val ratedAvg: Double = 0.0,
    val ratedCount: Long = 0L,
    val followsTotal: Long = 0L,
    val rank: Int = 0,
    val year: Int? = null,
    val originalLanguage: String? = null,
    val links: Map<String, String?>? = null,
    val url: String? = null,
) {
    @Serializable
    class Poster(
        val small: String? = null,
        val medium: String? = null,
        val large: String? = null,
    ) {
        fun from(quality: String? = null) = when (quality) {
            "large" -> large ?: medium ?: small ?: ""
            "small" -> small ?: medium ?: large ?: ""
            else -> medium ?: large ?: small ?: ""
        }
    }

    private val fancyScore: String
        get() {
            if (ratedAvg == 0.0) return ""
            val score = ratedAvg.toBigDecimal()
            val stars = score.div(BigDecimal(2))
                .setScale(0, RoundingMode.HALF_UP).toInt()

            val scoreString = if (score.scale() == 0) {
                score.toPlainString()
            } else {
                score.stripTrailingZeros().toPlainString()
            }

            return buildString {
                append("★".repeat(stars))
                if (stars < 5) append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        }

    fun toSManga() = SManga.create().apply {
        url = this@Manga.url?.substringAfter("/title") ?: "/$hid"
        memo = buildJsonObject { put(MANGA_ID_MEMO, hid) }
        title = this@Manga.title

        val actualAuthors = authors ?: authorOld
        val actualArtists = artists ?: artistOld

        author = actualAuthors?.joinToString { it.title }
        artist = actualArtists?.joinToString { it.title }
        description = buildString {
            fancyScore.takeIf { it.isNotEmpty() }?.let {
                append(it)
                append("\n\n")
            }
            synopsis?.takeUnless { it.isEmpty() }?.let { append(it) }

            val actualAltTitles = altTitles.ifEmpty { altTitlesOld }
            if (actualAltTitles.isNotEmpty()) {
                append("\n\n")
                append("**Alternative Names**:\n")
                append(actualAltTitles.joinToString("\n") { "- $it" })
            }
        }
        initialized = true
        status = when (this@Manga.status) {
            "releasing" -> SManga.ONGOING
            "on_hiatus" -> SManga.ON_HIATUS
            "finished" -> SManga.COMPLETED
            "discontinued" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = this@Manga.poster?.from("large")
        genre = formatGenres()
    }

    fun toBasicSManga() = SManga.create().apply {
        url = this@Manga.url?.substringAfter("/title") ?: "/$hid"
        memo = buildJsonObject { put(MANGA_ID_MEMO, hid) }
        title = this@Manga.title
        thumbnail_url = this@Manga.poster?.from("medium")
    }

    private fun formatGenres() = buildList {
        when (type) {
            "manhwa" -> add("Manhwa")
            "manhua" -> add("Manhua")
            "manga" -> add("Manga")
            else -> add("Other")
        }
        (genres ?: genreOld)?.map { it.title }?.let { addAll(it) }
        (demographics ?: demographicOld)?.map { it.title }?.let { addAll(it) }
        tags?.map { it.title }?.let { addAll(it) }
        if (contentRating == "erotica" || contentRating == "pornographic") add("NSFW")
    }.distinct().joinToString()
}

@Serializable
class SingleMangaResponse(
    val result: Manga,
)

@Serializable
class Meta(
    val page: Int = 1,
    val lastPage: Int = 1,
    @SerialName("last_page") val lastPageOld: Int = 1,
    val hasNext: Boolean = false,
) {
    val actualLastPage: Int get() = maxOf(lastPage, lastPageOld)
}

@Serializable
class Pagination(
    val page: Int = 1,
    val lastPage: Int = 1,
    @SerialName("last_page") val lastPageOld: Int = 1,
) {
    val actualLastPage: Int get() = maxOf(lastPage, lastPageOld)
}

@Serializable
class SearchResponse(
    val result: Items,
) {
    @Serializable
    class Items(
        val items: List<Manga> = emptyList(),
        val meta: Meta? = null,
        val pagination: Pagination? = null,
    ) {
        fun hasNextPage(): Boolean = when {
            meta != null -> meta.page < meta.actualLastPage
            pagination != null -> pagination.page < pagination.actualLastPage
            else -> false
        }
    }
}

@Serializable
class ChapterDetailsResponse(
    val result: Items,
) {
    @Serializable
    class Items(
        val items: List<Chapter> = emptyList(),
        val meta: Meta? = null,
        val pagination: Pagination? = null,
    ) {
        fun hasNextPage(): Boolean = when {
            meta != null -> meta.page < meta.actualLastPage
            pagination != null -> pagination.page < pagination.actualLastPage
            else -> false
        }
    }
}

@Serializable
class Chapter(
    val id: Int,
    val url: String = "",
    val number: Double,
    val name: String = "",
    val votes: Int = 0,
    val createdAtFormatted: String = "",
    val group: ScanlationGroup? = null,
    val isOfficial: Boolean = false,
) {
    @Serializable
    class ScanlationGroup(
        val id: Int? = null,
        val name: String,
    )

    companion object {
        private val DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
    }

    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = this@Chapter.url.indexOf("/title/").let { index ->
            if (index != -1) {
                this@Chapter.url.substring(index + 1)
            } else {
                "title/$mangaSlug/$id-chapter-${number.toString().removeSuffix(".0")}"
            }
        }
        memo = buildJsonObject {
            put(CHAPTER_ID_MEMO, id)
            put(CHAPTER_VOTES_MEMO, votes)
            put(CHAPTER_OFFICIAL_MEMO, isOfficial)
            group?.id?.let { put(CHAPTER_GROUP_ID_MEMO, it) }
        }
        name = buildString {
            append("Chapter ")
            append(this@Chapter.number.toString().removeSuffix(".0"))
            this@Chapter.name.takeUnless { it.isEmpty() }?.let { append(": $it") }
        }
        date_upload = parseRelativeDate(this@Chapter.createdAtFormatted)
        chapter_number = this@Chapter.number.toFloat()
        scanlator = if (this@Chapter.group != null) {
            this@Chapter.group.name
        } else if (this@Chapter.isOfficial) {
            "Official"
        } else {
            "Unknown"
        }
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        val trimmed = dateStr.trim().lowercase().removeSuffix(" ago")
        val match = DATE_REGEX.find(trimmed) ?: return 0L

        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val unit = match.groupValues[2]

        val calendar = Calendar.getInstance()
        when (unit) {
            "s", "sec", "secs" -> calendar.add(Calendar.SECOND, -amount)
            "m", "min", "mins" -> calendar.add(Calendar.MINUTE, -amount)
            "h", "hr", "hrs" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "d", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            "w", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "mo", "mos", "month", "months" -> calendar.add(Calendar.MONTH, -amount)
            "y", "yr", "yrs", "year", "years" -> calendar.add(Calendar.YEAR, -amount)
        }
        return calendar.timeInMillis
    }
}

@Serializable
class ChapterResponse(
    val result: ChapterResult,
) {
    @Serializable
    class ChapterResult(
        val pages: Pages,
    )

    @Serializable
    class Pages(
        val baseUrl: String,
        val items: List<PageDto>,
    )

    @Serializable
    class PageDto(
        val url: String,
        val s: Int = 0,
    )
}
