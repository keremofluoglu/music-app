package com.example.musicapp.scoring

import kotlin.math.max

private const val MS_PER_MIN = 60_000.0

/**
 * The clock that ages every score: real elapsed time in minutes (proje.txt line 37, "puan decayi
 * geçen zamana göre"; the report measures Δ in hours since a play).
 */
object ListeningClock {
    fun reading(atMs: Long): Double = atMs / MS_PER_MIN

    /** Age between two readings; never negative (a clock moved backwards counts as zero). */
    fun age(now: Double, then: Double): Double = max(0.0, now - then)
}
