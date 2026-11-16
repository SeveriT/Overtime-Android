package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XmltvParserTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="yle1.fi"><display-name>Yle TV1</display-name></channel>
          <programme channel="yle1.fi" start="20261116180000 +0000" stop="20261116190000 +0000">
            <title>News</title><desc>The day's news.</desc>
          </programme>
          <programme channel="yle1.fi" start="20261116190000 +0000" stop="20261116200000 +0000">
            <title>Liiga: HIFK – Tappara</title>
          </programme>
        </tv>
    """.trimIndent()

    private val at1830 = XmltvParser.parseTime("20261116183000 +0000")

    @Test
    fun readsProgrammesAndMatchesByIdOrName() {
        val epg = XmltvParser.parse(xml.byteInputStream())
        val byId = Channel(
            "a",
            "s",
            "Whatever",
            "g",
            "u",
            epgId = "yle1.fi",
        )
        val byName = Channel(
            "b",
            "s",
            "Yle TV1 HD",
            "g",
            "u",
        )
        val (now, next) = epg.nowNext(
            byId,
            at1830,
        )
        assertEquals(
            "News",
            now?.title,
        )
        assertEquals(
            "The day's news.",
            now?.description,
        )
        assertEquals(
            "Liiga: HIFK – Tappara",
            next?.title,
        )
        assertEquals(
            "News",
            epg.nowNext(
                byName,
                at1830,
            ).first?.title,
        )
        assertNull(
            epg.nowNext(
                Channel(
                    "c",
                    "s",
                    "Unknown",
                    "g",
                    "u",
                ),
                at1830,
            ).first,
        )
    }

    @Test
    fun readsGzip() {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        assertEquals(
            2,
            XmltvParser.parse(bytes.inputStream()).programmes.getValue("yle1.fi").size,
        )
    }
}
