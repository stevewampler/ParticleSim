package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.BullWheel
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.physics.Truss
import particlesim.physics.TrussWind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/**
 * A guy derrick's dimensions, masses and member sizes - by default a 20 t derrick with a 40 m
 * mast and a 30 m boom, of the kind long used to erect steelwork and to lift at quarries and
 * docks. Masses and member areas aren't from a data sheet: they're chosen so the structure weighs
 * about what such a derrick does and its members work at sensible stresses with the full load at
 * the longest radius. The guys are 28 mm wire rope pretensioned to 60 kN - about 12% of their
 * breaking load, enough that none goes slack at the rated load whichever way the boom points.
 *
 * Coordinates: y up, the mast's foot pin at the origin, its axis along y, the boom along +x at
 * slew angle zero.
 */
data class GuyDerrickSpec(
    /** The top pin's height: where the guys meet. */
    val mastHeight: Double = 40.0,
    val mastWidth: Double = 1.5,
    /** The length of each tapered end, foot and top, down to its pin. */
    val mastTaper: Double = 2.5,
    val mastPanels: Int = 14,
    /** From the boom's foot pins to its tip, along it. */
    val boomLength: Double = 30.0,
    val boomPanels: Int = 14,
    val boomWidth: Double = 1.5,
    val boomDepth: Double = 1.5,
    val boomTipLength: Double = 1.0,
    val minBoomAngle: Double = 20.0,
    val maxBoomAngle: Double = 78.0,
    val guyCount: Int = 8,
    /** The guys' anchors' distance from the foot. */
    val guyAnchorRadius: Double = 90.0,
    val guyPretension: Double = 60e3,
    val maxLoadTonnes: Double = 20.0,
    // Masses, kg.
    val mastMassPerMetre: Double = 300.0,
    val boomMassPerMetre: Double = 220.0,
    /** The top pin's fittings: the spider the guys hang from, the sheaves. */
    val spiderMass: Double = 1000.0,
    val bullWheelMass: Double = 2000.0,
    val boomTipMass: Double = 600.0,
    val hookBlockMass: Double = 800.0,
    // Member cross-section areas, m^2.
    val mastChordArea: Double = 25e-4,
    val mastWebArea: Double = 8e-4,
    val mastTaperArea: Double = 30e-4,
    val boomChordArea: Double = 25e-4,
    val boomWebArea: Double = 8e-4,
    val boomTipArea: Double = 30e-4,
    /** One 28 mm rope's steel. */
    val guyArea: Double = 3.7e-4,
    /** Four falls of 20 mm rope's steel from the mast top to the boom tip. */
    val toppingLiftArea: Double = 4 * 1.9e-4,
    val toppingLiftFalls: Int = 4,
    /** Four falls of 20 mm rope's steel. */
    val hoistRopeArea: Double = 4 * 1.9e-4,
    val hoistRopeFalls: Int = 4,
    // Failure (when it's on): S355 steel, members buckling by their slenderness as round tubes
    // (see Truss.tubeRadiusOfGyration); rope at about 500 kN (28 mm) and 250 kN (20 mm) minimum
    // breaking load a fall.
    val yieldStress: Double = 355e6,
    val ruptureStrain: Double = 0.15,
    val guyBreakingForce: Double = 500e3,
    val toppingLiftBreakingForce: Double = 4 * 250e3,
    val hoistRopeBreakingForce: Double = 4 * 250e3,
    // Drives: swinging at half a revolution a minute, luffing at a degree a second, hoisting at
    // 60 m/min.
    val slewSpeedDegrees: Double = 3.0,
    val slewAccelerationDegrees: Double = 0.3,
    val luffSpeedDegrees: Double = 1.0,
    val luffAccelerationDegrees: Double = 0.4,
    val hoistSpeed: Double = 1.0,
    val hoistAcceleration: Double = 0.5,
    val minHookHeight: Double = 2.0,
    /** The bull wheel's grip, N·m per radian of lag, and its damping, N·m·s. */
    val bullWheelStiffness: Double = 2e8,
    val bullWheelDamping: Double = 5e6,
) {
    val halfWidth: Double get() = mastWidth / 2
    val mastPanel: Double get() = (mastHeight - 2 * mastTaper) / mastPanels
    val boomPanel: Double get() = (boomLength - boomTipLength) / boomPanels
    /** The boom's foot pins' height: on the mast's lowest square. */
    val boomFootHeight: Double get() = mastTaper

    // The boom's depth hangs below the line of its foot pins, so standing up it clears the mast;
    // the tip sheave is half its depth below that line.

    /** The tip sheave's distance from the mast's axis, the boom straight at [degrees]. */
    fun tipRadius(degrees: Double): Double {
        val a = degrees * PI / 180
        return halfWidth + boomLength * cos(a) + boomDepth / 2 * sin(a)
    }

    /** The tip sheave's height, the boom straight at [degrees]. */
    fun tipHeight(degrees: Double): Double {
        val a = degrees * PI / 180
        return boomFootHeight + boomLength * sin(a) - boomDepth / 2 * cos(a)
    }

    /** From the top pin to the tip sheave, the boom straight at [degrees]: the topping lift. */
    fun toppingLiftLength(degrees: Double): Double = hypot(tipRadius(degrees), mastHeight - tipHeight(degrees))

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

    /** Guy [k]'s anchor, in plan: evenly round, half a spacing off the boom's line at slew zero. */
    fun anchor(k: Int): Vector3 {
        val phi = (k + 0.5) * 2 * PI / guyCount
        return Vector3(guyAnchorRadius * cos(phi), 0.0, -guyAnchorRadius * sin(phi))
    }
}

