package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.BRIDGE_DT
import particlesim.examples.buildBridge
import particlesim.physics.Integrator
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer

/**
 * §9.6 scene-library demo for a deck under a traveling load - see [buildBridge]'s own doc
 * comment for the "pinned only at the two ends, not the whole rim" physics/approximation
 * reasoning. Re-launches the load back across the deck on a cycle once it's crossed (same
 * re-drop-cycle pattern [TrampolineScene]/[BallBounceScene] already use), rather than leaving
 * it sitting wherever it comes to rest forever.
 */
class BridgeScene : DemoScene {
    internal val scenario = buildBridge()
    private val integrator = Integrator()
    private val cycleSeconds = 12.0
    private var cycleStart = 0.0
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    // A fixed overview of the whole structure as built - deliberately not following the load
    // (orbit/pan/zoom with the mouse to look around).
    private val overview = overviewCameraPose(scenario.store, scenario.grid.flatten() + scenario.loadId)
    private val camera = CameraFunction { _, _ -> overview }

    // Weathered-plank brown, low roughness reserved for the trampoline's fabric sheen (§10.2) -
    // a deck should read as matte wood, not satin. Same "prove lights are configurable, don't
    // just inherit the viewer's own default rig" stance TrampolineScene already takes, with a
    // plainer two-light rig (a bridge has no single highlight-worthy point the way a trampoline's
    // bounce center does).
    private val deckMesh = SurfaceRenderer(
        scenario.surface, wireframe = false,
        material = Material(color = Color(0.42, 0.28, 0.16), roughness = 0.9),
    )
    private val lights = listOf(
        Light.Ambient(color = Color(0.6, 0.65, 0.75), intensity = 0.4, name = "ambient-fill"),
        Light.Directional(
            position = Vector3(5.0, 9.0, -3.0), color = Color(1.0, 0.97, 0.9), intensity = 0.75,
            name = "sun",
        ),
    )
    private val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.surface),
        groups = scenario.groups,
        lights = lights,
    )
    private val allIds = scenario.grid.flatten() + scenario.loadId
    private val abutmentIds = scenario.groups.membersOf("abutments")
    private val sphereRadii = abutmentIds.associateWith { 0.04 } + (scenario.loadId to 0.14)
    private val visibleIds = abutmentIds + scenario.loadId
    // Bridge structure particles start hidden (the "show particles" toggle on each group's panel
    // brings them back); only the load stays visible by default.
    private val hiddenByDefaultGroups = groupsHiddenByDefaultExcept(scenario.groups, listOf(scenario.loadId))

    override val dt = BRIDGE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun step(t: Double) {
        // Re-launches once per cycle, not just once the load drifts off the far end - a load
        // that settles mid-span (the realistic, intended outcome of low restitution + the
        // deck's own sag catching it) would otherwise just sit there forever with nothing left
        // to watch, the same reasoning TrampolineScene's own time-based cycle (not an
        // apex-detection one like BallBounceScene, which assumes the thing being cycled
        // actually returns to apex) already follows.
        if (t - cycleStart >= cycleSeconds) {
            scenario.store.setPosition(scenario.loadId, scenario.loadStart)
            scenario.store.setVelocity(scenario.loadId, scenario.loadStartVelocity)
            cycleStart = t
        }
        integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, scenario.constraints, scenario.store, t, lights)) return
        if (message is SceneControlMessage.SetGroupEnabled) scenario.groups.setEnabled(message.name, message.enabled)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        camera = camera.evaluate(t, scene),
        sphereRadii = sphereRadii,
        meshes = listOf(deckMesh),
        visibleIds = visibleIds,
        hiddenByDefaultGroups = hiddenByDefaultGroups,
        registry = registry,
        lights = lights,
    )
}
