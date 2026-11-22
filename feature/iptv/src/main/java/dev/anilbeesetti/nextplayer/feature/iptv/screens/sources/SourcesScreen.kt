package dev.anilbeesetti.nextplayer.feature.iptv.screens.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.SourceType
import dev.anilbeesetti.nextplayer.feature.iptv.screens.screenSheet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.KoinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A source and how many channels it has. */
data class SourceRow(val source: IptvSource, val channels: Int)

@KoinViewModel
class SourcesViewModel(private val repository: IptvRepository) : ViewModel() {
    val rows: StateFlow<List<SourceRow>> = combine(
        repository.sources,
        repository.channels,
    ) {
            sources,
            channels,
        ->
        sources.map {
            SourceRow(
                it,
                channels[it.id]?.size ?: 0,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun saveOrder(ids: List<String>) = repository.reorderSources(ids)
}

/** Every playlist and login, like the Sources dialog on Windows: tap one to edit or delete it, or add another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    // Dragged locally for smooth movement; saved when the drag ends.
    var shown by remember { mutableStateOf(rows) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(rows) { if (!dragging) shown = rows }
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        shown = shown.toMutableList().apply {
            add(
                to.index,
                removeAt(from.index),
            )
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.iptv_sources),
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.live_tv_add_source)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .screenSheet(padding)
                .padding(bottom = padding.calculateBottomPadding()),
            state = listState,
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.live_tv_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(
                shown,
                key = { it.source.id },
            ) { row ->
                ReorderableItem(
                    reorderState,
                    key = row.source.id,
                ) { isDragging ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            )
                            .clickable { onEdit(row.source.id) }
                            .padding(
                                start = 4.dp,
                                end = 16.dp,
                                top = 14.dp,
                                bottom = 14.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Drag here to move the source up or down.
                        Icon(
                            NextIcons.DragHandle,
                            contentDescription = stringResource(R.string.iptv_reorder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp).padding(8.dp).draggableHandle(
                                onDragStarted = {
                                    dragging = true
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.GestureThresholdActivate,
                                    )
                                },
                                onDragStopped = {
                                    dragging = false
                                    viewModel.saveOrder(shown.map { it.source.id })
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            ),
                        )
                        Box(
                            Modifier.size(
                                40.dp,
                            ).clip(
                                RoundedCornerShape(12.dp),
                            ).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (row.source.type ==
                                    SourceType.XTREAM
                                ) {
                                    Icons.Rounded.Dns
                                } else {
                                    Icons.Rounded.PlaylistPlay
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.source.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(
                                    if (row.source.type ==
                                        SourceType.XTREAM
                                    ) {
                                        R.string.iptv_xtream
                                    } else {
                                        R.string.iptv_m3u
                                    },
                                ) +
                                    " · " +
                                    pluralStringResource(
                                        R.plurals.iptv_channel_count,
                                        row.channels,
                                        row.channels,
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.iptv_edit_source),
                        )
                    }
                }
            }
        }
    }
}
