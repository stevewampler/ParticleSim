package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.sqrt

/**
 * Members of [group] standing on level ground at height [groundHeight] - a crane's crawler pads, a
 * machine's feet - as a stiff, compression-only spring-damper with Coulomb friction, so each
 * reports what the ground holds it up with ([force]) and so a machine can lift off one side and
 * tip over. (A [particlesim.collision.PlaneCollider] instead puts a particle back on the surface
 * each step and scales its sliding velocity, which reports no force and can't hold a sideways
 * load.)
 *
 * - **Normal**: below the ground by `pen`, pushed up with `k pen - c v_y`, never pulled down - it
 *   lets go once it would pull. [stiffness] is per particle; each one's damping is
 *   `2 dampingRatio sqrt(k m)` from its mass at construction.
 * - **Friction**: on touching down a particle sticks where it landed, held there by a tangential
 *   spring-damper of the same stiffness and damping, until that would need more than
 *   [friction] times the normal force - then it slides, the force held at that limit and the
 *   sticking point dragged along behind it. Off the ground it forgets where it stuck.
 *
 * The sticking points move once a step, in the first chunk, so the result doesn't depend on the
 * chunk count; [force] reads them without moving them. Skipped while [group] is disabled;
 * membership is read once, at construction.
 */
class GroundContact(
    private val group: String,
    groups: Groups,
    store: ParticleStore,
    private val stiffness: Double,
    dampingRatio: Double,
    private val friction: Double,
    private val groundHeight: Double = 0.0,
    override val name: String? = "ground contact",
) : Force {
    val ids: List<Int> = groups.membersOf(group).toList()
    private val damping = DoubleArray(ids.size) { 2 * dampingRatio * sqrt(stiffness * store.mass(ids[it])) }
    private val index: Map<Int, Int> = ids.withIndex().associate { (i, id) -> id to i }
    private val touching = BooleanArray(ids.size)
    private val stickX = DoubleArray(ids.size)
    private val stickZ = DoubleArray(ids.size)

    /** What the ground pushes particle [id] with right now (zero off the ground). */
    fun force(store: ParticleStore, id: Int): Vector3 = compute(store, index.getValue(id), update = false)

    /** The upward part of [force]. */
    fun normalForce(store: ParticleStore, id: Int): Double = force(store, id).y

    /** Whether [id] is pressing on the ground. */
    fun inContact(store: ParticleStore, id: Int): Boolean = normalForce(store, id) > 0.0

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        if (chunkIndex != 0 || !groups.isEnabled(group)) return
        for (i in ids.indices) {
            val f = compute(store, i, update = true)
            if (f.x != 0.0 || f.y != 0.0 || f.z != 0.0) chunk.add(store.slotOf(ids[i]), f.x, f.y, f.z)
        }
    }

    private fun compute(store: ParticleStore, i: Int, update: Boolean): Vector3 {
        val s = store.slotOf(ids[i])
        val pen = groundHeight - store.yAt(s)
        if (pen <= 0.0) {
            if (update) touching[i] = false
            return Vector3.ZERO
        }
        val c = damping[i]
        val normal = maxOf(0.0, stiffness * pen - c * store.vyAt(s))
        val x = store.xAt(s); val z = store.zAt(s)
        // Where it stuck - here, if it's only just touched down.
        val ax = if (touching[i]) stickX[i] else x
        val az = if (touching[i]) stickZ[i] else z
        val vx = store.vxAt(s); val vz = store.vzAt(s)
        var fx = -stiffness * (x - ax) - c * vx
        var fz = -stiffness * (z - az) - c * vz
        val limit = friction * normal
        val f = sqrt(fx * fx + fz * fz)
        var nx = ax; var nz = az
        if (f > limit) {
            // Sliding: the force at the limit, the sticking point dragged to match it.
            val scale = if (f > 0.0) limit / f else 0.0
            fx *= scale; fz *= scale
            nx = x + (fx + c * vx) / stiffness
            nz = z + (fz + c * vz) / stiffness
        }
        if (update) {
            touching[i] = true
            stickX[i] = nx; stickZ[i] = nz
        }
        return Vector3(fx, normal, fz)
    }
}
