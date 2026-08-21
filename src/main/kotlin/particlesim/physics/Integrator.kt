package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService

/** Thrown when a particle's position or velocity blows up to NaN/Infinity (§13.2). */
class BlowUpException(message: String) : RuntimeException(message)

/**
 * Result of one [Integrator.step]. [brokenForces] (§5.4) is empty on almost every step — it's
 * the set of breakable forces whose threshold was exceeded by the state at the *start* of
 * this step. They still contributed their force this step (removal takes effect starting
 * next step, never retroactively), so the caller is responsible for dropping them from the
 * active force list before calling [Integrator.step] again — the integrator itself is
 * stateless and doesn't own the force list between calls.
 */
data class StepResult(val brokenForces: List<Force>)

/**
 * Advances a simulation by one fixed timestep using semi-implicit (symplectic) Euler (§8,
 * §13.1): `v += (F/m)·dt` then `x += v·dt`. Explicit Euler is deliberately not offered — it's
 * unstable for anything involving springs (§13.1).
 *
 * Force accumulation is chunked (§9.3, §11): a fixed number of [chunkCount] chunks, each with
 * its own private [ChunkAccumulator], merged in fixed chunk-index order regardless of which
 * chunk's work actually finished when. When [executor] is non-null, each chunk's work is
 * submitted to it and joined before the merge — the merge itself is untouched, since it was
 * already written in fixed index order rather than completion order. [chunkCount] and the
 * executor's own thread/pool size are deliberately independent (§9.3): chunk count is what
 * determinism is keyed on and must stay fixed across runs/machines, while how many threads
 * actually service those chunks can be anything, including fewer threads than chunks (some
 * threads process more than one, sequentially) or more.
 *
 * The executor is caller-owned, not created or shut down here — an `Integrator` doesn't know
 * whether it's one of many short-lived instances (most tests) or the one long-lived instance
 * a real engine loop would want a single shared pool across (Phase 8's later interactive/batch
 * modes). `executor = null` (the default) runs every chunk sequentially on the calling thread,
 * identical to every pre-Phase-8 call site — this is a strictly additive capability, not a
 * behavior change for existing callers.
 */
class Integrator(
    private val chunkCount: Int = DEFAULT_CHUNK_COUNT,
    private val executor: ExecutorService? = null,
) {

    fun step(
        store: ParticleStore,
        groups: Groups,
        forces: List<Force>,
        constraints: List<Constraint>,
        t: Double,
        dt: Double,
    ): StepResult {
        store.refreshDynamicMass(t)

        val net = accumulateForces(store, groups, forces, t)

        // Break checks (§5.4) read the same pre-integration state the forces above were just
        // computed from — fixed forces-list order, so which connections break doesn't depend
        // on evaluation order, and "multiple breaks in one step" are inherently simultaneous.
        val broken = forces.filter { it is Breakable && it.shouldBreak(store) }

        for (id in store.liveIds()) {
            val slot = store.slotOf(id)
            val accel = net.at(slot) * (1.0 / store.mass(id))
            store.setVelocity(id, store.velocity(id) + accel * dt)
        }
        for (c in constraints) c.applyVelocity(store, groups, t)

        for (id in store.liveIds()) {
            store.setPosition(id, store.position(id) + store.velocity(id) * dt)
        }
        for (c in constraints) c.applyPosition(store, groups, t)

        checkForBlowUp(store)

        return StepResult(broken)
    }

    private fun accumulateForces(
        store: ParticleStore, groups: Groups, forces: List<Force>, t: Double,
    ): ChunkAccumulator {
        val capacity = store.capacity
        val chunks = Array(chunkCount) { ChunkAccumulator(capacity) }

        fun runChunk(chunkIndex: Int) {
            for (force in forces) {
                force.accumulate(store, groups, t, chunks[chunkIndex], chunkIndex, chunkCount)
            }
        }

        val exec = executor
        if (exec == null) {
            for (chunkIndex in 0 until chunkCount) runChunk(chunkIndex)
        } else {
            // Submit every chunk before joining any of them — joining future 0 first would let
            // a slow/hung chunk 0 block us from even noticing chunks 1..n have already failed.
            val futures = (0 until chunkCount).map { chunkIndex -> exec.submit { runChunk(chunkIndex) } }
            for (future in futures) {
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    // Unwrap so a force's own exception (e.g. BlowUpException) surfaces as
                    // itself, not as "ExecutionException: BlowUpException: ..." — callers
                    // catching a specific exception type shouldn't have to know whether the
                    // run happened to be parallel.
                    throw e.cause ?: e
                }
            }
        }

        // Fixed chunk-index order regardless of actual completion order (§9.3, §11) — this
        // was already true before parallelism existed, and stays untouched by it.
        val merged = ChunkAccumulator(capacity)
        for (chunk in chunks) chunk.addInto(merged)
        return merged
    }

    private fun checkForBlowUp(store: ParticleStore) {
        for (id in store.liveIds()) {
            val p = store.position(id)
            val v = store.velocity(id)
            if (!p.isFinite() || !v.isFinite()) {
                throw BlowUpException("particle $id blew up: position=$p velocity=$v")
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_COUNT = 4
    }
}
