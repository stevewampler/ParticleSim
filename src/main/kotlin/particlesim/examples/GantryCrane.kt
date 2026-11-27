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
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * A goliath (gantry) crane's dimensions, masses and member sizes - by default 40 t over a 30 m
 * span, 16 m clear under the girder, travelling on a pair of rails 90 m long. Not from a data
 * sheet: the masses are chosen so the structure weighs about what a crane of that class does, and
 * the member areas so its girder works at roughly 100-150 MPa with the full load in the middle of
 * the span.
 *
 * Coordinates: y up, the span along x (the rigid leg at -x, the flexible leg at +x), the rails
 * along z, everything centred on the origin with the gantry in the middle of its rails.
 */
data class GantryCraneSpec(
    /** Between the legs' centre lines. */
    val span: Double = 30.0,
    /** How far the girder runs on past each leg's centre line: one panel, which the rigid leg's
     * V stands on. */
    val girderPanel: Double = 2.0,
    /** The girder: a box lattice this wide (along the rails) and deep. */
    val girderWidth: Double = 2.0,
    val girderDepth: Double = 2.5,
    /** Clear height: ground to the girder's bottom chords, which the trolley runs on. */
    val girderHeight: Double = 16.0,
    /** Each leg's feet are this far apart along its rail. */
    val legSpread: Double = 10.0,
    val railLength: Double = 90.0,
    /** How far the gantry can travel either way from the rails' middle. */
    val travelRange: Double = 30.0,
    /** How near the trolley can run to a leg's centre line - the hook clear of its members. */
    val trolleyClearance: Double = 3.0,
    val maxLoadTonnes: Double = 40.0,
    // Masses, kg.
    val girderMassPerMetre: Double = 800.0,
    val rigidLegMass: Double = 10000.0,
    val flexibleLegMass: Double = 6000.0,
    /** Each leg's sill beam and wheel bogies, on its feet. */
    val sillMass: Double = 8000.0,
    /** The trolley, with the hoist winch on it. */
    val trolleyMass: Double = 12000.0,
    val cabMass: Double = 2000.0,
    val hookBlockMass: Double = 1500.0,
    // Member cross-section areas, m^2.
    val girderChordArea: Double = 65e-4,
    val girderWebArea: Double = 30e-4,
    val legArea: Double = 120e-4,
    val legBraceArea: Double = 60e-4,
    val sillArea: Double = 100e-4,
    /** Eight falls of 20 mm rope's steel. */
    val hoistRopeArea: Double = 8 * 1.9e-4,
    /** The legs are big, thin-walled tubes. */
    val legDiameterOverThickness: Double = 60.0,
    // Failure (when it's on): S355 steel, members buckling by their slenderness as round tubes
    // (see Truss.tubeRadiusOfGyration); rope at about 250 kN minimum breaking load a fall.
    val yieldStress: Double = 355e6,
    val ruptureStrain: Double = 0.15,
    val hoistRopeBreakingForce: Double = 8 * 250e3,
    // Drives: travelling at 36 m/min, the trolley at 30 m/min, hoisting at 24 m/min.
    val travelSpeed: Double = 0.6,
    val travelAcceleration: Double = 0.15,
    val trolleySpeed: Double = 0.5,
    val trolleyAcceleration: Double = 0.25,
    val hoistSpeed: Double = 0.4,
    val hoistAcceleration: Double = 0.3,
    val minHookHeight: Double = 2.0,
) {
    val halfSpan: Double get() = span / 2
    val halfWidth: Double get() = girderWidth / 2
    /** The girder's panels, end to end: the span plus one each end. */
    val girderPanels: Int get() = Math.round(span / girderPanel).toInt() + 2
    val girderLength: Double get() = girderPanels * girderPanel
    val girderTop: Double get() = girderHeight + girderDepth
    /** Panel point `k`'s x. */
    fun girderX(k: Int): Double = -halfSpan - girderPanel + k * girderPanel
    /** The panel points under the legs' centre lines. */
    val rigidLegPanel: Int get() = 1
    val flexibleLegPanel: Int get() = girderPanels - 1
    val minTrolley: Double get() = -halfSpan + trolleyClearance
    val maxTrolley: Double get() = halfSpan - trolleyClearance
}

