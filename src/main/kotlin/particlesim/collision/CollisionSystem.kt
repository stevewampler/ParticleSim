package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One particle-group-vs-collider collision rule (§12.3, §12.5). Only particles with a radius
 * set (§12.1) participate — a group member with no radius is silently skipped, never an error,
 * since "no radius" just means "doesn't collide."
 *
 * [compressionDamping]/[extensionDamping] reuse §5.1's asymmetric-damper naming, but — unlike
 * that continuous per-step force — they act as a one-shot attenuation folded into this
 * contact's restitution impulse: `relVelAfter = restitution·|relVelBefore| / sqrt(1 + damping)`.
 * A damping of 0 reduces to plain restitution; larger damping smoothly pulls the outgoing
 * speed toward zero without ever overshooting into "moving further into the surface," so no
 * clamping is needed. This is deliberately dt-independent, matching restitution's own
 * dt-independence, rather than an `F·dt/mass` impulse — a force integrated over a single
 * discrete detection step vanishes as dt shrinks (confirmed empirically: an early version used
 * exactly that, and it was indistinguishable from zero damping at `dt = 1e-3`), which isn't a
 * coherent physical model for "how bouncy is this contact." A plain `/(1 + damping)` divisor
 * was tried first and rejected too — visually confirmed against §12.6's own parameters
 * (`compressionDamping: 3.0`), it killed ~82% of the impact speed on the very first bounce,
 * reading as "drops and just stops" rather than the demo's own "watch it bounce, settle
 * noticeably faster" description. The gentler `sqrt` form keeps `extensionDamping: 0.2`
 * (barely below 1.0) close to a crisp, near-full rebound while still making
 * `compressionDamping: 3.0` (halved, `1/sqrt(4)`) settle in about a third as many visible
 * bounces as undamped restitution — tuned empirically against the running demo, the same way
 * the flag's wind strength was in Phase 4, since the spec deliberately leaves the exact
 * damping formula unspecified.
 */
data class ParticleColliderRule(
    val group: String,
    val collider: Collider,
    val restitution: Double,
    val compressionDamping: Double = 0.0,
    val extensionDamping: Double = 0.0,
    /** Fraction of penetration corrected per step (§13.4) — gradual, not instantaneous. */
    val correctionFactor: Double = 0.2,
    /** Coulomb friction (§12.5), promoted out of `[stretch]` once particle-vs-particle piles
     * (`ParticleCollisionDebugDemo`) exposed a concrete need: without it, nothing ever stops a
     * particle's tangential (along-the-surface) velocity, so any sideways nudge from a
     * collision persists forever. [staticFriction] governs a contact already within the
     * [CollisionSystem]'s own rest thresholds (§12.7) — not a hard on/off "stick," but the
     * *fraction* of tangential velocity killed per step (`1.0` = instant stop, `0.3` = decays
     * over a handful of steps); a hard binary stop was tried first and rejected — several
     * particles settling within the same frame would visibly *snap* to a halt one by one
     * rather than gently slowing, which read as a bug the instant it was watched in the
     * browser. [kineticFriction] governs everything else (still actively bouncing/sliding):
     * true Coulomb kinetic friction, an impulse opposing the tangential relative velocity,
     * capped at `kineticFriction * (that step's own normal impulse magnitude)` and never
     * enough to overshoot into reversing the slide. Both default to `0.0` (frictionless),
     * matching every rule/demo built before this. */
    val staticFriction: Double = 0.0,
    val kineticFriction: Double = 0.0,
)

/**
 * Resolves collisions as a step the *caller* runs after [particlesim.physics.Integrator.step]
 * completes, not a stage inside it: collision needs colliders, rules, and per-contact state
 * the integrator has no business knowing about, and keeping it a separate call keeps the
 * integrator's own contract (forces in, state out) unchanged (§12.4, §12.5).
 *
 * [restVelocity]/[restPenetration] are global, not per-rule (§12.7) — when a contact's
 * relative normal speed and penetration are both already below these thresholds, the normal
 * velocity component is clamped directly to zero instead of having restitution/damping
 * applied, so a nearly-resting particle doesn't jitter from restitution repeatedly
 * "re-bouncing" it off numerical noise. Full sleeping (temporal hysteresis + wake
 * propagation) is deferred — see TODO.md.
 */
class CollisionSystem(
    private val particleColliderRules: List<ParticleColliderRule>,
    private val restVelocity: Double = 0.01,
    private val restPenetration: Double = 0.005,
) {
    fun resolve(store: ParticleStore, groups: Groups, t: Double, dt: Double) {
        for (collider in particleColliderRules.map { it.collider }.distinct()) {
            collider.advance(t, dt)
        }

        for (rule in particleColliderRules) {
            if (!groups.isEnabled(rule.group)) continue
            for (id in groups.membersOf(rule.group)) {
                val radius = store.radius(id) ?: continue
                val contact = rule.collider.contact(store.position(id), radius) ?: continue
                respond(store, id, rule.collider, contact, rule)
            }
        }
    }

    private fun respond(
        store: ParticleStore,
        id: Int,
        collider: Collider,
        contact: Contact,
        rule: ParticleColliderRule,
    ) {
        val v = store.velocity(id)
        val relVelVector = v - collider.velocity
        val relVel = relVelVector.dot(contact.normal)
        val isResting = abs(relVel) < restVelocity && contact.penetration < restPenetration

        val newRelVel = when {
            isResting -> 0.0
            relVel < 0.0 -> -rule.restitution * relVel / sqrt(1.0 + rule.compressionDamping)
            else -> relVel / sqrt(1.0 + rule.extensionDamping)
        }
        val deltaRelVel = newRelVel - relVel

        // Infinite-mass collider: the particle absorbs the entire relative-velocity change
        // itself, so (as already true of the normal-direction line above) no mass or impulse
        // division is needed anywhere here — velocity-delta *is* impulse-per-unit-mass for a
        // single dynamic body, in every direction, not just the normal.
        val tangentialDelta = relVelVector - contact.normal * relVel
        val tangentialSpeed = tangentialDelta.length()
        val frictionDelta = if (tangentialSpeed > 1e-9) {
            if (isResting) {
                if (rule.staticFriction > 0.0) tangentialDelta * -rule.staticFriction.coerceIn(0.0, 1.0) else Vector3.ZERO
            } else if (rule.kineticFriction > 0.0) {
                val tangentDir = tangentialDelta * (1.0 / tangentialSpeed)
                val stopSpeed = min(rule.kineticFriction * abs(deltaRelVel), tangentialSpeed)
                tangentDir * -stopSpeed
            } else {
                Vector3.ZERO
            }
        } else {
            Vector3.ZERO
        }

        store.setVelocity(id, v + contact.normal * deltaRelVel + frictionDelta)
        store.setPosition(id, store.position(id) + contact.normal * (contact.penetration * rule.correctionFactor))
    }
}
