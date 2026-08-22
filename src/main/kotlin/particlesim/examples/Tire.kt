package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Damper
import particlesim.physics.Force
import particlesim.physics.Spring
import particlesim.physics.UniformGravity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * §4.5's third shape-library entry: a soft ring of particles — a tire, lying flat (in the
 * X/Z plane, +Y up, §11) rather than standing on its edge, since rolling/standing would need
 * friction and rotational dynamics this engine doesn't have yet (§12.5's friction is
 * `[stretch]`). Dropped onto the ground, it deforms on impact and bounces/settles the same
 * way §12.6's ball does — reuses the exact same collision mechanism (`ParticleColliderRule`
 * against a `PlaneCollider`), just applied to every rim particle instead of one.
 *
 * **Structure**: [segments] particles evenly spaced around a circle of [radius], connected to
 * their immediate neighbors by structural springs (the rim itself, closed into a loop) *and*
 * to the particle directly opposite them by a softer "diameter" spring — without the second
 * set, a rim held together only by neighbor-to-neighbor springs has very little resistance to
 * collapsing in on itself under gravity/impact (nothing opposes the ring folding flat); a
 * handful of diameter braces is a much simpler fix than adding a rigid hub-and-spokes wheel
 * structure, and keeps this a soft, deformable tire rather than a rigid disc. Requires
 * [segments] to be even so every particle has an exact opposite.
 */
data class TireScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val collisions: CollisionSystem,
    /** Rim particle ids, in ring order (`rimIds[i]` and `rimIds[(i+1) % segments]` are
     * neighbors) — for rendering the rim as a closed line loop. */
    val rimIds: List<Int>,
)

const val TIRE_DT = 1e-3

fun buildTire(
    radius: Double = 1.0,
    segments: Int = 16,
    dropHeight: Double = 3.0,
    particleRadius: Double = 0.05,
    massPerParticle: Double = 0.02,
    rimStiffness: Double = 300.0,
    rimDamping: Double = 2.0,
    diameterStiffness: Double = 40.0,
    diameterDamping: Double = 1.0,
    restitution: Double = 0.4,
    compressionDamping: Double = 2.0,
    extensionDamping: Double = 0.3,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): TireScenario {
    require(segments >= 4 && segments % 2 == 0) { "segments must be even and at least 4, was $segments" }

    val rimGroup = placement.name("rim")
    val rimIds = (0 until segments).map { i ->
        val angle = 2.0 * PI * i / segments
        val position = Vector3(radius * cos(angle), dropHeight, radius * sin(angle)) + placement.offset
        val id = store.create(position = position, mass = ScalarExpr.of(massPerParticle), radius = ScalarExpr.of(particleRadius))
        groups.add(rimGroup, id)
        id
    }

    val neighborRestLength = 2.0 * radius * sin(PI / segments)
    val neighborSprings = rimIds.indices.map { i ->
        Spring(rimIds[i], rimIds[(i + 1) % segments], restLength = neighborRestLength, stiffness = rimStiffness)
    }
    // Diameter braces only need to connect each opposite pair once, not both directions.
    val diameterSprings = (0 until segments / 2).map { i ->
        Spring(rimIds[i], rimIds[i + segments / 2], restLength = 2.0 * radius, stiffness = diameterStiffness)
    }
    val dampers = rimIds.indices.map { i ->
        Damper(rimIds[i], rimIds[(i + 1) % segments], damping = rimDamping)
    } + (0 until segments / 2).map { i ->
        Damper(rimIds[i], rimIds[i + segments / 2], damping = diameterDamping)
    }

    val gravity = UniformGravity(rimGroup, Vector3(0.0, -9.8, 0.0))
    val floor = PlaneCollider(VectorExpr.of(placement.offset), normal = Vector3(0.0, 1.0, 0.0), name = placement.name("floor"))
    val rule = ParticleColliderRule(
        group = rimGroup,
        collider = floor,
        restitution = restitution,
        compressionDamping = compressionDamping,
        extensionDamping = extensionDamping,
    )

    return TireScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity) + neighborSprings + diameterSprings + dampers,
        collisions = CollisionSystem(listOf(rule)),
        rimIds = rimIds,
    )
}
