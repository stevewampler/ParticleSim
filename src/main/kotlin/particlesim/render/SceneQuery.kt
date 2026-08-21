package particlesim.render

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3

/**
 * The read-only scene-query surface camera expressions get (§4.1: "camera expressions
 * additionally get a scene query API... to reference other particles/groups/surfaces by
 * name"), deliberately narrower than [ParticleStore]/[Groups] themselves — camera evaluation
 * happens purely for rendering and must never be able to mutate simulation state, so this
 * interface only exposes reads. `normal(surface, triangleIndex)` from the spec's own list
 * isn't here yet: no consumer needs it before a camera actually orbits a surface, and surfaces
 * don't have a stable name→object registry accessible from here yet either — deferred rather
 * than guessed at.
 */
interface SceneQuery {
    fun position(id: Int): Vector3

    /** The average position of every current member of [group] — empty groups return
     * [Vector3.ZERO] rather than throwing, since a camera that briefly targets a
     * not-yet-populated or momentarily-empty group (an emitter's group before its first
     * spawn, say) shouldn't crash the whole render step over it. */
    fun centroid(group: String): Vector3
}

class SceneQueryImpl(private val store: ParticleStore, private val groups: Groups) : SceneQuery {
    override fun position(id: Int): Vector3 = store.position(id)

    override fun centroid(group: String): Vector3 {
        val members = groups.membersOf(group)
        if (members.isEmpty()) return Vector3.ZERO
        var sum = Vector3.ZERO
        for (id in members) sum += store.position(id)
        return sum * (1.0 / members.size)
    }
}
