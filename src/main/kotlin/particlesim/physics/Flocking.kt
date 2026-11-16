package particlesim.physics

import particlesim.collision.SpatialGrid
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.sqrt

/**
 * Self-propelled flocking (§5.2) - the steering a bird in a starling murmuration applies to
 * itself, after Reynolds' boids (1987) as refined for starlings by field measurement and
 * modelling:
 *
 * - **Topological neighbours.** Each bird responds to its [neighbours] nearest flockmates, however
 *   far apart they are, not to everything within a fixed radius: Ballerini et al., "Interaction
 *   ruling animal collective behavior depends on topological rather than metric distance"
 *   (PNAS 105(4), 2008), reconstructed real starling flocks in 3D and found each bird interacts
 *   with about 6-7 nearest neighbours. That's what keeps a flock cohering as it stretches and
 *   thins - a metric rule would let it fall apart wherever it spreads out. [perceptionRadius]
 *   only caps how far a bird can see at all.
 * - **Separation, alignment, cohesion** over those neighbours: move away from any closer than
 *   [separationDistance], match their mean velocity, steer toward their centroid.
 * - **Cruise speed.** Birds fly near a preferred speed ([cruiseSpeed]) and relax back to it with
 *   time constant [speedRelaxation] - without this, boids either run away in speed or stall.
 * - **Roost attraction.** Pre-roost murmurations stay over their roosting site: a bird farther
 *   than [roostRadius] horizontally from [roostCenter] is pulled back ([roostStrength] per metre
 *   beyond it), and every bird toward the display altitude, `roostCenter.y`
 *   ([altitudeStrength] per metre off it). This follows Hildenbrandt, Carere & Hemelrijk,
 *   "Self-organized aerial displays of thousands of starlings: a model" (Behavioral Ecology
 *   21(6), 2010), whose flocks likewise stay above a sleeping site at a certain height.
 * - **Predator evasion.** Birds within [fleeRadius] of any particle in [predatorGroup] (a falcon)
 *   flee straight away from it, harder the closer it is ([fleeStrength] at contact). Hemelrijk's
 *   group showed the dark "agitation waves" and splits of real murmurations come from birds
 *   evading a predator and their neighbours copying them.
 *
 * Weights are accelerations (m/s² per unit of the term), so a heavier bird steers the same as a
 * light one: the force applied is mass times the summed steering, capped at [maxAcceleration]
 * in magnitude so a near-collision can't kick a bird hard enough to destabilize the integrator.
 * Gravity is deliberately not part of this - a flying bird's lift balances it - and a scene
 * shouldn't add [UniformGravity] to its birds.
 *
 * Not modelled: banking turns, wing-flap aerodynamics, a bird's limited field of view (Ballerini's
 * data show neighbours ahead and to the side count more than ones behind) and the pressure of
 * real roost entry at dusk.
 *
 * Each bird reads only the state at the start of the step, so - unlike [SphFluid] - the work
 * strides across chunks: every chunk rebuilds its own [SpatialGrid] (cheap, and nothing shared
 * between threads) and computes the birds at its stride, each bird's force landing in exactly
 * one chunk.
 */
