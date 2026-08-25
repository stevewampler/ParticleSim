package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Constraint
import kotlin.math.abs
import kotlin.math.min
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
    /** Coulomb friction (§12.5) — see [ParticleColliderRule]'s own doc comment for the
     * static-vs-kinetic split and why static friction is a per-step *fractional* arrest rather
     * than a hard stop. Both default to `0.0` (frictionless). */
    val staticFriction: Double = 0.0,
    val kineticFriction: Double = 0.0,
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
 * **Broad phase**: candidate pairs are generated via [SpatialGrid] (§9.3, §12.4) rather than a
 * brute-force double loop — [ParticleCollisionDebugDemo] scaled to a few thousand particles is
 * this structure's first real consumer, exactly the "revisit once a scenario's particle count
 * makes O(n²) the actual bottleneck" trigger this was deliberately deferred behind. This is
 * collision-only: [particlesim.physics.NBodyGravity] is not wired to this or any other spatial
 * index, deliberately — see [SpatialGrid]'s own doc comment on why the same technique isn't
 * valid for gravity's unbounded interaction range.
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
            for ((a, b) in candidatePairs(store, groups, rule)) {
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

    /**
     * Grid-accelerated replacement for what used to be a brute-force `i<j` (same-group) /
     * full-cross-product (cross-group) double loop. [SpatialGrid] only narrows *which* pairs are
     * considered — completeness for the positions at the moment the grid is built is guaranteed
     * by its cell-size contract (see its own doc comment) — but [respond] mutates the store as
     * [resolve] iterates the pairs this returns, so a pair that only comes into contact *after*
     * an earlier pair's penetration correction (never a candidate here, since the grid was built
     * before that correction happened) is silently missed, where the old brute-force loop's
     * live position re-read would occasionally have caught it. Accepted, not fixed - see
     * [SpatialGrid]'s own doc comment. Independently of that gap, pair order still has to match
     * the old brute-force order exactly for results to be identical whenever several contacts
     * that *are* candidates involve the same particle in one step (see [SpatialGridRegressionTest]).
     * That's why each particle's neighbor query result is sorted back into ascending list-index
     * order before being emitted, rather than left in the grid's arbitrary bucket order: same-group
     * emits `(i, j)` with `i < j` exactly like the old `for i; for j in i+1..size` did, and
     * cross-group emits `(a, b)` with `b`'s membersB-index ascending for each `a` in turn, exactly
     * like the old `for a; for b` did.
     */
    private fun candidatePairs(store: ParticleStore, groups: Groups, rule: ParticleCollisionRule): List<Pair<Int, Int>> {
        // §10.4 group disable: either side disabled means nothing to collide on that side, so
        // the whole rule contributes no pairs this step.
        if (!groups.isEnabled(rule.groupA) || !groups.isEnabled(rule.groupB)) return emptyList()
        val membersA = groups.membersOf(rule.groupA).toList()
        if (rule.groupA == rule.groupB) return sameGroupCandidates(store, membersA)
        val membersB = groups.membersOf(rule.groupB).toList()
        return crossGroupCandidates(store, membersA, membersB)
    }

    private fun maxRadius(store: ParticleStore, ids: List<Int>): Double =
        ids.maxOfOrNull { store.radius(it) ?: 0.0 } ?: 0.0

    private fun sameGroupCandidates(store: ParticleStore, members: List<Int>): List<Pair<Int, Int>> {
        // Twice the largest radius in play: two spheres can only overlap if their centers are
        // within radiusA + radiusB of each other, and that sum is never more than 2 * the max.
        val cellSize = 2.0 * maxRadius(store, members)
        if (cellSize <= 0.0) return emptyList() // no member has a radius - nothing can ever overlap

        val grid = SpatialGrid(cellSize)
        val indexOf = HashMap<Int, Int>(members.size)
        for ((i, id) in members.withIndex()) {
            indexOf[id] = i
            if ((store.radius(id) ?: 0.0) > 0.0) grid.insert(id, store.position(id))
        }

        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in members.indices) {
            val a = members[i]
            if ((store.radius(a) ?: 0.0) <= 0.0) continue
            val js = grid.neighbors(store.position(a)).mapNotNull { indexOf[it] }.filter { it > i }.sorted()
            for (j in js) pairs += a to members[j]
        }
        return pairs
    }

    private fun crossGroupCandidates(store: ParticleStore, membersA: List<Int>, membersB: List<Int>): List<Pair<Int, Int>> {
        val cellSize = 2.0 * maxOf(maxRadius(store, membersA), maxRadius(store, membersB))
        if (cellSize <= 0.0) return emptyList()

        val grid = SpatialGrid(cellSize)
        val indexOfB = HashMap<Int, Int>(membersB.size)
        for ((i, id) in membersB.withIndex()) {
            indexOfB[id] = i
            if ((store.radius(id) ?: 0.0) > 0.0) grid.insert(id, store.position(id))
        }

        val pairs = ArrayList<Pair<Int, Int>>()
        for (a in membersA) {
            if ((store.radius(a) ?: 0.0) <= 0.0) continue
            val js = grid.neighbors(store.position(a)).mapNotNull { indexOfB[it] }.filter { membersB[it] != a }.sorted()
            for (j in js) pairs += a to membersB[j]
        }
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
        val relVelVector = velA - velB
        val relVel = relVelVector.dot(normal)
        val isResting = abs(relVel) < restVelocity && penetration < restPenetration

        val newRelVel = when {
            isResting -> 0.0
            relVel < 0.0 -> -rule.restitution * relVel / sqrt(1.0 + rule.compressionDamping)
            else -> relVel / sqrt(1.0 + rule.extensionDamping)
        }
        val deltaRelVel = newRelVel - relVel
        val impulse = deltaRelVel / invMassSum

        // Same invMassSum scalar applies to any direction (no rotational inertia anywhere in
        // this engine), so friction reuses it exactly like the normal impulse above.
        val tangentialDelta = relVelVector - normal * relVel
        val tangentialSpeed = tangentialDelta.length()
        val frictionImpulse = if (tangentialSpeed > 1e-9) {
            if (isResting) {
                if (rule.staticFriction > 0.0) {
                    tangentialDelta * (-rule.staticFriction.coerceIn(0.0, 1.0) / invMassSum)
                } else {
                    Vector3.ZERO
                }
            } else if (rule.kineticFriction > 0.0) {
                val tangentDir = tangentialDelta * (1.0 / tangentialSpeed)
                val maxStopImpulse = tangentialSpeed / invMassSum
                val frictionMag = min(rule.kineticFriction * abs(impulse), maxStopImpulse)
                tangentDir * -frictionMag
            } else {
                Vector3.ZERO
            }
        } else {
            Vector3.ZERO
        }
        val totalImpulse = normal * impulse + frictionImpulse
        store.setVelocity(a, velA + totalImpulse * invMassA)
        store.setVelocity(b, velB - totalImpulse * invMassB)

        val correction = penetration * rule.correctionFactor
        store.setPosition(a, store.position(a) + normal * (correction * (invMassA / invMassSum)))
        store.setPosition(b, store.position(b) - normal * (correction * (invMassB / invMassSum)))
    }
}
