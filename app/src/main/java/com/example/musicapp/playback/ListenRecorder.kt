package com.example.musicapp.playback

import com.example.musicapp.data.db.EndReason
import com.example.musicapp.data.db.EventSource
import com.example.musicapp.data.db.InitiatedBy
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.MusicDatabase
import com.example.musicapp.data.db.PlayEventEntity
import com.example.musicapp.data.db.QueueTag
import com.example.musicapp.data.db.RepeatMode
import com.example.musicapp.data.db.SessionEntity
import com.example.musicapp.data.scoring.ScoreRepository
import com.example.musicapp.data.settings.AppSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A play in progress; also what is saved every few seconds so a killed process does not lose it. */
@Serializable
data class OpenListen(
    val songId: String,
    val startedAt: Long,
    val tzOffsetMin: Int,
    val listenedMs: Long,
    val durationMs: Long,
    val initiatedBy: InitiatedBy,
    val mode: ListenMode,
    val tag: QueueTag?,
    val context: String,
    val contextSize: Int,
    val repeat: RepeatMode,
)

/** Writes play_events and sessions, then triggers the score update (docs/algoritma-kodlama.md, section 7). */
class ListenRecorder(
    private val db: MusicDatabase,
    private val settings: AppSettings,
    private val scores: ScoreRepository,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun heartbeat(open: OpenListen) = settings.saveOpenListen(json.encodeToString(OpenListen.serializer(), open))

    suspend fun record(open: OpenListen, end: EndReason) = mutex.withLock {
        val listened = open.listenedMs.coerceIn(0, open.durationMs.coerceAtLeast(0))
        val sessionId = sessionFor(open.startedAt)
        val event = PlayEventEntity(
            songId = open.songId,
            sessionId = sessionId,
            startedAt = open.startedAt,
            tzOffsetMin = open.tzOffsetMin,
            listenedMs = listened,
            durationMs = open.durationMs,
            endReason = end,
            initiatedBy = open.initiatedBy,
            modeAtPlay = open.mode,
            queueTag = open.tag,
            queueContext = open.context,
            contextSize = open.contextSize,
            repeatMode = open.repeat,
            source = EventSource.APP,
        )
        if (db.songDao().exists(open.songId)) {
            db.playEventDao().insert(event)
            db.sessionDao().end(sessionId, open.startedAt + listened)
        }
        settings.saveOpenListen(null)
        if (db.songDao().exists(open.songId)) scores.onPlayRecorded(event)
    }

    /** Called at app start: a play saved by the heartbeat but never finished is recorded as APP_CLOSED. */
    suspend fun recoverOpenListen() {
        val raw = settings.openListen() ?: return
        val open = runCatching { json.decodeFromString(OpenListen.serializer(), raw) }.getOrNull()
        if (open == null) {
            settings.saveOpenListen(null)
            return
        }
        val latest = db.playEventDao().latest()
        if (latest != null && latest.songId == open.songId && latest.startedAt == open.startedAt) {
            settings.saveOpenListen(null) // already written before the process died
            return
        }
        record(open, EndReason.APP_CLOSED)
    }

    /** Same session if the previous play ended less than 30 minutes before this one started. */
    private suspend fun sessionFor(startedAt: Long): Long {
        val last = db.sessionDao().latest()
        val lastEnd = last?.endedAt ?: last?.startedAt
        val gap = scores.params.sessionGapMin * 60_000L
        return if (last != null && lastEnd != null && startedAt - lastEnd in -gap..gap) last.id
        else db.sessionDao().insert(SessionEntity(startedAt = startedAt))
    }
}
