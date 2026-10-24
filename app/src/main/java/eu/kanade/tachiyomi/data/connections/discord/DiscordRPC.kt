package eu.kanade.tachiyomi.data.connections.discord

class DiscordRPC(val token: String, val status: String) {
    private val discordWebSocket: DiscordWebSocket = DiscordWebSocketImpl(token)
    private var rpc: Presence? = null

    fun closeRPC() {
        discordWebSocket.close()
    }

    suspend fun updateRPC(
        activity: Activity,
        since: Long? = null,
    ) {
        rpc = Presence(
            activities = listOf(activity),
            afk = status != "online",
            since = since,
            status = status,
        )

        rpc?.let { discordWebSocket.sendActivity(it) }
    }
}
