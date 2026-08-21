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

/**
 * §9.4's interactive drag: pins exactly one particle to a live, externally-updated target
 * position each step — the viewer-driven counterpart to [FixedPosition]'s static/expression
 * target, reusing the same constraint mechanism rather than a parallel one (§9.4's own framing:
 * "driven exactly like a fixed-position constraint, except the target comes from the live input
 * stream"). Targets *one specific id*, not a named [Groups] selector like every other
 * constraint here — deliberately: a drag session is ad hoc and freely reassigned particle-to-
 * particle over a session's lifetime (whichever one the user last clicked), so registering a
 * throwaway one-off group per drag would just accumulate stale group entries for no benefit.
 *
 * Nothing here tracks velocity analytically during the hold — [applyPosition] forcibly pins
 * position and zeroes velocity every step, exactly like [FixedPosition], so a connected spring
 * only ever feels the dragged particle's position changing, never a velocity it doesn't
 * actually have. [releaseVelocity] recovers a natural "throw" velocity from the two most recent
 * targets divided by the step's `dt` on request — the same finite-difference-velocity idea
 * moving colliders already use (§12.5) rather than a second, parallel velocity-tracking path.
 */
class DragConstraint(val particleId: Int, initialTarget: Vector3) : Constraint {
    private var currentTarget = initialTarget
    private var previousTarget = initialTarget
    private var lastDt = 0.0

    /** Called once per step this constraint is live, with the target meant for *this* step
     * (§9.4: the viewer stamps each target with the physics step index it's for — the caller
     * driving the step loop is responsible for only calling this with the target actually
     * meant for the step about to run, this class just applies whatever it's given). */
    fun updateTarget(target: Vector3, dt: Double) {
        previousTarget = currentTarget
        currentTarget = target
        lastDt = dt
    }

    override fun applyPosition(store: ParticleStore, groups: Groups, t: Double) {
        store.setPosition(particleId, currentTarget)
        store.setVelocity(particleId, Vector3.ZERO)
    }

    /** Call on release, before removing this constraint from the active list, so letting go
     * while moving imparts a natural throw instead of a dead stop (§9.4). Zero if the target
     * was never updated after being created (picked up and released without moving). */
    fun releaseVelocity(): Vector3 =
        if (lastDt > 0.0) (currentTarget - previousTarget) * (1.0 / lastDt) else Vector3.ZERO
}
