package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * §9.3/§11's actual multi-threading guarantee: bit-identical output "for a given seed and
 * chunk count," on any thread/machine configuration — not just reruns on the same one. Runs
 * the flag scenario (chosen because its 3 `MeshSprings` instances each mutate their own
 * `active` array, the one piece of shared mutable state in a chunk's accumulate path) for
 * hundreds of steps under different executor configurations at a *fixed* chunk count and
 * checks every live particle's final position/velocity is exactly equal, not just close.
 */
class ParallelIntegratorTest {

    private fun runFlag(chunkCount: Int, executor: ExecutorService?, steps: Int): List<Pair<Vector3, Vector3>> {
        val scenario = buildFlag(rows = 8, cols = 14)
        val integrator = Integrator(chunkCount = chunkCount, executor = executor)
        var t = 0.0
        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, FLAG_DT)
            t += FLAG_DT
        }
        return scenario.store.liveIds().map { scenario.store.position(it) to scenario.store.velocity(it) }
    }

    private fun withPool(size: Int, block: (ExecutorService) -> Unit) {
        val pool = Executors.newFixedThreadPool(size)
        try {
            block(pool)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `parallel execution matches sequential bit-for-bit at a fixed chunk count`() {
        val steps = 500
        val sequential = runFlag(chunkCount = 4, executor = null, steps = steps)
        withPool(8) { pool ->
            val parallel = runFlag(chunkCount = 4, executor = pool, steps = steps)
            assertEquals(sequential, parallel)
        }
    }

    @Test
    fun `parallel execution matches sequential even when thread count doesn't evenly divide chunk count`() {
        // chunkCount=7, parallelism=2: one worker processes chunks 0,2,4,6 and the other
        // 1,3,5 - completion order diverges from submission order the most here, which is
        // exactly the configuration that would expose a merge that accidentally depended on
        // completion order instead of the fixed chunk-index order it's supposed to use.
        val steps = 500
        val sequential = runFlag(chunkCount = 7, executor = null, steps = steps)
        withPool(2) { pool ->
            val parallel = runFlag(chunkCount = 7, executor = pool, steps = steps)
            assertEquals(sequential, parallel)
        }
    }

    // --- Exception propagation ----------------------------------------------------------------

    private class DeliberateTestException(message: String) : RuntimeException(message)

    private class ThrowingForce(private val chunkToThrowOn: Int) : Force {
        override val name: String? = null
        override fun accumulate(
            store: ParticleStore, groups: Groups, t: Double,
            chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
        ) {
            if (chunkIndex == chunkToThrowOn) throw DeliberateTestException("deliberate test failure")
        }
    }

    @Test
    fun `an exception thrown inside a parallel chunk surfaces as itself, not wrapped in ExecutionException`() {
        val store = ParticleStore()
        val groups = Groups()
        groups.add("g", store.create())
        withPool(4) { pool ->
            val integrator = Integrator(chunkCount = 4, executor = pool)
            assertFailsWith<DeliberateTestException> {
                integrator.step(store, groups, listOf(ThrowingForce(chunkToThrowOn = 2)), emptyList(), 0.0, 1e-3)
            }
        }
    }

    @Test
    fun `the same exception type surfaces on the sequential path too`() {
        val store = ParticleStore()
        val groups = Groups()
        groups.add("g", store.create())
        val integrator = Integrator(chunkCount = 4, executor = null)
        assertFailsWith<DeliberateTestException> {
            integrator.step(store, groups, listOf(ThrowingForce(chunkToThrowOn = 2)), emptyList(), 0.0, 1e-3)
        }
    }
}
