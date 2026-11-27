package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.GantryCrane
import particlesim.examples.GantryCrane.Part
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.ColorLegend
import particlesim.render.ColorRamp
import particlesim.render.ConnectionStyle
import particlesim.render.Light
import particlesim.render.SceneControl
import particlesim.render.SceneOption
import particlesim.render.SceneRegistry
import particlesim.render.srgbToLinear

/**
 * §9.6 scene-library demo for [GantryCrane]: a goliath crane - 40 t over a 30 m span, 16 m clear
 * under its girder - travelling on its rails at real speed, its steel members coloured by the
 * stress in them.
 *
 * **Controls** (the viewer's "controls" section): `travel` (metres along the rails from their
 * middle), `trolley` (metres across the span from its middle, + toward the flexible leg), `hook
 * height` (above the ground) and `load` (tonnes on the hook, coming on over a few seconds). Each
 * shows where the crane has actually got to beside it. The crane drives toward them at its own
 * speeds - travelling at 36 m/min, the trolley at 30 m/min, hoisting at 24 m/min - and the load
 * swings as it starts and stops. The drives answer once the crane has settled under its own
 * weight, about 7 s after a (re)start.
 *
 * **Wind** (controls: `wind`, the mean speed at 10 m in m/s, the speed blowing now beside it;
 * `wind direction`, degrees, 0 blowing along +x and positive turning it the way the crane slews;
 * options: `gusts`, a checkbox): drag on every member, rope and block, blowing harder higher up
 * (see [particlesim.physics.TrussWind]). A new speed or direction comes on over a few seconds;
 * gusts swing the speed 35% either side of the mean. Still by default.
 *
 * **Colours** (options: `colours`): `stress` colours each member by its axial stress -
 * compression blue, tension red, white unloaded, full colour at ±160 MPa - with a legend;
 * `paint` shows the crane's yellow. The rated load is 40 t anywhere across the span.
 *
 * **Failure** (options: `failure`, a checkbox, off by default): off, the members just work
 * harder past the rated load; on, they yield, buckle and tear (see [particlesim.physics.Truss]) -
 * about three times the rated load buckles the girder's top chords and brings it down. Torn
 * members disappear. What has failed stays failed until a restart.
 */
class GantryCraneScene : DemoScene {
    private val crane = GantryCrane()
    override val dt: Double = crane.dt
    override val store: ParticleStore = crane.store
    private val spec = crane.spec

    private var colours = COLOUR_MODES.first()
    private var failure = false
    private val windControls = WindControls(crane.wind)

    private val members: List<Pair<Int, Int>> = crane.truss.members.map { it.a to it.b }

    private val connectionStyles: Map<Pair<Int, Int>, ConnectionStyle> = run {
        val chord = ConnectionStyles.paintedSteel("chords", TowerCraneScene.CRANE_YELLOW, radius = 0.1)
        val web = ConnectionStyles.paintedSteel("lattice", TowerCraneScene.CRANE_YELLOW, radius = 0.06, joints = false)
        val leg = ConnectionStyles.paintedSteel("legs", TowerCraneScene.CRANE_YELLOW, radius = 0.25)
        val brace = ConnectionStyles.paintedSteel("leg braces", TowerCraneScene.CRANE_YELLOW, radius = 0.13)
        val sill = ConnectionStyles.paintedSteel("sills", TowerCraneScene.CRANE_YELLOW, radius = 0.3)
        val hoist = ConnectionStyles.cable("hoist rope", Color(0.2, 0.2, 0.22), radius = 0.03)
        members.indices.associate { i ->
            members[i] to when (crane.parts[i]) {
                Part.GIRDER_CHORD -> chord
                Part.GIRDER_WEB -> web
                Part.RIGID_LEG, Part.FLEXIBLE_LEG -> leg
                Part.LEG_BRACE -> brace
                Part.SILL -> sill
                Part.HOIST_ROPE -> hoist
            }
        }
    }

    // ---- Blocks drawn on the structure, each built in a frame that moves with it.

    private fun pos(id: Int) = store.position(id)
    /** Along the girder, levelled. */
    private fun along() = pos(crane.girder.last()[0]) - pos(crane.girder.first()[0])

