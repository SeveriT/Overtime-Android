package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistDetailRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistListRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavigationTest {

    private fun newState(): Pair<TopLevelNavState, Map<NavKey, NavBackStack<NavKey>>> {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = TopLevelDestination.entries.indexOf(TopLevelDestination.START)),
        )
        return state to stacks
    }

    @Test
    fun topLevelDestinationsAreInOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.MEDIA,
                TopLevelDestination.LIVE_TV,
                TopLevelDestination.LIBRARY,
                TopLevelDestination.CONTINUE,
                TopLevelDestination.MORE,
            ),
            TopLevelDestination.entries,
        )
    }

    @Test
    fun playlistShortcutOpensUnderMore() {
        val (state, stacks) = newState()
        stacks.getValue(TopLevelDestination.MORE.route).add(element = PlaylistDetailRoute(playlistId = 7))
        state.switchTo(TopLevelDestination.LIBRARY.route)

        state.openPlaylist(playlistId = 42)
        state.openPlaylist(playlistId = 42)

        assertEquals(TopLevelDestination.MORE.route, state.topLevelRoute)
        assertEquals(
            listOf(
                TopLevelDestination.MORE.route,
                PlaylistListRoute,
                PlaylistDetailRoute(playlistId = 42),
            ),
            state.currentStack.toList(),
        )
        state.goBack()
        state.goBack()
        assertEquals(listOf(TopLevelDestination.MORE.route), state.currentStack.toList())
        state.goBack()
        assertEquals(TopLevelDestination.START.route, state.topLevelRoute)
    }

    @Test
    fun topLevelContentKeysContainsDestinations() {
        val (state, _) = newState()
        val provider = entryProvider<NavKey> {
            TopLevelDestination.entries.forEach { destination ->
                entry(key = destination.route) {}
            }
        }
        for (dest in TopLevelDestination.entries) {
            assertTrue(state.topLevelContentKeys.contains(dest.route))
            assertTrue(state.topLevelContentKeys.contains(provider(dest.route).contentKey))
        }
    }
}
