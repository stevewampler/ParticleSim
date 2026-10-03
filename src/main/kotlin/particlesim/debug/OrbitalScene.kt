package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ORBITAL_DT
import particlesim.examples.buildOrbital
import particlesim.physics.Integrator
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import kotlin.math.cos
import kotlin.math.sin

/**
 * §9.6 scene-library demo for N-body gravity (§5.2): a star and three planets in roughly
 * circular orbits, built on [buildOrbital] — see that function's own doc comment for the
 * physics/approximation reasoning. The camera slowly circles the whole system from above so
 * the orbits read as orbits rather than being watched edge-on.
 */
class OrbitalScene : DemoScene {
    private val scenario = buildOrbital()
    private val integrator = Integrator()
    private val allIds = listOf(scenario.starId) + scenario.planetIds
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    // Purely cosmetic — buildOrbital hands back ids, not render hints, the same "physics
    // builder stays visual-free" split FireScene's own particle-color logic already follows.
    // The star renders larger and warm-colored; each planet gets a distinct, slightly larger
    // size and its own color outward, so three bodies at different orbital radii/speeds stay
    // visually distinguishable at a glance rather than reading as identical dots.
    private val sphereRadii = mapOf(scenario.starId to 0.6) +
        scenario.planetIds.mapIndexed { i, id -> id to 0.18 + i * 0.05 }
    private val planetColors = listOf(
        Color(0.55, 0.65, 0.95), // pale blue — innermost
        Color(0.85, 0.45, 0.25), // rust
        Color(0.45, 0.80, 0.50), // green — outermost
    )
    private val particleColors = mapOf(scenario.starId to Color(1.0, 0.8, 0.3)) +
        scenario.planetIds.mapIndexed { i, id -> id to planetColors[i % planetColors.size] }

    // Orbits in the XZ plane (§11's ground plane), so a camera parked directly overhead would
    // flatten them to a line - slowly circling at a steep elevation angle instead keeps the
    // whole system readable as it turns, the same "orbit a fixed point at a rotating offset"
    // shape FlagScene's own camera uses, just centered on the star/planets' own centroid
    // instead of a fixed lookAt.
    private val camera = CameraFunction { t, s ->
        val centroid = s.centroid("bodies")
        CameraPose(
            position = centroid + Vector3(sin(t * 0.05) * 22.0, 16.0, cos(t * 0.05) * 22.0),
            lookAt = centroid,
        )
    }
    private val registry = SceneRegistry.build(forces = scenario.forces, groups = scenario.groups)

    override val dt = ORBITAL_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun handleControl(message: SceneControlMessage, t: Double) {
        applyEditableFieldMessage(message, scenario.forces, emptyList(), scenario.store, t)
    }

    override fun step(t: Double) {
        integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, dt)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        camera = camera.evaluate(t, scene),
        sphereRadii = sphereRadii,
        particleColors = particleColors,
        registry = registry,
    )
}
