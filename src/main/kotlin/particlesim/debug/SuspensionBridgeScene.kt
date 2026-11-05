package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.SUSPENSION_BRIDGE_DT
import particlesim.examples.buildSuspensionBridge
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
 * §9.6 scene-library demo for [buildSuspensionBridge] - an old rope bridge (plank walkway on
 * sagging foot ropes, handrails, vertical ropes, wooden posts), after the "old ropebridge low
 * poly" Sketchfab model; see that function's doc comment for the credit.
 *
 * The load is held at its start for [settleSeconds] while the ropes take up their own small
 * stretch under gravity (the bridge is built already in its hanging shape, so this is short),
 * then launched across and re-launched every [cycleSeconds].
 */
class SuspensionBridgeScene : DemoScene {
    internal val scenario = buildSuspensionBridge()
    private val integrator = Integrator()
    private val settleSeconds = 2.0
    private val cycleSeconds = 10.0
    private var cycleStart: Double? = null
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    private val walkwayMesh = SurfaceRenderer(
        scenario.walkwaySurface, wireframe = false,
        material = Material(color = Color(0.45, 0.33, 0.22), roughness = 0.95),
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
        surfaces = listOf(scenario.walkwaySurface),
        groups = scenario.groups,
        lights = lights,
    )

    // Lit solid shapes (§10.2 connection styles): thick wooden posts, hemp foot ropes and
    // handrails (with joints, so their sag reads as one continuous rope), thinner vertical ropes.
    private val postConnections = scenario.posts.flatMap { it.zipWithNext() }
    private val mainRopeConnections = scenario.footRopes.flatMap { it.zipWithNext() } + scenario.handrails.flatMap { it.zipWithNext() }
    private val structureConnections = postConnections + mainRopeConnections + scenario.verticalConnections
    private val connectionStyles =
        (postConnections styledAs ConnectionStyles.wood("posts", radius = 0.06)) +
            (mainRopeConnections styledAs ConnectionStyles.rope("main-ropes", radius = 0.022)) +
            (scenario.verticalConnections styledAs ConnectionStyles.rope("vertical-ropes", radius = 0.012, joints = false))

    private val nonWalkwayIds = scenario.posts.flatten() + scenario.handrails.flatten().distinct() + scenario.loadId
    private val allIds = (scenario.walkway.flatten() + nonWalkwayIds).distinct()
    private val sphereRadii = mapOf(scenario.loadId to 0.14)
    // Walkway particles are mesh-only (the planks already show them).
    private val visibleIds = nonWalkwayIds.toSet()
    // Bridge structure particles start hidden (the "show particles" toggle on each group's panel
    // brings them back); only the load stays visible by default.
    private val hiddenByDefaultGroups = groupsHiddenByDefaultExcept(scenario.groups, listOf(scenario.loadId))

    // A fixed overview of the whole structure as built - deliberately not following the load
    // (orbit/pan/zoom with the mouse to look around).
    private val overview = overviewCameraPose(scenario.store, allIds)
    private val camera = CameraFunction { _, _ -> overview }

    override val dt = SUSPENSION_BRIDGE_DT
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
        connectionStyles = connectionStyles,
        sphereRadii = sphereRadii,
        meshes = listOf(walkwayMesh),
        visibleIds = visibleIds,
        hiddenByDefaultGroups = hiddenByDefaultGroups,
        registry = registry,
        lights = lights,
    )
}
