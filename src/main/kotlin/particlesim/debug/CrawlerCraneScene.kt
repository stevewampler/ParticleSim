package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.CrawlerCrane
import particlesim.examples.CrawlerCrane.Part
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
 * §9.6 scene-library demo for [CrawlerCrane]: a crawler crane on a 50 m lattice boom standing on
 * the ground, working at real speed, its steel members coloured by the stress in them - and free
 * to tip over.
 *
 * **Controls** (the viewer's "controls" section): `slew` (degrees; positive turns
 * counter-clockwise seen from above), `boom angle` (degrees above horizontal, 30-82), `radius`
 * (the hook's distance from the slewing axis - the same thing as the boom angle, so moving either
 * moves both), `hook height` and `load` (tonnes, coming on over a few seconds). Each shows where
 * the crane has actually got to beside it. It slews at up to 1 rpm, luffs at 1.5 degrees a second
 * and hoists at 1.5 m/s, holding the hook level while the boom luffs. The drives answer once the
 * crane has settled under its own weight, about 7 s after a (re)start.
 *
 * **Stability**: it tips when its centre of mass, load included, passes over the outermost pads -
 * over the side sooner than over the end. With the boom at 45 degrees (36 m) it holds about 20 t
 * over the end but only about 15 t over the side: hang 17 t over the end and slew round to watch
 * it go over. Wind from behind the boom tips it with less: 15 m/s takes the 20 t over the end
 * down to about 18 t.
 *
 * **Wind** (controls: `wind`, the mean speed at 10 m in m/s, the speed blowing now beside it;
 * `wind direction`, degrees, 0 blowing along +x and positive turning it the way the crane slews;
 * options: `gusts`, a checkbox): drag on every member, rope and block, blowing harder higher up
 * (see [particlesim.physics.TrussWind]). A new speed or direction comes on over a few seconds;
 * gusts swing the speed 35% either side of the mean. Still by default.
 *
 * **Colours** (options: `colours`): `stress` colours each member by its axial stress -
 * compression blue, tension red, white unloaded, full colour at ±160 MPa - with a legend;
 * `paint` shows the crane's colours.
 *
 * **Failure** (options: `failure`, a checkbox, off by default): off, the members just work
 * harder; on, they yield, buckle and tear (see [particlesim.physics.Truss]) - a boom that hits
 * the ground as the crane goes over breaks up. Torn members disappear.
 */
class CrawlerCraneScene : DemoScene {
    private val crane = CrawlerCrane()
    override val dt: Double = crane.dt
    override val store: ParticleStore = crane.store
    private val spec = crane.spec

    private var colours = COLOUR_MODES.first()
    private var failure = false
    private val windControls = WindControls(crane.wind)

    private val members: List<Pair<Int, Int>> = crane.truss.members.map { it.a to it.b }

    private val connectionStyles: Map<Pair<Int, Int>, ConnectionStyle> = run {
        val boomChord = ConnectionStyles.paintedSteel("boom chords", BOOM_RED, radius = 0.08)
        val boomWeb = ConnectionStyles.paintedSteel("boom lattice", BOOM_RED, radius = 0.04, joints = false)
        val frame = ConnectionStyles.paintedSteel("frame", TowerCraneScene.CRANE_YELLOW, radius = 0.12)
        val gantry = ConnectionStyles.paintedSteel("gantry", TowerCraneScene.CRANE_YELLOW, radius = 0.12)
        val track = ConnectionStyles.paintedSteel("tracks", Color(0.25, 0.25, 0.27), radius = 0.08, joints = false)
        val pendant = ConnectionStyles.cable("pendants", Color(0.25, 0.25, 0.27), radius = 0.05)
        val boomHoist = ConnectionStyles.cable("boom hoist rope", Color(0.2, 0.2, 0.22), radius = 0.04)
        val hoist = ConnectionStyles.cable("hoist rope", Color(0.2, 0.2, 0.22), radius = 0.03)
        members.indices.associate { i ->
            members[i] to when (crane.parts[i]) {
                Part.BOOM_CHORD, Part.BOOM_TIP -> boomChord
                Part.BOOM_WEB -> boomWeb
                Part.CARBODY, Part.UPPERWORKS -> frame
                Part.GANTRY -> gantry
                Part.TRACK -> track
                Part.PENDANT -> pendant
                Part.BOOM_HOIST_ROPE -> boomHoist
                Part.HOIST_ROPE -> hoist
            }
        }
    }

    // ---- Blocks drawn on the structure, each built in a frame that moves (and tilts) with it.

    private fun pos(id: Int) = store.position(id)
    private fun mean(ids: List<Int>) = ids.fold(Vector3.ZERO) { s, id -> s + pos(id) } * (1.0 / ids.size)

