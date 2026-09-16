package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.examples.FIRE_DT
import particlesim.examples.buildFire
import particlesim.physics.Integrator
import particlesim.render.Color
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library wrapping of [buildFire]'s worked example - same destroy-before-emit
 * ordering as [SparksScene] (see its own doc comment for why), and the same "events accumulate
 * across [step] calls within one frame, drained by [frame]" pattern.
 *
 * Colors every live particle yellow-to-red by its own age fraction (`(t - spawnTime) /
 * lifetime`, §14.2) via [BinaryFrame]'s per-particle color section - representational, not a
 * scalar-encoding ramp like [particlesim.render.ColorRamp.blueOrange], so it's kept local here
 * rather than added to that shared object.
 */
class FireScene : DemoScene {
    private val youngColor = Color(1.0, 0.95, 0.55) // pale yellow - just spawned
    private val oldColor = Color(0.75, 0.08, 0.02) // deep red - about to expire

    private fun colorForAge(ageFraction: Double): Color {
        val f = ageFraction.coerceIn(0.0, 1.0)
        return Color(
            youngColor.r + (oldColor.r - youngColor.r) * f,
            youngColor.g + (oldColor.g - youngColor.g) * f,
            youngColor.b + (oldColor.b - youngColor.b) * f,
        )
    }

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
        val particleColors = scenario.groups.membersOf("fire").associateWith { id ->
            val lifetime = scenario.store.lifetime(id)
            val ageFraction = if (lifetime != null && lifetime > 0.0) {
                (t - scenario.store.spawnTime(id)) / lifetime
            } else {
                0.0
            }
            colorForAge(ageFraction)
        }
        val frame = SceneFrame(
            registry = SceneRegistry.build(
                forces = scenario.forces, groups = scenario.groups,
                emitters = listOf(scenario.emitter),
            ),
            particleColors = particleColors,
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
