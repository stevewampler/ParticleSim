package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.examples.FIRE_DT
import particlesim.examples.buildFire
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library wrapping of [buildFire]'s worked example - same destroy-before-emit
 * ordering as [SparksScene] (see its own doc comment for why), and the same "events accumulate
 * across [step] calls within one frame, drained by [frame]" pattern.
 */
class FireScene : DemoScene {
    private val scenario = buildFire()
    private val integrator = Integrator()
    private val events = mutableListOf<SimEvent>()

    override val dt = FIRE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = scenario.store.liveIds()

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, emptyList(), scenario.store, t)) return
        applyEmitterMessage(message, listOf(scenario.emitter))
    }

    override fun step(t: Double) {
        integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, dt)
        val destroyed = scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, dt)
        for (id in destroyed.destroyedIds) events += SimEvent.ParticleDestroyed(id)
        val emitted = scenario.emitter.update(scenario.store, scenario.groups, t, dt)
        for (id in emitted.spawnedIds) events += SimEvent.ParticleSpawned(id)
        for (id in emitted.evictedIds) events += SimEvent.ParticleDestroyed(id)
    }

    override fun frame(t: Double): SceneFrame {
        val frame = SceneFrame(
            registry = SceneRegistry.build(
                forces = scenario.forces, groups = scenario.groups,
                emitters = listOf(scenario.emitter),
            ),
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
