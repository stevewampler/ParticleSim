package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Constraint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One group-vs-group particle-particle collision rule (§12.3, §12.4's "sphere-sphere
 * (particle-particle)" narrow phase, §12.5). [groupB] defaults to [groupA] — the common "this
 * group collides with itself" case from §12.3's own example ("debris... with each other") —
 * rather than requiring a second, identical group name at every call site.
 *
 * A rule with [groupA] different from [groupB] assumes the two groups are otherwise disjoint:
 * a particle that happens to be a member of both would be checked against itself twice (once
 * from each side), which the same-group case explicitly avoids via its own triangular pairing.
 * Every group in this codebase's shape library so far is exactly one of "the whole shape" or
 * "one part of it," never both at once for two groups sharing a rule, so this hasn't come up in
 * practice — worth revisiting only once a scenario actually needs overlapping collision groups.
 */
data class ParticleCollisionRule(
    val groupA: String,
    val groupB: String = groupA,
    val restitution: Double,
    val compressionDamping: Double = 0.0,
    val extensionDamping: Double = 0.0,
    /** Fraction of penetration corrected per step (§13.4), split between the two particles by
     * inverse mass — the lighter one (or the only finite-mass one, if the other is pinned)
     * moves more. */
    val correctionFactor: Double = 0.2,
)

/**
 * Resolves particle-vs-particle contacts, mirroring [CollisionSystem]/[SurfaceCollisionSystem]'s
 * own "caller runs this after [particlesim.physics.Integrator.step]" contract and
 * [restVelocity]/[restPenetration] resting-contact clamp (§12.7).
 *
 * Unlike either of those, both sides of a contact here are ordinary dynamic particles — a
 * genuine two-body impulse, not one dynamic side and one infinite-mass/static side — *unless*
 * one side is under an active [Constraint] that pins it (§12.5: "constrained particles behave
 * as infinite mass in collision response, ... never themselves moved by a collision"). This is
 * why [resolve] takes the step's live `constraints` list, not just `store`/`groups` like its
 * siblings: it's the only way to know, for *this* step, which particle ids a
 * [particlesim.physics.FixedPosition]/[particlesim.physics.FixedVelocity]/
 * [particlesim.physics.DragConstraint] currently pins, via [Constraint.pinnedIds].
 *
 * No broad phase (brute-force over each rule's candidate pairs) — same call made for
 * [SurfaceCollisionSystem]: a spatial index is deferred until a scenario has enough
 * particles/colliders to actually need one (§9.3), not built ahead of a concrete consumer.
 */
class ParticleCollisionSystem(
    private val rules: List<ParticleCollisionRule>,
    private val restVelocity: Double = 0.01,
    private val restPenetration: Double = 0.005,
) {
    fun resolve(store: ParticleStore, groups: Groups, constraints: List<Constraint>) {
        val pinnedIds = HashSet<Int>()
        for (constraint in constraints) pinnedIds += constraint.pinnedIds(groups)

        for (rule in rules) {
            for ((a, b) in candidatePairs(groups, rule)) {
                val radiusA = store.radius(a) ?: continue
                val radiusB = store.radius(b) ?: continue
                val posA = store.position(a)
                val posB = store.position(b)
                val delta = posA - posB
                val dist = delta.length()
                val penetration = (radiusA + radiusB) - dist
                if (penetration <= 0.0) continue
                // Degenerate only when the two centers coincide exactly; an arbitrary normal
                // here is no worse than SphereCollider's own coincident-centers fallback.
                val normal = if (dist > 1e-12) delta * (1.0 / dist) else Vector3(0.0, 1.0, 0.0)
                respond(store, a, b, normal, penetration, rule, pinnedIds)
            }
        }
    }

    private fun candidatePairs(groups: Groups, rule: ParticleCollisionRule): List<Pair<Int, Int>> {
        val membersA = groups.membersOf(rule.groupA).toList()
        if (rule.groupA == rule.groupB) {
            val pairs = ArrayList<Pair<Int, Int>>(membersA.size)
            for (i in membersA.indices) for (j in i + 1 until membersA.size) pairs += membersA[i] to membersA[j]
            return pairs
        }
        val membersB = groups.membersOf(rule.groupB).toList()
        val pairs = ArrayList<Pair<Int, Int>>(membersA.size * membersB.size)
        for (a in membersA) for (b in membersB) if (a != b) pairs += a to b
        return pairs
    }

    private fun respond(
        store: ParticleStore,
        a: Int,
        b: Int,
        normal: Vector3,
        penetration: Double,
        rule: ParticleCollisionRule,
        pinnedIds: Set<Int>,
    ) {
        val invMassA = if (a in pinnedIds) 0.0 else 1.0 / store.mass(a)
        val invMassB = if (b in pinnedIds) 0.0 else 1.0 / store.mass(b)
        val invMassSum = invMassA + invMassB
        if (invMassSum <= 0.0) return // both sides pinned - an immovable object meeting one

        val velA = store.velocity(a)
        val velB = store.velocity(b)
        // normal points from b toward a, matching Contact's "away from the collider, toward
        // the particle" convention with b standing in for the collider side: relVel < 0 means
        // a is closing on b, exactly the sign convention ParticleColliderRule/
        // SurfaceCollisionSystem already use.
        val relVel = (velA - velB).dot(normal)

        val newRelVel = when {
            abs(relVel) < restVelocity && penetration < restPenetration -> 0.0
            relVel < 0.0 -> -rule.restitution * relVel / sqrt(1.0 + rule.compressionDamping)
            else -> relVel / sqrt(1.0 + rule.extensionDamping)
        }
        val deltaRelVel = newRelVel - relVel
        if (deltaRelVel != 0.0) {
            val impulse = deltaRelVel / invMassSum
            store.setVelocity(a, velA + normal * (impulse * invMassA))
            store.setVelocity(b, velB - normal * (impulse * invMassB))
        }

        val correction = penetration * rule.correctionFactor
        store.setPosition(a, store.position(a) + normal * (correction * (invMassA / invMassSum)))
        store.setPosition(b, store.position(b) - normal * (correction * (invMassB / invMassSum)))
    }
}
