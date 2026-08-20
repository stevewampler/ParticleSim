package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.sqrt

/** Constant acceleration applied to every member of [group] (§5.2) — e.g. `[0, -9.8, 0]`. */
class UniformGravity(
    private val group: String,
    private val acceleration: Vector3,
    override val name: String? = null,
) : Force {
    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val members = groups.membersOf(group).toList()
        var i = chunkIndex
        while (i < members.size) {
            val id = members[i]
            chunk.add(store.slotOf(id), acceleration * store.mass(id))
            i += chunkCount
        }
    }
}

/** Drag/air resistance opposing velocity, linear or quadratic in speed (§5.2). */
class Drag(
    private val group: String,
    private val coefficient: Double,
    private val quadratic: Boolean = false,
    override val name: String? = null,
) : Force {
    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val members = groups.membersOf(group).toList()
        var i = chunkIndex
        while (i < members.size) {
            val id = members[i]
            val v = store.velocity(id)
            val speed = v.length()
            if (speed > 0.0) {
                val magnitude = if (quadratic) coefficient * speed * speed else coefficient * speed
                chunk.add(store.slotOf(id), v * (-magnitude / speed))
            }
            i += chunkCount
        }
    }
}

/**
 * Pairwise Newtonian gravity between every pair of particles in [group] (§5.2). Strides the
 * *outer* index of the O(n²) pair loop across chunks — each chunk still runs the inner loop
 * to completion for its assigned outer indices, so every pair is computed exactly once.
 * [softening] is this force's own minimum-distance floor (§13.2): N-body and spring forces
 * operate at very different distance scales, so each force type owns its own default rather
 * than sharing one global constant.
 */
class NBodyGravity(
    private val group: String,
    private val g: Double = 6.674e-11,
    private val softening: Double = DEFAULT_SOFTENING,
    override val name: String? = null,
) : Force {
    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val members = groups.membersOf(group).toList()
        var i = chunkIndex
        while (i < members.size) {
            val idA = members[i]
            val posA = store.position(idA)
            val massA = store.mass(idA)
            for (j in i + 1 until members.size) {
                val idB = members[j]
                val delta = store.position(idB) - posA
                val distSq = delta.lengthSquared() + softening * softening
                val dist = sqrt(distSq)
                val forceMag = g * massA * store.mass(idB) / distSq
                val dir = delta * (1.0 / dist)
                chunk.add(store.slotOf(idA), dir * forceMag)
                chunk.add(store.slotOf(idB), dir * -forceMag)
            }
            i += chunkCount
        }
    }

    companion object {
        const val DEFAULT_SOFTENING = 1e-3
    }
}

/**
 * A spring between exactly two particles (§5.1) — Hooke's law about [restLength], with
 * independent [extensionStiffness]/[compressionStiffness] overriding the symmetric
 * [stiffness] default depending on whether the spring is stretched or compressed. A single
 * spring is one atomic unit of work (nothing to subdivide across chunks), so it always
 * contributes on `chunkIndex == 0`; Phase 4's auto-generated mesh springs (potentially
 * thousands of them) should be one `Force` striding its own pair list like [NBodyGravity],
 * not thousands of individual `Spring` instances that would all collide on chunk 0.
 *
 * Optionally breakable (§5.4): [breakThreshold] is a maximum *displacement from [restLength]*
 * (meters) — the default/symmetric limit; [extensionBreakThreshold]/[compressionBreakThreshold]
 * independently override it, mirroring the stiffness split above. Left at the default
 * [Double.POSITIVE_INFINITY], a spring never breaks. A rope that snaps under tension but goes
 * slack (never breaks) under compression is `extensionBreakThreshold` finite,
 * `compressionBreakThreshold` left at its infinite default.
 */
