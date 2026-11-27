package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.ChunkAccumulator
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.Truss
import particlesim.physics.WeightedAttachment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The time step for [TowerCrane]: its truss is real steel at real masses, so it vibrates as fast
 * as real steel - the stiffest light node rings at about 3,500 rad/s, stable below ~0.55 ms.
 * Exactly 80 steps per 60 Hz frame.
 */
const val CRANE_DT = 1.0 / 4800

/** Wire rope's effective Young's modulus (lower than solid steel: the strands are twisted). */
const val WIRE_ROPE_MODULUS = 100e9

/**
 * A hammerhead tower crane's dimensions, masses and member sizes - by default an 8 t crane with a
 * 55 m jib and a 1.85 t tip load, after the Liebherr 132 EC-H 8, on a 1.6 m mast (the Potain
 * MC 175 class uses 1.6 m or 2 m). Masses and member areas aren't from a data sheet: they're
 * chosen so the structure weighs what such a crane does and its members work at 100-160 MPa at
 * the load chart's limits (the maximum load at its largest radius, or the tip load at the tip).
 * The ballast puts the crane leaning back unloaded about as much as it leans forward fully
 * loaded, the way real cranes are balanced.
 *
 * Coordinates: y up, the mast's axis at x = z = 0, the jib along +x at slew angle zero.
 */
data class TowerCraneSpec(
    val mastHeight: Double = 40.0,
    val mastWidth: Double = 1.6,
    val mastPanels: Int = 16,
    val turntableHeight: Double = 1.5,
    /** From the mast's axis to the jib's tip. */
    val jibLength: Double = 55.0,
    val jibPanels: Int = 22,
    val jibDepth: Double = 1.4,
    val counterJibLength: Double = 17.5,
    val counterJibPanels: Int = 7,
    val counterJibDepth: Double = 1.0,
    /** The tower head's apex above the turntable's top. */
    val towerHeadHeight: Double = 9.0,
    /** The jib top-chord panel point the jib tie holds up. */
    val jibTiePanel: Int = 13,
    val maxLoadTonnes: Double = 8.0,
    val maxLoadRadius: Double = 16.5,
    val tipLoadTonnes: Double = 1.85,
    // Masses, kg.
    val mastMassPerMetre: Double = 450.0,
    val jibMassPerMetre: Double = 160.0,
    val counterJibMass: Double = 4500.0,
    val winchMass: Double = 3000.0,
    val ballastMass: Double = 16500.0,
    val turntableMass: Double = 6000.0,
    val towerHeadMass: Double = 2500.0,
    val cabMass: Double = 1500.0,
    val trolleyMass: Double = 600.0,
    val hookBlockMass: Double = 400.0,
    // Member cross-section areas, m^2.
    val mastChordArea: Double = 60e-4,
    val mastWebArea: Double = 25e-4,
    val turntableArea: Double = 60e-4,
    val jibTopChordArea: Double = 40e-4,
    val jibBottomChordArea: Double = 30e-4,
    val jibWebArea: Double = 14e-4,
    val counterJibChordArea: Double = 60e-4,
    val counterJibWebArea: Double = 20e-4,
    val towerHeadLegArea: Double = 120e-4,
    val tieArea: Double = 40e-4,
    /** Four falls of 16 mm rope's steel. */
    val ropeArea: Double = 4 * 1.2e-4,
    // Failure (when it's on): S355 crane steel; members buckle by their slenderness, each taken
    // as a round tube of its area (diameter 20 times its wall); four falls of 16 mm rope at
    // about 160 kN minimum breaking load each.
    val yieldStress: Double = 355e6,
    val ruptureStrain: Double = 0.15,
    val ropeBreakingForce: Double = 4 * 160e3,
    // Drives: slewing at 0.8 rpm, the trolley and hoist at 60 m/min.
    val slewSpeedDegrees: Double = 4.8,
    val slewAccelerationDegrees: Double = 0.5,
    val trolleySpeed: Double = 1.0,
    val trolleyAcceleration: Double = 0.4,
    val hoistSpeed: Double = 1.0,
    val hoistAcceleration: Double = 0.5,
    val minRadius: Double = 3.5,
    val maxRadius: Double = 54.0,
    val minHookHeight: Double = 3.0,
) {
    val halfWidth: Double get() = mastWidth / 2
    val turntableTop: Double get() = mastHeight + turntableHeight
    val jibPanel: Double get() = (jibLength - halfWidth) / jibPanels
    val counterJibPanel: Double get() = (counterJibLength - halfWidth) / counterJibPanels
    val apexHeight: Double get() = turntableTop + towerHeadHeight
}

