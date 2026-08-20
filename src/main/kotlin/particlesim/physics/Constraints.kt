package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3

/**
 * A constraint pins some aspect of a particle's state (§6), overriding what the
 * force/integration step would otherwise produce. Applied in two distinct stages within one
 * integrator step, not one: [applyVelocity] runs after the velocity update but before the
 * position update, so a fixed velocity is the one actually integrated into position this
 * step; [applyPosition] runs after the position update, so a fixed position wins outright
 * regardless of what velocity produced. "Fixed force" (§6) is not a constraint in this
 * sense at all — it never pins state, it's just another additive force term — so it's
 * implemented as [ConstantForce] in the accumulation pass instead.
 */
interface Constraint {
    fun applyVelocity(store: ParticleStore, groups: Groups, t: Double) {}
    fun applyPosition(store: ParticleStore, groups: Groups, t: Double) {}
}

/**
 * Pins every member of [group] and zero velocity (§6). Either every member goes to the same
 * shared [position], or (via [atCurrentPositions]) each member freezes individually wherever
 * it already is — needed for e.g. §7.3's flag, where "pin the pole edge" means each particle
 * along that edge keeps its own height, not all collapsing to one point. Scripted,
 * time-varying fixed positions are `[stretch]` (§6) — not implemented here.
 */
class FixedPosition private constructor(
    private val group: String,
    private val position: Vector3?,
    private val perParticlePosition: Map<Int, Vector3>?,
) : Constraint {
    constructor(group: String, position: Vector3) : this(group, position, null)

    override fun applyPosition(store: ParticleStore, groups: Groups, t: Double) {
        for (id in groups.membersOf(group)) {
            store.setPosition(id, perParticlePosition?.get(id) ?: position!!)
            store.setVelocity(id, Vector3.ZERO)
        }
    }

    companion object {
        /** Pins every current member of [group] to wherever it is right now, individually. */
        fun atCurrentPositions(group: String, store: ParticleStore, groups: Groups): FixedPosition =
            FixedPosition(group, null, groups.membersOf(group).associateWith { store.position(it) })
    }
}

/** Pins every member of [group] to a constant [velocity] regardless of forces acting on it (§6). */
class FixedVelocity(private val group: String, private val velocity: Vector3) : Constraint {
    override fun applyVelocity(store: ParticleStore, groups: Groups, t: Double) {
        for (id in groups.membersOf(group)) {
            store.setVelocity(id, velocity)
        }
    }
}
