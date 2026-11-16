package dev.anilbeesetti.nextplayer.feature.player.timeshift

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlin.math.max

/**
 * Live rewind (Overtime): the playing live channel is recorded into a rolling on-disk buffer by a
 * [TimeshiftRecorder], and the player reads from that buffer instead of the network, so it can pause,
 * go back and return to live. One channel is recorded at a time.
 *
 * Positions are wall-clock times: [Session.startOffset] is where the player's current read began
 * (always on a keyframe), and the player's own position counts from there.
 */
class Timeshift(private val root: File, private val bufferMs: Long = BUFFER_MS) {

    class Session(val uri: String, val recorder: TimeshiftRecorder, val cushionMs: Long) {
        /** Byte offset the next read starts at; negative means "at live" (decided when ready). */
        @Volatile var startOffset: Long = -1L

        /** Wall time of the data at [startOffset]; 0 until playback has started from the buffer. */
        @Volatile var startWall: Long = 0L

        /** Newest moment playback may start at, a cushion behind live so it never runs dry. */
        val newestWall: Long get() = recorder.liveWall - max(
            cushionMs,
            recorder.liveDelayMs,
        )

        /** Points the next read at [wall]: the keyframe before it (or after it, with [preferLater]). */
        fun startAt(wall: Long, preferLater: Boolean = false) {
            val rec = recorder
            val newest = newestWall
            val target = wall.coerceIn(
                rec.earliestWall,
                max(
                    rec.earliestWall,
                    newest,
                ),
            )
            val offset = rec.keyframeNear(
                rec.offsetAt(target),
                preferLater,
                limit = rec.offsetAt(newest),
            )
            startOffset = offset
            startWall = rec.wallAt(offset)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile var session: Session? = null
        private set

    /** Streams that can't be recorded (or failed to): played straight from the network instead. */
    private val direct: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Called on the main thread when playback fell out of the buffer (paused longer than it holds). */
    @Volatile var onFellBehind: (() -> Unit)? = null

    fun isDirect(uri: String): Boolean = uri in direct

    /** The recording of [uri], started if needed; any other channel's recording is stopped. */
    @Synchronized
    fun sessionFor(uri: String, headers: Map<String, String>): Session {
        session?.takeIf { it.uri == uri }?.let { return it }
        stop()
        val recorder = TimeshiftRecorder(
            url = uri,
            headers = headers,
            bufferMs = bufferMs,
            readyAfterMs = CUSHION_MS,
            dir = File(
                root,
                UUID.randomUUID().toString(),
            ),
        )
        recorder.start()
        return Session(
            uri,
            recorder,
            CUSHION_MS,
        ).also { session = it }
    }

    /** Gives up recording [uri]; it plays straight from the network from now on. */
    @Synchronized
    fun markDirect(uri: String) {
        direct += uri
        if (session?.uri == uri) stop()
    }

    @Synchronized
    fun stop() {
        session?.recorder?.stop()
        session = null
    }

    /** Removes buffers left behind by a crash. */
    fun clearLeftovers() {
        if (session == null) root.deleteRecursively()
    }

    internal fun fellBehind() {
        main.post { onFellBehind?.invoke() }
    }

    companion object {
        /** How far back live TV can be rewound (the Windows app's default). */
        const val BUFFER_MS = 5 * 60_000L

        /** Live playback sits this far behind the recording so it never runs dry. */
        const val CUSHION_MS = 3_000L
    }
}

/** Thrown when a live stream can't be recorded, so the player falls back to playing it directly. */
class TimeshiftUnsupportedException(message: String) : java.io.IOException(message)
