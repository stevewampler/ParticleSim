package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** §13.2: NaN/Infinity in position or velocity after a step must fail fast, not propagate. */
class BlowUpTest {

    @Test
    fun `a force that produces a NaN component throws BlowUpException`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create()
        groups.add("g", id)

        val badForce = object : Force {
            override val name: String? = "bad"
            override fun accumulate(
                store: ParticleStore, groups: Groups, t: Double,
                chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
            ) {
                if (chunkIndex == 0) chunk.add(store.slotOf(id), Vector3(Double.NaN, 0.0, 0.0))
            }
        }

        assertFailsWith<BlowUpException> {
            Integrator().step(store, groups, listOf(badForce), emptyList(), 0.0, 0.01)
        }
    }
}
