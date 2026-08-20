package particlesim.physics

import particlesim.core.Vector3

/**
 * A private per-particle net-force accumulator for one logical chunk (§9.3, §11) — slot-
 * indexed, matching [particlesim.core.ParticleStore]'s own backing arrays. Multiple chunks
 * exist so that (once Phase 8 turns on multi-threading) each can be filled by a different
 * thread without racing on a shared array; chunks are always merged back together in fixed
 * chunk-index order, so the result is bit-identical regardless of which chunk finishes when.
 */
class ChunkAccumulator(capacity: Int) {
    private val fx = DoubleArray(capacity)
    private val fy = DoubleArray(capacity)
    private val fz = DoubleArray(capacity)

    fun add(slot: Int, force: Vector3) {
        fx[slot] += force.x
        fy[slot] += force.y
        fz[slot] += force.z
    }

    fun at(slot: Int): Vector3 = Vector3(fx[slot], fy[slot], fz[slot])

    /** Adds this chunk's contents into [target] — used for the fixed-order chunk merge. */
    fun addInto(target: ChunkAccumulator) {
        for (i in fx.indices) {
            target.fx[i] += fx[i]
            target.fy[i] += fy[i]
            target.fz[i] += fz[i]
        }
    }
}
