package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.Truss
import particlesim.physics.WeightedAttachment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A luffing-jib tower crane's dimensions, masses and member sizes - by default a 12 t crane with a
 * 50 m jib working between 15 and 85 degrees, 3 t at its longest reach. Not from a data sheet: the
 * masses are chosen so the structure weighs about what a crane of that class does, and the member
 * areas so its members work at roughly 80-160 MPa at the load chart's limits (the maximum load
 * at its largest radius, the tip load with the jib right down). The ballast puts the crane leaning
 * back with the jib raised and no load, and forward at the chart's limits.
 *
 * Coordinates: y up, the mast's axis at x = z = 0, the jib along +x at slew angle zero. Jib angles
 * are degrees above horizontal.
 */
data class LuffingCraneSpec(
    val mastHeight: Double = 40.0,
    val mastWidth: Double = 2.0,
    val mastPanels: Int = 16,
    val turntableHeight: Double = 1.5,
    /** From the jib's foot pins to its head sheave. */
    val jibLength: Double = 50.0,
    val jibPanels: Int = 20,
    /** The jib's lattice: as wide as the mast, this deep. */
    val jibDepth: Double = 1.6,
    /** The jib head, from its last panel to the head sheave. */
    val jibHeadLength: Double = 1.0,
    /** The A-frame's apex above the turntable's top, where the luffing rope runs from. */
    val aFrameHeight: Double = 12.0,
    val counterJibLength: Double = 14.0,
    val counterJibPanels: Int = 6,
    val counterJibDepth: Double = 1.0,
    val minJibAngle: Double = 15.0,
    val maxJibAngle: Double = 85.0,
    val maxLoadTonnes: Double = 12.0,
    val maxLoadRadius: Double = 20.0,
    val tipLoadTonnes: Double = 3.0,
    // Masses, kg.
    val mastMassPerMetre: Double = 600.0,
    val jibMassPerMetre: Double = 200.0,
    val jibHeadMass: Double = 800.0,
    val counterJibMass: Double = 4000.0,
    val hoistWinchMass: Double = 3000.0,
    val luffingWinchMass: Double = 4000.0,
    val ballastMass: Double = 14000.0,
    val turntableMass: Double = 6000.0,
    val aFrameMass: Double = 3000.0,
    val cabMass: Double = 1500.0,
    val hookBlockMass: Double = 500.0,
    // Member cross-section areas, m^2.
    val mastChordArea: Double = 80e-4,
    val mastWebArea: Double = 30e-4,
    val turntableArea: Double = 80e-4,
    val jibChordArea: Double = 30e-4,
    val jibWebArea: Double = 10e-4,
    val jibHeadArea: Double = 30e-4,
    val counterJibChordArea: Double = 60e-4,
    val counterJibWebArea: Double = 20e-4,
    val aFrameLegArea: Double = 150e-4,
    val tieArea: Double = 60e-4,
    /** Six falls of 20 mm rope's steel between the A-frame and the jib head. */
    val luffingRopeArea: Double = 6 * 1.9e-4,
    /** Four falls of 16 mm rope's steel. */
    val hoistRopeArea: Double = 4 * 1.2e-4,
    // Failure (when it's on): S355 steel, members buckling by their slenderness as round tubes
    // (see Truss.tubeRadiusOfGyration); rope at about 250 kN (20 mm) and 160 kN (16 mm) minimum
    // breaking load a fall.
    val yieldStress: Double = 355e6,
    val ruptureStrain: Double = 0.15,
    val luffingRopeBreakingForce: Double = 6 * 250e3,
    val hoistRopeBreakingForce: Double = 4 * 160e3,
    // Drives: slewing at 0.8 rpm, luffing at 1 degree a second (bottom to top in about 70 s),
    // hoisting at 90 m/min - quick enough to hold the hook level while the jib luffs.
    val slewSpeedDegrees: Double = 4.8,
    val slewAccelerationDegrees: Double = 0.5,
    val luffSpeedDegrees: Double = 1.0,
    val luffAccelerationDegrees: Double = 0.3,
    val hoistSpeed: Double = 1.5,
    val hoistAcceleration: Double = 0.6,
    val minHookHeight: Double = 3.0,
) {
    val halfWidth: Double get() = mastWidth / 2
    val turntableTop: Double get() = mastHeight + turntableHeight
    val jibPanel: Double get() = (jibLength - jibHeadLength) / jibPanels
    val counterJibPanel: Double get() = (counterJibLength - halfWidth) / counterJibPanels
    val apexHeight: Double get() = turntableTop + aFrameHeight

    /** The head sheave's distance from the mast's axis, the jib straight at [degrees]. */
    fun headRadius(degrees: Double): Double {
        val a = degrees * PI / 180
        return halfWidth + jibLength * cos(a) - jibDepth / 2 * sin(a)
    }

    /** The head sheave's height, the jib straight at [degrees]. */
    fun headHeight(degrees: Double): Double {
        val a = degrees * PI / 180
        return turntableTop + jibLength * sin(a) + jibDepth / 2 * cos(a)
    }

    /** The luffing rope's length, apex to head sheave, the jib straight at [degrees]. */
    fun luffingRopeLength(degrees: Double): Double = hypot(headRadius(degrees), headHeight(degrees) - apexHeight)

    val minRadius: Double get() = headRadius(maxJibAngle)
    val maxRadius: Double get() = headRadius(minJibAngle)

    /** The jib angle that puts the head sheave (and so the hook) at [radius], clamped to the
     * jib's range. */
    fun jibAngleFor(radius: Double): Double {
        if (radius >= maxRadius) return minJibAngle
        if (radius <= minRadius) return maxJibAngle
        var lo = minJibAngle
        var hi = maxJibAngle
        repeat(60) {
            val mid = (lo + hi) / 2
            if (headRadius(mid) > radius) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}

/**
 * A luffing-jib tower crane ([spec]) as a pin-jointed steel [Truss] at real stiffness and real
 * masses, moving at real speed, like [TowerCrane] - but instead of a trolley running along a level
 * jib, the whole jib tilts up and down ("luffs") to bring the hook in and out, so it can work
 * where a level jib would swing over its neighbours.
 *
 * - **Mast** and **slewing ring**: as [TowerCrane]'s - a braced square lattice tower fixed at its
 *   base, the turntable's lower corners held at their slewed places on the mast top.
 * - **Jib**: a box lattice boom pinned at its foot to the turntable's two front top corners, so it
 *   pivots freely about them, with a head sheave at its far end. Nothing but the luffing rope
 *   holds it up.
 * - **A-frame**: four legs from the turntable's top corners to an apex above the mast. The
 *   luffing rope runs from the apex to the jib head - its length, paid out or hauled in by the
 *   luffing winch, sets the jib's angle. The counter-jib, carrying both winches and the ballast,
 *   hangs from the apex on a tie bar and pulls it back against the rope.
 * - **Hook**: hangs from the jib head on a tension-only hoist rope.
 *
 * The luffing drive moves the jib angle ([luff], degrees); the rope's length follows from the
 * geometry of a straight jib at that angle, trimmed over a few seconds for the rope's stretch and
 * the jib's sag so the jib really sits at the angle asked for. The hoist holds the hook at
 * [hookHeightTarget] while the jib luffs - "level luffing" - by paying out or hauling in as the
 * head rises and falls.
 *
 * Gravity ramps on over [rampSeconds] under heavy structural damping, held for [settlingSeconds]
 * more and then eased off; the drives answer from [readyAt].
 */
class LuffingCrane(
    val spec: LuffingCraneSpec = LuffingCraneSpec(),
    val dt: Double = CRANE_DT,
    jibAngle: Double = 45.0,
    hookHeight: Double = 20.0,
    loadTonnes: Double = 0.0,
    val rampSeconds: Double = 3.0,
    val settlingSeconds: Double = 4.0,
    val easeSeconds: Double = 2.0,
    /** How quickly the luffing rope's trim and the hoist's allowance for the head's height catch
     * up - well slower than the jib rocks on its rope, or they'd chase it. */
    val trimSeconds: Double = TRIM_SECONDS,
) {
    val store = ParticleStore()
    val groups = Groups()
    private val h = spec.halfWidth

    private fun node(p: Vector3, mass: Double, group: String, radius: Double? = TowerCrane.NODE_RADIUS): Int {
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
        (0 until 4).map { c -> val (x, z) = corner(c); node(Vector3(x, y, z), mass, MAST_GROUP, if (level == 0) null else TowerCrane.NODE_RADIUS) }
    }
    val mastTop: List<Int> get() = mast.last()

    /** The turntable's lower corners, on the slewing ring; the cab is beside corner 2. */
    val turntableLower: List<Int> = (0 until 4).map { c ->
        val (x, z) = corner(c)
        node(Vector3(x, spec.mastHeight, z), spec.turntableMass / 8 + if (c == 2) spec.cabMass / 2 else 0.0, SLEWING_GROUP)
    }
    /** The turntable's upper corners: the jib's foot pins are 1 and 2, the counter-jib hinges on
     * 0 and 3, and the A-frame stands on all four. */
    val turntableUpper: List<Int> = (0 until 4).map { c ->
        val (x, z) = corner(c)
        node(Vector3(x, spec.turntableTop, z), spec.turntableMass / 8 + spec.aFrameMass / 8 + if (c == 2) spec.cabMass / 2 else 0.0, SLEWING_GROUP)
    }
    val apex: Int = node(Vector3(0.0, spec.apexHeight, 0.0), spec.aFrameMass / 2, SLEWING_GROUP)

    /** The jib's foot pins (-z side, +z side), which it pivots about. */
    val jibFoot: List<Int> = listOf(turntableUpper[1], turntableUpper[2])

    /** A point of the jib straight at [degrees]: [along] it from the foot, [up] off its bottom
     * chords, [side] across. */
    private fun onJib(degrees: Double, along: Double, up: Double, side: Double): Vector3 {
        val a = degrees * PI / 180
        return Vector3(h + along * cos(a) - up * sin(a), spec.turntableTop + along * sin(a) + up * cos(a), side)
    }

    private val jibNodeMass = spec.jibMassPerMetre * spec.jibLength / (4 * spec.jibPanels)
    /** The jib's cross-sections from panel point 1 (`jib[0]`) to its end, each four nodes going
     * round it: bottom -z, bottom +z, top +z, top -z. */
    val jib: List<List<Int>> = (1..spec.jibPanels).map { k ->
        val s = k * spec.jibPanel
        listOf(0.0 to -h, 0.0 to h, spec.jibDepth to h, spec.jibDepth to -h).map { (up, side) ->
            node(onJib(jibAngle, s, up, side), jibNodeMass, SLEWING_GROUP)
        }
    }
    /** The head sheave: the luffing rope and hoist rope both run from here. */
    val jibHead: Int = node(onJib(jibAngle, spec.jibLength, spec.jibDepth / 2, 0.0), spec.jibHeadMass, SLEWING_GROUP)

    private val ballastPanels = (spec.counterJibPanels - 1)..spec.counterJibPanels
    /** Where the winches sit on the counter-jib: hoist, then luffing. */
    val hoistWinchPanel: Int = 2
    val luffingWinchPanel: Int = 3
    private fun counterJibBottomMass(k: Int) = spec.counterJibMass / (3 * spec.counterJibPanels) +
        (if (k in ballastPanels) spec.ballastMass / (2 * ballastPanels.count()) else 0.0) +
        (if (k == hoistWinchPanel) spec.hoistWinchMass / 2 else 0.0) +
        (if (k == luffingWinchPanel) spec.luffingWinchMass / 2 else 0.0)
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

    var loadTonnes: Double = loadTonnes
        set(value) { require(value >= 0.0 && value.isFinite()); field = value }
    private fun hookMass() = spec.hookBlockMass + 1000 * loadTonnes
    val hookNode: Int = node(Vector3(store.position(jibHead).x, hookHeight, 0.0), hookMass(), HOOK_GROUP, TowerCrane.HOOK_RADIUS)

    // ---- Members.

    /** What each member is part of, for colouring and drawing. */
    enum class Part { MAST_CHORD, MAST_WEB, TURNTABLE, JIB_CHORD, JIB_WEB, JIB_HEAD, COUNTER_JIB_CHORD, COUNTER_JIB_WEB, A_FRAME, TIE, LUFFING_ROPE, HOIST_ROPE }

    private val memberList = ArrayList<Truss.Member>()
    private val partList = ArrayList<Part>()
    private fun member(a: Int, b: Int, area: Double, part: Part): Int {
        val length = (store.position(b) - store.position(a)).length()
        memberList += when (part) {
            Part.LUFFING_ROPE, Part.HOIST_ROPE -> Truss.Member(
                a, b, area, WIRE_ROPE_MODULUS, tensionOnly = true, restLength = length, stiffnessDamping = TowerCrane.ROPE_DAMPING,
                breakingForce = if (part == Part.LUFFING_ROPE) spec.luffingRopeBreakingForce else spec.hoistRopeBreakingForce,
            )
            Part.TIE -> Truss.Member(a, b, area, STEEL_YOUNGS_MODULUS, tensionOnly = true, yieldStress = spec.yieldStress, ruptureStrain = spec.ruptureStrain)
            else -> Truss.Member(
                a, b, area, STEEL_YOUNGS_MODULUS,
                yieldStress = spec.yieldStress,
                bucklingStress = Truss.rankineBucklingStress(spec.yieldStress, STEEL_YOUNGS_MODULUS, length, Truss.tubeRadiusOfGyration(area)),
                ruptureStrain = spec.ruptureStrain,
            )
        }
        partList += part
        return memberList.size - 1
    }

    /** The bottom panel's four chords, in corner order. */
    val mastBaseChords: List<Int>
    val counterJibTie: Int
    val luffingRope: Int
    val hoistRope: Int

    /** One panel of a square lattice from cross-section [lo] to [hi] (four nodes each, going
     * round): chords, a diagonal on every face (alternating with [parity]), and [hi]'s edges and
     * both its diagonals - a square tube is a mechanism without them. Returns the chords. */
    private fun boxPanel(lo: List<Int>, hi: List<Int>, parity: Int, chordArea: Double, webArea: Double, chordPart: Part, webPart: Part): List<Int> {
        val chords = (0 until 4).map { c -> member(lo[c], hi[c], chordArea, chordPart) }
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            if ((parity + c) % 2 == 0) member(lo[c], hi[n], webArea, webPart) else member(lo[n], hi[c], webArea, webPart)
            member(hi[c], hi[n], webArea, webPart)
        }
        member(hi[0], hi[2], webArea, webPart)
        member(hi[1], hi[3], webArea, webPart)
        return chords
    }

    init {
        val baseChords = ArrayList<Int>()
        for (level in 0 until spec.mastPanels) {
            val chords = boxPanel(mast[level], mast[level + 1], level, spec.mastChordArea, spec.mastWebArea, Part.MAST_CHORD, Part.MAST_WEB)
            if (level == 0) baseChords += chords
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

        // A-frame: four legs to the apex.
        for (c in 0 until 4) member(turntableUpper[c], apex, spec.aFrameLegArea, Part.A_FRAME)

        // Jib: its first cross-section braced on itself and back to the two foot pins - all on
        // the line between them, so it pivots there - then box panels out to the end, and the
        // head on the last cross-section.
        val first = jib[0]
        for (c in 0 until 4) member(first[c], first[(c + 1) % 4], spec.jibWebArea, Part.JIB_WEB)
        member(first[0], first[2], spec.jibWebArea, Part.JIB_WEB)
        member(first[1], first[3], spec.jibWebArea, Part.JIB_WEB)
        val (pinMinus, pinPlus) = jibFoot
        member(pinMinus, first[0], spec.jibChordArea, Part.JIB_CHORD)
        member(pinPlus, first[1], spec.jibChordArea, Part.JIB_CHORD)
        member(pinPlus, first[2], spec.jibChordArea, Part.JIB_CHORD)
        member(pinMinus, first[3], spec.jibChordArea, Part.JIB_CHORD)
        member(pinMinus, first[1], spec.jibWebArea, Part.JIB_WEB)
        member(pinPlus, first[3], spec.jibWebArea, Part.JIB_WEB)
        for (k in 0 until jib.lastIndex) boxPanel(jib[k], jib[k + 1], k, spec.jibChordArea, spec.jibWebArea, Part.JIB_CHORD, Part.JIB_WEB)
        for (id in jib.last()) member(id, jibHead, spec.jibHeadArea, Part.JIB_HEAD)

        // Counter-jib: a triangular girder like the hammerhead's, its end tied to the apex.
        val left = counterJibLeft; val right = counterJibRight; val top = counterJibTop
        for (k in 0 until left.lastIndex) {
            member(left[k], left[k + 1], spec.counterJibChordArea, Part.COUNTER_JIB_CHORD)
            member(right[k], right[k + 1], spec.counterJibChordArea, Part.COUNTER_JIB_CHORD)
            if (k >= 1) member(top[k]!!, top[k + 1]!!, spec.counterJibChordArea, Part.COUNTER_JIB_CHORD)
            val t = top[k + 1]!!
            member(left[k], t, spec.counterJibWebArea, Part.COUNTER_JIB_WEB)
            member(right[k], t, spec.counterJibWebArea, Part.COUNTER_JIB_WEB)
            member(left[k + 1], t, spec.counterJibWebArea, Part.COUNTER_JIB_WEB)
            member(right[k + 1], t, spec.counterJibWebArea, Part.COUNTER_JIB_WEB)
            member(left[k + 1], right[k + 1], spec.counterJibWebArea, Part.COUNTER_JIB_WEB)
            member(left[k], right[k + 1], spec.counterJibWebArea, Part.COUNTER_JIB_WEB)
        }
        counterJibTie = member(apex, top.last()!!, spec.tieArea, Part.TIE)

        // The ropes' rest lengths are set every step (see updateRopes).
        luffingRope = member(apex, jibHead, spec.luffingRopeArea, Part.LUFFING_ROPE)
        hoistRope = member(jibHead, hookNode, spec.hoistRopeArea, Part.HOIST_ROPE)
    }

    val parts: List<Part> = partList
    val truss = Truss(store, memberList, stiffnessDamping = 3e-4, name = "steel")

    // ---- Drives and couplings.

    val slew = Drive(0.0, spec.slewSpeedDegrees, spec.slewAccelerationDegrees)
    /** The jib angle the luffing winch is driving to, degrees. */
    val luff = Drive(jibAngle.coerceIn(spec.minJibAngle, spec.maxJibAngle), spec.luffSpeedDegrees, spec.luffAccelerationDegrees, spec.minJibAngle, spec.maxJibAngle)
    /** The hook's radius the jib is luffing to: [luff]'s target, as a radius. */
    var radiusTarget: Double
        get() = spec.headRadius(luff.target)
        set(value) { luff.target = spec.jibAngleFor(value) }
    var hookHeightTarget: Double = hookHeight
        set(value) { field = value.coerceIn(spec.minHookHeight, spec.headHeight(spec.maxJibAngle) - MIN_ROPE) }
    /** The hoist rope's length from head to hook with the hook hanging still. */
    private val hoist = Drive(truss.restLength(hoistRope), spec.hoistSpeed, spec.hoistAcceleration, MIN_ROPE, spec.headHeight(spec.maxJibAngle) - spec.minHookHeight)
    private val hoistRopeStiffness = WIRE_ROPE_MODULUS * spec.hoistRopeArea

    /** How much shorter than a straight jib's geometry the luffing rope is hauled in: its stretch,
     * the jib's bending and the mast's lean, learnt from the angle the jib actually sits at. (Not
     * worked out from the rope's tension: allowing for its stretch that way would cancel its
     * stiffness.) */
    var luffTrim: Double = 0.0
        private set
    /** How far the head actually sits from a straight jib's geometry (the mast's lean), smoothed:
     * what the hoist allows for to hold the hook level. */
    private var headOffset = 0.0

    val slewingRing: List<WeightedAttachment> = turntableLower.map { id ->
        val m = store.mass(id)
        WeightedAttachment(id, TowerCrane.RING_STIFFNESS, 2 * 0.5 * sqrt(TowerCrane.RING_STIFFNESS * m))
    }

    /** Slew rate, rad/s. */
    val slewRate: Double get() = slew.velocity * PI / 180

    // The jib is in the slewing group, so luffing is damped a little too: about 1 kN of drag on
    // a 10 t jib moving at 1 m/s against luffing-rope tensions of hundreds of kN.
    val structuralDamping = SlewFrameDamping(SLEWING_GROUP, MAST_GROUP, groups, { slewRate }, TowerCrane.SETTLING_DAMPING)
    /** Damping on the hook while the crane settles - the jib's sag and trim move the head, which
     * would leave the hook swinging - gone once it's ready, so the load swings freely. Unnamed:
     * there's nothing to edit in it. */
    val hookSettling = SlewFrameDamping(HOOK_GROUP, FOUNDATION_GROUP, groups, { 0.0 }, TowerCrane.SETTLING_DAMPING, name = null)
    val gravity = RampedGravity(listOf(MAST_GROUP, SLEWING_GROUP, HOOK_GROUP), groups, rampSeconds)
    val forces: List<Force> = listOf(truss, gravity, structuralDamping, hookSettling) + slewingRing

    /** Whether the steel can fail (see [Truss]): off, it stays elastic however it's loaded. */
    var failure: Boolean
        get() = truss.failure
        set(value) { truss.failure = value }

    val collisions = CollisionSystem(
        listOf(MAST_GROUP, SLEWING_GROUP, HOOK_GROUP).map { group ->
            ParticleColliderRule(group, PlaneCollider(VectorExpr.of(Vector3.ZERO), Vector3(0.0, 1.0, 0.0), name = "ground"), restitution = 0.0, staticFriction = 0.6, kineticFriction = 0.5)
        },
    )
    val constraints: List<Constraint> = run {
        mast[0].forEach { groups.add(FOUNDATION_GROUP, it) }
        listOf(FixedPosition.atCurrentPositions(FOUNDATION_GROUP, store, groups, name = "foundation"))
    }

    private val integrator = Integrator()

    init { updateCouplings(0.0) }

    fun ringWeights(c: Int, degrees: Double): List<Pair<Int, Double>> = slewedCornerWeights(mastTop, h, c, degrees)

    /** The angle the jib actually sits at, degrees above horizontal: from its foot pins to its
     * head, less the head's offset above the jib's bottom chords. */
    val jibAngle: Double get() {
        val foot = (store.position(jibFoot[0]) + store.position(jibFoot[1])) * 0.5
        val d = store.position(jibHead) - foot
        return (atan2(d.y, hypot(d.x, d.z)) - atan2(spec.jibDepth / 2, spec.jibLength)) * 180 / PI
    }

    /** The hook's actual radius: the head sheave's distance from the mast's axis. */
    val radius: Double get() = store.position(jibHead).let { hypot(it.x, it.z) }
    val slewDegrees: Double get() = slew.position

    private fun updateCouplings(t: Double) {
        for ((c, ring) in slewingRing.withIndex()) ring.setTarget(ringWeights(c, slew.position))
        truss.setRestLength(luffingRope, maxOf(MIN_ROPE, spec.luffingRopeLength(luff.position) - luffTrim))
        // The unstretched rope that hangs the hook's weight - at gravity's current ramp - at the
        // hoist's length.
        truss.setRestLength(hoistRope, hoist.position / (1 + store.mass(hookNode) * STANDARD_GRAVITY * gravity.factor(t) / hoistRopeStiffness))
    }

    /** One step of [dt] from time [t]: the drives move toward their targets, then the physics. */
    fun step(t: Double) {
        if (t >= readyAt) {
            slew.advance(dt)
            luff.advance(dt)
        }
        // Trim the luffing rope toward the length that puts the jib where it should be.
        // Quickly while the heavy settling damping holds the jib still, then slowly.
        val trimTime = if (t < readyAt) SETTLING_TRIM_SECONDS else trimSeconds
        val error = spec.luffingRopeLength(jibAngle) - spec.luffingRopeLength(luff.position)
        luffTrim = (luffTrim + error * dt / trimTime).coerceIn(-MAX_TRIM, MAX_TRIM)
        headOffset += (store.position(jibHead).y - spec.headHeight(luff.position) - headOffset) * (dt / trimTime)
        if (t >= readyAt) {
            hoist.target = spec.headHeight(luff.position) + headOffset - hookHeightTarget
            hoist.advance(dt)
        }
        val mass = store.mass(hookNode)
        val wanted = hookMass()
        if (mass != wanted) {
            val stepMass = TowerCrane.LOAD_RAMP_RATE * dt
            store.setMass(hookNode, ScalarExpr.of(if (abs(wanted - mass) <= stepMass) wanted else mass + sign(wanted - mass) * stepMass), t)
        }
        updateCouplings(t)
        structuralDamping.coefficient = dampingAt(t)
        hookSettling.coefficient = TowerCrane.SETTLING_DAMPING * (dampingAt(t) - TowerCrane.OPERATING_DAMPING) / (TowerCrane.SETTLING_DAMPING - TowerCrane.OPERATING_DAMPING)
        integrator.step(store, groups, forces, constraints, t, dt)
        collisions.resolve(store, groups, t, dt)
    }

    val readyAt: Double get() = rampSeconds + settlingSeconds

    fun dampingAt(t: Double): Double {
        val easeStart = readyAt
        if (t <= easeStart) return TowerCrane.SETTLING_DAMPING
        if (t >= easeStart + easeSeconds) return TowerCrane.OPERATING_DAMPING
        val f = (t - easeStart) / easeSeconds
        return TowerCrane.SETTLING_DAMPING + (TowerCrane.OPERATING_DAMPING - TowerCrane.SETTLING_DAMPING) * f
    }

    companion object {
        const val MAST_GROUP = "mast"
        const val SLEWING_GROUP = "slewing parts"
        const val HOOK_GROUP = "hook"
        const val FOUNDATION_GROUP = "foundation"
        const val MIN_ROPE = 1.0
        /** s: how quickly the luffing rope's trim and the hoist's allowance for the head's
         * height catch up - well slower than the jib rocks on its rope (5-8 s), or they'd chase
         * it into rocking harder. */
        const val TRIM_SECONDS = 20.0
        /** s: the same while the crane settles, its rocking then heavily damped. */
        const val SETTLING_TRIM_SECONDS = 1.5
        const val MAX_TRIM = 3.0
    }
}
