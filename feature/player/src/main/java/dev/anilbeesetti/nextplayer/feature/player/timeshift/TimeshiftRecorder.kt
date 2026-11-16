// Ported from Overtime for Windows (fi.severi.overtime.player); keep the two in step.
package dev.anilbeesetti.nextplayer.feature.player.timeshift

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records a live stream into a rolling on-disk buffer so playback can pause, rewind and return to live.
 *
 * MPEG-TS streams are stored as-is (whole 188-byte packets only); live HLS is flattened by appending its
 * TS segments. Anything else — on-demand, encrypted or fMP4 HLS, unknown formats — reports
 * [Status.Unsupported] so the caller can play the URL directly instead.
 *
 * Data is kept in chunk files of a few seconds each; whole chunks older than [bufferMs] behind the live
 * edge are deleted. Offsets are logical (bytes since recording started) and map to wall-clock time
 * through marks recorded as data arrives.
 */
class TimeshiftRecorder(
    private val url: String,
    private val headers: Map<String, String>,
    private val bufferMs: Long,
    /** Status turns [Status.Ready] once this much has been buffered (the playback jitter cushion). */
    private val readyAfterMs: Long,
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Status {
        data object Starting : Status
        data object Ready : Status
        data class Unsupported(val reason: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private class Chunk(val file: File, val startOffset: Long, val createdAt: Long) {
        val raf = RandomAccessFile(
            file,
            "rw",
        )
        var endOffset = startOffset
    }

    private class Mark(val offset: Long, val wall: Long)

    private class UnsupportedStream(reason: String) : Exception(reason)

    private enum class Format { TS, HLS }

    private val lock = ReentrantLock()
    private val newData = lock.newCondition()
    private val chunks = ArrayDeque<Chunk>()
    private val marks = ArrayList<Mark>()

    /** Offsets of packets that start a video keyframe, oldest first; trimmed with the buffer. */
    private val keyframes = ArrayList<Long>()
    private val scanner = TsKeyframeScanner()
    private var lastPatOffset = -1L
    private var written = 0L
    private var chunkCounter = 0

    @Volatile private var stopped = false

    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null

    // HLS position, kept across reconnects.
    private var hlsNextSequence = -1L
    private var hlsCursor = 0L

    /**
     * How far behind the live edge playback should sit so it never runs dry. For a continuous TS stream
     * that is [readyAfterMs]; HLS data arrives a whole segment at a time, so it needs ~1.5 segments.
     */
    @Volatile var liveDelayMs: Long = readyAfterMs
        private set

    /** "MPEG-TS" or "HLS" once the stream type is known. */
    @Volatile var formatLabel: String? = null
        private set

    /** How much the live edge jumps at once: one segment for HLS, nothing for continuous TS. */
    @Volatile var edgeStepMs: Long = 0L
        private set

    fun start() {
        dir.mkdirs()
        worker = thread(
            name = "timeshift-recorder",
            isDaemon = true,
        ) { run() }
    }

    /** Stops recording and deletes the buffer. Unblocks any waiting reader. */
    fun stop() {
        stopped = true
        connection?.disconnect()
        worker?.interrupt()
        lock.withLock {
            chunks.forEach { runCatching { it.raf.close() } }
            chunks.clear()
            marks.clear()
            keyframes.clear()
            newData.signalAll()
        }
        dir.deleteRecursively()
    }

    val earliestOffset: Long get() = lock.withLock { chunks.firstOrNull()?.startOffset ?: 0L }
    val latestOffset: Long get() = lock.withLock { written }
    val earliestWall: Long get() = lock.withLock {
        wallAtLocked(chunks.firstOrNull()?.startOffset ?: 0L)
    }
    val liveWall: Long get() = lock.withLock { wallAtLocked(written) }

    fun wallAt(offset: Long): Long = lock.withLock { wallAtLocked(offset) }

    /**
     * The keyframe to start playback at for [target], so the picture appears at once: the last one at
     * or before it, or with [preferLater] (seeking forward) the first one after it up to [limit].
     * Falls back to the other side, then to [target] itself when no keyframes are known.
     */
    fun keyframeNear(target: Long, preferLater: Boolean, limit: Long): Long = lock.withLock {
        val before = keyframes.lastOrNull { it <= target }
        val after = keyframes.firstOrNull { it >= target && it <= limit }
        (if (preferLater) after ?: before else before ?: after) ?: target
    }

    /** Average time between keyframes (0 until two are seen); live playback can sit this far behind. */
    val keyframeIntervalMs: Long get() = lock.withLock {
        if (keyframes.size < 2) {
            0L
        } else {
            (wallAtLocked(keyframes.last()) - wallAtLocked(keyframes.first())) /
                (keyframes.size - 1)
        }
    }

    /** The packet-aligned offset whose data arrived at [wall], clamped to what is buffered. */
    fun offsetAt(wall: Long): Long = lock.withLock {
        val earliest = chunks.firstOrNull()?.startOffset ?: 0L
        if (marks.isEmpty()) return earliest
        val raw = when {
            wall <= marks.first().wall -> marks.first().offset

            wall >= marks.last().wall -> marks.last().offset

            else -> {
                var lo = 0
                var hi = marks.lastIndex
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (marks[mid].wall <= wall) lo = mid else hi = mid
                }
                val a = marks[lo]
                val b = marks[hi]
                if (b.wall == a.wall) {
                    a.offset
                } else {
                    a.offset + ((b.offset - a.offset) * (wall - a.wall) / (b.wall - a.wall))
                }
            }
        }
        (raw - raw % TS_PACKET).coerceIn(
            earliest,
            written - written % TS_PACKET,
        )
    }

    /**
     * Reads buffered bytes at [offset]. Blocks up to [timeoutMs] for new data at the live edge.
     * Returns the byte count, 0 on timeout, [END] once stopped, or [FELL_BEHIND] if [offset] has
     * already been dropped from the buffer.
     */
    fun read(
        offset: Long,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Long,
        bufferOffset: Int = 0,
    ): Int = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if (stopped) return END
            val first = chunks.firstOrNull()
            if (first != null && offset < first.startOffset) return FELL_BEHIND
            if (offset < written) break
            if (remaining <= 0) return 0
            remaining = newData.awaitNanos(remaining)
        }
        val chunk = chunks.last { it.startOffset <= offset }
        val count = min(
            length.toLong(),
            chunk.endOffset - offset,
        ).toInt()
        chunk.raf.seek(offset - chunk.startOffset)
        chunk.raf.readFully(
            buffer,
            bufferOffset,
            count,
        )
        count
    }

    // ---- recording ------------------------------------------------------------------

    /** Downloads until stopped, reconnecting on drops. Gives up after 3 failed starts or a 30 s stall. */
    private fun run() {
        var format: Format? = null
        var failures = 0
        while (!stopped) {
            try {
                val conn = open(url)
                val input = BufferedInputStream(
                    conn.inputStream,
                    64 * 1024,
                )
                if (format == null) {
                    format =
                        detect(input) ?: throw UnsupportedStream("Not an MPEG-TS or HLS stream")
                    formatLabel = if (format == Format.HLS) "HLS" else "MPEG-TS"
                }
                val before = written
                when (format) {
                    Format.TS -> pumpTs(input)

                    Format.HLS -> runHls(
                        conn.url.toString(),
                        input.readBytes().decodeToString(),
                    )
                }
                if (written >
                    before
                ) {
                    failures = 0
                } else {
                    throw IOException("Stream ended without data")
                }
            } catch (e: UnsupportedStream) {
                _status.value = Status.Unsupported(e.message.orEmpty())
                return
            } catch (e: Exception) {
                if (stopped) return
                failures++
                val neverStarted = written == 0L && failures >= 3
                val stalled = written > 0 && clock() - lastAppendAt > STALL_TIMEOUT_MS
                if (neverStarted || stalled) {
                    _status.value = Status.Failed(e.message ?: e::class.simpleName.orEmpty())
                    return
                }
                runCatching {
                    Thread.sleep(
                        min(
                            1_000L * failures,
                            5_000L,
                        ),
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    @Volatile private var lastAppendAt = 0L

    private fun detect(input: BufferedInputStream): Format? {
        input.mark(TS_PACKET * 2 + 16)
        val head = readUpTo(
            input,
            TS_PACKET * 2 + 1,
        )
        input.reset()
        val text = head.decodeToString().trimStart(
            '\uFEFF',
            ' ',
            '\r',
            '\n',
            '\t',
        )
        return when {
            text.startsWith("#EXTM3U") -> Format.HLS

            head.isNotEmpty() && head[0] == SYNC &&
                (head.size <= TS_PACKET || head[TS_PACKET] == SYNC) -> Format.TS

            else -> null
        }
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = input.read(
                bytes,
                filled,
                count - filled,
            )
            if (n < 0) break
            filled += n
        }
        return if (filled == count) bytes else bytes.copyOf(filled)
    }

    /** Appends whole TS packets, resyncing on the 0x47 sync byte if the stream drifts. */
    private fun pumpTs(input: InputStream) {
        val buffer = ByteArray(TS_PACKET * 512)
        var filled = 0
        while (!stopped) {
            val n = input.read(
                buffer,
                filled,
                buffer.size - filled,
            )
            if (n < 0) return
            filled += n
            var start = 0
            if (buffer[0] != SYNC) {
                start = findSync(
                    buffer,
                    filled,
                )
                if (start < 0) {
                    filled = 0
                    continue
                }
            }
            val usable = (filled - start) / TS_PACKET * TS_PACKET
            if (usable > 0) {
                val now = clock()
                append(
                    buffer,
                    start,
                    usable,
                    wallStart = now,
                    wallEnd = now,
                )
            }
            val rest = filled - start - usable
            System.arraycopy(
                buffer,
                start + usable,
                buffer,
                0,
                rest,
            )
            filled = rest
        }
    }

    private fun findSync(buffer: ByteArray, length: Int): Int {
        for (i in 0 until length) {
            if (buffer[i] == SYNC &&
                (i + TS_PACKET >= length || buffer[i + TS_PACKET] == SYNC)
            ) {
                return i
            }
        }
        return -1
    }

    private fun runHls(firstUrl: String, firstText: String) {
        var playlistUrl = firstUrl
        var text = firstText
        if (text.contains("#EXT-X-STREAM-INF")) {
            playlistUrl =
                pickVariant(
                    firstUrl,
                    text,
                )
                    ?: throw UnsupportedStream("HLS master playlist has no variants")
            text = fetch(playlistUrl).decodeToString()
        }
        while (!stopped) {
            val playlist = HlsPlaylist.parse(
                playlistUrl,
                text,
            )
            when {
                playlist.endList -> throw UnsupportedStream("On-demand stream")
                playlist.encrypted -> throw UnsupportedStream("Encrypted HLS")
                playlist.fragmentedMp4 -> throw UnsupportedStream("fMP4 HLS")
            }
            if (hlsNextSequence < 0) {
                edgeStepMs = playlist.targetDurationMs
                liveDelayMs = max(
                    readyAfterMs,
                    playlist.targetDurationMs * 3 / 2,
                )
                val startIndex = max(
                    0,
                    playlist.segments.size - HLS_START_SEGMENTS,
                )
                hlsNextSequence = playlist.mediaSequence + startIndex
                hlsCursor = clock() - playlist.segments.drop(startIndex).sumOf { it.durationMs }
            }
            if (hlsNextSequence < playlist.mediaSequence) hlsNextSequence = playlist.mediaSequence
            var fetched = false
            for ((index, segment) in playlist.segments.withIndex()) {
                val sequence = playlist.mediaSequence + index
                if (sequence < hlsNextSequence || stopped) continue
                val bytes = fetch(segment.url)
                if (written == 0L &&
                    (bytes.isEmpty() || bytes[0] != SYNC)
                ) {
                    throw UnsupportedStream("HLS segments are not MPEG-TS")
                }
                // After a long stall, re-anchor the timeline so "live" stays near the wall clock.
                if (hlsCursor < clock() - HLS_RESYNC_MS) hlsCursor = clock() - segment.durationMs
                val start = hlsCursor
                hlsCursor += segment.durationMs
                append(
                    bytes,
                    0,
                    bytes.size,
                    wallStart = start,
                    wallEnd = hlsCursor,
                )
                hlsNextSequence = sequence + 1
                fetched = true
            }
            val pause = if (fetched) {
                500L
            } else {
                (playlist.targetDurationMs / 2).coerceIn(
                    1_000L,
                    5_000L,
                )
            }
            Thread.sleep(pause)
            text = fetch(playlistUrl).decodeToString()
        }
    }

    private fun pickVariant(baseUrl: String, master: String): String? {
        var best: Pair<Long, String>? = null
        var pendingBandwidth: Long? = null
        for (raw in master.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingBandwidth =
                        Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                            ?: 0L

                line.isNotEmpty() && !line.startsWith("#") && pendingBandwidth != null -> {
                    val bw = pendingBandwidth
                    if (best == null || bw > best.first) {
                        best = bw to resolve(
                            baseUrl,
                            line,
                        )
                    }
                    pendingBandwidth = null
                }
            }
        }
        return best?.second
    }

    private fun append(
        data: ByteArray,
        from: Int,
        length: Int,
        wallStart: Long,
        wallEnd: Long,
    ): Unit = lock.withLock {
        if (stopped) return
        if (marks.isEmpty()) {
            marks += Mark(
                written,
                wallStart,
            )
        }
        var chunk = chunks.lastOrNull()
        val now = clock()
        if (chunk == null || chunk.endOffset - chunk.startOffset >= CHUNK_BYTES ||
            now - chunk.createdAt >= CHUNK_MS
        ) {
            chunk = Chunk(
                File(
                    dir,
                    "chunk-${chunkCounter++}.ts",
                ),
                written,
                now,
            )
            chunks += chunk
        }
        var packet = from
        while (packet + TS_PACKET <= from + length) {
            val at = written + (packet - from)
            if (scanner.isPat(
                    data,
                    packet,
                )
            ) {
                lastPatOffset = at
            }
            if (scanner.isKeyframe(
                    data,
                    packet,
                )
            ) {
                // Start from the tables just before it: VLC restarts at this offset and can't play anything
                // until it has seen a PAT and PMT, which HLS segments carry only once, at their start.
                keyframes +=
                    if (lastPatOffset >= 0 &&
                        at - lastPatOffset <= PAT_REACH_BYTES
                    ) {
                        lastPatOffset
                    } else {
                        at
                    }
            }
            packet += TS_PACKET
        }
        chunk.raf.seek(chunk.endOffset - chunk.startOffset)
        chunk.raf.write(
            data,
            from,
            length,
        )
        chunk.endOffset += length
        written += length
        lastAppendAt = now
        addMark(
            Mark(
                written,
                wallEnd,
            ),
        )
        prune()
        if (_status.value == Status.Starting && isReadyLocked(wallEnd)) _status.value = Status.Ready
        newData.signalAll()
    }

    /**
     * Enough is buffered to play smoothly — and, when the video stream is known, a keyframe with that much
     * after it, so playback starts with a picture rather than sound over black. Gives up waiting for a
     * keyframe after [KEYFRAME_WAIT_MS] in case this stream's keyframes can't be recognised.
     */
    private fun isReadyLocked(wallEnd: Long): Boolean {
        val buffered = wallEnd - marks.first().wall
        if (buffered < liveDelayMs) return false
        if (!scanner.knowsVideo || buffered >= KEYFRAME_WAIT_MS) return true
        return keyframes.any { wallEnd - wallAtLocked(it) >= liveDelayMs }
    }

    /** Keeps roughly two marks per second: a new mark replaces the last one if it is close in time. */
    private fun addMark(mark: Mark) {
        if (marks.size >= 2 &&
            mark.wall - marks[marks.size - 2].wall < MARK_SPACING_MS
        ) {
            marks[marks.lastIndex] = mark
        } else {
            marks += mark
        }
    }

    private fun prune() {
        val cutoff = marks.last().wall - bufferMs
        var dropped = false
        while (chunks.size > 1 && wallAtLocked(chunks[1].startOffset) <= cutoff) {
            val old = chunks.removeFirst()
            runCatching { old.raf.close() }
            old.file.delete()
            dropped = true
        }
        if (dropped) {
            val earliest = chunks.first().startOffset
            val earliestWall = wallAtLocked(earliest)
            marks.removeAll { it.offset < earliest }
            keyframes.removeAll { it < earliest }
            if (marks.isEmpty() ||
                marks.first().offset > earliest
            ) {
                marks.add(
                    0,
                    Mark(
                        earliest,
                        earliestWall,
                    ),
                )
            }
        }
    }

    private fun wallAtLocked(offset: Long): Long {
        if (marks.isEmpty()) return clock()
        if (offset <= marks.first().offset) return marks.first().wall
        if (offset >= marks.last().offset) return marks.last().wall
        var lo = 0
        var hi = marks.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (marks[mid].offset <= offset) lo = mid else hi = mid
        }
        val a = marks[lo]
        val b = marks[hi]
        if (b.offset == a.offset) return a.wall
        return a.wall + (b.wall - a.wall) * (offset - a.offset) / (b.offset - a.offset)
    }

    // ---- HTTP -------------------------------------------------------------------------

    /** Opens [target], following redirects across hosts and http/https. */
    private fun open(target: String): HttpURLConnection {
        var current = target
        repeat(MAX_REDIRECTS) {
            val conn = URI(current).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers.forEach { (name, value) ->
                conn.setRequestProperty(
                    name,
                    value,
                )
            }
            connection = conn
            if (stopped) throw IOException("Stopped")
            val code = conn.responseCode
            when {
                code in 300..399 -> {
                    val location =
                        conn.getHeaderField("Location")
                            ?: throw IOException("Redirect without location")
                    conn.disconnect()
                    current = resolve(
                        current,
                        location,
                    )
                }

                code >= 400 -> {
                    conn.disconnect()
                    throw IOException("HTTP $code")
                }

                else -> return conn
            }
        }
        throw IOException("Too many redirects")
    }

    private fun fetch(target: String): ByteArray {
        val conn = open(target)
        try {
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val END = -1
        const val FELL_BEHIND = -2
        const val TS_PACKET = 188
        private const val SYNC: Byte = 0x47
        private const val CHUNK_BYTES = 4L * 1024 * 1024
        private const val CHUNK_MS = 10_000L
        private const val MARK_SPACING_MS = 500L
        private const val STALL_TIMEOUT_MS = 30_000L
        private const val HLS_START_SEGMENTS = 3
        private const val HLS_RESYNC_MS = 60_000L
        private const val MAX_REDIRECTS = 5
        private const val KEYFRAME_WAIT_MS = 10_000L

        /** How far before a keyframe the tables may be and still be included (~a few hundred ms of 4K). */
        private const val PAT_REACH_BYTES = 4096L * TS_PACKET

        internal fun resolve(base: String, relative: String): String = runCatching {
            URI(base).resolve(
                relative.replace(
                    " ",
                    "%20",
                ),
            ).toString()
        }.getOrDefault(relative)
    }
}

/** Minimal HLS media playlist model: just what live recording needs. */
internal class HlsPlaylist(
    val mediaSequence: Long,
    val targetDurationMs: Long,
    val segments: List<Segment>,
    val endList: Boolean,
    val encrypted: Boolean,
    val fragmentedMp4: Boolean,
) {
    class Segment(val url: String, val durationMs: Long)

    companion object {
        fun parse(baseUrl: String, text: String): HlsPlaylist {
            var sequence = 0L
            var target = 6_000L
            var endList = false
            var encrypted = false
            var fmp4 = false
            var pendingDuration: Long? = null
            val segments = ArrayList<Segment>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        sequence =
                            line.substringAfter(':').trim().toLongOrNull() ?: 0L

                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        target = (
                            (
                                line.substringAfter(
                                    ':',
                                ).trim().toDoubleOrNull() ?: 6.0
                                ) * 1000
                            ).toLong()

                    line.startsWith("#EXTINF:") ->
                        pendingDuration = (
                            (
                                line.substringAfter(
                                    ':',
                                ).substringBefore(',').trim().toDoubleOrNull()
                                    ?: 0.0
                                ) *
                                1000
                            ).toLong()

                    line.startsWith("#EXT-X-ENDLIST") -> endList = true

                    line.startsWith("#EXT-X-KEY") -> if (!line.contains("METHOD=NONE")) {
                        encrypted =
                            true
                    }

                    line.startsWith("#EXT-X-MAP") -> fmp4 = true

                    line.isNotEmpty() && !line.startsWith("#") -> {
                        segments +=
                            Segment(
                                TimeshiftRecorder.resolve(
                                    baseUrl,
                                    line,
                                ),
                                pendingDuration ?: target,
                            )
                        pendingDuration = null
                    }
                }
            }
            return HlsPlaylist(
                sequence,
                target,
                segments,
                endList,
                encrypted,
                fmp4,
            )
        }
    }
}
