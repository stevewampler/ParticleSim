package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wind on a [Truss]'s members and on bluff bodies hung on it, as quasi-steady drag - what a
 * crane feels (§5.1).
 *
 * - **Members**: each is a slender cylinder in cross-flow. Only the wind's component across the
 *   member counts (the cross-flow principle): with `v` the wind at the member's midpoint less
 *   the mean of its ends' velocities, and `v⊥` its part normal to the member,
 *   `F = ½ ρ (Cd·D) L |v⊥| v⊥`, half to each end. [dragWidths] gives each member's `Cd·D`, metres
 *   (0 for one the wind shouldn't see). A member edge-on to the wind feels nothing; one at angle θ
 *   to it feels `sin²θ` of the full force, along its normal. Torn members feel nothing.
 * - **Bodies** (a ballast stack, a cab, a load): each a group whose `Cd·A` ([Body.dragArea], m²,
 *   read every step - a load can change) feels `F = ½ ρ CdA |v| v` at its members' mean position
 *   and velocity, shared equally among them. Skipped while its group is disabled; membership is
 *   read once, at construction.
 *
 * In still air ([velocity] zero) it does nothing at all - the drag on a structure swaying through
 * still air is nothing beside its own damping - so a scene without wind runs as it did without it.
 *
 * [velocity] is the wind at [referenceHeight] above [groundHeight]; higher up it blows harder by
 * the power law `(z / referenceHeight)^profileExponent` (`z` floored at [MIN_PROFILE_HEIGHT]) -
 * 0 for the same wind everywhere.
 *
 * Unlike [Wind] (§7.2), whose pressure on a triangle is linear in the relative wind and pushes
 * along the triangle's normal, this is drag in the square of the wind speed with a drag
 * coefficient, which is how codes load lattice steelwork and ropes. Lattice faces shielding each
 * other isn't modelled: every member sees the full wind, which errs high.
 *
 * Deterministic under chunking (§9.3): member `i` belongs to chunk `i mod chunkCount`, body `k`
 * to chunk `(members + k) mod chunkCount`.
 */
class TrussWind(
    private val truss: Truss,
    store: ParticleStore,
    groups: Groups,
    dragWidths: DoubleArray,
    val bodies: List<Body> = emptyList(),
    var velocity: VectorExpr = VectorExpr.of(Vector3.ZERO),
    density: Double = AIR_DENSITY,
    private val profileExponent: Double = 0.0,
    private val referenceHeight: Double = 10.0,
    private val groundHeight: Double = 0.0,
    override val name: String? = "wind",
) : Force, EditableFields {
    /** A bluff body: [group]'s particles together, with drag coefficient times area [dragArea]. */
    class Body(val group: String, val dragArea: () -> Double)

    var density: Double = density
        private set

    private val count = truss.size
    private val slotA = IntArray(count) { store.slotOf(truss.members[it].a) }
    private val slotB = IntArray(count) { store.slotOf(truss.members[it].b) }
    private val width = dragWidths.copyOf()
    private val bodyIds: List<IntArray> = bodies.map { b -> groups.membersOf(b.group).toIntArray() }

    init {
        require(dragWidths.size == count) { "one drag width a member: ${dragWidths.size} for $count members" }
        require(dragWidths.all { it >= 0.0 }) { "drag widths can't be negative" }
        require(bodyIds.all { it.isNotEmpty() }) { "every body's group needs particles" }
    }

    override fun editableFields(): Map<String, FieldValue> = mapOf("density" to FieldValue.Scalar(density))

    override fun setField(field: String, value: FieldValue): Boolean {
        if (field != "density" || value !is FieldValue.Scalar || value.value < 0.0) return false
        density = value.value
        return true
    }

    /** How much harder than at [referenceHeight] it blows at height [y]. */
    fun profile(y: Double): Double {
        if (profileExponent == 0.0) return 1.0
        return (maxOf(y - groundHeight, MIN_PROFILE_HEIGHT) / referenceHeight).pow(profileExponent)
    }

    /** The wind at height [y] at time [t]. */
    fun windAt(y: Double, t: Double): Vector3 = velocity.evaluate(t) * profile(y)

    /** The wind's whole force on [member] right now (half goes to each end). */
    fun memberForce(store: ParticleStore, t: Double, member: Int): Vector3 {
        if (truss.isTorn(member) || width[member] == 0.0 || velocity.evaluate(t) == Vector3.ZERO) return Vector3.ZERO
        val a = truss.members[member].a; val b = truss.members[member].b
        val pa = store.position(a); val pb = store.position(b)
        val d = pb - pa
        val length = d.length()
        if (length == 0.0) return Vector3.ZERO
        val e = d * (1.0 / length)
        val rel = windAt((pa.y + pb.y) / 2, t) - (store.velocity(a) + store.velocity(b)) * 0.5
        val normal = rel - e * rel.dot(e)
        return normal * (0.5 * density * width[member] * length * normal.length())
    }

    /** The wind's whole force on body [k] right now. */
    fun bodyForce(store: ParticleStore, groups: Groups, t: Double, k: Int): Vector3 {
        if (!groups.isEnabled(bodies[k].group) || velocity.evaluate(t) == Vector3.ZERO) return Vector3.ZERO
        val ids = bodyIds[k]
        var p = Vector3.ZERO; var v = Vector3.ZERO
        for (id in ids) { p += store.position(id); v += store.velocity(id) }
        val n = 1.0 / ids.size
        val rel = windAt(p.y * n, t) - v * n
        return rel * (0.5 * density * bodies[k].dragArea() * rel.length())
    }

    /** Calls [action] with each member's and body's force right now and where it acts: a
     * member's midpoint, a body's mean position. */
    fun forEachForce(store: ParticleStore, groups: Groups, t: Double, action: (at: Vector3, force: Vector3) -> Unit) {
        for (i in 0 until count) {
            val f = memberForce(store, t, i)
            if (f != Vector3.ZERO) action((store.position(truss.members[i].a) + store.position(truss.members[i].b)) * 0.5, f)
        }
        for (k in bodies.indices) {
            val f = bodyForce(store, groups, t, k)
            if (f != Vector3.ZERO) action(bodyIds[k].fold(Vector3.ZERO) { s, id -> s + store.position(id) } * (1.0 / bodyIds[k].size), f)
        }
    }

    /** The wind's total force on everything it pushes. */
    fun totalForce(store: ParticleStore, groups: Groups, t: Double): Vector3 =
        (0 until count).fold(Vector3.ZERO) { s, i -> s + memberForce(store, t, i) } +
            bodies.indices.fold(Vector3.ZERO) { s, k -> s + bodyForce(store, groups, t, k) }

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        val wind = velocity.evaluate(t)
        // In still air it leaves everything alone: the drag on a structure moving through still
        // air is nothing beside its own damping, and not looping saves the time.
        if (wind == Vector3.ZERO) return
        val wx = wind.x; val wy = wind.y; val wz = wind.z
        val half = 0.5 * density
        var i = chunkIndex
        while (i < count) {
            val cd = width[i]
            if (cd == 0.0 || truss.isTorn(i)) { i += chunkCount; continue }
            val a = slotA[i]; val b = slotB[i]
            val dx = store.xAt(b) - store.xAt(a); val dy = store.yAt(b) - store.yAt(a); val dz = store.zAt(b) - store.zAt(a)
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length == 0.0) { i += chunkCount; continue }
            val inv = 1.0 / length
            val ex = dx * inv; val ey = dy * inv; val ez = dz * inv
            val f = profile((store.yAt(a) + store.yAt(b)) * 0.5)
            val rx = wx * f - (store.vxAt(a) + store.vxAt(b)) * 0.5
            val ry = wy * f - (store.vyAt(a) + store.vyAt(b)) * 0.5
            val rz = wz * f - (store.vzAt(a) + store.vzAt(b)) * 0.5
            val along = rx * ex + ry * ey + rz * ez
            val nx = rx - ex * along; val ny = ry - ey * along; val nz = rz - ez * along
            // Half the member's force to each end.
            val c = 0.5 * half * cd * length * sqrt(nx * nx + ny * ny + nz * nz)
            chunk.add(a, c * nx, c * ny, c * nz)
            chunk.add(b, c * nx, c * ny, c * nz)
            i += chunkCount
        }
        while (i < count + bodies.size) {
            val k = i - count
            val force = bodyForce(store, groups, t, k)
            if (force != Vector3.ZERO) {
                val ids = bodyIds[k]
                val share = force * (1.0 / ids.size)
                for (id in ids) chunk.add(store.slotOf(id), share)
            }
            i += chunkCount
        }
    }

    companion object {
        /** kg/m³: air at sea level, the value wind codes use. */
        const val AIR_DENSITY = 1.25
        /** m: below this the power-law profile stops falling. */
        const val MIN_PROFILE_HEIGHT = 1.0
    }
}
