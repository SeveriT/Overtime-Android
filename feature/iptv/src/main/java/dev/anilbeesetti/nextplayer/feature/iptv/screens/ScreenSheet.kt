package dev.anilbeesetti.nextplayer.feature.iptv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Makes a screen's content area a sheet with rounded top corners under the top bar, as Next Player's own
 * screens (Home, More, Playlists) draw it. Use it with
 * `Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer)` and the Scaffold's [padding];
 * only its top is applied here.
 */
@Composable
fun Modifier.screenSheet(padding: PaddingValues): Modifier = this
    .padding(top = padding.calculateTopPadding())
    .clip(
        RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
        ),
    )
    .background(MaterialTheme.colorScheme.background)
