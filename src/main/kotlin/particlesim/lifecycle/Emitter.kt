package particlesim.lifecycle

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import kotlin.random.Random

/** What happens when an emitter's spawn rate would exceed [Emitter.maxAlive] (§14.1). */
enum class EmitterCapPolicy {
    /** Stop spawning (and stop accumulating unspent spawn budget) until the count drops. */
    STOP,

    /** Destroy the oldest particle this emitter has spawned to make room for the new one,
     * keeping the live count pinned at the cap rather than blocking emission. */
    EVICT_OLDEST,
}

/**
 * The primary spawning mechanism (§14.1): a spawn rate (particles/sec, expression-capable so
 * it can ramp or burst) plus initial-property distributions for each new particle's position,
 * velocity, mass, radius, and lifetime. Spawned particles are added to [group] automatically,
 * so any force/constraint/collision rule/renderer already targeting that group picks them up
 * with no extra wiring.
 *
 * Call [update] once per simulation step, **before** [DestructionSystem.resolve] runs for
 * that same step — a particle spawned this step should be simulated (and only then be
 * eligible for lifetime/condition/collision destruction) starting *next* step, not destroyed
 * before it's ever been integrated once. Composing `destroy → emit` per step, not `emit →
 * destroy`, is what keeps that ordering; this class doesn't enforce it, the caller's loop does.
 */
class Emitter(
    val name: String,
    val group: String,
    private val rate: ScalarExpr,
    private val position: VectorDistribution,
    private val velocity: VectorDistribution,
    private val mass: ScalarDistribution = ScalarDistribution.Constant(1.0),
    private val radius: ScalarDistribution? = null,
    private val lifetime: ScalarDistribution? = null,
    val maxAlive: Int,
    private val capPolicy: EmitterCapPolicy = EmitterCapPolicy.STOP,
    masterSeed: Long,
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) {
    // Each emitter gets its own independent RNG sub-stream (§11, §14.4), seeded from the run's
    // master seed plus this emitter's stable name — not a shared stream, since which emitter
    // consumes the next value first would depend on iteration/scheduling order, not the seed.
    private val rng = Random(mixSeed(masterSeed, name))

    // Fractional spawn budget: `rate` is particles/sec but a step is a tiny fraction of a
    // second, so most steps accumulate less than one whole particle. Carried across steps.
    private var accumulator = 0.0

    // This emitter's own spawned-and-still-alive ids, oldest first — both the live-count
    // source for the cap and the eviction order under EVICT_OLDEST. Self-healing: pruned
    // against `store.contains` each call rather than requiring destruction to notify this
    // emitter, so there's no cross-system contract to forget.
    private val liveIds = ArrayDeque<Int>()
    private var atCap = false

    fun update(store: ParticleStore, groups: Groups, t: Double, dt: Double) {
        liveIds.removeAll { !store.contains(it) }

        accumulator += rate.evaluate(t).coerceAtLeast(0.0) * dt
        while (accumulator >= 1.0) {
            if (liveIds.size >= maxAlive) {
                if (!atCap) {
                    onWarning("emitter '$name' hit its particle cap ($maxAlive)")
                    atCap = true
                }
                when (capPolicy) {
                    EmitterCapPolicy.STOP -> {
                        // Don't let unspent budget pile up while blocked — otherwise clearing
                        // the cap later would release a burst instead of resuming the steady rate.
                        accumulator = accumulator.coerceAtMost(1.0)
                        return
                    }
                    EmitterCapPolicy.EVICT_OLDEST -> {
                        val oldest = liveIds.removeFirst()
                        store.destroy(oldest)
                        groups.removeParticle(oldest)
                    }
                }
            } else {
                atCap = false
            }

            val id = store.create(
                position = position.sample(rng),
                velocity = velocity.sample(rng),
                mass = ScalarExpr.of(mass.sample(rng)),
                radius = radius?.let { ScalarExpr.of(it.sample(rng)) },
                spawnTime = t,
                lifetime = lifetime?.let { ScalarExpr.of(it.sample(rng)) },
            )
            groups.add(group, id)
            liveIds.addLast(id)

            accumulator -= 1.0
        }
    }

    companion object {
        /**
         * SplitMix64-style finalizer mix, applied to `masterSeed` offset by `name`'s (stable,
         * spec-guaranteed) hash scaled by an odd golden-ratio constant. Plain `masterSeed xor
         * name.hashCode()` was tried first and rejected: `hashCode()` is only 32 bits, so XOR
         * only changes the low half of the seed, and two [java.util.Random]/[kotlin.random.Random]
         * streams seeded with close values can produce correlated early output — the same
         * "different emitters, suspiciously similar spawn sequences" bug determinism is meant
         * to rule out, just disguised as near-duplicates instead of exact ones.
         */
        private const val GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15UL
        private const val SPLITMIX_MULT_1 = 0xBF58476D1CE4E5B9UL
        private const val SPLITMIX_MULT_2 = 0x94D049BB133111EBUL

        internal fun mixSeed(masterSeed: Long, name: String): Long {
            var z = masterSeed + name.hashCode().toLong() * GOLDEN_RATIO_64.toLong()
            z = (z xor (z ushr 30)) * SPLITMIX_MULT_1.toLong()
            z = (z xor (z ushr 27)) * SPLITMIX_MULT_2.toLong()
            return z xor (z ushr 31)
        }
    }
}
