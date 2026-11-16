package dev.anilbeesetti.nextplayer.feature.player.timeshift

import android.os.Handler
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
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
 * Makes a recorded live channel look like an ordinary seekable video whose length is the rewind
 * buffer, so the existing seek bar, double-tap and swipe seeking, and the notification all rewind
 * live TV. The end of the bar is live. Everything else passes straight through to [player].
 *
 * A seek picks the keyframe nearest the target in the recording and restarts reading there (see
 * [TimeshiftDataSource]); the player's own position then counts from that moment.
 */
@UnstableApi
class TimeshiftPlayer(private val player: Player, private val timeshift: Timeshift) :
    ForwardingPlayer(player) {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<Player.Listener, Player.Listener>()

    // What the bar shows, kept smooth across the recorder's steps (as on Windows). Reset per channel.
    private var shownFor: Timeshift.Session? = null
    private var shownLive = 0L
    private var shownSpan = 0L
    private var wasActive = false

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

                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int,
                ) {
                    // Another channel or a video: stop recording the old one.
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

    // ---- the bar: span = buffer, position = playhead -----------------------------------

    private class Window(val start: Long, val live: Long, val position: Long)

    private fun window(session: Timeshift.Session): Window {
        val rec = session.recorder
        if (shownFor !== session) {
            shownFor = session
            shownLive = 0L
            shownSpan = 0L
        }
        val start = rec.earliestWall
        val dataEdge = max(
            start,
            rec.liveWall,
        )
        // HLS data lands a segment at a time: let live follow the clock, never past one segment.
        val live = max(
            shownLive,
            System.currentTimeMillis().coerceIn(
                dataEdge,
                dataEdge + rec.edgeStepMs,
            ),
        )
        shownLive = live
        // A full buffer drops its oldest chunk at a time; a span that only grows keeps the knob steady.
        shownSpan = max(
            shownSpan,
            live - start,
        )
        val position = (session.startWall + player.currentPosition).coerceIn(
            live - shownSpan,
            live,
        )
        return Window(
            live - shownSpan,
            live,
            position,
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

    private fun seekWall(session: Timeshift.Session, wall: Long, preferLater: Boolean) {
        session.startAt(
            wall,
            preferLater,
        )
        // Unseekable live progressive media restarts reading when sent to 0: see TimeshiftDataSource.
        player.seekTo(
            player.currentMediaItemIndex,
            0L,
        )
    }

    private fun seekBar(positionMs: Long): Boolean {
        val session = active() ?: return false
        val w = window(session)
        seekWall(
            session,
            w.start + positionMs,
            preferLater = w.start + positionMs > w.position,
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

    /** Back to live. */
    override fun seekToDefaultPosition() {
        val session = active() ?: return super.seekToDefaultPosition()
        seekWall(
            session,
            Long.MAX_VALUE,
            preferLater = false,
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

    /** Tells listeners (the media session) when rewinding becomes possible or stops being so. */
    private fun tick() {
        val isActive = active() != null
        if (isActive == wasActive) return
        wasActive = isActive
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
        const val TICK_MS = 500L
    }
}
