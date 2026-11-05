package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.TACOMA_DT
import particlesim.examples.buildTacomaNarrows
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
 * §9.6 scene-library demo for [buildTacomaNarrows] - the 1940 Tacoma Narrows Bridge at 1 unit =
 * 10 m, in a steady crosswind of its failure-day speed (Froude-scaled). See that function's doc
 * comment for the sources, dimensions and scaling.
 *
 * The wind is a live-editable expression in the `wind` force's panel - e.g. a steady
 * `[12, 0, 0]`, or a periodic gust tuned to the symmetric torsion mode,
 * `[5.9 + 2.0*sin(t*4.65), 0, 0]`. There's no load crossing this one: the wind is the load.
 */
class TacomaNarrowsScene : DemoScene {
    internal val scenario = buildTacomaNarrows()
    private val integrator = Integrator()
    private val scene = SceneQueryImpl(scenario.store, scenario.groups)

    private val roadwayMesh = SurfaceRenderer(
        scenario.deckSurface, wireframe = false,
        material = Material(color = Color(0.28, 0.28, 0.3), roughness = 0.85),
    )
    private val girderMesh = SurfaceRenderer(
        scenario.girderSurface, wireframe = false,
        material = Material(color = Color(0.42, 0.47, 0.44), roughness = 0.55),
    )
    private val lights = listOf(
        Light.Ambient(color = Color(0.6, 0.65, 0.75), intensity = 0.45, name = "ambient-fill"),
        Light.Directional(position = Vector3(30.0, 40.0, -20.0), color = Color(1.0, 0.97, 0.9), intensity = 0.8, name = "sun"),
    )
    private val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.deckSurface, scenario.girderSurface),
        groups = scenario.groups,
        lights = lights,
    )

    // Lit solid shapes (§10.2 connection styles), at the real members' scaled sizes: ~0.44 m
    // diameter main cables, slender hangers, massive steel towers. The deck lattice itself isn't
    // drawn - the roadway and girder surfaces show it.
    private val cableConnections = scenario.cables.flatMap { it.zipWithNext() }
    private val towerConnections = scenario.towerLegs.flatMap { it.zipWithNext() } + scenario.towerBeamConnections
    private val structureConnections = cableConnections + scenario.hangerConnections + towerConnections
    private val towerGrey = Color(0.5, 0.55, 0.55)
    private val connectionStyles =
        (cableConnections styledAs ConnectionStyles.steel("main-cables", radius = 0.04)) +
            (scenario.hangerConnections styledAs ConnectionStyles.cable("hangers", radius = 0.01)) +
            (towerConnections styledAs ConnectionStyles.paintedSteel("towers", towerGrey, radius = 0.18))

    private val deckIds = scenario.spans.flatten().flatMap { it.all }
    private val nonDeckIds = scenario.cables.flatten().distinct() + scenario.towerLegs.flatten()
    private val allIds = (deckIds + nonDeckIds).distinct()
    private val visibleIds = nonDeckIds.toSet()
    // Structure dots start hidden - the cylinders and surfaces show it ("show particles" on a
    // group brings them back).
    private val hiddenByDefaultGroups = groupsHiddenByDefaultExcept(scenario.groups, emptyList())

    // Close in on the west half of the main span, looking along the deck from just above and
    // outside it, so a few degrees of twist read clearly - a plain overview of the bounding box
    // would make a 1:72 deck a hairline. Orbit/zoom out for the whole bridge.
    private val camera = CameraFunction { _, _ ->
        CameraPose(position = Vector3(5.0, 3.0, -42.0), lookAt = Vector3(0.0, 0.0, -18.0))
    }

    override val dt = TACOMA_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun step(t: Double) {
        integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
    }

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, scenario.constraints, scenario.store, t, lights)) return
        if (message is SceneControlMessage.SetGroupEnabled) scenario.groups.setEnabled(message.name, message.enabled)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        camera = camera.evaluate(t, scene),
        connections = structureConnections,
        connectionStyles = connectionStyles,
        meshes = listOf(roadwayMesh, girderMesh),
        visibleIds = visibleIds,
        hiddenByDefaultGroups = hiddenByDefaultGroups,
        registry = registry,
        lights = lights,
    )
}