/**
 * A goliath crane ([spec]) - a bridge girder on two legs that travel on rails, a trolley running
 * across the girder with the hoist on it - as a pin-jointed steel [Truss] at real stiffness and
 * real masses, moving at real speed like [TowerCrane] and [LuffingCrane].
 *
 * - **Girder**: a square box lattice spanning between the legs, one panel past each.
 * - **Rigid leg** (at -x): from each of its two feet a V of tubes up to the girder's bottom
 *   chords a panel either side of the leg's centre line - so it holds the girder in place along
 *   the span.
 * - **Flexible leg** (at +x): two tubes straight up from its feet, cross-braced with the sill in
 *   their own plane across the span and nothing else - so it can rock about its feet along the
 *   span. That's what real goliaths do: the girder can sag and grow with the sun without pushing
 *   the legs apart, and the girder works as a simple beam - each leg's share of a load follows
 *   from the lever rule ([rigidLegReaction], [flexibleLegReaction]).
 * - **Rails**: each a pair of fixed anchors at its ends. Each foot is held to its rail by a
 *   [WeightedAttachment] at the gantry's place along it - the travel drive moves that place, so
 *   the wheels drive the gantry along, and the load swings when it starts and stops. The feet
 *   can't lift off the rails.
 * - **Trolley**: a particle held by a [WeightedAttachment] between the girder's bottom chords, so
 *   its load bears on the panel points either side.
 * - **Hook**: hangs from the trolley on a tension-only rope whose length the hoist pays out.
 *
 * Gravity ramps on over [rampSeconds] under heavy structural damping, held for [settlingSeconds]
 * more and then eased off; the drives answer from [readyAt].
 */
