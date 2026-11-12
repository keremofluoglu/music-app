package com.example.musicapp.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.musicapp.MainActivity
import com.example.musicapp.data.db.EndReason
import com.example.musicapp.data.db.RepeatMode
import com.example.musicapp.graph
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Background playback with notification, lock screen and headset controls (Media3). */
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var tracker: PlaybackTracker
    private var sleepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        tracker = PlaybackTracker(
            player = player,
            recorder = graph.recorder,
            scope = graph.appScope, // recording must outlive the service
            mainScope = scope,
            repeatMode = {
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
            },
            onWrapAround = ::reshuffleAfterWrap,
        )
        player.addListener(tracker)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        tracker.finish(EndReason.STOPPED)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Repeat-all with shuffle on: re-sort the queue so the next round is a new order (docs/algoritma.md §2.4). */
    private fun reshuffleAfterWrap(lastPlayed: List<String>) {
        scope.launch {
            val settings = graph.settings.current()
            if (!settings.shuffle) return@launch
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            val ids = items.map { it.mediaId }.toSet()
            val songs = graph.db.songDao().activeListItems().filter { it.id in ids }
            if (songs.size < 2) return@launch
            val ctx = items.first().playContext()
            val order = graph.queues.shuffled(songs, settings.homeMode, avoidFirst = lastPlayed)
            tracker.discardCurrent()
            player.setMediaItems(order.map { it.song.toMediaItem(ctx, it.tag, manual = false) }, 0, 0L)
            player.prepare()
            player.play()
        }
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(CMD_MANUAL, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList(),
        )

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) {
                setSleepTimer(args.getInt(ARG_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_MANUAL) {
                tracker.pendingManualId = args.getString(ARG_SONG_ID)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** 0 cancels. When the timer fires the play is recorded as SLEEP_TIMER, never as a skip. */
    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            tracker.pendingEndReason = EndReason.SLEEP_TIMER
            if (player.isPlaying) player.pause() else tracker.finish(EndReason.SLEEP_TIMER)
        }
    }

    companion object {
        const val CMD_SLEEP = "com.example.musicapp.SLEEP"
        const val ARG_MINUTES = "minutes"
        const val CMD_MANUAL = "com.example.musicapp.MANUAL"
        const val ARG_SONG_ID = "song_id"
    }
}
