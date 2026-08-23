package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.surface.Surface
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One particle-group-vs-[Surface] collision rule (§12.4's stretch goal, promoted out of
 * speculation by §12.8's trampoline). Unlike [ParticleColliderRule]'s [Collider] — always
 * static geometry or, at most, a rigid body moving as a whole — a [Surface]'s triangles are
 * vertices of ordinary simulated particles: they deform under the mesh's own spring forces
 * every step, and a contact here must push back on them too (Newton's third law), not just
 * on the colliding particle. That's the genuinely new mechanics [SurfaceCollisionSystem] adds;
 * everything else (restitution, asymmetric compression/extension damping, the rest-velocity/
 * rest-penetration clamp) reuses [ParticleColliderRule]'s own formulas so a surface contact
 * feels like the same kind of bounce a static collider gives, not a different physical model.
 */
data class SurfaceCollisionRule(
    val group: String,
    val surface: Surface,
    val restitution: Double,
    val compressionDamping: Double = 0.0,
    val extensionDamping: Double = 0.0,
    /** Fraction of penetration corrected per step (§13.4), applied to the particle only — the
     * surface's own vertices are left to the mesh's spring forces to settle, the same way a
     * static [Collider]'s "position" never needs correcting. */
    val correctionFactor: Double = 0.2,
)

/**
 * Resolves particle-vs-triangulated-surface contacts, mirroring [CollisionSystem]'s own
 * "caller runs this after [particlesim.physics.Integrator.step]" contract and its
 * [restVelocity]/[restPenetration] resting-contact clamp (§12.7) — same constants, same
 * meaning, just measured relative to the contact point's *interpolated* velocity (the
 * barycentric blend of the triangle's three vertex velocities) instead of a rigid collider's.
 *
 * Narrow phase is brute-force over every triangle in the rule's surface, picking whichever one
 * penetrates deepest for a given particle (there is no broad-phase partitioning here — see
 * requirements.md §9.3/§12.4: shared spatial partitioning is for N-body/collision at real
 * scale, and one ball against a few hundred trampoline triangles doesn't need it). The contact
 * normal points from the surface toward the particle regardless of which side it approached
 * from — deliberately two-sided, because a closest-point contact is inherently side-agnostic
 * (there is no well-defined "outward" face once the surface can deform through itself), not
 * because a trampoline specifically needs to catch from both sides.
 */
class SurfaceCollisionSystem(
    private val rules: List<SurfaceCollisionRule>,
    private val restVelocity: Double = 0.01,
    private val restPenetration: Double = 0.005,
) {
    fun resolve(store: ParticleStore, groups: Groups, t: Double, dt: Double) {
        for (rule in rules) {
            for (id in groups.membersOf(rule.group)) {
                val radius = store.radius(id) ?: continue
                val contact = deepestContact(store, rule.surface, store.position(id), radius) ?: continue
                respond(store, id, contact, rule)
            }
        }
    }

    private class TriangleContact(
        val normal: Vector3,
        val penetration: Double,
        val a: Int, val b: Int, val c: Int,
        val u: Double, val v: Double, val w: Double,
    )

    private fun deepestContact(store: ParticleStore, surface: Surface, particlePos: Vector3, radius: Double): TriangleContact? {
        var best: TriangleContact? = null
        for (triangle in surface.triangles) {
            val closest = triangle.closestPoint(store, particlePos)
            val delta = particlePos - closest.point
            val dist = delta.length()
            val penetration = radius - dist
            if (penetration <= 0.0) continue
            // Degenerate only when the particle center sits exactly on the surface point; an
            // arbitrary normal here is no worse than SphereCollider's own coincident-centers case.
            val normal = if (dist > 1e-12) delta * (1.0 / dist) else Vector3(0.0, 1.0, 0.0)
            if (best == null || penetration > best.penetration) {
                best = TriangleContact(normal, penetration, triangle.a, triangle.b, triangle.c, closest.u, closest.v, closest.w)
            }
        }
        return best
    }

    private fun respond(store: ParticleStore, id: Int, contact: TriangleContact, rule: SurfaceCollisionRule) {
        val massP = store.mass(id)
        val massA = store.mass(contact.a)
        val massB = store.mass(contact.b)
        val massC = store.mass(contact.c)

        val velP = store.velocity(id)
        val velA = store.velocity(contact.a)
        val velB = store.velocity(contact.b)
        val velC = store.velocity(contact.c)
        val contactVel = velA * contact.u + velB * contact.v + velC * contact.w

        val relVel = (velP - contactVel).dot(contact.normal)

        val newRelVel = when {
            abs(relVel) < restVelocity && contact.penetration < restPenetration -> 0.0
            relVel < 0.0 -> -rule.restitution * relVel / sqrt(1.0 + rule.compressionDamping)
            else -> relVel / sqrt(1.0 + rule.extensionDamping)
        }
        val deltaRelVel = newRelVel - relVel
        if (deltaRelVel == 0.0) {
            store.setPosition(id, store.position(id) + contact.normal * (contact.penetration * rule.correctionFactor))
            return
        }

        // Impulse J along the normal that produces exactly deltaRelVel of relative-velocity
        // change: v_p' = v_p + (J/m_p)n, v_i' = v_i - (J*w_i/m_i)n for each vertex i, so
        // relVel' - relVel = J*(1/m_p + sum(w_i^2/m_i)) - solve for J.
        val invMassSum = (1.0 / massP) +
            (contact.u * contact.u / massA) + (contact.v * contact.v / massB) + (contact.w * contact.w / massC)
        val impulse = deltaRelVel / invMassSum

        store.setVelocity(id, velP + contact.normal * (impulse / massP))
        store.setVelocity(contact.a, velA - contact.normal * (impulse * contact.u / massA))
        store.setVelocity(contact.b, velB - contact.normal * (impulse * contact.v / massB))
        store.setVelocity(contact.c, velC - contact.normal * (impulse * contact.w / massC))

        store.setPosition(id, store.position(id) + contact.normal * (contact.penetration * rule.correctionFactor))
    }
}