/**
 * A guy derrick ([spec]) as a pin-jointed steel [Truss] at real stiffness and real masses, moving
 * at real speed like the other cranes.
 *
 * - **Mast**: a square lattice tapered at both ends to pins. The foot pin stands on a fixed base;
 *   the top pin is held only by the **guys** - tension-only wire ropes to anchors all round,
 *   pretensioned (brought on as gravity ramps) so they hold the top from every side. The mast is
 *   pinned at both ends, so it carries the boom's thrust down to its foot and leaves the moment to
 *   the guys: the ones behind the boom tighten, the ones in front ease off.
 * - **Bull wheel**: being pinned at both ends, the mast is free to turn about its own axis; a
 *   [BullWheel] at its foot swings it - and the boom with it - by a pure torque. The boom passes
 *   under the guys, so it can swing all the way round.
 * - **Boom**: a box lattice pinned at its foot to the mast's lowest square. The **topping lift**
 *   runs from the top pin to the boom tip, and its length - paid out or hauled in - sets the
 *   boom's angle, trimmed for stretch and sag as [LuffingCrane]'s luffing rope is.
 * - **Hook**: hangs from the boom tip on a tension-only hoist rope, held level while the boom
 *   luffs.
 *
 * The derrick's classic property: the boom's thrust from a load at its tip doesn't depend on the
 * radius - the load, the topping lift and the boom make a triangle of forces similar to the one
 * the mast, the topping lift and the boom make in space - while the topping lift's pull and the
 * guys' work grow as the boom comes down.
 *
 * Gravity ramps on over [rampSeconds] under heavy structural damping, held for [settlingSeconds]
 * more and then eased off; the drives answer from [readyAt].
 */