class Flocking(
    private val group: String,
    private var neighbours: Int = 7,
    private var perceptionRadius: Double = 25.0,
    private var separationDistance: Double = 2.0,
    private var separationStrength: Double = 30.0,
    private var alignmentStrength: Double = 2.0,
    private var cohesionStrength: Double = 0.6,
    private var cruiseSpeed: Double = 10.0,
    private var speedRelaxation: Double = 1.0,
    private var roostCenter: Vector3 = Vector3.ZERO,
    private var roostRadius: Double = 60.0,
    private var roostStrength: Double = 0.05,
    private var altitudeStrength: Double = 0.15,
    private val predatorGroup: String? = null,
    private var fleeRadius: Double = 25.0,
    private var fleeStrength: Double = 40.0,
    private var maxAcceleration: Double = 40.0,
    override val name: String? = null,
    /** Grid cell size for the neighbour search - only speed depends on it; about the birds'
     * typical spacing is best. */
    private val cellSize: Double = 2.0,
) : Force, EditableFields {
    init {
        require(speedRelaxation > 0.0) { "speedRelaxation must be positive, was $speedRelaxation" }
    }

    override fun editableFields(): Map<String, FieldValue> = mapOf(
        "neighbours" to FieldValue.Scalar(neighbours.toDouble()),
        "perceptionRadius" to FieldValue.Scalar(perceptionRadius),
        "separationDistance" to FieldValue.Scalar(separationDistance),
        "separationStrength" to FieldValue.Scalar(separationStrength),
        "alignmentStrength" to FieldValue.Scalar(alignmentStrength),
        "cohesionStrength" to FieldValue.Scalar(cohesionStrength),
        "cruiseSpeed" to FieldValue.Scalar(cruiseSpeed),
        "speedRelaxation" to FieldValue.Scalar(speedRelaxation),
        "roostCenter" to FieldValue.Vector(roostCenter),
        "roostRadius" to FieldValue.Scalar(roostRadius),
        "roostStrength" to FieldValue.Scalar(roostStrength),
        "altitudeStrength" to FieldValue.Scalar(altitudeStrength),
        "fleeRadius" to FieldValue.Scalar(fleeRadius),
        "fleeStrength" to FieldValue.Scalar(fleeStrength),
        "maxAcceleration" to FieldValue.Scalar(maxAcceleration),
    )

    override fun setField(field: String, value: FieldValue): Boolean {
        if (field == "roostCenter") {
            if (value !is FieldValue.Vector) return false
            roostCenter = value.value
            return true
        }
        if (value !is FieldValue.Scalar) return false
        val v = value.value
        when (field) {
            "neighbours" -> neighbours = v.toInt().coerceAtLeast(0)
            "perceptionRadius" -> perceptionRadius = v.coerceAtLeast(0.0)
            "separationDistance" -> separationDistance = v.coerceAtLeast(0.0)
            "separationStrength" -> separationStrength = v
            "alignmentStrength" -> alignmentStrength = v
            "cohesionStrength" -> cohesionStrength = v
            "cruiseSpeed" -> cruiseSpeed = v.coerceAtLeast(0.0)
            "speedRelaxation" -> if (v > 0.0) speedRelaxation = v else return false
            "roostRadius" -> roostRadius = v.coerceAtLeast(0.0)
            "roostStrength" -> roostStrength = v
            "altitudeStrength" -> altitudeStrength = v
            "fleeRadius" -> fleeRadius = v.coerceAtLeast(0.0)
            "fleeStrength" -> fleeStrength = v
            "maxAcceleration" -> maxAcceleration = v.coerceAtLeast(0.0)
            else -> return false
        }
        return true
    }

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        if (!groups.isEnabled(group)) return
        val members = groups.membersOf(group).toList()
        if (members.isEmpty()) return
        val grid = SpatialGrid(cellSize)
        for (id in members) grid.insert(id, store.position(id))
        val predators = if (predatorGroup != null && groups.isEnabled(predatorGroup)) {
            groups.membersOf(predatorGroup).map { store.position(it) }
        } else emptyList()

        var i = chunkIndex
        while (i < members.size) {
            val id = members[i]
            chunk.add(store.slotOf(id), steering(store, grid, id, predators) * store.mass(id))
            i += chunkCount
        }
    }

    /** Bird [id]'s steering acceleration, capped at [maxAcceleration]. */
    private fun steering(store: ParticleStore, grid: SpatialGrid, id: Int, predators: List<Vector3>): Vector3 {
        val pos = store.position(id)
        val vel = store.velocity(id)
        var accel = Vector3.ZERO

        val near = grid.nearest(pos, neighbours, perceptionRadius, exclude = id, positionOf = store::position)
        if (near.isNotEmpty()) {
            var separation = Vector3.ZERO
            var meanVel = Vector3.ZERO
            var centroid = Vector3.ZERO
            for (j in near) {
                val pj = store.position(j)
                meanVel += store.velocity(j)
                centroid += pj
                val away = pos - pj
                val d = away.length()
                if (d < separationDistance && d > 1e-9) separation += away * ((1.0 - d / separationDistance) / d)
            }
            val n = 1.0 / near.size
            accel += separation * separationStrength
            accel += (meanVel * n - vel) * alignmentStrength
            accel += (centroid * n - pos) * cohesionStrength
        }

        val speed = vel.length()
        if (speed > 1e-9) accel += vel * ((cruiseSpeed - speed) / (speedRelaxation * speed))

        val offX = pos.x - roostCenter.x
        val offZ = pos.z - roostCenter.z
        val horizontal = sqrt(offX * offX + offZ * offZ)
        if (horizontal > roostRadius) {
            val pull = roostStrength * (horizontal - roostRadius) / horizontal
            accel += Vector3(-offX * pull, 0.0, -offZ * pull)
        }
        accel += Vector3(0.0, (roostCenter.y - pos.y) * altitudeStrength, 0.0)

        for (p in predators) {
            val away = pos - p
            val d = away.length()
            if (d < fleeRadius && d > 1e-9) accel += away * (fleeStrength * (1.0 - d / fleeRadius) / d)
        }

        val magnitude = accel.length()
        return if (magnitude > maxAcceleration) accel * (maxAcceleration / magnitude) else accel
    }
}

