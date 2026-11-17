package dev.anilbeesetti.nextplayer.feature.iptv

import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.EpgData
import dev.anilbeesetti.nextplayer.feature.iptv.model.Programme
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgMatchingTest {
    @Test
    fun tvgIdMatchesRegardlessOfCase() {
        // m3u4u's playlist says "jim.fi" while its guide says "Jim.fi".
        val programme = Programme(
            channelId = "Jim.fi",
            start = 0,
            stop = 60_000,
            title = "Uutiset",
        )
        val guide = EpgData(
            nameToId = mapOf("jim.fi" to "Jim.fi"),
            programmes = mapOf("Jim.fi" to listOf(programme)),
        )
        val channel = Channel(
            id = "s:1",
            sourceId = "s",
            name = "JIM HD",
            group = "TV",
            url = "http://x/1.ts",
            epgId = "jim.fi",
        )
        assertEquals(
            "Jim.fi",
            guide.resolveId(channel),
        )
    }

    @Test
    fun tvgIdMatchesRegardlessOfSpaces() {
        fun guideWith(id: String) = EpgData(
            programmes = mapOf(
                id to listOf(
                    Programme(
                        channelId = id,
                        start = 0,
                        stop = 60_000,
                        title = "Sport",
                    ),
                ),
            ),
        )
        fun channel(epgId: String) = Channel(
            id = "s:2",
            sourceId = "s",
            name = "Channel",
            group = "Sport",
            url = "http://x/2.ts",
            epgId = epgId,
        )
        // Playlist with spaces, guide without, and the other way round.
        assertEquals(
            "Eurosport1.fi",
            guideWith("Eurosport1.fi").resolveId(channel("Eurosport 1.fi")),
        )
        assertEquals(
            "BBC Three HD.uk",
            guideWith("BBC Three HD.uk").resolveId(channel("BBCThreeHD.uk")),
        )
    }
}
