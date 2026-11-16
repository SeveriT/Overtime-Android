package dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.EpgData
import dev.anilbeesetti.nextplayer.feature.iptv.model.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MinuteWidth = 4.dp
private val ChannelColumnWidth = 88.dp
private val RowHeight = 64.dp
private val HeaderHeight = 32.dp
private const val SLOT_MS = 30 * 60_000L
private const val WINDOW_MS = 12 * 60 * 60_000L

/**
 * Programme guide: channels down the left, time across, all rows scrolling sideways together. Starts
 * half an hour back with the current time in view; a line marks "now". Tapping a programme shows its
 * details with a Watch button; tapping a channel plays it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuideGrid(
    channels: List<Channel>,
    epg: EpgData,
    now: Long,
    bottomPadding: Dp,
    onPlay: (Channel) -> Unit,
) {
    val start = remember(now / SLOT_MS) { (now / SLOT_MS) * SLOT_MS - SLOT_MS }
    val end = start + WINDOW_MS
    val totalWidth = MinuteWidth * ((end - start) / 60_000L).toInt()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var picked by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    // Open with "now" a little in from the left.
    LaunchedEffect(start) {
        val nowX =
            with(density) { (MinuteWidth * ((now - start) / 60_000L).toInt() - 24.dp).toPx() }
        scroll.scrollTo(nowX.toInt().coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Time ruler
            Row(Modifier.fillMaxWidth().height(HeaderHeight)) {
                Spacer(Modifier.width(ChannelColumnWidth))
                Box(Modifier.horizontalScroll(scroll).width(totalWidth).fillMaxHeight()) {
                    var slot = start
                    while (slot < end) {
                        Text(
                            formatClock(slot),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.offset(
                                x =
                                    MinuteWidth * ((slot - start) / 60_000L).toInt() + 6.dp,
                            ).align(Alignment.CenterStart),
                        )
                        slot += SLOT_MS
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                items(
                    channels,
                    key = { it.id },
                ) { channel ->
                    GuideRow(
                        channel,
                        epg,
                        start,
                        end,
                        now,
                        totalWidth,
                        scroll,
                        onPlay = {
                            onPlay(channel)
                        },
                    ) { programme ->
                        picked = channel to programme
                    }
                }
            }
        }
        // The "now" line, following the sideways scroll.
        val nowOffset = with(density) {
            ChannelColumnWidth +
                MinuteWidth * ((now - start) / 60_000L).toInt() -
                scroll.value.toDp()
        }
        if (nowOffset > ChannelColumnWidth) {
            Box(
                Modifier
                    .offset(x = nowOffset)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }

    picked?.let { (channel, programme) ->
        ModalBottomSheet(onDismissRequest = { picked = null }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${channel.name} · ${formatClock(
                        programme.start,
                    )}–${formatClock(programme.stop)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (programme.description.isNotBlank()) {
                    Text(
                        programme.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = {
                        picked = null
                        onPlay(channel)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Text(
                        stringResource(
                            R.string.live_tv_watch_channel,
                            channel.name,
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    epg: EpgData,
    start: Long,
    end: Long,
    now: Long,
    totalWidth: Dp,
    scroll: ScrollState,
    onPlay: () -> Unit,
    onPick: (Programme) -> Unit,
) {
    val programmes = remember(
        channel.id,
        epg,
        start,
    ) {
        epg.programmesFor(channel).filter { it.stop > start && it.start < end }
    }
    Row(Modifier.fillMaxWidth().height(RowHeight)) {
        // Channel: logo and name; tap to watch.
        Column(
            Modifier
                .width(ChannelColumnWidth)
                .fillMaxHeight()
                .clickable(onClick = onPlay)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChannelLogo(
                url = channel.logo,
                modifier = Modifier.size(32.dp),
                imageModifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    Icons.Rounded.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.horizontalScroll(
                scroll,
            ).width(totalWidth).fillMaxHeight().padding(vertical = 3.dp),
        ) {
            if (programmes.isEmpty()) {
                Text(
                    stringResource(R.string.live_tv_no_guide),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
            programmes.forEach { programme ->
                val from = maxOf(
                    programme.start,
                    start,
                )
                val to = minOf(
                    programme.stop,
                    end,
                )
                val airing = programme.start <= now && now < programme.stop
                val over = programme.stop <= now
                Column(
                    Modifier
                        .offset(x = MinuteWidth * ((from - start) / 60_000L).toInt())
                        .width(MinuteWidth * ((to - from) / 60_000L).toInt())
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                airing -> MaterialTheme.colorScheme.primaryContainer
                                over -> MaterialTheme.colorScheme.surfaceContainerLow
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        )
                        .clickable { onPick(programme) }
                        .padding(
                            horizontal = 8.dp,
                            vertical = 6.dp,
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val color = if (airing) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        programme.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatClock(programme.start)}–${formatClock(programme.stop)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatClock(millis: Long): String = SimpleDateFormat(
    "HH:mm",
    Locale.getDefault(),
).format(Date(millis))
