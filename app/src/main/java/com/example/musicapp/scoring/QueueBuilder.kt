package com.example.musicapp.scoring

import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.QueueTag
import kotlin.math.ln
import kotlin.random.Random

/** A song that may enter the shuffled queue; values come from song_stats. */
data class Candidate(
    val songId: String,
    val artistIds: List<Long>,
    val discovery: Double,
    val repeat: Double,
    val penalty: Double,
    val betaA: Double,
    val betaB: Double,
    /** A'_i as stored in song_stats.genre_affinity. */
    val genreGate: Double,
)

data class QueuedSong(val songId: String, val tag: QueueTag)

/** Score-weighted shuffle (docs/algoritma.md, section 9). */
class QueueBuilder(private val p: ScoringParams, private val rng: Random = Random.Default) {

    private class Keyed(val c: Candidate, val key: Double, val tag: QueueTag)

    /**
     * @param mode the home mode selected by the user (NEW or FAV).
     * @param lambda blend weight from [LambdaTuner].
     * @param avoidFirst songs that must not land in the first [ScoringParams.wrapGuard] slots
     *   (repeat-all wrap-around: the last songs played).
     */
    fun build(
        candidates: List<Candidate>,
        mode: ListenMode,
        lambda: Double,
        avoidFirst: Collection<String> = emptyList(),
    ): List<QueuedSong> {
        val temp = if (mode == ListenMode.FAV) p.tempFav else p.tempNew
        // One Gumbel key per song, drawn before sorting.
        val keyed = candidates.map { c ->
            val m = 0.5 + 0.2 * Formulas.affinityFromGate(c.genreGate, p)
            val theta = BetaSampler.sample(p.kappa * m + c.betaA, p.kappa * (1 - m) + c.betaB, rng)
            val z = lambda * c.discovery + (1 - lambda) * c.repeat + p.wQ * (theta - 0.5)
            val logW = z / temp - p.psi * c.penalty
            val u = rng.nextDouble().coerceIn(1e-12, 1 - 1e-12)
            val key = logW - ln(-ln(u))
            val tag = if (lambda * c.discovery >= (1 - lambda) * c.repeat) QueueTag.NOVEL else QueueTag.FAMILIAR
            Keyed(c, key, tag)
        }.sortedByDescending { it.key }

        val spaced = spaceArtists(keyed)
        return guardWrap(spaced, avoidFirst.toSet()).map { QueuedSong(it.c.songId, it.tag) }
    }

    /** Pulls forward the first song within the window whose artists did not play in the last g slots. */
    private fun spaceArtists(sorted: List<Keyed>): List<Keyed> {
        val distinctArtists = sorted.flatMap { it.c.artistIds }.toSet().size
        val gap = minOf(p.artistGap, distinctArtists - 1)
        if (gap <= 0) return sorted
        val remaining = sorted.toMutableList()
        val result = ArrayList<Keyed>(sorted.size)
        while (remaining.isNotEmpty()) {
            val recent = result.takeLast(gap).flatMap { it.c.artistIds }.toSet()
            val limit = minOf(p.artistWindow, remaining.size)
            var pick = 0
            for (i in 0 until limit) {
                if (remaining[i].c.artistIds.none { it in recent }) { pick = i; break }
            }
            result.add(remaining.removeAt(pick))
        }
        return result
    }

    /** Keeps [avoid] songs out of the first slots, otherwise preserving order. */
    private fun guardWrap(list: List<Keyed>, avoid: Set<String>): List<Keyed> {
        if (avoid.isEmpty()) return list
        val head = list.filter { it.c.songId !in avoid }.take(p.wrapGuard)
        val headIds = head.mapTo(HashSet()) { it.c.songId }
        return head + list.filter { it.c.songId !in headIds }
    }
}

/** Adaptive λ (docs/algoritma-kodlama.md, section 8.1). */
object LambdaTuner {
    fun lambda(mode: ListenMode, facts: List<ListenFact>, p: ScoringParams): Double {
        val base = if (mode == ListenMode.FAV) p.lambdaFav else p.lambdaNew
        val recent = facts.asSequence()
            .filter { it.mode == mode && !it.manual && it.queueTag != null }
            .toList()
            .takeLast(p.lambdaWindow)
        val novel = recent.filter { it.queueTag == QueueTag.NOVEL }
        val familiar = recent.filter { it.queueTag == QueueTag.FAMILIAR }
        if (novel.size < p.lambdaMinPerTag || familiar.size < p.lambdaMinPerTag) return base
        val cNovel = novel.count { it.completed }.toDouble() / novel.size
        val cFamiliar = familiar.count { it.completed }.toDouble() / familiar.size
        val shift = (p.lambdaEta * (cNovel - cFamiliar)).coerceIn(-p.lambdaBand, p.lambdaBand)
        return (base + shift).coerceIn(0.0, 1.0)
    }
}
