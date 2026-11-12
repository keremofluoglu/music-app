package com.example.musicapp.playback

import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.MusicDatabase
import com.example.musicapp.data.db.QueueTag
import com.example.musicapp.data.db.SongListItem
import com.example.musicapp.data.scoring.ScoreRepository
import com.example.musicapp.scoring.Candidate
import com.example.musicapp.scoring.LambdaTuner
import com.example.musicapp.scoring.QueueBuilder

/** Where a queue came from; stored with every play (play_events.queue_context / context_size). */
data class PlayContext(val key: String, val size: Int, val mode: ListenMode)

data class QueueEntry(val song: SongListItem, val tag: QueueTag?)

/** Builds score-ordered queues from song_stats (docs/algoritma.md, sections 2 and 9). */
class QueueService(private val db: MusicDatabase, private val scores: ScoreRepository) {

    /** Score-shuffles [songs] (or the whole library when null) for [mode]. */
    suspend fun shuffled(
        songs: List<SongListItem>?,
        mode: ListenMode,
        avoidFirst: Collection<String> = emptyList(),
    ): List<QueueEntry> {
        val pool = songs ?: db.songDao().activeListItems()
        if (pool.isEmpty()) return emptyList()
        scores.recomputeNow() // scores age with real time: bring them up to the minute first
        val stats = db.songStatsDao().getAll().associateBy { it.songId }
        val artists = db.artistDao().allSongArtists().groupBy({ it.songId }, { it.artistId })
        val p = scores.params
        val candidates = pool.map { s ->
            val st = stats[s.id]
            Candidate(
                songId = s.id,
                artistIds = artists[s.id].orEmpty(),
                discovery = st?.discoveryScore ?: 1.0,
                repeat = st?.repeatScore ?: 0.0,
                penalty = st?.penalty ?: 0.0,
                betaA = st?.betaA ?: 0.0,
                betaB = st?.betaB ?: 0.0,
                genreGate = st?.genreAffinity ?: 1.0,
            )
        }
        val lambda = LambdaTuner.lambda(mode, scores.facts(), p)
        val order = QueueBuilder(p).build(candidates, mode, lambda, avoidFirst)
        val byId = pool.associateBy { it.id }
        return order.map { QueueEntry(byId.getValue(it.songId), it.tag) }
    }
}
