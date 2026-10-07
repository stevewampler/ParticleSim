package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.TRAIN_TRESTLE_DT
import particlesim.examples.buildTrainTrestle
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
 * §9.6 scene-library demo for [buildTrainTrestle] - see that function's own doc comment for the
 * rigid-bent/coupled-train composition. Shares the settle-before-launch pattern
 * [SuspensionBridgeScene] uses (a stiff, densely-pinned deck settles far faster than a hung
 * one, but starting the train the instant the scene loads is still one avoidable source of a
 * first-frame collision surprise, not worth reintroducing just because this structure is
 * stiffer) and the re-launch cycle every bridge scene in this library uses.
 */
class TrainTrestleScene : DemoScene {
    private val scenario = buildTrainTrestle()
    private val integrator = Integrator()
    private val settleSeconds = 1.0 // the deck itself is nearly rigid - far less to settle than a hung cable system
    private val cycleSeconds = 10.0
    private var cycleStart: Double? = null
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    // Cool grey, low roughness (steel sheen) - no metalness channel exists on Material (§10.2's
    // model is color/roughness/opacity only), so the "steel" read comes from color+roughness
    // alone, not a PBR metalness workflow.
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

    // Every bent's two legs, plus an X-brace lattice between them (left[i]-right[i+1] and
    // right[i]-left[i+1] for each rung, same zigzag a real steel trestle's cross-bracing
    // follows) - the "superstructure for strength" look, purely a rendering choice since both
    // legs are already independently rigid (§4.5's buildFlagpole, FixedPosition throughout) and
    // have no physical need of a connecting brace to stay put.
    private val bentConnections = scenario.bents.flatMap { (left, right) ->
        val rungs = (0 until minOf(left.size, right.size) - 1).flatMap { i ->
            listOf(left[i] to right[i + 1], right[i] to left[i + 1])
        }
        left.zipWithNext() + right.zipWithNext() + rungs
    }
    private val trainCouplingConnections = scenario.trainIds.zipWithNext()
    private val structureConnections = bentConnections + trainCouplingConnections

    private val nonDeckIds = scenario.bents.flatMap { (l, r) -> l + r } + scenario.trainIds
    private val allIds = scenario.deckGrid.flatten() + nonDeckIds
    private val sphereRadii = scenario.trainIds.associateWith { 0.16 }
    private val visibleIds = nonDeckIds.toSet()

    // Tracks the lead car, same "follow a particle's position" shape BridgeScene/
    // SuspensionBridgeScene's own cameras already use - closer and lower than the suspension
    // bridge's own offset, since this structure doesn't tower as high.
    private val camera = CameraFunction { _, s ->
        val leadPos = s.position(scenario.trainIds.first())
        CameraPose(position = leadPos + Vector3(5.5, 2.6, -2.2), lookAt = leadPos)
    }

    override val dt = TRAIN_TRESTLE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun step(t: Double) {
        if (t < settleSeconds) {
            resetTrain()
        } else if (cycleStart == null || t - cycleStart!! >= cycleSeconds) {
            launchTrain()
            cycleStart = t
        }
        integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    private fun resetTrain() {
        scenario.trainIds.forEachIndexed { i, id ->
            scenario.store.setPosition(id, scenario.trainStarts[i])
            scenario.store.setVelocity(id, Vector3.ZERO)
        }
    }

    private fun launchTrain() {
        scenario.trainIds.forEachIndexed { i, id ->
            scenario.store.setPosition(id, scenario.trainStarts[i])
            scenario.store.setVelocity(id, scenario.trainStartVelocity)
        }
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
