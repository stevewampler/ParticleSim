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
 * Pins every member of [group] to a constant [position] and zero velocity (§6). Scripted,
 * time-varying fixed positions are `[stretch]` (§6) — not implemented here.
 */
class FixedPosition(private val group: String, private val position: Vector3) : Constraint {
    override fun applyPosition(store: ParticleStore, groups: Groups, t: Double) {
        for (id in groups.membersOf(group)) {
            store.setPosition(id, position)
            store.setVelocity(id, Vector3.ZERO)
        }
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
