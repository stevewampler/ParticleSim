package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.GuyDerrick
import particlesim.examples.GuyDerrick.Part
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
 * §9.6 scene-library demo for [GuyDerrick]: a 20 t guy derrick - a 40 m mast held up by eight
 * guys, a 30 m boom swinging all the way round under them - working at real speed, its steel and
 * rope coloured by the stress in them.
 *
 * **Controls** (the viewer's "controls" section): `slew` (degrees; positive turns
 * counter-clockwise seen from above - the bull wheel swings mast and boom together), `boom angle`
 * (degrees above horizontal, 20-78), `radius` (the hook's distance from the mast - the same thing
 * as the boom angle, so moving either moves both), `hook height` and `load` (tonnes, coming on
 * over a few seconds; it's rated for 20 t at any radius). Each shows where the derrick has
 * actually got to beside it. It swings at half a revolution a minute, luffs at a degree a second
 * and hoists at 1 m/s, holding the hook level while the boom luffs. The drives answer once it has
 * settled under its own weight, about 7 s after a (re)start.
 *
 * **What to watch**: the guys are pretensioned, so with stress colours they all start red. As a
 * load comes on at a long radius the ones in front of the boom ease off and pale while the ones
 * behind take it up - swing it round and the work passes from guy to guy. Lowering the boom works
 * the topping lift and the guys harder; the boom's own thrust hardly changes.
 *
 * **Wind** (controls: `wind`, the mean speed at 10 m in m/s, the speed blowing now beside it;
 * `wind direction`, degrees, 0 blowing along +x and positive turning it the way the derrick
 * swings; options: `gusts`, a checkbox): drag on every member, guy, rope and the load, blowing
 * harder higher up (see [particlesim.physics.TrussWind]). A new speed or direction comes on over a
 * few seconds; gusts swing the speed 35% either side of the mean. Still by default.
 *
 * **Colours** (options: `colours`): `stress` colours each member by its axial stress -
 * compression blue, tension red, white unloaded, full colour at ±160 MPa - with a legend;
 * `paint` shows the derrick's colours.
 *
 * **Failure** (options: `failure`, a checkbox, off by default): off, the members just work
 * harder; on, they yield, buckle and tear (see [particlesim.physics.Truss]) - three times its
 * rated load brings it down. Torn members disappear.
 */
class GuyDerrickScene : DemoScene {
    private val derrick = GuyDerrick()
    override val dt: Double = derrick.dt
    override val store: ParticleStore = derrick.store
    private val spec = derrick.spec

    private var colours = COLOUR_MODES.first()
    private var failure = false
    private val windControls = WindControls(derrick.wind)

    private val members: List<Pair<Int, Int>> = derrick.truss.members.map { it.a to it.b }

    private val connectionStyles: Map<Pair<Int, Int>, ConnectionStyle> = run {
        val mastChord = ConnectionStyles.paintedSteel("mast chords", TowerCraneScene.CRANE_YELLOW, radius = 0.07)
        val mastWeb = ConnectionStyles.paintedSteel("mast lattice", TowerCraneScene.CRANE_YELLOW, radius = 0.035, joints = false)
        val boomChord = ConnectionStyles.paintedSteel("boom chords", TowerCraneScene.CRANE_YELLOW, radius = 0.07)
        val boomWeb = ConnectionStyles.paintedSteel("boom lattice", TowerCraneScene.CRANE_YELLOW, radius = 0.035, joints = false)
        val guy = ConnectionStyles.cable("guys", Color(0.25, 0.25, 0.27), radius = 0.03)
        val lift = ConnectionStyles.cable("topping lift", Color(0.2, 0.2, 0.22), radius = 0.04)
        val hoist = ConnectionStyles.cable("hoist rope", Color(0.2, 0.2, 0.22), radius = 0.03)
        members.indices.associate { i ->
            members[i] to when (derrick.parts[i]) {
                Part.MAST_CHORD, Part.MAST_TAPER -> mastChord
                Part.MAST_WEB -> mastWeb
                Part.BOOM_CHORD, Part.BOOM_TIP -> boomChord
                Part.BOOM_WEB -> boomWeb
                Part.GUY -> guy
                Part.TOPPING_LIFT -> lift
                Part.HOIST_ROPE -> hoist
            }
        }
    }

    // ---- Blocks: the bull wheel and hook turn and move with the derrick; the rest stay put.

    private fun pos(id: Int) = store.position(id)
    private fun mean(ids: List<Int>) = ids.fold(Vector3.ZERO) { s, id -> s + pos(id) } * (1.0 / ids.size)

    /** The mast's lowest square's frame: x toward the boom, y up the mast. */
    private fun wheelFrame(): CraneFrame {
        val base = derrick.mast[0]
        val x = (pos(base[1]) + pos(base[2]) - pos(base[0]) - pos(base[3])).normalized()
        val up = mean(derrick.mast[1]) - mean(base)
        val y = (up - x * up.dot(x)).normalized()
        return CraneFrame(mean(base), x, y, x.cross(y))
    }

    private val bullWheel = MovingBlock(store, Vector3(-1.8, -0.5, -1.8), Vector3(1.8, -0.2, 1.8), "bull wheel") { wheelFrame() }
    private val hookBlock = MovingBlock(store, Vector3(-0.45, -0.25, -0.3), Vector3(0.45, 0.9, 0.3), "hook block") {
        levelFrame(pos(derrick.hookNode), pos(derrick.boomTip) - mean(derrick.boomFoot))
    }

    /** The load hangs on slings below the hook, tilted with the rope. */
    private val loadBlock = MovingBlock(store, Vector3.ZERO, Vector3.ZERO, "load") {
        val hook = pos(derrick.hookNode)
        val y = (pos(derrick.boomTip) - hook).normalized()
        val z = (pos(derrick.boomTip) - mean(derrick.boomFoot)).cross(y).normalized()
        CraneFrame(hook, y.cross(z), y, z)
    }

    private fun sizeLoad() {
        // The derrick's crate (the wind pushes on the same one), as deep as its tonnes need.
        val crate = derrick.loadCrate
        val depth = crate.depth(derrick.loadTonnes)
        val top = -SLING_DROP
        loadBlock.min = Vector3(-crate.length / 2, top - depth, -crate.width / 2)
        loadBlock.max = Vector3(crate.length / 2, top, crate.width / 2)
    }

    private val blocks = listOf(bullWheel, hookBlock, loadBlock)

    private val base = FixedBox(store, Vector3(-1.5, -1.0, -1.5), Vector3(1.5, 0.05, 1.5), "base")
    private val anchorBlocks = derrick.anchors.mapIndexed { k, id ->
        val p = pos(id)
        FixedBox(store, p + Vector3(-1.0, -1.5, -1.0), p + Vector3(1.0, 0.3, 1.0), "anchor ${k + 1}")
    }
    private val winchHouse = FixedBox(store, Vector3(-9.0, 0.0, 4.0), Vector3(-5.0, 3.0, 7.0), "winch house")
    private val ground = FixedBox(store, Vector3(-130.0, -0.2, -130.0), Vector3(130.0, 0.0, 130.0), "ground")

    private val bullWheelMesh = craneMesh(bullWheel.surface, Color(0.3, 0.32, 0.35), 0.5)
    private val hookMesh = craneMesh(hookBlock.surface, Color(0.9, 0.55, 0.1), 0.5)
    private val loadMesh = craneMesh(loadBlock.surface, Color(0.35, 0.22, 0.15), 0.85)
    private val baseMesh = craneMesh(base.surface, Color(0.6, 0.58, 0.54), 0.95)
    private val anchorMeshes = anchorBlocks.map { craneMesh(it.surface, Color(0.6, 0.58, 0.54), 0.95) }
    private val winchMesh = craneMesh(winchHouse.surface, Color(0.55, 0.35, 0.25), 0.8)
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

    override fun step(t: Double) = derrick.step(t)

    /** The derrick itself, for tests and probes. */
    internal val model: GuyDerrick get() = derrick

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, derrick.forces, derrick.constraints, store, t, lights)) return
        when (message) {
            is SceneControlMessage.SetGroupEnabled -> derrick.groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.SetSceneOption -> when (message.name) {
                COLOURS_OPTION -> if (message.choice in COLOUR_MODES) colours = message.choice
                FAILURE_OPTION -> if (message.choice in SceneOption.ON_OFF) { failure = message.choice == "on"; derrick.failure = failure }
                WindControls.GUSTS_OPTION -> windControls.setGusts(message.choice)
            }
            is SceneControlMessage.SetSceneControl -> setControl(message.name, message.value)
            else -> {}
        }
    }

    /** Sets control [name] (see [controls]); false if there's no such control or [value] makes
     * no sense for it (a negative load). Targets out of range are clamped by the derrick. */
    internal fun setControl(name: String, value: Double): Boolean {
        if (!value.isFinite()) return false
        when (name) {
            SLEW -> derrick.slew.target = value
            BOOM_ANGLE -> derrick.luff.target = value
            RADIUS -> derrick.radiusTarget = value
            HOOK_HEIGHT -> derrick.hookHeightTarget = value
            LOAD -> { if (value < 0.0) return false; derrick.loadTonnes = value }
            else -> return windControls.set(name, value)
        }
        return true
    }

    /** The operator's controls: each target, with where the derrick has got to. */
    internal fun controls(t: Double = 0.0): List<SceneControl> = listOf(
        SceneControl(SLEW, "°", -360.0, 360.0, derrick.slew.target, derrick.slewDegrees),
        SceneControl(BOOM_ANGLE, "°", spec.minBoomAngle, spec.maxBoomAngle, derrick.luff.target, derrick.boomAngle),
        SceneControl(RADIUS, "m", spec.minRadius, spec.maxRadius, derrick.radiusTarget, derrick.radius),
        SceneControl(HOOK_HEIGHT, "m", spec.minHookHeight, spec.tipHeight(spec.maxBoomAngle) - GuyDerrick.MIN_ROPE, derrick.hookHeightTarget, store.position(derrick.hookNode).y),
        SceneControl(LOAD, "t", 0.0, MAX_LOAD_CONTROL, derrick.loadTonnes, (store.mass(derrick.hookNode) - spec.hookBlockMass) / 1000),
    ) + windControls.controls(t)

    /** The members still standing: torn ones aren't drawn. */
    private fun standing(): List<Int> = members.indices.filter { !derrick.truss.isTorn(it) }

    /** Each standing member's colour for its axial stress. */
    internal fun stressColours(): Map<Pair<Int, Int>, Color> = standing().associate { i ->
        members[i] to ColorRamp.signed(derrick.truss.stress(store, i) / FULL_SCALE_STRESS).srgbToLinear()
    }

    override fun frame(t: Double): SceneFrame {
        sizeLoad()
        blocks.forEach { it.place() }
        val loaded = derrick.loadTonnes > 0.0
        val slings = if (loaded) listOf(2, 3, 6, 7).map { derrick.hookNode to loadBlock.ids[it] } else emptyList()
        val byStress = colours == "stress"
        val drawn = if (derrick.truss.hasTorn) standing().map { members[it] } else members
        return SceneFrame(
            camera = CAMERA,
            connections = drawn + slings,
            connectionStyles = (if (derrick.truss.hasTorn) connectionStyles.filterKeys { it in drawn.toSet() } else connectionStyles) +
                (slings styledAs ConnectionStyles.cable("slings", Color(0.2, 0.2, 0.22), radius = 0.02)),
            lineColors = if (byStress) stressColours() else emptyMap(),
            lineLegend = if (byStress) ColorLegend("member stress", "MPa", -FULL_SCALE_STRESS / 1e6, FULL_SCALE_STRESS / 1e6, ColorRamp.SIGNED_STOPS) else null,
            meshes = listOf(bullWheelMesh, hookMesh) + (if (loaded) listOf(loadMesh) else emptyList()) + listOf(baseMesh) + anchorMeshes + listOf(winchMesh, groundMesh),
            visibleIds = emptySet(),
            lights = lights,
            skyColor = TowerCraneScene.SKY,
            options = listOf(SceneOption.toggle(FAILURE_OPTION, failure), SceneOption(COLOURS_OPTION, COLOUR_MODES, colours), windControls.option()),
            controls = controls(t),
            registry = SceneRegistry.build(forces = derrick.forces, constraints = derrick.constraints, groups = derrick.groups, lights = lights),
        )
    }

    companion object {
        const val COLOURS_OPTION = "colours"
        const val FAILURE_OPTION = "failure"
        const val SLEW = "slew"
        const val BOOM_ANGLE = "boom angle"
        const val RADIUS = "radius"
        const val HOOK_HEIGHT = "hook height"
        const val LOAD = "load"
        /** The load slider's top: past what it's rated for, to see it fail. */
        const val MAX_LOAD_CONTROL = 60.0
        val COLOUR_MODES = listOf("stress", "paint")
        /** Full colour on the stress ramp, Pa. */
        const val FULL_SCALE_STRESS = 160e6
        const val SLING_DROP = 1.2
        val CAMERA = CameraPose(position = Vector3(48.0, 26.0, 66.0), lookAt = Vector3(8.0, 18.0, 0.0))
    }
}
