package particlesim.examples

import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.lifecycle.CollisionDestroyRule
import particlesim.lifecycle.DestroyCondition
import particlesim.lifecycle.DestructionSystem
import particlesim.lifecycle.Emitter
import particlesim.lifecycle.EmitterCapPolicy
import particlesim.lifecycle.ScalarDistribution
import particlesim.lifecycle.VectorDistribution
import particlesim.physics.Drag
import particlesim.physics.Force
import particlesim.physics.UniformGravity
import kotlin.math.abs
import kotlin.math.sin

/**
 * §14's particle-lifecycle worked example: a spark fountain. One [Emitter] sprays particles
 * upward in a cone (`PointWithSpread`, §14.1) at a rate that pulses over time rather than
 * staying constant, exercising the "expression-capable... bursts, ramps" language directly.
 * Sparks fall under gravity and drag, then leave the simulation one of two ways — whichever
 * comes first — exercising two of §14.2's three destroy mechanisms together: expiring after a
 * randomly-drawn lifetime, or disappearing on contact with the ground (§14.2's own example).
 * A third mechanism (an expression-condition bounding-region check) is a backstop for the
 * rare spark that flies out sideways fast enough to outrun the other two.
 */
data class SparksScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val emitter: Emitter,
    val destruction: DestructionSystem,
)

const val SPARKS_DT = 1e-3

fun buildSparks(masterSeed: Long = 1L): SparksScenario {
    val store = ParticleStore()
    val groups = Groups()

    val emitter = Emitter(
        name = "fountain",
        group = "sparks",
        rate = ScalarExpr.of { t -> 20.0 + 15.0 * sin(t * 0.5) },
        // Spawned well clear of the ground collider at y=0 (not centered on it) — a spark
        // needs a real flight arc before it's eligible to land, not a coin-flip on whether its
        // spawn position already overlaps the floor.
        position = VectorDistribution.UniformSphere(Vector3(0.0, 0.5, 0.0), 0.05),
        velocity = VectorDistribution.PointWithSpread(
            direction = Vector3(0.0, 1.0, 0.0),
            spreadAngleRadians = Math.toRadians(25.0),
            minMagnitude = 3.0,
            maxMagnitude = 6.0,
        ),
        mass = ScalarDistribution.Constant(0.05),
        radius = ScalarDistribution.Constant(0.03),
        lifetime = ScalarDistribution.UniformRange(1.0, 2.0),
        maxAlive = 300,
        capPolicy = EmitterCapPolicy.STOP,
        masterSeed = masterSeed,
    )

    val gravity = UniformGravity("sparks", Vector3(0.0, -9.8, 0.0))
    val drag = Drag("sparks", coefficient = 0.05)

    val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
    val destruction = DestructionSystem(
        destroyConditions = listOf(
            DestroyCondition("sparks") { s, id, _ ->
                val p = s.position(id)
                abs(p.x) > 10.0 || abs(p.z) > 10.0
            },
        ),
        collisionDestroyRules = listOf(CollisionDestroyRule("sparks", floor)),
    )

    return SparksScenario(store, groups, listOf(gravity, drag), emitter, destruction)
}
