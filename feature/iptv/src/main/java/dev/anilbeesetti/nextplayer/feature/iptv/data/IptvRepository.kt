package dev.anilbeesetti.nextplayer.feature.iptv.data

import android.content.Context
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.EpgData
import dev.anilbeesetti.nextplayer.feature.iptv.model.Episode
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryCategory
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.MovieDetails
import dev.anilbeesetti.nextplayer.feature.iptv.model.SeriesDetails
import dev.anilbeesetti.nextplayer.feature.iptv.model.SourceType
import dev.anilbeesetti.nextplayer.feature.iptv.model.WatchEntry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Single

/**
 * Sources, their channels and guides, and favourites. Everything is kept as files in the app's private
 * storage ("iptv/"): channels and guides are cached so the lists show at once, and refreshed on demand.
 */
@Single
class IptvRepository(context: Context) {
    private val dir = File(
        context.filesDir,
        "iptv",
    ).apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues =
            true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(
            15,
            TimeUnit.SECONDS,
        )
        .readTimeout(
            60,
            TimeUnit.SECONDS,
        )
        .build()
    private val http = HttpText { url -> fetchText(url) }
    private val xtream = XtreamClient(
        http,
        json,
    )

    private val _sources = MutableStateFlow(
        readList(
            SOURCES,
            IptvSource.serializer(),
        ),
    )
    val sources: StateFlow<List<IptvSource>> = _sources.asStateFlow()

    private val _channels = MutableStateFlow(
        _sources.value.associate {
            it.id to
                readList(
                    channelsFile(it.id),
                    Channel.serializer(),
                )
        },
    )
    val channels: StateFlow<Map<String, List<Channel>>> = _channels.asStateFlow()

    private val _epg = MutableStateFlow<Map<String, EpgData>>(emptyMap())
    val epg: StateFlow<Map<String, EpgData>> = _epg.asStateFlow()

    private val _favorites = MutableStateFlow(readSet(FAVORITES))
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** Starred channel groups as "<source id>|<group>": listed first, as on Windows. */
    private val _favoriteGroups = MutableStateFlow(readSet(FAVORITE_GROUPS))
    val favoriteGroups: StateFlow<Set<String>> = _favoriteGroups.asStateFlow()

    fun toggleFavoriteGroup(sourceId: String, group: String) {
        val key = "$sourceId|$group"
        _favoriteGroups.update { if (key in it) it - key else it + key }
        File(
            dir,
            FAVORITE_GROUPS,
        ).writeText(
            json.encodeToString(
                SetSerializer(String.serializer()),
                _favoriteGroups.value,
            ),
        )
    }

    // ---- continue watching -----------------------------------------------------------

    private val _watching = MutableStateFlow(
        readList(
            WATCHING,
            WatchEntry.serializer(),
        ),
    )

    /** Movies and episodes sent to the player, oldest first. */
    val watching: StateFlow<List<WatchEntry>> = _watching.asStateFlow()

    private val _dismissed = MutableStateFlow(
        runCatching {
            json.decodeFromString(
                MapSerializer(
                    String.serializer(),
                    Long.serializer(),
                ),
                File(
                    dir,
                    DISMISSED,
                ).readText(),
            )
        }.getOrDefault(emptyMap()),
    )

    /** Titles removed from Continue watching, and when: they come back once played again. */
    val dismissed: StateFlow<Map<String, Long>> = _dismissed.asStateFlow()

    /** Remembers what's being played: the picked item has a [WatchEntry.playedAt], queued ones keep theirs. */
    @Synchronized
    fun recordWatching(entries: List<WatchEntry>) {
        if (entries.isEmpty()) return
        val old = _watching.value.associateBy { it.url }
        val updated = entries.map { entry ->
            old[entry.url]?.takeIf {
                entry.playedAt == 0L
            }?.let { entry.copy(playedAt = it.playedAt) }
                ?: entry
        }
        val urls = updated.mapTo(HashSet()) { it.url }
        val list = (_watching.value.filterNot { it.url in urls } + updated).takeLast(MAX_WATCHING)
        _watching.value = list
        writeList(
            WATCHING,
            ListSerializer(WatchEntry.serializer()),
            list,
        )
    }

    fun dismissWatching(titleKey: String) {
        _dismissed.update { it + (titleKey to System.currentTimeMillis()) }
        File(
            dir,
            DISMISSED,
        ).writeText(
            json.encodeToString(
                MapSerializer(
                    String.serializer(),
                    Long.serializer(),
                ),
                _dismissed.value,
            ),
        )
    }

    fun newSourceId(): String = UUID.randomUUID().toString().take(8)

    private var launchRefreshDone = false

    /** True the first time it's asked in this app process: the moment to refresh everything once. */
    @Synchronized
    fun claimLaunchRefresh(): Boolean {
        if (launchRefreshDone || _sources.value.isEmpty()) return false
        launchRefreshDone = true
        return true
    }

