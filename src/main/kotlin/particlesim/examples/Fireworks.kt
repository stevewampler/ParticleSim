package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.lifecycle.CustomEmitter
import particlesim.lifecycle.DestructionSystem
import particlesim.lifecycle.ReplayableRandom
import particlesim.physics.Drag
import particlesim.physics.FieldValue
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.UniformGravity
import particlesim.render.Color
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** A fireworks display's timestep - one viewer frame, so it runs in real time. Nothing here is
 * stiff: the fastest thing is a star's drag, which acts over tenths of a second. */
const val FIREWORKS_DT = 1.0 / 60.0

const val FIREWORKS_GRAVITY = 9.81

/** The linear drag coefficient on every star, N·s/m. A star's drag time constant is its mass
 * over this, so lighter stars ([BurstType.WILLOW]'s) slow faster and hang longer. */
const val STAR_DRAG = 0.01

/** The quadratic drag coefficient on a rising shell, kg/m: ½·ρ·Cd·A for a 7.6 cm (3-inch) ball,
 * with air density 1.2 kg/m³ and a sphere's drag coefficient 0.47 - standard textbook values, not
 * measured from real shells. With [FireworksShow.SHELL_MASS] it gives a terminal speed of about
 * 39 m/s, so shells leave the mortar at 58-88 m/s and slow hard on the way up. */
const val SHELL_DRAG = 0.0013

/** The kinds of shell [FireworksShow] fires - the shapes in a typical display:
 * - [PEONY]: a round burst of stars all one colour.
 * - [TWO_TONE]: a round burst of stars in two colours, mixed through each other.
 * - [WILLOW]: gold stars that slow quickly, burn long and droop.
 * - [RING]: one flat circle of stars, tilted at random.
 * - [CRACKLE]: glittering gold stars that each pop into a few brief white sparks as they burn out.
 * - [SPLIT]: a round burst of a dozen bright sub-shells, each bursting again into a small peony
 *   of its own a moment later.
 * - [CROSSETTE]: a few comets that each split, a moment out, into four pieces flying apart in
 *   a cross, square to the comet's path.
 * - [STROBE]: slow, long-burning white stars that blink on and off.
 * - [PALM]: a handful of heavy gold comets thrown up and out, each shedding a tail of sparks as
 *   it arcs over and droops - the fronds of a palm tree.
 * - [KAMURO]: a dense crown of long-burning glittering stars, each shedding a trail of brief
 *   glitter as it slows and hangs.
 * - [HORSETAIL]: stars barely thrown out, so they fall away from the break together in a
 *   long streaming column - a horse's tail.
 * - [MULTI_BREAK]: a peony whose break also throws an inner shell further up, which breaks
 *   again a moment later in another colour - two or three breaks in all, one above another. */
enum class BurstType { PEONY, TWO_TONE, WILLOW, RING, CRACKLE, SPLIT, CROSSETTE, STROBE, PALM, KAMURO, HORSETAIL, MULTI_BREAK }

/** A cloud of [puffs] smoke puffs, each thrown out at [minSpeed]-[maxSpeed] m/s and lasting
 * [minLife]-[maxLife] s; [flatten] squashes their spread vertically (1 = round). Modelling
 * choices, picked to look right, not measured. */
data class SmokeCloud(val puffs: Int, val minSpeed: Double, val maxSpeed: Double, val minLife: Double, val maxLife: Double, val flatten: Double = 1.0)

/** A burning star that sheds sparks behind it, with [chance] of one each step: a
 * [BurstType.PALM] frond or a [BurstType.KAMURO] star. Each spark is a [recipe] star in [color],
 * leaving with [carry] times the star's velocity plus [StarRecipe.speed] in a random direction. */
data class SparkTail(val chance: Double, val recipe: StarRecipe, val color: Color, val carry: Double)

/** What a star does as it burns out - see [FireworksShow]. */
sealed interface BurnoutEffect {
    /** A crackling star: pops into a few brief sparks. */
    data object Crackle : BurnoutEffect
    /** A split sub-shell: bursts into a small peony in [colors]. */
    data class Split(val colors: List<Color>) : BurnoutEffect
    /** A crossette comet: splits into four pieces of [color] in a cross. */
    data class Crossette(val color: Color) : BurnoutEffect
}

/** The look and flight of one burst type's stars. [starMass] sets how quickly drag slows them
 * ([STAR_DRAG]); all values are modelling choices tuned by eye, not measured from real shells. */
data class StarRecipe(
    val count: Int,
    val speed: Double,
    val starMass: Double,
    val minBurn: Double,
    val maxBurn: Double,
)

/** A shell on its way up, the height it's aimed to burst at, and what it will burst into. */
data class Shell(
    val id: Int,
    val burstTime: Double,
    val breakHeight: Double,
    val type: BurstType,
    val colors: List<Color>,
    /** When this shell starts whistling (it whistles until [whistleEnd]), or null if it doesn't. */
    val whistleStart: Double? = null,
    val whistleEnd: Double? = null,
    /** A [BurstType.MULTI_BREAK] shell's breaks still to come after this one, each's colour. */
    val laterBreaks: List<Color> = emptyList(),
    /** Which break this is: 1 for a shell fired from a mortar, 2 and 3 for a multi-break
     * shell's inner shells. */
    val stage: Int = 1,
)

/** A shell bursting at [position] at [time], lighting the ground in [color] - the scene turns
 * each one into a brief point-light flash. */
data class BurstFlash(val position: Vector3, val time: Double, val color: Color)

/** The noises a display makes. */
enum class FireworkNoise {
    /** A shell leaving its mortar. */
    LAUNCH,
    /** A burst - a shell's, or (quieter) a split sub-shell's or crossette comet's break. */
    BOOM,
    /** A crackling star popping as it burns out. */
    CRACKLE,
    /** A whistling shell's whistle, over the last second or so of its climb. */
    WHISTLE,
}

/** A noise at [position] at [time], [loudness] times its kind's usual level, lasting [duration]
 * seconds (0 for a noise whose length is its kind's own). */
