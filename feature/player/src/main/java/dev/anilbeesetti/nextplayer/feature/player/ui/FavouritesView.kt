package dev.anilbeesetti.nextplayer.feature.player.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.PlayerChannelList
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.QueuedChannel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.rememberFavouriteChannels
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi

/**
 * Overtime: the favourite channels of every source, drawn like the player's channel list. Picking one
 * plays it with the favourites as the queue, so next and previous go through them.
 */
@Composable
fun BoxScope.FavouritesView(modifier: Modifier = Modifier, show: Boolean, player: Player) {
    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.live_tv_favourites),
    ) {
        if (!show) return@OverlayView
        val context = LocalContext.current
        val channels = rememberFavouriteChannels()
        if (channels.isEmpty()) {
            NoFavourites()
            return@OverlayView
        }
        val playing = player.currentMediaItem?.mediaId
        PlayerChannelList(
            channels = channels,
            currentIndex = channels.indexOfFirst { it.url == playing },
            onSelect = { index ->
                context.playLiveQueue(
                    channels,
                    index,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun NoFavourites() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.player_no_favourites),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.player_no_favourites_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Opens [channels] in this player as a live queue, starting at [index] (the Live TV screen's request). */
private fun Context.playLiveQueue(channels: List<QueuedChannel>, index: Int) {
    val channel = channels.getOrNull(index) ?: return
    val intent = Intent(
        this,
        PlayerActivity::class.java,
    ).apply {
        action = Intent.ACTION_VIEW
        data = channel.url.toUri()
        putExtra(
            PlayerApi.API_TITLE,
            channel.title,
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
            ArrayList(
                channels.map {
                    it.url.toUri()
                },
            ),
        )
        putExtra(
            "video_list.name",
            channels.map { it.title }.toTypedArray(),
        )
        putExtra(
            PlayerApi.API_LIVE,
            true,
        )
    }
    startActivity(intent)
}
