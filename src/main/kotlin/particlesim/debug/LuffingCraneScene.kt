package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.LuffingCrane
import particlesim.examples.LuffingCrane.Part
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
 * §9.6 scene-library demo for [LuffingCrane]: a luffing-jib tower crane - 40 m mast, 50 m jib,
 * 12 t - working at real speed, its steel members coloured by the stress in them.
 *
 * **Controls** (the viewer's "controls" section): `slew` (degrees; positive turns
 * counter-clockwise seen from above), `jib angle` (degrees above horizontal, 15-85), `radius` (the
 * hook's distance from the mast - the same thing as the jib angle, put the operator's way, so
 * moving either moves both), `hook height` (above the ground) and `load` (tonnes on the hook,
 * coming on over a few seconds). Each shows where the crane has actually got to beside it. The
 * crane drives toward them at its own speeds - slewing at up to 0.8 rpm, luffing at a degree a
 * second, hoisting at 1.5 m/s - and the hoist holds the hook at its height while the jib luffs.
 * The drives answer once the crane has settled under its own weight, about 7 s after a
 * (re)start.
 *
 * **Wind** (controls: `wind`, the mean speed at 10 m in m/s, the speed blowing now beside it;
 * `wind direction`, degrees, 0 blowing along +x and positive turning it the way the crane slews;
 * options: `gusts`, a checkbox): drag on every member, rope and block, blowing harder higher up
 * (see [particlesim.physics.TrussWind]). A new speed or direction comes on over a few seconds;
 * gusts swing the speed 35% either side of the mean. Still by default.
 *
 * **Colours** (options: `colours`): `stress` colours each member by its axial stress -
 * compression blue, tension red, white unloaded, full colour at ±160 MPa - with a legend;
 * `paint` shows the crane's yellow. The load chart's limits are 12 t out to 20 m and 3 t with the
 * jib right down (49 m).
 *
 * **Failure** (options: `failure`, a checkbox, off by default): off, the members just work
 * harder past the load chart; on, they yield, buckle and tear (see [particlesim.physics.Truss]) -
 * about 2.5-3 times the chart's load brings the jib down, and the crane with it. Torn members
 * disappear. What has failed stays failed until a restart.
 */
class LuffingCraneScene : DemoScene {
    private val crane = LuffingCrane()
    override val dt: Double = crane.dt
    override val store: ParticleStore = crane.store
    private val spec = crane.spec

    private var colours = COLOUR_MODES.first()
    private var failure = false
    private val windControls = WindControls(crane.wind)

    private val members: List<Pair<Int, Int>> = crane.truss.members.map { it.a to it.b }

    private val connectionStyles: Map<Pair<Int, Int>, ConnectionStyle> = run {
        val chord = ConnectionStyles.paintedSteel("chords", TowerCraneScene.CRANE_YELLOW, radius = 0.09)
        val web = ConnectionStyles.paintedSteel("lattice", TowerCraneScene.CRANE_YELLOW, radius = 0.045, joints = false)
        val turntable = ConnectionStyles.paintedSteel("turntable", TowerCraneScene.CRANE_YELLOW, radius = 0.14)
        val aFrame = ConnectionStyles.paintedSteel("A-frame", TowerCraneScene.CRANE_YELLOW, radius = 0.13)
        val tie = ConnectionStyles.cable("tie bars", Color(0.25, 0.25, 0.27), radius = 0.06)
        val luffing = ConnectionStyles.cable("luffing rope", Color(0.2, 0.2, 0.22), radius = 0.04)
        val hoist = ConnectionStyles.cable("hoist rope", Color(0.2, 0.2, 0.22), radius = 0.02)
        members.indices.associate { i ->
            members[i] to when (crane.parts[i]) {
                Part.MAST_CHORD, Part.JIB_CHORD, Part.JIB_HEAD, Part.COUNTER_JIB_CHORD -> chord
                Part.MAST_WEB, Part.JIB_WEB, Part.COUNTER_JIB_WEB -> web
                Part.TURNTABLE -> turntable
                Part.A_FRAME -> aFrame
                Part.TIE -> tie
                Part.LUFFING_ROPE -> luffing
                Part.HOIST_ROPE -> hoist
            }
        }
    }

    // ---- Blocks drawn on the structure, each built in a frame that moves with it.

    private fun pos(id: Int) = store.position(id)
    private fun mean(ids: List<Int>) = ids.fold(Vector3.ZERO) { s, id -> s + pos(id) } * (1.0 / ids.size)
    /** Out along the jib, levelled: the way the turntable faces. */
    private fun facing() = pos(crane.turntableUpper[1]) - pos(crane.turntableUpper[0])
    private fun onCounterJib(k: Int) = mean(listOf(crane.counterJibLeft[k], crane.counterJibRight[k]))

