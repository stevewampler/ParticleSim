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
import particlesim.physics.Force
import particlesim.physics.GroundContact
import particlesim.physics.Integrator
import particlesim.physics.Truss
import particlesim.physics.TrussWind
import particlesim.physics.WeightedAttachment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A crawler crane's dimensions, masses and member sizes - by default a crane of roughly the 100 t
 * class on a 50 m lattice boom, working between 30 and 82 degrees. Not from a data sheet: the
 * masses are chosen so the crane weighs about what one of that class does and stands stably
 * empty with its boom right up, slewed any way round; the member areas so the steel holds
 * whatever it can lift before it tips.
 *
 * Coordinates: y up, the slewing axis at x = z = 0, the crawler tracks running along x, the boom
 * along +x at slew angle zero. Boom angles are degrees above horizontal.
 */
data class CrawlerCraneSpec(
    // Crawlers: two box-lattice tracks whose bottom nodes are the pads on the ground.
    val trackLength: Double = 9.0,
    /** Between the tracks' centre lines. */
    val trackGauge: Double = 6.0,
    val trackWidth: Double = 0.9,
    val trackHeight: Double = 1.1,
    val trackPanels: Int = 4,
    /** The slewing ring: its height and the side of its square. */
    val ringHeight: Double = 1.8,
    val ringWidth: Double = 3.0,
    /** The upperworks: this deep above the ring, its tail (the counterweight) this far behind
     * the slewing axis. */
    val deckDepth: Double = 1.5,
    val tailRadius: Double = 5.5,
    /** The gantry's apex: above the deck, and behind the slewing axis. */
    val gantryHeight: Double = 8.0,
    val gantryX: Double = -2.0,
    /** From the boom's foot pins to its tip sheave. */
    val boomLength: Double = 50.0,
    val boomPanels: Int = 20,
    val boomWidth: Double = 2.0,
    val boomDepth: Double = 2.0,
    val boomTipLength: Double = 1.0,
    val minBoomAngle: Double = 30.0,
    val maxBoomAngle: Double = 82.0,
    /** The most the hook and its tackle take, whatever the stability allows. */
    val maxLoadTonnes: Double = 60.0,
    /** The rated load as a share of the tipping load: a chosen margin, not a standard's. */
    val ratingFraction: Double = 0.75,
    // Masses, kg.
    val carbodyMass: Double = 20000.0,
    /** Each track. */
    val trackMass: Double = 15000.0,
    /** The upperworks with its engine, winches and cab. */
    val upperworksMass: Double = 20000.0,
    val counterweightMass: Double = 50000.0,
    val gantryMass: Double = 3000.0,
    val boomMassPerMetre: Double = 200.0,
    val boomTipMass: Double = 1000.0,
    /** The floating bail the pendants hang from. */
    val bailMass: Double = 500.0,
    val hookBlockMass: Double = 1500.0,
    // Member cross-section areas, m^2.
    val trackChordArea: Double = 140e-4,
    val trackWebArea: Double = 60e-4,
    val carbodyArea: Double = 150e-4,
    val upperworksArea: Double = 150e-4,
    val gantryArea: Double = 100e-4,
    val boomChordArea: Double = 55e-4,
    val boomWebArea: Double = 12e-4,
    val boomTipArea: Double = 60e-4,
    /** Two steel pendant bars. */
    val pendantArea: Double = 2 * 30e-4,
    /** Eight falls of 20 mm rope's steel between the gantry and the bail. */
    val boomHoistRopeArea: Double = 8 * 1.9e-4,
    val boomHoistRopeFalls: Int = 8,
    /** Eight falls of 20 mm rope's steel. */
    val hoistRopeArea: Double = 8 * 1.9e-4,
    val hoistRopeFalls: Int = 8,
    // Failure (when it's on): S355 steel, members buckling by their slenderness as round tubes
    // (see Truss.tubeRadiusOfGyration); rope at about 250 kN minimum breaking load a fall.
    val yieldStress: Double = 355e6,
    val ruptureStrain: Double = 0.15,
    val boomHoistRopeBreakingForce: Double = 8 * 250e3,
    val hoistRopeBreakingForce: Double = 8 * 250e3,
    // The ground: firm, on crane mats - each pad settles a couple of millimetres.
    val padStiffness: Double = 3e7,
    val padDampingRatio: Double = 0.5,
    val groundFriction: Double = 0.5,
    // Drives: slewing at 1 rpm, luffing at 1.5 degrees a second, hoisting at 90 m/min.
    val slewSpeedDegrees: Double = 6.0,
    val slewAccelerationDegrees: Double = 0.8,
    val luffSpeedDegrees: Double = 1.5,
    val luffAccelerationDegrees: Double = 0.5,
    val hoistSpeed: Double = 1.5,
    val hoistAcceleration: Double = 0.6,
    val minHookHeight: Double = 2.0,
) {
    val ringHalf: Double get() = ringWidth / 2
    val deckTop: Double get() = ringHeight + deckDepth
    val apexHeight: Double get() = deckTop + gantryHeight
    val boomPanel: Double get() = (boomLength - boomTipLength) / boomPanels
    /** The tracks' outer edges: the pads furthest out, which the crane tips over sideways. */
    val padLineZ: Double get() = trackGauge / 2 + trackWidth / 2

    /** The tip sheave's distance from the slewing axis, the boom straight at [degrees]. */
    fun tipRadius(degrees: Double): Double {
        val a = degrees * PI / 180
        return ringHalf + boomLength * cos(a) - boomDepth / 2 * sin(a)
    }

    /** The tip sheave's height, the boom straight at [degrees]. */
    fun tipHeight(degrees: Double): Double {
        val a = degrees * PI / 180
        return ringHeight + boomLength * sin(a) + boomDepth / 2 * cos(a)
    }

    /** From the gantry's apex to the tip sheave, the boom straight at [degrees]: the pendants
     * and the boom hoist rope together. */
    fun boomLineLength(degrees: Double): Double = hypot(tipRadius(degrees) - gantryX, tipHeight(degrees) - apexHeight)

    /** The pendants' length: the line's at the boom's top angle less a few metres of rope. */
    val pendantLength: Double get() = boomLineLength(maxBoomAngle) - 5.0

    val minRadius: Double get() = tipRadius(maxBoomAngle)
    val maxRadius: Double get() = tipRadius(minBoomAngle)

    /** The boom angle that puts the tip (and so the hook) at [radius], clamped to its range. */
    fun boomAngleFor(radius: Double): Double {
        if (radius >= maxRadius) return minBoomAngle
        if (radius <= minRadius) return maxBoomAngle
        var lo = minBoomAngle
        var hi = maxBoomAngle
        repeat(60) {
            val mid = (lo + hi) / 2
            if (tipRadius(mid) > radius) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}

/**
 * A crawler crane ([spec]) as a pin-jointed steel [Truss] at real stiffness and real masses,
 * moving at real speed like the other cranes - but standing on the ground rather than fixed to
 * it, so it can tip over. What it can lift is set by its stability more than its steel.
 *
 * - **Crawlers and carbody**: two box-lattice tracks braced to a carbody box under the slewing
 *   ring. The tracks' bottom nodes are the pads, standing on the ground through a
 *   [GroundContact] - pushed up, never held down, held sideways by friction - so each pad reports
 *   what it carries ([padReactions]) and lifts off when the crane leans over it.
 * - **Upperworks**: a deck box on the slewing ring (held like the tower crane's turntable, each
 *   lower corner at its slewed place on the carbody's ring square), its tail carrying the
 *   counterweight behind the slewing axis, a gantry (A-frame) on it.
 * - **Boom**: a box lattice pinned at its foot to the deck's two front lower corners. Two steel
 *   pendants run from its tip to a floating bail; the boom hoist rope runs from the bail to the
 *   gantry's apex, and its length - paid out or hauled in - sets the boom's angle, trimmed for
 *   stretch and sag like [LuffingCrane]'s luffing rope.
 * - **Hook**: hangs from the boom tip on a tension-only hoist rope, held level while the boom
 *   luffs.
 *
 * It tips when the centre of mass of everything, load included, passes over the line of the
 * outermost pads: over the end of the tracks or over their outer edges. Over the side that line
 * is nearer, so a load it can hold over the end can tip it over the side ([tippingLoadTonnes]).
 *
 * Gravity ramps on over [rampSeconds] under heavy structural damping, held for [settlingSeconds]
 * more and then eased off; the drives answer from [readyAt].
 */
class CrawlerCrane(
    val spec: CrawlerCraneSpec = CrawlerCraneSpec(),
    val dt: Double = CRANE_DT,
    slewDegrees: Double = 0.0,
    boomAngle: Double = 60.0,
    hookHeight: Double = 10.0,
    loadTonnes: Double = 0.0,
    val rampSeconds: Double = 3.0,
    val settlingSeconds: Double = 4.0,
    val easeSeconds: Double = 2.0,
    /** How quickly the boom hoist rope's trim and the hoist's allowance for the tip's height
     * catch up - well slower than the boom rocks on its rope. */
    val trimSeconds: Double = LuffingCrane.TRIM_SECONDS,
) {
    val store = ParticleStore()
    val groups = Groups()
    private val r = spec.ringHalf

    private fun node(p: Vector3, mass: Double, group: String, radius: Double? = TowerCrane.NODE_RADIUS): Int {
        val id = store.create(position = p, mass = ScalarExpr.of(mass), radius = radius?.let { ScalarExpr.of(it) })
        groups.add(group, id)
        return id
    }

    // ---- Crawlers and carbody.

    private val trackNodeMass = spec.trackMass / (4 * (spec.trackPanels + 1))
    /** `tracks[side][j]`, side 0 at -z and 1 at +z, section j from the -x end, each four nodes
     * going round it: bottom inner, bottom outer, top outer, top inner. The bottom two are pads. */
    val tracks: List<List<List<Int>>> = listOf(-1.0, 1.0).map { s ->
        (0..spec.trackPanels).map { j ->
            val x = -spec.trackLength / 2 + j * spec.trackLength / spec.trackPanels
            val inner = s * (spec.trackGauge - spec.trackWidth) / 2
            val outer = s * (spec.trackGauge + spec.trackWidth) / 2
            listOf(0.0 to inner, 0.0 to outer, spec.trackHeight to outer, spec.trackHeight to inner).map { (y, z) ->
                val pad = y == 0.0
                node(Vector3(x, y, z), trackNodeMass, CARBODY_GROUP, if (pad) null else TowerCrane.NODE_RADIUS).also { if (pad) groups.add(PADS_GROUP, it) }
            }
        }
    }
    val pads: List<Int> = groups.membersOf(PADS_GROUP).toList()

    /** The carbody's ring square (its top, under the slewing ring) and its bottom, in
     * [squareCorner] order. */
    val ringSquare: List<Int> = (0 until 4).map { c -> val (x, z) = squareCorner(c, r); node(Vector3(x, spec.ringHeight, z), spec.carbodyMass / 8, CARBODY_GROUP) }
    val carbodyBottom: List<Int> = (0 until 4).map { c -> val (x, z) = squareCorner(c, r); node(Vector3(x, CARBODY_BOTTOM, z), spec.carbodyMass / 8, CARBODY_GROUP) }

    // ---- Upperworks.

    private val upperNodeMass = spec.upperworksMass / 12
    /** The deck's lower corners, on the slewing ring: the boom's foot pins are 1 and 2. */
    val deckLower: List<Int> = (0 until 4).map { c -> val (x, z) = squareCorner(c, r); node(Vector3(x, spec.ringHeight, z), upperNodeMass, UPPER_GROUP) }
    val deckUpper: List<Int> = (0 until 4).map { c ->
        val (x, z) = squareCorner(c, r)
        node(Vector3(x, spec.deckTop, z), upperNodeMass + if (c == 1 || c == 2) spec.gantryMass / 8 else 0.0, UPPER_GROUP)
    }
    /** The tail, carrying the counterweight: lower -z, lower +z, upper +z, upper -z. */
    val tail: List<Int> = listOf(spec.ringHeight to -r, spec.ringHeight to r, spec.deckTop to r, spec.deckTop to -r).map { (y, z) ->
        node(Vector3(-spec.tailRadius, y, z), upperNodeMass + spec.counterweightMass / 4 + if (y == spec.deckTop) spec.gantryMass / 8 else 0.0, UPPER_GROUP)
    }
    val apex: Int = node(Vector3(spec.gantryX, spec.apexHeight, 0.0), spec.gantryMass / 2, UPPER_GROUP)
    val boomFoot: List<Int> = listOf(deckLower[1], deckLower[2])

    private fun onBoom(degrees: Double, along: Double, up: Double, side: Double): Vector3 {
        val a = degrees * PI / 180
        return Vector3(r + along * cos(a) - up * sin(a), spec.ringHeight + along * sin(a) + up * cos(a), side)
    }

    private val boomNodeMass = spec.boomMassPerMetre * spec.boomLength / (4 * spec.boomPanels)
    private val bw = spec.boomWidth / 2
    /** The boom's cross-sections from panel point 1, each four nodes going round it: bottom -z,
     * bottom +z, top +z, top -z. */
    val boom: List<List<Int>> = (1..spec.boomPanels).map { k ->
        listOf(0.0 to -bw, 0.0 to bw, spec.boomDepth to bw, spec.boomDepth to -bw).map { (up, side) ->
            node(onBoom(boomAngle, k * spec.boomPanel, up, side), boomNodeMass, UPPER_GROUP)
        }
    }
    val boomTip: Int = node(onBoom(boomAngle, spec.boomLength, spec.boomDepth / 2, 0.0), spec.boomTipMass, UPPER_GROUP)
    /** The floating bail: the pendants from the boom tip and the boom hoist rope from the gantry
     * meet here. */
    val bail: Int = run {
        val tip = store.position(boomTip)
        val toApex = (store.position(apex) - tip).normalized()
        node(tip + toApex * spec.pendantLength, spec.bailMass, UPPER_GROUP)
    }

    var loadTonnes: Double = loadTonnes
        set(value) { require(value >= 0.0 && value.isFinite()); field = value }
    private fun hookMass() = spec.hookBlockMass + 1000 * loadTonnes
    val hookNode: Int = node(Vector3(store.position(boomTip).x, hookHeight, 0.0), hookMass(), HOOK_GROUP, TowerCrane.HOOK_RADIUS)

    init {
        // Built at slew zero; turned to where it starts (positive turns +x toward -z, as the
        // slewing ring does).
        val theta = slewDegrees * PI / 180
        if (theta != 0.0) {
            for (id in groups.membersOf(UPPER_GROUP) + groups.membersOf(HOOK_GROUP)) {
                val p = store.position(id)
                store.setPosition(id, Vector3(p.x * cos(theta) + p.z * sin(theta), p.y, -p.x * sin(theta) + p.z * cos(theta)))
            }
        }
    }

    // ---- Members.

    /** What each member is part of, for colouring and drawing. */
    enum class Part { TRACK, CARBODY, UPPERWORKS, GANTRY, BOOM_CHORD, BOOM_WEB, BOOM_TIP, PENDANT, BOOM_HOIST_ROPE, HOIST_ROPE }

    private val memberList = ArrayList<Truss.Member>()
    private val partList = ArrayList<Part>()
    private fun member(a: Int, b: Int, area: Double, part: Part): Int {
        val length = (store.position(b) - store.position(a)).length()
        memberList += when (part) {
            Part.BOOM_HOIST_ROPE, Part.HOIST_ROPE -> Truss.Member(
                a, b, area, WIRE_ROPE_MODULUS, tensionOnly = true, restLength = length, stiffnessDamping = TowerCrane.ROPE_DAMPING,
                breakingForce = if (part == Part.BOOM_HOIST_ROPE) spec.boomHoistRopeBreakingForce else spec.hoistRopeBreakingForce,
            )
            Part.PENDANT -> Truss.Member(a, b, area, STEEL_YOUNGS_MODULUS, tensionOnly = true, yieldStress = spec.yieldStress, ruptureStrain = spec.ruptureStrain)
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

    /** One panel of a box lattice from cross-section [lo] to [hi] (four nodes each, going
     * round): chords, a diagonal on every face (alternating with [parity]), and [hi]'s edges and
     * both its diagonals. */
    private fun boxPanel(lo: List<Int>, hi: List<Int>, parity: Int, chordArea: Double, webArea: Double, chordPart: Part, webPart: Part) {
        for (c in 0 until 4) member(lo[c], hi[c], chordArea, chordPart)
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            if ((parity + c) % 2 == 0) member(lo[c], hi[n], webArea, webPart) else member(lo[n], hi[c], webArea, webPart)
            member(hi[c], hi[n], webArea, webPart)
        }
        member(hi[0], hi[2], webArea, webPart)
        member(hi[1], hi[3], webArea, webPart)
    }

    /** A cross-section braced on itself: its edges and both diagonals. */
    private fun bracedSection(s: List<Int>, area: Double, part: Part) {
        for (c in 0 until 4) member(s[c], s[(c + 1) % 4], area, part)
        member(s[0], s[2], area, part)
        member(s[1], s[3], area, part)
    }

    /** A box between two squares [lo] and [hi] in [squareCorner] order: edges, posts, a
     * diagonal on every side face and both diagonals top and bottom. */
    private fun box(lo: List<Int>, hi: List<Int>, area: Double, part: Part) {
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            member(lo[c], hi[c], area, part)
            member(lo[c], lo[n], area, part)
            member(hi[c], hi[n], area, part)
            member(lo[c], hi[n], area, part)
        }
        for ((a, b) in listOf(0 to 2, 1 to 3)) {
            member(lo[a], lo[b], area, part)
            member(hi[a], hi[b], area, part)
        }
    }

    val pendant: Int
    val boomHoistRope: Int
    val hoistRope: Int

    init {
        // Tracks, each a box lattice along x.
        for (side in tracks) {
            bracedSection(side[0], spec.trackWebArea, Part.TRACK)
            for (j in 0 until side.lastIndex) boxPanel(side[j], side[j + 1], j, spec.trackChordArea, spec.trackWebArea, Part.TRACK, Part.TRACK)
        }
        // Carbody: a box, each corner braced out to the inner side of the track beside it, at
        // the track's middle and a panel toward the corner's end.
        box(carbodyBottom, ringSquare, spec.carbodyArea, Part.CARBODY)
        val mid = spec.trackPanels / 2
        for (c in 0 until 4) {
            val (x, z) = squareCorner(c, r)
            val side = tracks[if (z < 0) 0 else 1]
            val end = mid + sign(x).toInt()
            member(ringSquare[c], side[mid][3], spec.carbodyArea, Part.CARBODY)
            member(ringSquare[c], side[end][3], spec.carbodyArea, Part.CARBODY)
            member(carbodyBottom[c], side[mid][0], spec.carbodyArea, Part.CARBODY)
            member(carbodyBottom[c], side[end][0], spec.carbodyArea, Part.CARBODY)
            member(carbodyBottom[c], side[end][3], spec.carbodyArea, Part.CARBODY)
        }

        // Upperworks: the deck box, then its tail out to the counterweight.
        box(deckLower, deckUpper, spec.upperworksArea, Part.UPPERWORKS)
        boxPanel(listOf(deckLower[0], deckLower[3], deckUpper[3], deckUpper[0]), tail, 0, spec.upperworksArea, spec.upperworksArea, Part.UPPERWORKS, Part.UPPERWORKS)
        // Gantry: legs from the apex to the deck's front top corners and the tail's top.
        for (id in listOf(deckUpper[1], deckUpper[2], tail[2], tail[3])) member(apex, id, spec.gantryArea, Part.GANTRY)

        // Boom: its first cross-section braced on itself and back to the two foot pins - on the
        // line between them, so it pivots there - then box panels out to the tip.
        val first = boom[0]
        bracedSection(first, spec.boomWebArea, Part.BOOM_WEB)
        val (pinMinus, pinPlus) = boomFoot
        member(pinMinus, first[0], spec.boomChordArea, Part.BOOM_CHORD)
        member(pinPlus, first[1], spec.boomChordArea, Part.BOOM_CHORD)
        member(pinPlus, first[2], spec.boomChordArea, Part.BOOM_CHORD)
        member(pinMinus, first[3], spec.boomChordArea, Part.BOOM_CHORD)
        member(pinMinus, first[1], spec.boomWebArea, Part.BOOM_WEB)
        member(pinPlus, first[3], spec.boomWebArea, Part.BOOM_WEB)
        for (k in 0 until boom.lastIndex) boxPanel(boom[k], boom[k + 1], k, spec.boomChordArea, spec.boomWebArea, Part.BOOM_CHORD, Part.BOOM_WEB)
        for (id in boom.last()) member(id, boomTip, spec.boomTipArea, Part.BOOM_TIP)

        pendant = member(boomTip, bail, spec.pendantArea, Part.PENDANT)
        // The ropes' rest lengths are set every step (see updateCouplings).
        boomHoistRope = member(apex, bail, spec.boomHoistRopeArea, Part.BOOM_HOIST_ROPE)
        hoistRope = member(boomTip, hookNode, spec.hoistRopeArea, Part.HOIST_ROPE)
    }

    val parts: List<Part> = partList
    val truss = Truss(store, memberList, stiffnessDamping = 3e-4, name = "steel")

    // ---- Drives and couplings.

    val slew = Drive(slewDegrees, spec.slewSpeedDegrees, spec.slewAccelerationDegrees)
    /** The boom angle the boom hoist is driving to, degrees. */
    val luff = Drive(boomAngle.coerceIn(spec.minBoomAngle, spec.maxBoomAngle), spec.luffSpeedDegrees, spec.luffAccelerationDegrees, spec.minBoomAngle, spec.maxBoomAngle)
    /** The hook's radius the boom is luffing to: [luff]'s target, as a radius. */
    var radiusTarget: Double
        get() = spec.tipRadius(luff.target)
        set(value) { luff.target = spec.boomAngleFor(value) }
    var hookHeightTarget: Double = hookHeight
        set(value) { field = value.coerceIn(spec.minHookHeight, spec.tipHeight(spec.maxBoomAngle) - MIN_ROPE) }
    private val hoist = Drive(truss.restLength(hoistRope), spec.hoistSpeed, spec.hoistAcceleration, MIN_ROPE, spec.tipHeight(spec.maxBoomAngle) - spec.minHookHeight)
    private val hoistRopeStiffness = WIRE_ROPE_MODULUS * spec.hoistRopeArea

    /** How much shorter than a straight boom's geometry the boom hoist rope is hauled in, learnt
     * from the angle the boom actually sits at (see [LuffingCrane.luffTrim]). */
    var luffTrim: Double = 0.0
        private set
    private var tipOffset = 0.0

    val slewingRing: List<WeightedAttachment> = deckLower.map { id ->
        val m = store.mass(id)
        WeightedAttachment(id, TowerCrane.RING_STIFFNESS, 2 * 0.5 * sqrt(TowerCrane.RING_STIFFNESS * m))
    }

    /** Slew rate, rad/s. */
    val slewRate: Double get() = slew.velocity * PI / 180

    /** The ground under the pads. */
    val ground = GroundContact(PADS_GROUP, groups, store, spec.padStiffness, spec.padDampingRatio, spec.groundFriction)
    val structuralDamping = SlewFrameDamping(UPPER_GROUP, CARBODY_GROUP, groups, { slewRate }, TowerCrane.SETTLING_DAMPING)
    /** Damping on the hook while the crane settles, gone once it's ready. Unnamed: there's
     * nothing to edit in it. */
    val hookSettling = SlewFrameDamping(HOOK_GROUP, PADS_GROUP, groups, { 0.0 }, TowerCrane.SETTLING_DAMPING, name = null)
    val gravity = RampedGravity(listOf(CARBODY_GROUP, UPPER_GROUP, HOOK_GROUP), groups, rampSeconds)

    /** The crate a load comes in - the scene draws it, the wind pushes on it. */
    val loadCrate = LoadCrate(3.0, 1.5, 2.0)
    /** The wind it works in: still until set. */
    val wind = CraneWind()
    /** The wind on every member, the counterweight, the cab, and the hook and its load. */
    val windForce: TrussWind = run {
        tail.forEach { groups.add(COUNTERWEIGHT_GROUP, it) }
        groups.add(TowerCrane.CAB_GROUP, deckLower[2])
        TrussWind(
            truss, store, groups,
            DoubleArray(truss.size) { i ->
                when (parts[i]) {
                    Part.PENDANT -> 2 * CraneWind.tubeDragWidth(spec.pendantArea / 2)
                    Part.BOOM_HOIST_ROPE -> CraneWind.ropeDragWidth(spec.boomHoistRopeArea, spec.boomHoistRopeFalls)
                    Part.HOIST_ROPE -> CraneWind.ropeDragWidth(spec.hoistRopeArea, spec.hoistRopeFalls)
                    else -> CraneWind.tubeDragWidth(memberList[i].area)
                }
            },
            listOf(
                TrussWind.Body(COUNTERWEIGHT_GROUP) { COUNTERWEIGHT_DRAG_AREA },
                TrussWind.Body(TowerCrane.CAB_GROUP) { CAB_DRAG_AREA },
                TrussWind.Body(HOOK_GROUP) { GantryCrane.HOOK_BLOCK_DRAG_AREA + loadCrate.dragArea(loadTonnes) },
            ),
            VectorExpr.of { t -> wind.velocity(t) },
            profileExponent = CraneWind.PROFILE_EXPONENT,
        )
    }
    val forces: List<Force> = listOf(truss, gravity, ground, structuralDamping, hookSettling, windForce) + slewingRing

    /** Whether the steel can fail (see [Truss]): off, it stays elastic however it's loaded. */
    var failure: Boolean
        get() = truss.failure
        set(value) { truss.failure = value }

    /** The ground, for everything but the pads (which stand on [ground]) once it's tipped. */
    val collisions = CollisionSystem(
        listOf(CARBODY_GROUP, UPPER_GROUP, HOOK_GROUP).map { group ->
            ParticleColliderRule(group, PlaneCollider(VectorExpr.of(Vector3.ZERO), Vector3(0.0, 1.0, 0.0), name = "ground"), restitution = 0.0, staticFriction = 0.6, kineticFriction = 0.5)
        },
    )
    /** None: it just stands on the ground. */
    val constraints: List<Constraint> = emptyList()

    private val integrator = Integrator()

    init { updateCouplings(0.0) }

    fun ringWeights(c: Int, degrees: Double): List<Pair<Int, Double>> = slewedCornerWeights(ringSquare, r, c, degrees)

    /** The angle the boom actually sits at, degrees above horizontal. */
    val boomAngle: Double get() {
        val foot = (store.position(boomFoot[0]) + store.position(boomFoot[1])) * 0.5
        val d = store.position(boomTip) - foot
        return (atan2(d.y, hypot(d.x, d.z)) - atan2(spec.boomDepth / 2, spec.boomLength)) * 180 / PI
    }

    /** Where the slewing axis is: the middle of the carbody's ring square. */
    private fun axis(): Vector3 = ringSquare.fold(Vector3.ZERO) { s, id -> s + store.position(id) } * 0.25

    /** The hook's actual radius: the tip sheave's horizontal distance from the slewing axis. */
    val radius: Double get() {
        val d = store.position(boomTip) - axis()
        return hypot(d.x, d.z)
    }
    val slewDegrees: Double get() = slew.position

    /** What the ground holds each pad up with, N, in [pads] order. */
    val padReactions: List<Double> get() = pads.map { ground.normalForce(store, it) }

    /** Where the ground's push acts, (x, z): the reactions' centre. */
    val centreOfPressure: Pair<Double, Double> get() {
        var n = 0.0; var x = 0.0; var z = 0.0
        for (id in pads) {
            val f = ground.normalForce(store, id)
            val p = store.position(id)
            n += f; x += f * p.x; z += f * p.z
        }
        return (x / n) to (z / n)
    }

    private val weighed: List<Int> = listOf(CARBODY_GROUP, UPPER_GROUP, HOOK_GROUP).flatMap { groups.membersOf(it) }

    /** Everything's mass, hook and load included, kg. */
    val totalMass: Double get() = weighed.sumOf { store.mass(it) }

    /** Where everything's centre of mass is, hook and load included, (x, z). */
    val centreOfMass: Pair<Double, Double> get() {
        var m = 0.0; var x = 0.0; var z = 0.0
        for (id in weighed) {
            val mi = store.mass(id)
            val p = store.position(id)
            m += mi; x += mi * p.x; z += mi * p.z
        }
        return (x / m) to (z / m)
    }

    /**
     * The load, tonnes, that would tip the crane as it stands, in the wind blowing at [t]: for
     * each edge of the pads' outline the hook is beyond, the load whose moment about it balances
     * everything else's - its weight and the wind on it - the least of them. Infinite if the hook
     * is inside the outline.
     */
    fun tippingLoadTonnes(t: Double): Double {
        val ps = pads.map { store.position(it) }
        val edges = listOf(
            Vector3(1.0, 0.0, 0.0) to ps.maxOf { it.x }, Vector3(-1.0, 0.0, 0.0) to -ps.minOf { it.x },
            Vector3(0.0, 0.0, 1.0) to ps.maxOf { it.z }, Vector3(0.0, 0.0, -1.0) to -ps.minOf { it.z },
        )
        val (cx, cz) = centreOfMass
        val hook = store.position(hookNode)
        val m = totalMass
        var least = Double.POSITIVE_INFINITY
        for ((n, offset) in edges) {
            val beyond = n.x * hook.x + n.z * hook.z - offset
            val inside = offset - (n.x * cx + n.z * cz)
            if (beyond > 0.0) least = minOf(least, (m * inside + windMoment(n, offset, t) / STANDARD_GRAVITY) / beyond)
        }
        return loadTonnes + least / 1000
    }

    /** The wind's moment about the edge of the pads' outline whose outward normal is [n], [offset]
     * from the slewing axis, at ground level: positive holds the crane down, negative tips it over
     * that edge. */
    fun windMoment(n: Vector3, offset: Double, t: Double): Double {
        val edge = n * offset
        val axis = n.cross(Vector3(0.0, 1.0, 0.0))
        var moment = 0.0
        windForce.forEachForce(store, groups, t) { at, force -> moment += (at - edge).cross(force).dot(axis) }
        return moment
    }

    /** What it's rated to lift as it stands, in the wind blowing at [t]:
     * [CrawlerCraneSpec.ratingFraction] of the tipping load, and no more than
     * [CrawlerCraneSpec.maxLoadTonnes]. */
    fun ratedLoadTonnes(t: Double): Double = minOf(spec.maxLoadTonnes, spec.ratingFraction * tippingLoadTonnes(t))

    /** How far the carbody has tilted from level, degrees. */
    val tilt: Double get() {
        val p = ringSquare.map { store.position(it) }
        val normal = (p[2] - p[0]).cross(p[1] - p[3]).normalized()
        return acos(abs(normal.y).coerceAtMost(1.0)) * 180 / PI
    }

    private fun updateCouplings(t: Double) {
        for ((c, ring) in slewingRing.withIndex()) ring.setTarget(ringWeights(c, slew.position))
        truss.setRestLength(boomHoistRope, maxOf(MIN_ROPE, spec.boomLineLength(luff.position) - spec.pendantLength - luffTrim))
        truss.setRestLength(hoistRope, hoist.position / (1 + store.mass(hookNode) * STANDARD_GRAVITY * gravity.factor(t) / hoistRopeStiffness))
    }

    /** One step of [dt] from time [t]: the drives move toward their targets, then the physics. */
    fun step(t: Double) {
        if (t >= readyAt) {
            slew.advance(dt)
            luff.advance(dt)
        }
        val trimTime = if (t < readyAt) LuffingCrane.SETTLING_TRIM_SECONDS else trimSeconds
        val error = spec.boomLineLength(boomAngle) - spec.boomLineLength(luff.position)
        luffTrim = (luffTrim + error * dt / trimTime).coerceIn(-LuffingCrane.MAX_TRIM, LuffingCrane.MAX_TRIM)
        tipOffset += (store.position(boomTip).y - spec.tipHeight(luff.position) - tipOffset) * (dt / trimTime)
        if (t >= readyAt) {
            hoist.target = spec.tipHeight(luff.position) + tipOffset - hookHeightTarget
            hoist.advance(dt)
        }
        val mass = store.mass(hookNode)
        val wanted = hookMass()
        if (mass != wanted) {
            val stepMass = TowerCrane.LOAD_RAMP_RATE * dt
            store.setMass(hookNode, ScalarExpr.of(if (abs(wanted - mass) <= stepMass) wanted else mass + sign(wanted - mass) * stepMass), t)
        }
        wind.advance(dt)
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
        const val CARBODY_GROUP = "carbody"
        const val PADS_GROUP = "pads"
        const val UPPER_GROUP = "upperworks"
        const val COUNTERWEIGHT_GROUP = "counterweight"
        // Cd·A of the blocks the scene draws (see CraneWind.blockDragArea), m².
        val COUNTERWEIGHT_DRAG_AREA = CraneWind.blockDragArea(1.7, 3.8, 2.9)
        val CAB_DRAG_AREA = CraneWind.blockDragArea(2.2, 1.3, 2.2)
        const val HOOK_GROUP = "hook"
        const val MIN_ROPE = 1.0
        /** The carbody box's bottom, above the ground. */
        const val CARBODY_BOTTOM = 0.5
    }
}
