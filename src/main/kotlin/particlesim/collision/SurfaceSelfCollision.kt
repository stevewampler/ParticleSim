package particlesim.collision

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.surface.Surface
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One surface-vs-itself collision rule (§12.4's "Surface self-collision," promoted out of
 * `[stretch]` for §7.3's flag). Keeps two topologically-*distant* parts of the same cloth mesh
 * from passing through each other as it billows/folds — e.g. a sharply-folded corner pressing
 * back into the sheet — unlike [SurfaceCollisionRule], which is one *other* particle group
 * against a surface.
 *
 * **A documented approximation, not a robust cloth solver**: narrow phase is brute-force
 * vertex-vs-triangle within one mesh, no broad phase, no continuous collision detection — the
 * same "revisit if a scenario needs it" stance [SurfaceCollisionSystem] already takes. A single
 * [SurfaceSelfCollisionSystem.resolve] pass can also correct the same tight fold from both a
 * vertex's own query *and* a nearby vertex's separate query in the same step — each nudge pushes
 * the two layers further apart, not against each other, so this doesn't diverge, but it isn't a
 * from-first-principles simultaneous solve either.
 */
data class SurfaceSelfCollisionRule(
    val surface: Surface,
    /** Minimum allowed gap between a vertex and a non-adjacent triangle — this mesh's own
     * "thickness," since a cloth particle has no [particlesim.core.ParticleStore.radius] of its
     * own the way a colliding ball does. No sensible generic default — depends entirely on the
     * mesh's own scale (spacing between neighboring particles). */
    val thickness: Double,
    // Fully damped, no bounce by default — like SurfaceCollisionRule's pole/rope use, this
    // pairing exists to stop interpenetration, not to make the cloth bounce off itself.
    // Deliberately no extensionDamping counterpart: a self-contact that's already separating
    // needs no correction, the same as SurfaceCollisionRule's own extensionDamping = 0.0 default.
    val restitution: Double = 0.0,
    val compressionDamping: Double = 1.0,
    val correctionFactor: Double = 0.2,
    /** Triangles within this many mesh-*edge* hops of a query vertex's own incident triangles
     * are excluded from that vertex's narrow phase. Without this, a vertex is trivially
     * "penetrating" its own triangles (zero distance, by construction) and its immediate
     * neighborhood, whose natural resting curvature routinely sits closer than [thickness] with
     * no real fold involved. See [SurfaceSelfCollisionSystem]'s own doc comment for how the
     * exclusion set is computed. */
    val excludeRings: Int = 2,
)

/**
 * Resolves a [Surface] against itself, mirroring [SurfaceCollisionSystem]'s restitution/damping/
 * rest-clamp formulas (§12.7) but with both sides of a contact being the *same* kind of
 * finite-mass cloth particle — unlike a static [Collider] or a particle-vs-surface contact,
 * there's no "which side is the immovable one" here, so both the query vertex and the contact
 * triangle's three vertices give way, weighted by the same generalized inverse-mass split the
 * velocity impulse uses. **This also means the positional correction is split across both
 * sides** — unlike [SurfaceCollisionSystem.respond], which only ever corrects its query
 * particle's position and leaves the surface's own vertices to the mesh's spring forces on the
 * next step (a deliberate choice there, since the "other side" is a different, independently
 * authoritative object). Here the other side *is* this same mesh, so leaving it uncorrected
 * would silently favor whichever vertex's query happened to run first in a given step's
 * iteration order.
 *
 * Narrow phase excludes each vertex's own topologically-nearby triangles
 * ([SurfaceSelfCollisionRule.excludeRings]) — built once per rule at construction from the
 * mesh's *edge* adjacency (two triangles sharing an edge, not merely a vertex — the tighter,
 * standard notion of "mesh distance" here, since a shared-vertex graph fans out through
 * high-valence vertices much faster than the surface itself actually spreads), not recomputed
 * per step since a [Surface]'s triangle list doesn't change over a scenario's lifetime (§14.3
 * explicitly scopes destruction/mesh-repair as still open).
 */
class SurfaceSelfCollisionSystem(
    private val rules: List<SurfaceSelfCollisionRule>,
    private val restVelocity: Double = 0.01,
    private val restPenetration: Double = 0.005,
) {
    private class RuleState(val vertices: List<Int>, val excludedTriangles: Map<Int, Set<Int>>)

    private val states: List<RuleState> = rules.map { rule ->
        RuleState(vertexIdsOf(rule.surface), buildExclusion(rule.surface, rule.excludeRings))
    }

    fun resolve(store: ParticleStore) {
        for ((rule, state) in rules.zip(states)) {
            for (vertexId in state.vertices) {
                val excluded = state.excludedTriangles[vertexId] ?: emptySet()
                val contact = deepestContact(store, rule.surface, excluded, vertexId, rule.thickness) ?: continue
                respond(store, vertexId, contact, rule)
            }
        }
    }

    private class TriangleContact(
        val normal: Vector3,
        val penetration: Double,
        val a: Int, val b: Int, val c: Int,
        val u: Double, val v: Double, val w: Double,
    )

    private fun deepestContact(
        store: ParticleStore,
        surface: Surface,
        excludedTriangles: Set<Int>,
        vertexId: Int,
        thickness: Double,
    ): TriangleContact? {
        var best: TriangleContact? = null
        val vertexPos = store.position(vertexId)
        for ((index, triangle) in surface.triangles.withIndex()) {
            if (index in excludedTriangles) continue
            val closest = triangle.closestPoint(store, vertexPos)
            val delta = vertexPos - closest.point
            val dist = delta.length()
            val penetration = thickness - dist
            if (penetration <= 0.0) continue
            // Degenerate only when the query vertex sits exactly on the closest point — no
            // worse than SurfaceCollisionSystem's own coincident-centers fallback.
            val normal = if (dist > 1e-12) delta * (1.0 / dist) else Vector3(0.0, 1.0, 0.0)
            if (best == null || penetration > best.penetration) {
                best = TriangleContact(normal, penetration, triangle.a, triangle.b, triangle.c, closest.u, closest.v, closest.w)
            }
        }
        return best
    }

    private fun respond(store: ParticleStore, vertexId: Int, contact: TriangleContact, rule: SurfaceSelfCollisionRule) {
        val invMassP = 1.0 / store.mass(vertexId)
        val invMassA = 1.0 / store.mass(contact.a)
        val invMassB = 1.0 / store.mass(contact.b)
        val invMassC = 1.0 / store.mass(contact.c)

        val velP = store.velocity(vertexId)
        val velA = store.velocity(contact.a)
        val velB = store.velocity(contact.b)
        val velC = store.velocity(contact.c)
        val contactVel = velA * contact.u + velB * contact.v + velC * contact.w

        val relVelVector = velP - contactVel
        val relVel = relVelVector.dot(contact.normal)
        val isResting = abs(relVel) < restVelocity && contact.penetration < restPenetration

        val newRelVel = when {
            isResting -> 0.0
            relVel < 0.0 -> -rule.restitution * relVel / sqrt(1.0 + rule.compressionDamping)
            else -> relVel // already separating - no correction needed (see class doc)
        }
        val deltaRelVel = newRelVel - relVel

        val invMassSum = invMassP +
            contact.u * contact.u * invMassA + contact.v * contact.v * invMassB + contact.w * contact.w * invMassC
        val impulse = deltaRelVel / invMassSum

        store.setVelocity(vertexId, velP + contact.normal * (impulse * invMassP))
        store.setVelocity(contact.a, velA - contact.normal * (impulse * contact.u * invMassA))
        store.setVelocity(contact.b, velB - contact.normal * (impulse * contact.v * invMassB))
        store.setVelocity(contact.c, velC - contact.normal * (impulse * contact.w * invMassC))

        // Positional correction split the same way, by each side's share of invMassSum - see
        // the class doc for why this differs from SurfaceCollisionSystem's query-only correction.
        val totalCorrection = contact.penetration * rule.correctionFactor
        store.setPosition(vertexId, store.position(vertexId) + contact.normal * (totalCorrection * invMassP / invMassSum))
        store.setPosition(contact.a, store.position(contact.a) - contact.normal * (totalCorrection * contact.u * invMassA / invMassSum))
        store.setPosition(contact.b, store.position(contact.b) - contact.normal * (totalCorrection * contact.v * invMassB / invMassSum))
        store.setPosition(contact.c, store.position(contact.c) - contact.normal * (totalCorrection * contact.w * invMassC / invMassSum))
    }
}

private fun vertexIdsOf(surface: Surface): List<Int> {
    val ids = LinkedHashSet<Int>()
    for (triangle in surface.triangles) {
        ids += triangle.a; ids += triangle.b; ids += triangle.c
    }
    return ids.toList()
}

private fun buildExclusion(surface: Surface, rings: Int): Map<Int, Set<Int>> {
    val triangles = surface.triangles
    val vertexToTriangles = HashMap<Int, MutableList<Int>>()
    for ((index, triangle) in triangles.withIndex()) {
        vertexToTriangles.getOrPut(triangle.a) { mutableListOf() } += index
        vertexToTriangles.getOrPut(triangle.b) { mutableListOf() } += index
        vertexToTriangles.getOrPut(triangle.c) { mutableListOf() } += index
    }

    // Triangle adjacency by shared *edge* (2 of 3 vertices), not merely a shared vertex - see
    // SurfaceSelfCollisionRule.excludeRings' doc comment for why.
    fun edgeKey(x: Int, y: Int) = if (x < y) x to y else y to x
    val edgeToTriangles = HashMap<Pair<Int, Int>, MutableList<Int>>()
    for ((index, triangle) in triangles.withIndex()) {
        for (edge in listOf(edgeKey(triangle.a, triangle.b), edgeKey(triangle.b, triangle.c), edgeKey(triangle.c, triangle.a))) {
            edgeToTriangles.getOrPut(edge) { mutableListOf() } += index
        }
    }
    val triangleAdjacency = Array(triangles.size) { mutableSetOf<Int>() }
    for (shared in edgeToTriangles.values) {
        for (i in shared) for (j in shared) if (i != j) triangleAdjacency[i] += j
    }

    val result = HashMap<Int, Set<Int>>()
    for ((vertex, ownTriangles) in vertexToTriangles) {
        val visited = HashSet(ownTriangles)
        var frontier: Set<Int> = ownTriangles.toSet()
        repeat(rings) {
            val next = HashSet<Int>()
            for (t in frontier) for (adjacent in triangleAdjacency[t]) if (visited.add(adjacent)) next += adjacent
            frontier = next
        }
        result[vertex] = visited
    }
    return result
}
