package eu.kanade.tachiyomi.network.challenge

/**
 * Global singleton access point for the challenge resolver engine.
 * Allows core:common (OkHttp interceptors) to invoke UI-aware resolvers registered by the application.
 */
object ChallengeCoordinator {
    @Volatile
    var instance: ChallengeResolver? = null

    fun resolve(
        url: String,
        headers: Map<String, String>? = null,
        isManaged: Boolean = false,
        isCanceled: () -> Boolean = { false },
    ): Boolean {
        return instance?.resolve(url, headers, isManaged, isCanceled) ?: false
    }

    fun hasClearance(url: String): Boolean {
        return instance?.hasClearance(url) ?: false
    }
}
