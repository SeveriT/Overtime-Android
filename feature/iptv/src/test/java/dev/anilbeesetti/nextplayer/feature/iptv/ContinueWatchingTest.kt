package dev.anilbeesetti.nextplayer.feature.iptv

import dev.anilbeesetti.nextplayer.feature.iptv.model.ContinueKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.PlaybackProgress
import dev.anilbeesetti.nextplayer.feature.iptv.model.WatchEntry
import dev.anilbeesetti.nextplayer.feature.iptv.model.continueWatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTest {
    private val movie = LibraryItem("m1", LibraryKind.MOVIES, "Movie")
    private val show = LibraryItem("s1", LibraryKind.SERIES, "Show")
    private val minute = 60_000L

    private fun ep(n: Int, playedAt: Long = 0) = WatchEntry("ep$n", "src", show, "Show E$n", season = 1, episode = n, playedAt = playedAt)

    @Test
    fun movieInProgressShowsAndFinishedMovieLeaves() {
        val entry = WatchEntry("m", "src", movie, "Movie", playedAt = 1)
        val going = continueWatching(listOf(entry), emptyMap(), mapOf("m" to PlaybackProgress(30 * minute, 100 * minute, 5)))
        assertEquals(ContinueKind.RESUME, going.single().kind)
        assertEquals(70 * minute, going.single().remainingMs)

        val done = continueWatching(listOf(entry), emptyMap(), mapOf("m" to PlaybackProgress(98 * minute, 100 * minute, 5)))
        assertTrue(done.isEmpty())
        val barely = continueWatching(listOf(entry), emptyMap(), mapOf("m" to PlaybackProgress(3_000, 100 * minute, 5)))
        assertTrue(barely.isEmpty())
    }

    @Test
    fun finishedEpisodeOffersTheNextWithTheRestOfTheSeason() {
        val entries = listOf(ep(1, playedAt = 1), ep(2), ep(3))
        val states = mapOf("ep1" to PlaybackProgress(-1, 40 * minute, 10))
        val item = continueWatching(entries, emptyMap(), states).single()
        assertEquals(ContinueKind.NEXT, item.kind)
        assertEquals(listOf("ep2", "ep3"), item.queue.map { it.url })
    }

    @Test
    fun autoplayedEpisodeIsTheOneToResume() {
        val entries = listOf(ep(1, playedAt = 1), ep(2), ep(3))
        val states = mapOf(
            "ep1" to PlaybackProgress(-1, 40 * minute, 10),
            "ep2" to PlaybackProgress(12 * minute, 40 * minute, 20),
        )
        val item = continueWatching(entries, emptyMap(), states).single()
        assertEquals(ContinueKind.RESUME, item.kind)
        assertEquals("ep2", item.entry.url)
        assertEquals(listOf("ep2", "ep3"), item.queue.map { it.url })
    }

    @Test
    fun lastEpisodeWatchedStaysUntilDismissedAndReturnsWhenPlayed() {
        val entries = listOf(ep(1, playedAt = 1))
        val states = mapOf("ep1" to PlaybackProgress(-1, 40 * minute, 10))
        assertEquals(ContinueKind.WATCHED, continueWatching(entries, emptyMap(), states).single().kind)
        assertTrue(continueWatching(entries, mapOf(entries[0].titleKey to 11L), states).isEmpty())
        val again = mapOf("ep1" to PlaybackProgress(5 * minute, 40 * minute, 12))
        assertEquals(ContinueKind.RESUME, continueWatching(entries, mapOf(entries[0].titleKey to 11L), again).single().kind)
    }
}
