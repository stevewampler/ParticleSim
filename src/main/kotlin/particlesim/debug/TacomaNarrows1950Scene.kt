package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.examples.ShapePlacement
import particlesim.examples.TACOMA_1950_DECK_HEIGHT_ABOVE_WATER
import particlesim.examples.TACOMA_DT
import particlesim.examples.TACOMA_GUSTY_WIND
import particlesim.examples.TACOMA_SCENE_SEED_RATE
import particlesim.examples.TacomaNarrows1950Scenario
import particlesim.examples.TacomaSharedForces
import particlesim.examples.buildTacomaNarrows1950
import particlesim.examples.seedOneNodeTwist
import particlesim.expr.ExpressionParser
import particlesim.physics.Force
import particlesim.physics.MeshSprings
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
 * breakable scene's conditions) the cables, suspenders, truss chords and diagonals are colored by
 * how close each is to its breaking limit ([TacomaNarrows1950Scenario.utilization]), green
 * unstressed, yellow halfway, red at breaking. With `breakable` too (`tacomaNarrows1950Breakable`)
 * they really break at those limits, as the 1940 breakable bridge's do: raise `gravity`, or the
 * wind well past the flutter speed, to see it.
 */
fun tacomaNarrows1950Scene(windVelocity: String = TACOMA_GUSTY_WIND, showStress: Boolean = false, breakable: Boolean = false): DemoScene {
    val store = ParticleStore()
    val groups = Groups()
    val bridge = Tacoma1950Bridge(store, groups, windVelocity = windVelocity, showStress = showStress || breakable, breakable = breakable)
    val water = TacomaWater(store, groups, y = -TACOMA_1950_DECK_HEIGHT_ABOVE_WATER)
    // The same close view along the west half of the main span as the 1940 scene, a little
    // further out for the wider, deeper deck.
    val camera = CameraFunction { _, _ -> CameraPose(position = Vector3(6.5, 3.0, -43.0), lookAt = Vector3(0.0, -0.5, -18.0)) }
    return ComponentScene(store, groups, listOf(water, bridge), TACOMA_DT, camera, tacomaLights())
}

/**
 * The 1950 Tacoma Narrows Bridge ([buildTacomaNarrows1950]) as a reusable [SceneComponent]:
 * built into [store]/[groups] at [placement], with its open Warren trusses drawn member by
 * member in their green paint, double-X-braced steel towers, a textured four-lane roadway, and
 * the flutter force's arrows - and, when [breakable], its breaking limits armed a few seconds in,
 * broken members dropped from the drawing and pieces landing in the water, as [Tacoma1940Bridge]
 * does. Starts with the same small one-node twist as [Tacoma1940Bridge].
 */
