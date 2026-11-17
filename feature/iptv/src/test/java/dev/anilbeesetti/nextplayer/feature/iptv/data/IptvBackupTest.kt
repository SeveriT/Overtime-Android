package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.SourceType
import dev.anilbeesetti.nextplayer.feature.iptv.model.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IptvBackupTest {
    private val repo = IptvRepository(RuntimeEnvironment.getApplication())

    @Test
    fun sourcesOnlyWithCredentials() {
        repo.saveSource(
            IptvSource(
                id = "s1",
                name = "Demo",
                type = SourceType.M3U_URL,
                url = "http://example.invalid/x.m3u",
            ),
        )
        assertFalse(repo.backup(includeSources = false).containsKey("sources"))
        assertTrue(repo.backup(includeSources = true).containsKey("sources"))
    }

    @Test
    fun favouritesAndContinueWatchingComeBack() {
        repo.toggleFavorite("ch1")
        repo.toggleFavoriteGroup(
            "s1",
            "Sport",
        )
        val movie = LibraryItem(
            id = "m1",
            kind = LibraryKind.MOVIES,
            name = "Movie",
        )
        repo.recordWatching(
            listOf(
                WatchEntry(
                    url = "http://x/m1.mkv",
                    sourceId = "s1",
                    item = movie,
                    title = "Movie",
                    playedAt = 5,
                ),
            ),
        )
        val backup = repo.backup(includeSources = false)

        repo.toggleFavorite("ch1")
        repo.toggleFavoriteGroup(
            "s1",
            "Sport",
        )
        repo.restore(backup)

        assertTrue("ch1" in repo.favorites.value)
        assertTrue("s1|Sport" in repo.favoriteGroups.value)
        assertEquals(
            listOf("http://x/m1.mkv"),
            repo.watching.value.map { it.url },
        )
    }
}
