package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore

/** Thrown when a particle's position or velocity blows up to NaN/Infinity (§13.2). */
class BlowUpException(message: String) : RuntimeException(message)

/**
 * Advances a simulation by one fixed timestep using semi-implicit (symplectic) Euler (§8,
 * §13.1): `v += (F/m)·dt` then `x += v·dt`. Explicit Euler is deliberately not offered — it's
 * unstable for anything involving springs (§13.1).
 *
 * Force accumulation is chunked (§9.3, §11) even though nothing is multi-threaded yet: a
 * fixed number of [chunkCount] chunks, each with its own private [ChunkAccumulator], merged
 * in fixed chunk-index order. This is what lets Phase 8 parallelize the per-chunk loop later
 * without touching the accumulation/merge protocol itself.
 */
class Integrator(private val chunkCount: Int = DEFAULT_CHUNK_COUNT) {

    fun step(
        store: ParticleStore,
        groups: Groups,
        forces: List<Force>,
        constraints: List<Constraint>,
        t: Double,
        dt: Double,
    ) {
        store.refreshDynamicMass(t)

        val net = accumulateForces(store, groups, forces, t)

        for (id in store.liveIds()) {
            val slot = store.slotOf(id)
            val accel = net.at(slot) * (1.0 / store.mass(id))
            store.setVelocity(id, store.velocity(id) + accel * dt)
        }
        for (c in constraints) c.applyVelocity(store, groups, t)

        for (id in store.liveIds()) {
            store.setPosition(id, store.position(id) + store.velocity(id) * dt)
        }
        for (c in constraints) c.applyPosition(store, groups, t)

        checkForBlowUp(store)
    }

    private fun accumulateForces(
        store: ParticleStore, groups: Groups, forces: List<Force>, t: Double,
    ): ChunkAccumulator {
        val capacity = store.capacity
        val chunks = Array(chunkCount) { ChunkAccumulator(capacity) }
        for (chunkIndex in 0 until chunkCount) {
            for (force in forces) {
                force.accumulate(store, groups, t, chunks[chunkIndex], chunkIndex, chunkCount)
            }
        }
        val merged = ChunkAccumulator(capacity)
        for (chunk in chunks) chunk.addInto(merged)
        return merged
    }

    private fun checkForBlowUp(store: ParticleStore) {
        for (id in store.liveIds()) {
            val p = store.position(id)
            val v = store.velocity(id)
            if (!p.isFinite() || !v.isFinite()) {
                throw BlowUpException("particle $id blew up: position=$p velocity=$v")
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_COUNT = 4
    }
}
