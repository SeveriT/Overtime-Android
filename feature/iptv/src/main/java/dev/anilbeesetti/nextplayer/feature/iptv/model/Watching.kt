package dev.anilbeesetti.nextplayer.feature.iptv.model

import kotlinx.serialization.Serializable

/**
 * A movie or episode that was sent to the player from Movies & series. How far it got is kept by the
 * player itself (per stream address); this remembers what the address was, for Continue watching.
 */
@Serializable
data class WatchEntry(
    val url: String,
    val sourceId: String,
    /** The movie, or the series the episode belongs to. */
    val item: LibraryItem,
    /** The title the player shows. */
    val title: String,
    /** Landscape art for the card: the episode still or the backdrop, else the poster. */
    val image: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** When it was picked to play; 0 for an episode only queued after it. */
    val playedAt: Long = 0,
) {
    /** One card per movie or series. */
    val titleKey: String get() = "$sourceId:${item.kind}:${item.id}"

    val isEpisode: Boolean get() = item.kind == LibraryKind.SERIES

    /** Episode order within a series. */
    val order: Long get() = (season ?: 0) * 100_000L + (episode ?: 0)
}

/** How far the player got with one stream. [position] is negative once it played to the end. */
data class PlaybackProgress(val position: Long, val duration: Long?, val lastPlayed: Long?)

enum class ContinueKind {
    /** Started and not finished: play on from where it stopped. */
    RESUME,

    /** The last episode was finished: the next one is ready. */
    NEXT,

    /** The last known episode was finished: opens the series to pick what's next. */
    WATCHED,
}

data class ContinueItem(
    val key: String,
    val entry: WatchEntry,
    val kind: ContinueKind,
    val progress: Float,
    val remainingMs: Long?,
    /** What to play, first item first; empty for [ContinueKind.WATCHED]. */
    val queue: List<WatchEntry>,
    val lastWatched: Long,
)

/**
 * Builds the Continue watching list: one card per movie or series, newest first. A movie leaves the
 * list once it's finished; a series stays (showing its next episode) until it's dismissed. Dismissed
 * titles come back when they're played again.
 */
fun continueWatching(
    watching: List<WatchEntry>,
    dismissed: Map<String, Long>,
    states: Map<String, PlaybackProgress>,
): List<ContinueItem> {
    fun recency(entry: WatchEntry) = maxOf(
        states[entry.url]?.lastPlayed ?: 0L,
        entry.playedAt,
    )

    return watching
        .filter { recency(it) > 0 }
        .groupBy { it.titleKey }
        .mapNotNull { (key, played) ->
            val latest = played.maxBy(::recency)
            val at = recency(latest)
            if (at <= (dismissed[key] ?: 0L)) return@mapNotNull null
            val state = states[latest.url]
            val position = state?.position ?: 0L
            val duration = state?.duration?.takeIf { it > 0 }
            val finished = position < 0 || (duration != null && position >= duration * FINISHED_AT)
            val started = position >= STARTED_MS
            val progress = if (duration != null &&
                position > 0
            ) {
                (position.toFloat() / duration).coerceIn(
                    0f,
                    1f,
                )
            } else {
                0f
            }
            val remaining = if (duration != null && position > 0) duration - position else null

            if (!latest.isEpisode) {
                if (finished || !started) return@mapNotNull null
                return@mapNotNull ContinueItem(
                    key,
                    latest,
                    ContinueKind.RESUME,
                    progress,
                    remaining,
                    listOf(latest),
                    at,
                )
            }

            // Every episode of the series ever queued, in order.
            val all = watching.filter { it.titleKey == key }.sortedBy { it.order }
            fun seasonFrom(first: WatchEntry) = all.filter {
                it.season == first.season &&
                    it.order >= first.order
            }
            if (!finished) {
                ContinueItem(
                    key,
                    latest,
                    ContinueKind.RESUME,
                    if (started) progress else 0f,
                    remaining.takeIf {
                        started
                    },
                    seasonFrom(latest),
                    at,
                )
            } else {
                val next = all.firstOrNull { it.order > latest.order }
                if (next != null) {
                    ContinueItem(
                        key,
                        next,
                        ContinueKind.NEXT,
                        0f,
                        null,
                        seasonFrom(next),
                        at,
                    )
                } else {
                    ContinueItem(
                        key,
                        latest,
                        ContinueKind.WATCHED,
                        1f,
                        null,
                        emptyList(),
                        at,
                    )
                }
            }
        }
        .sortedByDescending { it.lastWatched }
}

/** Watched this long, a movie or episode counts as started (as on Windows). */
const val STARTED_MS = 10_000L

/** Played this far, it counts as watched (end credits). */
private const val FINISHED_AT = 0.95
