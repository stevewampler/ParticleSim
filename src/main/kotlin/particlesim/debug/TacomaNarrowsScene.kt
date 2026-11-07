package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.examples.ShapePlacement
import particlesim.examples.TACOMA_DECK_HEIGHT_ABOVE_WATER
import particlesim.examples.TACOMA_DT
import particlesim.examples.TACOMA_GUSTY_WIND
import particlesim.examples.TACOMA_SCENE_SEED_RATE
import particlesim.examples.TacomaNarrowsScenario
import particlesim.examples.TacomaSharedForces
import particlesim.examples.buildTacomaNarrows
import particlesim.examples.seedOneNodeTwist
import particlesim.expr.ExpressionParser
import particlesim.physics.Force
import particlesim.physics.TorsionalFlutter
import particlesim.render.ArrowSample
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.ColorRamp
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.NamedArrowSamples
import particlesim.render.SurfaceRenderer
import particlesim.render.TextureAssets

/**
 * §9.6 scene-library demo for [buildTacomaNarrows] - the 1940 Tacoma Narrows Bridge at 1 unit =
 * 10 m, in its failure-day wind speed (Froude-scaled) with a 0.74 Hz gust by default, plus the
 * [particlesim.physics.TorsionalFlutter] self-excited deck moment. See that function's doc
 * comment for the sources, dimensions and scaling. The bridge itself is the reusable
 * [Tacoma1940Bridge] component; this is one of it with a camera and lights.
 *
 * The wind is a live-editable expression in the `wind` force's panel, and it drives the
 * `flutter` force too. The one-node (collapse-day) twist the scene starts with grows into a
 * limit cycle at the failure wind; `[3, 0, 0]` is below the flutter speed, so it dies away.
 * `tacomaNarrowsSteady` is the same scene in a steady failure-day wind, without the gust's
 * forced whole-span twist on top. The `flutter` force's arrows (shown by default) draw its
 * twisting push on each slice: magenta where it's feeding the twist, cyan where it's damping it.
 * `a2Slope` 0 in its panel switches flutter off.
 *
 * With `breakable` (the `tacomaNarrowsBreakable` scene, steady failure wind) the members break at
 * the 1941 report's real limits ([particlesim.examples.TacomaBreakage]) and pieces fall to the
 * water 195 ft below. At the documented 45° twist the model sits right at its limit: the first
 * quarter-point girders buckle after ~4 minutes and nothing else goes. Raise the `flutter`
 * panel's `saturationTwist` (e.g. 0.42, ~57°) and the whole cascade follows within ~2 1/2
 * minutes - girders buckling, hangers snapping and the main span falling away bay by bay. There's
 * no load crossing this one: the wind is the load.
 */
fun tacomaNarrowsScene(
    windVelocity: String = TACOMA_GUSTY_WIND,
    breakable: Boolean = false,
    showStress: Boolean = breakable,
): DemoScene {
    val store = ParticleStore()
    val groups = Groups()
    val bridge = Tacoma1940Bridge(store, groups, windVelocity = windVelocity, breakable = breakable, showStress = showStress)
    val water = TacomaWater(store, groups, y = -TACOMA_DECK_HEIGHT_ABOVE_WATER)
    // Close in on the west half of the main span, looking along the deck from just above and
    // outside it, so a few degrees of twist read clearly - a plain overview of the bounding box
    // would make a 1:72 deck a hairline. Orbit/zoom out for the whole bridge.
    val camera = CameraFunction { _, _ -> CameraPose(position = Vector3(5.0, 3.0, -42.0), lookAt = Vector3(0.0, 0.0, -18.0)) }
    return ComponentScene(store, groups, listOf(water, bridge), TACOMA_DT, camera, tacomaLights())
}

/** Seconds a breakable Tacoma bridge settles in still air before its scene starts: the freshly
 * built model rings for several seconds (hanger tension briefly ~2x its dead load). */
internal const val TACOMA_BREAKABLE_SETTLE_SECONDS = 8.0

/** Scene time at which a breakable Tacoma bridge's limits are armed - a few seconds in, so the
 * starting twist's own sudden kick isn't what breaks anything. */
internal const val TACOMA_ARM_AFTER_SECONDS = 5.0

/** Soft sky fill and a low sun, for both Tacoma Narrows bridges. */
fun tacomaLights(): List<Light> = listOf(
    Light.Ambient(color = Color(0.6, 0.65, 0.75), intensity = 0.45, name = "ambient-fill"),
    Light.Directional(position = Vector3(30.0, 40.0, -20.0), color = Color(1.0, 0.97, 0.9), intensity = 0.8, name = "sun"),
)

