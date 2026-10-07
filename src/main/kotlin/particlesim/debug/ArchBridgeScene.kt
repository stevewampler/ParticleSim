package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ARCH_BRIDGE_DT
import particlesim.examples.buildArchBridge
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
 * §9.6 scene-library demo for [buildArchBridge] - see that function's own doc comment for the
 * dynamic-arch/cross-brace composition. Shares the settle-before-launch pattern
 * [SuspensionBridgeScene]/[TrainTrestleScene] use and the single-load re-launch cycle
 * [BridgeScene]/[SuspensionBridgeScene] use (not [TrainTrestleScene]'s multi-car train - one
 * demo already covers a coupled train crossing a trestle; this one's own new ground is the
 * arch, not a second train).
 */
class ArchBridgeScene : DemoScene {
    private val scenario = buildArchBridge()
    private val integrator = Integrator()
    // The deck itself is still rigid by construction - nothing to settle there - but the arch's
    // own spring network (new, see buildArchBridge's own doc comment on why its tuning is
    // deferred) genuinely does need a moment to find whatever shape it settles into before
    // anything starts crossing the deck underneath it.
    private val settleSeconds = 2.0
    private val cycleSeconds = 10.0
    private var cycleStart: Double? = null
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    private val deckMesh = SurfaceRenderer(
        scenario.deckSurface, wireframe = false,
        material = Material(color = Color(0.35, 0.37, 0.4), roughness = 0.4),
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

    // scenario.archConnections/hangerConnections already carry every real spring connection
    // (each side's own structural/shear/bend lattice, the left-right cross-bracing, and now the
    // hangers actually holding the deck up - see buildArchBridge's own doc comment) - rendering
    // those directly rather than re-deriving a lattice pattern here keeps this view honest about
    // what the physics actually connects, not just a visual approximation of it. Pylon legs are
    // the one remaining plain line segment (still not a spring - purely static, see
    // buildFlagpole).
    private val pylonConnections = scenario.pylonLegs.flatMap { it.zipWithNext() }
    private val structureConnections = scenario.archConnections + pylonConnections + scenario.hangerConnections

    private val nonDeckIds = scenario.pylonLegs.flatten() + scenario.archSides.flatMap { (u, l) -> u + l } + scenario.loadId
    private val allIds = scenario.deckGrid.flatten() + nonDeckIds
    private val sphereRadii = mapOf(scenario.loadId to 0.15)
    private val visibleIds = nonDeckIds.toSet()

    // Tracks the load from a fixed elevated offset, same "follow a particle's position" shape
    // every other bridge scene's own camera already uses - pulled back further than
    // TrainTrestleScene's own offset, since the arch itself rises well above deck height and
    // needs to stay in frame too, not just the load and the deck immediately around it.
    private val camera = CameraFunction { _, s ->
        val loadPos = s.position(scenario.loadId)
        CameraPose(position = loadPos + Vector3(7.0, 4.0, -3.0), lookAt = loadPos)
    }

    override val dt = ARCH_BRIDGE_DT
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
        sphereRadii = sphereRadii,
        meshes = listOf(deckMesh),
        visibleIds = visibleIds,
        registry = registry,
        lights = lights,
    )
}
