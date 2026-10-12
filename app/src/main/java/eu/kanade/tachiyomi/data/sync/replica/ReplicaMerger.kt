package eu.kanade.tachiyomi.data.sync.replica

/**
 * Result of merging remote replicas with local records.
 */
data class SyncChanges(
    val categoriesToUpsert: List<RCategory> = emptyList(),
    val favouritesToUpsert: List<RFavourite> = emptyList(),
    val historyToUpsert: List<RHistory> = emptyList(),
    val bookmarksToUpsert: List<RBookmark> = emptyList(),
    val trackingToUpsert: List<RTracking> = emptyList(),
    val prefsToUpsert: List<RPref> = emptyList(),
    val itemsToDelete: List<RTombstone> = emptyList(),
) {
    val isEmpty: Boolean
        get() = categoriesToUpsert.isEmpty() &&
            favouritesToUpsert.isEmpty() &&
            historyToUpsert.isEmpty() &&
            bookmarksToUpsert.isEmpty() &&
            trackingToUpsert.isEmpty() &&
            prefsToUpsert.isEmpty() &&
            itemsToDelete.isEmpty()
}

/**
 * Decentralized Last-Write-Wins (LWW) merger.
 * Compares record versions across all device replicas.
 * Deletions (Tombstones) win over live records on version ties.
 */
object ReplicaMerger {

    fun merge(
        local: Replica,
        remotes: List<Replica>,
        enabledContent: Set<SyncContent>,
        isJoining: Boolean,
    ): SyncChanges {
        val categories = if (SyncContent.FAVOURITES in enabledContent) {
            mergeSection(
                local = local.categories,
                localTombstones = local.tombstones.filter { it.table == "categories" },
                remotes = remotes.map { r -> r.categories to r.tombstones.filter { it.table == "categories" } },
                isJoining = isJoining,
            )
        } else SectionResult()

        val favourites = if (SyncContent.FAVOURITES in enabledContent) {
            mergeSection(
                local = local.favourites,
                localTombstones = local.tombstones.filter { it.table == "favourites" },
                remotes = remotes.map { r -> r.favourites to r.tombstones.filter { it.table == "favourites" } },
                isJoining = isJoining,
            )
        } else SectionResult()

        val history = if (SyncContent.HISTORY in enabledContent) {
            mergeSection(
                local = local.history,
                localTombstones = local.tombstones.filter { it.table == "history" },
                remotes = remotes.map { r -> r.history to r.tombstones.filter { it.table == "history" } },
                isJoining = isJoining,
            )
        } else SectionResult()

        val bookmarks = if (SyncContent.BOOKMARKS in enabledContent) {
            mergeSection(
                local = local.bookmarks,
                localTombstones = local.tombstones.filter { it.table == "bookmarks" },
                remotes = remotes.map { r -> r.bookmarks to r.tombstones.filter { it.table == "bookmarks" } },
                isJoining = isJoining,
            )
        } else SectionResult()

        val tracking = if (SyncContent.TRACKING in enabledContent) {
            mergeSection(
                local = local.tracking,
                localTombstones = local.tombstones.filter { it.table == "tracking" },
                remotes = remotes.map { r -> r.tracking to r.tombstones.filter { it.table == "tracking" } },
                isJoining = isJoining,
            )
        } else SectionResult()

        val prefs = if (SyncContent.SETTINGS in enabledContent) {
            mergeSection(
                local = local.prefs,
                localTombstones = local.tombstones.filter { it.table == "prefs" },
                remotes = remotes.map { r -> r.prefs to r.tombstones.filter { it.table == "prefs" } },
                isJoining = isJoining,
            )
        } else SectionResult()

        return SyncChanges(
            categoriesToUpsert = categories.toUpsert,
            favouritesToUpsert = favourites.toUpsert,
            historyToUpsert = history.toUpsert,
            bookmarksToUpsert = bookmarks.toUpsert,
            trackingToUpsert = tracking.toUpsert,
            prefsToUpsert = prefs.toUpsert,
            itemsToDelete = categories.toDelete + favourites.toDelete + history.toDelete + bookmarks.toDelete + tracking.toDelete + prefs.toDelete,
        )
    }

    private data class SectionResult<T : SyncRecord>(
        val toUpsert: List<T> = emptyList(),
        val toDelete: List<RTombstone> = emptyList(),
    )

    private fun <T : SyncRecord> mergeSection(
        local: List<T>,
        localTombstones: List<RTombstone>,
        remotes: List<Pair<List<T>, List<RTombstone>>>,
        isJoining: Boolean,
    ): SectionResult<T> {
        val localMap = local.associateBy { it.key }
        val localTombMap = localTombstones.associateBy { it.key }

        // Gather all keys across local and all remotes
        val allKeys = HashSet<String>()
        allKeys.addAll(localMap.keys)
        allKeys.addAll(localTombMap.keys)
        for ((remoteRecords, remoteTombs) in remotes) {
            allKeys.addAll(remoteRecords.map { it.key })
            allKeys.addAll(remoteTombs.map { it.key })
        }

        val toUpsert = ArrayList<T>()
        val toDelete = ArrayList<RTombstone>()

        for (key in allKeys) {
            val localRecord = localMap[key]
            val localTomb = localTombMap[key]

            // Determine local state: (version, isTombstone, record)
            var bestV = localRecord?.v ?: localTomb?.v ?: 0L
            var bestIsTomb = localTomb != null && (localRecord == null || localTomb.v >= localRecord.v)
            var bestRecord: T? = if (!bestIsTomb) localRecord else null
            var bestTomb: RTombstone? = if (bestIsTomb) localTomb else null

            var winnerChangedFromLocal = false

            for ((remoteRecords, remoteTombs) in remotes) {
                val candidateRecord = remoteRecords.find { it.key == key }
                val candidateTomb = remoteTombs.find { it.key == key }

                val candV = candidateRecord?.v ?: candidateTomb?.v ?: continue
                val candIsTomb = candidateTomb != null && (candidateRecord == null || candidateTomb.v >= candidateRecord.v)

                val shouldReplace = when {
                    candV > bestV -> true
                    candV < bestV -> false
                    // Tie-breaker:
                    isJoining && bestV == 0L -> true // Initial join adopts cloud state
                    candIsTomb && !bestIsTomb -> true // Deletion beats live on equal version
                    !candIsTomb && bestIsTomb -> false
                    else -> candidateRecord.toString() > (bestRecord?.toString() ?: "")
                }

                if (shouldReplace) {
                    bestV = candV
                    bestIsTomb = candIsTomb
                    bestRecord = if (!candIsTomb) candidateRecord else null
                    bestTomb = if (candIsTomb) candidateTomb else null
                    winnerChangedFromLocal = true
                }
            }

            if (winnerChangedFromLocal) {
                if (bestIsTomb && bestTomb != null) {
                    toDelete.add(bestTomb)
                } else if (!bestIsTomb && bestRecord != null) {
                    toUpsert.add(bestRecord)
                }
            }
        }

        return SectionResult(toUpsert, toDelete)
    }
}
