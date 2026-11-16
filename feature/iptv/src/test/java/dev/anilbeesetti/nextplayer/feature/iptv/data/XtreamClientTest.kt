package dev.anilbeesetti.nextplayer.feature.iptv.data

import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.SourceType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Panels answer in many shapes; the client reads them leniently. */
class XtreamClientTest {
    private val source = IptvSource("s", "Test", SourceType.XTREAM, "http://panel.example:8080/", "user", "p@ss")
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(responses: Map<String, String>) = XtreamClient(HttpText { url ->
        responses.entries.first { url.contains(it.key) }.value
    }, json)

    @Test
    fun liveChannelsWithCategoriesAndAddresses() {
        val channels = client(
            mapOf(
                "get_live_categories" to """[{"category_id":"1","category_name":"Sport"}]""",
                "get_live_streams" to """[{"stream_id":7,"name":"Viaplay Sport 1","category_id":"1","stream_icon":"","epg_channel_id":"vs1.fi"},{"name":"broken"}]""",
            ),
        ).liveChannels(source)
        assertEquals(1, channels.size)
        val channel = channels.single()
        assertEquals("Sport", channel.group)
        assertEquals("http://panel.example:8080/live/user/p%40ss/7.ts", channel.url)
        assertNull(channel.logo)
        assertEquals("vs1.fi", channel.epgId)
    }

    @Test
    fun moviesAndSeriesItems() {
        val movies = client(
            mapOf("get_vod_streams" to """[{"stream_id":"10","name":"Inception (2010)","rating":"8.8","container_extension":"mkv","stream_icon":"http://img/i.jpg"}]"""),
        ).items(source, LibraryKind.MOVIES, "5")
        val movie = movies.single()
        assertEquals("2010", movie.year)
        assertEquals("8.8", movie.rating)
        assertEquals("http://panel.example:8080/movie/user/p%40ss/10.mkv", client(emptyMap()).movieUrl(source, movie))

        val series = client(mapOf("get_series" to """[{"series_id":3,"name":"Dark","cover":"http://img/d.jpg","rating":"0","backdrop_path":["http://img/b.jpg"]}]"""))
            .items(source, LibraryKind.SERIES, null).single()
        assertNull(series.rating)
        assertEquals("http://img/b.jpg", series.backdrop)
    }

    @Test
    fun episodesKeyedBySeasonOrAsArrays() {
        val keyed = """{"info":{"plot":"Time travel."},"seasons":[{"season_number":1,"name":"Season One"}],
            "episodes":{"1":[{"id":"101","episode_num":"2","title":"Lies","container_extension":"mkv","info":{"duration":"00:51:00"}},
                             {"id":"100","episode_num":"1","title":"Secrets"}],"2":[{"id":"201","episode_num":1,"title":"Beginnings"}]}}"""
        val details = client(mapOf("get_series_info" to keyed)).seriesDetails(source, LibraryItem("3", LibraryKind.SERIES, "Dark"))
        assertEquals("Time travel.", details.plot)
        assertEquals(listOf("Season One", "Season 2"), details.seasons.map { it.name })
        assertEquals(listOf("Secrets", "Lies"), details.seasons.first().episodes.map { it.title })
        assertEquals("51 min", details.seasons.first().episodes.last().duration)

        val arrays = """{"info":{},"episodes":[[{"id":"1","episode_num":1,"title":"A"}],[{"id":"2","episode_num":1,"title":"B"}]]}"""
        assertEquals(listOf(1, 2), client(mapOf("get_series_info" to arrays)).seriesDetails(source, LibraryItem("x", LibraryKind.SERIES, "X")).seasons.map { it.number })
    }
}
