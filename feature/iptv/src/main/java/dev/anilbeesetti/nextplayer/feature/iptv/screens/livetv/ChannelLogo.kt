package dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * A channel's logo, with [placeholder] shown only while it loads or when there is none (or it fails).
 * Drawing the placeholder underneath instead would show through logos with a transparent background.
 */
@Composable
internal fun ChannelLogo(
    url: String?,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
    placeholder: @Composable BoxScope.() -> Unit,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) placeholder()
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = imageModifier,
            )
        }
    }
}
