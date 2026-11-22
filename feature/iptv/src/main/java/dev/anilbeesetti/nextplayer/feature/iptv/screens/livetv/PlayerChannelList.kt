package dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** A channel queued in the player: its stream address and the name the player shows. */
data class QueuedChannel(val url: String, val title: String)

/**
 * The player's channel list for live TV, drawn like the Live TV screen: logo, name, what's on now
 * with its progress and what's next, and the favourite star. [currentIndex] is highlighted and
 * scrolled into view; tapping a channel calls [onSelect] with its index in [channels].
 *
 * Logos, guides and favourites come from the same repository the Live TV screen uses.
 */
@Composable
fun PlayerChannelList(
    channels: List<QueuedChannel>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = koinInject<IptvRepository>()
    val known by repository.channels.collectAsState()
    val guides by repository.epg.collectAsState()
    val favorites by repository.favorites.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // Opened straight into the player (e.g. after the app was restored): load the saved guides.
        if (repository.epg.value.isEmpty()) repository.loadCachedGuides()
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val byUrl = remember(known) {
        HashMap<String, Channel>().also { map ->
            known.values.forEach { list ->
                list.forEach {
                    map.putIfAbsent(
                        it.url,
                        it,
                    )
                }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (currentIndex in
            channels.indices
        ) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(
            channels,
            key = { index, item -> "$index:${item.url}" },
        ) { index, item ->
            val channel =
                byUrl[item.url]
                    ?: Channel(
                        id = item.url,
                        sourceId = "",
                        name = item.title,
                        group = "",
                        url = item.url,
                    )
            val (current, next) = remember(
                channel.id,
                guides,
                now,
            ) {
                guides[channel.sourceId]?.nowNext(
                    channel,
                    now,
                ) ?: (null to null)
            }
            ChannelRow(
                channel = channel,
                now = current?.title,
                next = next?.let { "${formatClock(it.start)} ${it.title}" },
                progress = current?.let {
                    (
                        (now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(
                            1,
                        )
                        ).coerceIn(
                        0f,
                        1f,
                    )
                },
                favorite = channel.id in favorites,
                selected = index == currentIndex,
                onClick = { onSelect(index) },
                onToggleFavorite = { repository.toggleFavorite(channel.id) },
            )
        }
    }
}

/** The favourite channels of every source, in source and playlist order, as the player queues them. */
@Composable
fun rememberFavouriteChannels(): List<QueuedChannel> {
    val repository = koinInject<IptvRepository>()
    val known by repository.channels.collectAsState()
    val favorites by repository.favorites.collectAsState()
    val hidden by repository.hidden.collectAsState()
    return remember(
        known,
        favorites,
        hidden,
    ) {
        known.values
            .flatten()
            .filter { it.id in favorites && it.id !in hidden }
            .distinctBy { it.url }
            .map {
                QueuedChannel(
                    url = it.url,
                    title = it.name,
                )
            }
    }
}

private const val CLOCK_TICK_MS = 30_000L
