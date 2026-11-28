package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.examples.MURMURATION_DT
import particlesim.examples.buildMurmuration
import particlesim.physics.Integrator
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.Material
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle
import kotlin.math.cos
import kotlin.math.sin

/**
 * §9.6 scene-library demo for [buildMurmuration]: a starling murmuration over its roost, with a
 * peregrine falcon making a pass every so often - see that function and
 * [particlesim.physics.Flocking] for the model and its sources. The camera slowly circles the
 * roost at head height, looking up at the flock against a dusk sky; the field is only for scale.
 *
 * Every steering weight is live-editable on the `flocking` force (try `neighbours`, or
 * `cohesionStrength`), and the falcon's chase on `pursuit`. Unchecking the `falcon` group's
 * "enabled" box calls the falcon off.
 *
 * Birds are drawn at [BIRD_DRAW_RADIUS], well over a starling's real size (about 20 cm long), so
 * they read as dots from 100 m off.
 */
class MurmurationScene(withFalcon: Boolean = true) : DemoScene {
    private val scenario = buildMurmuration(withFalcon = withFalcon)
    private val integrator = Integrator()
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    // The ground: a square field under the roost, on four particles nothing acts on.
    private val groundIds = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sz) ->
        scenario.store.create(position = Vector3(sx * GROUND_HALF_SIZE, -0.05, sz * GROUND_HALF_SIZE), mass = ScalarExpr.of(1.0))
            .also { scenario.groups.add("ground", it) }
    }
    private val ground = Surface(
        listOf(Triangle(groundIds[0], groundIds[2], groundIds[1]), Triangle(groundIds[0], groundIds[3], groundIds[2])),
        name = "ground",
    )
    private val groundMesh = SurfaceRenderer(ground, material = Material(color = Color(0.10, 0.13, 0.07), roughness = 1.0), pickable = false)

    private val birdIds = scenario.birdIds + listOfNotNull(scenario.falconId)
    private val sphereRadii = scenario.birdIds.associateWith { BIRD_DRAW_RADIUS } +
        listOfNotNull(scenario.falconId).associateWith { FALCON_DRAW_RADIUS }
    // Silhouettes against the dusk: near-black starlings, the larger falcon a dark slate.
    private val particleColors = scenario.birdIds.associateWith { STARLING_COLOR } +
        listOfNotNull(scenario.falconId).associateWith { FALCON_COLOR }

    // Standing in the field under the roost, as people watch them: a slowly circling viewpoint
    // at head height [CAMERA_DISTANCE] out from the roost, looking up at the flock.
    private val camera = CameraFunction { t, s ->
        val roost = scenario.roost
        CameraPose(
            position = Vector3(roost.x + sin(t * 0.02) * CAMERA_DISTANCE, 1.7, roost.z + cos(t * 0.02) * CAMERA_DISTANCE),
            lookAt = s.centroid(scenario.birdGroup),
        )
    }
    // Rebuilt each frame (it's tiny) so the falcon group's "enabled" box shows its real state.
    private fun registry() = SceneRegistry.build(forces = scenario.forces, groups = scenario.groups, surfaces = listOf(ground))

    override val dt = MURMURATION_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = birdIds + groundIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, emptyList(), scenario.store, t)) return
        if (message is SceneControlMessage.SetGroupEnabled) scenario.groups.setEnabled(message.name, message.enabled)
    }

    override fun step(t: Double) {
        integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        camera = camera.evaluate(t, scene),
        sphereRadii = sphereRadii,
        particleColors = particleColors,
        meshes = listOf(groundMesh),
        visibleIds = birdIds.toSet(),
        skyColor = DUSK_ZENITH,
        skyHorizonColor = DUSK_HORIZON,
        solidParticleColors = true,
        registry = registry(),
    )

    private companion object {
        const val BIRD_DRAW_RADIUS = 0.35
        const val FALCON_DRAW_RADIUS = 0.9
        const val GROUND_HALF_SIZE = 250.0
        const val CAMERA_DISTANCE = 130.0
        // Dusk after sunset: a warm glow along the horizon under a deepening blue overhead.
        val DUSK_ZENITH = Color(0.20, 0.22, 0.40)
        val DUSK_HORIZON = Color(0.88, 0.48, 0.26)
        val STARLING_COLOR = Color(0.0, 0.0, 0.01)
        val FALCON_COLOR = Color(0.22, 0.20, 0.22)
    }
}
