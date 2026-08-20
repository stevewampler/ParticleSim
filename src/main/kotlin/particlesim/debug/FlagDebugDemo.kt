package particlesim.debug

import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import particlesim.physics.Integrator

/**
 * A visible run of §7.3's flag worked example through Phase 3's debug renderer: `./gradlew
 * runFlagDemo`, then open the URL it prints. Only structural-edge lines are drawn (not
 * shear/bend) — those are diagonal/skip-vertex connections that would clutter a debug
 * wireframe view without adding to its readability as "a flag".
 */
fun main() {
    val scenario = buildFlag(rows = 8, cols = 14)
    val structural = scenario.meshSprings[0]

    val renderer = DebugRenderer()
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / FLAG_DT).toInt())

    var t = 0.0
    val frameNanos = 1_000_000_000L / framesPerSecond
    val allIds = scenario.grid.flatten()
    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, FLAG_DT)
            t += FLAG_DT
        }
        renderer.broadcast(t, scenario.store, allIds, structural.activeConnections())
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
