package com.example.musicapp.scoring

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The formulas of docs/algoritma.md, one function each. All ages are in listening minutes;
 * `now` is the current clock value from [ListeningClock].
 */
object Formulas {

    fun halfLife(ageMin: Double, halfLifeMin: Double): Double = 2.0.pow(-ageMin / halfLifeMin)

    private fun age(l: ListenFact, now: Double) = max(0.0, now - l.clock)

    /** n_i: weighted qualified listens, each halved every h_n. */
    fun decayedListens(qualified: List<ListenFact>, now: Double, p: ScoringParams): Double =
        qualified.sumOf { it.weight * halfLife(age(it, now), p.lowPlayHalfLifeMin) }

    /** L_i = 1 − log2(1 + n_i) / log2(N + |S|). */
    fun lowPlay(n: Double, nTotal: Double, librarySize: Int): Double {
        val denominator = log2(nTotal + librarySize).coerceAtLeast(1.0)
        return (1.0 - log2(1.0 + n) / denominator).coerceIn(0.0, 1.0)
    }

    /** ACT-R base-level activation F_i; null when the song has no qualified listen. */
    fun activation(qualified: List<ListenFact>, now: Double, p: ScoringParams): Double? {
        if (qualified.isEmpty()) return null
        val sum = qualified.sumOf { it.weight * max(age(it, now), p.epsMin).pow(-p.d) }
        return if (sum > 0.0) ln(sum) else null
    }

    /**
     * Long-term listen counts (the user's rule). Window = whichever reaches further back: the last
     * 2 months, or the last [ScoringParams.favWindowListens] qualified listens — so a light listener
     * still has enough data. One day counts at most [ScoringParams.favDailyCap] listens, so a song
     * played 25 times in one night is not a favourite. Listens keep their weight ω (manual / auto /
     * small playlist, proje.txt lines 31 and 36).
     */
    fun windowedCounts(qualifiedByTime: List<ListenFact>, nowMs: Long, p: ScoringParams): Map<String, Double> {
        if (qualifiedByTime.isEmpty()) return emptyMap()
        val twoMonthsStart = nowMs - (p.favWindowMin * 60_000).toLong()
        val lastXStart = qualifiedByTime[maxOf(0, qualifiedByTime.size - p.favWindowListens)].startedAt
        val start = minOf(twoMonthsStart, lastXStart)
        val perDay = HashMap<Pair<String, Long>, Double>()
        for (l in qualifiedByTime) {
            if (l.startedAt < start || l.startedAt > nowMs) continue
            perDay.merge(l.songId to l.startedAt / 86_400_000L, l.weight, Double::plus)
        }
        val counts = HashMap<String, Double>()
        for ((key, w) in perDay) counts.merge(key.first, minOf(w, p.favDailyCap), Double::plus)
        return counts
    }

    /** F̂_i = 0.6 · short-term score + 0.4 · long-term score (proje.txt line 25). */
    fun favourite(shortScore: Double, longScore: Double, p: ScoringParams): Double =
        p.favShortWeight * shortScore + (1 - p.favShortWeight) * longScore

    /**
     * Long-term part of the favourite score: the user's own favourites group, whatever its size
     * (20, 40, 60 songs …). The user's method: sort the counts from high to low and cut at the sharpest
     * drop. The drop is measured as a ratio, so 80 → 60 (×1.33, two favourites) is small next to
     * 30 → 6 (×5). Only the upper half is searched (favourites are a minority), otherwise a drop deep in
     * the rarely played tail could win. Favourites get close to 1, the rest close to 0, with a smooth
     * step between them.
     *
     * The cut is trusted only when it is real: the upper group must be listened to at least
     * [ScoringParams.coreMinRatio] times more than the lower one, and there must be enough songs.
     * Otherwise (e.g. the user listens to everything about equally) no break is invented and the
     * score is simply count / highest count.
     */
    fun coreScores(counts: Map<String, Double>, p: ScoringParams): Map<String, Double> {
        val positive = counts.filterValues { it > 0.0 }
        if (positive.isEmpty()) return emptyMap()
        val maxValue = positive.values.max()
        val fallback = { positive.mapValues { it.value / maxValue } }
        if (positive.size < p.coreMinSongs) return fallback()

        val desc = positive.entries.sortedByDescending { it.value }
        var cut = 0
        var bestDrop = 1.0
        for (i in 0 until desc.size / 2) {
            val drop = desc[i].value / desc[i + 1].value
            if (drop > bestDrop) {
                bestDrop = drop
                cut = i + 1
            }
        }
        if (cut == 0) return fallback()
        val upper = desc.subList(0, cut)
        val lower = desc.subList(cut, desc.size)
        val ratio = upper.map { it.value }.average() / lower.map { it.value }.average()
        if (ratio < p.coreMinRatio) return fallback()

        val t = (ln(desc[cut - 1].value) + ln(desc[cut].value)) / 2
        val gap = upper.map { ln(it.value) }.average() - lower.map { ln(it.value) }.average()
        val width = (gap / p.coreSoftness).coerceAtLeast(1e-6)
        return positive.mapValues { (_, v) -> 1.0 / (1.0 + exp(-(ln(v) - t) / width)) }
    }

