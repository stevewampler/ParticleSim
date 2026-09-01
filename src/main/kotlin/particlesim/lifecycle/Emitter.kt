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

/** What one [Emitter.update] call actually did (§9.1's discrete-event channel needs this to turn
 * spawns/evictions into [particlesim.debug.SimEvent]s) — both lists are ids this call created or
 * removed itself, in the order it did so; [evictedIds] is only ever non-empty under
 * [EmitterCapPolicy.EVICT_OLDEST], since [EmitterCapPolicy.STOP] never destroys anything. */
data class EmitResult(val spawnedIds: List<Int>, val evictedIds: List<Int> = emptyList())

/** Everything [Emitter.captureState]/[Emitter.restoreState] round-trip through a checkpoint
 * (§9.5) — see [Emitter.captureState]'s doc comment for what each field is for. */
data class EmitterCheckpointState(
    val name: String,
    val accumulator: Double,
    val liveIds: List<Int>,
    val atCap: Boolean,
    val rngDrawCount: Long,
)

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
    private var rate: ScalarExpr,
    private val position: VectorDistribution,
    private val velocity: VectorDistribution,
    private val mass: ScalarDistribution = ScalarDistribution.Constant(1.0),
    private val radius: ScalarDistribution? = null,
    private val lifetime: ScalarDistribution? = null,
    maxAlive: Int,
    private var capPolicy: EmitterCapPolicy = EmitterCapPolicy.STOP,
    masterSeed: Long,
    private val onWarning: (String) -> Unit = { System.err.println(it) },
) {
    /** §10.4's live-editing read path for the cap - a plain mutable property (unlike [rate]/
     * [capPolicy], which stay `private` and go through [currentRate]/[currentCapPolicy] instead)
     * since existing callers already read this directly (e.g. `SparksStabilityTest`) and there's
     * no derived/time-varying value to compute here the way [currentRate] has to. */
    var maxAlive: Int = maxAlive
        private set

    // Each emitter gets its own independent RNG sub-stream (§11, §14.4), seeded from the run's
    // master seed plus this emitter's stable name — not a shared stream, since which emitter
    // consumes the next value first would depend on iteration/scheduling order, not the seed.
    // CountingRandom (not plain Random) so its exact stream position can be checkpointed
    // (§9.5) and restored on resume — see restoreState/captureState below.
    private val seed = mixSeed(masterSeed, name)
    private var rng = CountingRandom(Random(seed))

    // Fractional spawn budget: `rate` is particles/sec but a step is a tiny fraction of a
    // second, so most steps accumulate less than one whole particle. Carried across steps.
    private var accumulator = 0.0

    // This emitter's own spawned-and-still-alive ids, oldest first — both the live-count
    // source for the cap and the eviction order under EVICT_OLDEST. Self-healing: pruned
    // against `store.contains` each call rather than requiring destruction to notify this
    // emitter, so there's no cross-system contract to forget.
    private val liveIds = ArrayDeque<Int>()
    private var atCap = false

    fun update(store: ParticleStore, groups: Groups, t: Double, dt: Double): EmitResult {
        liveIds.removeAll { !store.contains(it) }
        val spawned = mutableListOf<Int>()
        val evicted = mutableListOf<Int>()

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
                        return EmitResult(spawned, evicted)
                    }
                    EmitterCapPolicy.EVICT_OLDEST -> {
                        val oldest = liveIds.removeFirst()
                        store.destroy(oldest)
                        groups.removeParticle(oldest)
                        evicted += oldest
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
            spawned += id

            accumulator -= 1.0
        }
        return EmitResult(spawned, evicted)
    }

    /** requirements.md §10.4's emitter read path: the live evaluated rate at [t] (see
     * [currentRateSource] below for the companion expression-source read path). Deliberately
     * *not* clamped to zero like [update]'s own internal accumulation does - a pulsing/
     * negative-going expression should be visible as negative, not silently floored just
     * because it's being displayed. */
    fun currentRate(t: Double): Double = rate.evaluate(t)

    /** §10.4's new "show the current expression source" requirement - `null` when [rate] wasn't
     * set from a parsed expression string (its constructor default, or a native DSL lambda
     * passed directly to [ScalarExpr.of]). */
    fun currentRateSource(): String? = rate.source

    fun currentCapPolicy(): EmitterCapPolicy = capPolicy

    /** §10.4's rate live-editing write path - an outright replace, same convention as
     * `ParticleStore.setMass`/`setRadius`: a full replace is already the time-variance-preserving
     * option once the replacement can itself be a dynamic expression, so there's no separate
     * override layer to add. Only ever future-spawn-affecting, never retroactive - already-alive
     * particles this emitter spawned under the old rate are untouched, matching this section's
     * own "no retroactive edits" framing in requirements.md §10.4. */
    fun setRate(expr: ScalarExpr) {
        rate = expr
    }

    /** Rejects a non-positive cap: zero or fewer would mean this emitter can never spawn another
     * particle, and [EmitterCapPolicy.STOP] would then silently and permanently block emission
     * with no way back except another edit - the same "reject rather than accept a value that
     * breaks the class's own invariants" stance `ParticleStore.setMass` takes on non-positive
     * mass. */
    fun setMaxAlive(value: Int): Boolean {
        if (value <= 0) return false
        maxAlive = value
        return true
    }

    fun setCapPolicy(policy: EmitterCapPolicy) {
        capPolicy = policy
    }

    /** Snapshots everything about this emitter that isn't recoverable from the static
     * scenario definition alone (§9.5): the fractional spawn-rate accumulator's phase, this
     * emitter's own spawned-and-still-alive ids in spawn order (needed for correct future
     * [EmitterCapPolicy.EVICT_OLDEST] behavior — a generic *unordered* group-membership set
     * wouldn't preserve that), whether it had already warned about hitting its cap, and its
     * RNG sub-stream's exact position. */
    fun captureState(): EmitterCheckpointState = EmitterCheckpointState(
        name = name,
        accumulator = accumulator,
        liveIds = liveIds.toList(),
        atCap = atCap,
        rngDrawCount = rng.drawCount,
    )

    /** Restores state captured by [captureState] onto a freshly-constructed `Emitter` (same
     * `name`/`masterSeed`, so the same base RNG seed) — the checkpoint-resume counterpart.
     * Must be called before any real [update] call, since it fast-forwards a *fresh*
     * `CountingRandom` from its zero state rather than mutating the current stream position. */
    fun restoreState(state: EmitterCheckpointState) {
        require(state.name == name) { "checkpoint state is for emitter '${state.name}', this is '$name'" }
        accumulator = state.accumulator
        liveIds.clear()
        liveIds.addAll(state.liveIds)
        atCap = state.atCap
        rng = CountingRandom.restore(seed, state.rngDrawCount)
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
