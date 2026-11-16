package dev.anilbeesetti.nextplayer.feature.iptv.screens.continuewatching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.ContinueItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.ContinueKind
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.PlaybackProgress
import dev.anilbeesetti.nextplayer.feature.iptv.model.WatchEntry
import dev.anilbeesetti.nextplayer.feature.iptv.model.continueWatching
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.PlayableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.KoinViewModel

/**
 * Continue watching: what was played from Movies & series, joined with how far the player got
 * (the player keeps a position per stream address, so this updates by itself after playing).
 */
@KoinViewModel
class ContinueWatchingViewModel(private val repository: IptvRepository, stateDao: MediumStateDao) :
    ViewModel() {
    /** Null until loaded. */
    val items: StateFlow<List<ContinueItem>?> = combine(
        repository.watching,
        repository.dismissed,
        stateDao.getAll(),
    ) {
            watching,
            dismissed,
            states,
        ->
        val urls = watching.mapTo(HashSet()) { it.url }
        val progress = states.asSequence().filter { it.uriString in urls }.associate {
            it.uriString to PlaybackProgress(
                it.playbackPosition,
                it.duration,
                it.lastPlayedTime,
            )
        }
        continueWatching(
            watching,
            dismissed,
            progress,
        )
    }.flowOn(
        Dispatchers.Default,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun dismiss(item: ContinueItem) = repository.dismissWatching(item.key)

    /** Marks [item] as picked and returns what to play. */
    fun play(item: ContinueItem): List<PlayableItem> {
        val first = item.queue.firstOrNull() ?: return emptyList()
        repository.recordWatching(listOf(first.copy(playedAt = System.currentTimeMillis())))
        return item.queue.map {
            PlayableItem(
                it.url,
                it.title,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueWatchingScreen(
    viewModel: ContinueWatchingViewModel,
    onPlay: (List<PlayableItem>) -> Unit,
    onOpenTitle: (sourceId: String, item: LibraryItem) -> Unit,
    onSettings: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val bottomPadding = LocalNavigationBottomPadding.current
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.continue_watching),
                fontWeight = FontWeight.Bold,
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(
                            NextIcons.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    NextIcons.ContinueWatching,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    stringResource(R.string.continue_watching_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top =
                        padding.calculateTopPadding() + 8.dp,
                    bottom = 16.dp + bottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    list,
                    key = { it.key },
                ) { item ->
                    ContinueCard(
                        item = item,
                        onClick = {
                            if (item.kind == ContinueKind.WATCHED) {
                                onOpenTitle(
                                    item.entry.sourceId,
                                    item.entry.item,
                                )
                            } else {
                                onPlay(viewModel.play(item))
                            }
                        },
                        onDismiss = { viewModel.dismiss(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(
    item: ContinueItem,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = item.entry
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(
                16f / 9f,
            ).clip(
                RoundedCornerShape(16.dp),
            ).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.image?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Darkens the bottom so the progress bar reads on any picture.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f),
                    ),
                ),
            )
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (item.kind) {
                        ContinueKind.RESUME -> Icons.Rounded.PlayArrow
                        ContinueKind.NEXT -> Icons.Rounded.SkipNext
                        ContinueKind.WATCHED -> Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Box(
                    Modifier.size(
                        32.dp,
                    ).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.continue_watching_dismiss),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (item.kind == ContinueKind.RESUME && item.progress > 0f) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.align(
                        Alignment.BottomCenter,
                    ).fillMaxWidth().padding(
                        horizontal = 12.dp,
                        vertical = 10.dp,
                    ).height(4.dp),
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun subtitle(item: ContinueItem): String {
    val entry = item.entry
    val episode = episodeLabel(entry)
    val left = item.remainingMs?.let { ms ->
        stringResource(
            R.string.continue_watching_left,
            (
                (
                    ms +
                        59_999
                    ) /
                    60_000
                ).toInt(),
        )
    }
    return when (item.kind) {
        ContinueKind.RESUME -> listOfNotNull(
            episode,
            left,
        ).joinToString(" · ").ifEmpty {
            stringResource(R.string.continue_watching_resume)
        }

        ContinueKind.NEXT -> stringResource(
            R.string.continue_watching_next,
            episode.orEmpty(),
        )

        ContinueKind.WATCHED -> listOfNotNull(
            episode,
            stringResource(R.string.continue_watching_watched),
        ).joinToString(" · ")
    }
}

/** "S1 E3 · Title" for an episode, null for a movie. */
private fun episodeLabel(entry: WatchEntry): String? {
    if (!entry.isEpisode) return null
    return "S${entry.season ?: 0} E${entry.episode ?: 0}" +
        entry.episodeTitle?.let { " · $it" }.orEmpty()
}
