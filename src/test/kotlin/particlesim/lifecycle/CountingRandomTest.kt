package particlesim.lifecycle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [CountingRandom]'s fast-forward is the mechanism §9.5's emitter RNG checkpointing rests
 * on — tested directly here, isolated from the fuller sparks checkpoint/resume proof in
 * `particlesim.record.CheckpointTest`. */
class CountingRandomTest {

    @Test
    fun `drawCount increments once per nextDouble call`() {
        val rng = CountingRandom(Random(42L))
        assertEquals(0L, rng.drawCount)
        repeat(5) { rng.nextDouble() }
        assertEquals(5L, rng.drawCount)
    }

    @Test
    fun `restore reproduces the exact continuation of the original stream`() {
        val seed = 123L
        val original = CountingRandom(Random(seed))
        val drawnBeforeCheckpoint = List(17) { original.nextDouble() }
        val checkpointDrawCount = original.drawCount

        // What the original stream would draw *next*, had it kept going uninterrupted.
        val expectedContinuation = List(10) { original.nextDouble() }

        // A fresh stream, restored to the checkpoint's position, should draw the exact same
        // continuation — not just statistically similar values.
        val restored = CountingRandom.restore(seed, checkpointDrawCount)
        val actualContinuation = List(10) { restored.nextDouble() }

        assertEquals(expectedContinuation, actualContinuation)
        assertEquals(17, drawnBeforeCheckpoint.size) // sanity: nothing optimized away
    }

    @Test
    fun `restoring a zero draw count reproduces the stream from the very start`() {
        val seed = 99L
        val fresh = CountingRandom(Random(seed))
        val freshValues = List(5) { fresh.nextDouble() }
        val restored = CountingRandom.restore(seed, 0L)
        val restoredValues = List(5) { restored.nextDouble() }
        assertEquals(freshValues, restoredValues)
    }
}