class GantryCrane(
    val spec: GantryCraneSpec = GantryCraneSpec(),
    val dt: Double = CRANE_DT,
    travel: Double = 0.0,
    trolley: Double = 0.0,
    hookHeight: Double = 8.0,
    loadTonnes: Double = 0.0,
    val rampSeconds: Double = 3.0,
    val settlingSeconds: Double = 4.0,
    val easeSeconds: Double = 2.0,
) {
    val store = ParticleStore()
    val groups = Groups()
    private val w = spec.halfWidth

    private fun node(p: Vector3, mass: Double, group: String, radius: Double? = TowerCrane.NODE_RADIUS): Int {
        val id = store.create(position = p, mass = ScalarExpr.of(mass), radius = radius?.let { ScalarExpr.of(it) })
        groups.add(group, id)
        return id
    }

    private val travel0 = travel.coerceIn(-spec.travelRange, spec.travelRange)

    // ---- Nodes.

    /** The legs' feet, on the rails: `[-z foot, +z foot]`. */
    val rigidFeet: List<Int> = listOf(-1.0, 1.0).map { s ->
        node(Vector3(-spec.halfSpan, 0.0, travel0 + s * spec.legSpread / 2), spec.sillMass / 2 + spec.rigidLegMass / 4, GANTRY_GROUP, null)
    }
    val flexibleFeet: List<Int> = listOf(-1.0, 1.0).map { s ->
        node(Vector3(spec.halfSpan, 0.0, travel0 + s * spec.legSpread / 2), spec.sillMass / 2 + spec.flexibleLegMass / 4, GANTRY_GROUP, null)
    }
    val feet: List<Int> get() = rigidFeet + flexibleFeet

    /** Rail anchors: `[-z end, +z end]` of the rigid leg's rail and of the flexible leg's. */
    val rigidRail: List<Int> = listOf(-1.0, 1.0).map { s -> node(Vector3(-spec.halfSpan, 0.0, s * spec.railLength / 2), 1.0, RAILS_GROUP, null) }
    val flexibleRail: List<Int> = listOf(-1.0, 1.0).map { s -> node(Vector3(spec.halfSpan, 0.0, s * spec.railLength / 2), 1.0, RAILS_GROUP, null) }

    /** `girder[k]`, panel point k's cross-section going round it: bottom -z, bottom +z, top +z,
     * top -z. The rigid leg stands under panels 0 and 2, the flexible leg under the last but
     * one. */
    val girder: List<List<Int>> = (0..spec.girderPanels).map { k ->
        val x = spec.girderX(k)
        val m = spec.girderMassPerMetre * spec.girderLength / (4 * (spec.girderPanels + 1))
        listOf(0.0 to -w, 0.0 to w, spec.girderDepth to w, spec.girderDepth to -w).mapIndexed { c, (up, z) ->
            val legTop = c < 2 && (k == spec.rigidLegPanel - 1 || k == spec.rigidLegPanel + 1)
            val flexTop = c < 2 && k == spec.flexibleLegPanel
            val cab = c == 0 && k == spec.rigidLegPanel + 1
            node(
                Vector3(x, spec.girderHeight + up, travel0 + z),
                m + (if (legTop) spec.rigidLegMass / 8 else 0.0) + (if (flexTop) spec.flexibleLegMass / 4 else 0.0) + (if (cab) spec.cabMass else 0.0),
                GANTRY_GROUP,
            )
        }
    }

    val trolleyNode: Int = node(Vector3(trolley.coerceIn(spec.minTrolley, spec.maxTrolley), spec.girderHeight, travel0), spec.trolleyMass, TROLLEY_GROUP)

    var loadTonnes: Double = loadTonnes
        set(value) { require(value >= 0.0 && value.isFinite()); field = value }
    private fun hookMass() = spec.hookBlockMass + 1000 * loadTonnes
    val hookNode: Int = node(
        Vector3(store.position(trolleyNode).x, hookHeight.coerceIn(spec.minHookHeight, spec.girderHeight - MIN_ROPE), travel0),
        hookMass(), HOOK_GROUP, TowerCrane.HOOK_RADIUS,
    )

    // ---- Members.

    /** What each member is part of, for colouring and drawing. */
    enum class Part { GIRDER_CHORD, GIRDER_WEB, RIGID_LEG, FLEXIBLE_LEG, LEG_BRACE, SILL, HOIST_ROPE }

    private val memberList = ArrayList<Truss.Member>()
    private val partList = ArrayList<Part>()
    private fun member(a: Int, b: Int, area: Double, part: Part): Int {
        val length = (store.position(b) - store.position(a)).length()
        memberList += if (part == Part.HOIST_ROPE) {
            Truss.Member(
                a, b, area, WIRE_ROPE_MODULUS, tensionOnly = true, restLength = length, stiffnessDamping = TowerCrane.ROPE_DAMPING,
                breakingForce = spec.hoistRopeBreakingForce,
            )
        } else {
            val tube = if (part == Part.GIRDER_CHORD || part == Part.GIRDER_WEB) 20.0 else spec.legDiameterOverThickness
            Truss.Member(
                a, b, area, STEEL_YOUNGS_MODULUS,
                yieldStress = spec.yieldStress,
                bucklingStress = Truss.rankineBucklingStress(spec.yieldStress, STEEL_YOUNGS_MODULUS, length, Truss.tubeRadiusOfGyration(area, tube)),
                ruptureStrain = spec.ruptureStrain,
            )
        }
        partList += part
        return memberList.size - 1
    }

    /** The bottom and top chords of the panel either side of mid-span - the most bent. */
    val midspanBottomChords: List<Int>
    val midspanTopChords: List<Int>
    val hoistRope: Int

    init {
        // Girder: its first cross-section braced on itself, then box panels (each bracing its far
        // cross-section) end to end.
        val first = girder[0]
        for (c in 0 until 4) member(first[c], first[(c + 1) % 4], spec.girderWebArea, Part.GIRDER_WEB)
        member(first[0], first[2], spec.girderWebArea, Part.GIRDER_WEB)
        member(first[1], first[3], spec.girderWebArea, Part.GIRDER_WEB)
        val mid = spec.girderPanels / 2
        var bottom = emptyList<Int>(); var top = emptyList<Int>()
        for (k in 0 until spec.girderPanels) {
            val chords = boxPanel(girder[k], girder[k + 1], k)
            if (k == mid) { bottom = chords.take(2); top = chords.drop(2) }
        }
        midspanBottomChords = bottom
        midspanTopChords = top

        // Rigid leg: a V from each foot to the bottom chord on its side, a panel either side of
        // the leg's centre line; the sill between the feet.
        for ((c, foot) in rigidFeet.withIndex()) {
            member(foot, girder[spec.rigidLegPanel - 1][c], spec.legArea, Part.RIGID_LEG)
            member(foot, girder[spec.rigidLegPanel + 1][c], spec.legArea, Part.RIGID_LEG)
        }
        member(rigidFeet[0], rigidFeet[1], spec.sillArea, Part.SILL)

        // Flexible leg: straight up from each foot to the bottom chord on its side, all in the
        // plane across the span, cross-braced in it - it rocks about its feet along the span.
        val flexTop = girder[spec.flexibleLegPanel]
        for ((c, foot) in flexibleFeet.withIndex()) member(foot, flexTop[c], spec.legArea, Part.FLEXIBLE_LEG)
        member(flexibleFeet[0], flexTop[1], spec.legBraceArea, Part.LEG_BRACE)
        member(flexibleFeet[1], flexTop[0], spec.legBraceArea, Part.LEG_BRACE)
        member(flexibleFeet[0], flexibleFeet[1], spec.sillArea, Part.SILL)

        // The rope's rest length is set every step (see updateCouplings).
        hoistRope = member(trolleyNode, hookNode, spec.hoistRopeArea, Part.HOIST_ROPE)
    }

    /** One panel of the girder from cross-section [lo] to [hi]: chords, a diagonal on every
     * face (alternating with [parity]), and [hi]'s edges and both its diagonals. Returns the
     * chords, in corner order. */
    private fun boxPanel(lo: List<Int>, hi: List<Int>, parity: Int): List<Int> {
        val chords = (0 until 4).map { c -> member(lo[c], hi[c], spec.girderChordArea, Part.GIRDER_CHORD) }
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            if ((parity + c) % 2 == 0) member(lo[c], hi[n], spec.girderWebArea, Part.GIRDER_WEB) else member(lo[n], hi[c], spec.girderWebArea, Part.GIRDER_WEB)
            member(hi[c], hi[n], spec.girderWebArea, Part.GIRDER_WEB)
        }
        member(hi[0], hi[2], spec.girderWebArea, Part.GIRDER_WEB)
        member(hi[1], hi[3], spec.girderWebArea, Part.GIRDER_WEB)
        return chords
    }

    val parts: List<Part> = partList
    val truss = Truss(store, memberList, stiffnessDamping = 3e-4, name = "steel")

    // ---- Drives and couplings.

    /** Where the gantry is along its rails: its legs' centre, from the rails' middle (+z). */
    val travel = Drive(travel0, spec.travelSpeed, spec.travelAcceleration, -spec.travelRange, spec.travelRange)
    /** Where the trolley is across the span, from its middle (+x, toward the flexible leg). */
    val trolley = Drive(store.position(trolleyNode).x, spec.trolleySpeed, spec.trolleyAcceleration, spec.minTrolley, spec.maxTrolley)
    var hookHeightTarget: Double = store.position(hookNode).y
        set(value) { field = value.coerceIn(spec.minHookHeight, spec.girderHeight - MIN_ROPE) }
    /** The rope's length from trolley to hook with the hook hanging still: what the hoist
     * controls. */
    private val hoist = Drive(truss.restLength(hoistRope), spec.hoistSpeed, spec.hoistAcceleration, MIN_ROPE, spec.girderHeight - spec.minHookHeight)
    private val ropeAxialStiffness = WIRE_ROPE_MODULUS * spec.hoistRopeArea
    private var trolleyHeight = store.position(trolleyNode).y

    /** Each foot held to its rail at the gantry's place along it: [rigidFeet] then
     * [flexibleFeet]. Unnamed: four identical entries would only clutter the outliner. */
    val wheels: List<WeightedAttachment> = feet.map { id ->
        WeightedAttachment(id, WHEEL_STIFFNESS, 2 * 0.5 * sqrt(WHEEL_STIFFNESS * store.mass(id)))
    }
    // Unnamed: it has nothing to edit, and "trolley" is already the group's name.
    val trolleyCarriage = WeightedAttachment(trolleyNode, TowerCrane.TROLLEY_STIFFNESS, 2 * 0.5 * sqrt(TowerCrane.TROLLEY_STIFFNESS * spec.trolleyMass))

    /** The gantry's travelling velocity, m/s along +z. */
    private val travelVelocity: Vector3 get() = Vector3(0.0, 0.0, travel.velocity)

    /** Damps the gantry's sway about where it's travelling to, not the travel itself. */
    val structuralDamping = SlewFrameDamping(GANTRY_GROUP, RAILS_GROUP, groups, { 0.0 }, TowerCrane.SETTLING_DAMPING, frameVelocity = { travelVelocity })
    /** Damping on the hook while the crane settles - the girder's sag moves the trolley, which
     * would leave the hook swinging - gone once it's ready, so the load swings freely. Unnamed:
     * there's nothing to edit in it. */
    val hookSettling = SlewFrameDamping(HOOK_GROUP, RAILS_GROUP, groups, { 0.0 }, TowerCrane.SETTLING_DAMPING, name = null)
    val gravity = RampedGravity(listOf(GANTRY_GROUP, TROLLEY_GROUP, HOOK_GROUP), groups, rampSeconds)
    val forces: List<Force> = listOf(truss, gravity, structuralDamping, hookSettling, trolleyCarriage) + wheels

    /** Whether the steel can fail (see [Truss]): off, it stays elastic however it's loaded. */
    var failure: Boolean
        get() = truss.failure
        set(value) { truss.failure = value }

    /** The ground, for whatever falls once something has failed. */
    val collisions = CollisionSystem(
        listOf(GANTRY_GROUP, TROLLEY_GROUP, HOOK_GROUP).map { group ->
            ParticleColliderRule(group, PlaneCollider(VectorExpr.of(Vector3.ZERO), Vector3(0.0, 1.0, 0.0), name = "ground"), restitution = 0.0, staticFriction = 0.6, kineticFriction = 0.5)
        },
    )
    val constraints: List<Constraint> = listOf(FixedPosition.atCurrentPositions(RAILS_GROUP, store, groups, name = "rails"))

    private val integrator = Integrator()

    init { updateCouplings(0.0) }

    /** A foot's weights over its rail's two ends ([rail]), at its place along the rail with the
     * gantry at [travel] ([side] -1 for the -z foot, +1 for the +z foot). */
    fun wheelWeights(rail: List<Int>, side: Int, travel: Double): List<Pair<Int, Double>> {
        val f = (travel + side * spec.legSpread / 2 + spec.railLength / 2) / spec.railLength
        return listOf(rail[0] to 1 - f, rail[1] to f)
    }

    /** The trolley's weights over the girder's bottom-chord panel points at [x]. */
    fun trolleyWeights(x: Double): List<Pair<Int, Double>> {
        val along = (x - spec.girderX(0)) / spec.girderPanel
        val k = along.toInt().coerceIn(0, spec.girderPanels - 1)
        val f = along - k
        return listOf(girder[k][0] to (1 - f) / 2, girder[k + 1][0] to f / 2, girder[k][1] to (1 - f) / 2, girder[k + 1][1] to f / 2)
    }

    /** The unstretched rope that hangs the hook's weight - at gravity [t]'s ramp - at the
     * hoist's length, so the hook neither drops onto the rope nor is flung up by it. */
    private fun unstretched(t: Double): Double =
        hoist.position / (1 + store.mass(hookNode) * STANDARD_GRAVITY * gravity.factor(t) / ropeAxialStiffness)

    private fun updateCouplings(t: Double) {
        for ((i, wheel) in wheels.withIndex()) {
            val rail = if (i < 2) rigidRail else flexibleRail
            wheel.setTarget(wheelWeights(rail, if (i % 2 == 0) -1 else 1, travel.position))
        }
        trolleyCarriage.setTarget(trolleyWeights(trolley.position))
        truss.setRestLength(hoistRope, unstretched(t))
    }

    /** One step of [dt] from time [t]: the drives move toward their targets, then the physics. */
    fun step(t: Double) {
        if (t >= readyAt) {
            travel.advance(dt)
            trolley.advance(dt)
            // Measured from where the trolley really is, sagged girder included, but smoothed
            // over a few seconds: chasing its every bob would feed the hook's bounce back into
            // the hoist.
            trolleyHeight += (store.position(trolleyNode).y - trolleyHeight) * (dt / TowerCrane.TROLLEY_HEIGHT_SMOOTHING)
            hoist.target = trolleyHeight - hookHeightTarget
            hoist.advance(dt)
        } else {
            trolleyHeight = store.position(trolleyNode).y
        }
        // A load comes on (or off) the hook over a few seconds, the rope taking it up.
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

    /** When the crane has settled under its own weight and the drives start answering. */
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

    /** What the rails hold each leg up with, N: the sum of its wheels' upward pull. */
    val rigidLegReaction: Double get() = wheels.take(2).sumOf { it.force(store).y }
    val flexibleLegReaction: Double get() = wheels.drop(2).sumOf { it.force(store).y }
    /** What the rails push each leg with along the span, N (+x). */
    val rigidLegThrust: Double get() = wheels.take(2).sumOf { it.force(store).x }
    val flexibleLegThrust: Double get() = wheels.drop(2).sumOf { it.force(store).x }

    /** Where the gantry and the trolley actually are. */
    val travelPosition: Double get() = feet.sumOf { store.position(it).z } / 4
    val trolleyPosition: Double get() = store.position(trolleyNode).x

    companion object {
        const val GANTRY_GROUP = "gantry"
        const val TROLLEY_GROUP = "trolley"
        const val HOOK_GROUP = "hook"
        const val RAILS_GROUP = "rails"
        const val MIN_ROPE = 1.0
        /** N/m: each wheel bogie on its rail - a few millimetres under the gantry's weight. */
        const val WHEEL_STIFFNESS = 5e8
    }
}
