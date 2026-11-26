package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import kotlin.math.sqrt

/**
 * A pin-jointed truss: straight members between particles, each carrying only axial force -
 * tension or compression along its length - with a stiffness `EA / L` from its own [Member.area],
 * Young's modulus and rest length. One [Force] for the whole structure, so a few thousand members
 * cost one tight loop rather than a few thousand [Spring] objects, and so each member's axial
 * force and stress can be read back for colouring.
 *
 * [Member.tensionOnly] members (tie bars, wire rope) go slack instead of pushing. A member's rest
 * length can be changed while it runs ([setRestLength]) - a hoist paying out rope.
 *
 * Damping is along each member only, proportional to its stiffness ([stiffnessDamping] seconds:
 * `c = stiffnessDamping * EA / L`), so it damps the truss's fast vibration without braking any
 * motion of the structure as a whole.
 *
 * Deterministic under chunking (§9.3): member `i` belongs to chunk `i mod chunkCount`.
 */
class Truss(
    store: ParticleStore,
    val members: List<Member>,
    private val stiffnessDamping: Double = 0.0,
    override val name: String? = null,
) : Force {
    /** One straight member from particle [a] to [b]. [restLength] defaults to their distance when
     * the truss is built; [stiffnessDamping], if given, replaces the truss's own for this member
     * (wire rope damps far more than solid steel). */
    data class Member(
        val a: Int,
        val b: Int,
        val area: Double,
        val youngsModulus: Double,
        val tensionOnly: Boolean = false,
        val restLength: Double? = null,
        val stiffnessDamping: Double? = null,
    ) {
        init {
            require(a != b) { "a member needs two different ends" }
            require(area > 0.0 && youngsModulus > 0.0) { "area and Young's modulus must be positive" }
        }
    }

    private val slotA = IntArray(members.size) { store.slotOf(members[it].a) }
    private val slotB = IntArray(members.size) { store.slotOf(members[it].b) }
    private val axialStiffness = DoubleArray(members.size) { members[it].area * members[it].youngsModulus }
    private val tensionOnly = BooleanArray(members.size) { members[it].tensionOnly }
    private val damping = DoubleArray(members.size) { members[it].stiffnessDamping ?: stiffnessDamping }
    private val rest = DoubleArray(members.size) { i ->
        members[i].restLength ?: (store.position(members[i].b) - store.position(members[i].a)).length()
    }

    init {
        for (i in members.indices) require(rest[i] > 0.0) { "member $i has no length" }
    }

    val size: Int get() = members.size

    fun restLength(member: Int): Double = rest[member]

    /** Sets [member]'s unstretched length - safe between steps, never during one. */
    fun setRestLength(member: Int, length: Double) {
        require(length > 0.0 && length.isFinite()) { "rest length must be positive, was $length" }
        rest[member] = length
    }

    /** [member]'s stiffness `EA / L` at its current rest length. */
    fun stiffness(member: Int): Double = axialStiffness[member] / rest[member]

    /** [member]'s current length. */
    fun length(store: ParticleStore, member: Int): Double {
        val a = slotA[member]; val b = slotB[member]
        val dx = store.xAt(b) - store.xAt(a); val dy = store.yAt(b) - store.yAt(a); val dz = store.zAt(b) - store.zAt(a)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** [member]'s axial force from its stretch alone, newtons: positive in tension, negative in
     * compression, zero for a slack tension-only member. */
    fun axialForce(store: ParticleStore, member: Int): Double {
        val n = stiffness(member) * (length(store, member) - rest[member])
        return if (tensionOnly[member] && n < 0.0) 0.0 else n
    }

    /** [member]'s axial stress, pascals: [axialForce] over its area. */
    fun stress(store: ParticleStore, member: Int): Double = axialForce(store, member) / members[member].area

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        var i = chunkIndex
        while (i < slotA.size) {
            val a = slotA[i]
            val b = slotB[i]
            val dx = store.xAt(b) - store.xAt(a)
            val dy = store.yAt(b) - store.yAt(a)
            val dz = store.zAt(b) - store.zAt(a)
            // Floored so a member crushed to nothing can't divide by zero.
            val length = maxOf(sqrt(dx * dx + dy * dy + dz * dz), MIN_LENGTH_FRACTION * rest[i])
            val stretch = length - rest[i]
            if (tensionOnly[i] && stretch <= 0.0) { i += chunkCount; continue }
            val inv = 1.0 / length
            val nx = dx * inv; val ny = dy * inv; val nz = dz * inv
            val k = axialStiffness[i] / rest[i]
            var tension = k * stretch
            if (damping[i] != 0.0) {
                val opening = (store.vxAt(b) - store.vxAt(a)) * nx + (store.vyAt(b) - store.vyAt(a)) * ny + (store.vzAt(b) - store.vzAt(a)) * nz
                tension += damping[i] * k * opening
                // A slack-going rope can't push back, even while it's closing fast.
                if (tensionOnly[i] && tension < 0.0) tension = 0.0
            }
            chunk.add(a, nx * tension, ny * tension, nz * tension)
            chunk.add(b, -nx * tension, -ny * tension, -nz * tension)
            i += chunkCount
        }
    }

    companion object {
        private const val MIN_LENGTH_FRACTION = 0.05
    }
}

