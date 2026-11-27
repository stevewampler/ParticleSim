package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A slewing drive that only twists (§5.1): it turns [group]'s particles about the vertical axis
 * through ([axisX], [axisZ]) toward an angle, with a torque `k (θ_target - θ) + c (ω_target - ω)`
 * shared out as forces tangential to the axis, each particle's `τ / (n r)`. For particles spread
 * evenly round the axis these add up to a pure couple - no net force, so nothing pushes the
 * structure sideways or holds it up; only its turning is driven. The reaction goes into the
 * ground. A derrick's bull wheel does this: the wheel at the mast's foot, turned by ropes, swings
 * the mast and boom while the foot pin and the guys carry everything else.
 *
 * Angles are measured as the cranes slew: positive turns +x toward -z (right-handed about +y).
 * The particles' angle `θ` is the mean of each one's turn from where it was at construction,
 * taken there to be [startDegrees]; each is wrapped against the target, so the target may wind
 * round any number of turns. [target] and [targetRate] (degrees, degrees a second) are read every
 * step. Skipped while [group] is disabled; membership is read once, at construction.
 *
 * Deterministic under chunking (§9.3): the first chunk applies it all.
 */
class BullWheel(
    private val group: String,
    groups: Groups,
    store: ParticleStore,
    private val stiffness: Double,
    private val damping: Double,
    startDegrees: Double = 0.0,
    private val target: () -> Double,
    private val targetRate: () -> Double = { 0.0 },
    private val axisX: Double = 0.0,
    private val axisZ: Double = 0.0,
    override val name: String? = "bull wheel",
) : Force {
    private val ids: IntArray = groups.membersOf(group).toIntArray()
    /** Each particle's angle at slew zero, radians. */
    private val zeroAngle = DoubleArray(ids.size) { angleOf(store, ids[it]) - startDegrees * PI / 180 }

    init {
        require(ids.isNotEmpty()) { "the bull wheel's group needs particles" }
        require(ids.all { radiusOf(store, it) > 0.0 }) { "a particle on the axis can't be turned" }
    }

    private fun angleOf(store: ParticleStore, id: Int): Double {
        val p = store.position(id)
        return atan2(-(p.z - axisZ), p.x - axisX)
    }

    private fun radiusOf(store: ParticleStore, id: Int): Double {
        val p = store.position(id)
        val dx = p.x - axisX; val dz = p.z - axisZ
        return sqrt(dx * dx + dz * dz)
    }

    /** How far the particles lag the target, radians (positive: behind it). */
    fun lag(store: ParticleStore): Double {
        val goal = target() * PI / 180
        var sum = 0.0
        for (i in ids.indices) sum += wrap(goal + zeroAngle[i] - angleOf(store, ids[i]))
        return sum / ids.size
    }

    /** Their mean turning rate, radians a second. */
    fun rate(store: ParticleStore): Double {
        var sum = 0.0
        for (id in ids) {
            val p = store.position(id); val v = store.velocity(id)
            val dx = p.x - axisX; val dz = p.z - axisZ
            sum += (dz * v.x - dx * v.z) / (dx * dx + dz * dz)
        }
        return sum / ids.size
    }

    /** The torque it applies now, N·m (positive turning +x toward -z). */
    fun torque(store: ParticleStore): Double = stiffness * lag(store) + damping * (targetRate() * PI / 180 - rate(store))

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        if (chunkIndex != 0 || !groups.isEnabled(group)) return
        val tau = torque(store)
        val share = tau / ids.size
        for (id in ids) {
            val p = store.position(id)
            val dx = p.x - axisX; val dz = p.z - axisZ
            // Tangent (up x r) / r, force tau / (n r): together (dz, 0, -dx) * tau / (n r^2).
            val f = share / (dx * dx + dz * dz)
            chunk.add(store.slotOf(id), f * dz, 0.0, -f * dx)
        }
    }

    private fun wrap(a: Double): Double {
        var x = a % (2 * PI)
        if (x > PI) x -= 2 * PI
        if (x <= -PI) x += 2 * PI
        return x
    }
}