    fun saveSource(source: IptvSource) {
        _sources.update { list ->
            if (list.any { it.id == source.id }) {
                list.map {
                    if (it.id ==
                        source.id
                    ) {
                        source
                    } else {
                        it
                    }
                }
            } else {
                list + source
            }
        }
        writeList(
            SOURCES,
            ListSerializer(IptvSource.serializer()),
            _sources.value,
        )
    }

    /** Puts the sources in the order of [ids] (the order they're listed and switched between). */
    fun reorderSources(ids: List<String>) {
        val byId = _sources.value.associateBy { it.id }
        val ordered = ids.mapNotNull(byId::get) + _sources.value.filterNot { it.id in ids }
        if (ordered == _sources.value) return
        _sources.value = ordered
        writeList(
            SOURCES,
            ListSerializer(IptvSource.serializer()),
            ordered,
        )
    }

    fun removeSource(id: String) {
        _sources.update { list -> list.filterNot { it.id == id } }
        writeList(
            SOURCES,
            ListSerializer(IptvSource.serializer()),
            _sources.value,
        )
        _channels.update { it - id }
        _epg.update { it - id }
        File(
            dir,
            channelsFile(id),
        ).delete()
        dir.listFiles { file -> file.name.startsWith("epg_${id}_") }?.forEach { it.delete() }
    }

    fun toggleFavorite(channelId: String) {
        _favorites.update { if (channelId in it) it - channelId else it + channelId }
        File(
            dir,
            FAVORITES,
        ).writeText(
            json.encodeToString(
                SetSerializer(String.serializer()),
                _favorites.value,
            ),
        )
    }

    // ---- movies & series (Xtream sources) -------------------------------------------

    private val categoryCache = HashMap<Pair<String, LibraryKind>, List<LibraryCategory>>()
    private val itemCache = HashMap<Triple<String, LibraryKind, String?>, List<LibraryItem>>()

    /** Sources that have a movie & series library. */
    val librarySources: List<IptvSource> get() = _sources.value.filter {
        it.type ==
            SourceType.XTREAM
    }

    suspend fun categories(source: IptvSource, kind: LibraryKind): List<LibraryCategory> =
        withContext(Dispatchers.IO) {
            categoryCache.getOrPut(source.id to kind) {
                xtream.categories(
                    source,
                    kind,
                )
            }
        }

    /** Titles of a category, or the whole library (for search) when [categoryId] is null. Kept for the session. */
    suspend fun libraryItems(
        source: IptvSource,
        kind: LibraryKind,
        categoryId: String?,
    ): List<LibraryItem> = withContext(Dispatchers.IO) {
        itemCache.getOrPut(
            Triple(
                source.id,
                kind,
                categoryId,
            ),
        ) {
            xtream.items(
                source,
                kind,
                categoryId,
            )
        }
    }

    suspend fun movieDetails(source: IptvSource, item: LibraryItem): MovieDetails =
        withContext(Dispatchers.IO) {
            xtream.movieDetails(
                source,
                item,
            )
        }

    suspend fun seriesDetails(source: IptvSource, item: LibraryItem): SeriesDetails =
        withContext(Dispatchers.IO) {
            xtream.seriesDetails(
                source,
                item,
            )
        }

    fun movieUrl(source: IptvSource, item: LibraryItem): String = xtream.movieUrl(
        source,
        item,
    )

    fun episodeUrl(source: IptvSource, episode: Episode): String = xtream.episodeUrl(
        source,
        episode,
    )

    /** Downloads a source's channels (and the guides its playlist names); throws with a readable message on failure. */
    suspend fun refreshChannels(source: IptvSource): Unit = withContext(Dispatchers.IO) {
        val (channels, guides) = when (source.type) {
            SourceType.M3U_URL -> M3uParser.parsePlaylist(
                fetchText(source.url.trim()),
                source.id,
            ).let {
                it.channels to
                    it.guideUrls
            }

            SourceType.XTREAM -> xtream.liveChannels(source) to emptyList()
        }
        // An error page parses as an empty playlist: never replace channels with nothing.
        if (channels.isEmpty()) throw IOException("No channels found at this address")
        File(
            dir,
            channelsFile(source.id),
        ).writeText(
            json.encodeToString(
                ListSerializer(Channel.serializer()),
                channels,
            ),
        )
        File(
            dir,
            guidesFile(source.id),
        ).writeText(guides.joinToString("\n"))
        _channels.update { it + (source.id to channels) }
    }

