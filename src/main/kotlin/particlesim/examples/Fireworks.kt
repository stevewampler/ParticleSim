package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.lifecycle.DestructionSystem
import particlesim.physics.Drag
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
import kotlin.random.Random

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
 *   of its own a moment later. */
enum class BurstType { PEONY, TWO_TONE, WILLOW, RING, CRACKLE, SPLIT }

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
data class Shell(val id: Int, val burstTime: Double, val breakHeight: Double, val type: BurstType, val colors: List<Color>)

/** A shell bursting at [position] at [time], lighting the ground in [color] - the scene turns
 * each one into a brief point-light flash. */
data class BurstFlash(val position: Vector3, val time: Double, val color: Color)

/** What one [FireworksShow.step] created and destroyed - for the scene's §9.1 event channel. */
data class FireworksStepResult(val spawnedIds: List<Int>, val destroyedIds: List<Int>)

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
 *   [BurstType.CRACKLE] star pops into sparks, and a [BurstType.SPLIT] sub-shell bursts again.
 * - Every shell's burst is also recorded as a [BurstFlash] for a moment ([recentFlashes]).
 *
 * Break heights (90-140 m), star speeds (~30-42 m/s), burn times (~2 s, willows ~4 s) and every
 * burst type's star counts are modelling choices, picked to look right from the scene's
 * viewpoint, not sourced figures.
 *
 * Launching isn't an [particlesim.lifecycle.Emitter]: bursts appear where a shell happens to be
 * when its fuse runs out, which a rate-driven emitter at a fixed place can't express. So the
 * launcher doesn't show up under the viewer's EMITTERS, can't be edited live, and isn't part of a
 * §9.5 checkpoint. Its randomness comes from one seeded [Random], so a run repeats exactly (§11).
 */
