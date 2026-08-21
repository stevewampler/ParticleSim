package particlesim.record

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.lifecycle.Emitter
import particlesim.lifecycle.EmitterCheckpointState
import particlesim.physics.Force
import particlesim.physics.PairwiseForce

data class CheckpointParticle(
    val id: Int,
    val position: Vector3,
    val velocity: Vector3,
    val mass: Double,
    val radius: Double?,
    val spawnTime: Double,
    val lifetime: Double?,
)

/**
 * A full resumable snapshot of everything about a simulation that isn't recoverable from the
 * static scenario definition alone (§9.5) — "resume" means rebuilding the static definition
 * fresh (same forces/constraints/colliders/emitter parameters) and applying one of these on
 * top via [applyCheckpoint], not replaying history from t=0.
 *
 * [groupMembership] preserves each group's original insertion order (a `List`, not a `Set`) —
 * force accumulation iterates [Groups.membersOf] in that order (§11), so restoring it out of
 * order would still be *correct* but would silently stop being *bit-identical* to what an
 * uninterrupted run would have produced from the same point.
 *
 * [brokenConnections] is the set of (particleA, particleB) id pairs for every [PairwiseForce]
 * that's no longer active as of the checkpoint — whether it broke via [particlesim.physics.Breakable]
 * (§5.4) or went dangling because one of its particles was destroyed (§14.2's
 * `DestructionResult.danglingForces`). Nothing in the engine remembers this after the force is
 * dropped from the caller's active list, so a caller taking checkpoints has to accumulate this
 * set itself, folding in both `StepResult.brokenForces` and `DestructionResult.danglingForces`
 * (filtered to `PairwiseForce`) every step — [Checkpoint] just carries whatever's accumulated
 * by the time a snapshot is taken.
 */
data class Checkpoint(
    val t: Double,
    val step: Long,
    val nextId: Int,
    val particles: List<CheckpointParticle>,
    val groupMembership: Map<String, List<Int>>,
    val brokenConnections: Set<Pair<Int, Int>>,
    val emitters: List<EmitterCheckpointState>,
)

/** Assembles a [Checkpoint] from live simulation state. [groupNames] and [emitters] are
 * supplied explicitly (rather than discovered) since neither [ParticleStore] nor [Groups]
 * knows the full set of group names or emitters a scenario declares. */
fun captureCheckpoint(
    store: ParticleStore,
    groups: Groups,
    groupNames: List<String>,
    emitters: List<Emitter>,
    brokenConnections: Set<Pair<Int, Int>>,
    t: Double,
    step: Long,
): Checkpoint {
    val particles = store.liveIds().map { id ->
        CheckpointParticle(
            id = id,
            position = store.position(id),
            velocity = store.velocity(id),
            mass = store.mass(id),
            radius = store.radius(id),
            spawnTime = store.spawnTime(id),
            lifetime = store.lifetime(id),
        )
    }
    val groupMembership = groupNames.associateWith { name -> groups.membersOf(name).toList() }
    return Checkpoint(
        t = t,
        step = step,
        nextId = store.nextIdValue,
        particles = particles,
        groupMembership = groupMembership,
        brokenConnections = brokenConnections,
        emitters = emitters.map { it.captureState() },
    )
}

/**
 * Applies a [Checkpoint] onto a freshly-built (empty store, no group membership yet, emitters
 * just constructed) scenario shell — the counterpart to [captureCheckpoint]. Doesn't touch the
 * force list: filtering out already-broken connections is [filterBrokenConnections]'s job,
 * kept separate since a force list's identity is scenario-specific in a way
 * store/groups/emitters aren't.
 */
fun applyCheckpoint(
    store: ParticleStore,
    groups: Groups,
    emittersByName: Map<String, Emitter>,
    checkpoint: Checkpoint,
) {
    for (p in checkpoint.particles) {
        store.restoreParticle(p.id, p.position, p.velocity, p.mass, p.radius, p.spawnTime, p.lifetime)
    }
    store.advanceNextIdTo(checkpoint.nextId)
    for ((groupName, ids) in checkpoint.groupMembership) {
        for (id in ids) groups.add(groupName, id)
    }
    for (state in checkpoint.emitters) {
        emittersByName.getValue(state.name).restoreState(state)
    }
}

/** Drops every [PairwiseForce] in [forces] whose (particleA, particleB) pair is in
 * [Checkpoint.brokenConnections] — the resume-side counterpart to how a live run's caller
 * already drops [particlesim.physics.StepResult.brokenForces]/`DestructionResult.danglingForces`
 * from its own active list each step. */
fun filterBrokenConnections(forces: List<Force>, brokenConnections: Set<Pair<Int, Int>>): List<Force> =
    forces.filterNot { it is PairwiseForce && (it.particleA to it.particleB) in brokenConnections }
