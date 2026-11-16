package dev.anilbeesetti.nextplayer.feature.iptv.screens.library

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryKind

/** Movies or Series: category chips, search, and a poster grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpen: (sourceId: String, item: LibraryItem) -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bottomPadding = LocalNavigationBottomPadding.current
    // A tab of its own now: sources added in Live TV meanwhile show up when it's opened again.
    LaunchedEffect(Unit) { viewModel.reloadSources() }
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.library_title),
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
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (state.sources.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_source),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }
            if (state.sources.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        state.sources,
                        key = { it.id },
                    ) { source ->
                        FilterChip(
                            selected = source.id == state.sourceId,
                            onClick = {
                                viewModel.selectSource(source.id)
                            },
                            label = { Text(source.name) },
                        )
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
            ) {
                listOf(
                    LibraryKind.MOVIES to R.string.library_movies,
                    LibraryKind.SERIES to R.string.library_series,
                ).forEachIndexed {
                        index,
                        (kind, label),
                    ->
                    SegmentedButton(
                        selected = state.kind == kind,
                        onClick = { viewModel.selectKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index,
                            2,
                        ),
                    ) { Text(stringResource(label)) }
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                placeholder = {
                    Text(
                        stringResource(
                            if (state.kind ==
                                LibraryKind.MOVIES
                            ) {
                                R.string.library_search_movies
                            } else {
                                R.string.library_search_series
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = {
                            viewModel.search("")
                        }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.live_tv_clear_search),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
            )
            if (state.query.isBlank() && state.categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        state.categories,
                        key = { it.id },
                    ) { category ->
                        FilterChip(
                            selected = category.id == state.categoryId,
                            onClick = { viewModel.selectCategory(category.id) },
                            label = {
                                Text(
                                    category.name,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
            if (state.loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(
                        horizontal = 12.dp,
                        vertical = 4.dp,
                    ),
                )
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (!state.loading && state.error == null && state.items.isEmpty() &&
                (state.query.isNotBlank() || state.categoryId != null)
            ) {
                Text(
                    stringResource(
                        if (state.query.isNotBlank()) {
                            R.string.library_no_match
                        } else {
                            R.string.library_empty_category
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom =
                        12.dp + bottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    state.items,
                    key = { "${it.kind}:${it.id}" },
                ) { item ->
                    PosterCard(item) {
                        state.sourceId?.let {
                            onOpen(
                                it,
                                item,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: LibraryItem, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(
                2f / 3f,
            ).clip(
                RoundedCornerShape(12.dp),
            ).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.poster?.let {
                AsyncImage(
                    model = it,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.rating?.let { rating ->
                Row(
                    Modifier.align(
                        Alignment.TopEnd,
                    ).padding(
                        6.dp,
                    ).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.65f))
                        .padding(
                            horizontal = 5.dp,
                            vertical = 1.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        rating.take(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.year?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A movie (Play) or a series (seasons and episodes). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailsScreen(
    viewModel: TitleDetailsViewModel,
    onBack: () -> Unit,
    onPlay: (List<PlayableItem>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = item.name,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val backdrop =
                state.movie?.backdrop ?: state.series?.backdrop ?: item.backdrop ?: item.poster
            Box(
                Modifier.fillMaxWidth().aspectRatio(
                    16f / 9f,
                ).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                backdrop?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier.fillMaxWidth().height(72.dp).align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )
            }
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                val facts = listOfNotNull(
                    item.year ?: state.movie?.releaseDate?.take(4)
                        ?: state.series?.releaseDate?.take(4),
                    item.rating?.let { "★ $it" },
                    state.movie?.duration,
                    state.movie?.genre ?: state.series?.genre,
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.kind == LibraryKind.MOVIES) {
                    Button(onClick = { viewModel.movie()?.let { onPlay(listOf(it)) } }) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                        Text(
                            stringResource(R.string.library_play),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (state.loading) CircularProgressIndicator(Modifier.padding(8.dp))
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                (state.movie?.plot ?: state.series?.plot)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                (state.movie?.cast ?: state.series?.cast)?.let {
                    Text(
                        stringResource(
                            R.string.library_cast,
                            it,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.movie?.director?.let {
                    Text(
                        stringResource(
                            R.string.library_director,
                            it,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.series?.let { series ->
                val seasons = series.seasons
                if (seasons.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons.size) { index ->
                            FilterChip(
                                selected = index == state.seasonIndex,
                                onClick = {
                                    viewModel.selectSeason(index)
                                },
                                label = { Text(seasons[index].name) },
                            )
                        }
                    }
                }
                seasons.getOrNull(state.seasonIndex)?.episodes?.forEach { episode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(viewModel.episodesFrom(episode)) }
                            .padding(
                                horizontal = 16.dp,
                                vertical = 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(
                                width = 112.dp,
                                height = 63.dp,
                            ).clip(
                                RoundedCornerShape(8.dp),
                            ).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            episode.image?.let {
                                AsyncImage(
                                    model = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                "${episode.number}. ${episode.title.ifBlank {
                                    stringResource(
                                        R.string.library_episode,
                                        episode.number,
                                    )
                                }}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            episode.duration?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            episode.plot?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(24.dp))
        }
    }
}