    /**
     * Downloads the guides for a source, unless the cached copy is younger than [maxAgeMs]: guide hosts
     * often limit downloads per day (m3u4u answers "download limit reached" instead of the guide).
     * A guide that fails keeps its last good copy. Returns why no guide could be had, or null.
     */
    suspend fun refreshGuide(source: IptvSource, maxAgeMs: Long = GUIDE_MAX_AGE_MS): String? =
        withContext(Dispatchers.IO) {
            val urls = guideUrls(source)
            var merged = EpgData()
            var problem: String? = null
            urls.forEachIndexed { index, url ->
                val file = File(
                    dir,
                    "epg_${source.id}_$index.xml",
                )
                val fresh =
                    file.isFile && System.currentTimeMillis() - file.lastModified() < maxAgeMs
                val downloaded = if (fresh) {
                    null
                } else {
                    runCatching {
                        val bytes = fetchBytes(url)
                        val parsed = XmltvParser.parse(bytes.inputStream())
                        if (parsed.isEmpty) throw IOException(notAGuide(bytes))
                        file.writeBytes(bytes)
                        parsed
                    }.onFailure {
                        if (problem ==
                            null
                        ) {
                            problem = it.message ?: it::class.simpleName
                        }
                    }.getOrNull()
                }
                (
                    downloaded ?: runCatching {
                        file.inputStream().use(XmltvParser::parse)
                    }.getOrNull()
                    )?.let {
                    merged +=
                        it
                }
            }
            _epg.update { it + (source.id to merged) }
            problem.takeIf { merged.isEmpty }
        }

    /** What a guide host sent instead of a guide: the text of its page (e.g. a download-limit notice). */
    private fun notAGuide(bytes: ByteArray): String {
        val text = runCatching {
            bytes.copyOf(
                minOf(
                    bytes.size,
                    2_000,
                ),
            ).decodeToString()
        }.getOrDefault("")
        val readable = text.replace(
            Regex("<[^>]*>"),
            " ",
        ).replace(
            Regex("\\s+"),
            " ",
        ).trim().take(160)
        return if (readable.isNotEmpty() &&
            !text.trimStart().startsWith("<?xml")
        ) {
            "Guide server says: $readable"
        } else {
            "The guide was empty"
        }
    }

    /** Loads the cached guides into memory (at start, so now/next shows before any download). */
    suspend fun loadCachedGuides(): Unit = withContext(Dispatchers.IO) {
        _sources.value.forEach { source ->
            val files = dir.listFiles { file ->
                file.name.startsWith("epg_${source.id}_")
            }.orEmpty()
            val merged = files.mapNotNull { file ->
                runCatching { file.inputStream().use(XmltvParser::parse) }.getOrNull()
            }
                .fold(EpgData()) { all, next -> all + next }
            if (!merged.isEmpty) _epg.update { it + (source.id to merged) }
        }
    }

    private fun guideUrls(source: IptvSource): List<String> {
        val own = source.epgUrl.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (own.isNotEmpty()) return own
        return when (source.type) {
            SourceType.XTREAM -> listOf(xtream.epgUrl(source))

            SourceType.M3U_URL -> runCatching {
                File(
                    dir,
                    guidesFile(source.id),
                ).readLines()
            }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        }
    }

    private fun fetchText(url: String): String = fetchBytes(url).decodeToString()

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header(
            "User-Agent",
            USER_AGENT,
        ).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("Empty response")
        }
    }

    private fun <T> readList(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> = runCatching {
        json.decodeFromString(
            ListSerializer(serializer),
            File(
                dir,
                name,
            ).readText(),
        )
    }.getOrDefault(emptyList())

    private fun <T> writeList(
        name: String,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
        list: List<T>,
    ) = File(
        dir,
        name,
    ).writeText(
        json.encodeToString(
            serializer,
            list,
        ),
    )

    private fun readSet(name: String): Set<String> = runCatching {
        json.decodeFromString(
            SetSerializer(String.serializer()),
            File(
                dir,
                name,
            ).readText(),
        )
    }.getOrDefault(emptySet())

    private fun channelsFile(id: String) = "channels_$id.json"
    private fun guidesFile(id: String) = "guides_$id.txt"

    companion object {
        /** Many IPTV providers only answer media players; this is what they generally accept. */
        const val USER_AGENT = "VLC/3.0.24 LibVLC/3.0.24"
        private const val SOURCES = "sources.json"
        private const val FAVORITES = "favorites.json"

        /** How long a downloaded guide is used before the automatic refresh fetches it again. */
        const val GUIDE_MAX_AGE_MS = 12 * 60 * 60_000L

        /** A manual refresh re-downloads guides older than this. */
        const val GUIDE_MANUAL_MAX_AGE_MS = 60 * 60_000L
        private const val FAVORITE_GROUPS = "favorite_groups.json"
        private const val WATCHING = "watching.json"
        private const val DISMISSED = "watching_dismissed.json"

        /** Remembered movies and episodes; the oldest are forgotten past this. */
        private const val MAX_WATCHING = 3_000
    }
}
