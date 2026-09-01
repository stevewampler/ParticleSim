package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Force
import particlesim.physics.UniformGravity

/**
 * §12.6's worked example: a single ball dropped onto a static plane. Restitution alone
 * (§15.1's analytic test) would let it bounce forever with a slowly-decaying apex height;
 * layering compression/extension damping on top (§12.5) makes it settle out much sooner,
 * which is the point of the demo — the analytic test uses its own zero-damping scenario
 * instead, since damping would pull the bounce-apex ratio away from the pure `e^(2n)` curve
 * it's checking.
 *
 * Also §4.5's second shape-library proof (alongside `buildFlag`): accepts a shared
 * `store`/`groups` and a `placement` so more than one ball-bounce can coexist in one scene —
 * see `ShapePlacement`'s own doc comment for the defaulting/namespacing rules, which apply
 * here identically. The floor collider's position and name move with `placement` too, not
 * just the ball — each instance is a fully self-contained ball-and-its-own-floor, not a ball
 * that happens to share someone else's floor.
 */
data class BallBounceScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val collisions: CollisionSystem,
    val ballId: Int,
    val floor: PlaneCollider,
)

const val BALL_BOUNCE_DT = 1e-3

fun buildBallBounce(
    dropHeight: Double = 5.0,
    restitution: Double = 0.7,
    compressionDamping: Double = 3.0,
    extensionDamping: Double = 0.2,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): BallBounceScenario {
    val ballGroup = placement.name("ball")
    val ballId = store.create(position = Vector3(0.0, dropHeight, 0.0) + placement.offset, radius = ScalarExpr.of(0.2))
    groups.add(ballGroup, ballId)

    val gravity = UniformGravity(ballGroup, Vector3(0.0, -9.8, 0.0), name = placement.name("gravity"))
    val floor = PlaneCollider(VectorExpr.of(placement.offset), normal = Vector3(0.0, 1.0, 0.0), name = placement.name("floor"))
    val rule = ParticleColliderRule(
        group = ballGroup,
        collider = floor,
        restitution = restitution,
        compressionDamping = compressionDamping,
        extensionDamping = extensionDamping,
    )

    return BallBounceScenario(
        store = store,
        groups = groups,
        forces = listOf(gravity),
        collisions = CollisionSystem(listOf(rule)),
        ballId = ballId,
        floor = floor,
    )
}
