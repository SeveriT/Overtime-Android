package dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ViewTimeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelFab
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen hands back to navigation. */
data class LiveTvActions(
    val addSource: () -> Unit,
    val editSource: (String) -> Unit,
    /** All sources, like the Sources dialog on Windows. */
    val openSources: () -> Unit,
    /** Plays [channel], with the channels shown alongside so the player's next/previous zaps. */
    val play: (channel: Channel, list: List<Channel>) -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun LiveTvScreen(viewModel: LiveTvViewModel, actions: LiveTvActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiveTvContent(
        state,
        viewModel,
        actions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTvContent(
    state: LiveTvUiState,
    viewModel: LiveTvViewModel,
    actions: LiveTvActions,
) {
    // Resume the channel watched last (sources are added from the Sources list or the empty screen);
    // before any channel was watched, Settings like the other tabs.
    val last by viewModel.lastChannel.collectAsStateWithLifecycle()
    val resume = last
    if (resume != null) {
        BindTopLevelFab(
            key = TopLevelFabKey.LIVE_TV,
            icon = NextIcons.Play,
            onClick = {
                actions.play(
                    resume.first,
                    resume.second,
                )
            },
        )
    } else {
        BindTopLevelFab(
            key = TopLevelFabKey.LIVE_TV,
            icon = NextIcons.Settings,
            onClick = actions.openSettings,
        )
    }
    val bottomPadding = LocalNavigationBottomPadding.current

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.live_tv),
                fontWeight = FontWeight.Bold,
                actions = {
                    if (state.sources.isNotEmpty()) {
                        IconButton(onClick = viewModel::toggleGuide) {
                            Icon(
                                if (state.showGuide) {
                                    Icons.AutoMirrored.Rounded.List
                                } else {
                                    Icons.Rounded.ViewTimeline
                                },
                                contentDescription = stringResource(
                                    if (state.showGuide) {
                                        R.string.live_tv_show_list
                                    } else {
                                        R.string.live_tv_show_guide
                                    },
                                ),
                            )
                        }
                    }
                    if (state.sources.isNotEmpty()) {
                        IconButton(onClick = actions.openSources) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ViewList,
                                contentDescription = stringResource(R.string.iptv_sources),
                            )
                        }
                    }
                    if (state.sources.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.refreshAll() },
                            enabled = !state.busy,
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.live_tv_refresh),
                            )
                        }
                    }
                    // Always there, as on NextPlayer's other main screens.
                    IconButton(onClick = actions.openSettings) {
                        Icon(
                            NextIcons.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.sources.isEmpty()) {
                EmptyState(onAdd = actions.addSource)
                return@Box
            }
            Column(Modifier.fillMaxSize()) {
                // Sources (when there are several), then groups, then search.
                if (state.sources.size > 1) {
                    ChipRow(
                        state.sources.map {
                            it.id to it.name
                        },
                        state.sourceId,
                    ) { viewModel.selectSource(it) }
                }
                // Back from a group returns to the group list, as on Windows.
                BackHandler(enabled = state.group != null) { viewModel.backToGroups() }
                state.group?.let { group ->
                    Row(
                        Modifier.fillMaxWidth().padding(
                            start = 4.dp,
                            end = 16.dp,
                            top = 4.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = viewModel::backToGroups) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(
                                    R.string.live_tv_back_to_groups,
                                ),
                            )
                        }
                        Text(
                            groupLabel(group),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::search,
                    placeholder = {
                        Text(
                            stringResource(
                                if (state.group ==
                                    null
                                ) {
                                    R.string.live_tv_search
                                } else {
                                    R.string.live_tv_search_group
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
                                    contentDescription = stringResource(
                                        R.string.live_tv_clear_search,
                                    ),
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
                state.status?.let { status ->
                    Text(
                        when (status) {
                            is LiveTvStatus.Loading -> when {
                                status.quiet -> stringResource(
                                    R.string.live_tv_updating,
                                    status.source,
                                )

                                status.guide -> stringResource(
                                    R.string.live_tv_loading_guide,
                                    status.source,
                                )

                                else -> stringResource(
                                    R.string.live_tv_loading_channels,
                                    status.source,
                                )
                            }

                            is LiveTvStatus.Failed -> stringResource(
                                R.string.live_tv_source_error,
                                status.source,
                                status.message,
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status is LiveTvStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 4.dp,
                        ),
                    )
                }
                if (state.group == null && state.query.isBlank()) {
                    GroupList(
                        groups = state.groups,
                        bottomPadding = 8.dp + bottomPadding,
                        onOpen = viewModel::selectGroup,
                        onToggleFavorite = viewModel::toggleFavoriteGroup,
                    )
                    return@Column
                }
                if (state.channels.isEmpty()) {
                    Text(
                        when {
                            state.query.isNotBlank() -> stringResource(
                                R.string.live_tv_no_match,
                                state.query,
                            )

                            state.group == GROUP_FAVORITES -> stringResource(
                                R.string.live_tv_no_favourites,
                            )

                            else -> stringResource(R.string.live_tv_no_channels)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                if (state.showGuide) {
                    GuideGrid(
                        channels = state.channels,
                        epg = state.epg,
                        now = state.now,
                        bottomPadding = 8.dp + bottomPadding,
                        onPlay = { channel ->
                            actions.play(
                                channel,
                                state.channels,
                            )
                        },
                    )
                    return@Column
                }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 4.dp,
                        bottom =
                            8.dp + bottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        state.channels,
                        key = { it.id },
                    ) { channel ->
                        val (now, next) = remember(
                            channel.id,
                            state.epg,
                            state.now,
                        ) {
                            state.epg.nowNext(
                                channel,
                                state.now,
                            )
                        }
                        ChannelRow(
                            channel = channel,
                            now = now?.title,
                            next = next?.let { "${formatClock(it.start)} ${it.title}" },
                            progress = now?.let {
                                (
                                    (state.now - it.start).toFloat() /
                                        (it.stop - it.start).coerceAtLeast(1)
                                    ).coerceIn(
                                    0f,
                                    1f,
                                )
                            },
                            favorite = channel.id in state.favorites,
                            onClick = {
                                actions.play(
                                    channel,
                                    state.channels,
                                )
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(channel) },
                        )
                    }
                }
            }
        }
    }
}

/** "All channels" and "Favourites" are named by the app; playlist groups by the playlist. */
@Composable
private fun groupLabel(group: String): String = when (group) {
    GROUP_ALL -> stringResource(R.string.live_tv_all_channels)
    GROUP_FAVORITES -> stringResource(R.string.live_tv_favourites)
    else -> group
}

/**
 * Every group of the source, as on Windows: All channels, Favourites, then starred groups and the rest,
 * each with its channel count and (for the playlist's own groups) a star to pin it to the top.
 */
@Composable
private fun GroupList(
    groups: List<GroupEntry>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 4.dp,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            groups,
            key = { it.name },
        ) { group ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpen(group.name) }
                    .padding(
                        start = 12.dp,
                        end = 4.dp,
                        top = 4.dp,
                        bottom = 4.dp,
                    )
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (group.name) {
                        GROUP_ALL -> Icons.Rounded.GridView
                        GROUP_FAVORITES -> Icons.Rounded.Star
                        else -> Icons.Rounded.LiveTv
                    },
                    contentDescription = null,
                    tint = if (group.name ==
                        GROUP_FAVORITES
                    ) {
                        Color(0xFFFFC107)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    groupLabel(group.name),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Text(
                    group.count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (group.name != GROUP_ALL && group.name != GROUP_FAVORITES) {
                    IconButton(onClick = { onToggleFavorite(group.name) }) {
                        Icon(
                            if (group.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = stringResource(
                                if (group.favorite) {
                                    R.string.live_tv_unstar_group
                                } else {
                                    R.string.live_tv_star_group
                                },
                            ),
                            tint = if (group.favorite) {
                                Color(
                                    0xFFFFC107,
                                )
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        items(
            options,
            key = { it.first },
        ) { (id, label) ->
            FilterChip(
                selected = id == selected,
                onClick = {
                    onSelect(id)
                },
                label = {
                    Text(
                        label,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/** A channel in a list: also used by the player's channel list, which highlights the one playing. */
@Composable
internal fun ChannelRow(
    channel: Channel,
    now: String?,
    next: String?,
    progress: Float?,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    selected: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = 0.4f,
                    )
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = 8.dp,
                vertical = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(
            url = channel.logo,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            imageModifier = Modifier.fillMaxSize().padding(4.dp),
        ) {
            Icon(
                Icons.Rounded.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                now ?: channel.group,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                )
            }
            next?.let {
                Text(
                    stringResource(
                        R.string.live_tv_next,
                        it,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (favorite) {
                        R.string.live_tv_remove_favourite
                    } else {
                        R.string.live_tv_add_favourite
                    },
                ),
                tint = if (favorite) {
                    Color(
                        0xFFFFC107,
                    )
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(
            12.dp,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(
                72.dp,
            ).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            stringResource(R.string.live_tv),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.live_tv_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onAdd) { Text(stringResource(R.string.live_tv_add_a_source)) }
    }
}

internal fun formatClock(millis: Long): String = SimpleDateFormat(
    "HH:mm",
    Locale.getDefault(),
).format(Date(millis))
