package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ShapePlacement
import particlesim.examples.buildBallBounce
import particlesim.examples.buildFlag
import particlesim.examples.buildFlagpole
import particlesim.examples.buildTire
import particlesim.physics.Integrator

/**
 * §4.5's shape-library worked example: a flag planted on a flagpole, a tire, and a ball-bounce,
 * each an independently-built shape, sharing one scene — `./gradlew runMultiShapeDemo`, then
 * open the URL it prints. Proves the practical point of a shape library rather than just its
 * unit-tested mechanics (`ShapeCompositionTest`): this is the same `buildFlag`/`buildFlagpole`/
 * `buildTire`/`buildBallBounce` functions every single-shape demo already uses, just called
 * against one shared `store`/`groups` with different placements, and nothing about their own
 * internals had to know that.
 *
 * The flag and flagpole are placed so the flag's pole-edge (its own `FixedPosition` column,
 * independent of the flagpole's particles) starts right at the flagpole's top and hangs
 * downward alongside it — the two aren't physically connected, just visually lined up, exactly
 * the "compose by placement" pattern §4.5 describes.
 *
 * Kept deliberately simple visually (plain dots/lines, a static camera) — the point here is
 * composition, not re-proving Phase 9's renderer/camera work, which is already proven
 * elsewhere (`FlagDebugDemo`).
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()

    val poleHeight = 3.5
    val pole = buildFlagpole(
        height = poleHeight, store = store, groups = groups,
        placement = ShapePlacement(instanceName = "pole"),
    )
    val flag = buildFlag(
        rows = 8, cols = 14, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(0.0, poleHeight, 0.0), instanceName = "flag"),
    )
    val tire = buildTire(
        radius = 1.0, segments = 16, dropHeight = 3.0, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(3.0, 0.0, 0.0), instanceName = "tire"),
    )
    val ball = buildBallBounce(
        dropHeight = 4.0, store = store, groups = groups,
        placement = ShapePlacement(offset = Vector3(-2.5, 0.0, 1.5), instanceName = "ball"),
    )

    val allIds = pole.poleIds + flag.grid.flatten() + tire.rimIds + listOf(ball.ballId)
    val allForces = flag.forces + tire.forces + ball.forces
    val allConstraints = pole.constraints + flag.constraints
    fun connections() =
        pole.poleIds.zipWithNext() +
            flag.meshSprings[0].activeConnections() +
            tire.rimIds.indices.map { i -> tire.rimIds[i] to tire.rimIds[(i + 1) % tire.rimIds.size] }

    val renderer = DebugRenderer()
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3 // matches buildFlag's FLAG_DT, buildTire's TIRE_DT, and buildBallBounce's BALL_BOUNCE_DT
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())

    var t = 0.0
    var step = 0L
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            integrator.step(store, groups, allForces, allConstraints, t, dt)
            tire.collisions.resolve(store, groups, t, dt)
            ball.collisions.resolve(store, groups, t, dt)
            t += dt
            step++
        }
        renderer.broadcast(t, step, store, allIds, connections())
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
