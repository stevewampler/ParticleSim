package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Flocking
import particlesim.physics.Force
import particlesim.physics.Pursuit
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A pre-roost starling murmuration (§5.6's [Flocking] worked example): [birdCount] starlings
 * circling over their roost, with an optional peregrine falcon chasing them.
 *
 * Real units - metres, seconds, kilograms:
 * - A starling weighs about 75-90 g ([STARLING_MASS]).
 * - Cruise speed 10 m/s, as in Hildenbrandt, Carere & Hemelrijk's starling model (2010).
 * - 7 topological neighbours, after Ballerini et al. (PNAS 2008), who measured about 6-7.
 * - Nearest neighbours end up about 1.8 m apart (`separationDistance` 2 m). That's a modelling
 *   choice, not a measurement: Ballerini et al. ("Empirical investigation of starling flocks",
 *   Animal Behaviour 76(1), 2008) report birds keeping a minimum distance comparable to their
 *   wingspan, but at that spacing 1500 birds pack into a ball ~7 m across; looser spacing lets a
 *   flock this small spread into a murmuration's shape.
 * - Each bird is blind in a 90° cone behind it ([STARLING_BLIND_ANGLE]), after Hemelrijk &
 *   Hildenbrandt (PLOS ONE 2011), and banks at most [STARLING_MAX_BANK] into a turn.
 * - The falcon chases at twice the starlings' cruise speed - a peregrine's level pursuit is
 *   faster than a starling's, though nothing like its 300+ km/h stoop.
 *
 * Real murmurations run from hundreds to hundreds of thousands of birds; the default stays in
 * the low thousands for the step rate (about 9 ms a step at 1500).
 *
 * The birds start in a loose ball [displayAltitude] up over the roost (at [placement]'s offset),
 * all heading roughly the same way with some scatter in direction, from a fixed [seed] so a run
 * repeats exactly (§11). The falcon starts
 * well off to one side, a little above them, and makes its first pass once its first rest is over.
 */
data class MurmurationScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val flocking: Flocking,
    val pursuit: Pursuit?,
    val birdIds: List<Int>,
    val falconId: Int?,
    val birdGroup: String,
    val falconGroup: String,
    val roost: Vector3,
)

/** Nothing here is stiff: the steering terms act over tenths of a second at the fastest, so the
 * step can be far longer than a structure's - one viewer frame (the viewer steps at 60 frames/s,
 * so this runs exactly in real time). */
const val MURMURATION_DT = 1.0 / 60.0

/** How far from the roost (horizontally) the flock starts, m. */
const val FLOCK_START_OFFSET = 40.0

/** The falcon's hunting bouts and the rests between them, s. */
const val FALCON_HUNT_SECONDS = 8.0
const val FALCON_REST_SECONDS = 12.0

/** How far above the display the falcon waits between passes, m. */
const val FALCON_WAIT_HEIGHT = 50.0

/** The blind cone behind a starling, degrees: 2 × 45° in Hemelrijk & Hildenbrandt (2011). */
const val STARLING_BLIND_ANGLE = 90.0

/** The steepest a starling banks into a turn, degrees. A modelling choice, not a measurement: at
 * 10 m/s in level flight it allows turns down to about a 6 m radius. */
const val STARLING_MAX_BANK = 60.0

/** A European starling's mass, kg. */
const val STARLING_MASS = 0.08

/** A peregrine falcon's mass, kg (females run larger, ~1 kg). */
const val FALCON_MASS = 0.8

fun buildMurmuration(
    birdCount: Int = 1500,
    withFalcon: Boolean = true,
    displayAltitude: Double = 60.0,
    seed: Long = 1L,
    store: ParticleStore = ParticleStore(),
    groups: Groups = Groups(),
    placement: ShapePlacement = ShapePlacement(),
): MurmurationScenario {
    val birdGroup = placement.name("starlings")
    val falconGroup = placement.name("falcon")
    val roost = placement.offset + Vector3(0.0, displayAltitude, 0.0)
    val random = Random(seed)

    // A ball sized so each bird has a few cubic metres to itself (looser than the flock will
    // settle to), heading along +x with up to ~30° of scatter.
    // Off to one side of the roost, flying across it: starting at the roost itself, heading
    // straight out, the roost's pull would only swing the flock back and forth through it.
    val start = roost + Vector3(0.0, 0.0, -FLOCK_START_OFFSET)
    val ballRadius = cbrt(birdCount * 4.0 / (4.0 / 3.0 * Math.PI))
    val birdIds = (0 until birdCount).map {
        var offset: Vector3
        do {
            offset = Vector3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
        } while (offset.lengthSquared() > 1.0)
        val heading = random.nextDouble(-0.5, 0.5)
        val climb = random.nextDouble(-0.15, 0.15)
        val velocity = Vector3(cos(heading) * cos(climb), sin(climb), sin(heading) * cos(climb)) * 10.0
        store.create(position = start + offset * ballRadius, velocity = velocity, mass = ScalarExpr.of(STARLING_MASS))
            .also { groups.add(birdGroup, it) }
    }

    val flocking = Flocking(
        group = birdGroup,
        roostCenter = roost,
        predatorGroup = if (withFalcon) falconGroup else null,
        blindAngle = STARLING_BLIND_ANGLE,
        maxBankAngle = STARLING_MAX_BANK,
        name = placement.name("flocking"),
    )

    var falconId: Int? = null
    var pursuit: Pursuit? = null
    if (withFalcon) {
        falconId = store.create(position = roost + Vector3(0.0, 10.0, -150.0), velocity = Vector3(12.0, 0.0, 0.0), mass = ScalarExpr.of(FALCON_MASS))
            .also { groups.add(falconGroup, it) }
        pursuit = Pursuit(falconGroup, birdGroup, huntSeconds = FALCON_HUNT_SECONDS, restSeconds = FALCON_REST_SECONDS,
            restPoint = roost + Vector3(0.0, FALCON_WAIT_HEIGHT, 0.0), name = placement.name("pursuit"))
    }

    return MurmurationScenario(
        store = store,
        groups = groups,
        forces = listOfNotNull(flocking, pursuit),
        flocking = flocking,
        pursuit = pursuit,
        birdIds = birdIds,
        falconId = falconId,
        birdGroup = birdGroup,
        falconGroup = falconGroup,
        roost = roost,
    )
}
