package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore

/**
 * A force contributes to particles' net force each step (§5). `accumulate` is called once
 * per logical chunk (§9.3): a force with many independent work items (a group's members for
 * a field force, or all pairs for N-body gravity, §5.2) must stride its *own* work by
 * `chunkIndex`/`chunkCount` so the work is actually split across chunks — striding at the
 * force-declaration level instead would leave a single expensive force (N-body gravity, or
 * a mesh's worth of structural springs in Phase 4) entirely unparallelized, which defeats
 * the point. A force with only one atomic unit of work (a single spring between two named
 * particles) has nothing to usefully subdivide and should just contribute on `chunkIndex ==
 * 0` — see `Spring`/`Damper`.
 *
 * Iteration order matters for determinism (§11): summing floating-point forces in a
 * different order can change the bit-exact result, so any force that iterates a group's
 * members must do so in [Groups.membersOf]'s (insertion-ordered) iteration order, not
 * re-sort or use an unordered set.
 */
interface Force {
    val name: String?

    fun accumulate(
        store: ParticleStore,
        groups: Groups,
        t: Double,
        chunk: ChunkAccumulator,
        chunkIndex: Int,
        chunkCount: Int,
    )
}

/**
 * A [Force] that can permanently remove itself from the simulation once some threshold is
 * exceeded (§5.4) — currently [Spring] and [Damper]. Checked once per physics step against
 * the state at the *start* of that step, before integration moves anything — never
 * re-evaluated mid-step, so which connections break in a given step doesn't depend on the
 * order they're checked in.
 */
interface Breakable {
    fun shouldBreak(store: ParticleStore): Boolean
}

/**
 * A [Force] that connects exactly two named particles — currently [Spring] and [Damper].
 * Lets generic code (the debug renderer's line-per-connection view, §10.2; later the real
 * pairwise-force renderer) find a connection's endpoints without knowing which concrete
 * force type it is.
 */
interface PairwiseForce {
    val particleA: Int
    val particleB: Int
}
