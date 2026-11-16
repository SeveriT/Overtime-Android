package dev.anilbeesetti.nextplayer.feature.iptv.screens.addsource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.feature.iptv.data.IptvRepository
import dev.anilbeesetti.nextplayer.feature.iptv.model.IptvSource
import dev.anilbeesetti.nextplayer.feature.iptv.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

data class AddSourceUiState(
    val type: SourceType = SourceType.M3U_URL,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val editing: Boolean = false,
) {
    val canSave: Boolean get() = url.isNotBlank() && (type == SourceType.M3U_URL || (username.isNotBlank() && password.isNotBlank()))
}

/** Adds a playlist or Xtream login (or edits one), then loads its channels and guide straight away. */
@KoinViewModel
class AddSourceViewModel(
    private val repository: IptvRepository,
    @InjectedParam private val sourceId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        repository.sources.value.firstOrNull { it.id == sourceId }?.let {
            AddSourceUiState(it.type, it.name, it.url, it.username, it.password, it.epgUrl, editing = true)
        } ?: AddSourceUiState(),
    )
    val state: StateFlow<AddSourceUiState> = _state.asStateFlow()

    fun update(transform: (AddSourceUiState) -> AddSourceUiState) = _state.update { transform(it).copy(error = null) }

    /** Saves and loads the source; [onDone] runs once the channels are in. */
    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving) return
        val source = IptvSource(
            id = sourceId ?: repository.newSourceId(),
            name = s.name.trim().ifEmpty { defaultName(s) },
            type = s.type,
            url = s.url.trim(),
            username = s.username.trim(),
            password = s.password,
            epgUrl = s.epgUrl.trim(),
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.refreshChannels(source)
                repository.saveSource(source)
                onDone()
                // The guide can take a while; it fills in on the Live TV screen.
                // Always fetched anew here: the guide address may just have changed.
                runCatching { repository.refreshGuide(source, maxAgeMs = 0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: e::class.simpleName) }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        sourceId?.let(repository::removeSource)
        onDone()
    }

    private fun defaultName(s: AddSourceUiState): String =
        runCatching { java.net.URI(s.url.trim()).host }.getOrNull()?.removePrefix("www.") ?: "Playlist"
}
