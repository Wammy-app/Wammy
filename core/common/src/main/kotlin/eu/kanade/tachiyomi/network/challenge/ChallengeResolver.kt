package eu.kanade.tachiyomi.network.challenge

/**
 * Interface implemented by challenge-solving engines (e.g. BossChallengeCoordinator)
 * to automatically or interactively resolve bot defense challenges (Cloudflare Turnstile, DDoS-GUARD, etc.).
 */
interface ChallengeResolver {
    /**
     * Resolves a challenge for the given [url].
     * Blocks or suspends until resolution completes, returning true if resolved, false otherwise.
     */
    fun resolve(url: String, headers: Map<String, String>? = null): Boolean

    /**
     * Checks if the given [url] or host already has an active clearance cookie.
     */
    fun hasClearance(url: String): Boolean
}
