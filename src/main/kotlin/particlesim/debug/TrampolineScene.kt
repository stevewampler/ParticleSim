package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.TRAMPOLINE_DT
import particlesim.examples.buildTrampoline
import particlesim.physics.Integrator
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer

/**
 * §9.6 scene-library wrapping of [TrampolineDebugDemo]'s worked example - see that file's own
 * doc comment for the re-drop cycle and the rim/ball `sphereRadii` override's reasoning
 * (unchanged here).
 */
class TrampolineScene : DemoScene {
    private val scenario = buildTrampoline()
    private val dropPosition = scenario.store.position(scenario.ballId)
    private val cycleSeconds = 10.0
    private val integrator = Integrator()
    private var cycleStart = 0.0

    private val matMesh = SurfaceRenderer(scenario.surface, wireframe = false)
    private val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.surface),
        groups = scenario.groups,
    )
    private val allIds = scenario.grid.flatten() + scenario.ballId
    private val rimIds = scenario.groups.membersOf("rim")
    private val sphereRadii = rimIds.associateWith { 0.03 } + (scenario.ballId to 0.12)
    private val visibleIds = rimIds + scenario.ballId

    override val dt = TRAMPOLINE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun step(t: Double) {
        if (t - cycleStart >= cycleSeconds) {
            scenario.store.setPosition(scenario.ballId, dropPosition)
            scenario.store.setVelocity(scenario.ballId, Vector3.ZERO)
            cycleStart = t
        }
        integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, scenario.constraints)) return
        if (message is SceneControlMessage.SetGroupEnabled) scenario.groups.setEnabled(message.name, message.enabled)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        sphereRadii = sphereRadii,
        meshes = listOf(matMesh),
        visibleIds = visibleIds,
        registry = registry,
    )
}
