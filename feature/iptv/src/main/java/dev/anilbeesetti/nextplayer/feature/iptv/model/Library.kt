package dev.anilbeesetti.nextplayer.feature.iptv.model

import kotlinx.serialization.Serializable

/** On-demand content of an Xtream Codes provider. */
@Serializable
enum class LibraryKind { MOVIES, SERIES }

data class LibraryCategory(val id: String, val name: String)

/** A movie or a series as listed in a category. */
@Serializable
data class LibraryItem(
    /** stream_id (movies) or series_id (series). */
    val id: String,
    val kind: LibraryKind,
    val name: String,
    val poster: String? = null,
    /** Rating as the provider gives it, usually out of 10. */
    val rating: String? = null,
    val year: String? = null,
    val categoryId: String = "",
    /** Movie file extension for the play URL (mkv, mp4…). */
    val extension: String? = null,
    val backdrop: String? = null,
)

data class MovieDetails(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val duration: String?,
    val backdrop: String?,
)

data class Episode(
    val id: String,
    val number: Int,
    val title: String,
    val extension: String,
    val duration: String?,
    val plot: String?,
    val image: String?,
)

data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class SeriesDetails(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val releaseDate: String?,
    val backdrop: String?,
    val seasons: List<Season>,
)
