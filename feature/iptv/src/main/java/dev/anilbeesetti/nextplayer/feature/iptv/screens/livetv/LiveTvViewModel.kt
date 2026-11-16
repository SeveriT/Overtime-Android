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

data class LiveTvUiState(
    val sources: List<IptvSource> = emptyList(),
    val sourceId: String? = null,
    /** Groups of the selected source, in playlist order, after All and Favourites. */
    val groups: List<String> = emptyList(),
    val group: String = GROUP_ALL,
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val epg: EpgData = EpgData(),
    val now: Long = System.currentTimeMillis(),
    val status: LiveTvStatus? = null,
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
    private val selection = MutableStateFlow(Selection())
    private val status = MutableStateFlow<LiveTvStatus?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    private data class Selection(val sourceId: String? = null, val group: String = GROUP_ALL, val query: String = "")

    val state: StateFlow<LiveTvUiState> = combine(
        repository.sources,
        repository.channels,
        repository.favorites,
        repository.epg,
        combine(selection, status, clock) { s, st, c -> Triple(s, st, c) },
    ) { sources, channelsBySource, favorites, epg, (sel, st, now) ->
        val sourceId = sel.sourceId?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
        val all = sourceId?.let(channelsBySource::get).orEmpty()
        val groups = listOf(GROUP_ALL, GROUP_FAVORITES) + all.map { it.group }.distinct()
        val group = sel.group.takeIf { it in groups } ?: GROUP_ALL
        val q = sel.query.trim()
        val shown = when {
            q.isNotEmpty() -> all.filter { it.name.contains(q, ignoreCase = true) }
            group == GROUP_ALL -> all
            group == GROUP_FAVORITES -> all.filter { it.id in favorites }
            else -> all.filter { it.group == group }
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvUiState())

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
        selection.value = Selection(sourceId = id)
    }

    fun selectGroup(group: String) {
        selection.value = selection.value.copy(group = group, query = "")
    }

    fun search(query: String) {
        selection.value = selection.value.copy(query = query)
    }

    fun toggleFavorite(channel: Channel) = repository.toggleFavorite(channel.id)

    /**
     * Reloads channels and guides of every source. [quiet] (the refresh at launch) says "Updating" and keeps
     * a failure to itself while saved channels are still there to watch.
     */
    fun refreshAll(quiet: Boolean = false) {
        viewModelScope.launch {
            for (source in repository.sources.value) {
                try {
                    status.value = LiveTvStatus.Loading(source.name, guide = false, quiet = quiet)
                    repository.refreshChannels(source)
                    status.value = LiveTvStatus.Loading(source.name, guide = true, quiet = quiet)
                    repository.refreshGuide(source)
                    status.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val hasChannels = repository.channels.value[source.id].orEmpty().isNotEmpty()
                    status.value = if (quiet && hasChannels) null else LiveTvStatus.Failed(source.name, e.message ?: e::class.simpleName.orEmpty())
                }
            }
        }
    }
}
