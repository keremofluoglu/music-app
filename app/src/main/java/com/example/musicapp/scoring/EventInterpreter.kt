package com.example.musicapp.scoring

import com.example.musicapp.data.db.EndReason
import com.example.musicapp.data.db.InitiatedBy
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.PlayEventEntity
import com.example.musicapp.data.db.QueueTag
import com.example.musicapp.data.db.RepeatMode
import kotlin.math.min

/** One play as the formulas see it (docs/algoritma-kodlama.md, section 4). */
data class ListenFact(
    val songId: String,
    val startedAt: Long,
    /** Clock value at the end of this play. */
    val clock: Double,
    val fraction: Double,
    val listenedMs: Long,
    /** ω = ω_src · min(1, n_ctx / N_ref). */
    val weight: Double,
    val manual: Boolean,
    /** r: +1 completed, −(1−f) skipped, 0 otherwise. */
    val outcome: Double,
    val skipped: Boolean,
    val completed: Boolean,
    val notNow: Boolean,
    val sigma: Double,
    /** False only for ERROR: the user never heard the song, so its wait bonus is kept. */
    val countsAsPlayed: Boolean,
    val mode: ListenMode,
    val queueTag: QueueTag?,
)

/**
 * Turns play events into [ListenFact]s. Some values depend on earlier events ("not now" skips,
 * skip waves), so events must be fed in time order. Stateful so new plays can be appended one by
 * one without re-reading the whole history.
 */
class EventInterpreter(private val p: ScoringParams) {
    private val lastStart = HashMap<String, Long>()
    private val recentSkips = HashMap<Long, ArrayDeque<Boolean>>()

    fun add(e: PlayEventEntity): ListenFact {
        val reading = ListeningClock.reading(e.startedAt + e.listenedMs)

        val f = if (e.durationMs > 0) (e.listenedMs.toDouble() / e.durationMs).coerceIn(0.0, 1.0) else 0.0
        val manual = e.initiatedBy == InitiatedBy.MANUAL
        val src = if (manual) p.wManual else p.wAuto
        val k = min(1.0, e.contextSize.toDouble() / p.nRef)
        val skipped = e.endReason == EndReason.SKIPPED
        val completed = e.endReason == EndReason.COMPLETED

        val prev = lastStart[e.songId]
        val notNow = skipped && e.listenedMs < p.penaltyMinListenMs &&
            (e.repeatMode == RepeatMode.ONE ||
                (prev != null && e.startedAt - prev <= p.notNowWindowMin * 60_000L))

        // σ looks at the plays BEFORE this one in the same session.
        val window = recentSkips.getOrPut(e.sessionId) { ArrayDeque() }
        val sigma = if (window.count { it } >= p.skipWaveThreshold) p.skipWaveSigma else 1.0

        val r = when {
            notNow -> 0.0
            completed -> 1.0
            skipped -> -(1.0 - f)
            else -> 0.0
        }

        window.addLast(skipped)
        if (window.size > p.skipWaveWindow) window.removeFirst()
        lastStart[e.songId] = e.startedAt

        return ListenFact(
            songId = e.songId,
            startedAt = e.startedAt,
            clock = reading,
            fraction = f,
            listenedMs = e.listenedMs,
            weight = src * k,
            manual = manual,
            outcome = r,
            skipped = skipped,
            completed = completed,
            notNow = notNow,
            sigma = sigma,
            countsAsPlayed = e.endReason != EndReason.ERROR,
            mode = e.modeAtPlay,
            queueTag = e.queueTag,
        )
    }

    fun addAll(eventsByTime: List<PlayEventEntity>): List<ListenFact> = eventsByTime.map(::add)
}
