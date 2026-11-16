package dev.anilbeesetti.nextplayer.feature.iptv.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.Episode
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryCategory
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.MovieDetails
import dev.anilbeesetti.nextplayer.feature.iptv.model.SeriesDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class LibraryUiState(
    val sources: List<IptvSource> = emptyList(),
    val sourceId: String? = null,
    val kind: LibraryKind = LibraryKind.MOVIES,
    val categories: List<LibraryCategory> = emptyList(),
    val categoryId: String? = null,
    val items: List<LibraryItem> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

/** A movie or episode ready for the player: its stream address and a title to show. */
data class PlayableItem(val url: String, val title: String)

/**
 * The Xtream movie & series library: pick Movies or Series, then a category; search covers the whole
 * library of that kind (loaded once per session).
 */
@KoinViewModel
class LibraryViewModel(private val repository: IptvRepository) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState(sources = repository.librarySources, sourceId = repository.librarySources.firstOrNull()?.id))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    private val source: IptvSource? get() = repository.librarySources.firstOrNull { it.id == _state.value.sourceId }

    init {
        loadCategories()
    }

    fun selectSource(id: String) {
        _state.update { it.copy(sourceId = id, categories = emptyList(), categoryId = null, items = emptyList(), query = "") }
        loadCategories()
    }

    fun selectKind(kind: LibraryKind) {
        if (kind == _state.value.kind) return
        _state.update { it.copy(kind = kind, categories = emptyList(), categoryId = null, items = emptyList(), query = "") }
        loadCategories()
    }

    fun selectCategory(id: String) {
        _state.update { it.copy(categoryId = id, query = "") }
        load { repository.libraryItems(it, _state.value.kind, id) }
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        val q = query.trim()
        if (q.isEmpty()) {
            _state.value.categoryId?.let(::selectCategory)
            return
        }
        load { source -> repository.libraryItems(source, _state.value.kind, null).filter { it.name.contains(q, ignoreCase = true) }.take(MAX_RESULTS) }
    }

    private fun loadCategories() {
        val source = source ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val categories = repository.categories(source, _state.value.kind)
                _state.update { it.copy(categories = categories, loading = false) }
                categories.firstOrNull()?.let { selectCategory(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    private fun load(block: suspend (IptvSource) -> List<LibraryItem>) {
        val source = source ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val items = block(source)
                _state.update { it.copy(items = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    private companion object {
        const val MAX_RESULTS = 300
    }
}

data class DetailsUiState(
    val item: LibraryItem,
    val movie: MovieDetails? = null,
    val series: SeriesDetails? = null,
    val seasonIndex: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

/** One movie or series: its details, and for a series the seasons and episodes. */
@KoinViewModel
class TitleDetailsViewModel(
    private val repository: IptvRepository,
    @org.koin.core.annotation.InjectedParam private val sourceId: String,
    @org.koin.core.annotation.InjectedParam private val item: LibraryItem,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailsUiState(item))
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    private val source: IptvSource? get() = repository.librarySources.firstOrNull { it.id == sourceId }

    init {
        viewModelScope.launch {
            val source = source ?: return@launch
            try {
                if (item.kind == LibraryKind.MOVIES) {
                    val movie = repository.movieDetails(source, item)
                    _state.update { it.copy(movie = movie, loading = false) }
                } else {
                    val series = repository.seriesDetails(source, item)
                    _state.update { it.copy(series = series, loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    fun selectSeason(index: Int) = _state.update { it.copy(seasonIndex = index) }

    fun movie(): PlayableItem? = source?.let { PlayableItem(repository.movieUrl(it, item), item.name) }

    /** [episode] and the rest of its season after it, so the player's next button (and autoplay) goes on. */
    fun episodesFrom(episode: Episode): List<PlayableItem> {
        val source = source ?: return emptyList()
        val season = _state.value.series?.seasons?.firstOrNull { s -> s.episodes.any { it.id == episode.id } } ?: return emptyList()
        return season.episodes.dropWhile { it.id != episode.id }.map {
            PlayableItem(repository.episodeUrl(source, it), "${item.name} · S${season.number} E${it.number}" + it.title.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" }.orEmpty())
        }
    }
}