/** Corner [c] of a square of half-width [h] in the x-z plane, centred on the y axis:
 * (-h,-h), (h,-h), (h,h), (-h,h). */
fun squareCorner(c: Int, h: Double): Pair<Double, Double> = when (c) {
    0 -> -h to -h
    1 -> h to -h
    2 -> h to h
    else -> -h to h
}

/**
 * A slewing ring's coupling: the bilinear weights, over the four corners of [square] (in
 * [squareCorner] order, half-width [h]), of the place corner [c] reaches turned [degrees] about the
 * y axis (positive turns +x toward -z). A [WeightedAttachment] to them holds a turntable corner
 * there.
 */
fun slewedCornerWeights(square: List<Int>, h: Double, c: Int, degrees: Double): List<Pair<Int, Double>> {
    val theta = degrees * PI / 180
    val (x, z) = squareCorner(c, h)
    val qx = x * cos(theta) + z * sin(theta)
    val qz = -x * sin(theta) + z * cos(theta)
    val s = (qx / h + 1) / 2
    val t = (qz / h + 1) / 2
    return listOf(square[0] to (1 - s) * (1 - t), square[1] to s * (1 - t), square[2] to s * t, square[3] to (1 - s) * t)
}

/**
 * One axis of motion driven toward a [target] at up to [maxSpeed], speeding up and slowing down
 * at [acceleration] - how a crane's variable-speed drives move, so the load is started and
 * stopped smoothly but still swings. Lands on the target without overshooting.
 */
class Drive(
    position: Double,
    val maxSpeed: Double,
    val acceleration: Double,
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY,
) {
    var position: Double = position
        private set
    var velocity: Double = 0.0
        private set
    var target: Double = position
        set(value) { field = value.coerceIn(min, max) }

    val moving: Boolean get() = velocity != 0.0 || position != target

    fun advance(dt: Double) {
        val error = target - position
        if (abs(error) < SNAP && abs(velocity) < SNAP_SPEED) { position = target; velocity = 0.0; return }
        // The fastest speed from which it can still stop at the target - planned at a little
        // under its full deceleration, so the step-by-step speed can keep up with the plan.
        val wanted = sign(error) * minOf(maxSpeed, sqrt(2 * PLANNED_DECELERATION * acceleration * abs(error)))
        val step = acceleration * dt
        velocity = wanted.coerceIn(velocity - step, velocity + step)
        val move = velocity * dt
        // The last step: slow enough already to stop on the target rather than overshoot it.
        if (sign(move) == sign(error) && abs(move) >= abs(error) && abs(velocity) <= 2 * step + SNAP_SPEED) {
            position = target; velocity = 0.0
        } else {
            position += move
        }
    }

    private companion object {
        const val PLANNED_DECELERATION = 0.9
        const val SNAP = 1e-6
        const val SNAP_SPEED = 1e-2
    }
}

/**
 * Gravity on the particles of [targetGroups] (each skipped while disabled), ramped on smoothly
 * over [rampSeconds] so the crane settles under its own weight instead of dropping into it.
 * Reads each particle's mass every step, so a load picked up (the hook's mass changing) weighs
 * what it should. Membership is read once, at construction.
 */