/**
 * The 1940 Tacoma Narrows Bridge ([buildTacomaNarrows]) as a reusable [SceneComponent]: built
 * into [store]/[groups] at [placement], drawn with its real members' scaled sizes and a textured
 * concrete roadway, with the flutter force's arrows, and - when [breakable] - its real breaking
 * limits armed a few seconds in, broken members dropped from the drawing and pieces landing in
 * the water. Starts with a small one-node twist, as on the day.
 */
class Tacoma1940Bridge(
    store: ParticleStore,
    groups: Groups,
    placement: ShapePlacement = ShapePlacement(),
    private val windVelocity: String = TACOMA_GUSTY_WIND,
    /** Real breaking limits ([particlesim.examples.TacomaBreakage]) and the water below. */
    private val breakable: Boolean = false,
    /** Color every breakable member - hangers, cable segments and (drawn only then) the girder
     * chords - by how close it is to its real breaking limit
     * ([particlesim.examples.TacomaBreakage.utilization]), green unstressed, yellow halfway, red
     * at breaking, with the viewer's break-proximity legend. */
    private val showStress: Boolean = breakable,
    /** Wind, gravity and flutter shared with other bridges - then owned and drawn by a
     * [TacomaSharedForcesComponent], not this one. */
    private val shared: TacomaSharedForces? = null,
) : SceneComponent {
    val scenario: TacomaNarrowsScenario = buildTacomaNarrows(
        windVelocity = shared?.windVelocity ?: windVelocity, breakable = breakable, shared = shared,
        store = store, groups = groups, placement = placement,
    )
    private val instanceName = placement.instanceName

    override val forces: List<Force> = scenario.forces
    override val constraints = scenario.constraints
    override val surfaces = listOf(scenario.deckSurface, scenario.girderSurface)

    // The freshly built model rings for several seconds (hanger tension briefly ~2x its dead
    // load) - a breakable one settles in still air before anything can break.
    override val settleSeconds = if (breakable) TACOMA_BREAKABLE_SETTLE_SECONDS else 0.0
    // (A shared wind is calmed by its own component.)
    override fun beginSettle() { if (shared == null) scenario.wind.setVelocity(VectorExpr.of(Vector3.ZERO)) }
    override fun endSettle() { if (shared == null) scenario.wind.setVelocity(ExpressionParser.parseVector(windVelocity)) }

    // The model is symmetric, so the collapse-day mode needs a disturbance to grow from (on the
    // day, a slipped cable band at mid-span).
    override fun start() = scenario.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)

    override fun beforeStep(t: Double) {
        // Armed a few seconds in, so the seed's own sudden kick isn't what breaks anything.
        if (breakable && !scenario.breakage.armed && t >= TACOMA_ARM_AFTER_SECONDS) scenario.breakage.arm()
    }

    private val ownForces = forces.toHashSet()

    override fun afterStep(t: Double, dt: Double, brokenForces: List<Force>): List<SimEvent> {
        val events = scenario.breakage.afterStep(brokenForces.filter { it in ownForces })
        scenario.collisions?.resolve(scenario.store, scenario.groups, t, dt)
        return events.map { SimEvent.ForceBreak(if (instanceName == null) it else "$instanceName: $it") }
    }

    private val roadwayMesh = SurfaceRenderer(
        scenario.deckSurface, wireframe = false, textureName = TextureAssets.TACOMA_1940_ROADWAY,
        material = Material(color = Material.UNTINTED, roughness = 0.9),
    )
    private val girderMesh = SurfaceRenderer(
        scenario.girderSurface, wireframe = false,
        material = Material(color = GIRDER_GREEN, roughness = 0.55),
    )

    // Lit solid shapes (§10.2 connection styles), at the real members' scaled sizes: ~0.44 m
    // diameter main cables, slender hangers, massive steel towers. The deck lattice itself isn't
    // drawn - the roadway and girder surfaces show it.
    private val cableConnections = scenario.cables.flatMap { it.zipWithNext() }
    private val towerConnections = scenario.towerLegs.flatMap { it.zipWithNext() } + scenario.towerBeamConnections
    private val structureConnections = cableConnections + scenario.hangerConnections + towerConnections
    // With stress colors on, the hangers are drawn thicker so their color reads, and the girder
    // chords (otherwise only implied by the girder panels) are drawn too, standing well proud of
    // the panels' edges so their color reads.
    private val chordConnections = if (!showStress) emptyList() else {
        val chordPairs = HashSet<Pair<Int, Int>>()
        for (span in scenario.spans) for ((a, b) in span.zipWithNext()) {
            chordPairs += listOf(a.topLeft to b.topLeft, a.bottomLeft to b.bottomLeft, a.topRight to b.topRight, a.bottomRight to b.bottomRight)
        }
        chordPairs.toList()
    }
    private val styleName = { local: String -> placement.name(local) }
    private val connectionStyles =
        (cableConnections styledAs ConnectionStyles.steel(styleName("main-cables"), radius = 0.04)) +
            (scenario.hangerConnections styledAs ConnectionStyles.cable(styleName("hangers"), radius = if (showStress) 0.018 else 0.01)) +
            (towerConnections styledAs ConnectionStyles.paintedSteel(styleName("towers"), TOWER_GREY, radius = 0.18)) +
            (chordConnections styledAs ConnectionStyles.paintedSteel(styleName("girder-chords"), GIRDER_GREEN, radius = 0.05, joints = false))

    private val deckIds = scenario.spans.flatten().flatMap { it.all }
    private val nonDeckIds = scenario.cables.flatten().distinct() + scenario.towerLegs.flatten()
    override val ids = (deckIds + nonDeckIds).distinct()
    override val visibleIds = nonDeckIds.toSet()

    // The flutter force drawn as its couple on each main-span slice: an arrow up on one girder
    // and down on the other, magenta where it's feeding energy into the twist (moment the same way
    // as the twist rate - negative aerodynamic damping) and cyan where it's taking it out.
    private fun flutterArrows(t: Double): List<NamedArrowSamples> = if (shared != null) emptyList() else tacomaFlutterArrows(scenario.flutter, scenario.store, t)

    // Broken hangers and cable segments stop being drawn; a torn-apart bay's roadway and girder
    // panels disappear (rebuilt only when something new has broken).
    private var drawnBreakCount = -1
    private var connections = structureConnections
    private var meshes = listOf(roadwayMesh, girderMesh)

    private fun refreshBrokenGeometry() {
        val breakage = scenario.breakage
        val count = breakage.brokenHangerCount + breakage.brokenCableCount + breakage.separatedBayCount
        if (count == drawnBreakCount) return
        drawnBreakCount = count
        val gone = (breakage.hangers + breakage.cables).filter { breakage.isBroken(it.spring) }.map { it.connection }.toSet()
        connections = structureConnections.filter { it !in gone }
        val hidden = breakage.hiddenTriangles
        meshes = if (hidden.isEmpty()) listOf(roadwayMesh, girderMesh) else listOf(roadwayMesh, girderMesh).map { mesh ->
            // copy() keeps the surface's name and texture coordinates.
            mesh.copy(surface = mesh.surface.copy(triangles = mesh.surface.triangles.filter { it !in hidden }))
        }
    }

    override fun frame(t: Double): ComponentFrame {
        refreshBrokenGeometry()
        // Unbroken hangers, cable segments and chords, each with its stress color; the towers
        // keep their plain style color.
        val stress = if (showStress) scenario.breakage.utilization(scenario.store) else emptyMap()
        return ComponentFrame(
            connections = if (showStress) towerConnections + stress.keys else connections,
            connectionStyles = connectionStyles,
            lineColors = stress.mapValues { (_, u) -> ColorRamp.greenYellowRed(u) },
            meshes = meshes,
            arrowGroups = flutterArrows(t),
            shownByDefaultArrows = if (shared != null) emptySet() else setOfNotNull(scenario.flutter?.name),
        )
    }

    /** Current meshes, for tests. */
    internal fun currentMeshes(): List<SurfaceRenderer> { refreshBrokenGeometry(); return meshes }

    private companion object {
        val GIRDER_GREEN = Color(0.42, 0.47, 0.44)
        val TOWER_GREY = Color(0.5, 0.55, 0.55)
    }
}

// Magenta/cyan - well clear of the green-yellow-red stress colors on the hangers and cables.
private val FLUTTER_FEEDING = Color(0.9, 0.2, 0.85)
private val FLUTTER_DAMPING = Color(0.1, 0.85, 0.95)

/** Arrow length per unit of couple force - a few deck widths at the 1940 limit cycle's peak. */
private const val FLUTTER_ARROW_SCALE = 2.0

/** A [TorsionalFlutter]'s couple on each slice as a pair of arrows, magenta where it's feeding
 * the twist and cyan where it's damping it - shared by both Tacoma bridges. */
internal fun tacomaFlutterArrows(flutter: TorsionalFlutter?, store: ParticleStore, t: Double): List<NamedArrowSamples> {
    if (flutter == null) return emptyList()
    val samples = flutter.couples(store, t).flatMap { c ->
        val color = if (c.feeding) FLUTTER_FEEDING else FLUTTER_DAMPING
        val v = c.force * FLUTTER_ARROW_SCALE
        listOf(ArrowSample(store.position(c.strip.right), v, color), ArrowSample(store.position(c.strip.left), v * -1.0, color))
    }
    return listOf(NamedArrowSamples(flutter.name ?: "", samples))
}
