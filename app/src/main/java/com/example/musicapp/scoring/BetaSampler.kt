package com.example.musicapp.scoring

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Beta(α, β) samples for Thompson sampling, built from two Gamma samples (Marsaglia–Tsang). */
object BetaSampler {

    fun sample(alpha: Double, beta: Double, rng: Random): Double {
        val x = gamma(alpha, rng)
        val y = gamma(beta, rng)
        val sum = x + y
        return if (sum > 0.0) x / sum else 0.5
    }

    fun gamma(shape: Double, rng: Random): Double {
        require(shape > 0.0) { "shape must be positive: $shape" }
        if (shape < 1.0) {
            // Boost: Gamma(a) = Gamma(a + 1) · U^(1/a).
            val u = rng.nextDouble().coerceAtLeast(1e-300)
            return gamma(shape + 1.0, rng) * u.pow(1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = normal(rng)
                v = 1.0 + c * x
            } while (v <= 0.0)
            v = v * v * v
            val u = rng.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun normal(rng: Random): Double {
        // Box–Muller; one value per call is enough here.
        val u1 = rng.nextDouble().coerceAtLeast(1e-300)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
