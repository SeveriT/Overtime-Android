package dev.anilbeesetti.nextplayer.feature.iptv.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType { M3U_URL, XTREAM }

/** A place channels come from: a playlist URL, or an Xtream Codes server with a login. */
@Serializable
data class IptvSource(
    val id: String,
    val name: String,
    val type: SourceType,
    /** Playlist URL, or the Xtream server address. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** XMLTV guide URL(s), comma separated. Optional: Xtream and many playlists name their own. */
    val epgUrl: String = "",
)

@Serializable
data class Channel(
    val id: String,
    val sourceId: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String? = null,
    /** tvg-id / epg_channel_id, matched against the guide. */
    val epgId: String? = null,
)

@Serializable
data class Programme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
)

/** A parsed XMLTV guide. [nameToId] maps lower-cased display names to channel ids, for channels without a tvg-id. */
data class EpgData(
    val nameToId: Map<String, String> = emptyMap(),
    val programmes: Map<String, List<Programme>> = emptyMap(),
) {
    val isEmpty: Boolean get() = programmes.isEmpty()

    /** The guide's id for [channel]: its tvg-id first, then its name (also without an HD/FHD/UHD suffix). */
    fun resolveId(channel: Channel): String? {
        channel.epgId?.let { if (programmes.containsKey(it)) return it }
        val name = channel.name.lowercase()
        nameToId[name]?.let { return it }
        return nameToId[name.removeSuffix(" hd").removeSuffix(" fhd").removeSuffix(" uhd").trim()]
    }

    /** Two guides combined; programmes both list for a channel are merged by start time. */
    operator fun plus(other: EpgData): EpgData {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val merged = HashMap(programmes)
        other.programmes.forEach { (id, list) ->
            merged[id] = merged[id]?.let { mine -> (mine + list).distinctBy { it.start }.sortedBy { it.start } } ?: list
        }
        return EpgData(nameToId = other.nameToId + nameToId, programmes = merged)
    }

    /** Every programme the guide has for [channel], by start time. */
    fun programmesFor(channel: Channel): List<Programme> = resolveId(channel)?.let(programmes::get).orEmpty()

    /** What's on [channel] at [now] and what follows. */
    fun nowNext(channel: Channel, now: Long): Pair<Programme?, Programme?> {
        val list = resolveId(channel)?.let(programmes::get) ?: return null to null
        val search = list.binarySearchBy(now) { it.start }
        val index = if (search >= 0) search else -search - 2
        val current = list.getOrNull(index)?.takeIf { it.stop > now }
        val next = list.getOrNull(index + 1)
        return current to next
    }
}