class RampedGravity(
    targetGroups: List<String>,
    groups: Groups,
    private val rampSeconds: Double,
    override val name: String? = "gravity",
) : Force {
    private val ids: List<Int> = targetGroups.flatMap { groups.membersOf(it) }
    private val groupOf: List<String> = targetGroups.flatMap { g -> groups.membersOf(g).map { g } }

    fun factor(t: Double): Double = if (t >= rampSeconds) 1.0 else 0.5 - 0.5 * cos(PI * t / rampSeconds)

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        val g = STANDARD_GRAVITY * factor(t)
        var i = chunkIndex
        while (i < ids.size) {
            if (groups.isEnabled(groupOf[i])) chunk.add(store.slotOf(ids[i]), 0.0, -store.mass(ids[i]) * g, 0.0)
            i += chunkCount
        }
    }
}

/**
 * Structural damping that leaves slewing alone: a force `-c m (v - v_rigid)` on each node, where
 * `v_rigid` is the velocity the node would have if the slewing part ([slewingGroup]) turned
 * rigidly at the current slew rate about the mast's axis - zero for the mast ([fixedGroup]). It
 * damps the crane's sway and the jib's whip, not the slew itself; the hook and trolley aren't in
 * it, so the load swings freely. [coefficient] (1/s) is `2 zeta omega` for the modes it's tuned
 * to. Each group is skipped while disabled; membership is read once, at construction.
 *
 * A crane that travels as a whole (a gantry on its rails) gives [frameVelocity]: the moving part
 * is carried along at it too, so travelling isn't damped either - only the structure's movement
 * about the travelling frame.
 */
class SlewFrameDamping(
    private val slewingGroup: String,
    private val fixedGroup: String,
    groups: Groups,
    private val slewRate: () -> Double,
    coefficient: Double,
    override val name: String? = "structural damping",
    private val frameVelocity: () -> Vector3 = { Vector3.ZERO },
) : Force {
    var coefficient: Double = coefficient
    private val slewing: List<Int> = groups.membersOf(slewingGroup).toList()
    private val fixed: List<Int> = groups.membersOf(fixedGroup).toList()

    override fun accumulate(store: ParticleStore, groups: Groups, t: Double, chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int) {
        val c = coefficient
        if (c == 0.0) return
        val slewingOn = groups.isEnabled(slewingGroup)
        val fixedOn = groups.isEnabled(fixedGroup)
        val omega = slewRate()
        val frame = frameVelocity()
        val total = slewing.size + fixed.size
        var i = chunkIndex
        while (i < total) {
            val rotating = i < slewing.size
            if (if (rotating) !slewingOn else !fixedOn) { i += chunkCount; continue }
            val id = if (rotating) slewing[i] else fixed[i - slewing.size]
            val s = store.slotOf(id)
            val m = store.mass(id)
            // The frame's velocity plus omega x r for a turn about the y axis.
            val rx = if (rotating) frame.x + omega * store.zAt(s) else 0.0
            val ry = if (rotating) frame.y else 0.0
            val rz = if (rotating) frame.z - omega * store.xAt(s) else 0.0
            chunk.add(s, -c * m * (store.vxAt(s) - rx), -c * m * (store.vyAt(s) - ry), -c * m * (store.vzAt(s) - rz))
            i += chunkCount
        }
    }
}

/**
 * A hammerhead tower crane ([spec]) as a pin-jointed steel [Truss] at real stiffness and real
 * masses, so it moves at real speed: the load swings with a pendulum's period, the jib whips when
 * a slew stops.
 *
 * - **Mast**: a square lattice tower, four chords, braced on every face and in plan at every
 *   level, its base fixed to the foundation.
 * - **Slewing ring**: the turntable (a braced box) sits on the mast top, each of its four lower
 *   corners held by a [WeightedAttachment] at its position rotated by the slew angle within the
 *   mast-top square. That carries the jib's overturning moment down into the mast's chords as a
 *   couple, and the slew drive's torque into the mast as a twist.
 * - **Jib** and **counter-jib**: triangular lattice girders hinged at the turntable's top corners,
 *   each held up by one tie bar from the tower head's apex - so their tie tensions follow from
 *   statics alone. The counter-jib carries the hoist winch and the ballast at its end.
 * - **Trolley**: a particle held by a [WeightedAttachment] at its radius between the jib's bottom
 *   chords, so its load bears on the panel points either side.
 * - **Hook**: hangs from the trolley on a tension-only rope whose length the hoist pays out. Its
 *   mass is the hook block plus the load.
 *
 * Gravity ramps on over [rampSeconds] under heavy structural damping, held for [settlingSeconds]
 * more and then eased to about 2% of critical for the sway modes; the drives answer from
 * [readyAt].
 */
