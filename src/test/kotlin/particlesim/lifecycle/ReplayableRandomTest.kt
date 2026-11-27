package particlesim.lifecycle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ReplayableRandom]: the same draws as a plain seeded Random, and put back exactly. */
class ReplayableRandomTest {
    private fun mixedDraws(r: Random, n: Int) = (0 until n).map {
        when (it % 4) {
            0 -> r.nextDouble()
            1 -> r.nextInt(7).toDouble()
            2 -> if (r.nextBoolean()) 1.0 else 0.0
            else -> r.nextDouble(-3.0, 5.0)
        }
    }

    @Test
    fun `it draws exactly what a plain Random with the same seed draws`() {
        assertEquals(mixedDraws(Random(42L), 500), mixedDraws(ReplayableRandom(42L), 500))
    }

    @Test
    fun `restored at its step count, it carries on as the original does, whatever was drawn`() {
        val original = ReplayableRandom(9L)
        mixedDraws(original, 333)
        val copy = ReplayableRandom.restore(9L, original.steps)
        assertEquals(original.steps, copy.steps)
        assertEquals(mixedDraws(original, 200), mixedDraws(copy, 200))
    }
}