    private val ballast = MovingBlock(store, Vector3(-2.2, -2.6, -0.9), Vector3(2.2, -0.05, 0.9), "ballast") {
        levelFrame(mean(crane.ballastNodes), facing())
    }
    private val hoistWinch = MovingBlock(store, Vector3(-0.8, 0.05, -0.6), Vector3(0.8, 1.1, 0.6), "hoist winch") {
        levelFrame(onCounterJib(crane.hoistWinchPanel), facing())
    }
    private val luffingWinch = MovingBlock(store, Vector3(-0.9, 0.05, -0.75), Vector3(0.9, 1.3, 0.75), "luffing winch") {
        levelFrame(onCounterJib(crane.luffingWinchPanel), facing())
    }
    private val cab = MovingBlock(store, Vector3(-1.4, -2.2, 0.05), Vector3(0.6, 0.0, 1.6), "cab") {
        levelFrame(pos(crane.turntableUpper[2]), facing())
    }
    private val hookBlock = MovingBlock(store, Vector3(-0.35, -0.2, -0.2), Vector3(0.35, 0.7, 0.2), "hook block") {
        levelFrame(pos(crane.hookNode), facing())
    }

    /** The load hangs on slings below the hook, tilted with the rope. */
    private val loadBlock = MovingBlock(store, Vector3.ZERO, Vector3.ZERO, "load") {
        val hook = pos(crane.hookNode)
        val y = (pos(crane.jibHead) - hook).normalized()
        val z = facing().cross(y).normalized()
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

    private val blocks = listOf(ballast, hoistWinch, luffingWinch, cab, hookBlock, loadBlock)

    // The mast stands on the foundation at ground level (y = 0).
    private val foundation = FixedBox(store, Vector3(-3.5, -1.5, -3.5), Vector3(3.5, 0.05, 3.5), "foundation")
    private val ground = FixedBox(store, Vector3(-120.0, -0.2, -120.0), Vector3(120.0, 0.0, 120.0), "ground")

    private val ballastMesh = craneMesh(ballast.surface, Color(0.62, 0.6, 0.56), 0.95)
    private val hoistWinchMesh = craneMesh(hoistWinch.surface, Color(0.3, 0.32, 0.35), 0.5)
    private val luffingWinchMesh = craneMesh(luffingWinch.surface, Color(0.3, 0.32, 0.35), 0.5)
    private val cabMesh = craneMesh(cab.surface, Color(0.85, 0.85, 0.82), 0.4)
    private val hookMesh = craneMesh(hookBlock.surface, Color(0.9, 0.55, 0.1), 0.5)
    private val loadMesh = craneMesh(loadBlock.surface, Color(0.35, 0.22, 0.15), 0.85)
    private val foundationMesh = craneMesh(foundation.surface, Color(0.6, 0.58, 0.54), 0.95)
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
    internal val model: LuffingCrane get() = crane

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
            SLEW -> crane.slew.target = value
            JIB_ANGLE -> crane.luff.target = value
            RADIUS -> crane.radiusTarget = value
            HOOK_HEIGHT -> crane.hookHeightTarget = value
            LOAD -> { if (value < 0.0) return false; crane.loadTonnes = value }
            else -> return windControls.set(name, value)
        }
        return true
    }

    /** The operator's controls: each target, with where the crane has got to. */
    internal fun controls(t: Double = 0.0): List<SceneControl> = listOf(
        SceneControl(SLEW, "°", -360.0, 360.0, crane.slew.target, crane.slewDegrees),
        SceneControl(JIB_ANGLE, "°", spec.minJibAngle, spec.maxJibAngle, crane.luff.target, crane.jibAngle),
        SceneControl(RADIUS, "m", spec.minRadius, spec.maxRadius, crane.radiusTarget, crane.radius),
        SceneControl(HOOK_HEIGHT, "m", spec.minHookHeight, spec.headHeight(spec.maxJibAngle) - LuffingCrane.MIN_ROPE, crane.hookHeightTarget, store.position(crane.hookNode).y),
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
                (slings styledAs ConnectionStyles.cable("slings", Color(0.2, 0.2, 0.22), radius = 0.015)),
            lineColors = if (byStress) stressColours() else emptyMap(),
            lineLegend = if (byStress) ColorLegend("member stress", "MPa", -FULL_SCALE_STRESS / 1e6, FULL_SCALE_STRESS / 1e6, ColorRamp.SIGNED_STOPS) else null,
            meshes = listOf(ballastMesh, hoistWinchMesh, luffingWinchMesh, cabMesh, hookMesh) + (if (loaded) listOf(loadMesh) else emptyList()) + foundationMesh + groundMesh,
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
        const val SLEW = "slew"
        const val JIB_ANGLE = "jib angle"
        const val RADIUS = "radius"
        const val HOOK_HEIGHT = "hook height"
        const val LOAD = "load"
        /** The load slider's top: 2.5 times the maximum load, room to overload it. */
        const val MAX_LOAD_CONTROL = 30.0
        val COLOUR_MODES = listOf("stress", "paint")
        /** Full colour on the stress ramp, Pa. */
        const val FULL_SCALE_STRESS = 160e6
        const val SLING_DROP = 1.2
        val CAMERA = CameraPose(position = Vector3(60.0, 28.0, 86.0), lookAt = Vector3(14.0, 40.0, 0.0))
    }
}
