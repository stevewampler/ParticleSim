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
 * rigid-arch/hanger composition. Shares the settle-before-launch pattern
 * [SuspensionBridgeScene]/[TrainTrestleScene] use and the single-load re-launch cycle
 * [BridgeScene]/[SuspensionBridgeScene] use (not [TrainTrestleScene]'s multi-car train - one
 * demo already covers a coupled train crossing a trestle; this one's own new ground is the
 * arch, not a second train).
 */
class ArchBridgeScene : DemoScene {
    private val scenario = buildArchBridge()
    private val integrator = Integrator()
    private val settleSeconds = 1.0 // everything but the deck is rigid by construction - nothing to settle
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

    // Each side's two chords, plus a diagonal zigzag lattice between them (upper[i]-lower[i+1]
    // and lower[i]-upper[i+1] for every rung) - the same "superstructure" rendering-only
    // treatment TrainTrestleScene's own bent legs use, just curved. Hangers and pylon legs are
    // plain line segments too.
    private val archConnections = scenario.archSides.flatMap { (upper, lower) ->
        val lattice = (0 until minOf(upper.size, lower.size) - 1).flatMap { i ->
            listOf(upper[i] to lower[i + 1], lower[i] to upper[i + 1])
        }
        upper.zipWithNext() + lower.zipWithNext() + lattice
    }
    private val pylonConnections = scenario.pylonLegs.flatMap { it.zipWithNext() }
    private val structureConnections = archConnections + pylonConnections + scenario.hangerConnections

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
