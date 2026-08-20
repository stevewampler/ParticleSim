package particlesim.core

/**
 * Owns every particle's state as struct-of-arrays storage (§9.3): parallel primitive
 * arrays indexed by an internal "slot", with a stable `id` → slot map on top so callers
 * never see or depend on slot numbers. Ids are assigned once and never reused; slots are
 * reclaimed via a free-list when a particle is destroyed, so the backing arrays stay dense
 * without unbounded growth as particles are created/destroyed over a run (§14).
 *
 * Expression-capable fields (mass, radius, lifetime — §3) always have their *current*
 * evaluated value in the array (the hot-path read); a field whose [ScalarExpr] is a
 * function of time is additionally retained in a sparse id-keyed map so it can be
 * re-evaluated later. Constant-valued fields are evaluated once at creation and not
 * retained anywhere.
 *
 * Mass carries the runtime safety net required by §13.2: a constant mass that evaluates
 * non-positive is rejected immediately (the Kotlin-DSL equivalent of "reject at validation
 * time when statically checkable" — there's no later moment a constant could still be
 * checked at), while a dynamic (time-varying) mass that evaluates non-positive is clamped
 * to [MASS_EPSILON] and reported via [onWarning] instead of failing the run; either kind
 * evaluating to NaN/Infinity always throws, since that's a genuine blow-up rather than an
 * authoring mistake with a sane fallback.
 */
class ParticleStore(private val onWarning: (String) -> Unit = { System.err.println(it) }) {
    private var nextId = 0
    private val idToSlot = HashMap<Int, Int>()
    private val slotToId = ArrayList<Int>() // -1 = free/unused slot

    private val freeSlots = ArrayDeque<Int>()

    private val posX = GrowableDoubleArray()
    private val posY = GrowableDoubleArray()
    private val posZ = GrowableDoubleArray()
    private val velX = GrowableDoubleArray()
    private val velY = GrowableDoubleArray()
    private val velZ = GrowableDoubleArray()

    // Scratch buffer: recomputed from net force / mass each physics step (§3). Not part of
    // checkpoint state (§9.5 doesn't list it) — stored here only so forces have somewhere
    // to accumulate into once Phase 2 wires up the integrator.
    private val accX = GrowableDoubleArray()
    private val accY = GrowableDoubleArray()
    private val accZ = GrowableDoubleArray()

    private val massArr = GrowableDoubleArray()
    private val radiusArr = GrowableDoubleArray() // NaN = unset
    private val spawnTimeArr = GrowableDoubleArray()
    private val lifetimeArr = GrowableDoubleArray() // NaN = unset

    private val massExprs = HashMap<Int, ScalarExpr.OfTime>()
    private val radiusExprs = HashMap<Int, ScalarExpr.OfTime>()
    private val lifetimeExprs = HashMap<Int, ScalarExpr.OfTime>()

    /** Number of live particles. */
    val size: Int get() = idToSlot.size

    /** Number of backing-array rows currently allocated (live + reclaimable-but-unshrunk). */
    internal val capacity: Int get() = slotToId.size

    fun create(
        position: Vector3 = Vector3.ZERO,
        velocity: Vector3 = Vector3.ZERO,
        mass: ScalarExpr = ScalarExpr.of(1.0),
        radius: ScalarExpr? = null,
        spawnTime: Double = 0.0,
        lifetime: ScalarExpr? = null,
    ): Int {
        val id = nextId++
        val slot = if (freeSlots.isNotEmpty()) freeSlots.removeLast() else allocateSlot()

        idToSlot[id] = slot
        slotToId[slot] = id

        posX[slot] = position.x; posY[slot] = position.y; posZ[slot] = position.z
        velX[slot] = velocity.x; velY[slot] = velocity.y; velZ[slot] = velocity.z
        accX[slot] = 0.0; accY[slot] = 0.0; accZ[slot] = 0.0

        massArr[slot] = evaluateMassGuarded(mass, spawnTime, id)
        setOrClearExpr(massExprs, id, mass)

        radiusArr[slot] = radius?.evaluate(spawnTime) ?: Double.NaN
        setOrClearExpr(radiusExprs, id, radius)

        spawnTimeArr[slot] = spawnTime

        lifetimeArr[slot] = lifetime?.evaluate(spawnTime) ?: Double.NaN
        setOrClearExpr(lifetimeExprs, id, lifetime)

        return id
    }

