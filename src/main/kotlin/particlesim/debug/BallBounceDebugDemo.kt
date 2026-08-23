package particlesim.debug

import particlesim.core.Vector3
import particlesim.examples.BALL_BOUNCE_DT
import particlesim.examples.buildBallBounce
import particlesim.physics.Integrator

/**
 * A visible run of §12.6's ball-bounce worked example: `./gradlew runBallBounceDemo`, then
 * open the URL it prints. Collision resolution runs as a separate call after each integrator
 * step, not a stage inside it (§12.4/§12.5) — [particlesim.collision.CollisionSystem] needs
 * colliders and per-contact state the integrator has no reason to know about.
 *
 * The ball settles to rest within a few seconds — easy to load the page after it's already
 * done bouncing and see nothing but a stationary dot. The drop re-triggers on a fixed cycle
 * (well past the settle time with margin) so there's always another bounce coming soon,
 * rather than requiring the viewer tab to be open at the exact moment `main` starts.
 *
 * Playback controls (pause/speed/step-once) via [ViewerInput] — every debug demo gets these
 * the same way now, see that class's own doc comment for why.
 */
fun main() {
    val scenario = buildBallBounce()
    val dropPosition = scenario.store.position(scenario.ballId)
    val cycleSeconds = 8.0

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / BALL_BOUNCE_DT).toInt())

    var t = 0.0
    var step = 0L
    var cycleStart = 0.0
    val frameNanos = 1_000_000_000L / framesPerSecond
    val ids = listOf(scenario.ballId)
    while (true) {
        val frameStart = System.nanoTime()
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
            if (t - cycleStart >= cycleSeconds) {
                scenario.store.setPosition(scenario.ballId, dropPosition)
                scenario.store.setVelocity(scenario.ballId, Vector3.ZERO)
                cycleStart = t
            }
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, BALL_BOUNCE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, BALL_BOUNCE_DT)
            t += BALL_BOUNCE_DT
            step++
        }
        renderer.broadcast(t, step, scenario.store, ids, emptyList())
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
