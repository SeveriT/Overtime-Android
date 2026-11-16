package dev.anilbeesetti.nextplayer.feature.iptv.screens.addsource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.feature.iptv.model.SourceType

/** Add or edit a playlist URL or an Xtream Codes login. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceScreen(viewModel: AddSourceViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = if (state.editing) "Edit source" else "Add source",
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(SourceType.M3U_URL to "M3U playlist", SourceType.XTREAM to "Xtream Codes").forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { viewModel.update { it.copy(type = type) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(label) }
                }
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = { v -> viewModel.update { it.copy(url = v) } },
                label = { Text(if (state.type == SourceType.XTREAM) "Server address" else "Playlist URL") },
                placeholder = { Text(if (state.type == SourceType.XTREAM) "http://example.com:8080" else "https://…/playlist.m3u") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.type == SourceType.XTREAM) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.epgUrl,
                onValueChange = { v -> viewModel.update { it.copy(epgUrl = v) } },
                label = { Text("XMLTV guide URL (optional)") },
                supportingText = {
                    Text(
                        if (state.type == SourceType.XTREAM) "Leave empty to use the provider's own guide."
                        else "Leave empty to use the guide the playlist names, if any. Separate several with commas.",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (state.editing) {
                    TextButton(onClick = { viewModel.delete(onDone) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                if (state.saving) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Button(onClick = { viewModel.save(onDone) }, enabled = state.canSave && !state.saving) {
                    Text(if (state.saving) "Loading channels…" else "Save")
                }
            }
        }
    }
}
