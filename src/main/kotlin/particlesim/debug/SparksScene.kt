package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.examples.SPARKS_DT
import particlesim.examples.buildSparks
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library wrapping of [SparksDebugDemo]'s worked example - integrate, then destroy
 * before emit (see that file's own doc comment for why). [events] accumulates across every
 * [step] call within one frame and is drained by [frame], the same "rebuilt fresh, never
 * carried across frames" behavior `SparksDebugDemo`'s own inline loop already had, just moved
 * from a local `val events` re-declared each frame to an instance field cleared on each read.
 */
class SparksScene : DemoScene {
    private val scenario = buildSparks()
    private val integrator = Integrator()
    private val events = mutableListOf<SimEvent>()

    override val dt = SPARKS_DT
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
                emitters = listOf(scenario.emitter), colliders = listOf(scenario.floor),
            ),
            colliders = listOf(scenario.floor),
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
