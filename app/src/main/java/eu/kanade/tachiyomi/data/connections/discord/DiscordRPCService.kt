package eu.kanade.tachiyomi.data.connections.discord

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.ui.util.fastAny
import androidx.core.content.ContextCompat
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category.Companion.UNCATEGORIZED_ID
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.ceil
import kotlin.math.floor

class DiscordRPCService : Service() {

    private val connectionsManager: ConnectionsManager by injectLazy()

    override fun onCreate() {
        super.onCreate()
        logcat(LogPriority.INFO) { "Starting Discord RPC service" }

        val token = connectionsPreferences.connectionsToken(connectionsManager.discord).get()

        if (token.isBlank()) {
            logcat(LogPriority.WARN) { "Discord RPC disabled due to missing token" }
            connectionsPreferences.enableDiscordRPC().set(false)
            stopSelf()
            return
        }

        notification(this)

        val status = when (connectionsPreferences.discordRPCStatus().get()) {
            -1 -> "dnd"
            0 -> "idle"
            else -> "online"
        }

        try {
            rpc = DiscordRPC(token, status)
            try {
                discordScope.launchIO { setScreen(this@DiscordRPCService) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error setting initial screen: ${e.message}" }
                stopSelf()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to initialize Discord RPC: ${e.message}" }
            connectionsPreferences.enableDiscordRPC().set(false)
            stopSelf()
        }
    }

    override fun onDestroy() {
        NotificationReceiver.dismissNotification(this, Notifications.ID_DISCORD_RPC)
        rpc?.run {
            closeRPC()
            rpc = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART -> restartRPC()
            STOP_SERVICE -> {
                logcat(LogPriority.INFO) { "Stopping Discord RPC service" }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun restartRPC() {
        try {
            logcat(LogPriority.INFO) { "Restarting Discord RPC service" }
            rpc?.closeRPC()
            rpc = null

            val token = connectionsPreferences.connectionsToken(connectionsManager.discord).get()
            if (token.isBlank()) {
                logcat(LogPriority.WARN) { "Discord RPC restart failed due to missing token" }
                stopSelf()
                return
            }

            val status = when (connectionsPreferences.discordRPCStatus().get()) {
                -1 -> "dnd"
                0 -> "idle"
                else -> "online"
            }

            rpc = DiscordRPC(token, status)
            discordScope.launchIO {
                setScreen(this@DiscordRPCService)
            }
            logcat(LogPriority.INFO) { "Discord RPC restarted successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to restart Discord RPC: ${e.message}" }
            stopSelf()
        }
    }

    private fun notification(context: Context) {
        val stopIntent = NotificationReceiver.stopDiscordRPCService(context)

        val builder = context.notificationBuilder(Notifications.CHANNEL_DISCORD_RPC) {
            setSmallIcon(R.drawable.ic_discord_24dp)
            setContentTitle(context.stringResource(MR.strings.app_name))
            setContentText(context.stringResource(MR.strings.pref_discord_rpc))
            addAction(R.drawable.ic_close_24dp, context.stringResource(MR.strings.action_stop), stopIntent)
            setAutoCancel(false)
            setOngoing(true)
            setUsesChronometer(true)
        }

        startForeground(Notifications.ID_DISCORD_RPC, builder.build())
    }

    companion object {
        private val connectionsPreferences: ConnectionsPreferences by injectLazy()

        private var rpc: DiscordRPC? = null
        private val handler = Handler(Looper.getMainLooper())
        private val job = SupervisorJob()
        internal val discordScope = CoroutineScope(Dispatchers.IO + job)

        private const val ACTION_RESTART = "eu.kanade.tachiyomi.DISCORD_RPC_RESTART"
        private const val STOP_SERVICE = "eu.kanade.tachiyomi.DISCORD_RPC_STOP"

        fun start(context: Context, connectionsManager: ConnectionsManager = Injekt.get()) {
            handler.removeCallbacksAndMessages(null)
            val token = connectionsPreferences.connectionsToken(connectionsManager.discord).get()
            if (connectionsPreferences.enableDiscordRPC().get()) {
                if (token.isBlank()) {
                    logcat(LogPriority.WARN) { "Discord RPC not started due to missing token" }
                    connectionsPreferences.enableDiscordRPC().set(false)
                } else if (rpc == null) {
                    since = System.currentTimeMillis()
                    context.startForegroundService(Intent(context, DiscordRPCService::class.java))
                }
            }
        }

        fun stop(context: Context, delay: Long = 30000L) {
            handler.removeCallbacksAndMessages(null)
            if (delay > 0) {
                handler.postDelayed({
                    val stopIntent = Intent(context, DiscordRPCService::class.java).apply {
                        action = STOP_SERVICE
                    }
                    try {
                        context.startService(stopIntent)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "Failed to stop Discord RPC service: ${e.message}" }
                    }
                }, delay)
            } else {
                val stopIntent = Intent(context, DiscordRPCService::class.java).apply {
                    action = STOP_SERVICE
                }
                try {
                    context.startService(stopIntent)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to stop Discord RPC service: ${e.message}" }
                }
            }
        }

        fun restart(context: Context, connectionsManager: ConnectionsManager = Injekt.get()) {
            val token = connectionsPreferences.connectionsToken(connectionsManager.discord).get()
            if (connectionsPreferences.enableDiscordRPC().get() && token.isNotBlank()) {
                val restartIntent = Intent(context, DiscordRPCService::class.java).apply {
                    action = ACTION_RESTART
                }
                try {
                    context.startForegroundService(restartIntent)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to send restart intent: ${e.message}" }
                    stop(context, 0L)
                    handler.postDelayed({ start(context, connectionsManager) }, 1000L)
                }
            } else if (token.isBlank()) {
                logcat(LogPriority.WARN) { "Discord RPC not started due to missing token" }
                connectionsPreferences.enableDiscordRPC().set(false)
            }
        }

        private var since = 0L

        private var lastUsedScreen = DiscordScreen.APP
            set(value) {
                if (value !in listOf(DiscordScreen.MANGA, DiscordScreen.NOVEL, DiscordScreen.WEBVIEW)) {
                    field = value
                }
            }

        private const val MP_PREFIX = "mp:"
        private const val EXTERNAL_PREFIX = "external/"
        private val json = Json {
            encodeDefaults = true
            allowStructuredMapKeys = true
            ignoreUnknownKeys = true
        }

        private const val TAG = "DiscordRPCService"

        internal suspend fun setScreen(
            context: Context,
            discordScreen: DiscordScreen = lastUsedScreen,
            readerData: ReaderData = ReaderData(),
            sinceTime: Long = since,
        ) {
            rpc ?: return
            handler.removeCallbacksAndMessages(null)

            lastUsedScreen = discordScreen

            val showProgress = connectionsPreferences.discordShowProgress().get()
            val showTimestamp = connectionsPreferences.discordShowTimestamp().get()

            val (title, state, imageUrl) = when (discordScreen) {
                DiscordScreen.MANGA, DiscordScreen.NOVEL -> Triple(
                    readerData.mangaTitle,
                    readerData.chapterNumber.takeIf { showProgress },
                    readerData.thumbnailUrl ?: discordScreen.imageUrl,
                )
                else -> Triple(
                    null,
                    context.stringResource(discordScreen.text),
                    discordScreen.imageUrl,
                )
            }

            val timestamps = if (showTimestamp) {
                when (discordScreen) {
                    DiscordScreen.MANGA, DiscordScreen.NOVEL -> Activity.Timestamps(
                        start = readerData.startTimestamp ?: since,
                    )
                    else -> Activity.Timestamps(start = sinceTime)
                }
            } else {
                null
            }

            updateDiscordRPC(
                context = context,
                discordScreen = discordScreen,
                title = title,
                state = state,
                imageUrl = imageUrl,
                timestamps = timestamps,
            )
        }

        private suspend fun updateDiscordRPC(
            context: Context,
            discordScreen: DiscordScreen,
            title: String? = null,
            state: String?,
            imageUrl: String,
            timestamps: Activity.Timestamps?,
            sinceTime: Long = since,
            appName: String = context.stringResource(MR.strings.app_name),
        ) {
            val customMessage = connectionsPreferences.discordCustomMessage().get()
            val showButtons = connectionsPreferences.discordShowButtons().get()
            val showDownloadButton = connectionsPreferences.discordShowDownloadButton().get()
            val showDiscordButton = connectionsPreferences.discordShowDiscordButton().get()

            val name = title ?: appName
            val details = customMessage.takeIf { it.isNotBlank() }
                ?: title
                ?: context.stringResource(discordScreen.details)

            val buttonLabels = mutableListOf<String>().apply {
                if (showButtons) {
                    if (showDownloadButton) add(context.stringResource(DOWNLOAD_BUTTON_LABEL_RES, appName))
                    if (showDiscordButton) add(DISCORD_BUTTON_LABEL)
                }
            }

            val buttonUrls = mutableListOf<String>().apply {
                if (showButtons) {
                    if (showDownloadButton) add(DOWNLOAD_BUTTON_URL)
                    if (showDiscordButton) add(DISCORD_BUTTON_URL)
                }
            }

            val metadata = if (buttonLabels.isNotEmpty()) {
                Activity.Metadata(buttonUrls = buttonUrls)
            } else {
                null
            }

            rpc?.updateRPC(
                activity = Activity(
                    name = name,
                    details = details,
                    state = state,
                    type = ActivityType.WATCHING.value,
                    timestamps = timestamps,
                    assets = Activity.Assets(
                        largeImage = "$MP_PREFIX$imageUrl",
                        smallImage = "$MP_PREFIX${DiscordScreen.APP.imageUrl}",
                        largeText = context.stringResource(
                            MR.strings.discord_status_description,
                            context.stringResource(discordScreen.details),
                            title ?: context.stringResource(discordScreen.text),
                        ),
                        smallText = context.stringResource(MR.strings.discord_app_description),
                    ),
                    buttons = buttonLabels.takeIf { it.isNotEmpty() },
                    metadata = metadata,
                ),
                since = sinceTime,
            )
        }

        internal suspend fun setReaderActivity(
            context: Context,
            readerData: ReaderData = ReaderData(),
            isNovel: Boolean = false,
        ) {
            if (rpc == null) {
                logcat(LogPriority.WARN) { "RPC client is null, skipping reader activity update" }
                return
            }

            if (readerData.thumbnailUrl == null || readerData.mangaId == null) {
                logcat(LogPriority.WARN) { "Missing required data for reader activity" }
                return
            }

            try {
                val categories = getCategories(readerData.mangaId)
                val discordIncognito = isIncognito(categories, readerData.incognitoMode)

                val title = readerData.mangaTitle.takeUnless { discordIncognito }
                val chapterNumber = getFormattedChapterNumber(context, readerData, discordIncognito)
                val (startTime, _) = getTimestamps(readerData)

                withIOContext {
                    val rpcExternalAsset = getRPCExternalAsset()
                    val thumbnail = getDiscordThumbnail(rpcExternalAsset, readerData.thumbnailUrl, discordIncognito)

                    discordScope.launchIO {
                        setScreen(
                            context = context,
                            discordScreen = if (isNovel) DiscordScreen.NOVEL else DiscordScreen.MANGA,
                            readerData = readerData.copy(
                                mangaTitle = title,
                                chapterNumber = chapterNumber,
                                thumbnailUrl = thumbnail,
                                startTimestamp = startTime,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error setting reader activity: ${e.message}" }
            }
        }

        private suspend fun getCategories(id: Long): List<String> =
            Injekt.get<GetCategories>()
                .await(id)
                .map { it.id.toString() }
                .ifEmpty { listOf(UNCATEGORIZED_ID.toString()) }

        private fun isIncognito(categories: List<String>, incognitoMode: Boolean): Boolean {
            val discordIncognitoMode = connectionsPreferences.discordRPCIncognito().get()
            val incognitoCategories = connectionsPreferences.discordRPCIncognitoCategories().get()
            val incognitoCategory = categories.fastAny { it in incognitoCategories }
            return discordIncognitoMode || incognitoMode || incognitoCategory
        }

        private fun getFormattedChapterNumber(context: Context, readerData: ReaderData, discordIncognito: Boolean): String? {
            if (discordIncognito) return null

            val chapterNumber = readerData.chapterNumber ?: return null
            val chapterNumberDouble = chapterNumber.toDoubleOrNull()
            val useChapterTitles = connectionsPreferences.useChapterTitles().get()

            return when {
                useChapterTitles || chapterNumberDouble == null -> chapterNumber
                ceil(chapterNumberDouble) == floor(chapterNumberDouble) -> {
                    context.stringResource(MR.strings.notification_chapters_single, "${chapterNumberDouble.toInt()}")
                }
                else -> context.stringResource(MR.strings.notification_chapters_single, chapterNumber)
            }
        }

        private fun getTimestamps(readerData: ReaderData): Pair<Long?, Long?> =
            Pair(
                readerData.startTimestamp ?: System.currentTimeMillis(),
                null,
            )

        private fun getRPCExternalAsset(): RPCExternalAsset {
            val connectionsManager: ConnectionsManager by injectLazy()
            val networkService: NetworkHelper by injectLazy()

            return RPCExternalAsset(
                applicationId = RICH_PRESENCE_APPLICATION_ID,
                token = connectionsPreferences.connectionsToken(connectionsManager.discord).get(),
                client = networkService.client,
                json = json,
            )
        }

        private suspend fun getDiscordThumbnail(
            rpcExternalAsset: RPCExternalAsset,
            thumbnailUrl: String?,
            incognito: Boolean,
        ): String? {
            if (incognito || thumbnailUrl == null) return null

            return try {
                rpcExternalAsset.getDiscordUri(thumbnailUrl)
                    ?.takeIf { !it.contains("external/Not Found") }
                    ?.let {
                        it.substringAfter("\"id\": \"")
                            .substringBefore("\"}")
                            .split(EXTERNAL_PREFIX)
                            .getOrNull(1)
                            ?.let { id -> "$EXTERNAL_PREFIX$id" }
                    }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error getting Discord URI: ${e.message}" }
                null
            }
        }
    }
}
