package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.examples.FLUID_DT
import particlesim.examples.buildFluid
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry

/**
 * §9.6 scene-library wrapping of [buildFluid]'s worked example - see that file's own doc
 * comment for the SPH/container design. No emitter or destruction here (unlike [FireScene]):
 * the particle count is fixed at construction, so `ids()` never needs to change frame to frame.
 */
class FluidScene : DemoScene {
    private val scenario = buildFluid()
    private val integrator = Integrator()
    private val ids = scenario.store.liveIds()
    private val registry = SceneRegistry.build(
        forces = scenario.forces, groups = scenario.groups, colliders = scenario.walls,
    )

    override val dt = FLUID_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = ids

    override fun handleControl(message: SceneControlMessage, t: Double) {
        applyEditableFieldMessage(message, scenario.forces, emptyList(), scenario.store, t)
    }

    override fun step(t: Double) {
        integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(registry = registry, colliders = scenario.walls)
}