data class FireworkSound(val noise: FireworkNoise, val position: Vector3, val loudness: Double, val time: Double, val duration: Double = 0.0)

/** What one [FireworksShow.step] created, destroyed and sounded - for the scene's §9.1 event
 * channel. */
data class FireworksStepResult(
    val spawnedIds: List<Int>,
    val destroyedIds: List<Int>,
    val sounds: List<FireworkSound> = emptyList(),
)

/**
 * A continuous fireworks display: shells launched from mortars spread along the ground, each
 * rising to its break height and bursting into stars that spread, slow under drag, droop under
 * gravity and burn out.
 *
 * Built from the same pieces as the other particle-effect scenes, in metres and seconds:
 * - Shells fly under gravity ([FIREWORKS_GRAVITY]) and quadratic air drag ([SHELL_DRAG]),
 *   launched straight up (with a little tilt) at the speed that peaks at their break height, and
 *   burst on a time fuse just before the top. Both come from the closed-form solution for a
 *   ball rising against quadratic drag ([launchSpeedFor], [timeToApex]).
 * - Each burst replaces its shell with stars ([BurstType], [StarRecipe]), sent out in an even
 *   spread of directions (a jittered Fibonacci sphere, so the burst is round) on top of the
 *   shell's own velocity at the moment it bursts.
 * - Stars feel gravity and linear [Drag]; each burns for a set time ([particlesim.core.ParticleStore]
 *   lifetime, §14.2) and is then destroyed. Some stars do something as they burn out: a
 *   [BurstType.CRACKLE] star pops into sparks, a [BurstType.SPLIT] sub-shell bursts again, and a
 *   [BurstType.CROSSETTE] comet splits in a cross ([BurnoutEffect]). A [BurstType.PALM] frond
 *   and a [BurstType.KAMURO] star shed tail sparks all the while they burn ([SparkTail]).
 * - A [BurstType.MULTI_BREAK] shell's break also throws an inner shell further up, which
 *   breaks again (and again, for a three-break shell) a moment later.
 * - Every shell's burst is also recorded as a [BurstFlash] for a moment ([recentFlashes]).
 * - Every launch and burst leaves smoke ([SMOKE_GROUP], [smokeIds]): puffs thrown out a little
 *   way, then carried on the breeze by [smokeDrift], gone after ~20 s.
 * - Each step reports the noises it made ([FireworkSound]): a launch, a boom per burst (quieter
 *   for split sub-shells and crossette breaks), a crackle per crackling star, and a whistle for
 *   the shells that whistle ([WHISTLE_CHANCE]). A whistle runs over the last ~1 s of the climb
 *   and stops just before the burst - as measured from a field recording of a display.
 *
 * Break heights (90-140 m), star speeds (~30-42 m/s), burn times (~2 s, willows ~4 s) and every
 * burst type's star counts are modelling choices, picked to look right from the scene's
 * viewpoint, not sourced figures.
 *
 * Launching isn't a rate-driven [particlesim.lifecycle.Emitter]: bursts appear where a shell
 * happens to be when its fuse runs out, which an emitter at a fixed place can't express. The show
 * is a [CustomEmitter] instead, named `launcher`: the viewer lists it under EMITTERS with its
 * launch interval, break heights and spread editable live ([editableFields]), and a §9.5
 * checkpoint carries everything it has in flight ([captureState]) so a resumed run carries on
 * exactly. Its randomness comes from two seeded streams - one for the display, one for the smoke,
 * so smoke never shifts the display's draws - and a run repeats exactly (§11).
 */