class Tacoma1950Bridge(
    store: ParticleStore,
    groups: Groups,
    placement: ShapePlacement = ShapePlacement(),
    private val windVelocity: String = TACOMA_GUSTY_WIND,
    /** Color the cables, suspenders, truss chords and diagonals by how close each is to its
     * breaking limit ([TacomaNarrows1950Scenario.utilization]), as [Tacoma1940Bridge] does for
     * its breakable members, with the viewer's break-proximity legend. */
    private val showStress: Boolean = false,
    /** Wind, gravity and flutter shared with other bridges - then owned and drawn by a
     * [TacomaSharedForcesComponent], not this one. */
    private val shared: TacomaSharedForces? = null,
    /** Real breaking limits ([particlesim.examples.TacomaBreakage]) and the water below. */
    private val breakable: Boolean = false,
) : SceneComponent {
    val scenario: TacomaNarrows1950Scenario = buildTacomaNarrows1950(
        windVelocity = shared?.windVelocity ?: windVelocity, breakable = breakable, shared = shared,
        store = store, groups = groups, placement = placement,
    )
    private val instanceName = placement.instanceName

    override val forces: List<Force> = scenario.forces
    override val constraints = scenario.constraints
    override val surfaces = listOf(scenario.roadwaySurface)

    // Settles in still air before anything can break, as the 1940 bridge does.
    override val settleSeconds = if (breakable) TACOMA_BREAKABLE_SETTLE_SECONDS else 0.0
    // (A shared wind is calmed by its own component.)
    override fun beginSettle() { if (shared == null) scenario.wind.setVelocity(VectorExpr.of(Vector3.ZERO)) }
    override fun endSettle() { if (shared == null) scenario.wind.setVelocity(ExpressionParser.parseVector(windVelocity)) }

    override fun start() = scenario.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)

    override fun beforeStep(t: Double) {
        if (breakable && !scenario.breakage.armed && t >= TACOMA_ARM_AFTER_SECONDS) scenario.breakage.arm()
    }

    private val ownForces = forces.toHashSet()

    override fun afterStep(t: Double, dt: Double, brokenForces: List<Force>): List<SimEvent> {
        val events = scenario.breakage.afterStep(brokenForces.filter { it in ownForces })
        scenario.collisions?.resolve(scenario.store, scenario.groups, t, dt)
        return events.map { SimEvent.ForceBreak(if (instanceName == null) it else "$instanceName: $it") }
    }

    private val roadwayMesh = SurfaceRenderer(
        scenario.roadwaySurface, wireframe = false, textureName = TextureAssets.TACOMA_1950_ROADWAY,
        material = Material(color = Material.UNTINTED, roughness = 0.9),
    )

    private val cableConnections = scenario.cables.flatMap { it.zipWithNext() }
    private val towerConnections = scenario.towerLegs.flatMap { it.zipWithNext() } + scenario.towerBracing
    private val diagonals = scenario.trussDiagonals.toHashSet()
    private val verticals = scenario.trussWebs.filter { it !in diagonals }
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
            (scenario.trussWebs styledAs ConnectionStyles.paintedSteel(name("truss-webs"), BRIDGE_GREEN, radius = if (showStress) 0.025 else 0.02, joints = false)) +
            (scenario.bottomBracing styledAs ConnectionStyles.paintedSteel(name("bottom-bracing"), BRACING_GREEN, radius = 0.012, joints = false))

    private val deckIds = scenario.spans.flatten().flatMap { it.all }
    private val nonDeckIds = scenario.cables.flatten().distinct() + scenario.towerLegs.flatten()
    override val ids = (deckIds + nonDeckIds).distinct()
    override val visibleIds = nonDeckIds.toSet()

    // Members with a breaking limit (cables, suspenders, chords, diagonals) are drawn while
    // unbroken - exactly the keys of the stress map; the unrated ones (verticals, bracing) while
    // their spring is - a torn-apart bay's span-wise bracing goes with it. The towers always stand.
    private val unratedDeckConnections = verticals + scenario.bottomBracing

    private fun MeshSprings.draws(connection: Pair<Int, Int>): Boolean {
        val edge = edgeIndexOf(connection.first, connection.second).takeIf { it >= 0 } ?: edgeIndexOf(connection.second, connection.first)
        return edge < 0 || isActive(edge)
    }

    override fun frame(t: Double): ComponentFrame {
        // Every unbroken rated member, with its stress (also what says which are unbroken).
        val stress = scenario.utilization()
        val unrated = towerConnections + unratedDeckConnections.filter { scenario.deckStructure.draws(it) }
        val hidden = scenario.breakage.hiddenTriangles
        val roadway = if (hidden.isEmpty()) roadwayMesh
        else roadwayMesh.copy(surface = roadwayMesh.surface.copy(triangles = roadwayMesh.surface.triangles.filter { it !in hidden }))
        return ComponentFrame(
            connections = unrated + stress.keys,
            connectionStyles = connectionStyles,
            lineColors = if (showStress) stress.mapValues { (_, u) -> ColorRamp.greenYellowRed(u) } else emptyMap(),
            meshes = listOf(roadway),
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
