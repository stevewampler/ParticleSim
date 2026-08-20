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
 */
data class BallBounceScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val collisions: CollisionSystem,
    val ballId: Int,
)

const val BALL_BOUNCE_DT = 1e-3

fun buildBallBounce(
    dropHeight: Double = 5.0,
    restitution: Double = 0.7,
    compressionDamping: Double = 3.0,
    extensionDamping: Double = 0.2,
): BallBounceScenario {
    val store = ParticleStore()
    val groups = Groups()

    val ballId = store.create(position = Vector3(0.0, dropHeight, 0.0), radius = ScalarExpr.of(0.2))
    groups.add("ball", ballId)

    val gravity = UniformGravity("ball", Vector3(0.0, -9.8, 0.0))
    val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    val rule = ParticleColliderRule(
        group = "ball",
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
    )
}
