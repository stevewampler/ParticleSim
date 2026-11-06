package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.TACOMA_DT
import particlesim.examples.TACOMA_GUSTY_WIND
import particlesim.examples.TACOMA_SCENE_SEED_RATE
import particlesim.examples.buildTacomaNarrows
import particlesim.examples.seedOneNodeTwist
import particlesim.physics.Integrator
import particlesim.render.ArrowSample
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.ColorRamp
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.core.VectorExpr
import particlesim.expr.ExpressionParser
import particlesim.surface.Surface

/**
 * §9.6 scene-library demo for [buildTacomaNarrows] - the 1940 Tacoma Narrows Bridge at 1 unit =
 * 10 m, in its failure-day wind speed (Froude-scaled) with a 0.74 Hz gust by default, plus the [particlesim.physics.TorsionalFlutter] self-excited deck moment.
 * See that function's doc comment for the sources, dimensions and scaling.
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
 * minutes - girders buckling, hangers snapping and the main span falling away bay by bay. There's no load crossing this one: the wind
 * is the load.
 */
class TacomaNarrowsScene(
    windVelocity: String = TACOMA_GUSTY_WIND,
    /** Real breaking limits ([particlesim.examples.TacomaBreakage]) and the water below. */
    private val breakable: Boolean = false,
    /** Color every breakable member - hangers, cable segments and (drawn only then) the girder
     * chords - by how close it is to its real breaking limit
     * ([particlesim.examples.TacomaBreakage.utilization]), green unstressed, yellow halfway, red at breaking,
     * with the viewer's break-proximity legend. */
    private val showStress: Boolean = breakable,
) : DemoScene {
    internal val scenario = buildTacomaNarrows(windVelocity = windVelocity, breakable = breakable)
    private val integrator = Integrator()
    private val forces = scenario.forces.toMutableList()
    private val events = ArrayList<SimEvent>()

    init {
        if (breakable) {
            // The freshly built model rings for several seconds (hanger tension briefly ~2x its
            // dead load) - let that die away in still air before anything can break.
            scenario.wind.setVelocity(VectorExpr.of(Vector3.ZERO))
            var t = 0.0
            repeat((BREAKABLE_SETTLE_SECONDS / TACOMA_DT).toInt()) {
                integrator.step(scenario.store, scenario.groups, forces, scenario.constraints, t, TACOMA_DT)
                t += TACOMA_DT
            }
            scenario.wind.setVelocity(ExpressionParser.parseVector(windVelocity))
        }
        // Starts with a small one-node twist - the model is symmetric, so the collapse-day mode
        // needs a disturbance to grow from (on the day, a slipped cable band at mid-span).
        scenario.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)
    }
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
    // With stress colors on, the hangers are drawn thicker so their color reads, and the girder
    // chords (otherwise only implied by the girder panels) are drawn too, just proud of them.
    private val chordConnections = if (!showStress) emptyList() else {
        val chordPairs = HashSet<Pair<Int, Int>>()
        for (span in scenario.spans) for ((a, b) in span.zipWithNext()) {
            chordPairs += listOf(a.topLeft to b.topLeft, a.bottomLeft to b.bottomLeft, a.topRight to b.topRight, a.bottomRight to b.bottomRight)
        }
        chordPairs.toList()
    }
    private val connectionStyles =
        (cableConnections styledAs ConnectionStyles.steel("main-cables", radius = 0.04)) +
            (scenario.hangerConnections styledAs ConnectionStyles.cable("hangers", radius = if (showStress) 0.018 else 0.01)) +
            (towerConnections styledAs ConnectionStyles.paintedSteel("towers", towerGrey, radius = 0.18)) +
            (chordConnections styledAs ConnectionStyles.paintedSteel("girder-chords", Color(0.42, 0.47, 0.44), radius = 0.03, joints = false))

    private companion object {
        const val BREAKABLE_SETTLE_SECONDS = 8.0
        const val ARM_AFTER_SECONDS = 5.0
        // Magenta/cyan - well clear of the green-yellow-red stress colors on the hangers and cables.
        val FEEDING = Color(0.9, 0.2, 0.85)
        val DAMPING = Color(0.1, 0.85, 0.95)
        /** Arrow length per unit of couple force - a few deck widths at the limit cycle's peak. */
        const val FLUTTER_ARROW_SCALE = 2.0
    }

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
        val breakage = scenario.breakage
        // Armed a few seconds in, so the seed's own sudden kick isn't what breaks anything.
        if (breakable && !breakage.armed && t >= ARM_AFTER_SECONDS) breakage.arm()
        val result = integrator.step(scenario.store, scenario.groups, forces, scenario.constraints, t, dt)
        if (result.brokenForces.isNotEmpty()) forces.removeAll(result.brokenForces.toSet())
        for (description in breakage.afterStep(result.brokenForces)) events += SimEvent.ForceBreak(description)
        scenario.collisions?.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, forces, scenario.constraints, scenario.store, t, lights)) return
        if (message is SceneControlMessage.SetGroupEnabled) scenario.groups.setEnabled(message.name, message.enabled)
    }

    // The flutter force drawn as its couple on each main-span slice: an arrow up on one girder
    // and down on the other, magenta where it's feeding energy into the twist (moment the same way
    // as the twist rate - negative aerodynamic damping) and cyan where it's taking it out.
    private fun flutterArrows(t: Double): List<NamedArrowSamples> {
        val flutter = scenario.flutter ?: return emptyList()
        val samples = flutter.couples(scenario.store, t).flatMap { c ->
            val color = if (c.feeding) FEEDING else DAMPING
            val v = c.force * FLUTTER_ARROW_SCALE
            listOf(
                ArrowSample(scenario.store.position(c.strip.right), v, color),
                ArrowSample(scenario.store.position(c.strip.left), v * -1.0, color),
            )
        }
        return listOf(NamedArrowSamples(flutter.name ?: "", samples))
    }

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
            mesh.copy(surface = Surface(mesh.surface.triangles.filter { it !in hidden }, name = mesh.surface.name))
        }
    }

    override fun frame(t: Double): SceneFrame {
        refreshBrokenGeometry()
        // Unbroken hangers, cable segments and chords, each with its stress color; the towers
        // keep their plain style color.
        val stress = if (showStress) scenario.breakage.utilization(scenario.store) else emptyMap()
        val frame = SceneFrame(
            lineColors = stress.mapValues { (_, u) -> ColorRamp.greenYellowRed(u) },
            arrowGroups = flutterArrows(t),
            shownByDefaultArrows = setOfNotNull(scenario.flutter?.name),
            camera = camera.evaluate(t, scene),
            connections = if (showStress) towerConnections + stress.keys else connections,
            connectionStyles = connectionStyles,
            meshes = meshes,
            visibleIds = visibleIds,
            hiddenByDefaultGroups = hiddenByDefaultGroups,
            registry = registry,
            lights = lights,
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
