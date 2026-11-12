package com.example.musicapp

import android.app.Application
import android.content.Context
import com.example.musicapp.data.db.MusicDatabase
import com.example.musicapp.data.scoring.ScoreRepository
import com.example.musicapp.data.settings.AppSettings
import com.example.musicapp.library.LibraryScanner
import com.example.musicapp.playback.ListenRecorder
import com.example.musicapp.playback.QueueService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The app's long-lived objects, created once (simple manual dependency injection). */
class AppGraph(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = MusicDatabase.get(context)
    val settings = AppSettings(context)
    val scores = ScoreRepository(db)
    val scanner = LibraryScanner(context, db)
    val queues = QueueService(db, scores)
    val recorder = ListenRecorder(db, settings, scores)
    // Lyrics are on hold (2026-11-12): lyrics/LyricsRepository.kt is kept but not wired in.
}

class MusicApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.appScope.launch {
            // A play that was running when the process died is recorded as APP_CLOSED.
            graph.recorder.recoverOpenListen()
            graph.scores.refreshAll()
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as MusicApp).graph
