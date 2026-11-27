package particlesim.lifecycle

import kotlin.random.Random

/**
 * A seeded random stream that can be put back exactly where it was (§9.5): it counts every
 * step of its generator, whatever was drawn - doubles, ints in a range, booleans - and
 * [restore] replays that many steps from the same seed.
 *
 * Unlike [CountingRandom], which counts only [nextDouble] calls (all an [Emitter] draws), this
 * counts at the generator itself: every draw of any kind reaches [nextBits], which steps the
 * seeded generator exactly once per call. A draw that takes several steps (a double is two;
 * an int in a range may reject and redraw) is counted as all of them. For a stream that draws
 * more than doubles, such as [particlesim.examples.FireworksShow]'s.
 *
 * Its draws are the same as a plain `Random(seed)`'s.
 */
class ReplayableRandom private constructor(val seed: Long, private val inner: Random) : Random() {
    constructor(seed: Long) : this(seed, Random(seed))

    /** How many times the generator has stepped since it was seeded. */
    var steps: Long = 0
        private set

    override fun nextBits(bitCount: Int): Int {
        steps++
        return inner.nextBits(bitCount)
    }

    companion object {
        /** A stream seeded with [seed] and stepped on [steps] times - where one that had made
         * that many steps had got to. */
        fun restore(seed: Long, steps: Long): ReplayableRandom {
            val random = ReplayableRandom(seed)
            repeat(steps) { random.nextBits(32) }
            return random
        }

        private inline fun repeat(times: Long, action: () -> Unit) {
            var left = times
            while (left > 0) { action(); left-- }
        }
    }
}
