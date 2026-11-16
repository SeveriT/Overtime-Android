package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.PlaylistShortcut
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.utils.PlaylistPlaybackContract
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistDetailRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistListRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.navigateToPlaylistDetail
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.playlistDetailEntry
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.playlistListEntry
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.playlistNavGraph(
    context: Context,
    backStack: NavBackStack<NavKey>,
) {
    playlistListEntry(
        onPlaylistClick = backStack::navigateToPlaylistDetail,
        onSettingsClick = backStack::navigateToSettings,
        onAddToHomeScreen = if (PlaylistShortcut.isSupported(context = context)) {
            { playlist ->
                if (!PlaylistShortcut.requestPin(
                        context = context,
                        playlist = playlist,
                    )
                ) {
                    Toast.makeText(
                        context,
                        R.string.playlist_shortcut_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } else {
            null
        },
        onNavigateUp = { backStack.removeLastIfNotRoot() },
    )

    playlistDetailEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayPlaylist = { playlistId, startUri ->
            context.startPlaylistPlayback(
                playlistId = playlistId,
                startUri = startUri,
            )
        },
    )
}

/** A playlist shortcut: Overtime keeps Playlists under More, so the playlist opens there. */
internal fun TopLevelNavState.openPlaylist(playlistId: Long) {
    val homeRoute = TopLevelDestination.MORE.route
    val homeStack = backStacks.getValue(key = homeRoute)
    while (homeStack.size > 1) homeStack.removeLastOrNull()
    homeStack.add(element = PlaylistListRoute)
    homeStack.add(element = PlaylistDetailRoute(playlistId = playlistId))
    switchTo(route = homeRoute)
}

internal fun Context.startPlaylistPlayback(playlistId: Long, startUri: Uri) {
    startActivity(
        Intent(
            this,
            PlayerActivity::class.java,
        ).apply {
            action = Intent.ACTION_VIEW
            data = startUri
            putExtra(
                PlaylistPlaybackContract.EXTRA_PLAYLIST_ID,
                playlistId,
            )
        },
    )
}