class GuyDerrick(
    val spec: GuyDerrickSpec = GuyDerrickSpec(),
    val dt: Double = CRANE_DT,
    slewDegrees: Double = 0.0,
    boomAngle: Double = 50.0,
    hookHeight: Double = 10.0,
    loadTonnes: Double = 0.0,
    val rampSeconds: Double = 3.0,
    val settlingSeconds: Double = 4.0,
    val easeSeconds: Double = 2.0,
    val trimSeconds: Double = LuffingCrane.TRIM_SECONDS,
) {
    val store = ParticleStore()
    val groups = Groups()
    private val h = spec.halfWidth

    private fun node(p: Vector3, mass: Double, group: String, radius: Double? = TowerCrane.NODE_RADIUS): Int {
        val id = store.create(position = p, mass = ScalarExpr.of(mass), radius = radius?.let { ScalarExpr.of(it) })
        groups.add(group, id)
        return id
    }

    // ---- Nodes.

    /** The mast's foot pin, on its base. */
    val foot: Int = node(Vector3.ZERO, 1.0, FOOT_GROUP, radius = null)

    private val mastNodeMass = spec.mastMassPerMetre * spec.mastHeight / (4 * (spec.mastPanels + 1))
    /** `mast[level][corner]` in [squareCorner] order, level 0 at the top of the foot's taper.
     * Level 0 carries the bull wheel and the boom's foot pins (corners 1 and 2). */
    val mast: List<List<Int>> = (0..spec.mastPanels).map { level ->
        val y = spec.mastTaper + level * spec.mastPanel
        (0 until 4).map { c ->
            val (x, z) = squareCorner(c, h)
            node(Vector3(x, y, z), mastNodeMass + if (level == 0) spec.bullWheelMass / 4 else 0.0, DERRICK_GROUP)
                .also { if (level == 0) groups.add(BULL_WHEEL_GROUP, it) }
        }
    }
    /** The top pin, where the guys, the topping lift and the mast meet. */
    val topPin: Int = node(Vector3(0.0, spec.mastHeight, 0.0), spec.spiderMass, DERRICK_GROUP)
    val boomFoot: List<Int> = listOf(mast[0][1], mast[0][2])

    private fun onBoom(degrees: Double, along: Double, up: Double, side: Double): Vector3 {
        val a = degrees * PI / 180
        return Vector3(h + along * cos(a) - up * sin(a), spec.boomFootHeight + along * sin(a) + up * cos(a), side)
    }

    private val boomNodeMass = spec.boomMassPerMetre * spec.boomLength / (4 * spec.boomPanels)
    private val bw = spec.boomWidth / 2
    /** The boom's cross-sections from panel point 1, each four nodes going round it: upper -z,
     * upper +z, lower +z, lower -z (the upper ones on the line of the foot pins). */
    val boom: List<List<Int>> = (1..spec.boomPanels).map { k ->
        listOf(0.0 to -bw, 0.0 to bw, -spec.boomDepth to bw, -spec.boomDepth to -bw).map { (up, side) ->
            node(onBoom(boomAngle, k * spec.boomPanel, up, side), boomNodeMass, DERRICK_GROUP)
        }
    }
    val boomTip: Int = node(onBoom(boomAngle, spec.boomLength, -spec.boomDepth / 2, 0.0), spec.boomTipMass, DERRICK_GROUP)

    var loadTonnes: Double = loadTonnes
        set(value) { require(value >= 0.0 && value.isFinite()); field = value }
    private fun hookMass() = spec.hookBlockMass + 1000 * loadTonnes
    val hookNode: Int = node(Vector3(store.position(boomTip).x, hookHeight, 0.0), hookMass(), HOOK_GROUP, TowerCrane.HOOK_RADIUS)

    /** The guys' anchors, fixed in the ground. */
    val anchors: List<Int> = (0 until spec.guyCount).map { node(spec.anchor(it), 1.0, ANCHOR_GROUP, radius = null) }

    init {
        // Built at slew zero; swung to where it starts (positive turns +x toward -z).
        val theta = slewDegrees * PI / 180
        if (theta != 0.0) {
            for (id in groups.membersOf(DERRICK_GROUP) + groups.membersOf(HOOK_GROUP)) {
                val p = store.position(id)
                store.setPosition(id, Vector3(p.x * cos(theta) + p.z * sin(theta), p.y, -p.x * sin(theta) + p.z * cos(theta)))
            }
        }
    }

    // ---- Members.

    /** What each member is part of, for colouring and drawing. */
    enum class Part { MAST_CHORD, MAST_WEB, MAST_TAPER, BOOM_CHORD, BOOM_WEB, BOOM_TIP, GUY, TOPPING_LIFT, HOIST_ROPE }

    private val memberList = ArrayList<Truss.Member>()
    private val partList = ArrayList<Part>()
    private fun member(a: Int, b: Int, area: Double, part: Part): Int {
        val length = (store.position(b) - store.position(a)).length()
        memberList += when (part) {
            Part.GUY, Part.TOPPING_LIFT, Part.HOIST_ROPE -> Truss.Member(
                a, b, area, WIRE_ROPE_MODULUS, tensionOnly = true, restLength = length, stiffnessDamping = TowerCrane.ROPE_DAMPING,
                breakingForce = when (part) {
                    Part.GUY -> spec.guyBreakingForce
                    Part.TOPPING_LIFT -> spec.toppingLiftBreakingForce
                    else -> spec.hoistRopeBreakingForce
                },
            )
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

    /** The four legs of the foot's taper, from the foot pin to the lowest square. */
    val footTaper: List<Int>
    /** The six members from the boom's foot pins into its first cross-section. */
    val boomFootMembers: List<Int>
    val guys: List<Int>
    val toppingLift: Int
    val hoistRope: Int

    init {
        // Mast: the foot's taper, its lowest square braced, box panels up, the top's taper.
        footTaper = mast[0].map { member(foot, it, spec.mastTaperArea, Part.MAST_TAPER) }
        bracedSection(mast[0], spec.mastWebArea, Part.MAST_WEB)
        for (level in 0 until spec.mastPanels) boxPanel(mast[level], mast[level + 1], level, spec.mastChordArea, spec.mastWebArea, Part.MAST_CHORD, Part.MAST_WEB)
        for (id in mast.last()) member(id, topPin, spec.mastTaperArea, Part.MAST_TAPER)

        // Boom: its first cross-section braced on itself and back to the two foot pins - on the
        // line between them, so it pivots there - then box panels out to the tip.
        val first = boom[0]
        bracedSection(first, spec.boomWebArea, Part.BOOM_WEB)
        val (pinMinus, pinPlus) = boomFoot
        boomFootMembers = listOf(
            member(pinMinus, first[0], spec.boomChordArea, Part.BOOM_CHORD),
            member(pinPlus, first[1], spec.boomChordArea, Part.BOOM_CHORD),
            member(pinPlus, first[2], spec.boomChordArea, Part.BOOM_CHORD),
            member(pinMinus, first[3], spec.boomChordArea, Part.BOOM_CHORD),
            member(pinMinus, first[1], spec.boomWebArea, Part.BOOM_WEB),
            member(pinPlus, first[3], spec.boomWebArea, Part.BOOM_WEB),
        )
        for (k in 0 until boom.lastIndex) boxPanel(boom[k], boom[k + 1], k, spec.boomChordArea, spec.boomWebArea, Part.BOOM_CHORD, Part.BOOM_WEB)
        for (id in boom.last()) member(id, boomTip, spec.boomTipArea, Part.BOOM_TIP)

        // Their rest lengths are set every step (see updateCouplings).
        guys = anchors.map { member(topPin, it, spec.guyArea, Part.GUY) }
        toppingLift = member(topPin, boomTip, spec.toppingLiftArea, Part.TOPPING_LIFT)
        hoistRope = member(boomTip, hookNode, spec.hoistRopeArea, Part.HOIST_ROPE)
    }

    val parts: List<Part> = partList
    val truss = Truss(store, memberList, stiffnessDamping = 3e-4, name = "steel")
    private val guyLength = DoubleArray(guys.size) { truss.restLength(guys[it]) }

    // ---- Drives and couplings.

    val slew = Drive(slewDegrees, spec.slewSpeedDegrees, spec.slewAccelerationDegrees)
    /** The boom angle the topping lift is driving to, degrees. */
    val luff = Drive(boomAngle.coerceIn(spec.minBoomAngle, spec.maxBoomAngle), spec.luffSpeedDegrees, spec.luffAccelerationDegrees, spec.minBoomAngle, spec.maxBoomAngle)
    /** The hook's radius the boom is luffing to: [luff]'s target, as a radius. */
    var radiusTarget: Double
        get() = spec.tipRadius(luff.target)
        set(value) { luff.target = spec.boomAngleFor(value) }
    var hookHeightTarget: Double = hookHeight
        set(value) { field = value.coerceIn(spec.minHookHeight, spec.tipHeight(spec.maxBoomAngle) - MIN_ROPE) }
    private val hoist = Drive(truss.restLength(hoistRope), spec.hoistSpeed, spec.hoistAcceleration, MIN_ROPE, spec.tipHeight(spec.maxBoomAngle) - spec.minHookHeight)
    private val hoistRopeStiffness = WIRE_ROPE_MODULUS * spec.hoistRopeArea

    /** How much shorter than a straight boom's geometry the topping lift is hauled in, learnt
     * from the angle the boom actually sits at (see [LuffingCrane.luffTrim]). */
    var luffTrim: Double = 0.0
        private set
    private var tipOffset = 0.0

    /** Slew rate, rad/s. */
    val slewRate: Double get() = slew.velocity * PI / 180

    val bullWheel = BullWheel(
        BULL_WHEEL_GROUP, groups, store, spec.bullWheelStiffness, spec.bullWheelDamping, startDegrees = slewDegrees,
        target = { slew.position }, targetRate = { slew.velocity },
    )
    val structuralDamping = SlewFrameDamping(DERRICK_GROUP, ANCHOR_GROUP, groups, { slewRate }, TowerCrane.SETTLING_DAMPING)
    /** Damping on the hook while the derrick settles, gone once it's ready. Unnamed: there's
     * nothing to edit in it. */
    val hookSettling = SlewFrameDamping(HOOK_GROUP, ANCHOR_GROUP, groups, { 0.0 }, TowerCrane.SETTLING_DAMPING, name = null)
    val gravity = RampedGravity(listOf(DERRICK_GROUP, HOOK_GROUP), groups, rampSeconds)

    /** The crate a load comes in - the scene draws it, the wind pushes on it. */
    val loadCrate = LoadCrate(3.0, 1.5, 2.0)
    /** The wind it works in: still until set. */
    val wind = CraneWind()
    /** The wind on every member, guy and rope, and on the hook and its load. */
    val windForce = TrussWind(
        truss, store, groups,
        DoubleArray(truss.size) { i ->
            when (parts[i]) {
                Part.GUY -> CraneWind.ropeDragWidth(spec.guyArea, 1)
                Part.TOPPING_LIFT -> CraneWind.ropeDragWidth(spec.toppingLiftArea, spec.toppingLiftFalls)
                Part.HOIST_ROPE -> CraneWind.ropeDragWidth(spec.hoistRopeArea, spec.hoistRopeFalls)
                else -> CraneWind.tubeDragWidth(memberList[i].area)
            }
        },
        listOf(TrussWind.Body(HOOK_GROUP) { GantryCrane.HOOK_BLOCK_DRAG_AREA + loadCrate.dragArea(loadTonnes) }),
        VectorExpr.of { t -> wind.velocity(t) },
        profileExponent = CraneWind.PROFILE_EXPONENT,
    )
    val forces: List<Force> = listOf(truss, gravity, bullWheel, structuralDamping, hookSettling, windForce)

    /** Whether the steel and rope can fail (see [Truss]): off, they stay elastic however loaded. */
    var failure: Boolean
        get() = truss.failure
        set(value) { truss.failure = value }

    /** The ground, for whatever falls once something has failed. */
    val collisions = CollisionSystem(
        listOf(DERRICK_GROUP, HOOK_GROUP).map { group ->
            ParticleColliderRule(group, PlaneCollider(VectorExpr.of(Vector3.ZERO), Vector3(0.0, 1.0, 0.0), name = "ground"), restitution = 0.0, staticFriction = 0.6, kineticFriction = 0.5)
        },
    )
    val constraints: List<Constraint> = listOf(
        FixedPosition.atCurrentPositions(FOOT_GROUP, store, groups, name = "foot pin"),
        FixedPosition.atCurrentPositions(ANCHOR_GROUP, store, groups, name = "guy anchors"),
    )

    private val integrator = Integrator()

    init { updateCouplings(0.0) }

    /** The angle the boom actually sits at, degrees above horizontal. */
    val boomAngle: Double get() {
        val pins = (store.position(boomFoot[0]) + store.position(boomFoot[1])) * 0.5
        val d = store.position(boomTip) - pins
        return (atan2(d.y, hypot(d.x, d.z)) + atan2(spec.boomDepth / 2, spec.boomLength)) * 180 / PI
    }

    /** The hook's actual radius: the tip sheave's horizontal distance from the foot pin. */
    val radius: Double get() {
        val p = store.position(boomTip)
        return hypot(p.x, p.z)
    }

    val slewDegrees: Double get() = slew.position

    /** Each guy's tension, newtons. */
    val guyTensions: List<Double> get() = guys.map { truss.axialForce(store, it) }

    /** The guys' pull on the top pin, all together. */
    val guyForce: Vector3 get() = guys.fold(Vector3.ZERO) { s, g -> s + memberPull(g, topPin) }

    /** What the foot pin pushes the mast up with: its taper legs' thrust, together. */
    val footThrust: Vector3 get() = footTaper.fold(Vector3.ZERO) { s, m -> s - memberPull(m, foot) }

    /** What the boom pushes on its foot pins with, together. */
    val boomThrust: Vector3 get() = boomFootMembers.fold(Vector3.ZERO) { s, m ->
        val member = truss.members[m]
        val pin = if (member.a in boomFoot) member.a else member.b
        s + memberPull(m, pin)
    }

    /** The force member [m] puts on its end [at]: toward its other end in tension. */
    private fun memberPull(m: Int, at: Int): Vector3 {
        val member = truss.members[m]
        val other = if (member.a == at) member.b else member.a
        return (store.position(other) - store.position(at)).normalized() * truss.axialForce(store, m)
    }

    /** What it's rated to lift: the same at every radius - its boom's thrust doesn't grow as it
     * comes down, and the guys and topping lift are sized for the full load at the longest. */
    fun ratedLoadTonnes(): Double = spec.maxLoadTonnes

    private fun updateCouplings(t: Double) {
        // The guys' pretension comes on with gravity, so the mast isn't struck by it.
        val pre = spec.guyPretension * gravity.factor(t) / (WIRE_ROPE_MODULUS * spec.guyArea)
        for ((k, g) in guys.withIndex()) truss.setRestLength(g, guyLength[k] / (1 + pre))
        truss.setRestLength(toppingLift, maxOf(MIN_ROPE, spec.toppingLiftLength(luff.position) - luffTrim))
        truss.setRestLength(hoistRope, hoist.position / (1 + store.mass(hookNode) * STANDARD_GRAVITY * gravity.factor(t) / hoistRopeStiffness))
    }

    /** One step of [dt] from time [t]: the drives move toward their targets, then the physics. */
    fun step(t: Double) {
        if (t >= readyAt) {
            slew.advance(dt)
            luff.advance(dt)
        }
        val trimTime = if (t < readyAt) LuffingCrane.SETTLING_TRIM_SECONDS else trimSeconds
        val error = spec.toppingLiftLength(boomAngle) - spec.toppingLiftLength(luff.position)
        luffTrim = (luffTrim + error * dt / trimTime).coerceIn(-LuffingCrane.MAX_TRIM, LuffingCrane.MAX_TRIM)
        tipOffset += (store.position(boomTip).y - spec.tipHeight(luff.position) - tipOffset) * (dt / trimTime)
        if (t >= readyAt) {
            hoist.target = spec.tipHeight(luff.position) + tipOffset - hookHeightTarget
            hoist.advance(dt)
        }
        // A load comes on (or off) the hook over a few seconds, the rope taking it up.
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

    /** When the derrick has settled under its own weight and the drives start answering. */
    val readyAt: Double get() = rampSeconds + settlingSeconds

    /** Heavy while gravity comes on and for [settlingSeconds] after, then easing to the
     * operating level over [easeSeconds]. */
    fun dampingAt(t: Double): Double {
        val easeStart = readyAt
        if (t <= easeStart) return TowerCrane.SETTLING_DAMPING
        if (t >= easeStart + easeSeconds) return TowerCrane.OPERATING_DAMPING
        val f = (t - easeStart) / easeSeconds
        return TowerCrane.SETTLING_DAMPING + (TowerCrane.OPERATING_DAMPING - TowerCrane.SETTLING_DAMPING) * f
    }

    companion object {
        const val DERRICK_GROUP = "mast and boom"
        const val BULL_WHEEL_GROUP = "bull wheel"
        const val FOOT_GROUP = "foot pin"
        const val ANCHOR_GROUP = "guy anchors"
        const val HOOK_GROUP = "hook"
        const val MIN_ROPE = 1.0
    }
}
