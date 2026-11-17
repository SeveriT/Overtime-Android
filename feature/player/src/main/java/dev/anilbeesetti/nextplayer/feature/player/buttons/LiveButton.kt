package dev.anilbeesetti.nextplayer.feature.player.buttons

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * Overtime: LIVE marker for a recorded live channel, next to the playback speed button. A red dot
 * while watching live; behind live the dot is grey and a tap returns to live.
 *
 * Only a rewindable live channel is "dynamic" and seekable at once (movies aren't dynamic); its end
 * of the bar is live, and the position sits exactly there while watching live.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveButton(
    player: Player?,
    progressState: ProgressStateWithTickInterval,
    modifier: Modifier = Modifier,
) {
    if (player == null || !player.isCurrentMediaItemDynamic ||
        !player.isCurrentMediaItemSeekable
    ) {
        return
    }
    val duration = progressState.durationMs
    if (duration == C.TIME_UNSET || duration <= 0) return
    val atLive = duration - progressState.currentPositionMs <= AT_LIVE_MARGIN_MS
    val description = stringResource(if (atLive) R.string.player_live else R.string.player_go_live)
    PlayerButton(
        modifier = modifier.semantics { contentDescription = description },
        onClick = { if (!atLive) player.seekToDefaultPosition() },
        containerColor = PlayerButtonBlackAlpha,
        contentPadding = PaddingValues(
            horizontal = 10.dp,
            vertical = 6.dp,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).background(
                    if (atLive) LIVE_RED else Color.White.copy(alpha = 0.5f),
                    CircleShape,
                ),
            )
            Text(
                text = stringResource(R.string.player_live),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (atLive) Color.White else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** The bar reports the live edge exactly while live; this absorbs the screen's own rounding. */
private const val AT_LIVE_MARGIN_MS = 1_000L

private val LIVE_RED = Color(0xFFE53935)
