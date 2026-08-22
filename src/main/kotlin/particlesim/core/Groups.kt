package particlesim.core

/**
 * Named, reusable sets of particle ids (§2, §4.2). A first-class runtime concept, not just
 * a YAML-authoring convenience: emitters (Phase 6) mutate group membership live during a
 * run, so membership has to be something the engine can update at any time, not a list
 * baked in once at load time.
 *
 * Membership is keyed by stable particle id, never by [ParticleStore] slot — so a
 * destroyed particle's slot being recycled by a new particle can never leak stale group
 * membership onto the newcomer.
 */
class Groups {
    private val membersByGroup = HashMap<String, MutableSet<Int>>()
    private val groupsById = HashMap<Int, MutableSet<String>>()

    fun add(group: String, id: Int) {
        membersByGroup.getOrPut(group) { mutableSetOf() }.add(id)
        groupsById.getOrPut(id) { mutableSetOf() }.add(group)
    }

    fun remove(group: String, id: Int) {
        membersByGroup[group]?.remove(id)
        groupsById[id]?.remove(group)
    }

    /** Removes a particle from every group it belongs to — call when a particle is destroyed. */
    fun removeParticle(id: Int) {
        val groups = groupsById.remove(id) ?: return
        for (group in groups) {
            membersByGroup[group]?.remove(id)
        }
    }

    fun membersOf(group: String): Set<Int> = membersByGroup[group] ?: emptySet()
    fun groupsOf(id: Int): Set<String> = groupsById[id] ?: emptySet()

    /** Every group name that has ever had a member added, whether or not it's currently
     * non-empty (§10.3's outliner: a group's own declared existence should stay visible even
     * if every particle in it has since moved to another group or been destroyed — the same
     * way a named [particlesim.physics.Force] stays registered regardless of its current
     * numeric value). A group with zero current members via [remove]/[removeParticle] still
     * appears here; there's no separate "undeclare a group" operation. */
    fun names(): Set<String> = membersByGroup.keys.toSet()
}
