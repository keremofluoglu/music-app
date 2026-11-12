package com.example.musicapp.scoring

import com.example.musicapp.data.db.SongStatsEntity
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/** A song as the engine needs it. Inactive songs (deleted or missing) still get stats but are not counted in |S|. */
data class SongInfo(
    val id: String,
    val addedAt: Long,
    val active: Boolean = true,
)

data class EngineInput(
    val songs: List<SongInfo>,
    /** Song → its plays in time order. */
    val facts: Map<String, List<ListenFact>>,
    /** Song → genre IDs. Songs without genres are neutral (A' = 1). */
    val genres: Map<String, List<Long>>,
)

/**
 * Computes both scores for the whole library from scratch (docs/algoritma-kodlama.md, section 5).
 * Pure: the same input and time always give the same output.
 */
class ScoreEngine(private val p: ScoringParams) {

    private class Partial(
        val info: SongInfo,
        val n: Double,
        val activation: Double?,
        val penalty: Double,
        val a: Double,
        val b: Double,
        val wait: Double,
        val playCount: Int,
        val qualifiedCount: Int,
        val skipCount: Int,
        val manualCount: Int,
        val firstPlayedAt: Long?,
        val lastPlayedAt: Long?,
    )

    fun computeAll(input: EngineInput, nowMs: Long): List<SongStatsEntity> {
        val now = ListeningClock.reading(nowMs)

        // Pass 1: everything that depends on one song only.
        val partials = input.songs.map { song ->
            val all = input.facts[song.id].orEmpty()
            val recent = if (all.size > p.historyMaxListens) all.takeLast(p.historyMaxListens) else all
            val qualifiedAll = all.count { it.fraction >= p.qualifiedFraction }
            val qualifiedRecent = recent.filter { it.fraction >= p.qualifiedFraction }
            val (a, b) = Formulas.evidence(recent, now, p)
            val everPlayed = all.any { it.countsAsPlayed }
            Partial(
                info = song,
                n = Formulas.decayedListens(qualifiedRecent, now, p),
                activation = Formulas.activation(qualifiedRecent, now, p),
                penalty = Formulas.penalty(recent, qualifiedAll, now, p),
                a = a,
                b = b,
                wait = Formulas.waitBonus(ListeningClock.reading(song.addedAt), everPlayed, now, p),
                playCount = all.size,
                qualifiedCount = qualifiedAll,
                skipCount = all.count { it.skipped },
                manualCount = all.count { it.manual },
                firstPlayedAt = all.firstOrNull()?.startedAt,
                lastPlayedAt = all.lastOrNull()?.startedAt,
            )
        }

        // Library-wide values.
        val active = partials.filter { it.info.active }
        val nTotal = active.sumOf { it.n }
        val librarySize = active.size
        // Favourite = 0.6 · short-term + 0.4 · long-term (proje.txt line 25).
        // Short-term: attention share of the ACT-R sum exp(F) — fills up right after a listen.
        // Long-term: the user's own favourites group (user's method: window, daily cap, sharpest drop).
        val shortScore = Formulas.shareScores(
            active.filter { it.activation != null }.associate { it.info.id to exp(it.activation!!) },
        )
        val activeIds = active.mapTo(HashSet()) { it.info.id }
        val qualifiedByTime = input.facts.values.asSequence().flatten()
            .filter { it.fraction >= p.qualifiedFraction }
            .sortedBy { it.startedAt }
            .toList()
        val longScore = Formulas.coreScores(
            Formulas.windowedCounts(qualifiedByTime, nowMs, p).filterKeys { it in activeIds },
            p,
        )
        val gates = genreGates(input, now)

        // Pass 2.
        return partials.map { s ->
            val lowPlay = Formulas.lowPlay(s.n, nTotal, librarySize)
            val gate = gates[s.info.id] ?: 1.0
            SongStatsEntity(
                songId = s.info.id,
                playCount = s.playCount,
                qualifiedListenCount = s.qualifiedCount,
                skipCount = s.skipCount,
                manualPlayCount = s.manualCount,
                firstPlayedAt = s.firstPlayedAt,
                lastPlayedAt = s.lastPlayedAt,
                discoveryScore = Formulas.discovery(gate, lowPlay, s.wait, p),
                repeatScore = Formulas.favourite(
                    shortScore = shortScore[s.info.id] ?: 0.0,
                    longScore = longScore[s.info.id] ?: 0.0,
                    p = p,
                ),
                lowPlay = lowPlay,
                genreAffinity = gate,
                waitBonus = s.wait,
                activation = s.activation,
                penalty = s.penalty,
                betaA = s.a,
                betaB = s.b,
                evidenceUpdatedAt = nowMs,
                computedAt = nowMs,
                paramsVersion = ScoringParams.VERSION,
            )
        }
    }

    /** A'_i for every song that has genres (docs/algoritma.md, section 4). Missing songs are neutral. */
    private fun genreGates(input: EngineInput, now: Double): Map<String, Double> {
        val activeIds = input.songs.filter { it.active }.mapTo(HashSet()) { it.id }
        val libShare = HashMap<Long, Double>()
        for ((songId, genres) in input.genres) {
            if (songId !in activeIds || genres.isEmpty()) continue
            for (g in genres) libShare.merge(g, 1.0 / genres.size, Double::plus)
        }
        if (libShare.isEmpty()) return emptyMap()

        val recentShare = HashMap<Long, Double>()
        for ((songId, facts) in input.facts) {
            val genres = input.genres[songId].orEmpty()
            if (genres.isEmpty()) continue
            for (l in facts) {
                if (l.fraction < p.qualifiedFraction) continue
                val ageMin = max(0.0, now - l.clock)
                if (ageMin > p.genreWindowMin) continue
                val w = l.weight * max(ageMin, p.epsMin).pow(-p.dGenre) / genres.size
                for (g in genres) recentShare.merge(g, w, Double::plus)
            }
        }

        val libTotal = libShare.values.sum()
        val recentTotal = recentShare.values.sum()
        val alpha = if (recentTotal > 0.0) p.genreSmoothing else 1.0
        val mixed = libShare.keys.associateWith { g ->
            val rec = if (recentTotal > 0.0) (recentShare[g] ?: 0.0) / recentTotal else 0.0
            (1 - alpha) * rec + alpha * libShare.getValue(g) / libTotal
        }
        val maxShare = mixed.values.maxOrNull()?.takeIf { it > 0.0 } ?: return emptyMap()

        val result = HashMap<String, Double>()
        for ((songId, genres) in input.genres) {
            if (genres.isEmpty()) continue
            val mean = genres.sumOf { mixed[it] ?: 0.0 } / genres.size
            result[songId] = Formulas.genreGate(mean / maxShare, p)
        }
        return result
    }
}
