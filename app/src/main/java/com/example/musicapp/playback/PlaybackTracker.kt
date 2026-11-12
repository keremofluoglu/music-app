package com.example.musicapp.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.musicapp.data.db.EndReason
import com.example.musicapp.data.db.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Measures every play and decides how it ended (docs/algoritma-kodlama.md, section 7).
 * Listened time is real playing time: pauses, seeks and buffering do not count.
 */
class PlaybackTracker(
    private val player: Player,
    private val recorder: ListenRecorder,
    /** Database writes; must outlive the service. Never touches the player. */
    private val scope: CoroutineScope,
    /** Main-thread scope: the player may only be read on the main thread. */
    private val mainScope: CoroutineScope,
    private val repeatMode: () -> RepeatMode,
    /** Called when a repeat-all queue wraps around, so it can be re-shuffled. */
    private val onWrapAround: (lastPlayed: List<String>) -> Unit,
) : Player.Listener {

    private class Current(
        val item: MediaItem,
        val startedAt: Long,
        val manual: Boolean,
        var listenedMs: Long = 0,
        var playingSince: Long? = null,
    )

    private var current: Current? = null
    private var heartbeat: Job? = null
    private val recentlyPlayed = ArrayDeque<String>()
    /** Set by the sleep timer just before it pauses. */
    var pendingEndReason: EndReason? = null
    /** Set just before the user jumps to a song in the queue screen: that play counts as manual. */
    var pendingManualId: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previousIndexWasLast = lastIndex >= 0 && lastIndex == lastCount - 1
        finish(
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EndReason.COMPLETED
                else -> EndReason.SKIPPED
            },
        )
        if (mediaItem != null) {
            val manual = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem.isManualStart()) ||
                mediaItem.mediaId == pendingManualId
            pendingManualId = null
            start(mediaItem, manual)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previousIndexWasLast &&
                player.currentMediaItemIndex == 0 && player.mediaItemCount > 1
            ) {
                onWrapAround(recentlyPlayed.toList())
            }
        }
        rememberIndex()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val c = current ?: player.currentMediaItem?.let { start(it, manual = false); current } ?: return
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) {
            if (c.playingSince == null) c.playingSince = now
            startHeartbeat()
        } else {
            c.playingSince?.let { c.listenedMs += now - it }
            c.playingSince = null
            heartbeat?.cancel()
            pendingEndReason?.let { reason ->
                pendingEndReason = null
                finish(reason)
            }
            saveHeartbeat()
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) finish(EndReason.COMPLETED)
        rememberIndex()
    }

    override fun onPlayerError(error: PlaybackException) {
        finish(EndReason.ERROR)
    }

    /** Drops the play in progress without recording it (used when the queue is rebuilt in place). */
    fun discardCurrent() {
        current = null
        heartbeat?.cancel()
    }

    /** Ends the play in progress, e.g. STOPPED when the service shuts down. */
    fun finish(reason: EndReason) {
        val c = current ?: return
        current = null
        heartbeat?.cancel()
        c.playingSince?.let { c.listenedMs += SystemClock.elapsedRealtime() - it }
        val open = c.toOpenListen()
        recentlyPlayed.addLast(c.item.mediaId)
        while (recentlyPlayed.size > 3) recentlyPlayed.removeFirst()
        scope.launch { recorder.record(open, reason) }
    }

    private fun start(item: MediaItem, manual: Boolean) {
        current = Current(item, System.currentTimeMillis(), manual).also {
            if (player.isPlaying) it.playingSince = SystemClock.elapsedRealtime()
        }
        if (player.isPlaying) startHeartbeat()
    }

    private fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = mainScope.launch {
            while (isActive) {
                delay(5_000)
                saveHeartbeat()
            }
        }
    }

    private fun saveHeartbeat() {
        val c = current ?: return
        val running = c.playingSince?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        val open = c.toOpenListen(extraMs = running)
        scope.launch { recorder.heartbeat(open) }
    }

    private fun Current.toOpenListen(extraMs: Long = 0): OpenListen {
        val ctx = item.playContext()
        val duration = player.duration.takeIf { it > 0 && player.currentMediaItem?.mediaId == item.mediaId }
            ?: item.knownDurationMs()
        return OpenListen(
            songId = item.mediaId,
            startedAt = startedAt,
            tzOffsetMin = TimeZone.getDefault().getOffset(startedAt) / 60_000,
            listenedMs = listenedMs + extraMs,
            durationMs = duration,
            initiatedBy = initiated(manual),
            mode = ctx.mode,
            tag = item.queueTag(),
            context = ctx.key,
            contextSize = ctx.size,
            repeat = repeatMode(),
        )
    }

    private var lastIndex = -1
    private var lastCount = 0
    private fun rememberIndex() {
        lastIndex = player.currentMediaItemIndex
        lastCount = player.mediaItemCount
    }
}
