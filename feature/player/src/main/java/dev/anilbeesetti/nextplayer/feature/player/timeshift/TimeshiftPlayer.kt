package dev.anilbeesetti.nextplayer.feature.player.timeshift

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.math.max

/**
 * Makes a recorded live channel look like an ordinary seekable video spanning the rewind buffer, so
 * the existing seek bar, double-tap and swipe seeking, and the notification all rewind live TV. The
 * end of the bar is live. Everything else passes straight through to [player].
 *
 * The bar starts at the oldest moment kept ([Bar.origin]) and only moves on when the buffer drops a
 * whole chunk (~10 s), which listeners are told about, like a live HLS window. In between, the
 * position advances with playback: the player screen estimates the position between updates by
 * assuming exactly that, so anything else makes the knob jump back and forth.
 *
 * A seek restarts reading at the keyframe nearest the target (see [TimeshiftDataSource]). Seeks that
 * come in quick succession (dragging, repeated double taps) are gathered and only the last one is
 * carried out; until then the target is reported as the position, so taps add up.
 */
@UnstableApi
class TimeshiftPlayer(private val player: Player, private val timeshift: Timeshift) :
    ForwardingPlayer(player) {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<Player.Listener, Player.Listener>()
    private var wasActive = false

    /** The bar's start and live edge for the session it was made for. */
    private class Bar(val session: Timeshift.Session) {
        var origin = 0L
        var live = 0L
        var notifiedOrigin = 0L
    }

    private var bar: Bar? = null

    /** Wall time of a seek still to be carried out (and reported as the position meanwhile). */
    private var pendingWall: Long? = null
    private val applySeek = Runnable {
        val wall = pendingWall ?: return@Runnable
        pendingWall = null
        active()?.let {
            seekWall(
                it,
                wall,
            )
        }
    }

    init {
        timeshift.onFellBehind = {
            active()?.let {
                seekWall(
                    it,
                    it.recorder.earliestWall + it.cushionMs,
                    preferLater = true,
                )
            }
        }
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (generateSequence<Throwable>(error) {
                            it.cause
                        }.any { it is TimeshiftUnsupportedException }
                    ) {
                        playDirect()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Another channel or a video: stop recording the old one.
                    pendingWall = null
                    main.removeCallbacks(applySeek)
                    val session = timeshift.session ?: return
                    if (mediaItem?.localConfiguration?.uri?.toString() !=
                        session.uri
                    ) {
                        timeshift.stop()
                    }
                    tick()
                }
            },
        )
        main.post(::tickLoop)
    }

    /** The recording session of the item now playing, once playback runs from it. */
    private fun active(): Timeshift.Session? {
        val session = timeshift.session ?: return null
        if (session.startWall <= 0L) return null
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return null
        return session.takeIf {
            it.uri == uri &&
                it.recorder.status.value == TimeshiftRecorder.Status.Ready
        }
    }

    /** The stream can't be recorded: play it as it is (a changed cache key makes a fresh source). */
    private fun playDirect() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        player.replaceMediaItem(
            index,
            item.buildUpon().setCustomCacheKey("direct").build(),
        )
        player.prepare()
        player.play()
    }

    // ---- the bar: origin = oldest moment kept, end = live --------------------------------

    private class Window(val start: Long, val live: Long, val position: Long)

    private fun barFor(session: Timeshift.Session): Bar =
        bar?.takeIf { it.session === session } ?: Bar(session).also { bar = it }

    private fun window(session: Timeshift.Session): Window {
        val rec = session.recorder
        val b = barFor(session)
        // Steps forward only when the recorder drops its oldest chunk.
        b.origin = max(
            b.origin,
            rec.earliestWall,
        )
        val dataEdge = max(
            b.origin,
            rec.liveWall,
        )
        // HLS data lands a segment at a time: let live follow the clock, never past one segment.
        b.live = max(
            b.live,
            System.currentTimeMillis().coerceIn(
                dataEdge,
                dataEdge + rec.edgeStepMs,
            ),
        )
        val playhead = pendingWall ?: (session.startWall + player.currentPosition)
        return Window(
            b.origin,
            b.live,
            playhead.coerceIn(
                b.origin,
                b.live,
            ),
        )
    }

    override fun getDuration(): Long = active()?.let {
        val w = window(it)
        w.live - w.start
    } ?: super.getDuration()

    override fun getContentDuration(): Long =
        active()?.let { duration } ?: super.getContentDuration()

    override fun getCurrentPosition(): Long = active()?.let {
        val w = window(it)
        w.position - w.start
    } ?: super.getCurrentPosition()

    override fun getContentPosition(): Long =
        active()?.let { currentPosition } ?: super.getContentPosition()

    override fun getBufferedPosition(): Long = active()?.let {
        val w = window(it)
        (w.position - w.start + player.totalBufferedDuration).coerceAtMost(w.live - w.start)
    } ?: super.getBufferedPosition()

    override fun getContentBufferedPosition(): Long =
        active()?.let { bufferedPosition } ?: super.getContentBufferedPosition()

    override fun isCurrentMediaItemSeekable(): Boolean =
        active() != null || super.isCurrentMediaItemSeekable()

    override fun isCurrentMediaItemLive(): Boolean =
        active() == null && super.isCurrentMediaItemLive()

    override fun isCurrentMediaItemDynamic(): Boolean =
        active() != null || super.isCurrentMediaItemDynamic()

    override fun getCurrentTimeline(): Timeline {
        val timeline = super.getCurrentTimeline()
        val session = active() ?: return timeline
        if (timeline.isEmpty) return timeline
        val index = player.currentMediaItemIndex
        val w = window(session)
        val durationUs = (w.live - w.start) * 1000
        return object : ForwardingTimeline(timeline) {
            override fun getWindow(
                windowIndex: Int,
                window: Timeline.Window,
                defaultPositionProjectionUs: Long,
            ): Timeline.Window {
                super.getWindow(
                    windowIndex,
                    window,
                    defaultPositionProjectionUs,
                )
                if (windowIndex == index) {
                    window.isSeekable = true
                    window.isDynamic = true
                    window.liveConfiguration = null
                    window.durationUs = durationUs
                    window.defaultPositionUs = durationUs
                }
                return window
            }

            override fun getPeriod(
                periodIndex: Int,
                period: Timeline.Period,
                setIds: Boolean,
            ): Timeline.Period {
                super.getPeriod(
                    periodIndex,
                    period,
                    setIds,
                )
                if (period.windowIndex == index) period.durationUs = durationUs
                return period
            }
        }
    }

    private val seekCommands = listOf(
        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        COMMAND_SEEK_BACK,
        COMMAND_SEEK_FORWARD,
        COMMAND_SEEK_TO_DEFAULT_POSITION,
    )

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (active() == null) return commands
        return commands.buildUpon().addAll(*seekCommands.toIntArray()).build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        (active() != null && command in seekCommands) || super.isCommandAvailable(command)

    // ---- seeking ------------------------------------------------------------------------

    /** Restarts reading at the keyframe nearest [wall] (or the next one, with [preferLater]). */
    private fun seekWall(session: Timeshift.Session, wall: Long, preferLater: Boolean = false) {
        session.startAt(
            wall,
            if (preferLater) Snap.LATER else Snap.NEAREST,
        )
        Log.d(
            TAG,
            "seek: target ${(session.recorder.liveWall - wall) / 1000.0} s behind live, " +
                "keyframe ${(session.recorder.liveWall - session.startWall) / 1000.0} s behind",
        )
        // Unseekable live progressive media restarts reading when sent to 0: see TimeshiftDataSource.
        player.seekTo(
            player.currentMediaItemIndex,
            0L,
        )
    }

    /** Takes a seek to [positionMs] on the bar; carried out once seeks stop coming in. */
    private fun seekBar(positionMs: Long): Boolean {
        val session = active() ?: return false
        val w = window(session)
        pendingWall = (w.start + positionMs).coerceIn(
            w.start,
            max(
                w.start,
                session.newestWall,
            ),
        )
        main.removeCallbacks(applySeek)
        main.postDelayed(
            applySeek,
            SEEK_SETTLE_MS,
        )
        return true
    }

    override fun seekTo(positionMs: Long) {
        if (!seekBar(positionMs)) super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex != player.currentMediaItemIndex ||
            !seekBar(positionMs)
        ) {
            super.seekTo(
                mediaItemIndex,
                positionMs,
            )
        }
    }

    override fun seekBack() {
        if (!seekBar(currentPosition - seekBackIncrement)) super.seekBack()
    }

    override fun seekForward() {
        if (!seekBar(currentPosition + seekForwardIncrement)) super.seekForward()
    }

    /** Back to live, at once. */
    override fun seekToDefaultPosition() {
        val session = active() ?: return super.seekToDefaultPosition()
        pendingWall = null
        main.removeCallbacks(applySeek)
        seekWall(
            session,
            Long.MAX_VALUE,
        )
    }

    // ---- listeners: report the seekable view, not the raw live stream ------------------

    override fun addListener(listener: Player.Listener) {
        val proxy = Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader,
            arrayOf(Player.Listener::class.java),
            ListenerHandler(listener),
        ) as Player.Listener
        listeners[listener] = proxy
        super.addListener(proxy)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
            ?: super.removeListener(listener)
    }

    private inner class ListenerHandler(private val target: Player.Listener) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: java.lang.reflect.Method,
            args: Array<out Any?>?,
        ): Any? {
            val arguments = args ?: emptyArray()
            // Matched by parameter types, not names: R8 may rename the listener methods.
            val types = method.parameterTypes
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> return proxy === arguments.firstOrNull()
                    "hashCode" -> return System.identityHashCode(proxy)
                    else -> return "TimeshiftListener($target)"
                }

                types.size == 1 && types[0] == Player.Commands::class.java ->
                    return target.onAvailableCommandsChanged(availableCommands)

                types.size == 2 && types[0] == Timeline::class.java &&
                    types[1] == Int::class.javaPrimitiveType ->
                    return target.onTimelineChanged(
                        currentTimeline,
                        arguments[1] as Int,
                    )
            }
            return try {
                method.invoke(
                    target,
                    *arguments,
                )
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /**
     * Tells listeners (the media session) when rewinding becomes possible or stops being so, and
     * when the bar's start moves on, so the player screen takes the new position and length at once.
     */
    private fun tick() {
        val session = active()
        val isActive = session != null
        var changed = isActive != wasActive
        wasActive = isActive
        if (session != null) {
            window(session)
            val b = barFor(session)
            if (b.origin != b.notifiedOrigin) {
                b.notifiedOrigin = b.origin
                changed = true
            }
        }
        if (!changed) return
        val commands = availableCommands
        val timeline = currentTimeline
        listeners.keys.toList().forEach {
            it.onAvailableCommandsChanged(commands)
            it.onTimelineChanged(
                timeline,
                TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            )
        }
    }

    private fun tickLoop() {
        tick()
        main.postDelayed(
            ::tickLoop,
            TICK_MS,
        )
    }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        timeshift.onFellBehind = null
        timeshift.stop()
        super.release()
    }

    private companion object {
        const val TAG = "Timeshift"
        const val TICK_MS = 500L

        /** A seek is carried out once no other has come in for this long. */
        const val SEEK_SETTLE_MS = 300L
    }
}