    /**
     * Attention-share score: 1 − (total share of the songs that are stronger). The strongest song
     * gets 1; songs inside the group that holds half of the listening attention get ≥ 0.5; the long
     * tail falls towards 0. Unlike a plain rank this leaves a real gap between the few songs the
     * user keeps coming back to and the rest, without a hard limit on how many favourites there are.
     * Ties share a score. Songs with zero strength are not in the map.
     */
    fun shareScores(strength: Map<String, Double>): Map<String, Double> {
        val positive = strength.filterValues { it > 0.0 }
        val total = positive.values.sum()
        if (total <= 0.0) return emptyMap()
        val sorted = positive.entries.sortedByDescending { it.value }
        val result = HashMap<String, Double>(sorted.size)
        var stronger = 0.0
        var i = 0
        while (i < sorted.size) {
            var j = i
            var tieSum = 0.0
            while (j < sorted.size && sorted[j].value == sorted[i].value) { tieSum += sorted[j].value; j++ }
            val score = 1.0 - stronger / total
            for (k in i until j) result[sorted[k].key] = score
            stronger += tieSum
            i = j
        }
        return result
    }

    /**
     * W_i = 1 − e^(−age/τ_w) for a song that was never played (age = time since it was added);
     * 0 once it has been played, skips included (proje.txt line 32).
     */
    fun waitBonus(addedClock: Double, everPlayed: Boolean, now: Double, p: ScoringParams): Double =
        if (everPlayed) 0.0 else 1.0 - exp(-max(0.0, now - addedClock) / p.waitTauMin)

    /** Repeat penalty P_i (docs/algoritma.md, section 6). */
    fun penalty(all: List<ListenFact>, qualifiedCount: Int, now: Double, p: ScoringParams): Double {
        val ramp = min(1.0, max(qualifiedCount, 1).toDouble() / p.penaltyRamp)
        val hits = all.sumOf { l ->
            val h = halfLife(age(l, now), p.penaltyHalfLifeMin)
            when {
                l.notNow -> p.notNowWeight * h
                l.listenedMs >= p.penaltyMinListenMs -> h
                else -> 0.0
            }
        }
        return ramp * hits
    }

    /** Shuffle weight multiplier of the repeat penalty: e^(−ψ·P). */
    fun penaltyWeight(penalty: Double, p: ScoringParams): Double = exp(-p.psi * penalty)

    /** Beta evidence (a, b); every contribution fades with γ per [ScoringParams.gammaPeriodMin]. */
    fun evidence(all: List<ListenFact>, now: Double, p: ScoringParams): Pair<Double, Double> {
        var a = 0.0
        var b = 0.0
        for (l in all) {
            val forget = p.gamma.pow(age(l, now) / p.gammaPeriodMin)
            a += forget * (l.weight * max(l.outcome, 0.0) + if (l.manual) p.manualBonus else 0.0)
            b += forget * (l.sigma * max(-l.outcome, 0.0))
        }
        return a to b
    }

    /** A'_i = a_0 + (1 − a_0)·√A_i. */
    fun genreGate(affinity: Double, p: ScoringParams): Double =
        p.genreFloor + (1.0 - p.genreFloor) * sqrt(affinity.coerceIn(0.0, 1.0))

    /** Inverse of [genreGate]; the Thompson prior mean needs the raw A_i. */
    fun affinityFromGate(gate: Double, p: ScoringParams): Double {
        val x = ((gate - p.genreFloor) / (1.0 - p.genreFloor)).coerceIn(0.0, 1.0)
        return x * x
    }

    /**
     * Nov_i = A'_i · (L_i + b_w · W_i). A play lowers it only through L (the play count); the
     * "just played" push-back is the repeat penalty in the shuffle weight (report, proje.txt line 24).
     */
    fun discovery(gate: Double, lowPlay: Double, wait: Double, p: ScoringParams): Double =
        gate * (lowPlay + p.waitMax * wait)
}