    private val trolley = MovingBlock(store, Vector3(-1.6, -1.3, -1.3), Vector3(1.6, -0.05, 1.3), "trolley") {
        levelFrame(pos(crane.trolleyNode), along())
    }
    private val cab = MovingBlock(store, Vector3(-1.0, -2.4, -1.8), Vector3(1.0, -0.1, -0.15), "cab") {
        levelFrame(pos(crane.girder[spec.rigidLegPanel + 1][0]), along())
    }
    private val hookBlock = MovingBlock(store, Vector3(-0.45, -0.25, -0.3), Vector3(0.45, 0.9, 0.3), "hook block") {
        levelFrame(pos(crane.hookNode), along())
    }
    private val bogies = crane.feet.mapIndexed { i, foot ->
        MovingBlock(store, Vector3(-0.6, 0.0, -1.6), Vector3(0.6, 1.0, 1.6), "bogie ${i + 1}") { levelFrame(pos(foot), along()) }
    }

    /** The load hangs on slings below the hook, tilted with the rope. */
    private val loadBlock = MovingBlock(store, Vector3.ZERO, Vector3.ZERO, "load") {
        val hook = pos(crane.hookNode)
        val y = (pos(crane.trolleyNode) - hook).normalized()
        val z = along().cross(y).normalized()
        CraneFrame(hook, y.cross(z), y, z)
    }

    private fun sizeLoad() {
        // The crane's crate (the wind pushes on the same one), as deep as its tonnes need.
        val crate = crane.loadCrate
        val depth = crate.depth(crane.loadTonnes)
        val top = -SLING_DROP
        loadBlock.min = Vector3(-crate.length / 2, top - depth, -crate.width / 2)
        loadBlock.max = Vector3(crate.length / 2, top, crate.width / 2)
    }

    private val blocks = listOf(trolley, cab, hookBlock, loadBlock) + bogies

    // The rails run along z under each leg, on the ground (y = 0).
    private val rails = listOf(-spec.halfSpan, spec.halfSpan).mapIndexed { i, x ->
        FixedBox(store, Vector3(x - 0.15, 0.0, -spec.railLength / 2), Vector3(x + 0.15, 0.15, spec.railLength / 2), "rail ${i + 1}")
    }
    private val ground = FixedBox(store, Vector3(-120.0, -0.2, -120.0), Vector3(120.0, 0.0, 120.0), "ground")

    private val trolleyMesh = craneMesh(trolley.surface, Color(0.3, 0.32, 0.35), 0.5)
    private val cabMesh = craneMesh(cab.surface, Color(0.85, 0.85, 0.82), 0.4)
    private val hookMesh = craneMesh(hookBlock.surface, Color(0.9, 0.55, 0.1), 0.5)
    private val bogieMeshes = bogies.map { craneMesh(it.surface, Color(0.25, 0.25, 0.27), 0.6) }
    private val loadMesh = craneMesh(loadBlock.surface, Color(0.66, 0.65, 0.62), 0.9)
    private val railMeshes = rails.map { craneMesh(it.surface, Color(0.45, 0.42, 0.4), 0.4) }
    private val groundMesh = craneMesh(ground.surface, Color(0.42, 0.38, 0.3), 1.0)

    private val lights = listOf(
        Light.Ambient(color = Color(0.85, 0.9, 1.0), intensity = 0.6, name = "sky light"),
        Light.Directional(position = Vector3(40.0, 80.0, 50.0), color = Color(1.0, 0.97, 0.92), intensity = 1.1, name = "sun"),
    )

    init {
        sizeLoad()
        blocks.forEach { it.place() }
    }

    override fun ids(): List<Int> = store.liveIds()

    override fun step(t: Double) = crane.step(t)

