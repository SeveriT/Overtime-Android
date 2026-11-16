package dev.anilbeesetti.nextplayer.feature.iptv.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.iptv.model.LibraryItem
import dev.anilbeesetti.nextplayer.feature.iptv.screens.addsource.AddSourceScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.addsource.AddSourceViewModel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.continuewatching.ContinueWatchingScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.continuewatching.ContinueWatchingViewModel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.LibraryScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.LibraryViewModel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.PlayableItem
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.TitleDetailsScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.library.TitleDetailsViewModel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvActions
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvViewModel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.sources.SourcesScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.sources.SourcesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object LiveTvRoute : NavKey

@Serializable
data class AddSourceRoute(val sourceId: String? = null) : NavKey

@Serializable
object LibraryRoute : NavKey

@Serializable
object SourcesRoute : NavKey

@Serializable
object ContinueWatchingRoute : NavKey

fun EntryProviderScope<NavKey>.continueWatchingEntry(
    onPlay: (List<PlayableItem>) -> Unit,
    onOpenTitle: (String, LibraryItem) -> Unit,
    onSettings: () -> Unit,
) {
    entry<ContinueWatchingRoute> {
        ContinueWatchingScreen(
            viewModel = koinViewModel<ContinueWatchingViewModel>(),
            onPlay = onPlay,
            onOpenTitle = onOpenTitle,
            onSettings = onSettings,
        )
    }
}

fun NavBackStack<NavKey>.navigateToSources() {
    add(element = SourcesRoute)
}

fun EntryProviderScope<NavKey>.sourcesEntry(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    entry<SourcesRoute> {
        SourcesScreen(
            viewModel = koinViewModel<SourcesViewModel>(),
            onBack = onBack,
            onAdd = onAdd,
            onEdit = onEdit,
        )
    }
}

@Serializable
data class TitleRoute(val sourceId: String, val item: LibraryItem) : NavKey

fun NavBackStack<NavKey>.navigateToTitle(sourceId: String, item: LibraryItem) {
    add(
        element = TitleRoute(
            sourceId = sourceId,
            item = item,
        ),
    )
}

fun EntryProviderScope<NavKey>.libraryEntry(
    onOpen: (String, LibraryItem) -> Unit,
    onSettings: () -> Unit,
) {
    entry<LibraryRoute> {
        LibraryScreen(
            viewModel = koinViewModel<LibraryViewModel>(),
            onOpen = onOpen,
            onSettings = onSettings,
        )
    }
}

fun EntryProviderScope<NavKey>.titleEntry(
    onBack: () -> Unit,
    onPlay: (List<PlayableItem>) -> Unit,
) {
    entry<TitleRoute> { route ->
        TitleDetailsScreen(
            viewModel = koinViewModel<TitleDetailsViewModel>(
                key = "${route.sourceId}:${route.item.kind}:${route.item.id}",
            ) {
                parametersOf(
                    route.sourceId,
                    route.item,
                )
            },
            onBack = onBack,
            onPlay = onPlay,
        )
    }
}

fun NavBackStack<NavKey>.navigateToAddSource(sourceId: String? = null) {
    add(element = AddSourceRoute(sourceId = sourceId))
}

fun EntryProviderScope<NavKey>.liveTvEntry(actions: LiveTvActions) {
    entry<LiveTvRoute> {
        LiveTvScreen(
            viewModel = koinViewModel<LiveTvViewModel>(),
            actions = actions,
        )
    }
}

fun EntryProviderScope<NavKey>.addSourceEntry(onNavigateUp: () -> Unit) {
    entry<AddSourceRoute> { route ->
        AddSourceScreen(
            viewModel = koinViewModel<AddSourceViewModel>(key = route.sourceId ?: "new") {
                parametersOf(route.sourceId)
            },
            onDone = onNavigateUp,
        )
    }
}
