package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.CABLE_STAYED_BRIDGE_DT
import particlesim.examples.buildCableStayedBridge
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
 * §9.6 scene-library demo for [buildCableStayedBridge] - a single red H-tower with fanned
 * stays, after the "Bridge Road Lowpoly" Sketchfab model (see that function's doc comment for
 * the credit). Same settle-then-launch single-load cycle as [ArchBridgeScene].
 */
class CableStayedBridgeScene : DemoScene {
    internal val scenario = buildCableStayedBridge()
    private val integrator = Integrator()
    private val settleSeconds = 2.0
    private val cycleSeconds = 10.0
    private var cycleStart: Double? = null
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    private val deckMesh = SurfaceRenderer(
        scenario.deckSurface, wireframe = false,
        material = Material(color = Color(0.22, 0.22, 0.24), roughness = 0.8),
    )
    private val lights = listOf(
        Light.Ambient(color = Color(0.6, 0.65, 0.75), intensity = 0.4, name = "ambient-fill"),
        Light.Directional(
            position = Vector3(5.0, 9.0, -3.0), color = Color(1.0, 0.97, 0.9), intensity = 0.8,
            name = "sun",
        ),
    )
    private val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.deckSurface),
        groups = scenario.groups,
        lights = lights,
    )

    private val towerRed = Color(0.85, 0.12, 0.1)
    private val structureConnections = scenario.towerConnections + scenario.stayConnections + scenario.floorBeamConnections
    private val lineColors = (scenario.towerConnections + scenario.stayConnections).associateWith { towerRed }

    private val towerIds = scenario.towerLegs.flatten()
    private val nonDeckIds = towerIds + scenario.floorBeamIds + scenario.loadId
    private val allIds = scenario.deckGrid.flatten() + nonDeckIds
    private val sphereRadii = mapOf(scenario.loadId to 0.15)
    private val particleColors = towerIds.associateWith { towerRed }
    private val visibleIds = nonDeckIds.toSet()
    // Bridge structure particles start hidden (the "show particles" toggle on each group's panel
    // brings them back); only the load stays visible by default.
    private val hiddenByDefaultGroups = groupsHiddenByDefaultExcept(scenario.groups, listOf(scenario.loadId))

    // A fixed overview of the whole structure as built - deliberately not following the load
    // (orbit/pan/zoom with the mouse to look around).
    private val overview = overviewCameraPose(scenario.store, allIds)
    private val camera = CameraFunction { _, _ -> overview }

    override val dt = CABLE_STAYED_BRIDGE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun step(t: Double) {
        if (t < settleSeconds) {
            scenario.store.setPosition(scenario.loadId, scenario.loadStart)
            scenario.store.setVelocity(scenario.loadId, Vector3.ZERO)
        } else if (cycleStart == null || t - cycleStart!! >= cycleSeconds) {
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
        connections = structureConnections,
        lineColors = lineColors,
        sphereRadii = sphereRadii,
        particleColors = particleColors,
        meshes = listOf(deckMesh),
        visibleIds = visibleIds,
        hiddenByDefaultGroups = hiddenByDefaultGroups,
        registry = registry,
        lights = lights,
    )
}
