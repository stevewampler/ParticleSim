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
    private var extensionStiffness: Double = stiffness,
    private var compressionStiffness: Double = stiffness,
    private val damping: Double = 0.0,
    private var extensionDamping: Double = damping,
    private var compressionDamping: Double = damping,
    private val minLength: Double = Spring.DEFAULT_MIN_LENGTH,
    private val breakThreshold: Double = Double.POSITIVE_INFINITY,
    private var extensionBreakThreshold: Double = breakThreshold,
    private var compressionBreakThreshold: Double = breakThreshold,
    override val name: String? = null,
) : Force, EditableFields {
    /** Same rationale as [Spring.editableFields]: exposes the six fields [accumulate]/
     * [activeConnectionsWithBreakProximity] actually read, not the base [stiffness]/[damping]/
     * [breakThreshold] constructor defaults - the break-threshold pair is §10.4's new
     * requirement, letting a surface's tear resistance (e.g. the flag's structural springs,
     * §7.3) be tuned live the same way its stiffness/damping already could. Per-edge
     * [restLength] is deliberately not exposed here — unlike stiffness/damping/break-threshold
     * it isn't one shared value but an array computed per-edge at construction, and editing it
     * live is a different feature. Lowering a break threshold below an edge's current
     * displacement/relative-velocity breaks that edge on its very next [accumulate] call, same
     * as reaching it via normal simulation — there is no "undo" that restores an already-broken
     * edge by raising the threshold back up (§5.4: breaking is permanent). */
    override fun editableFields(): Map<String, FieldValue> = mapOf(
        "extensionStiffness" to FieldValue.Scalar(extensionStiffness),
        "compressionStiffness" to FieldValue.Scalar(compressionStiffness),
        "extensionDamping" to FieldValue.Scalar(extensionDamping),
        "compressionDamping" to FieldValue.Scalar(compressionDamping),
        "extensionBreakThreshold" to FieldValue.Scalar(extensionBreakThreshold),
        "compressionBreakThreshold" to FieldValue.Scalar(compressionBreakThreshold),
    )

    override fun setField(field: String, value: FieldValue): Boolean {
        if (value !is FieldValue.Scalar || value.value.isNaN()) return false
        when (field) {
            "extensionStiffness" -> extensionStiffness = value.value
            "compressionStiffness" -> compressionStiffness = value.value
            "extensionDamping" -> extensionDamping = value.value
            "compressionDamping" -> compressionDamping = value.value
            "extensionBreakThreshold" -> extensionBreakThreshold = value.value
            "compressionBreakThreshold" -> compressionBreakThreshold = value.value
            else -> return false
        }
        return true
    }

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

    /** Endpoints plus per-edge break proximity (§10.2's `breakProximity` line-renderer
     * coloring) for every currently-active edge — `0` at rest, `1` the instant before
     * breaking, mirroring [Breakable.breakProximity]'s semantics. `MeshSprings` isn't itself
     * [Breakable] (it represents *many* independently-breakable edges as one `Force`, §9.3's
     * chunking requirement — there's no single proximity value for "the force" as a whole),
     * so this is its own per-edge equivalent rather than an interface implementation. */
    fun activeConnectionsWithBreakProximity(store: ParticleStore): List<Triple<Int, Int, Double>> {
        val result = ArrayList<Triple<Int, Int, Double>>()
        for (i in idA.indices) {
            if (!active[i]) continue
            val a = idA[i]
            val b = idB[i]
            val length = maxOf((store.position(b) - store.position(a)).length(), minLength)
            val displacement = length - restLength[i]
            val threshold = if (displacement >= 0.0) extensionBreakThreshold else compressionBreakThreshold
            val proximity = if (threshold.isInfinite()) 0.0 else kotlin.math.abs(displacement) / threshold
            result += Triple(a, b, proximity)
        }
        return result
    }

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
