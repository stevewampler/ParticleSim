package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.BRIDGE_DT
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
 * §9.6 scene-library demo for [buildSuspensionBridge] - see that function's own doc comment for
 * the tower/cable/suspender composition and the "suspenders aren't engineering-accurate
 * lengths" approximation. Shares [BRIDGE_DT] (the deck underneath is the exact same
 * [particlesim.examples.buildBridge] scenario, just unpinned) and the load re-launch cycle
 * with [BridgeScene].
 */
class SuspensionBridgeScene : DemoScene {
    private val scenario = buildSuspensionBridge()
    private val integrator = Integrator()
    private val cycleSeconds = 12.0
    // Held at its start position (re-pinned every step, same mechanism as the cycle-reset
    // check below, just unconditional until this elapses) rather than launched from t=0, like
    // BridgeScene's load is - the suspension structure's own initial settling (cables/deck
    // finding their loaded equilibrium from a flat start) moves fast enough in the first
    // second or so that the load could tunnel straight through a transiently fast-moving deck
    // triangle between one step and the next (this engine has no continuous collision
    // detection, §12.4's still-open TODO item) - caught live, not guessed at: an earlier
    // version launched immediately and the load fell through the deck and kept falling,
    // confirmed via the load's own live readout (centroid y around -45 and dropping) rather
    // than a blind guess from how the camera looked. Letting the bridge settle before anything
    // crosses it is also just a more sensible demo narrative than driving onto one still
    // mid-collapse.
    private val settleSeconds = 3.0
    private var cycleStart: Double? = null
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    private val deckMesh = SurfaceRenderer(
        scenario.deckSurface, wireframe = false,
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
        surfaces = listOf(scenario.deckSurface),
        groups = scenario.groups,
        lights = lights,
    )

    // Towers/cables/suspenders have no mesh of their own (unlike the deck) - rendered as plain
    // lines, same mechanism PoleRopeScene's pole+rope already use, just with more pieces
    // flattened together into one connections list.
    private val structureConnections = scenario.towerLegs.flatMap { it.zipWithNext() } +
        scenario.cableLeft.zipWithNext() + scenario.cableRight.zipWithNext() + scenario.suspenderConnections
    private val nonDeckIds = scenario.towerLegs.flatten() + scenario.cableLeft + scenario.cableRight + scenario.loadId
    private val allIds = scenario.deckGrid.flatten() + nonDeckIds
    // Deck particles are mesh-only (visibleIds excludes them, same convention
    // buildBridge/buildTrampoline's own scenes use) - towers/cables draw as their default dots
    // at each joint, the load gets its own sphere like BridgeScene's.
    private val sphereRadii = mapOf(scenario.loadId to 0.14)
    private val visibleIds = nonDeckIds.toSet()

    // A wider, higher offset than BridgeScene's own tracking camera - there's more to fit in
    // frame here (two towers rising above the deck, cables draped between them), not just the
    // deck and its load.
    private val camera = CameraFunction { _, s ->
        val loadPos = s.position(scenario.loadId)
        CameraPose(position = loadPos + Vector3(6.5, 3.2, -2.5), lookAt = loadPos)
    }

    override val dt = BRIDGE_DT
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
