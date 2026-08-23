package particlesim.lifecycle

import particlesim.collision.Collider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.physics.Force
import particlesim.physics.PairwiseForce

/** A per-group destroy condition (§14.2), evaluated against a live particle's current state
 * each step — a native lambda ahead of Phase 7's expression parser, the same stand-in
 * approach used for every other expression-capable field so far (mass, radius, wind...). */
class DestroyCondition(val group: String, val predicate: (ParticleStore, Int, Double) -> Boolean)

/** Destroys members of [group] on contact with [collider] (§14.2) — e.g. a spark disappearing
 * when it hits the ground. Reuses [Collider]'s narrow-phase geometry from Phase 5, but
 * destroys the particle instead of computing a physical response; a group shouldn't need both
 * a [particlesim.collision.ParticleColliderRule] and this for the same collider in practice
 * (bounce-then-vanish isn't a scenario this phase's example needs), but nothing stops it. */
class CollisionDestroyRule(val group: String, val collider: Collider)

data class DestructionResult(
    val destroyedIds: List<Int>,
    /** [PairwiseForce]s (e.g. Spring, Damper) that referenced one of [destroyedIds] by id —
     * the caller must drop these from its own active force list before the next
     * [particlesim.physics.Integrator.step] call, the same pattern already established for
     * broken forces (§5.4) and StepResult. Forces that target particles by group membership
     * (UniformGravity, NBodyGravity, MeshSprings, Wind, ...) need no such cleanup — group
     * membership already stops including a destroyed id the moment [Groups.removeParticle]
     * runs, which this class calls internally for every id it destroys.
     */
    val danglingForces: List<Force>,
)

/**
 * Resolves particle destruction (§14.2) as a step the caller runs after [Emitter.update] for
 * the same tick — **not** before it. A particle spawned this step should be simulated at
 * least once before it's eligible for destruction; checking `t - spawnTime >= lifetime`
 * against a particle whose `spawnTime` is *this* `t` would let a near-zero sampled lifetime
 * (or a spawn position already inside a destroy-collider's volume) kill it before it was ever
 * integrated. `destroy → emit` per step keeps that from happening; this class doesn't enforce
 * the ordering, the caller's loop does (mirrors how collision resolution is a separate call
 * after `Integrator.step`, not a stage inside it — see Phase 5's `CollisionSystem`).
 *
 * Scoped to free (non-surface) particles per §14.3 — nothing here checks whether a particle
 * is a surface-mesh vertex, so a [DestroyCondition]/[CollisionDestroyRule] whose group
 * includes surface vertices (e.g. a flag's cloth) would destroy them and leave `MeshSprings`/
 * `Wind` holding a dangling id, which throws on the next `accumulate`. Surface-vertex removal
 * needs its own mesh-repair design (§14.3 marks it `[stretch]`); this phase's worked example
 * doesn't touch surfaces, so the gap is documented rather than guarded against here.
 */
class DestructionSystem(
    private val destroyConditions: List<DestroyCondition> = emptyList(),
    private val collisionDestroyRules: List<CollisionDestroyRule> = emptyList(),
) {
    /** [explicitIds] is §14.2's fourth destroy mechanism — "explicit delete via the viewer,
     * alongside interactive dragging (§9.4)" — a caller-driven trigger rather than one this
     * class evaluates itself, the same way [particlesim.physics.DragConstraint] is driven by
     * live viewer input rather than an expression. Silently ignores any id no longer alive
     * (e.g. a delete click racing a lifetime expiry in the same step) rather than erroring —
     * "already gone" and "just destroyed" end up in the same place either way. */
    fun resolve(
        store: ParticleStore, groups: Groups, forces: List<Force>, t: Double, dt: Double,
        explicitIds: Set<Int> = emptySet(),
    ): DestructionResult {
        for (collider in collisionDestroyRules.map { it.collider }.distinct()) {
            collider.advance(t, dt)
        }

        val toDestroy = LinkedHashSet<Int>()

        for (id in explicitIds) if (store.contains(id)) toDestroy += id

        for (id in store.liveIds()) {
            val lifetime = store.lifetime(id)
            if (lifetime != null && t - store.spawnTime(id) >= lifetime) toDestroy += id
        }
        for (condition in destroyConditions) {
            for (id in groups.membersOf(condition.group)) {
                if (id in toDestroy) continue
                if (condition.predicate(store, id, t)) toDestroy += id
            }
        }
        for (rule in collisionDestroyRules) {
            for (id in groups.membersOf(rule.group)) {
                if (id in toDestroy) continue
                val radius = store.radius(id) ?: continue
                if (rule.collider.contact(store.position(id), radius) != null) toDestroy += id
            }
        }

        val danglingForces = forces.filter {
            it is PairwiseForce && (it.particleA in toDestroy || it.particleB in toDestroy)
        }

        for (id in toDestroy) {
            store.destroy(id)
            groups.removeParticle(id)
        }

        return DestructionResult(toDestroy.toList(), danglingForces)
    }
}
