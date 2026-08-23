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
 *
 * Playback controls (pause/speed/step-once) via [ViewerInput] — every debug demo gets these
 * the same way now, see that class's own doc comment for why. Pausing freezes emission and
 * destruction along with physics, since both live inside the same `stepsThisFrame` loop.
 *
 * §9.1's discrete-event channel, first live consumer: this demo constantly spawns and destroys
 * particles (a spark's lifetime always ends it, §14.2), so unlike every other demo it produces
 * [SimEvent]s continuously with no interaction needed — the `events` list is rebuilt fresh each
 * frame (never accumulated across frames) so a paused frame, which runs zero steps, correctly
 * reports zero events instead of replaying the same ones every broadcast.
 */
fun main() {
    val scenario = buildSparks()

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / SPARKS_DT).toInt())

    var t = 0.0
    var step = 0L
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        val events = mutableListOf<SimEvent>()
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, SPARKS_DT)
            val destroyed = scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, SPARKS_DT)
            for (id in destroyed.destroyedIds) events += SimEvent.ParticleDestroyed(id)
            val emitted = scenario.emitter.update(scenario.store, scenario.groups, t, SPARKS_DT)
            for (id in emitted.spawnedIds) events += SimEvent.ParticleSpawned(id)
            for (id in emitted.evictedIds) events += SimEvent.ParticleDestroyed(id)
            t += SPARKS_DT
            step++
        }
        renderer.broadcast(t, step, scenario.store, scenario.store.liveIds(), emptyList(), events = events)
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
