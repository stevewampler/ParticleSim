package particlesim.lifecycle

import kotlin.random.Random

/**
 * Wraps a [Random] instance, counting every draw through [nextDouble] so an emitter's RNG
 * sub-stream position can be captured and later restored exactly (§9.5's "each emitter's...
 * RNG sub-stream state"). `kotlin.random.Random` has no public API to serialize its own
 * internal state, and every higher-level draw method is implemented in terms of an internal
 * `nextBits` primitive whose behavior isn't part of the documented contract — rather than lean
 * on that, this counts calls to the one method every [VectorDistribution]/[ScalarDistribution]
 * sampler in this codebase actually calls (`nextDouble()`; the two-argument range overload is
 * itself implemented as exactly one call to the zero-argument one for any finite range, which
 * covers every distribution here). Fast-forwarding a freshly-reseeded stream by replaying that
 * many `nextDouble()` calls reproduces the exact same state a resumed run needs, with no
 * assumption about `nextBits`' behavior required.
 */
class CountingRandom(private val inner: Random) : Random() {
    var drawCount: Long = 0
        private set

    override fun nextBits(bitCount: Int): Int = inner.nextBits(bitCount)

    override fun nextDouble(): Double {
        drawCount++
        return inner.nextDouble()
    }

    companion object {
        /** Builds a [CountingRandom] seeded like a fresh emitter stream, then fast-forwards it
         * to [drawCount] by replaying that many draws — the checkpoint-resume counterpart to
         * capturing [CountingRandom.drawCount] from a live stream. */
        fun restore(seed: Long, drawCount: Long): CountingRandom {
            val rng = CountingRandom(Random(seed))
            var remaining = drawCount
            while (remaining > 0) {
                rng.nextDouble()
                remaining--
            }
            return rng
        }
    }
}
