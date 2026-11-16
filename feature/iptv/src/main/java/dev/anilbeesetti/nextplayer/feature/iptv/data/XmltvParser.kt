package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.EpgData
import dev.anilbeesetti.nextplayer.feature.iptv.model.Programme
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

private val xmltvTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/**
 * Streaming XMLTV parser (multi-megabyte guides, no DOM). Reads plain or gzip-compressed XML, and
 * collects channel display names so channels without a tvg-id can still be matched by name.
 */
object XmltvParser {
    fun parse(input: InputStream): EpgData {
        val buffered = input.buffered()
        buffered.mark(2)
        val isGzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (isGzip) GZIPInputStream(buffered) else buffered

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(
            XmlPullParser.FEATURE_PROCESS_NAMESPACES,
            false,
        )
        parser.setInput(
            stream,
            null,
        )

        val nameToId = HashMap<String, String>()
        val programmes = HashMap<String, ArrayList<Programme>>()
        var channelId: String? = null
        var pChannel = ""
        var pStart = 0L
        var pStop = 0L
        val title = StringBuilder()
        val desc = StringBuilder()
        var text: StringBuilder? = null

        stream.use {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> channelId = parser.getAttributeValue(
                            null,
                            "id",
                        )

                        "display-name" -> if (channelId != null) text = StringBuilder()

                        "programme" -> {
                            pChannel = parser.getAttributeValue(
                                null,
                                "channel",
                            ).orEmpty()
                            pStart = parseTime(
                                parser.getAttributeValue(
                                    null,
                                    "start",
                                ),
                            )
                            pStop = parseTime(
                                parser.getAttributeValue(
                                    null,
                                    "stop",
                                ),
                            )
                            title.setLength(0)
                            desc.setLength(0)
                        }

                        "title" -> if (channelId == null) text = title

                        "desc" -> if (channelId == null) text = desc
                    }

                    XmlPullParser.TEXT -> text?.append(parser.text)

                    XmlPullParser.END_TAG -> when (parser.name) {
                        "display-name" -> {
                            val id = channelId
                            val name = text?.toString()?.trim().orEmpty()
                            if (id != null &&
                                name.isNotEmpty()
                            ) {
                                nameToId.putIfAbsent(
                                    name.lowercase(),
                                    id,
                                )
                            }
                            text = null
                        }

                        "channel" -> channelId = null

                        "title", "desc" -> text = null

                        "programme" -> if (pChannel.isNotEmpty() && pStart > 0 && pStop > pStart) {
                            programmes.getOrPut(pChannel) { ArrayList() } +=
                                Programme(
                                    pChannel,
                                    pStart,
                                    pStop,
                                    title.toString().trim(),
                                    desc.toString().trim(),
                                )
                        }
                    }
                }
                event = parser.next()
            }
        }
        programmes.values.forEach { it.sortBy(Programme::start) }
        return EpgData(
            nameToId = nameToId,
            programmes = programmes,
        )
    }

    /** "20261115203000 +0200" (offset and seconds optional) as epoch millis; 0 when unreadable. */
    internal fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        return runCatching {
            val digits = value.takeWhile(Char::isDigit).padEnd(
                14,
                '0',
            ).take(14)
            val local = LocalDateTime.parse(
                digits,
                xmltvTime,
            )
            val rest = value.drop(value.indexOfFirst { !it.isDigit() }.coerceAtLeast(0)).trim()
            val offset = if (rest.isEmpty() ||
                value.all(Char::isDigit)
            ) {
                ZoneId.systemDefault().rules.getOffset(local)
            } else {
                ZoneOffset.of(rest)
            }
            local.toInstant(offset).toEpochMilli()
        }.getOrDefault(0)
    }
}