/**
 * A predator's chase (§5.2): each particle in [group] steers toward the nearest particle of
 * [targetGroup], wanting to fly at [chaseSpeed] straight at it and closing the gap between that
 * and its current velocity over [responseTime] - so it overshoots and has to turn back, as a
 * real falcon's stoop through a flock does. The acceleration is capped at [maxAcceleration].
 * Pairs with [Flocking]'s `predatorGroup`, whose birds flee it.
 *
 * It hunts in bouts, as a real falcon makes repeated passes rather than one endless chase: it
 * rests for [restSeconds], then chases for [huntSeconds], over and over (starting with the rest,
 * so a flock gets to form first). While resting it climbs back toward [restPoint] (flying at half
 * [chaseSpeed] and circling it, as it overshoots) - a peregrine waits above a murmuration and
 * stoops on it from there - which gives the flock time to re-form. With no [restPoint] it just
 * carries on at whatever velocity it had. [restSeconds] = 0 chases without a break.
 *
 * Nearest by a plain scan of [targetGroup] (there are only ever a handful of predators), ties
 * going to the lower id (§11). Contributes on chunk 0 only, like [Spring]: too little work to
 * split.
 */
class Pursuit(
    private val group: String,
    private val targetGroup: String,
    private var chaseSpeed: Double = 20.0,
    private var responseTime: Double = 1.5,
    private var maxAcceleration: Double = 30.0,
    private var huntSeconds: Double = 15.0,
    private var restSeconds: Double = 0.0,
    private var restPoint: Vector3? = null,
    override val name: String? = null,
) : Force, EditableFields {
    init {
        require(huntSeconds > 0.0) { "huntSeconds must be positive, was $huntSeconds" }
        require(responseTime > 0.0) { "responseTime must be positive, was $responseTime" }
    }

    override fun editableFields(): Map<String, FieldValue> = mapOf(
        "chaseSpeed" to FieldValue.Scalar(chaseSpeed),
        "responseTime" to FieldValue.Scalar(responseTime),
        "maxAcceleration" to FieldValue.Scalar(maxAcceleration),
        "huntSeconds" to FieldValue.Scalar(huntSeconds),
        "restSeconds" to FieldValue.Scalar(restSeconds),
    )

    /** Whether it's chasing at time [t] (see the class doc). */
    fun isHunting(t: Double): Boolean = restSeconds <= 0.0 || t.mod(huntSeconds + restSeconds) >= restSeconds

    override fun setField(field: String, value: FieldValue): Boolean {
        if (value !is FieldValue.Scalar) return false
        when (field) {
            "huntSeconds" -> if (value.value > 0.0) huntSeconds = value.value else return false
            "restSeconds" -> restSeconds = value.value.coerceAtLeast(0.0)
            "chaseSpeed" -> chaseSpeed = value.value.coerceAtLeast(0.0)
            "responseTime" -> if (value.value > 0.0) responseTime = value.value else return false
            "maxAcceleration" -> maxAcceleration = value.value.coerceAtLeast(0.0)
            else -> return false
        }
        return true
    }

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        if (chunkIndex != 0 || !groups.isEnabled(group) || !groups.isEnabled(targetGroup)) return
        val hunting = isHunting(t)
        val perch = restPoint
        if (!hunting && perch == null) return
        val targets = groups.membersOf(targetGroup)
        if (targets.isEmpty()) return
        for (id in groups.membersOf(group)) {
            val pos = store.position(id)
            if (!hunting) {
                val toPerch = perch!! - pos
                val d = toPerch.length()
                if (d > 1e-9) chunk.add(store.slotOf(id), steer(toPerch * (0.5 * chaseSpeed / d), store.velocity(id)) * store.mass(id))
                continue
            }
            var best = -1
            var bestSq = Double.POSITIVE_INFINITY
            for (j in targets) {
                val d = (store.position(j) - pos).lengthSquared()
                if (d < bestSq || (d == bestSq && j < best)) { bestSq = d; best = j }
            }
            if (bestSq < 1e-18) continue
            val desired = (store.position(best) - pos) * (chaseSpeed / sqrt(bestSq))
            chunk.add(store.slotOf(id), steer(desired, store.velocity(id)) * store.mass(id))
        }
    }

    /** The acceleration closing [velocity] on [desired] over [responseTime], capped. */
    private fun steer(desired: Vector3, velocity: Vector3): Vector3 {
        val accel = (desired - velocity) * (1.0 / responseTime)
        val magnitude = accel.length()
        return if (magnitude > maxAcceleration) accel * (maxAcceleration / magnitude) else accel
    }
}
