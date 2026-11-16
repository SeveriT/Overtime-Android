package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.Channel
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.addSourceEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.liveTvEntry
import dev.anilbeesetti.nextplayer.feature.iptv.navigation.navigateToAddSource
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvActions
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi

fun EntryProviderScope<NavKey>.liveTvNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    liveTvEntry(
        actions = LiveTvActions(
            addSource = { backStack.navigateToAddSource() },
            editSource = { id -> backStack.navigateToAddSource(sourceId = id) },
            play = { channel, list -> context.playChannel(channel, list) },
        ),
    )
    addSourceEntry(onNavigateUp = { backStack.removeLastIfNotRoot() })
}

/**
 * Opens [channel] in the player, with the channels around it in [list] as the queue so the player's
 * next/previous buttons switch channel. Streams get the user agent IPTV providers expect.
 */
private fun Context.playChannel(channel: Channel, list: List<Channel>) {
    val index = list.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
    // A whole provider can be thousands of channels: queue a window around the chosen one.
    val queue = if (index in list.indices) {
        list.subList((index - QUEUE_AROUND).coerceAtLeast(0), (index + QUEUE_AROUND + 1).coerceAtMost(list.size))
    } else {
        listOf(channel)
    }
    val intent = Intent(this, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = channel.url.toUri()
        putExtra(PlayerApi.API_TITLE, channel.name)
        putExtra("headers", arrayOf("User-Agent", IptvRepository.USER_AGENT))
        putExtra("video_list_is_explicit", true)
        putParcelableArrayListExtra(PlayerApi.API_PLAYLIST, ArrayList(queue.map { it.url.toUri() }))
        putExtra("video_list.name", queue.map { it.name }.toTypedArray())
    }
    startActivity(intent)
}

private const val QUEUE_AROUND = 100
