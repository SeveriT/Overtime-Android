package dev.anilbeesetti.nextplayer.feature.iptv.screens.hidden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.screens.livetv.ChannelLogo
import dev.anilbeesetti.nextplayer.feature.iptv.screens.screenSheet
import org.koin.compose.koinInject

/**
 * Settings > Hidden channels: every channel hidden from Live TV (long-press > Hide channel), with
 * Show to bring it back at once.
 */
@Composable
fun HiddenChannelsScreen(onNavigateUp: () -> Unit) {
    val repository = koinInject<IptvRepository>()
    val known by repository.channels.collectAsState()
    val hidden by repository.hidden.collectAsState()
    val sources by repository.sources.collectAsState()
    val channels = remember(
        known,
        hidden,
    ) {
        known.values.flatten().filter { it.id in hidden }.distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.hidden_channels),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().screenSheet(padding),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 16.dp,
                bottom = 16.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    stringResource(
                        if (channels.isEmpty()) {
                            R.string.hidden_channels_empty
                        } else {
                            R.string.hidden_channels_info
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                )
            }
            items(
                channels,
                key = { it.id },
            ) { channel ->
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = 8.dp,
                        vertical = 6.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(
                        url = channel.logo,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        imageModifier = Modifier.fillMaxSize().padding(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.LiveTv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val where = listOfNotNull(
                            channel.group.takeIf { it.isNotBlank() },
                            sources.firstOrNull { it.id == channel.sourceId }?.name?.takeIf {
                                sources.size >
                                    1
                            },
                        ).joinToString(" \u00B7 ")
                        if (where.isNotEmpty()) {
                            Text(
                                where,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = {
                        repository.setHidden(
                            channel.id,
                            false,
                        )
                    }) {
                        Icon(
                            Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.live_tv_show_channel))
                    }
                }
            }
        }
    }
}
