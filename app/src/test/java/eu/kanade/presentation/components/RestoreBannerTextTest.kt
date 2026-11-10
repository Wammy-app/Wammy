package eu.kanade.presentation.components

import eu.kanade.tachiyomi.data.backup.BackupNotifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RestoreBannerTextTest {

    @Test
    fun `formatRestoreBannerText shows ellipsis when indeterminate`() {
        assertEquals("Restoring backup...", formatRestoreBannerText("Restoring backup", 0, 0))
        assertEquals("Restoring backup...", formatRestoreBannerText("Restoring backup", 0, 100))
        assertEquals("Syncing library...", formatRestoreBannerText("Syncing library", 10, 0))
    }

    @Test
    fun `formatRestoreBannerText shows counts when progress and maxAmount are positive`() {
        assertEquals("Restoring backup (25/120)", formatRestoreBannerText("Restoring backup", 25, 120))
        assertEquals("Syncing library (1/10)", formatRestoreBannerText("Syncing library", 1, 10))
    }

    @Test
    fun `clearRestoreProgress resets flow to null`() {
        BackupNotifier.clearRestoreProgress()
        assertNull(BackupNotifier.restoreProgress.value)
    }
}
