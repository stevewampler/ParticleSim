package particlesim.lifecycle

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmitterTest {

    private fun emitter(
        rate: Double = 10.0,
        maxAlive: Int = 1000,
        capPolicy: EmitterCapPolicy = EmitterCapPolicy.STOP,
        masterSeed: Long = 1L,
        name: String = "e",
        warnings: MutableList<String>? = null,
    ) = Emitter(
        name = name,
        group = "spawned",
        rate = ScalarExpr.of(rate),
        position = VectorDistribution.UniformBox(Vector3.ZERO, Vector3.ZERO),
        velocity = VectorDistribution.UniformBox(Vector3.ZERO, Vector3.ZERO),
        maxAlive = maxAlive,
        capPolicy = capPolicy,
        masterSeed = masterSeed,
        onWarning = { warnings?.add(it) },
    )

    @Test
    fun `spawns exactly one particle per step when rate times dt equals one`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 10.0)
        val dt = 0.1 // rate * dt = 1.0 exactly

        repeat(5) { i ->
            e.update(store, groups, t = i * dt, dt = dt)
            assertEquals(i + 1, store.size, "expected ${i + 1} particles after step $i")
        }
    }

    @Test
    fun `fractional spawn budget accumulates across steps instead of rounding per step`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 1.0)
        val dt = 0.3 // 0.3 particles/step: no single step should spawn, but 4 steps (1.2) should spawn 1

        repeat(3) { i -> e.update(store, groups, t = i * dt, dt = dt); assertEquals(0, store.size) }
        e.update(store, groups, t = 3 * dt, dt = dt) // accumulator now 1.2
        assertEquals(1, store.size)
    }

    @Test
    fun `spawned particles are added to the target group`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 10.0)
        e.update(store, groups, t = 0.0, dt = 0.1)
        assertEquals(1, groups.membersOf("spawned").size)
    }

    @Test
    fun `stop policy blocks further spawning at the cap and warns once`() {
        val store = ParticleStore()
        val groups = Groups()
        val warnings = mutableListOf<String>()
        val e = emitter(rate = 1000.0, maxAlive = 3, capPolicy = EmitterCapPolicy.STOP, warnings = warnings)

        e.update(store, groups, t = 0.0, dt = 1.0) // rate*dt = 1000 requested, capped at 3
        assertEquals(3, store.size)
        e.update(store, groups, t = 1.0, dt = 1.0) // still capped, should stay at 3
        assertEquals(3, store.size)
        assertEquals(1, warnings.size, "expected exactly one cap warning, not one per blocked spawn attempt")
    }

    @Test
    fun `stop policy resumes at the steady rate once room frees, not a burst`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 1.0, maxAlive = 2, capPolicy = EmitterCapPolicy.STOP)

        e.update(store, groups, t = 0.0, dt = 5.0) // way over budget, capped at 2
        assertEquals(2, store.size)
        val ids = store.liveIds()
        store.destroy(ids[0])
        groups.removeParticle(ids[0])

        e.update(store, groups, t = 5.0, dt = 0.1) // small step: should not release a backlog burst
        assertEquals(2, store.size, "unspent accumulator should have been clamped while blocked, not banked")
    }

    @Test
    fun `evict-oldest policy keeps the population pinned at the cap under high rate`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 1000.0, maxAlive = 5, capPolicy = EmitterCapPolicy.EVICT_OLDEST)

        e.update(store, groups, t = 0.0, dt = 1.0) // far more than 5 requested in one step: churns, stays at 5
        assertEquals(5, store.size)
        assertEquals(5, groups.membersOf("spawned").size)
    }

    @Test
    fun `EmitResult reports every id spawned and evicted this call, for the discrete-event channel`() {
        val store = ParticleStore()
        val groups = Groups()
        // rate * dt = 3.0 exactly, so each call below spawns exactly 3 - deterministic enough to
        // assert "no eviction yet" on the first call and "every spawn evicts" on the second.
        val e = emitter(rate = 3.0, maxAlive = 3, capPolicy = EmitterCapPolicy.EVICT_OLDEST)

        val first = e.update(store, groups, t = 0.0, dt = 1.0) // fills to the cap, no room to evict yet
        assertEquals(3, first.spawnedIds.size)
        assertEquals(emptyList(), first.evictedIds)

        val second = e.update(store, groups, t = 1.0, dt = 1.0) // at cap: every new spawn evicts the oldest
        assertEquals(3, second.spawnedIds.size)
        assertEquals(second.spawnedIds.size, second.evictedIds.size)
        // Evicted ids must actually be gone and spawned ids must actually be present - a
        // consumer turning these into SimEvents needs that correspondence to be real, not
        // just plausible-looking counts.
        for (id in second.evictedIds) assertTrue(!store.contains(id))
        for (id in second.spawnedIds) assertTrue(store.contains(id))
    }

    @Test
    fun `EmitResult spawnedIds is empty, not omitted, when the STOP cap blocks every spawn this call`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 1000.0, maxAlive = 2, capPolicy = EmitterCapPolicy.STOP)
        e.update(store, groups, t = 0.0, dt = 1.0) // fills to the cap of 2

        val blocked = e.update(store, groups, t = 1.0, dt = 1.0) // cap already full: nothing spawns
        assertEquals(emptyList(), blocked.spawnedIds)
        assertEquals(emptyList(), blocked.evictedIds)
    }

    @Test
    fun `an emitter's live count self-heals when particles are destroyed elsewhere`() {
        val store = ParticleStore()
        val groups = Groups()
        val e = emitter(rate = 100.0, maxAlive = 2, capPolicy = EmitterCapPolicy.STOP)

        e.update(store, groups, t = 0.0, dt = 1.0) // fills to cap (2)
        assertEquals(2, store.size)

        // Something else (destruction system, not this emitter) destroys one.
        val victim = store.liveIds().first()
        store.destroy(victim)
        groups.removeParticle(victim)

        e.update(store, groups, t = 1.0, dt = 1.0) // should notice the cap has room again, no notification needed
        assertEquals(2, store.size)
    }

    @Test
    fun `same seed and name reproduce an identical spawn sequence`() {
        fun run(): List<Vector3> {
            val store = ParticleStore()
            val groups = Groups()
            val e = Emitter(
                name = "sparks", group = "g", rate = ScalarExpr.of(50.0),
                position = VectorDistribution.UniformSphere(Vector3.ZERO, 1.0),
                velocity = VectorDistribution.UniformBox(Vector3.ZERO, Vector3(1.0, 1.0, 1.0)),
                maxAlive = 1000, masterSeed = 12345L,
            )
            e.update(store, groups, t = 0.0, dt = 0.2)
            return store.liveIds().map { store.position(it) }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `different emitter names under the same master seed produce different sequences`() {
        fun run(name: String): List<Vector3> {
            val store = ParticleStore()
            val groups = Groups()
            val e = Emitter(
                name = name, group = "g", rate = ScalarExpr.of(50.0),
                position = VectorDistribution.UniformSphere(Vector3.ZERO, 1.0),
                velocity = VectorDistribution.UniformBox(Vector3.ZERO, Vector3(1.0, 1.0, 1.0)),
                maxAlive = 1000, masterSeed = 999L,
            )
            e.update(store, groups, t = 0.0, dt = 0.2)
            return store.liveIds().map { store.position(it) }
        }
        assertTrue(run("emitterA") != run("emitterB"))
    }

    @Test
    fun `mixSeed avoids the correlated-low-bits problem plain xor has`() {
        // Two names with the same masterSeed should differ across the FULL 64 bits, not just
        // the low 32 - otherwise Random streams seeded from them can start out correlated.
        val a = Emitter.mixSeed(1_000_000_000_000L, "a")
        val b = Emitter.mixSeed(1_000_000_000_000L, "b")
        assertTrue((a xor b).countOneBits() > 20, "mixed seeds differ in too few bits: $a vs $b")
    }
}