    fun destroy(id: Int) {
        val slot = idToSlot.remove(id) ?: throw IllegalArgumentException("no such particle: $id")
        slotToId[slot] = -1
        freeSlots.addLast(slot)
        massExprs.remove(id)
        radiusExprs.remove(id)
        lifetimeExprs.remove(id)
    }

    fun contains(id: Int): Boolean = idToSlot.containsKey(id)

    /** Live particle ids, in backing-slot order — deterministic given the same create/destroy
     * history, independent of hash-map iteration order (§11). */
    fun liveIds(): List<Int> {
        val result = ArrayList<Int>(size)
        for (slot in 0 until capacity) {
            val id = slotToId[slot]
            if (id != -1) result.add(id)
        }
        return result
    }

    /** Re-evaluates every particle's time-varying mass at [t] (§13.2's runtime safety net
     * applies here too); constant masses are already fixed at creation and untouched. */
    fun refreshDynamicMass(t: Double) {
        for ((id, expr) in massExprs) {
            massArr[slotOf(id)] = evaluateMassGuarded(expr, t, id)
        }
    }

    fun position(id: Int): Vector3 = slotOf(id).let { Vector3(posX[it], posY[it], posZ[it]) }
    fun setPosition(id: Int, value: Vector3) {
        val s = slotOf(id); posX[s] = value.x; posY[s] = value.y; posZ[s] = value.z
    }

    fun velocity(id: Int): Vector3 = slotOf(id).let { Vector3(velX[it], velY[it], velZ[it]) }
    fun setVelocity(id: Int, value: Vector3) {
        val s = slotOf(id); velX[s] = value.x; velY[s] = value.y; velZ[s] = value.z
    }

    fun acceleration(id: Int): Vector3 = slotOf(id).let { Vector3(accX[it], accY[it], accZ[it]) }
    fun setAcceleration(id: Int, value: Vector3) {
        val s = slotOf(id); accX[s] = value.x; accY[s] = value.y; accZ[s] = value.z
    }

    fun mass(id: Int): Double = massArr[slotOf(id)]
    fun hasDynamicMass(id: Int): Boolean = massExprs.containsKey(id)

    fun radius(id: Int): Double? = slotOf(id).let { radiusArr[it] }.let { if (it.isNaN()) null else it }
    fun lifetime(id: Int): Double? = slotOf(id).let { lifetimeArr[it] }.let { if (it.isNaN()) null else it }
    fun spawnTime(id: Int): Double = spawnTimeArr[slotOf(id)]

    /** Visible to `particlesim.physics` (module-scoped): force accumulators are indexed the
     * same way as these backing arrays (§9.3), so the integrator needs id→slot too. */
    internal fun slotOf(id: Int): Int =
        idToSlot[id] ?: throw IllegalArgumentException("no such particle: $id")

    private fun setOrClearExpr(map: MutableMap<Int, ScalarExpr.OfTime>, id: Int, expr: ScalarExpr?) {
        if (expr is ScalarExpr.OfTime) map[id] = expr else map.remove(id)
    }

    private fun evaluateMassGuarded(expr: ScalarExpr, t: Double, id: Int): Double {
        val raw = expr.evaluate(t)
        if (raw.isNaN() || raw.isInfinite()) {
            throw IllegalStateException("particle $id: mass expression evaluated to $raw at t=$t")
        }
        if (raw <= 0.0) {
            if (expr is ScalarExpr.Constant) {
                throw IllegalArgumentException("particle $id: constant mass must be positive, was $raw")
            }
            onWarning("particle $id: mass evaluated to $raw at t=$t, clamped to $MASS_EPSILON")
            return MASS_EPSILON
        }
        return raw
    }

    companion object {
        const val MASS_EPSILON = 1e-9
    }

    private fun allocateSlot(): Int {
        val slot = slotToId.size
        slotToId.add(-1)
        posX.add(0.0); posY.add(0.0); posZ.add(0.0)
        velX.add(0.0); velY.add(0.0); velZ.add(0.0)
        accX.add(0.0); accY.add(0.0); accZ.add(0.0)
        massArr.add(0.0)
        radiusArr.add(Double.NaN)
        spawnTimeArr.add(0.0)
        lifetimeArr.add(Double.NaN)
        return slot
    }
}
