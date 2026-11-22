package dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.EpgData
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** The pseudo-group listing starred channels. */
const val GROUP_FAVORITES = "\u0000favourites"

/** The pseudo-group listing every channel of the source. */
const val GROUP_ALL = "\u0000all"

/** A row of the group list: a group, how many channels it has, and whether it's starred. */
data class GroupEntry(val name: String, val count: Int, val favorite: Boolean = false)

data class LiveTvUiState(
    val sources: List<IptvSource> = emptyList(),
    val sourceId: String? = null,
    /** All channels and Favourites, then starred groups, then the rest, each in playlist order. */
    val groups: List<GroupEntry> = emptyList(),
    /** The opened group; null shows the group list (as Windows' sidebar opens on it). */
    val group: String? = null,
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val epg: EpgData = EpgData(),
    val now: Long = System.currentTimeMillis(),
    val status: LiveTvStatus? = null,
    /** The programme guide grid instead of the channel list. */
    val showGuide: Boolean = false,
) {
    val busy: Boolean get() = status is LiveTvStatus.Loading
}

/** What the screen says under the search box while sources load, or after one failed. */
sealed interface LiveTvStatus {
    data class Loading(val source: String, val guide: Boolean, val quiet: Boolean) : LiveTvStatus
    data class Failed(val source: String, val message: String) : LiveTvStatus
}

@KoinViewModel
class LiveTvViewModel(private val repository: IptvRepository) : ViewModel() {
    /** The channel watched last and its group, for the resume button; null until one was watched. */
    val lastChannel: StateFlow<Pair<Channel, List<Channel>>?> = combine(
        repository.channels,
        repository.lastChannelUrl,
    ) { channels, url ->
        repository.lastChannel(
            channels,
            url,
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            repository.lastChannel(
                repository.channels.value,
                repository.lastChannelUrl.value,
            ),
        )

    private val selection = MutableStateFlow(Selection())
    private val status = MutableStateFlow<LiveTvStatus?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    private data class Selection(
        val sourceId: String? = null,
        val group: String? = null,
        val query: String = "",
        val guide: Boolean = false,
    )

    val state: StateFlow<LiveTvUiState> = combine(
        repository.sources,
        repository.channels,
        combine(
            repository.favorites,
            repository.favoriteGroups,
            repository.hidden,
        ) { f, g, h ->
            Triple(
                f,
                g,
                h,
            )
        },
        repository.epg,
        combine(
            selection,
            status,
            clock,
        ) { s, st, c ->
            Triple(
                s,
                st,
                c,
            )
        },
    ) { sources, channelsBySource, (favorites, favoriteGroups, hidden), epg, (sel, st, now) ->
        val sourceId =
            sel.sourceId?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
        // Hidden channels are left out of every group, Favourites, search and the guide.
        val all = sourceId?.let(channelsBySource::get).orEmpty().filterNot { it.id in hidden }
        val counts = LinkedHashMap<String, Int>()
        all.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val own = counts.map { (name, count) ->
            GroupEntry(
                name,
                count,
                favorite =
                    "$sourceId|$name" in favoriteGroups,
            )
        }
        val groups = listOf(
            GroupEntry(
                GROUP_ALL,
                all.size,
            ),
            GroupEntry(
                GROUP_FAVORITES,
                all.count { it.id in favorites },
            ),
        ) + own.filter { it.favorite } + own.filterNot { it.favorite }
        val group = sel.group?.takeIf { name -> groups.any { it.name == name } }
        val inGroup = when (group) {
            null, GROUP_ALL -> all
            GROUP_FAVORITES -> all.filter { it.id in favorites }
            else -> all.filter { it.group == group }
        }
        val q = sel.query.trim()
        // Searching from the group list covers every channel; inside a group, just that group.
        val shown = when {
            q.isNotEmpty() -> inGroup.filter {
                it.name.contains(
                    q,
                    ignoreCase = true,
                )
            }

            group == null -> emptyList()

            else -> inGroup
        }
        LiveTvUiState(
            sources = sources,
            sourceId = sourceId,
            groups = groups,
            group = group,
            query = sel.query,
            channels = shown,
            favorites = favorites,
            epg = sourceId?.let(epg::get) ?: EpgData(),
            now = now,
            status = st,
            showGuide = sel.guide,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LiveTvUiState(),
    )

    init {
        viewModelScope.launch {
            repository.loadCachedGuides()
            // Channels and guides go stale; reload them in the background once per launch.
            if (repository.claimLaunchRefresh()) refreshAll(quiet = true)
        }
        // Now/next and progress bars move on their own.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                clock.value = System.currentTimeMillis()
            }
        }
    }

    fun selectSource(id: String) {
        selection.value = Selection(
            sourceId = id,
            guide = selection.value.guide,
        )
    }

    fun backToGroups() {
        selection.value = selection.value.copy(
            group = null,
            query = "",
        )
    }

    fun toggleFavoriteGroup(group: String) {
        val sourceId = state.value.sourceId ?: return
        repository.toggleFavoriteGroup(
            sourceId,
            group,
        )
    }

    fun toggleGuide() {
        selection.value = selection.value.copy(guide = !selection.value.guide)
    }

    fun selectGroup(group: String) {
        selection.value = selection.value.copy(
            group = group,
            query = "",
        )
    }

    fun search(query: String) {
        selection.value = selection.value.copy(query = query)
    }

    fun toggleFavorite(channel: Channel) = repository.toggleFavorite(channel.id)

    /** Hides [channel] from Live TV; Settings > Hidden channels brings it back. */
    fun hide(channel: Channel) = repository.setHidden(
        channel.id,
        true,
    )

    /**
     * Reloads channels and guides of every source. [quiet] (the refresh at launch) says "Updating" and keeps
     * a failure to itself while saved channels are still there to watch.
     */
    fun refreshAll(quiet: Boolean = false) {
        viewModelScope.launch {
            var failure: LiveTvStatus.Failed? = null
            for (source in repository.sources.value) {
                try {
                    status.value = LiveTvStatus.Loading(
                        source.name,
                        guide = false,
                        quiet = quiet,
                    )
                    repository.refreshChannels(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val hasChannels = repository.channels.value[source.id].orEmpty().isNotEmpty()
                    if (!(quiet && hasChannels)) {
                        failure =
                            failure
                                ?: LiveTvStatus.Failed(
                                    source.name,
                                    e.message ?: e::class.simpleName.orEmpty(),
                                )
                    }
                }
                // Guides are kept a while (hosts limit downloads); a source left with no guide at all says why.
                status.value = LiveTvStatus.Loading(
                    source.name,
                    guide = true,
                    quiet = quiet,
                )
                val maxAge = if (quiet) {
                    IptvRepository.GUIDE_MAX_AGE_MS
                } else {
                    IptvRepository.GUIDE_MANUAL_MAX_AGE_MS
                }
                repository.refreshGuide(
                    source,
                    maxAge,
                )?.let {
                    failure =
                        failure ?: LiveTvStatus.Failed(
                            source.name,
                            it,
                        )
                }
            }
            status.value = failure
        }
    }
}
