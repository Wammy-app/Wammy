package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpaceLibraryTypeTest {

    @Test
    fun `space separated single library matches active space`() {
        assertEquals(
            LibraryViewModel.LibraryType.Manga,
            resolveSpaceLibraryType(hideMangaUi = false, isJoined = true, isSpaceSeparated = true, homeTabIsNovel = false),
        )
        assertEquals(
            LibraryViewModel.LibraryType.Novel,
            resolveSpaceLibraryType(hideMangaUi = false, isJoined = true, isSpaceSeparated = true, homeTabIsNovel = true),
        )
    }

    @Test
    fun `disabling space separation combines manga and novels`() {
        assertEquals(
            LibraryViewModel.LibraryType.All,
            resolveSpaceLibraryType(hideMangaUi = false, isJoined = true, isSpaceSeparated = false, homeTabIsNovel = false),
        )
        assertEquals(
            LibraryViewModel.LibraryType.All,
            resolveSpaceLibraryType(hideMangaUi = false, isJoined = true, isSpaceSeparated = false, homeTabIsNovel = true),
        )
    }

    @Test
    fun `hideMangaUi or split tabs keep NovelsTab strictly Novel`() {
        assertEquals(
            LibraryViewModel.LibraryType.Novel,
            resolveSpaceLibraryType(hideMangaUi = true, isJoined = true, isSpaceSeparated = true, homeTabIsNovel = false),
        )
        assertEquals(
            LibraryViewModel.LibraryType.Novel,
            resolveSpaceLibraryType(hideMangaUi = true, isJoined = true, isSpaceSeparated = false, homeTabIsNovel = false),
        )
        assertEquals(
            LibraryViewModel.LibraryType.Novel,
            resolveSpaceLibraryType(hideMangaUi = false, isJoined = false, isSpaceSeparated = true, homeTabIsNovel = false),
        )
    }
}
