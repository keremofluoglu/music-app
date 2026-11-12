package com.example.musicapp.scoring

private const val HOUR = 60.0
private const val DAY = 24 * HOUR

/**
 * Every tunable number of the scoring and shuffle algorithm (docs/algoritma.md, section 10).
 *
 * Source: the literature review's "Proposed scoring algorithm" plus the user's amendments in
 * proje.txt ("rapor üstüne yapılmış bazı çıkarımlar", lines 30–37). Durations are real (wall-clock)
 * minutes: proje.txt line 37 says decay follows elapsed time.
 */
data class ScoringParams(
    // Familiarity (proje.txt line 25: favori = 0.6·short_term + 0.4·long_term)
    // short term = ACT-R activation (report); long term = the user's favourites group (user's method below)
    val favShortWeight: Double = 0.6,
    // Long-term window (user's rule): max(listens of the last 2 months, the last X listens) — whichever reaches further back.
    val favWindowMin: Double = 60 * DAY,
    val favWindowListens: Int = 1000,
    // One day counts at most this many listens, so a one-night binge does not make a favourite.
    val favDailyCap: Double = 3.0,
    // Favourites/rest split (user's method): sharpest ratio drop in the upper half of the sorted counts.
    val coreMinSongs: Int = 5,           // fewer played songs → no split
    val coreMinRatio: Double = 4.0,      // upper group must be listened ≥ 4× more than the rest, else no split
    val coreSoftness: Double = 6.0,      // smoothness of the step: (gap between group means) / this

    // Familiarity and genre (report)
    val d: Double = 0.5,
    val dGenre: Double = 1.5,
    val epsMin: Double = 3.0,                  // ε = 0.05 h
    val qualifiedFraction: Double = 0.8,

    // Listen weight (report + proje.txt line 36: small playlists count less)
    val wManual: Double = 1.0,
    val wAuto: Double = 0.5,
    val nRef: Int = 50,
    val manualBonus: Double = 0.25,            // proje.txt line 31: a manual pick is a positive signal

    // Discovery (report; waiting bonus per proje.txt line 32)
    val lowPlayHalfLifeMin: Double = 90 * DAY, // h_n
    val genreSmoothing: Double = 0.1,
    val genreFloor: Double = 0.2,
    val genreWindowMin: Double = 30 * DAY,
    val waitTauMin: Double = 14 * DAY,         // τ_w: never-played songs gain discovery over time
    val waitMax: Double = 0.5,                 // b_w

    // Repeat penalty (report; proje.txt line 24) — applied to the shuffle weight only
    val penaltyHalfLifeMin: Double = 12 * HOUR,
    val psi: Double = 4.0,
    val penaltyRamp: Int = 5,
    val notNowWeight: Double = 0.5,
    val penaltyMinListenMs: Long = 30_000,

    // Beta evidence and Thompson sampling (report)
    val gamma: Double = 0.98,
    val gammaPeriodMin: Double = DAY,
    val skipWaveSigma: Double = 0.5,
    val skipWaveWindow: Int = 5,
    val skipWaveThreshold: Int = 3,
    val kappa: Double = 2.0,

    // Mode blend and shuffle (report)
    val lambdaNew: Double = 0.7,
    val lambdaFav: Double = 0.3,
    val lambdaEta: Double = 0.5,
    val lambdaBand: Double = 0.15,
    val lambdaWindow: Int = 50,
    val lambdaMinPerTag: Int = 10,
    val wQ: Double = 0.5,
    val tempNew: Double = 0.20,
    val tempFav: Double = 0.15,
    val artistGap: Int = 2,
    val artistWindow: Int = 10,
    val wrapGuard: Int = 3,

    // Sessions (report)
    val sessionGapMin: Int = 30,
    val notNowWindowMin: Int = 30,

    // History cap per song
    val historyMaxListens: Int = 200,
) {
    companion object {
        /**
         * Bump whenever a default above or a formula changes; song_stats is then rebuilt.
         * 3: back to the report + proje.txt (wall-clock time, no drop-to-zero, waiting bonus only
         *    for never-played songs).
         * 4: favourite score = 0.6 · short-term (ACT-R) + 0.4 · long-term (proje.txt line 25).
         * 5: both parts scored by attention share instead of plain rank (gap between favourites and the rest).
         * 6: long-term part finds each user's core rotation (natural break), whatever its size.
         * 7: user's method — window max(2 months, last 1000 listens), daily cap 3, sharpest drop.
         */
        const val VERSION = 7
    }
}
