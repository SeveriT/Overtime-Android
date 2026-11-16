package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.Episode
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryCategory
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.MovieDetails
import dev.anilbeesetti.nextplayer.feature.iptv.model.Season
import dev.anilbeesetti.nextplayer.feature.iptv.model.SeriesDetails
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Xtream Codes "player_api" client for live channels. Panels differ a lot (numbers as strings, nulls,
 * empty objects as arrays), so responses are read leniently.
 */
class XtreamClient(private val http: HttpText, private val json: Json) {

    fun liveChannels(source: IptvSource): List<Channel> {
        val categories = fetchArray(
            apiUrl(
                source,
                "get_live_categories",
            ),
        )
            .associate { it.str("category_id") to it.str("category_name") }
        return fetchArray(
            apiUrl(
                source,
                "get_live_streams",
            ),
        ).mapNotNull { obj ->
            val streamId = obj.str("stream_id").ifEmpty { return@mapNotNull null }
            Channel(
                id = "${source.id}:$streamId",
                sourceId = source.id,
                name = obj.str("name"),
                group = categories[obj.str("category_id")] ?: "Other",
                url = "${base(
                    source,
                )}/live/${encode(source.username)}/${encode(source.password)}/$streamId.ts",
                logo = obj.str("stream_icon").ifBlank { null },
                epgId = obj.str("epg_channel_id").ifBlank { null },
            )
        }
    }

    // ---- movies & series --------------------------------------------------------------

    fun categories(source: IptvSource, kind: LibraryKind): List<LibraryCategory> {
        val action = if (kind ==
            LibraryKind.MOVIES
        ) {
            "get_vod_categories"
        } else {
            "get_series_categories"
        }
        return fetchArray(
            apiUrl(
                source,
                action,
            ),
        )
            .map {
                LibraryCategory(
                    it.str("category_id"),
                    it.str("category_name"),
                )
            }
            .filter { it.id.isNotEmpty() }
    }

    /** Titles in [categoryId], or the whole library when null. */
    fun items(source: IptvSource, kind: LibraryKind, categoryId: String?): List<LibraryItem> {
        val action = if (kind == LibraryKind.MOVIES) "get_vod_streams" else "get_series"
        val url = apiUrl(
            source,
            action,
        ) + (categoryId?.let { "&category_id=${encode(it)}" } ?: "")
        return fetchArray(url).mapNotNull {
            parseItem(
                it,
                kind,
            )
        }
    }

    fun movieDetails(source: IptvSource, item: LibraryItem): MovieDetails {
        val info =
            fetchObject(
                apiUrl(
                    source,
                    "get_vod_info",
                ) + "&vod_id=${item.id}",
            ).obj("info")
                ?: JsonObject(emptyMap())
        return MovieDetails(
            plot = info.firstOf(
                "plot",
                "description",
            ),
            genre = info.firstOf("genre"),
            cast = info.firstOf(
                "cast",
                "actors",
            ),
            director = info.firstOf("director"),
            releaseDate = info.firstOf(
                "releasedate",
                "release_date",
            ),
            duration = info.firstOf("duration")?.let(::tidyDuration),
            backdrop = info.firstImage("backdrop_path") ?: info.firstOf(
                "movie_image",
                "cover_big",
            ),
        )
    }

    fun seriesDetails(source: IptvSource, item: LibraryItem): SeriesDetails {
        val root = fetchObject(
            apiUrl(
                source,
                "get_series_info",
            ) + "&series_id=${item.id}",
        )
        val info = root.obj("info") ?: JsonObject(emptyMap())
        val seasonNames = (root["seasons"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { it.str("season_number").toIntOrNull() to it.str("name") }
        return SeriesDetails(
            plot = info.firstOf(
                "plot",
                "description",
            ),
            genre = info.firstOf("genre"),
            cast = info.firstOf("cast"),
            releaseDate = info.firstOf(
                "releaseDate",
                "release_date",
                "releasedate",
            ),
            backdrop = info.firstImage("backdrop_path") ?: info.firstOf("cover"),
            seasons = parseEpisodes(root["episodes"])
                .groupBy { it.first }
                .toSortedMap()
                .map { (number, episodes) ->
                    Season(
                        number,
                        seasonNames[number]?.ifBlank {
                            null
                        } ?: "Season $number",
                        episodes.map { it.second }.sortedBy { it.number },
                    )
                },
        )
    }

    fun movieUrl(source: IptvSource, item: LibraryItem): String = "${base(
        source,
    )}/movie/${encode(
        source.username,
    )}/${encode(source.password)}/${item.id}.${item.extension ?: "mp4"}"

    fun episodeUrl(source: IptvSource, episode: Episode): String = "${base(
        source,
    )}/series/${encode(
        source.username,
    )}/${encode(source.password)}/${episode.id}.${episode.extension}"

    internal fun parseItem(obj: JsonObject, kind: LibraryKind): LibraryItem? {
        val id = obj.str(
            if (kind ==
                LibraryKind.MOVIES
            ) {
                "stream_id"
            } else {
                "series_id"
            },
        ).ifEmpty { return null }
        val name = obj.str("name").ifEmpty { obj.str("title") }
        return LibraryItem(
            id = id,
            kind = kind,
            name = name,
            poster = obj.firstOf(
                "stream_icon",
                "cover",
            ),
            rating = obj.firstOf("rating")?.takeUnless { it == "0" || it == "0.0" },
            year =
                obj.firstOf("year")?.take(4) ?: YEAR.find(name)?.groupValues?.get(1)
                    ?: obj.firstOf(
                        "releaseDate",
                        "release_date",
                    )?.take(4),
            categoryId = obj.str("category_id"),
            extension = obj.firstOf("container_extension"),
            backdrop = obj.firstImage("backdrop_path"),
        )
    }

    /** (season, episode) pairs from either `{"1": [...], "2": [...]}` or `[[...], [...]]`. */
    internal fun parseEpisodes(element: JsonElement?): List<Pair<Int, Episode>> {
        val groups: List<Pair<Int?, JsonElement>> = when (element) {
            is JsonObject -> element.entries.map { (key, value) -> key.toIntOrNull() to value }
            is JsonArray -> element.mapIndexed { index, value -> (index + 1) to value }
            else -> emptyList()
        }
        return groups.flatMap { (seasonKey, list) ->
            (list as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { ep ->
                val id = ep.str("id").ifEmpty { return@mapNotNull null }
                val info = ep.obj("info")
                (ep.str("season").toIntOrNull() ?: seasonKey ?: 1) to Episode(
                    id = id,
                    number = ep.str("episode_num").toIntOrNull() ?: 0,
                    title = ep.str("title"),
                    extension = ep.firstOf("container_extension") ?: "mp4",
                    duration = info?.firstOf("duration")?.let(::tidyDuration),
                    plot = info?.firstOf(
                        "plot",
                        "description",
                    ),
                    image = info?.firstOf(
                        "movie_image",
                        "cover_big",
                    ),
                )
            }
        }
    }

    private fun fetchObject(url: String): JsonObject =
        json.parseToJsonElement(http.get(url)) as? JsonObject
            ?: throw IllegalStateException("Unexpected Xtream response")

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.firstOf(vararg keys: String): String? = keys.firstNotNullOfOrNull {
        str(it).ifBlank { null }
    }

    /** backdrop_path is an array of URLs on most panels, a plain string on some. */
    private fun JsonObject.firstImage(key: String): String? = when (val value = get(key)) {
        is JsonArray -> value.firstNotNullOfOrNull {
            (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content?.ifBlank { null }
        }

        else -> str(key).ifBlank { null }
    }

    /** "01:52:31" -> "1 h 52 min"; anything else is kept as is. */
    private fun tidyDuration(raw: String): String {
        val parts = raw.split(':').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return raw
        val (h, m) = parts
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> raw
        }
    }

    /** The panel's own XMLTV guide. */
    fun epgUrl(source: IptvSource): String = "${base(
        source,
    )}/xmltv.php?username=${encode(source.username)}&password=${encode(source.password)}"

    private fun base(source: IptvSource) = source.url.trim().trimEnd('/')

    private fun apiUrl(source: IptvSource, action: String) = "${base(
        source,
    )}/player_api.php?username=${encode(
        source.username,
    )}&password=${encode(source.password)}&action=$action"

    private fun fetchArray(url: String): List<JsonObject> {
        val element = json.parseToJsonElement(http.get(url))
        if (element !is JsonArray) {
            val auth = ((element as? JsonObject)?.get("user_info") as? JsonObject)?.str("auth")
            throw IllegalStateException(
                if (auth ==
                    "0"
                ) {
                    "Xtream login rejected"
                } else {
                    "Unexpected Xtream response"
                },
            )
        }
        return element.mapNotNull { it as? JsonObject }
    }

    private fun encode(value: String) = URLEncoder.encode(
        value,
        Charsets.UTF_8.name(),
    )

    private companion object {
        val YEAR = Regex("""\((\d{4})\)""")
    }

    private fun JsonObject.str(key: String): String =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim().orEmpty()
}

/** Fetches text over HTTP; an interface so parsing can be tested without a network. */
fun interface HttpText {
    fun get(url: String): String
}
