package dev.anilbeesetti.nextplayer.feature.iptv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private val playlist = """
        #EXTM3U x-tvg-url="http://epg.example/guide.xml.gz,http://epg.example/b.xml"
        #EXTINF:-1 tvg-id="yle1.fi" tvg-logo="http://logo/yle1.png" group-title="Finland",Yle TV1 HD
        http://stream.example/yle1.m3u8
        #EXTINF:-1,Plain Channel
        #EXTGRP:Misc
        http://stream.example/plain.ts
        #EXTINF:-1 tvg-name="Named Only",
        http://stream.example/named.ts
    """.trimIndent()

    @Test
    fun readsChannelsGroupsAndGuides() {
        val parsed = M3uParser.parsePlaylist(
            playlist,
            "src",
        )
        assertEquals(
            listOf(
                "http://epg.example/guide.xml.gz",
                "http://epg.example/b.xml",
            ),
            parsed.guideUrls,
        )
        assertEquals(
            3,
            parsed.channels.size,
        )
        val yle = parsed.channels[0]
        assertEquals(
            "Yle TV1 HD",
            yle.name,
        )
        assertEquals(
            "Finland",
            yle.group,
        )
        assertEquals(
            "yle1.fi",
            yle.epgId,
        )
        assertEquals(
            "http://logo/yle1.png",
            yle.logo,
        )
        assertEquals(
            "Misc",
            parsed.channels[1].group,
        )
        assertNull(parsed.channels[1].epgId)
        assertEquals(
            "Named Only",
            parsed.channels[2].name,
        )
    }
}
