package particlesim.physics

import particlesim.core.ParticleStore
import particlesim.core.Groups
import particlesim.surface.Grid

/**
 * A whole set of spring+damper connections generated from mesh topology (§7.1) — structural,
 * shear, or bend edges (see [Grid]) — as *one* [Force], striding its own edge list across
 * chunks the way [NBodyGravity] strides pairs. This is the reason `Spring`/`Damper` note
 * they must not be used one-per-edge for a mesh: a flag can have thousands of edges, and
 * thousands of individual chunk-0-pinned `Force` objects would all collide there instead of
 * actually parallelizing (see the note left in Phase 2's TODO.md when this was anticipated).
 *
 * Spring and damper are combined into one connector per edge, not left as separate `Force`s
 * the way single explicit connections are (§5.1): a mesh edge is one physical connector, and
 * if it breaks, both its spring and damping contribution must stop together. Two independent
 * `Force`s with independent break state couldn't guarantee that without extra synchronization.
 *
 * Optionally breakable (§5.4), same threshold pattern as [Spring]. A broken edge is
 * deactivated in place (not removed from the arrays) rather than routed through
 * [Integrator]'s external [Breakable]/[StepResult] mechanism — that mechanism is for
 * removing a whole `Force`, and a mesh is one `Force` representing many independently
 * breakable edges. Deactivation still happens *after* this step's force is applied (checked
 * once per edge, same "still applies its force the step it broke" semantics as [Spring]).
 * Safe to mutate `active[i]` in place even once Phase 8 runs chunks concurrently: each edge
 * index is touched by exactly one chunk per step (`i % chunkCount` striding), and the
 * chunk-join barrier after accumulation is what gives the *next* step's readers a
 * happens-before edge to see this step's writes — not synchronization on the array itself.
 */
class MeshSprings(
    edges: List<Grid.Edge>,
    store: ParticleStore,
    private val stiffness: Double,
    private val extensionStiffness: Double = stiffness,
    private val compressionStiffness: Double = stiffness,
    private val damping: Double = 0.0,
    private val extensionDamping: Double = damping,
    private val compressionDamping: Double = damping,
    private val minLength: Double = Spring.DEFAULT_MIN_LENGTH,
    private val breakThreshold: Double = Double.POSITIVE_INFINITY,
    private val extensionBreakThreshold: Double = breakThreshold,
    private val compressionBreakThreshold: Double = breakThreshold,
    override val name: String? = null,
) : Force {
    private val idA = IntArray(edges.size) { edges[it].a }
    private val idB = IntArray(edges.size) { edges[it].b }
    private val restLength = DoubleArray(edges.size) {
        (store.position(idB[it]) - store.position(idA[it])).length()
    }
    private val active = BooleanArray(edges.size) { true }

    val edgeCount: Int get() = idA.size

    /** Endpoints of every currently-unbroken edge — for the debug renderer's line view (§10.2). */
    fun activeConnections(): List<Pair<Int, Int>> =
        (0 until idA.size).filter { active[it] }.map { idA[it] to idB[it] }

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        var i = chunkIndex
        while (i < idA.size) {
            if (active[i]) {
                val a = idA[i]
                val b = idB[i]
                val delta = store.position(b) - store.position(a)
                val length = maxOf(delta.length(), minLength)
                val displacement = length - restLength[i]
                val k = if (displacement > 0.0) extensionStiffness else compressionStiffness
                val dir = delta * (1.0 / length)
                var forceOnB = dir * (-k * displacement)

                val relativeVelocity = (store.velocity(b) - store.velocity(a)).dot(dir)
                val c = if (relativeVelocity > 0.0) extensionDamping else compressionDamping
                forceOnB += dir * (-c * relativeVelocity)

                chunk.add(store.slotOf(b), forceOnB)
                chunk.add(store.slotOf(a), -forceOnB)

                val brokeExtending = displacement >= 0.0 && displacement > extensionBreakThreshold
                val brokeCompressing = displacement < 0.0 && -displacement > compressionBreakThreshold
                if (brokeExtending || brokeCompressing) active[i] = false
            }
            i += chunkCount
        }
    }
}