class TowerCrane(
    val spec: TowerCraneSpec = TowerCraneSpec(),
    val dt: Double = CRANE_DT,
    slewDegrees: Double = 0.0,
    radius: Double = 15.0,
    hookHeight: Double = 20.0,
    loadTonnes: Double = 0.0,
    val rampSeconds: Double = 3.0,
    /** How long the heavy damping holds after the ramp - the mast's first sway takes ~7 s. */
    val settlingSeconds: Double = 4.0,
    val easeSeconds: Double = 2.0,
) {
    val store = ParticleStore()
    val groups = Groups()
    private val h = spec.halfWidth

    /** A node; [radius] is for touching the ground once something has failed (null: never). */
    private fun node(p: Vector3, mass: Double, group: String, radius: Double? = NODE_RADIUS): Int {
        val id = store.create(position = p, mass = ScalarExpr.of(mass), radius = radius?.let { ScalarExpr.of(it) })
        groups.add(group, id)
        return id
    }

    private fun corner(c: Int): Pair<Double, Double> = squareCorner(c, h)

    // ---- Nodes.

    /** `mast[level][corner]`, level 0 on the foundation. */
    val mast: List<List<Int>> = (0..spec.mastPanels).map { level ->
        val y = spec.mastHeight * level / spec.mastPanels
        val mass = if (level == 0) 1.0 else spec.mastMassPerMetre * spec.mastHeight / (spec.mastPanels * 4)
        // The base stands on the ground: no radius, or the ground would push it every step.
        (0 until 4).map { c -> val (x, z) = corner(c); node(Vector3(x, y, z), mass, MAST_GROUP, if (level == 0) null else NODE_RADIUS) }
    }
    val mastTop: List<Int> get() = mast.last()

    // The cab sits by the jib's root on the +z side; the tower head's weight is shared between its
    // apex and its feet.
    private val turntableNodeMass = spec.turntableMass / 8 + spec.towerHeadMass / 2 / 4
    /** The turntable's lower corners, on the slewing ring. */
    val turntableLower: List<Int> = (0 until 4).map { c ->
        val (x, z) = corner(c)
        node(Vector3(x, spec.mastHeight, z), spec.turntableMass / 8 + if (c == 2) spec.cabMass / 2 else 0.0, SLEWING_GROUP)
    }
    /** The turntable's upper corners: the jib hinges on 1 and 2, the counter-jib on 0 and 3. */
    val turntableUpper: List<Int> = (0 until 4).map { c ->
        val (x, z) = corner(c)
        node(Vector3(x, spec.turntableTop, z), turntableNodeMass + if (c == 2) spec.cabMass / 2 else 0.0, SLEWING_GROUP)
    }
    val apex: Int = node(Vector3(0.0, spec.apexHeight, 0.0), spec.towerHeadMass / 2, SLEWING_GROUP)

    private val jibNodeMass = spec.jibMassPerMetre * (spec.jibLength - h) / (3 * spec.jibPanels)
    /** The jib's bottom chords (-z side, +z side) and top chord, panel point 0 at the turntable
     * (where the bottom chords hinge and the top chord has no node). */
    val jibLeft: List<Int> = listOf(turntableUpper[1]) + (1..spec.jibPanels).map { k -> node(Vector3(h + k * spec.jibPanel, spec.turntableTop, -h), jibNodeMass, SLEWING_GROUP) }
    val jibRight: List<Int> = listOf(turntableUpper[2]) + (1..spec.jibPanels).map { k -> node(Vector3(h + k * spec.jibPanel, spec.turntableTop, h), jibNodeMass, SLEWING_GROUP) }
    val jibTop: List<Int?> = listOf(null) + (1..spec.jibPanels).map { k -> node(Vector3(h + k * spec.jibPanel, spec.turntableTop + spec.jibDepth, 0.0), jibNodeMass, SLEWING_GROUP) }

    private val ballastPanels = (spec.counterJibPanels - 2)..spec.counterJibPanels
    private val winchPanel = spec.counterJibPanels / 2
    private fun counterJibBottomMass(k: Int) = spec.counterJibMass / (3 * spec.counterJibPanels) +
        (if (k in ballastPanels) spec.ballastMass / (2 * ballastPanels.count()) else 0.0) +
        (if (k == winchPanel) spec.winchMass / 2 else 0.0)
    val counterJibLeft: List<Int> = listOf(turntableUpper[0]) + (1..spec.counterJibPanels).map { k ->
        node(Vector3(-h - k * spec.counterJibPanel, spec.turntableTop, -h), counterJibBottomMass(k), SLEWING_GROUP)
    }
    val counterJibRight: List<Int> = listOf(turntableUpper[3]) + (1..spec.counterJibPanels).map { k ->
        node(Vector3(-h - k * spec.counterJibPanel, spec.turntableTop, h), counterJibBottomMass(k), SLEWING_GROUP)
    }
    val counterJibTop: List<Int?> = listOf(null) + (1..spec.counterJibPanels).map { k ->
        node(Vector3(-h - k * spec.counterJibPanel, spec.turntableTop + spec.counterJibDepth, 0.0), spec.counterJibMass / (3 * spec.counterJibPanels), SLEWING_GROUP)
    }

    /** The ballast's panel points on the counter-jib's bottom chords. */
    val ballastNodes: List<Int> = ballastPanels.flatMap { listOf(counterJibLeft[it], counterJibRight[it]) }

    val trolleyNode: Int = node(Vector3(radius.coerceIn(spec.minRadius, spec.maxRadius), spec.turntableTop, 0.0), spec.trolleyMass, TROLLEY_GROUP)
    var loadTonnes: Double = loadTonnes
        set(value) { require(value >= 0.0 && value.isFinite()); field = value }
    /** The hook's mass with [loadTonnes] on it - which it reaches gradually (see [step]). */
    private fun hookMass() = spec.hookBlockMass + 1000 * loadTonnes
    val hookNode: Int = node(Vector3(store.position(trolleyNode).x, hookHeight, 0.0), hookMass(), HOOK_GROUP, HOOK_RADIUS)

    // ---- Members.

    /** What each member is part of, for colouring and drawing. */
    enum class Part { MAST_CHORD, MAST_WEB, TURNTABLE, JIB_CHORD, JIB_WEB, COUNTER_JIB_CHORD, COUNTER_JIB_WEB, TOWER_HEAD, TIE, ROPE }

    private val memberList = ArrayList<Truss.Member>()
    private val partList = ArrayList<Part>()
    private fun member(a: Int, b: Int, area: Double, part: Part, tensionOnly: Boolean = false, modulus: Double = STEEL_YOUNGS_MODULUS, rest: Double? = null, damping: Double? = null): Int {
        val length = (store.position(b) - store.position(a)).length()
        memberList += if (part == Part.ROPE) {
            Truss.Member(a, b, area, modulus, tensionOnly, rest, damping, breakingForce = spec.ropeBreakingForce)
        } else {
            Truss.Member(
                a, b, area, modulus, tensionOnly, rest, damping,
                yieldStress = spec.yieldStress,
                bucklingStress = if (tensionOnly) null else Truss.rankineBucklingStress(spec.yieldStress, modulus, length, Truss.tubeRadiusOfGyration(area)),
                ruptureStrain = spec.ruptureStrain,
            )
        }
        partList += part
        return memberList.size - 1
    }

    /** The bottom panel's four chords, in corner order - where the mast carries the whole crane. */
    val mastBaseChords: List<Int>
    val jibTie: Int
    val counterJibTie: Int
    val rope: Int

    init {
        // Mast: chords, a diagonal on every face (alternating), and horizontals plus both plan
        // diagonals at every level - a square tube is a mechanism without plan bracing.
        val baseChords = ArrayList<Int>()
        for (level in 0 until spec.mastPanels) {
            val lo = mast[level]; val hi = mast[level + 1]
            for (c in 0 until 4) {
                val chord = member(lo[c], hi[c], spec.mastChordArea, Part.MAST_CHORD)
                if (level == 0) baseChords += chord
                val n = (c + 1) % 4
                if ((level + c) % 2 == 0) member(lo[c], hi[n], spec.mastWebArea, Part.MAST_WEB) else member(lo[n], hi[c], spec.mastWebArea, Part.MAST_WEB)
                member(hi[c], hi[n], spec.mastWebArea, Part.MAST_WEB)
            }
            member(hi[0], hi[2], spec.mastWebArea, Part.MAST_WEB)
            member(hi[1], hi[3], spec.mastWebArea, Part.MAST_WEB)
        }
        mastBaseChords = baseChords

        // Turntable: a box, braced on every face, both diagonals top and bottom.
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            member(turntableLower[c], turntableUpper[c], spec.turntableArea, Part.TURNTABLE)
            member(turntableLower[c], turntableLower[n], spec.turntableArea, Part.TURNTABLE)
            member(turntableUpper[c], turntableUpper[n], spec.turntableArea, Part.TURNTABLE)
            member(turntableLower[c], turntableUpper[n], spec.turntableArea, Part.TURNTABLE)
        }
        for ((a, b) in listOf(0 to 2, 1 to 3)) {
            member(turntableLower[a], turntableLower[b], spec.turntableArea, Part.TURNTABLE)
            member(turntableUpper[a], turntableUpper[b], spec.turntableArea, Part.TURNTABLE)
        }

        // Tower head: four legs from the turntable's top corners to the apex.
        for (c in 0 until 4) member(turntableUpper[c], apex, spec.towerHeadLegArea, Part.TOWER_HEAD)

        girder(jibLeft, jibRight, jibTop, spec.jibBottomChordArea, spec.jibTopChordArea, spec.jibWebArea, Part.JIB_CHORD, Part.JIB_WEB)
        girder(counterJibLeft, counterJibRight, counterJibTop, spec.counterJibChordArea, spec.counterJibChordArea, spec.counterJibWebArea, Part.COUNTER_JIB_CHORD, Part.COUNTER_JIB_WEB)

        jibTie = member(apex, jibTop[spec.jibTiePanel]!!, spec.tieArea, Part.TIE, tensionOnly = true)
        counterJibTie = member(apex, counterJibTop.last()!!, spec.tieArea, Part.TIE, tensionOnly = true)

        // Its rest length is set every step from the hoist's loaded length (see unstretched).
        rope = member(trolleyNode, hookNode, spec.ropeArea, Part.ROPE, tensionOnly = true, modulus = WIRE_ROPE_MODULUS,
            rest = (store.position(trolleyNode) - store.position(hookNode)).length(), damping = ROPE_DAMPING)
    }

    /** A triangular girder from the turntable: two bottom chords and a top chord, a Warren web
     * (vertical and diagonal) on each sloping face, and cross members with diagonals across the
     * bottom. The top chord starts at panel point 1, braced back to both root nodes, so the
     * girder hinges about the line through them. */
    private fun girder(left: List<Int>, right: List<Int>, top: List<Int?>, bottomArea: Double, topArea: Double, webArea: Double, chordPart: Part, webPart: Part) {
        for (k in 0 until left.lastIndex) {
            member(left[k], left[k + 1], bottomArea, chordPart)
            member(right[k], right[k + 1], bottomArea, chordPart)
            if (k >= 1) member(top[k]!!, top[k + 1]!!, topArea, chordPart)
            val t = top[k + 1]!!
            member(left[k], t, webArea, webPart)
            member(right[k], t, webArea, webPart)
            member(left[k + 1], t, webArea, webPart)
            member(right[k + 1], t, webArea, webPart)
            member(left[k + 1], right[k + 1], webArea, webPart)
            member(left[k], right[k + 1], webArea, webPart)
        }
    }

    val parts: List<Part> = partList
    /** Stiffness damping: half-critical for the fastest members' ringing. */
    val truss = Truss(store, memberList, stiffnessDamping = 3e-4, name = "steel")

    // ---- Drives and couplings.

    val slew = Drive(slewDegrees, spec.slewSpeedDegrees, spec.slewAccelerationDegrees)
    val trolley = Drive(store.position(trolleyNode).x, spec.trolleySpeed, spec.trolleyAcceleration, spec.minRadius, spec.maxRadius)
    var hookHeightTarget: Double = hookHeight
        set(value) { field = value.coerceIn(spec.minHookHeight, spec.turntableTop - MIN_ROPE) }
    /** The rope's length from trolley to hook with the hook hanging still: what the hoist
     * controls. */
    private val hoist = Drive(truss.restLength(rope), spec.hoistSpeed, spec.hoistAcceleration, MIN_ROPE, spec.turntableTop - spec.minHookHeight)
    private val ropeAxialStiffness = WIRE_ROPE_MODULUS * spec.ropeArea
    private var trolleyHeight = store.position(trolleyNode).y

    /** The unstretched rope that hangs the hook's weight - at gravity [t]'s ramp - at the
     * hoist's length, so the hook neither drops onto the rope nor is flung up by it. */
    private fun unstretched(t: Double): Double =
        hoist.position / (1 + store.mass(hookNode) * STANDARD_GRAVITY * gravity.factor(t) / ropeAxialStiffness)

    /** The slewing ring: each turntable lower corner held at its slewed place on the mast top. */
    val slewingRing: List<WeightedAttachment> = turntableLower.map { id ->
        val m = store.mass(id)
        // Unnamed: four identical entries would only clutter the outliner.
        WeightedAttachment(id, RING_STIFFNESS, 2 * 0.5 * sqrt(RING_STIFFNESS * m))
    }
    // Unnamed: it has nothing to edit, and "trolley" is already the group's name.
    val trolleyCarriage = WeightedAttachment(trolleyNode, TROLLEY_STIFFNESS, 2 * 0.5 * sqrt(TROLLEY_STIFFNESS * spec.trolleyMass))

    /** Slew rate, rad/s. */
    val slewRate: Double get() = slew.velocity * PI / 180

    val structuralDamping = SlewFrameDamping(SLEWING_GROUP, MAST_GROUP, groups, { slewRate }, SETTLING_DAMPING)
    val gravity = RampedGravity(listOf(MAST_GROUP, SLEWING_GROUP, TROLLEY_GROUP, HOOK_GROUP), groups, rampSeconds)
    val forces: List<Force> = listOf(truss, gravity, structuralDamping, trolleyCarriage) + slewingRing

    /** Whether the steel can fail (see [Truss]): off, it stays elastic however it's loaded. */
    var failure: Boolean
        get() = truss.failure
        set(value) { truss.failure = value }

    /** The ground, for whatever falls once something has failed. */
    val collisions = CollisionSystem(
        listOf(MAST_GROUP, SLEWING_GROUP, TROLLEY_GROUP, HOOK_GROUP).map { group ->
            ParticleColliderRule(group, PlaneCollider(VectorExpr.of(Vector3.ZERO), Vector3(0.0, 1.0, 0.0), name = "ground"), restitution = 0.0, staticFriction = 0.6, kineticFriction = 0.5)
        },
    )
    val constraints: List<Constraint> = run {
        mast[0].forEach { groups.add(FOUNDATION_GROUP, it) }
        listOf(FixedPosition.atCurrentPositions(FOUNDATION_GROUP, store, groups, name = "foundation"))
    }

    private val integrator = Integrator()

    init { updateCouplings(0.0) }

    /** The bilinear weights, over the mast top's corners, of turntable corner [c] slewed by
     * [degrees]. */
    fun ringWeights(c: Int, degrees: Double): List<Pair<Int, Double>> = slewedCornerWeights(mastTop, h, c, degrees)

    /** The trolley's weights over the jib's bottom-chord panel points at [radius]. */
    fun trolleyWeights(radius: Double): List<Pair<Int, Double>> {
        val along = (radius - h) / spec.jibPanel
        val k = along.toInt().coerceIn(0, spec.jibPanels - 1)
        val f = along - k
        return listOf(jibLeft[k] to (1 - f) / 2, jibLeft[k + 1] to f / 2, jibRight[k] to (1 - f) / 2, jibRight[k + 1] to f / 2)
    }

    private fun updateCouplings(t: Double) {
        for ((c, ring) in slewingRing.withIndex()) ring.setTarget(ringWeights(c, slew.position))
        trolleyCarriage.setTarget(trolleyWeights(trolley.position))
        truss.setRestLength(rope, unstretched(t))
    }

    /** One step of [dt] from time [t]: the drives move toward their targets, then the physics. */
    fun step(t: Double) {
        if (t >= readyAt) {
            slew.advance(dt)
            trolley.advance(dt)
            // Measured from where the trolley really is, sagged jib and leaning mast included -
            // the way the operator judges it - but smoothed over a few seconds: chasing the
            // trolley's every bob would feed the hook's bounce back into the hoist.
            trolleyHeight += (store.position(trolleyNode).y - trolleyHeight) * (dt / TROLLEY_HEIGHT_SMOOTHING)
            hoist.target = trolleyHeight - hookHeightTarget
            hoist.advance(dt)
        }
        // A load comes on (or off) the hook over a second or two, the rope taking it up, rather
        // than all at once - which would drop the hook onto the rope at twice the load.
        val mass = store.mass(hookNode)
        val wanted = hookMass()
        if (mass != wanted) {
            val stepMass = LOAD_RAMP_RATE * dt
            store.setMass(hookNode, ScalarExpr.of(if (abs(wanted - mass) <= stepMass) wanted else mass + sign(wanted - mass) * stepMass), t)
        }
        updateCouplings(t)
        structuralDamping.coefficient = dampingAt(t)
        integrator.step(store, groups, forces, constraints, t, dt)
        collisions.resolve(store, groups, t, dt)
    }

    /** When the crane has settled under its own weight and the drives start answering. */
    val readyAt: Double get() = rampSeconds + settlingSeconds

    /** Heavy while gravity comes on and for [settlingSeconds] after, then easing to the
     * operating level over [easeSeconds]. */
    fun dampingAt(t: Double): Double {
        val easeStart = readyAt
        if (t <= easeStart) return SETTLING_DAMPING
        if (t >= easeStart + easeSeconds) return OPERATING_DAMPING
        val f = (t - easeStart) / easeSeconds
        return SETTLING_DAMPING + (OPERATING_DAMPING - SETTLING_DAMPING) * f
    }

    /** The trolley's actual radius and the hook's actual height. */
    val radius: Double get() = trolley.position
    val slewDegrees: Double get() = slew.position

    companion object {
        const val MAST_GROUP = "mast"
        const val SLEWING_GROUP = "slewing parts"
        const val TROLLEY_GROUP = "trolley"
        const val HOOK_GROUP = "hook"
        const val FOUNDATION_GROUP = "foundation"
        const val RING_STIFFNESS = 5e8
        const val TROLLEY_STIFFNESS = 1e8
        const val MIN_ROPE = 1.0
        const val NODE_RADIUS = 0.1
        const val HOOK_RADIUS = 0.3
        /** kg/s: how fast a load comes onto or off the hook - 8 t in 2 s. */
        const val LOAD_RAMP_RATE = 4000.0
        /** s: wire rope's internal friction - about 10% of critical for a loaded hook bouncing
         * on 20 m of rope. */
        const val ROPE_DAMPING = 0.012
        const val TROLLEY_HEIGHT_SMOOTHING = 3.0
        /** 1/s: about critical for the mast's first sway (a 7 s period) while it settles. */
        const val SETTLING_DAMPING = 1.8
        /** 1/s: about 6% of critical for the mast's first sway, 2-3% for the jib's modes. */
        const val OPERATING_DAMPING = 0.12
    }
}
