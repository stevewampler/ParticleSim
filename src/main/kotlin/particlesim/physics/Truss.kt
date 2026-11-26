package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import kotlin.math.PI
import kotlin.math.abs
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
 * **Failure** (§5.4), while [failure] is on:
 * - a member with a [Member.yieldStress] yields in tension there: it stretches on at that force,
 *   its rest length following (steel's plastic flow);
 * - one with a [Member.bucklingStress] buckles in compression there, and as it goes on shortening
 *   its strength falls away - to [BUCKLED_RESIDUAL] of it once it has shortened [SOFTENING_STRAIN]
 *   past buckling - which is what lets one buckled member pass its load on and bring others down;
 * - one stretched past its [Member.ruptureStrain], or pulled harder than its
 *   [Member.breakingForce] (wire rope), tears and carries nothing from then on.
 * What has yielded, buckled or torn stays so if [failure] is turned off; only new failure stops.
 * [axialForce] and [stress] report what a member really carries, failure included.
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
     * (wire rope damps far more than solid steel). The failure limits (see [Truss]) are null for
     * a member that doesn't fail that way. */
    data class Member(
        val a: Int,
        val b: Int,
        val area: Double,
        val youngsModulus: Double,
        val tensionOnly: Boolean = false,
        val restLength: Double? = null,
        val stiffnessDamping: Double? = null,
        val yieldStress: Double? = null,
        val bucklingStress: Double? = null,
        val ruptureStrain: Double? = null,
        val breakingForce: Double? = null,
    ) {
        init {
            require(a != b) { "a member needs two different ends" }
            require(area > 0.0 && youngsModulus > 0.0) { "area and Young's modulus must be positive" }
        }
    }

    var failure: Boolean = false

    private val slotA = IntArray(members.size) { store.slotOf(members[it].a) }
    private val slotB = IntArray(members.size) { store.slotOf(members[it].b) }
    private val axialStiffness = DoubleArray(members.size) { members[it].area * members[it].youngsModulus }
    private val tensionOnly = BooleanArray(members.size) { members[it].tensionOnly }
    private val damping = DoubleArray(members.size) { members[it].stiffnessDamping ?: stiffnessDamping }
    /** The unstressed length the stiffness is measured against. */
    private val rest = DoubleArray(members.size) { i ->
        members[i].restLength ?: (store.position(members[i].b) - store.position(members[i].a)).length()
    }
    /** The length it has no force at now: [rest] moved by plastic flow. */
    private val plasticRest = rest.copyOf()
    private val yieldForce = DoubleArray(members.size) { members[it].yieldStress?.let { s -> s * members[it].area } ?: Double.POSITIVE_INFINITY }
    private val bucklingForce = DoubleArray(members.size) { members[it].bucklingStress?.let { s -> s * members[it].area } ?: Double.POSITIVE_INFINITY }
    private val ruptureStrain = DoubleArray(members.size) { members[it].ruptureStrain ?: Double.POSITIVE_INFINITY }
    private val breakingForce = DoubleArray(members.size) { members[it].breakingForce ?: Double.POSITIVE_INFINITY }
    private val torn = BooleanArray(members.size)

    init {
        for (i in members.indices) require(rest[i] > 0.0) { "member $i has no length" }
    }

    val size: Int get() = members.size

    fun restLength(member: Int): Double = rest[member]

    /** Sets [member]'s unstretched length - safe between steps, never during one. Any plastic
     * flow it had is forgotten (meant for rope, which doesn't yield). */
    fun setRestLength(member: Int, length: Double) {
        require(length > 0.0 && length.isFinite()) { "rest length must be positive, was $length" }
        rest[member] = length
        plasticRest[member] = length
    }

    /** [member]'s stiffness `EA / L` at its current rest length. */
    fun stiffness(member: Int): Double = axialStiffness[member] / rest[member]

    /** [member]'s current length. */
    fun length(store: ParticleStore, member: Int): Double {
        val a = slotA[member]; val b = slotB[member]
        val dx = store.xAt(b) - store.xAt(a); val dy = store.yAt(b) - store.yAt(a); val dz = store.zAt(b) - store.zAt(a)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun isTorn(member: Int): Boolean = torn[member]

    /** Whether any member has torn. */
    var hasTorn: Boolean = false
        private set

    /** How far [member] has shortened plastically past buckling, as a strain (0 if it hasn't). */
    fun bucklingStrain(member: Int): Double = maxOf(0.0, rest[member] - plasticRest[member]) / rest[member]

    /** Whether [member] has buckled: shortened plastically under compression. */
    fun hasBuckled(member: Int): Boolean = bucklingStrain(member) > 0.0

    /** How far [member] has stretched plastically past yield, as a strain (0 if it hasn't). */
    fun yieldStrain(member: Int): Double = maxOf(0.0, plasticRest[member] - rest[member]) / rest[member]

    /** The compressive force [member] can carry now, newtons: its buckling force, falling away
     * as it shortens past buckling. */
    fun compressionCapacity(member: Int): Double {
        val cap = bucklingForce[member]
        if (cap.isInfinite()) return cap
        val lost = (bucklingStrain(member) / SOFTENING_STRAIN).coerceAtMost(1.0)
        return cap * (1.0 - lost * (1.0 - BUCKLED_RESIDUAL))
    }

    /** The tensile force [member] can carry, newtons: whichever of yield and breaking comes first. */
    fun tensionCapacity(member: Int): Double = minOf(yieldForce[member], breakingForce[member])

    /** How close [member] is to its limit: its force over the capacity on that side (0 when torn
     * or carrying nothing; 1 at yield, buckling or breaking). */
    fun utilisation(store: ParticleStore, member: Int): Double {
        val n = axialForce(store, member)
        return if (n >= 0.0) n / tensionCapacity(member) else -n / compressionCapacity(member)
    }

    /** [member]'s axial force from its stretch, newtons: positive in tension, negative in
     * compression, zero for a slack tension-only member or a torn one - what it really carries,
     * failure included. */
    fun axialForce(store: ParticleStore, member: Int): Double {
        if (torn[member]) return 0.0
        val n = elasticForce(member, length(store, member))
        return if (failure) n.coerceIn(-compressionCapacity(member), yieldForce[member]) else n
    }

    /** [member]'s axial stress, pascals: [axialForce] over its area. */
    fun stress(store: ParticleStore, member: Int): Double = axialForce(store, member) / members[member].area

    private fun elasticForce(i: Int, length: Double): Double {
        val n = axialStiffness[i] / rest[i] * (length - plasticRest[i])
        return if (tensionOnly[i] && n < 0.0) 0.0 else n
    }

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        val failing = failure
        var i = chunkIndex
        while (i < slotA.size) {
            if (torn[i]) { i += chunkCount; continue }
            val a = slotA[i]
            val b = slotB[i]
            val dx = store.xAt(b) - store.xAt(a)
            val dy = store.yAt(b) - store.yAt(a)
            val dz = store.zAt(b) - store.zAt(a)
            // Floored so a member crushed to nothing can't divide by zero.
            val length = maxOf(sqrt(dx * dx + dy * dy + dz * dz), MIN_LENGTH_FRACTION * rest[i])
            val k = axialStiffness[i] / rest[i]
            var tension = k * (length - plasticRest[i])
            if (failing) {
                // Each member belongs to exactly one chunk, so writing its own entries is safe.
                if (tension > yieldForce[i]) { plasticRest[i] = length - yieldForce[i] / k; tension = yieldForce[i] }
                val cap = compressionCapacity(i)
                if (tension < -cap) { plasticRest[i] = length + cap / k; tension = -cap }
                if (length - rest[i] > ruptureStrain[i] * rest[i] || tension > breakingForce[i]) {
                    torn[i] = true; hasTorn = true
                    i += chunkCount
                    continue
                }
            }
            if (tensionOnly[i] && tension <= 0.0) { i += chunkCount; continue }
            val inv = 1.0 / length
            val nx = dx * inv; val ny = dy * inv; val nz = dz * inv
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
        /** What a buckled member keeps of its strength once it has folded well past buckling. */
        const val BUCKLED_RESIDUAL = 0.3
        /** Plastic shortening past buckling over which its strength falls to [BUCKLED_RESIDUAL]. */
        const val SOFTENING_STRAIN = 0.01

        /**
         * A pin-ended member's buckling stress by Rankine's formula, `1 / (1/yield + 1/euler)`:
         * near yield for stocky members, near Euler's `pi^2 E / (L/r)^2` for slender ones, and
         * below both in between, as real columns are (their small crookedness and residual
         * stresses). [radiusOfGyration] `r` is the section's.
         */
        fun rankineBucklingStress(yieldStress: Double, youngsModulus: Double, length: Double, radiusOfGyration: Double): Double {
            val slenderness = length / radiusOfGyration
            val euler = PI * PI * youngsModulus / (slenderness * slenderness)
            return 1.0 / (1.0 / yieldStress + 1.0 / euler)
        }

        /** The radius of gyration of a round tube of [area] whose diameter is [diameterOverThickness]
         * times its wall - a consistent `r` for a member known only by its area. */
        fun tubeRadiusOfGyration(area: Double, diameterOverThickness: Double = 20.0): Double {
            // A = pi t (D - t) with t = D / n; r = sqrt(D^2 + (D - 2t)^2) / 4.
            val n = diameterOverThickness
            val d = sqrt(area * n * n / (PI * (n - 1)))
            val inner = d * (1 - 2 / n)
            return sqrt(d * d + inner * inner) / 4
        }
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
