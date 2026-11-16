package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.addSourceEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.continueWatchingEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.libraryEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.liveTvEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.navigateToAddSource
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.navigateToSources
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.navigateToTitle
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.sourcesEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.titleEntry
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.PlayableItem
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvActions
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.liveTvNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    liveTvEntry(
        actions = LiveTvActions(
            addSource = { backStack.navigateToAddSource() },
            editSource = { id -> backStack.navigateToAddSource(sourceId = id) },
            openSources = { backStack.navigateToSources() },
            play = { channel, list ->
                context.playChannel(
                    channel,
                    list,
                )
            },
            openSettings = backStack::navigateToSettings,
        ),
    )
    addSourceEntry(onNavigateUp = { backStack.removeLastIfNotRoot() })
    sourcesEntry(
        onBack = { backStack.removeLastIfNotRoot() },
        onAdd = { backStack.navigateToAddSource() },
        onEdit = { id -> backStack.navigateToAddSource(sourceId = id) },
    )
}

/** The Movies & series tab: the library, and a title's page on top of it. */
fun EntryProviderScope<NavKey>.libraryNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    libraryEntry(
        onOpen = { sourceId, item ->
            backStack.navigateToTitle(
                sourceId,
                item,
            )
        },
        onSettings = backStack::navigateToSettings,
    )
    titleEntry(
        onBack = { backStack.removeLastIfNotRoot() },
        onPlay = { items -> context.playQueue(items) },
    )
}

/** The Continue watching tab: plays on, or opens a finished series' page via [openTitle]. */
fun EntryProviderScope<NavKey>.continueWatchingNavGraph(
    context: Context,
    backStack: NavBackStack<NavKey>,
    openTitle: (String, LibraryItem) -> Unit,
) {
    continueWatchingEntry(
        onPlay = { items -> context.playQueue(items) },
        onOpenTitle = openTitle,
        onSettings = backStack::navigateToSettings,
    )
}

/**
 * Plays a movie, or an episode followed by the rest of its season: the player's next button and
 * autoplay go on to the next episode, and it resumes each where it was left.
 */
internal fun Context.playQueue(items: List<PlayableItem>) {
    val first = items.firstOrNull() ?: return
    val intent = Intent(
        this,
        PlayerActivity::class.java,
    ).apply {
        action = Intent.ACTION_VIEW
        data = first.url.toUri()
        putExtra(
            PlayerApi.API_TITLE,
            first.title,
        )
        putExtra(
            "headers",
            arrayOf(
                "User-Agent",
                IptvRepository.USER_AGENT,
            ),
        )
        putExtra(
            "video_list_is_explicit",
            true,
        )
        putParcelableArrayListExtra(
            PlayerApi.API_PLAYLIST,
            ArrayList(items.map { it.url.toUri() }),
        )
        putExtra(
            "video_list.name",
            items.map { it.title }.toTypedArray(),
        )
    }
    startActivity(intent)
}

/**
 * Opens [channel] in the player, with the channels around it in [list] as the queue so the player's
 * next/previous buttons switch channel. Streams get the user agent IPTV providers expect.
 */
private fun Context.playChannel(channel: Channel, list: List<Channel>) {
    val index = list.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
    // A whole provider can be thousands of channels: queue a window around the chosen one.
    val queue = if (index in list.indices) {
        list.subList(
            (index - QUEUE_AROUND).coerceAtLeast(0),
            (index + QUEUE_AROUND + 1).coerceAtMost(list.size),
        )
    } else {
        listOf(channel)
    }
    val intent = Intent(
        this,
        PlayerActivity::class.java,
    ).apply {
        action = Intent.ACTION_VIEW
        data = channel.url.toUri()
        putExtra(
            PlayerApi.API_TITLE,
            channel.name,
        )
        putExtra(
            "headers",
            arrayOf(
                "User-Agent",
                IptvRepository.USER_AGENT,
            ),
        )
        putExtra(
            "video_list_is_explicit",
            true,
        )
        putParcelableArrayListExtra(
            PlayerApi.API_PLAYLIST,
            ArrayList(queue.map { it.url.toUri() }),
        )
        putExtra(
            "video_list.name",
            queue.map { it.name }.toTypedArray(),
        )
    }
    startActivity(intent)
}

private const val QUEUE_AROUND = 100
