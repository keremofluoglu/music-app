package com.example.musicapp.data.scoring

import com.example.musicapp.data.db.MusicDatabase
import com.example.musicapp.data.db.PlayEventEntity
import com.example.musicapp.data.db.SongStatsEntity
import com.example.musicapp.scoring.EngineInput
import com.example.musicapp.scoring.EventInterpreter
import com.example.musicapp.scoring.ListenFact
import com.example.musicapp.scoring.ScoreEngine
import com.example.musicapp.scoring.ScoringParams
import com.example.musicapp.scoring.SongInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Keeps song_stats up to date (docs/algoritma-kodlama.md, section 6). Play events are read once and
 * kept interpreted in memory; each new play is appended and the whole library is recomputed.
 */
class ScoreRepository(
    private val db: MusicDatabase,
    val params: ScoringParams = ScoringParams(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var interpreter: EventInterpreter? = null
    private val facts = HashMap<String, MutableList<ListenFact>>()
    private val allFacts = ArrayList<ListenFact>()
    private var lastStartedAt = Long.MIN_VALUE
    private val written = HashMap<String, SongStatsEntity>()

    /** Re-reads every play and recomputes everything (app start, after a scan). */
    suspend fun refreshAll() = mutex.withLock {
        reload()
        recompute()
    }

    /** Called right after a play_events row is inserted. */
    suspend fun onPlayRecorded(event: PlayEventEntity) = mutex.withLock {
        val i = interpreter
        if (i == null || event.startedAt < lastStartedAt) {
            reload()
        } else {
            val f = i.add(event)
            facts.getOrPut(f.songId) { ArrayList() }.add(f)
            allFacts.add(f)
            lastStartedAt = event.startedAt
        }
        recompute()
    }

    /**
     * Recomputes with the current time, without re-reading plays. Scores age with real time
     * (penalty recovery, decay, waiting bonus), so this runs before every score-ordered queue.
     */
    suspend fun recomputeNow() = mutex.withLock {
        if (interpreter == null) reload()
        recompute()
    }

    /** Snapshot of all interpreted plays, for adaptive λ. */
    suspend fun facts(): List<ListenFact> = mutex.withLock {
        if (interpreter == null) reload()
        ArrayList(allFacts)
    }

    private suspend fun reload() {
        val events = db.playEventDao().allOrdered()
        val i = EventInterpreter(params)
        facts.clear()
        allFacts.clear()
        for (e in events) {
            val f = i.add(e)
            facts.getOrPut(f.songId) { ArrayList() }.add(f)
            allFacts.add(f)
        }
        lastStartedAt = events.lastOrNull()?.startedAt ?: Long.MIN_VALUE
        interpreter = i
        written.clear()
        db.songStatsDao().getAll().associateByTo(written) { it.songId }
    }

    private suspend fun recompute() {
        val songs = db.songDao().allForScoring().map { SongInfo(it.id, it.addedAt, it.active) }
        val genres = db.genreDao().allSongGenres().groupBy({ it.songId }, { it.genreId })
        val stats = withContext(Dispatchers.Default) {
            ScoreEngine(params).computeAll(EngineInput(songs, facts, genres), clockMs())
        }
        val changed = stats.filter { isChanged(written[it.songId], it) }
        if (changed.isNotEmpty()) {
            db.songStatsDao().upsertAll(changed)
            for (s in changed) written[s.songId] = s
        }
    }

    private fun isChanged(old: SongStatsEntity?, new: SongStatsEntity): Boolean {
        if (old == null || old.paramsVersion != new.paramsVersion) return true
        if (old.playCount != new.playCount || old.qualifiedListenCount != new.qualifiedListenCount ||
            old.skipCount != new.skipCount || old.lastPlayedAt != new.lastPlayedAt
        ) return true
        fun diff(a: Double?, b: Double?) = (a == null) != (b == null) || (a != null && b != null && abs(a - b) > 1e-4)
        return diff(old.discoveryScore, new.discoveryScore) || diff(old.repeatScore, new.repeatScore) ||
            diff(old.penalty, new.penalty) || diff(old.waitBonus, new.waitBonus) ||
            diff(old.lowPlay, new.lowPlay) || diff(old.genreAffinity, new.genreAffinity) ||
            diff(old.betaA, new.betaA) || diff(old.betaB, new.betaB) || diff(old.activation, new.activation)
    }
}