class Spring(
    private val idA: Int,
    private val idB: Int,
    private val restLength: Double,
    private val stiffness: Double,
    private val extensionStiffness: Double = stiffness,
    private val compressionStiffness: Double = stiffness,
    private val minLength: Double = DEFAULT_MIN_LENGTH,
    private val breakThreshold: Double = Double.POSITIVE_INFINITY,
    private val extensionBreakThreshold: Double = breakThreshold,
    private val compressionBreakThreshold: Double = breakThreshold,
    override val name: String? = null,
) : Force, Breakable, PairwiseForce {
    override val particleA: Int get() = idA
    override val particleB: Int get() = idB

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        if (chunkIndex != 0) return
        val delta = store.position(idB) - store.position(idA)
        val length = maxOf(delta.length(), minLength)
        val displacement = length - restLength
        val k = if (displacement > 0.0) extensionStiffness else compressionStiffness
        val dir = delta * (1.0 / length)
        val forceOnB = dir * (-k * displacement)
        chunk.add(store.slotOf(idB), forceOnB)
        chunk.add(store.slotOf(idA), -forceOnB)
    }

    override fun shouldBreak(store: ParticleStore): Boolean {
        val length = (store.position(idB) - store.position(idA)).length()
        val displacement = length - restLength
        return if (displacement >= 0.0) displacement > extensionBreakThreshold else -displacement > compressionBreakThreshold
    }

    /** Elastic potential energy stored in the spring at its current length (§11, §13.5). */
    fun potentialEnergy(store: ParticleStore): Double {
        val length = maxOf((store.position(idB) - store.position(idA)).length(), minLength)
        val displacement = length - restLength
        val k = if (displacement > 0.0) extensionStiffness else compressionStiffness
        return 0.5 * k * displacement * displacement
    }

    companion object {
        const val DEFAULT_MIN_LENGTH = 1e-9
    }
}

/**
 * A damper between exactly two particles (§5.1) — force proportional to relative velocity
 * along the connecting axis, direction-dependent on whether the pair is separating
 * (extending) or approaching (compressing). Same single-atomic-work chunking note as
 * [Spring] applies.
 *
 * Optionally breakable (§5.4), same [breakThreshold]/[extensionBreakThreshold]/
 * [compressionBreakThreshold] pattern as [Spring] — but a damper has no rest length to
 * measure a displacement against, so its threshold is a *relative-velocity magnitude*
 * (m/s) instead of a distance: the same quantity its own force law is already keyed on.
 */
class Damper(
    private val idA: Int,
    private val idB: Int,
    private val damping: Double,
    private val extensionDamping: Double = damping,
    private val compressionDamping: Double = damping,
    private val minLength: Double = Spring.DEFAULT_MIN_LENGTH,
    private val breakThreshold: Double = Double.POSITIVE_INFINITY,
    private val extensionBreakThreshold: Double = breakThreshold,
    private val compressionBreakThreshold: Double = breakThreshold,
    override val name: String? = null,
) : Force, Breakable, PairwiseForce {
    override val particleA: Int get() = idA
    override val particleB: Int get() = idB

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        if (chunkIndex != 0) return
        val delta = store.position(idB) - store.position(idA)
        val length = maxOf(delta.length(), minLength)
        val dir = delta * (1.0 / length)
        val relativeVelocity = (store.velocity(idB) - store.velocity(idA)).dot(dir)
        val c = if (relativeVelocity > 0.0) extensionDamping else compressionDamping
        val forceOnB = dir * (-c * relativeVelocity)
        chunk.add(store.slotOf(idB), forceOnB)
        chunk.add(store.slotOf(idA), -forceOnB)
    }

    override fun shouldBreak(store: ParticleStore): Boolean {
        val delta = store.position(idB) - store.position(idA)
        val length = maxOf(delta.length(), minLength)
        val dir = delta * (1.0 / length)
        val relativeVelocity = (store.velocity(idB) - store.velocity(idA)).dot(dir)
        return if (relativeVelocity >= 0.0) relativeVelocity > extensionBreakThreshold else -relativeVelocity > compressionBreakThreshold
    }
}

/**
 * A constant external force applied to every member of [group] — the accumulation-pass
 * implementation of §6's "fixed force" constraint. Mechanically identical to a field force
 * (it just adds a constant vector), so it's implemented as one rather than as a third,
 * separate constraint mechanism.
 */
class ConstantForce(
    private val group: String,
    private val force: Vector3,
    override val name: String? = null,
) : Force {
    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val members = groups.membersOf(group).toList()
        var i = chunkIndex
        while (i < members.size) {
            chunk.add(store.slotOf(members[i]), force)
            i += chunkCount
        }
    }
}
