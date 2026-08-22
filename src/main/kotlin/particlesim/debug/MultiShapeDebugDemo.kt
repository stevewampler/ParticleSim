package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ShapePlacement
import particlesim.examples.buildBallBounce
import particlesim.examples.buildFlag
import particlesim.physics.Integrator

/**
 * §4.5's shape-library worked example: two flags and a ball-bounce, each an independently-
 * built shape, sharing one scene — `./gradlew runMultiShapeDemo`, then open the URL it prints.
 * Proves the practical point of a shape library rather than just its unit-tested mechanics
 * (`ShapeCompositionTest`): this is the same `buildFlag`/`buildBallBounce` functions every
 * single-shape demo already uses, just called three times against one shared `store`/`groups`
 * with different placements, and nothing about their own internals had to know that.
 *
 * Kept deliberately simple visually (plain dots/lines, a static camera) — the point here is
 * composition, not re-proving Phase 9's renderer/camera work, which is already proven
 * elsewhere (`FlagDebugDemo`).
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()

    val flag1 = buildFlag(rows = 6, cols = 10, store = store, groups = groups, placement = ShapePlacement(instanceName = "flag1"))
    val flag2 = buildFlag(
        rows = 6, cols = 10, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(0.0, 0.0, 3.0), instanceName = "flag2"),
    )
    val ball = buildBallBounce(
        dropHeight = 4.0, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(3.0, 0.0, 1.5), instanceName = "ball"),
    )

    val allIds = flag1.grid.flatten() + flag2.grid.flatten() + listOf(ball.ballId)
    val allForces = flag1.forces + flag2.forces + ball.forces
    val allConstraints = flag1.constraints + flag2.constraints
    fun connections() = flag1.meshSprings[0].activeConnections() + flag2.meshSprings[0].activeConnections()

    val renderer = DebugRenderer()
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3 // matches both buildFlag's FLAG_DT and buildBallBounce's BALL_BOUNCE_DT
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())

    var t = 0.0
    var step = 0L
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            integrator.step(store, groups, allForces, allConstraints, t, dt)
            ball.collisions.resolve(store, groups, t, dt)
            t += dt
            step++
        }
        renderer.broadcast(t, step, store, allIds, connections())
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