    /** The upperworks' frame: x forward (toward the boom), y up through the deck, at [origin]. */
    private fun upperFrame(origin: Vector3): CraneFrame {
        val x = (pos(crane.deckLower[1]) - pos(crane.deckLower[0])).normalized()
        val up = pos(crane.deckUpper[0]) - pos(crane.deckLower[0])
        val y = (up - x * up.dot(x)).normalized()
        return CraneFrame(origin, x, y, x.cross(y))
    }

    /** A track's frame: x along it, y up from its pads, at the middle of its bottom. */
    private fun trackFrame(side: List<List<Int>>): CraneFrame {
        val start = mean(side.first().take(2))
        val end = mean(side.last().take(2))
        val x = (end - start).normalized()
        val up = mean(side.first().drop(2)) - mean(side.first().take(2))
        val y = (up - x * up.dot(x)).normalized()
        return CraneFrame((start + end) * 0.5, x, y, x.cross(y))
    }

    private val trackBlocks = crane.tracks.mapIndexed { i, side ->
        val half = spec.trackLength / 2 + 0.4
        MovingBlock(store, Vector3(-half, 0.0, -spec.trackWidth / 2 - 0.05), Vector3(half, spec.trackHeight, spec.trackWidth / 2 + 0.05), "track ${i + 1}") { trackFrame(side) }
    }
    private val counterweight = MovingBlock(store, Vector3(-1.4, -1.0, -1.9), Vector3(0.3, 1.9, 1.9), "counterweight") {
        upperFrame(mean(listOf(crane.tail[0], crane.tail[1])))
    }
    private val cab = MovingBlock(store, Vector3(-2.2, 0.0, 0.1), Vector3(0.0, 2.2, 1.4), "cab") {
        upperFrame(pos(crane.deckLower[2]))
    }
    private val hookBlock = MovingBlock(store, Vector3(-0.45, -0.25, -0.3), Vector3(0.45, 0.9, 0.3), "hook block") {
        levelFrame(pos(crane.hookNode), pos(crane.boomTip) - mean(crane.boomFoot))
    }

    /** The load hangs on slings below the hook, tilted with the rope. */
    private val loadBlock = MovingBlock(store, Vector3.ZERO, Vector3.ZERO, "load") {
        val hook = pos(crane.hookNode)
        val y = (pos(crane.boomTip) - hook).normalized()
        val z = (pos(crane.boomTip) - mean(crane.boomFoot)).cross(y).normalized()
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

    private val blocks = trackBlocks + listOf(counterweight, cab, hookBlock, loadBlock)

    private val ground = FixedBox(store, Vector3(-120.0, -0.2, -120.0), Vector3(120.0, 0.0, 120.0), "ground")

    private val trackMeshes = trackBlocks.map { craneMesh(it.surface, Color(0.18, 0.18, 0.2), 0.9) }
    private val counterweightMesh = craneMesh(counterweight.surface, Color(0.55, 0.55, 0.52), 0.8)
    private val cabMesh = craneMesh(cab.surface, Color(0.85, 0.85, 0.82), 0.4)
    private val hookMesh = craneMesh(hookBlock.surface, Color(0.9, 0.55, 0.1), 0.5)
    private val loadMesh = craneMesh(loadBlock.surface, Color(0.35, 0.22, 0.15), 0.85)
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
    internal val model: CrawlerCrane get() = crane

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
            BOOM_ANGLE -> crane.luff.target = value
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
        SceneControl(BOOM_ANGLE, "°", spec.minBoomAngle, spec.maxBoomAngle, crane.luff.target, crane.boomAngle),
        SceneControl(RADIUS, "m", spec.minRadius, spec.maxRadius, crane.radiusTarget, crane.radius),
        SceneControl(HOOK_HEIGHT, "m", spec.minHookHeight, spec.tipHeight(spec.maxBoomAngle) - CrawlerCrane.MIN_ROPE, crane.hookHeightTarget, store.position(crane.hookNode).y),
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
            meshes = trackMeshes + listOf(counterweightMesh, cabMesh, hookMesh) + (if (loaded) listOf(loadMesh) else emptyList()) + groundMesh,
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
        const val BOOM_ANGLE = "boom angle"
        const val RADIUS = "radius"
        const val HOOK_HEIGHT = "hook height"
        const val LOAD = "load"
        /** The load slider's top: past the most it can lift close in. */
        const val MAX_LOAD_CONTROL = 80.0
        val COLOUR_MODES = listOf("stress", "paint")
        /** Full colour on the stress ramp, Pa. */
        const val FULL_SCALE_STRESS = 160e6
        const val SLING_DROP = 1.2
        val BOOM_RED = Color(0.75, 0.12, 0.1)
        val CAMERA = CameraPose(position = Vector3(30.0, 18.0, 62.0), lookAt = Vector3(12.0, 16.0, 0.0))
    }
}
