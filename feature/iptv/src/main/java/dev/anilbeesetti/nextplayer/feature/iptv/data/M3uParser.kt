package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel

/** Channels plus any XMLTV guide URLs the playlist's #EXTM3U header names. */
class M3uPlaylist(val channels: List<Channel>, val guideUrls: List<String>)

object M3uParser {
    private val attrRegex = Regex("([\\w-]+)=\"([^\"]*)\"")

    /** Header attributes that point at a guide; a value may list several URLs separated by commas. */
    private val guideAttributes = setOf(
        "x-tvg-url",
        "url-tvg",
        "tvg-url",
    )

    fun parsePlaylist(text: String, sourceId: String): M3uPlaylist {
        val header = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val guides = if (header.startsWith(
                "#EXTM3U",
                ignoreCase = true,
            )
        ) {
            attrRegex.findAll(header)
                .filter { it.groupValues[1].lowercase() in guideAttributes }
                .flatMap { it.groupValues[2].split(',') }
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .toList()
        } else {
            emptyList()
        }
        return M3uPlaylist(
            parse(
                text,
                sourceId,
            ),
            guides,
        )
    }

    fun parse(text: String, sourceId: String): List<Channel> {
        val channels = ArrayList<Channel>()
        var pending: PendingEntry? = null
        var extGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith(
                    "#EXTINF",
                    ignoreCase = true,
                ) -> {
                    val body = line.substringAfter(
                        ':',
                        "",
                    )
                    val attrs = attrRegex.findAll(body).associate {
                        it.groupValues[1].lowercase() to
                            it.groupValues[2]
                    }
                    val rest = attrRegex.replace(
                        body,
                        "",
                    )
                    val name = rest.substringAfter(
                        ',',
                        "",
                    ).trim().ifEmpty {
                        attrs["tvg-name"].orEmpty()
                    }
                    pending = PendingEntry(
                        name,
                        attrs,
                    )
                }

                line.startsWith(
                    "#EXTGRP:",
                    ignoreCase = true,
                ) ->
                    extGroup =
                        line.substringAfter(':').trim()

                line.startsWith("#") -> Unit

                else -> {
                    val entry = pending
                    if (entry != null) {
                        val group =
                            entry.attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup
                                ?: "Ungrouped"
                        channels += Channel(
                            id = "$sourceId:${line.hashCode().toUInt().toString(16)}",
                            sourceId = sourceId,
                            name = entry.name.ifEmpty { line.substringAfterLast('/') },
                            group = group,
                            url = line,
                            logo = entry.attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            epgId = entry.attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        )
                    }
                    pending = null
                    extGroup = null
                }
            }
        }
        return channels
    }

    private class PendingEntry(val name: String, val attrs: Map<String, String>)
}
