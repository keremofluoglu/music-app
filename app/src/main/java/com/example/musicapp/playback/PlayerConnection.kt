package com.example.musicapp.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueRow(val index: Int, val songId: String, val title: String, val artist: String)

data class PlayerUiState(
    val connected: Boolean = false,
    val currentId: String? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
    val index: Int = -1,
    val contextLabel: String = "",
    val queue: List<QueueRow> = emptyList(),
)

/** UI-side handle to the playback service (Media3 MediaController). */
class PlayerConnection(private val context: Context) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pending: ((MediaController) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            publish(c)
            pending?.invoke(c)
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending = block
            connect()
        }
    }

    private fun publish(p: Player) {
        val item = p.currentMediaItem
        val queue = if (p.mediaItemCount == _state.value.queue.size && _state.value.queue.indices.all {
                _state.value.queue[it].songId == p.getMediaItemAt(it).mediaId
            }
        ) {
            _state.value.queue
        } else {
            (0 until p.mediaItemCount).map { i ->
                val m = p.getMediaItemAt(i)
                QueueRow(i, m.mediaId, m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty())
            }
        }
        _state.value = PlayerUiState(
            queue = queue,
            connected = true,
            currentId = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = p.isPlaying,
            durationMs = p.duration.coerceAtLeast(0),
            repeatMode = p.repeatMode,
            queueSize = p.mediaItemCount,
            index = p.currentMediaItemIndex,
            contextLabel = item?.playContext()?.key.orEmpty(),
        )
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0

    fun setQueue(items: List<MediaItem>, startIndex: Int = 0) = withController {
        it.setMediaItems(items, startIndex, 0L)
        it.prepare()
        it.play()
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun setRepeatMode(mode: Int) = withController { it.repeatMode = mode }

    fun playNext(item: MediaItem) = withController {
        it.addMediaItem((it.currentMediaItemIndex + 1).coerceAtMost(it.mediaItemCount), item)
    }

    fun addToQueue(item: MediaItem) = withController { it.addMediaItem(item) }

    /** IDs of the songs after the current one, in order. */
    fun upcomingIds(): List<String> {
        val c = controller ?: return emptyList()
        return (c.currentMediaItemIndex + 1 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
    }

    fun currentItem(): MediaItem? = controller?.currentMediaItem

    /** Replaces everything after the current song (shuffle pressed on the now-playing screen). */
    fun replaceUpcoming(items: List<MediaItem>) = withController {
        val from = it.currentMediaItemIndex + 1
        if (from < it.mediaItemCount) it.removeMediaItems(from, it.mediaItemCount)
        it.addMediaItems(items)
    }

    /** The user picked a song in the queue screen: it counts as a manual choice. */
    fun playAt(index: Int) = withController {
        if (index !in 0 until it.mediaItemCount) return@withController
        it.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MANUAL, Bundle.EMPTY),
            Bundle().apply { putString(PlaybackService.ARG_SONG_ID, it.getMediaItemAt(index).mediaId) },
        )
        it.seekTo(index, 0L)
        it.play()
    }

    fun removeAt(index: Int) = withController {
        if (index in 0 until it.mediaItemCount && index != it.currentMediaItemIndex) it.removeMediaItem(index)
    }

    fun setSleepTimer(minutes: Int) = withController {
        it.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.ARG_MINUTES, minutes) },
        )
    }
}
