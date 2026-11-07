package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.ShapePlacement
import particlesim.examples.TACOMA_DT
import particlesim.examples.TACOMA_GUSTY_WIND
import particlesim.examples.TACOMA_SCENE_SEED_RATE
import particlesim.examples.TacomaNarrows1950Scenario
import particlesim.examples.TacomaSharedForces
import particlesim.examples.buildTacomaNarrows1950
import particlesim.examples.seedOneNodeTwist
import particlesim.physics.Force
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.ColorRamp
import particlesim.render.Material
import particlesim.render.SurfaceRenderer
import particlesim.render.TextureAssets

/**
 * §9.6 scene-library demo for [buildTacomaNarrows1950] - the 1950 replacement bridge, in the same
 * wind and with the same starting one-node twist as `tacomaNarrows`, so the two can be compared
 * scene to scene: here the twist dies away. See [buildTacomaNarrows1950] for the sources and the
 * model. The bridge itself is the reusable [Tacoma1950Bridge] component.
 *
 * The `wind` force's velocity is live-editable, as for 1940; this deck's flutter only sets in
 * above ~13 units/s (~94 mph at full scale), so try e.g. `[16, 0, 0]` to see it.
 *
 * With `showStress` (the `tacomaNarrows1950Stress` scene, steady failure wind - the 1940
 * breakable scene's conditions) the cables, suspenders and truss chords are colored by how close
 * each is to its breaking limit ([TacomaNarrows1950Scenario.utilization]), green unstressed,
 * yellow halfway, red at breaking. Nothing breaks.
 */
fun tacomaNarrows1950Scene(windVelocity: String = TACOMA_GUSTY_WIND, showStress: Boolean = false): DemoScene {
    val store = ParticleStore()
    val groups = Groups()
    val bridge = Tacoma1950Bridge(store, groups, windVelocity = windVelocity, showStress = showStress)
    // The same close view along the west half of the main span as the 1940 scene, a little
    // further out for the wider, deeper deck.
    val camera = CameraFunction { _, _ -> CameraPose(position = Vector3(6.5, 3.0, -43.0), lookAt = Vector3(0.0, -0.5, -18.0)) }
    return ComponentScene(store, groups, listOf(bridge), TACOMA_DT, camera, tacomaLights())
}

/**
 * The 1950 Tacoma Narrows Bridge ([buildTacomaNarrows1950]) as a reusable [SceneComponent]:
 * built into [store]/[groups] at [placement], with its open Warren trusses drawn member by
 * member in their green paint, double-X-braced steel towers, a textured four-lane roadway, and
 * the flutter force's arrows. Starts with the same small one-node twist as [Tacoma1940Bridge].
 */
class Tacoma1950Bridge(
    store: ParticleStore,
    groups: Groups,
    placement: ShapePlacement = ShapePlacement(),
    windVelocity: String = TACOMA_GUSTY_WIND,
    /** Color the cables, suspenders and truss chords by how close each is to its breaking limit
     * ([TacomaNarrows1950Scenario.utilization]), as [Tacoma1940Bridge] does for its breakable
     * members, with the viewer's break-proximity legend. */
    private val showStress: Boolean = false,
    /** Wind, gravity and flutter shared with other bridges - then owned and drawn by a
     * [TacomaSharedForcesComponent], not this one. */
    private val shared: TacomaSharedForces? = null,
) : SceneComponent {
    val scenario: TacomaNarrows1950Scenario = buildTacomaNarrows1950(
        windVelocity = shared?.windVelocity ?: windVelocity, shared = shared, store = store, groups = groups, placement = placement,
    )

    override val forces: List<Force> = scenario.forces
    override val constraints = scenario.constraints
    override val surfaces = listOf(scenario.roadwaySurface)

    override fun start() = scenario.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)

    private val roadwayMesh = SurfaceRenderer(
        scenario.roadwaySurface, wireframe = false, textureName = TextureAssets.TACOMA_1950_ROADWAY,
        material = Material(color = Material.UNTINTED, roughness = 0.9),
    )

    private val cableConnections = scenario.cables.flatMap { it.zipWithNext() }
    private val towerConnections = scenario.towerLegs.flatMap { it.zipWithNext() } + scenario.towerBracing
    private val connections = cableConnections + scenario.hangerConnections + towerConnections +
        scenario.trussChords + scenario.trussWebs + scenario.bottomBracing
    private val name = { local: String -> placement.name(local) }
    // The real members' sizes, scaled: 20¼ in main cables (drawn as thick, relative to the 1940
    // ones, as they were), heavy truss chords, lighter web members and bracing.
    private val connectionStyles =
        (cableConnections styledAs ConnectionStyles.steel(name("main-cables"), radius = 0.046)) +
            // Thicker with stress colors on, so their color reads.
            (scenario.hangerConnections styledAs ConnectionStyles.cable(name("hangers"), radius = if (showStress) 0.018 else 0.01)) +
            (scenario.towerLegs.flatMap { it.zipWithNext() } styledAs ConnectionStyles.paintedSteel(name("towers"), BRIDGE_GREEN, radius = 0.2)) +
            (scenario.towerBracing styledAs ConnectionStyles.paintedSteel(name("tower-bracing"), BRIDGE_GREEN, radius = 0.07, joints = false)) +
            (scenario.trussChords styledAs ConnectionStyles.paintedSteel(name("truss-chords"), BRIDGE_GREEN, radius = 0.035, joints = false)) +
            (scenario.trussWebs styledAs ConnectionStyles.paintedSteel(name("truss-webs"), BRIDGE_GREEN, radius = 0.02, joints = false)) +
            (scenario.bottomBracing styledAs ConnectionStyles.paintedSteel(name("bottom-bracing"), BRACING_GREEN, radius = 0.012, joints = false))

    private val deckIds = scenario.spans.flatten().flatMap { it.all }
    private val nonDeckIds = scenario.cables.flatten().distinct() + scenario.towerLegs.flatten()
    override val ids = (deckIds + nonDeckIds).distinct()
    override val visibleIds = nonDeckIds.toSet()

    // Drawn whatever the stress: the towers, truss webs and bracing (no limits on these).
    private val unratedConnections = towerConnections + scenario.trussWebs + scenario.bottomBracing

    override fun frame(t: Double): ComponentFrame {
        val stress = if (showStress) scenario.utilization() else emptyMap()
        return ComponentFrame(
            connections = if (showStress) unratedConnections + stress.keys else connections,
            connectionStyles = connectionStyles,
            lineColors = stress.mapValues { (_, u) -> ColorRamp.greenYellowRed(u) },
            meshes = listOf(roadwayMesh),
            arrowGroups = if (shared != null) emptyList() else tacomaFlutterArrows(scenario.flutter, scenario.store, t),
            shownByDefaultArrows = if (shared != null) emptySet() else setOfNotNull(scenario.flutter?.name),
        )
    }

    private companion object {
        /** The bridge's green paint (after photographs). */
        val BRIDGE_GREEN = Color(0.36, 0.55, 0.42)
        val BRACING_GREEN = Color(0.3, 0.45, 0.35)
    }
}
