package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Xtream Codes "player_api" client for live channels. Panels differ a lot (numbers as strings, nulls,
 * empty objects as arrays), so responses are read leniently.
 */
class XtreamClient(private val http: HttpText, private val json: Json) {

    fun liveChannels(source: IptvSource): List<Channel> {
        val categories = fetchArray(apiUrl(source, "get_live_categories"))
            .associate { it.str("category_id") to it.str("category_name") }
        return fetchArray(apiUrl(source, "get_live_streams")).mapNotNull { obj ->
            val streamId = obj.str("stream_id").ifEmpty { return@mapNotNull null }
            Channel(
                id = "${source.id}:$streamId",
                sourceId = source.id,
                name = obj.str("name"),
                group = categories[obj.str("category_id")] ?: "Other",
                url = "${base(source)}/live/${encode(source.username)}/${encode(source.password)}/$streamId.ts",
                logo = obj.str("stream_icon").ifBlank { null },
                epgId = obj.str("epg_channel_id").ifBlank { null },
            )
        }
    }

    /** The panel's own XMLTV guide. */
    fun epgUrl(source: IptvSource): String =
        "${base(source)}/xmltv.php?username=${encode(source.username)}&password=${encode(source.password)}"

    private fun base(source: IptvSource) = source.url.trim().trimEnd('/')

    private fun apiUrl(source: IptvSource, action: String) =
        "${base(source)}/player_api.php?username=${encode(source.username)}&password=${encode(source.password)}&action=$action"

    private fun fetchArray(url: String): List<JsonObject> {
        val element = json.parseToJsonElement(http.get(url))
        if (element !is JsonArray) {
            val auth = ((element as? JsonObject)?.get("user_info") as? JsonObject)?.str("auth")
            throw IllegalStateException(if (auth == "0") "Xtream login rejected" else "Unexpected Xtream response")
        }
        return element.mapNotNull { it as? JsonObject }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JsonObject.str(key: String): String =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim().orEmpty()
}

/** Fetches text over HTTP; an interface so parsing can be tested without a network. */
fun interface HttpText {
    fun get(url: String): String
}
