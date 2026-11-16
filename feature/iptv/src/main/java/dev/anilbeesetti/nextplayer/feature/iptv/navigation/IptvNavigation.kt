package dev.anilbeesetti.nextplayer.feature.iptv.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.iptv.screens.addsource.AddSourceScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.addsource.AddSourceViewModel
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvActions
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvScreen
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.LiveTvViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object LiveTvRoute : NavKey

@Serializable
data class AddSourceRoute(val sourceId: String? = null) : NavKey

fun NavBackStack<NavKey>.navigateToAddSource(sourceId: String? = null) {
    add(element = AddSourceRoute(sourceId = sourceId))
}

fun EntryProviderScope<NavKey>.liveTvEntry(actions: LiveTvActions) {
    entry<LiveTvRoute> {
        LiveTvScreen(viewModel = koinViewModel<LiveTvViewModel>(), actions = actions)
    }
}

fun EntryProviderScope<NavKey>.addSourceEntry(onNavigateUp: () -> Unit) {
    entry<AddSourceRoute> { route ->
        AddSourceScreen(
            viewModel = koinViewModel<AddSourceViewModel>(key = route.sourceId ?: "new") { parametersOf(route.sourceId) },
            onDone = onNavigateUp,
        )
    }
}