    /** The crane itself, for tests and probes. */
    internal val model: GantryCrane get() = crane

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, crane.forces, crane.constraints, store, t, lights)) return
        when (message) {
            is SceneControlMessage.SetGroupEnabled -> crane.groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.SetSceneOption -> when (message.name) {
                COLOURS_OPTION -> if (message.choice in COLOUR_MODES) colours = message.choice
                FAILURE_OPTION -> if (message.choice in SceneOption.ON_OFF) { failure = message.choice == "on"; crane.failure = failure }
                WindControls.GUSTS_OPTION -> windControls.setGusts(message.choice)
            }
            is SceneControlMessage.SetSceneControl -> setControl(message.name, message.value)
            else -> {}
        }
    }

    /** Sets control [name] (see [controls]); false if there's no such control or [value] makes
     * no sense for it (a negative load). Targets out of range are clamped by the crane. */
    internal fun setControl(name: String, value: Double): Boolean {
        if (!value.isFinite()) return false
        when (name) {
            TRAVEL -> crane.travel.target = value
            TROLLEY -> crane.trolley.target = value
            HOOK_HEIGHT -> crane.hookHeightTarget = value
            LOAD -> { if (value < 0.0) return false; crane.loadTonnes = value }
            else -> return windControls.set(name, value)
        }
        return true
    }

    /** The operator's controls: each target, with where the crane has got to. */
    internal fun controls(t: Double = 0.0): List<SceneControl> = listOf(
        SceneControl(TRAVEL, "m", -spec.travelRange, spec.travelRange, crane.travel.target, crane.travelPosition),
        SceneControl(TROLLEY, "m", spec.minTrolley, spec.maxTrolley, crane.trolley.target, crane.trolleyPosition),
        SceneControl(HOOK_HEIGHT, "m", spec.minHookHeight, spec.girderHeight - GantryCrane.MIN_ROPE, crane.hookHeightTarget, store.position(crane.hookNode).y),
        SceneControl(LOAD, "t", 0.0, MAX_LOAD_CONTROL, crane.loadTonnes, (store.mass(crane.hookNode) - spec.hookBlockMass) / 1000),
    ) + windControls.controls(t)

    /** The members still standing: torn ones aren't drawn. */
    private fun standing(): List<Int> = members.indices.filter { !crane.truss.isTorn(it) }

    /** Each standing member's colour for its axial stress. */
    internal fun stressColours(): Map<Pair<Int, Int>, Color> = standing().associate { i ->
        members[i] to ColorRamp.signed(crane.truss.stress(store, i) / FULL_SCALE_STRESS).srgbToLinear()
    }

    override fun frame(t: Double): SceneFrame {
        sizeLoad()
        blocks.forEach { it.place() }
        val loaded = crane.loadTonnes > 0.0
        val slings = if (loaded) listOf(2, 3, 6, 7).map { crane.hookNode to loadBlock.ids[it] } else emptyList()
        val byStress = colours == "stress"
        val drawn = if (crane.truss.hasTorn) standing().map { members[it] } else members
        return SceneFrame(
            camera = CAMERA,
            connections = drawn + slings,
            connectionStyles = (if (crane.truss.hasTorn) connectionStyles.filterKeys { it in drawn.toSet() } else connectionStyles) +
                (slings styledAs ConnectionStyles.cable("slings", Color(0.2, 0.2, 0.22), radius = 0.02)),
            lineColors = if (byStress) stressColours() else emptyMap(),
            lineLegend = if (byStress) ColorLegend("member stress", "MPa", -FULL_SCALE_STRESS / 1e6, FULL_SCALE_STRESS / 1e6, ColorRamp.SIGNED_STOPS) else null,
            meshes = listOf(trolleyMesh, cabMesh, hookMesh) + (if (loaded) listOf(loadMesh) else emptyList()) + bogieMeshes + railMeshes + groundMesh,
            visibleIds = emptySet(),
            lights = lights,
            skyColor = TowerCraneScene.SKY,
            options = listOf(SceneOption.toggle(FAILURE_OPTION, failure), SceneOption(COLOURS_OPTION, COLOUR_MODES, colours), windControls.option()),
            controls = controls(t),
            registry = SceneRegistry.build(forces = crane.forces, constraints = crane.constraints, groups = crane.groups, lights = lights),
        )
    }

    companion object {
        const val COLOURS_OPTION = "colours"
        const val FAILURE_OPTION = "failure"
        const val TRAVEL = "travel"
        const val TROLLEY = "trolley"
        const val HOOK_HEIGHT = "hook height"
        const val LOAD = "load"
        /** The load slider's top: three times the rated load, enough to bring the girder down. */
        const val MAX_LOAD_CONTROL = 120.0
        val COLOUR_MODES = listOf("stress", "paint")
        /** Full colour on the stress ramp, Pa. */
        const val FULL_SCALE_STRESS = 160e6
        const val SLING_DROP = 1.0
        val CAMERA = CameraPose(position = Vector3(36.0, 20.0, 46.0), lookAt = Vector3(0.0, 9.0, 0.0))
    }
}
