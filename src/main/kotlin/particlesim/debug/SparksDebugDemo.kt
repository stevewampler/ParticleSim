package particlesim.debug

import particlesim.examples.SPARKS_DT
import particlesim.examples.buildSparks
import particlesim.physics.Integrator

/**
 * A visible run of §14's spark-fountain worked example: `./gradlew runSparksDemo`, then open
 * the URL it prints. Per step: integrate, then **destroy before emit** — a particle spawned
 * this step shouldn't be eligible for its own lifetime/collision check before it's ever been
 * integrated once (see [particlesim.lifecycle.DestructionSystem]'s doc comment). Unlike the
 * flag/ball-bounce demos, the particle set actually changes size frame to frame, so the ids
 * broadcast each frame come fresh from `store.liveIds()` rather than a list built once upfront.
 */
fun main() {
    val scenario = buildSparks()

    val renderer = DebugRenderer()
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / SPARKS_DT).toInt())

    var t = 0.0
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, SPARKS_DT)
            scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, SPARKS_DT)
            scenario.emitter.update(scenario.store, scenario.groups, t, SPARKS_DT)
            t += SPARKS_DT
        }
        renderer.broadcast(t, scenario.store, scenario.store.liveIds(), emptyList())
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
