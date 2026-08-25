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
    /** Optional name (§10.3's name→object registry — same convention as [Force.name] and
     * [particlesim.collision.Collider.name]): an unnamed constraint still applies normally,
     * it's just not individually reachable via the outliner. */
    val name: String?

    fun applyVelocity(store: ParticleStore, groups: Groups, t: Double) {}
    fun applyPosition(store: ParticleStore, groups: Groups, t: Double) {}

    /** Particle ids this constraint pins as of this step — used by particle-vs-particle
     * collision response (§12.5) to treat a constrained particle as infinite mass, the same
     * way a static [particlesim.collision.Collider] already is: it affects what it collides
     * with but is never itself moved by a collision. Empty by default; only a constraint that
     * actually pins position or velocity needs to override it. */
    fun pinnedIds(groups: Groups): Set<Int> = emptySet()
}

/**
 * Pins every member of [group] and zero velocity (§6). Either every member goes to the same
 * shared [position], or (via [atCurrentPositions]) each member freezes individually wherever
 * it already is — needed for e.g. §7.3's flag, where "pin the pole edge" means each particle
 * along that edge keeps its own height, not all collapsing to one point. Scripted,
 * time-varying fixed positions are `[stretch]` (§6) — not implemented here.
 */
/** Respects §10.4's group enable/disable ([Groups.isEnabled]): while [group] is disabled, this
 * constraint pins nothing and [pinnedIds] reports empty, exactly as if it weren't running that
 * step — a disabled group's members are ordinary, unpinned particles for as long as it's off. */
class FixedPosition private constructor(
    private val group: String,
    private val position: Vector3?,
    private val perParticlePosition: Map<Int, Vector3>?,
    override val name: String? = null,
) : Constraint {
    constructor(group: String, position: Vector3, name: String? = null) : this(group, position, null, name)

    override fun applyPosition(store: ParticleStore, groups: Groups, t: Double) {
        if (!groups.isEnabled(group)) return
        for (id in groups.membersOf(group)) {
            store.setPosition(id, perParticlePosition?.get(id) ?: position!!)
            store.setVelocity(id, Vector3.ZERO)
        }
    }

    override fun pinnedIds(groups: Groups): Set<Int> = if (groups.isEnabled(group)) groups.membersOf(group) else emptySet()

    companion object {
        /** Pins every current member of [group] to wherever it is right now, individually. */
        fun atCurrentPositions(group: String, store: ParticleStore, groups: Groups, name: String? = null): FixedPosition =
            FixedPosition(group, null, groups.membersOf(group).associateWith { store.position(it) }, name)
    }
}

/** Pins every member of [group] to a constant [velocity] regardless of forces acting on it (§6). */
class FixedVelocity(
    private val group: String,
    private val velocity: Vector3,
    override val name: String? = null,
) : Constraint {
    override fun applyVelocity(store: ParticleStore, groups: Groups, t: Double) {
        if (!groups.isEnabled(group)) return
        for (id in groups.membersOf(group)) {
            store.setVelocity(id, velocity)
        }
    }

    override fun pinnedIds(groups: Groups): Set<Int> = if (groups.isEnabled(group)) groups.membersOf(group) else emptySet()
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
    // Always unnamed, not a defaulted constructor param like every other Constraint here: a
    // drag session is the ad hoc, freely-reassigned target described above, never something a
    // scene author would look up by name via §10.3's outliner - exposing a name param would be
    // dead API surface with no legitimate caller.
    override val name: String? = null
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

    override fun pinnedIds(groups: Groups): Set<Int> = setOf(particleId)

    /** Call on release, before removing this constraint from the active list, so letting go
     * while moving imparts a natural throw instead of a dead stop (§9.4). Zero if the target
     * was never updated after being created (picked up and released without moving). */
    fun releaseVelocity(): Vector3 =
        if (lastDt > 0.0) (currentTarget - previousTarget) * (1.0 / lastDt) else Vector3.ZERO
}
