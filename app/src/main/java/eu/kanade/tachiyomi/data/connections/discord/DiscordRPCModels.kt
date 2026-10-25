package eu.kanade.tachiyomi.data.connections.discord

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import tachiyomi.i18n.MR

// Constant for logging tag
const val RICH_PRESENCE_TAG = "discord_rpc"

// Wammy's Discord Application ID
internal const val RICH_PRESENCE_APPLICATION_ID = "1551895517397516318"

val DOWNLOAD_BUTTON_LABEL_RES = MR.strings.discord_download_button
const val DOWNLOAD_BUTTON_URL = "https://kainotch.github.io/WammyWeb/download/"
const val DISCORD_BUTTON_LABEL = "Discord"
const val DISCORD_BUTTON_URL = "https://discord.gg/qybFSATs7Q"

@Serializable
data class Activity(
    @SerialName("application_id")
    val applicationId: String? = RICH_PRESENCE_APPLICATION_ID,
    val name: String? = null,
    val details: String? = null,
    val state: String? = null,
    val type: Int? = null,
    val timestamps: Timestamps? = null,
    val assets: Assets? = null,
    val buttons: List<String>? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class Assets(
        @SerialName("large_image")
        val largeImage: String? = null,
        @SerialName("large_text")
        val largeText: String? = null,
        @SerialName("small_image")
        val smallImage: String? = null,
        @SerialName("small_text")
        val smallText: String? = null,
    )

    @Serializable
    data class Metadata(
        @SerialName("button_urls")
        val buttonUrls: List<String>,
    )

    @Serializable
    data class Timestamps(
        val start: Long? = null,
        val end: Long? = null,
        val stop: Long? = null,
    )
}

@Serializable
data class Presence(
    val status: String? = null,
    val afk: Boolean = true,
    val activities: List<Activity> = listOf(),
    val since: Long? = null,
) {
    @Serializable
    data class Response(
        val op: Long,
        val d: Presence,
    )
}

@Serializable
data class Identity(
    val token: String,
    val properties: Properties,
    val compress: Boolean,
    val intents: Long,
) {
    @Serializable
    data class Response(
        val op: Long,
        val d: Identity,
    )

    @Serializable
    data class Properties(
        @SerialName("\$os")
        val os: String,

        @SerialName("\$browser")
        val browser: String,

        @SerialName("\$device")
        val device: String,
    )
}

@Serializable
data class Res(
    val t: String?,
    val s: Int?,
    val op: Int,
    val d: JsonElement,
)

enum class ActivityType(val value: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    COMPETING(5),
    CUSTOM(4),
}

@Suppress("MagicNumber")
enum class OpCode(val value: Int) {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    PRESENCE_UPDATE(3),
    VOICE_STATE(4),
    RESUME(6),
    RECONNECT(7),
    REQUEST_GUILD_MEMBERS(8),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    UNKNOWN(-1),
}

data class ReaderData(
    val incognitoMode: Boolean = false,
    val mangaId: Long? = null,
    val mangaTitle: String? = null,
    val chapterNumber: String? = null,
    val thumbnailUrl: String? = null,
    val startTimestamp: Long? = null,
)

const val WAMMY_LOGO_URL = "https://raw.githubusercontent.com/Wammy-app/Wammy/master/.github/assets/logo.png"

enum class DiscordScreen(
    val text: StringResource,
    val details: StringResource,
) {
    APP(MR.strings.app_name, MR.strings.discord_status_using),
    LIBRARY(MR.strings.label_library, MR.strings.discord_status_browsing),
    UPDATES(MR.strings.label_recent_updates, MR.strings.discord_status_scrolling),
    HISTORY(MR.strings.label_recent_manga, MR.strings.discord_status_scrolling),
    BROWSE(MR.strings.label_sources, MR.strings.discord_status_browsing),
    MORE(MR.strings.label_settings, MR.strings.discord_status_messing),
    WEBVIEW(MR.strings.action_web_view, MR.strings.discord_status_browsing),
    MANGA(MR.strings.manga, MR.strings.reading),
    NOVEL(MR.strings.duplicate_type_novel, MR.strings.reading),
}