class FireworksShow(
    seed: Long = 1L,
    /** Mortars sit at random points this far either side of the origin along x, m. */
    val launchHalfWidth: Double = 50.0,
    val minBreakHeight: Double = 90.0,
    val maxBreakHeight: Double = 140.0,
    /** Time between one launch (or salvo) and the next, s. */
    val minLaunchInterval: Double = 0.6,
    val maxLaunchInterval: Double = 1.6,
    /** When the first launch goes up, s - [Double.POSITIVE_INFINITY] for none (only [launch]). */
    firstLaunchTime: Double = 0.5,
) {
    val store = ParticleStore()
    val groups = Groups()
    private val random = Random(seed)
    private val integrator = Integrator()
    private val destruction = DestructionSystem()

    val gravity = UniformGravity(ALL_GROUP, Vector3(0.0, -FIREWORKS_GRAVITY, 0.0), name = "gravity")
    val drag = Drag(STAR_GROUP, coefficient = STAR_DRAG, name = "star-drag")
    val shellDrag = Drag(SHELL_GROUP, coefficient = SHELL_DRAG, quadratic = true, name = "shell-drag")
    val forces: List<Force> = listOf(gravity, drag, shellDrag)

    private val shells = mutableListOf<Shell>()
    private val starColors = HashMap<Int, Color>()
    private val glitter = HashSet<Int>()
    /** Stars that do something when they burn out, by id: the colours a split sub-shell bursts
     * into, or [CRACKLE_SPARKS] for a crackling star. */
    private val burnoutEffects = HashMap<Int, List<Color>>()
    private val flashes = ArrayDeque<BurstFlash>()
    private var nextLaunchTime = firstLaunchTime

    /** Shells still on their way up. */
    val liveShells: List<Shell> get() = shells.toList()

    /** Every live star's base colour, by id - the scene dims it as the star burns out. */
    val starColorsById: Map<Int, Color> get() = starColors

    /** The live [BurstType.CRACKLE] stars, which the scene draws twinkling. */
    val glitterIds: Set<Int> get() = glitter

    /** Shell bursts in the last [FLASH_MEMORY] seconds, oldest first. */
    val recentFlashes: List<BurstFlash> get() = flashes.toList()

    /** Advances the display from [t] by [dt]: flight, then bursts and burn-outs, then launches -
     * destroy before create, as in the other particle-effect scenes. */
    fun step(t: Double, dt: Double): FireworksStepResult {
        integrator.step(store, groups, forces, emptyList(), t, dt)
        val now = t + dt

        // Read each bursting shell before it's destroyed below.
        val bursting = shells.filter { now >= it.burstTime }
        val bursts = bursting.map { Triple(it, store.position(it.id), store.velocity(it.id)) }
        shells.removeAll(bursting)

        // Stars with a burn-out effect, read the same way: whichever of them burn out below
        // act from where they were.
        val effectStates = burnoutEffects.keys.associateWith { Pair(store.position(it), store.velocity(it)) }

        // Burnt-out stars go by lifetime; bursting shells go explicitly.
        val destroyed = destruction.resolve(store, groups, forces, now, dt, explicitIds = bursting.map { it.id }.toSet())
        val spawned = mutableListOf<Int>()
        for (id in destroyed.destroyedIds) {
            starColors.remove(id)
            glitter.remove(id)
            val effect = burnoutEffects.remove(id) ?: continue
            val (position, velocity) = effectStates.getValue(id)
            spawned += if (effect === CRACKLE_SPARKS) crackle(position, velocity, now) else splitBurst(effect, position, velocity, now)
        }

        for ((shell, position, velocity) in bursts) {
            spawned += burst(shell, position, velocity, now)
            flashes.addLast(BurstFlash(position, now, shell.colors.first()))
        }
        while (flashes.isNotEmpty() && now - flashes.first().time > FLASH_MEMORY) flashes.removeFirst()

        if (now >= nextLaunchTime) {
            val salvo = when (random.nextDouble()) {
                in 0.0..0.7 -> 1
                in 0.7..0.9 -> 2
                else -> 3
            }
            repeat(salvo) { spawned += launch(now) }
            nextLaunchTime = now + random.nextDouble(minLaunchInterval, maxLaunchInterval)
        }
        return FireworksStepResult(spawned, destroyed.destroyedIds)
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
        shells += Shell(id, now + fuse, breakHeight, type, colorsFor(type))
        return id
    }

    private fun randomType(): BurstType = when (random.nextDouble()) {
        in 0.0..0.3 -> BurstType.PEONY
        in 0.3..0.48 -> BurstType.TWO_TONE
        in 0.48..0.6 -> BurstType.WILLOW
        in 0.6..0.74 -> BurstType.RING
        in 0.74..0.87 -> BurstType.CRACKLE
        else -> BurstType.SPLIT
    }

    private fun colorsFor(type: BurstType): List<Color> = when (type) {
        BurstType.PEONY, BurstType.RING, BurstType.SPLIT -> listOf(PALETTE[random.nextInt(PALETTE.size)])
        BurstType.TWO_TONE -> {
            val first = random.nextInt(PALETTE.size)
            val second = (first + 1 + random.nextInt(PALETTE.size - 1)) % PALETTE.size
            listOf(PALETTE[first], PALETTE[second])
        }
        BurstType.WILLOW -> listOf(WILLOW_GOLD)
        BurstType.CRACKLE -> listOf(GLITTER_GOLD)
    }

    private fun burst(shell: Shell, position: Vector3, velocity: Vector3, now: Double): List<Int> {
        val recipe = recipeFor(shell.type)
        // A random turn of the whole sphere, so bursts don't all share the same star pattern.
        val spin = random.nextDouble(0.0, 2.0 * PI)
        val directions = when (shell.type) {
            BurstType.RING -> ringDirections(recipe.count)
            else -> (0 until recipe.count).map { fibonacciDirection(it, recipe.count, spin) }
        }
        return directions.mapIndexed { i, direction ->
            val color = if (shell.type == BurstType.SPLIT) SUBSHELL_COLOR else shell.colors[i % shell.colors.size]
            val id = star(position, velocity + direction * (recipe.speed * random.nextDouble(0.92, 1.08)), recipe, color, now)
            when (shell.type) {
                BurstType.CRACKLE -> { glitter += id; burnoutEffects[id] = CRACKLE_SPARKS }
                BurstType.SPLIT -> burnoutEffects[id] = shell.colors
                else -> {}
            }
            id
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

    /** [n] directions evenly round a circle whose plane is tilted at random - a ring burst.
     * The plane's normal is a random direction, so a ring may be seen face-on, edge-on, or
     * anything between. */
    private fun ringDirections(n: Int): List<Vector3> {
        val normal = randomDirection()
        // Two unit vectors spanning the ring's plane.
        val helper = if (kotlin.math.abs(normal.y) < 0.9) Vector3(0.0, 1.0, 0.0) else Vector3(1.0, 0.0, 0.0)
        val u = normal.cross(helper).normalized()
        val w = normal.cross(u)
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
        const val MORTAR_HEIGHT = 0.5
        const val SHELL_MASS = 0.2
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
        /** The marker in burnoutEffects for a crackling star (compared by identity). */
        private val CRACKLE_SPARKS = listOf(SPARK_WHITE)

        /** A crackling star's sparks, per star: fast, light (so drag stops them within a few
         * metres) and gone in about a tenth of a second. */
        val CRACKLE_RECIPE = StarRecipe(count = 3, speed = 10.0, starMass = 0.002, minBurn = 0.06, maxBurn = 0.16)
        /** The stars each split sub-shell bursts into. */
        val SPLIT_STAR_RECIPE = StarRecipe(count = 22, speed = 13.0, starMass = 0.01, minBurn = 1.0, maxBurn = 1.4)

        fun recipeFor(type: BurstType): StarRecipe = when (type) {
            BurstType.PEONY -> StarRecipe(count = 170, speed = 36.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.4)
            BurstType.TWO_TONE -> StarRecipe(count = 180, speed = 38.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.4)
            BurstType.WILLOW -> StarRecipe(count = 120, speed = 42.0, starMass = 0.005, minBurn = 3.5, maxBurn = 4.5)
            BurstType.RING -> StarRecipe(count = 72, speed = 34.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.2)
            BurstType.CRACKLE -> StarRecipe(count = 110, speed = 30.0, starMass = 0.008, minBurn = 1.2, maxBurn = 1.9)
            // The sub-shells: their burn is their fuse, and burning out is their burst.
            BurstType.SPLIT -> StarRecipe(count = 12, speed = 24.0, starMass = 0.03, minBurn = 0.7, maxBurn = 0.95)
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