class FireworksShow(
    val seed: Long = 1L,
    /** Mortars sit at random points this far either side of the origin along x, m. */
    launchHalfWidth: Double = 50.0,
    minBreakHeight: Double = 90.0,
    maxBreakHeight: Double = 140.0,
    /** Time between one launch (or salvo) and the next, s. */
    minLaunchInterval: Double = 0.6,
    maxLaunchInterval: Double = 1.6,
    /** When the first launch goes up, s - [Double.POSITIVE_INFINITY] for none (only [launch]). */
    firstLaunchTime: Double = 0.5,
    /** Whether launches and bursts leave smoke. */
    val smoke: Boolean = true,
) : CustomEmitter {
    override val name = "launcher"
    val store = ParticleStore()
    val groups = Groups()
    private var random = ReplayableRandom(seed)
    /** The smoke's own stream, so puffs never shift the display's draws. */
    private var smokeRandom = ReplayableRandom(seed xor SMOKE_SEED_SALT)

    var launchHalfWidth = launchHalfWidth
        private set
    var minBreakHeight = minBreakHeight
        private set
    var maxBreakHeight = maxBreakHeight
        private set
    var minLaunchInterval = minLaunchInterval
        private set
    var maxLaunchInterval = maxLaunchInterval
        private set
    private val integrator = Integrator()
    private val destruction = DestructionSystem()

    val gravity = UniformGravity(ALL_GROUP, Vector3(0.0, -FIREWORKS_GRAVITY, 0.0), name = "gravity")
    val drag = Drag(STAR_GROUP, coefficient = STAR_DRAG, name = "star-drag")
    val shellDrag = Drag(SHELL_GROUP, coefficient = SHELL_DRAG, quadratic = true, name = "shell-drag")
    /** Carries the smoke along with the breeze: drag toward a moving air, so each puff soon
     * slows from its burst and then drifts with the wind (editable as `wind`). */
    val smokeDrift = Drag(SMOKE_GROUP, coefficient = SMOKE_DRAG, name = "smoke-drift", air = Vector3(SMOKE_WIND_SPEED, 0.0, 0.0))
    val forces: List<Force> = listOf(gravity, drag, shellDrag, smokeDrift)

    private val shells = mutableListOf<Shell>()
    private val starColors = HashMap<Int, Color>()
    private val glitter = HashSet<Int>()
    private val strobes = HashSet<Int>()
    private val palmFronds = HashSet<Int>()
    /** Smoke puffed since the last step reported its spawns. */
    private val pendingSmoke = mutableListOf<Int>()
    /** Stars shedding sparks as they burn, by id. */
    private val tails = HashMap<Int, SparkTail>()
    /** Stars that do something when they burn out, by id. */
    private val burnoutEffects = HashMap<Int, BurnoutEffect>()
    private val flashes = ArrayDeque<BurstFlash>()
    private var nextLaunchTime = firstLaunchTime

    /** Shells still on their way up. */
    val liveShells: List<Shell> get() = shells.toList()

    /** Every live star's base colour, by id - the scene dims it as the star burns out. */
    val starColorsById: Map<Int, Color> get() = starColors

    /** The live [BurstType.CRACKLE] stars, which the scene draws twinkling. */
    val glitterIds: Set<Int> get() = glitter

    /** The live [BurstType.STROBE] stars, which the scene draws blinking. */
    val strobeIds: Set<Int> get() = strobes

    /** The live [BurstType.PALM] fronds, which the scene draws larger. */
    val palmFrondIds: Set<Int> get() = palmFronds

    /** The live smoke puffs - kept out of [ALL_GROUP], so gravity leaves them hanging. */
    val smokeIds: Set<Int> get() = groups.membersOf(SMOKE_GROUP)

    /** Shell bursts in the last [FLASH_MEMORY] seconds, oldest first. */
    val recentFlashes: List<BurstFlash> get() = flashes.toList()

    /** Advances the display from [t] by [dt]: flight, then bursts and burn-outs, then launches -
     * destroy before create, as in the other particle-effect scenes. */
    fun step(t: Double, dt: Double): FireworksStepResult {
        integrator.step(store, groups, forces, emptyList(), t, dt)
        val now = t + dt

        // Read each bursting shell before it's destroyed below.
        val whistling = shells.filter { val start = it.whistleStart; start != null && start > t && start <= now }
        val bursting = shells.filter { now >= it.burstTime }
        val bursts = bursting.map { Triple(it, store.position(it.id), store.velocity(it.id)) }
        shells.removeAll(bursting)

        // Stars with a burn-out effect, read the same way: whichever of them burn out below
        // act from where they were.
        val effectStates = burnoutEffects.keys.associateWith { Pair(store.position(it), store.velocity(it)) }

        // Burnt-out stars go by lifetime; bursting shells go explicitly.
        val destroyed = destruction.resolve(store, groups, forces, now, dt, explicitIds = bursting.map { it.id }.toSet())
        val spawned = mutableListOf<Int>()
        val sounds = mutableListOf<FireworkSound>()
        for (shell in whistling) {
            val start = shell.whistleStart!!
            sounds += FireworkSound(FireworkNoise.WHISTLE, store.position(shell.id), 1.0, start, duration = shell.whistleEnd!! - start)
        }
        // In id order (the order they were made), not the store's slot order - which smoke
        // shifts - so burn-out effects draw from the random stream the same way with or without it.
        for (id in destroyed.destroyedIds.sorted()) {
            starColors.remove(id)
            glitter.remove(id)
            strobes.remove(id)
            palmFronds.remove(id)
            tails.remove(id)
            val effect = burnoutEffects.remove(id) ?: continue
            val (position, velocity) = effectStates.getValue(id)
            spawned += when (effect) {
                BurnoutEffect.Crackle -> crackle(position, velocity, now)
                is BurnoutEffect.Split -> splitBurst(effect.colors, position, velocity, now)
                is BurnoutEffect.Crossette -> crossette(effect.color, position, velocity, now)
            }
            sounds += when (effect) {
                BurnoutEffect.Crackle -> FireworkSound(FireworkNoise.CRACKLE, position, 1.0, now)
                is BurnoutEffect.Split -> FireworkSound(FireworkNoise.BOOM, position, SPLIT_BOOM_LOUDNESS, now)
                is BurnoutEffect.Crossette -> FireworkSound(FireworkNoise.BOOM, position, CROSSETTE_BOOM_LOUDNESS, now)
            }
        }

        // Burning palm fronds and kamuro stars leave their tails behind them.
        for (id in tails.keys.sorted()) {
            val tail = tails.getValue(id)
            if (random.nextDouble() < tail.chance) spawned += tailSpark(tail, store.position(id), store.velocity(id), now)
        }

        for ((shell, position, velocity) in bursts) {
            spawned += burst(shell, position, velocity, now)
            flashes.addLast(BurstFlash(position, now, shell.colors.first()))
            sounds += FireworkSound(FireworkNoise.BOOM, position, 1.0, now)
            puff(position, velocity * SMOKE_CARRY, BURST_SMOKE, now)
            if (shell.laterBreaks.isNotEmpty()) spawned += innerShell(shell, position, velocity, now)
        }
        while (flashes.isNotEmpty() && now - flashes.first().time > FLASH_MEMORY) flashes.removeFirst()

        if (now >= nextLaunchTime) {
            val salvo = when (random.nextDouble()) {
                in 0.0..0.7 -> 1
                in 0.7..0.9 -> 2
                else -> 3
            }
            repeat(salvo) {
                val id = launch(now)
                spawned += id
                sounds += FireworkSound(FireworkNoise.LAUNCH, store.position(id), 1.0, now)
            }
            nextLaunchTime = now + random.nextDouble(minLaunchInterval, maxLaunchInterval)
        }
        spawned += pendingSmoke
        pendingSmoke.clear()
        return FireworksStepResult(spawned, destroyed.destroyedIds, sounds)
    }

    /** The launcher's live-editable settings (§10.4): break heights and launch interval as
     * ranges, and how far either side of the middle the mortars spread. */
    override fun editableFields(): Map<String, FieldValue> = linkedMapOf(
        "minBreakHeight" to FieldValue.Scalar(minBreakHeight),
        "maxBreakHeight" to FieldValue.Scalar(maxBreakHeight),
        "minLaunchInterval" to FieldValue.Scalar(minLaunchInterval),
        "maxLaunchInterval" to FieldValue.Scalar(maxLaunchInterval),
        "launchHalfWidth" to FieldValue.Scalar(launchHalfWidth),
    )

    /** Refuses a value that isn't finite, a range turned inside out (a minimum above its
     * maximum), break heights outside 10-400 m, an interval under 0.05 s, or a negative spread.
     * A new interval takes effect from the next launch. */
    override fun setField(field: String, value: FieldValue): Boolean {
        val v = (value as? FieldValue.Scalar)?.value ?: return false
        if (!v.isFinite()) return false
        when (field) {
            "minBreakHeight" -> if (v in 10.0..maxBreakHeight) minBreakHeight = v else return false
            "maxBreakHeight" -> if (v in minBreakHeight..400.0) maxBreakHeight = v else return false
            "minLaunchInterval" -> if (v in 0.05..maxLaunchInterval) minLaunchInterval = v else return false
            "maxLaunchInterval" -> if (v >= minLaunchInterval) maxLaunchInterval = v else return false
            "launchHalfWidth" -> if (v >= 0.0) launchHalfWidth = v else return false
            else -> return false
        }
        return true
    }

    /** Everything the show keeps besides its particles and their groups (§9.5): where both
     * random streams have got to, when the next launch goes up, the editable settings, every
     * shell in flight, each star's colour and what it does as it burns, and the recent flashes. */
    override fun captureState(): Map<String, Any?> = linkedMapOf(
        "seed" to seed,
        "random_steps" to random.steps,
        "smoke_random_steps" to smokeRandom.steps,
        "next_launch_time" to nextLaunchTime,
        "settings" to editableFields().mapValues { (_, v) -> (v as FieldValue.Scalar).value },
        "shells" to shells.map { shell ->
            linkedMapOf(
                "id" to shell.id,
                "burst_time" to shell.burstTime,
                "break_height" to shell.breakHeight,
                "type" to shell.type.name,
                "colors" to shell.colors.map(::colorList),
                "whistle_start" to shell.whistleStart,
                "whistle_end" to shell.whistleEnd,
                "later_breaks" to shell.laterBreaks.map(::colorList),
                "stage" to shell.stage,
            )
        },
        "star_colors" to starColors.entries.sortedBy { it.key }.map { (id, c) -> listOf(id) + colorList(c) },
        "glitter" to glitter.sorted(),
        "strobes" to strobes.sorted(),
        "palm_fronds" to palmFronds.sorted(),
        "tails" to tails.entries.sortedBy { it.key }.map { (id, tail) -> listOf(id, tailName(tail)) },
        "burnout_effects" to burnoutEffects.entries.sortedBy { it.key }.map { (id, effect) ->
            when (effect) {
                BurnoutEffect.Crackle -> listOf(id, "crackle", emptyList<List<Double>>())
                is BurnoutEffect.Split -> listOf(id, "split", effect.colors.map(::colorList))
                is BurnoutEffect.Crossette -> listOf(id, "crossette", listOf(colorList(effect.color)))
            }
        },
        "flashes" to flashes.map { f -> linkedMapOf("position" to listOf(f.position.x, f.position.y, f.position.z), "time" to f.time, "color" to colorList(f.color)) },
    )

    /** Puts back what [captureState] took, into a show built with the same seed whose particles
     * and groups the checkpoint has already restored. */
    @Suppress("UNCHECKED_CAST")
    override fun restoreState(state: Map<String, Any?>) {
        require((state["seed"] as Number).toLong() == seed) { "checkpoint is of a show with seed ${state["seed"]}, not $seed" }
        random = ReplayableRandom.restore(seed, (state["random_steps"] as Number).toLong())
        smokeRandom = ReplayableRandom.restore(seed xor SMOKE_SEED_SALT, (state["smoke_random_steps"] as Number).toLong())
        nextLaunchTime = (state["next_launch_time"] as Number).toDouble()
        val settings = state["settings"] as Map<String, Number>
        launchHalfWidth = settings.getValue("launchHalfWidth").toDouble()
        minBreakHeight = settings.getValue("minBreakHeight").toDouble()
        maxBreakHeight = settings.getValue("maxBreakHeight").toDouble()
        minLaunchInterval = settings.getValue("minLaunchInterval").toDouble()
        maxLaunchInterval = settings.getValue("maxLaunchInterval").toDouble()
        fun num(x: Any?) = (x as Number).toDouble()
        fun color(x: Any?) = (x as List<Any?>).let { Color(num(it[0]), num(it[1]), num(it[2])) }
        shells.clear()
        for (m in state["shells"] as List<Map<String, Any?>>) {
            shells += Shell(
                id = (m["id"] as Number).toInt(),
                burstTime = num(m["burst_time"]),
                breakHeight = num(m["break_height"]),
                type = BurstType.valueOf(m["type"] as String),
                colors = (m["colors"] as List<Any?>).map(::color),
                whistleStart = (m["whistle_start"] as Number?)?.toDouble(),
                whistleEnd = (m["whistle_end"] as Number?)?.toDouble(),
                laterBreaks = (m["later_breaks"] as List<Any?>).map(::color),
                stage = (m["stage"] as Number).toInt(),
            )
        }
        starColors.clear()
        for (row in state["star_colors"] as List<List<Any?>>) starColors[(row[0] as Number).toInt()] = color(row.drop(1))
        fun ids(key: String) = (state[key] as List<Number>).map { it.toInt() }
        glitter.clear(); glitter += ids("glitter")
        strobes.clear(); strobes += ids("strobes")
        palmFronds.clear(); palmFronds += ids("palm_fronds")
        tails.clear()
        for (row in state["tails"] as List<List<Any?>>) tails[(row[0] as Number).toInt()] = tailNamed(row[1] as String)
        burnoutEffects.clear()
        for (row in state["burnout_effects"] as List<List<Any?>>) {
            val colors = (row[2] as List<Any?>).map(::color)
            burnoutEffects[(row[0] as Number).toInt()] = when (row[1] as String) {
                "crackle" -> BurnoutEffect.Crackle
                "split" -> BurnoutEffect.Split(colors)
                else -> BurnoutEffect.Crossette(colors.single())
            }
        }
        flashes.clear()
        for (m in state["flashes"] as List<Map<String, Any?>>) {
            val p = m["position"] as List<Any?>
            flashes.addLast(BurstFlash(Vector3(num(p[0]), num(p[1]), num(p[2])), num(m["time"]), color(m["color"])))
        }
        pendingSmoke.clear()
    }

    private fun colorList(c: Color) = listOf(c.r, c.g, c.b)

    private fun tailName(tail: SparkTail) = when (tail) {
        PALM_TAIL -> "palm"
        KAMURO_TAIL -> "kamuro"
        else -> error("unknown spark tail $tail")
    }

    private fun tailNamed(name: String) = when (name) {
        "palm" -> PALM_TAIL
        "kamuro" -> KAMURO_TAIL
        else -> error("unknown spark tail $name")
    }

    /** Fires one shell from a random mortar, aimed to peak at a random break height. */
    fun launch(now: Double, type: BurstType = randomType()): Int {
        val breakHeight = random.nextDouble(minBreakHeight, maxBreakHeight)
        // The launch speed that, against gravity and drag, tops out at exactly breakHeight; the
        // fuse runs out just before the top.
        val launchSpeed = launchSpeedFor(breakHeight)
        val fuse = FUSE_FRACTION * timeToApex(launchSpeed)
        val x = if (launchHalfWidth > 0.0) random.nextDouble(-launchHalfWidth, launchHalfWidth) else 0.0
        val position = Vector3(x, MORTAR_HEIGHT, random.nextDouble(-15.0, 15.0))
        val velocity = Vector3(random.nextDouble(-2.0, 2.0), launchSpeed, random.nextDouble(-2.0, 2.0))
        val id = store.create(position = position, velocity = velocity, mass = ScalarExpr.of(SHELL_MASS), spawnTime = now)
        groups.add(ALL_GROUP, id)
        groups.add(SHELL_GROUP, id)
        val burstTime = now + fuse
        var whistleStart: Double? = null
        var whistleEnd: Double? = null
        if (random.nextDouble() < WHISTLE_CHANCE) {
            whistleEnd = burstTime - random.nextDouble(WHISTLE_MIN_GAP, WHISTLE_MAX_GAP)
            whistleStart = whistleEnd - random.nextDouble(WHISTLE_MIN_LENGTH, WHISTLE_MAX_LENGTH)
        }
        puff(position, Vector3.ZERO, LAUNCH_SMOKE, now)
        val colors = colorsFor(type)
        val laterBreaks = if (type == BurstType.MULTI_BREAK) colors.drop(1) else emptyList()
        shells += Shell(id, burstTime, breakHeight, type, if (laterBreaks.isEmpty()) colors else colors.take(1), whistleStart, whistleEnd, laterBreaks)
        return id
    }

    private fun randomType(): BurstType {
        var u = random.nextDouble()
        for ((type, weight) in TYPE_WEIGHTS) {
            if (u < weight) return type
            u -= weight
        }
        return TYPE_WEIGHTS.last().first
    }

    /** A [BurstType.MULTI_BREAK] shell's next stage: an inner shell thrown on up from [shell]'s
     * break, to break [MULTI_BREAK_MIN_DELAY]-[MULTI_BREAK_MAX_DELAY] s later in the next colour. */
    private fun innerShell(shell: Shell, position: Vector3, velocity: Vector3, now: Double): Int {
        val id = store.create(
            position = position,
            velocity = velocity + Vector3(0.0, MULTI_BREAK_KICK, 0.0),
            mass = ScalarExpr.of(SHELL_MASS),
            spawnTime = now,
        )
        groups.add(ALL_GROUP, id)
        groups.add(SHELL_GROUP, id)
        val delay = random.nextDouble(MULTI_BREAK_MIN_DELAY, MULTI_BREAK_MAX_DELAY)
        shells += Shell(id, now + delay, position.y, BurstType.MULTI_BREAK, listOf(shell.laterBreaks.first()), laterBreaks = shell.laterBreaks.drop(1), stage = shell.stage + 1)
        return id
    }

    private fun colorsFor(type: BurstType): List<Color> = when (type) {
        BurstType.PEONY, BurstType.RING, BurstType.SPLIT, BurstType.CROSSETTE -> listOf(PALETTE[random.nextInt(PALETTE.size)])
        BurstType.TWO_TONE -> {
            val first = random.nextInt(PALETTE.size)
            val second = (first + 1 + random.nextInt(PALETTE.size - 1)) % PALETTE.size
            listOf(PALETTE[first], PALETTE[second])
        }
        BurstType.WILLOW -> listOf(WILLOW_GOLD)
        BurstType.CRACKLE -> listOf(GLITTER_GOLD)
        BurstType.STROBE -> listOf(STROBE_WHITE)
        BurstType.PALM -> listOf(WILLOW_GOLD)
        BurstType.KAMURO -> listOf(KAMURO_GOLD)
        BurstType.HORSETAIL -> listOf(if (random.nextBoolean()) WILLOW_GOLD else HORSETAIL_SILVER)
        // One colour per break, each different from the one before it.
        BurstType.MULTI_BREAK -> {
            val breaks = if (random.nextDouble() < 0.5) 2 else 3
            val picks = mutableListOf(random.nextInt(PALETTE.size))
            repeat(breaks - 1) { picks += (picks.last() + 1 + random.nextInt(PALETTE.size - 1)) % PALETTE.size }
            picks.map { PALETTE[it] }
        }
    }

    private fun burst(shell: Shell, position: Vector3, velocity: Vector3, now: Double): List<Int> {
        val recipe = recipeFor(shell.type)
        // A random turn of the whole sphere, so bursts don't all share the same star pattern.
        val spin = random.nextDouble(0.0, 2.0 * PI)
        val directions = when (shell.type) {
            BurstType.RING -> ringDirections(recipe.count)
            BurstType.PALM -> palmDirections(recipe.count, spin)
            else -> (0 until recipe.count).map { fibonacciDirection(it, recipe.count, spin) }
        }
        return directions.mapIndexed { i, direction ->
            val color = if (shell.type == BurstType.SPLIT) SUBSHELL_COLOR else shell.colors[i % shell.colors.size]
            val id = star(position, velocity + direction * (recipe.speed * random.nextDouble(0.92, 1.08)), recipe, color, now)
            when (shell.type) {
                BurstType.CRACKLE -> { glitter += id; burnoutEffects[id] = BurnoutEffect.Crackle }
                BurstType.SPLIT -> burnoutEffects[id] = BurnoutEffect.Split(shell.colors)
                BurstType.CROSSETTE -> burnoutEffects[id] = BurnoutEffect.Crossette(color)
                BurstType.STROBE -> strobes += id
                BurstType.PALM -> { palmFronds += id; tails[id] = PALM_TAIL }
                BurstType.KAMURO -> { glitter += id; tails[id] = KAMURO_TAIL }
                else -> {}
            }
            id
        }
    }

    /** [cloud]'s puffs of smoke, thrown out from [position] on top of [velocity], reported as
     * spawned at the end of the step. */
    private fun puff(position: Vector3, velocity: Vector3, cloud: SmokeCloud, now: Double) {
        if (!smoke) return
        for (k in 0 until cloud.puffs) {
            val y = smokeRandom.nextDouble(-1.0, 1.0)
            val a = smokeRandom.nextDouble(0.0, 2.0 * PI)
            val r = sqrt(1.0 - y * y)
            val direction = Vector3(r * cos(a), y * cloud.flatten, r * sin(a))
            val id = store.create(
                position = position,
                velocity = velocity + direction * smokeRandom.nextDouble(cloud.minSpeed, cloud.maxSpeed),
                mass = ScalarExpr.of(SMOKE_PUFF_MASS),
                spawnTime = now,
                lifetime = ScalarExpr.of(smokeRandom.nextDouble(cloud.minLife, cloud.maxLife)),
            )
            groups.add(SMOKE_GROUP, id)
            pendingSmoke += id
        }
    }

    /** One star, burning for a random time within [recipe]'s range. */
    private fun star(position: Vector3, velocity: Vector3, recipe: StarRecipe, color: Color, now: Double): Int {
        val id = store.create(
            position = position,
            velocity = velocity,
            mass = ScalarExpr.of(recipe.starMass),
            spawnTime = now,
            lifetime = ScalarExpr.of(random.nextDouble(recipe.minBurn, recipe.maxBurn)),
        )
        groups.add(ALL_GROUP, id)
        groups.add(STAR_GROUP, id)
        starColors[id] = color
        return id
    }

    /** A burnt-out crackling star's pop: a few brief sparks flung every which way. */
    private fun crackle(position: Vector3, velocity: Vector3, now: Double): List<Int> =
        (0 until CRACKLE_RECIPE.count).map {
            val direction = randomDirection()
            star(position, velocity + direction * (CRACKLE_RECIPE.speed * random.nextDouble(0.6, 1.4)), CRACKLE_RECIPE, SPARK_WHITE, now)
        }

    /** A split sub-shell's own burst: a small round peony in the parent shell's colour. */
    private fun splitBurst(colors: List<Color>, position: Vector3, velocity: Vector3, now: Double): List<Int> {
        val spin = random.nextDouble(0.0, 2.0 * PI)
        return (0 until SPLIT_STAR_RECIPE.count).map { i ->
            val direction = fibonacciDirection(i, SPLIT_STAR_RECIPE.count, spin)
            val speed = SPLIT_STAR_RECIPE.speed * random.nextDouble(0.9, 1.1)
            star(position, velocity + direction * speed, SPLIT_STAR_RECIPE, colors[i % colors.size], now)
        }
    }

    /** A burnt-out crossette comet's split: four pieces flying apart in a cross, square to the
     * comet's path, carrying on at half its speed - the cross turned at random about that path. */
    private fun crossette(color: Color, position: Vector3, velocity: Vector3, now: Double): List<Int> {
        val (u, w) = perpendicularPair(velocity)
        val a = random.nextDouble(0.0, PI / 2.0)
        val arm1 = u * cos(a) + w * sin(a)
        val arm2 = w * cos(a) - u * sin(a)
        return listOf(arm1, arm1 * -1.0, arm2, arm2 * -1.0).map { arm ->
            star(position, velocity * CROSSETTE_CARRY + arm * CROSSETTE_PIECE_RECIPE.speed, CROSSETTE_PIECE_RECIPE, color, now)
        }
    }

    /** One spark dropped behind a palm frond or kamuro star: drifting at a fraction of the
     * star's speed, so it hangs back along the star's path and droops away. */
    private fun tailSpark(tail: SparkTail, position: Vector3, velocity: Vector3, now: Double): Int =
        star(position, velocity * tail.carry + randomDirection() * tail.recipe.speed, tail.recipe, tail.color, now)

    /** [n] palm fronds' directions: evenly round the vertical, each tipped out from it by
     * 35-70°, so they rise up and out and gravity bends them over within their burn. */
    private fun palmDirections(n: Int, spin: Double): List<Vector3> = (0 until n).map { i ->
        val polar = random.nextDouble(PALM_MIN_TILT, PALM_MAX_TILT)
        val azimuth = spin + 2.0 * PI * i / n + random.nextDouble(-0.15, 0.15)
        Vector3(sin(polar) * cos(azimuth), cos(polar), sin(polar) * sin(azimuth))
    }

    /** Two unit vectors square to [direction] and to each other. */
    private fun perpendicularPair(direction: Vector3): Pair<Vector3, Vector3> {
        val d = if (direction.length() > 1e-9) direction.normalized() else Vector3(0.0, 1.0, 0.0)
        val helper = if (kotlin.math.abs(d.y) < 0.9) Vector3(0.0, 1.0, 0.0) else Vector3(1.0, 0.0, 0.0)
        val u = d.cross(helper).normalized()
        return u to d.cross(u)
    }

    /** [n] directions evenly round a circle whose plane is tilted at random - a ring burst.
     * The plane's normal is a random direction, so a ring may be seen face-on, edge-on, or
     * anything between. */
    private fun ringDirections(n: Int): List<Vector3> {
        val (u, w) = perpendicularPair(randomDirection())
        val phase = random.nextDouble(0.0, 2.0 * PI)
        return (0 until n).map { i ->
            val a = phase + 2.0 * PI * i / n
            u * cos(a) + w * sin(a)
        }
    }

    private fun randomDirection(): Vector3 {
        val y = random.nextDouble(-1.0, 1.0)
        val a = random.nextDouble(0.0, 2.0 * PI)
        val r = sqrt(1.0 - y * y)
        return Vector3(r * cos(a), y, r * sin(a))
    }

    /** The [i]th of [n] directions spread evenly over a sphere (the Fibonacci / golden-angle
     * spiral), turned by [spin] about the vertical and nudged slightly so it isn't too regular. */
    private fun fibonacciDirection(i: Int, n: Int, spin: Double): Vector3 {
        val y = 1.0 - 2.0 * (i + 0.5) / n
        val polar = acos(y.coerceIn(-1.0, 1.0)) + random.nextDouble(-0.04, 0.04)
        val azimuth = i * GOLDEN_ANGLE + spin + random.nextDouble(-0.04, 0.04)
        return Vector3(sin(polar) * cos(azimuth), cos(polar), sin(polar) * sin(azimuth))
    }

    companion object {
        const val ALL_GROUP = "fireworks"
        const val SHELL_GROUP = "shells"
        const val STAR_GROUP = "stars"
        const val SMOKE_GROUP = "smoke"
        private const val SMOKE_SEED_SALT = 0x5307EL
        /** A smoke puff's nominal mass, kg, and the drag on it, N·s/m: a 2 s time constant, so
         * a puff thrown out at 8 m/s stops within ~16 m and then drifts with the air. */
        const val SMOKE_PUFF_MASS = 1.0
        const val SMOKE_DRAG = 0.5
        /** The breeze carrying the smoke, m/s along +x - across the crowd's view. */
        const val SMOKE_WIND_SPEED = 2.0
        /** How much of a burst's own velocity its smoke keeps. */
        const val SMOKE_CARRY = 0.3
        /** The smoke from a shell's burst charge: a dozen puffs, lasting ~20 s. */
        val BURST_SMOKE = SmokeCloud(puffs = 12, minSpeed = 3.0, maxSpeed = 8.0, minLife = 18.0, maxLife = 24.0)
        /** The smoke left at a mortar by its lift charge: two low puffs, gone sooner. */
        val LAUNCH_SMOKE = SmokeCloud(puffs = 2, minSpeed = 0.5, maxSpeed = 2.0, minLife = 6.0, maxLife = 9.0, flatten = 0.3)
        const val MORTAR_HEIGHT = 0.5
        const val SHELL_MASS = 0.2
        /** The share of shells that whistle on the way up. */
        const val WHISTLE_CHANCE = 0.3
        /** A whistle's length, s, and the gap between it stopping and the burst - from the
         * whistles in a field recording, which ran 1.0-1.15 s and stopped 0.08-0.23 s before
         * their booms. */
        const val WHISTLE_MIN_LENGTH = 0.95
        const val WHISTLE_MAX_LENGTH = 1.15
        const val WHISTLE_MIN_GAP = 0.08
        const val WHISTLE_MAX_GAP = 0.23
        /** How loud a split sub-shell's and a crossette comet's breaks are, next to a shell's. */
        const val SPLIT_BOOM_LOUDNESS = 0.35
        const val CROSSETTE_BOOM_LOUDNESS = 0.2
        /** How long a burst stays in [recentFlashes], s - longer than the scene's flash lasts. */
        const val FLASH_MEMORY = 0.5
        /** How far into its climb a shell's fuse runs out. */
        const val FUSE_FRACTION = 0.95
        private val GOLDEN_ANGLE = PI * (3.0 - sqrt(5.0))

        /** Saturated colours - many overlapping glowing dots wash anything paler out to white. */
        val PALETTE = listOf(
            Color(1.0, 0.12, 0.22), // red
            Color(0.18, 0.42, 1.0), // blue
            Color(1.0, 0.6, 0.12), // orange-gold
            Color(0.2, 1.0, 0.35), // green
            Color(0.72, 0.28, 1.0), // purple
            Color(0.15, 0.85, 1.0), // cyan
        )
        val WILLOW_GOLD = Color(1.0, 0.7, 0.25)
        val GLITTER_GOLD = Color(1.0, 0.78, 0.4)
        val SPARK_WHITE = Color(1.0, 0.95, 0.85)
        /** A split shell's sub-shells, before they burst: bright, near-white comets. */
        val SUBSHELL_COLOR = Color(1.0, 0.9, 0.7)
        val STROBE_WHITE = Color(0.95, 0.97, 1.0)
        /** Kamuro's glitter: a paler, whiter gold than a willow's. */
        val KAMURO_GOLD = Color(1.0, 0.85, 0.6)
        val HORSETAIL_SILVER = Color(0.85, 0.88, 1.0)

        /** How often each burst type is fired - peonies most, the showier types less. */
        val TYPE_WEIGHTS: List<Pair<BurstType, Double>> = listOf(
            BurstType.PEONY to 0.18,
            BurstType.TWO_TONE to 0.12,
            BurstType.WILLOW to 0.08,
            BurstType.RING to 0.08,
            BurstType.CRACKLE to 0.08,
            BurstType.SPLIT to 0.08,
            BurstType.CROSSETTE to 0.07,
            BurstType.STROBE to 0.07,
            BurstType.PALM to 0.07,
            BurstType.KAMURO to 0.06,
            BurstType.HORSETAIL to 0.05,
            BurstType.MULTI_BREAK to 0.06,
        )

        /** A crackling star's sparks, per star: fast, light (so drag stops them within a few
         * metres) and gone in about a tenth of a second. */
        val CRACKLE_RECIPE = StarRecipe(count = 3, speed = 10.0, starMass = 0.002, minBurn = 0.06, maxBurn = 0.16)
        /** The stars each split sub-shell bursts into. */
        val SPLIT_STAR_RECIPE = StarRecipe(count = 22, speed = 13.0, starMass = 0.01, minBurn = 1.0, maxBurn = 1.4)
        /** The four pieces each crossette comet splits into; [StarRecipe.speed] is exact, each
         * piece's speed away from the others. */
        val CROSSETTE_PIECE_RECIPE = StarRecipe(count = 4, speed = 16.0, starMass = 0.01, minBurn = 1.1, maxBurn = 1.5)
        /** How much of a crossette comet's velocity its pieces keep. */
        const val CROSSETTE_CARRY = 0.5
        /** A palm frond's tail sparks: slow, short-lived, light enough for drag to hold them
         * back along the frond's path. */
        val PALM_TAIL_RECIPE = StarRecipe(count = 1, speed = 1.5, starMass = 0.004, minBurn = 1.2, maxBurn = 1.8)
        /** The chance, each step, that a burning frond drops a tail spark. */
        const val PALM_TAIL_CHANCE = 0.6
        /** How much of a frond's velocity its tail sparks start with. */
        const val PALM_TAIL_CARRY = 0.3
        val PALM_TAIL = SparkTail(PALM_TAIL_CHANCE, PALM_TAIL_RECIPE, WILLOW_GOLD, PALM_TAIL_CARRY)
        /** A kamuro star's glitter trail: brief, slow sparks, one every ~25 steps per star -
         * with ~200 stars, several hundred glittering at once. */
        val KAMURO_SPARK_RECIPE = StarRecipe(count = 1, speed = 1.0, starMass = 0.003, minBurn = 0.35, maxBurn = 0.7)
        val KAMURO_TAIL = SparkTail(0.04, KAMURO_SPARK_RECIPE, KAMURO_GOLD, 0.2)
        /** The upward speed a multi-break shell's break gives its inner shell, m/s, and how long
         * that inner shell's fuse runs, s. */
        const val MULTI_BREAK_KICK = 15.0
        const val MULTI_BREAK_MIN_DELAY = 0.6
        const val MULTI_BREAK_MAX_DELAY = 0.8
        val PALM_MIN_TILT = 35.0 * PI / 180.0
        val PALM_MAX_TILT = 70.0 * PI / 180.0

        fun recipeFor(type: BurstType): StarRecipe = when (type) {
            BurstType.PEONY -> StarRecipe(count = 170, speed = 36.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.4)
            BurstType.TWO_TONE -> StarRecipe(count = 180, speed = 38.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.4)
            BurstType.WILLOW -> StarRecipe(count = 120, speed = 42.0, starMass = 0.005, minBurn = 3.5, maxBurn = 4.5)
            BurstType.RING -> StarRecipe(count = 72, speed = 34.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.2)
            BurstType.CRACKLE -> StarRecipe(count = 110, speed = 30.0, starMass = 0.008, minBurn = 1.2, maxBurn = 1.9)
            // The sub-shells: their burn is their fuse, and burning out is their burst.
            BurstType.SPLIT -> StarRecipe(count = 12, speed = 24.0, starMass = 0.03, minBurn = 0.7, maxBurn = 0.95)
            // Comets again: they split as they burn out.
            BurstType.CROSSETTE -> StarRecipe(count = 10, speed = 30.0, starMass = 0.03, minBurn = 0.55, maxBurn = 0.75)
            BurstType.STROBE -> StarRecipe(count = 90, speed = 26.0, starMass = 0.006, minBurn = 2.4, maxBurn = 3.2)
            // Heavy, so drag hardly slows them (a 6 s drag time), and slow enough to top out
            // within 2 s and arc over before they burn out.
            BurstType.PALM -> StarRecipe(count = 7, speed = 20.0, starMass = 0.06, minBurn = 2.2, maxBurn = 2.8)
            // Dense, light enough for drag to stop them within a second or so (a 0.6 s drag
            // time), and long-burning, so the crown hangs.
            BurstType.KAMURO -> StarRecipe(count = 200, speed = 32.0, starMass = 0.006, minBurn = 3.2, maxBurn = 4.0)
            // Thrown out slowly; heavy enough (a 2.5 s drag time) to fall away together.
            BurstType.HORSETAIL -> StarRecipe(count = 90, speed = 7.0, starMass = 0.025, minBurn = 3.5, maxBurn = 4.5)
            // Each break: a smaller peony than a single-break shell's.
            BurstType.MULTI_BREAK -> StarRecipe(count = 100, speed = 30.0, starMass = 0.01, minBurn = 1.6, maxBurn = 2.1)
        }

        /** Terminal speed of a falling shell, where drag balances its weight: √(m·g/k). */
        val SHELL_TERMINAL_SPEED = sqrt(SHELL_MASS * FIREWORKS_GRAVITY / SHELL_DRAG)

        /** The launch speed at which a shell climbing straight up against gravity and quadratic
         * drag tops out at [height]: h = (vt²/2g)·ln(1 + v0²/vt²), solved for v0. */
        fun launchSpeedFor(height: Double): Double {
            val vt = SHELL_TERMINAL_SPEED
            return vt * sqrt(exp(2.0 * FIREWORKS_GRAVITY * height / (vt * vt)) - 1.0)
        }

        /** How long a shell launched straight up at [launchSpeed] takes to stop climbing:
         * t = (vt/g)·atan(v0/vt). */
        fun timeToApex(launchSpeed: Double): Double {
            val vt = SHELL_TERMINAL_SPEED
            return vt / FIREWORKS_GRAVITY * atan(launchSpeed / vt)
        }
    }
}
