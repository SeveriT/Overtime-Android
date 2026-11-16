package dev.anilbeesetti.nextplayer.feature.player.timeshift

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Feeds the player from the live recording: from [Timeshift.Session.startOffset], following the
 * recording as it grows. Reports an unknown length, so ExoPlayer treats it as a live progressive
 * stream: a seek to 0 opens it again, which is how a rewind starts reading somewhere else.
 */
@UnstableApi
class TimeshiftDataSource(
    private val timeshift: Timeshift,
    private val headers: Map<String, String>,
) : BaseDataSource(false) {

    private var uri: Uri? = null
    private var session: Timeshift.Session? = null
    private var offset = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val key = dataSpec.uri.toString()
        val session = timeshift.sessionFor(
            key,
            headers,
        )
        this.session = session
        val rec = session.recorder
        // Wait for enough to be buffered (or to learn that this stream can't be recorded).
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (rec.status.value == TimeshiftRecorder.Status.Starting) {
            if (System.currentTimeMillis() > deadline) break
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
        when (val status = rec.status.value) {
            TimeshiftRecorder.Status.Ready -> Unit

            is TimeshiftRecorder.Status.Unsupported -> unsupported(
                key,
                status.reason,
            )

            is TimeshiftRecorder.Status.Failed -> unsupported(
                key,
                status.message,
            )

            TimeshiftRecorder.Status.Starting -> unsupported(
                key,
                "No data from the stream",
            )
        }
        if (session.startOffset < 0) session.startAt(session.newestWall)
        offset = session.startOffset + dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    private fun unsupported(key: String, reason: String): Nothing {
        timeshift.markDirect(key)
        throw TimeshiftUnsupportedException(reason)
    }

    override fun read(buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (length == 0) return 0
        val rec = session?.recorder ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val n = try {
                rec.read(
                    offset,
                    buffer,
                    length,
                    POLL_MS,
                    bufferOffset,
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            when (n) {
                TimeshiftRecorder.END -> return C.RESULT_END_OF_INPUT

                TimeshiftRecorder.FELL_BEHIND -> {
                    // Paused for longer than the buffer holds: the data here was dropped.
                    timeshift.fellBehind()
                    throw IOException("Fell behind the rewind buffer")
                }

                0 -> if (Thread.currentThread().isInterrupted) throw InterruptedIOException()

                else -> {
                    offset += n
                    bytesTransferred(n)
                    return n
                }
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        session = null
        uri = null
    }

    class Factory(private val timeshift: Timeshift, private val headers: Map<String, String>) :
        DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(
            timeshift,
            headers,
        )
    }

    private companion object {
        const val POLL_MS = 250L
        const val READY_TIMEOUT_MS = 20_000L
    }
}
