package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.lifecycle.DestructionSystem
import particlesim.lifecycle.Emitter
import particlesim.lifecycle.EmitterCapPolicy
import particlesim.lifecycle.ScalarDistribution
import particlesim.lifecycle.VectorDistribution
import particlesim.physics.Drag
import particlesim.physics.Force
import particlesim.physics.UniformGravity
import kotlin.math.sin

/**
 * A campfire: a continuous flame of small short-lived particles licking upward and outward.
 * At this rate and lifetime, the population equilibrates well below [Emitter.maxAlive] (a
 * generous safety ceiling, not a target) purely through lifetime expiry — but unlike
 * [buildSparks]'s fountain, a fire should never visibly stop and gap while draining if that
 * ceiling ever is hit (a rate spike, a slow frame), so it uses [EmitterCapPolicy.EVICT_OLDEST]
 * (recycle the oldest particle) rather than [EmitterCapPolicy.STOP].
 *
 * Buoyancy is modeled the same way gravity is (§3): a uniform, mass-independent acceleration,
 * just pointed up instead of down and much weaker than real thermal buoyancy would be relative
 * to real flame mass, since what matters here is the visual rise rate, not a physically-derived
 * one. [Drag] slows and randomizes that rise as particles spread apart, and the emitter's own
 * [VectorDistribution.PointWithSpread] velocity — a fresh random cone sample per particle,
 * layered across many overlapping short-lived spawns — is what gives the flame its flicker,
 * the same trick [buildSparks] uses rather than a dedicated turbulence force.
 */
data class FireScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val emitter: Emitter,
    val destruction: DestructionSystem,
)

const val FIRE_DT = 1e-3

fun buildFire(masterSeed: Long = 1L): FireScenario {
    val store = ParticleStore()
    val groups = Groups()

    val emitter = Emitter(
        name = "flame",
        group = "fire",
        // A pulsing rate on top of the base, the same "bursts, ramps" idea `buildSparks` uses
        // for its fountain — here it reads as a flame guttering rather than a steady hiss.
        rate = ScalarExpr.of { t -> 160.0 + 40.0 * sin(t * 3.0) },
        // A small base area, like a compact bed of embers rather than a single point.
        position = VectorDistribution.UniformSphere(Vector3(0.0, 0.05, 0.0), 0.12),
        velocity = VectorDistribution.PointWithSpread(
            direction = Vector3(0.0, 1.0, 0.0),
            spreadAngleRadians = Math.toRadians(30.0),
            minMagnitude = 0.6,
            maxMagnitude = 1.4,
        ),
        mass = ScalarDistribution.Constant(0.01),
        radius = ScalarDistribution.UniformRange(0.04, 0.09),
        lifetime = ScalarDistribution.UniformRange(0.4, 0.9),
        maxAlive = 400,
        capPolicy = EmitterCapPolicy.EVICT_OLDEST,
        masterSeed = masterSeed,
    )

    val buoyancy = UniformGravity("fire", Vector3(0.0, 1.6, 0.0), name = "buoyancy")
    // Drag is a force (F = -coefficient * v), not an acceleration - at this particle's small
    // 0.01 kg mass, a coefficient much above ~0.02-0.03 decelerates it to a standstill within
    // milliseconds (time constant = mass / coefficient) rather than letting buoyancy and the
    // emitter's own spray-cone velocity actually carry it upward and outward before it expires.
    val drag = Drag("fire", coefficient = 0.02, name = "drag")

    // No collider/bounds destroy rule (unlike `buildSparks`'s floor/off-bounds pair): nothing
    // for a rising, decelerating flame particle to hit, and its short lifetime already keeps
    // it well within a reasonable region before it expires. Lifetime expiry alone is enough.
    val destruction = DestructionSystem()

    return FireScenario(store, groups, listOf(buoyancy, drag), emitter, destruction)
}