/**
 * A stiff zero-length spring holding [particle] at a weighted blend of other particles,
 * `target = sum(w_j * x_j)`, with the equal and opposite force shared among them by the same
 * weights. The weights must sum to one; they may be negative (a point just outside the particles
 * it's blended from).
 *
 * Because the reaction goes back exactly where the target came from, the pair exerts no net force
 * and - since the spring force points along `target - x` - no net torque: it only couples. Moving
 * the target by changing the weights ([setTarget], between steps) is how a drive acts:
 * - a trolley on a jib: weights along the bottom chords at the trolley's position, so its load
 *   bears on the two nearest panel points of each chord;
 * - a slewing ring: each turntable corner held at its rotated position in the mast-top square
 *   (bilinear weights), so the ring passes the jib's overturning moment down into the mast as a
 *   couple, and the slew motor's torque appears as the reaction on the mast.
 *
 * [damping] acts on the velocity relative to the blended target velocity.
 */
class WeightedAttachment(
    val particle: Int,
    private val stiffness: Double,
    private val damping: Double = 0.0,
    override val name: String? = null,
) : Force {
    private var targetIds = IntArray(0)
    private var weights = DoubleArray(0)

    val targets: List<Pair<Int, Double>> get() = targetIds.indices.map { targetIds[it] to weights[it] }

    /** Holds [particle] at `sum(weight * position)` over [blend] - weights summing to one. */
    fun setTarget(blend: List<Pair<Int, Double>>) {
        val sum = blend.sumOf { it.second }
        require(blend.isNotEmpty() && kotlin.math.abs(sum - 1.0) < 1e-9) { "weights must sum to one, were $sum" }
        targetIds = IntArray(blend.size) { blend[it].first }
        weights = DoubleArray(blend.size) { blend[it].second }
    }

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        // One item: the first chunk owns it, so the result doesn't depend on the chunk count.
        if (chunkIndex != 0 || targetIds.isEmpty()) return
        var tx = 0.0; var ty = 0.0; var tz = 0.0
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        for (j in targetIds.indices) {
            val s = store.slotOf(targetIds[j]); val w = weights[j]
            tx += w * store.xAt(s); ty += w * store.yAt(s); tz += w * store.zAt(s)
            vx += w * store.vxAt(s); vy += w * store.vyAt(s); vz += w * store.vzAt(s)
        }
        val p = store.slotOf(particle)
        val fx = stiffness * (tx - store.xAt(p)) + damping * (vx - store.vxAt(p))
        val fy = stiffness * (ty - store.yAt(p)) + damping * (vy - store.vyAt(p))
        val fz = stiffness * (tz - store.zAt(p)) + damping * (vz - store.vzAt(p))
        chunk.add(p, fx, fy, fz)
        for (j in targetIds.indices) {
            val w = weights[j]
            chunk.add(store.slotOf(targetIds[j]), -w * fx, -w * fy, -w * fz)
        }
    }
}
