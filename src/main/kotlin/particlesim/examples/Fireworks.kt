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
import kotlin.math.cos
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

/** The kinds of shell [FireworksShow] fires - the shapes in a typical display:
 * - [PEONY]: a round burst of stars all one colour.
 * - [TWO_TONE]: a round burst of stars in two colours, mixed through each other.
 * - [WILLOW]: gold stars that slow quickly, burn long and droop. */
enum class BurstType { PEONY, TWO_TONE, WILLOW }

/** The look and flight of one burst type's stars. [starMass] sets how quickly drag slows them
 * ([STAR_DRAG]); all values are modelling choices tuned by eye, not measured from real shells. */
data class StarRecipe(
    val count: Int,
    val speed: Double,
    val starMass: Double,
    val minBurn: Double,
    val maxBurn: Double,
)

/** A shell on its way up, and what it will burst into. */
data class Shell(val id: Int, val burstTime: Double, val type: BurstType, val colors: List<Color>)

/** What one [FireworksShow.step] created and destroyed - for the scene's §9.1 event channel. */
data class FireworksStepResult(val spawnedIds: List<Int>, val destroyedIds: List<Int>)

/**
 * A continuous fireworks display: shells launched from mortars spread along the ground, each
 * rising to its break height and bursting into stars that spread, slow under drag, droop under
 * gravity and burn out.
 *
 * Built from the same pieces as the other particle-effect scenes, in metres and seconds:
 * - Shells fly under gravity alone ([FIREWORKS_GRAVITY]), launched straight up (with a little
 *   tilt) at the speed that peaks at their break height, and burst on a time fuse just before
 *   the top. Real shells also feel strong drag on the way up; leaving it out keeps the fuse
 *   arithmetic exact.
 * - Each burst replaces its shell with stars ([BurstType], [StarRecipe]), sent out in an even
 *   spread of directions (a jittered Fibonacci sphere, so the burst is round) on top of the
 *   shell's own velocity at the moment it bursts.
 * - Stars feel gravity and linear [Drag]; each burns for a set time ([particlesim.core.ParticleStore]
 *   lifetime, §14.2) and is then destroyed.
 *
 * Break heights (90-140 m), star speeds (~36-42 m/s) and burn times (~2 s, willows ~4 s) are
 * modelling choices, picked to look right from the scene's viewpoint, not sourced figures.
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
    val forces: List<Force> = listOf(gravity, drag)

    private val shells = mutableListOf<Shell>()
    private val starColors = HashMap<Int, Color>()
    private var nextLaunchTime = firstLaunchTime

    /** Shells still on their way up. */
    val liveShells: List<Shell> get() = shells.toList()

    /** Every live star's base colour, by id - the scene dims it as the star burns out. */
    val starColorsById: Map<Int, Color> get() = starColors

    /** Advances the display from [t] by [dt]: flight, then bursts and burn-outs, then launches -
     * destroy before create, as in the other particle-effect scenes. */
    fun step(t: Double, dt: Double): FireworksStepResult {
        integrator.step(store, groups, forces, emptyList(), t, dt)
        val now = t + dt

        // Read each bursting shell before it's destroyed below.
        val bursting = shells.filter { now >= it.burstTime }
        val bursts = bursting.map { Triple(it, store.position(it.id), store.velocity(it.id)) }
        shells.removeAll(bursting)

        // Burnt-out stars go by lifetime; bursting shells go explicitly.
        val destroyed = destruction.resolve(store, groups, forces, now, dt, explicitIds = bursting.map { it.id }.toSet())
        for (id in destroyed.destroyedIds) starColors.remove(id)

        val spawned = mutableListOf<Int>()
        for ((shell, position, velocity) in bursts) spawned += burst(shell, position, velocity, now)

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
        // v² = 2gh reaches exactly breakHeight at the top of the climb, v/g seconds later; the
        // fuse runs out just before then.
        val launchSpeed = sqrt(2.0 * FIREWORKS_GRAVITY * breakHeight)
        val fuse = FUSE_FRACTION * launchSpeed / FIREWORKS_GRAVITY
        val x = if (launchHalfWidth > 0.0) random.nextDouble(-launchHalfWidth, launchHalfWidth) else 0.0
        val position = Vector3(x, MORTAR_HEIGHT, random.nextDouble(-15.0, 15.0))
        val velocity = Vector3(random.nextDouble(-2.0, 2.0), launchSpeed, random.nextDouble(-2.0, 2.0))
        val id = store.create(position = position, velocity = velocity, mass = ScalarExpr.of(SHELL_MASS), spawnTime = now)
        groups.add(ALL_GROUP, id)
        groups.add(SHELL_GROUP, id)
        shells += Shell(id, now + fuse, type, colorsFor(type))
        return id
    }

    private fun randomType(): BurstType = when (random.nextDouble()) {
        in 0.0..0.5 -> BurstType.PEONY
        in 0.5..0.8 -> BurstType.TWO_TONE
        else -> BurstType.WILLOW
    }

    private fun colorsFor(type: BurstType): List<Color> = when (type) {
        BurstType.PEONY -> listOf(PALETTE[random.nextInt(PALETTE.size)])
        BurstType.TWO_TONE -> {
            val first = random.nextInt(PALETTE.size)
            val second = (first + 1 + random.nextInt(PALETTE.size - 1)) % PALETTE.size
            listOf(PALETTE[first], PALETTE[second])
        }
        BurstType.WILLOW -> listOf(WILLOW_GOLD)
    }

    private fun burst(shell: Shell, position: Vector3, velocity: Vector3, now: Double): List<Int> {
        val recipe = recipeFor(shell.type)
        // A random turn of the whole sphere, so bursts don't all share the same star pattern.
        val spin = random.nextDouble(0.0, 2.0 * PI)
        return (0 until recipe.count).map { i ->
            val direction = fibonacciDirection(i, recipe.count, spin)
            val speed = recipe.speed * random.nextDouble(0.92, 1.08)
            val burn = random.nextDouble(recipe.minBurn, recipe.maxBurn)
            val id = store.create(
                position = position,
                velocity = velocity + direction * speed,
                mass = ScalarExpr.of(recipe.starMass),
                spawnTime = now,
                lifetime = ScalarExpr.of(burn),
            )
            groups.add(ALL_GROUP, id)
            groups.add(STAR_GROUP, id)
            starColors[id] = shell.colors[i % shell.colors.size]
            id
        }
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

        fun recipeFor(type: BurstType): StarRecipe = when (type) {
            BurstType.PEONY -> StarRecipe(count = 170, speed = 36.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.4)
            BurstType.TWO_TONE -> StarRecipe(count = 180, speed = 38.0, starMass = 0.01, minBurn = 1.8, maxBurn = 2.4)
            BurstType.WILLOW -> StarRecipe(count = 120, speed = 42.0, starMass = 0.005, minBurn = 3.5, maxBurn = 4.5)
        }
    }
}
