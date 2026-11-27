package particlesim.examples

import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TowerCrane]: a hammerhead crane whose tie bars and mast carry what statics says, driven at
 * real speed. */
class TowerCraneTest {
    private fun runUntil(crane: TowerCrane, from: Double, until: Double, each: (Double) -> Unit = {}): Double {
        var t = from
        while (t < until) { crane.step(t); t += crane.dt; each(t) }
        return t
    }

    private fun settled(crane: TowerCrane): Double = runUntil(crane, 0.0, crane.readyAt + crane.easeSeconds + 1.0)

    private fun weight(crane: TowerCrane, id: Int) = crane.store.mass(id) * STANDARD_GRAVITY

    /** The tension statics gives a tie holding up a girder hinged on the line [hingeA]-[hingeB],
     * from the weights of [carried] about that line. */
    private fun tieTensionFromStatics(crane: TowerCrane, carried: List<Int>, hingeA: Int, hingeB: Int, tie: Int): Double {
        val store = crane.store
        val h = (store.position(hingeA) + store.position(hingeB)) * 0.5
        val axis = (store.position(hingeB) - store.position(hingeA)).normalized()
        fun moment(at: Vector3, force: Vector3) = axis.dot((at - h).cross(force))
        val gravity = carried.sumOf { moment(store.position(it), Vector3(0.0, -weight(crane, it), 0.0)) }
        val m = crane.truss.members[tie]
        val girderEnd = if (m.a == crane.apex) m.b else m.a
        val towardApex = (store.position(crane.apex) - store.position(girderEnd)).normalized()
        return -gravity / moment(store.position(girderEnd), towardApex)
    }

    @Test
    fun `the tie bars carry the tensions statics gives, with the maximum load at its radius`() {
        val spec = TowerCraneSpec()
        val crane = TowerCrane(spec, radius = spec.maxLoadRadius, loadTonnes = spec.maxLoadTonnes)
        settled(crane)
        // The jib with the trolley and the load on it, hinged at the turntable.
        val jib = crane.jibLeft.drop(1) + crane.jibRight.drop(1) + crane.jibTop.filterNotNull() + crane.trolleyNode + crane.hookNode
        val jibTie = tieTensionFromStatics(crane, jib, crane.turntableUpper[1], crane.turntableUpper[2], crane.jibTie)
        assertEquals(jibTie, crane.truss.axialForce(crane.store, crane.jibTie), 0.01 * jibTie)
        // The counter-jib with its winch and ballast.
        val counterJib = crane.counterJibLeft.drop(1) + crane.counterJibRight.drop(1) + crane.counterJibTop.filterNotNull()
        val counterTie = tieTensionFromStatics(crane, counterJib, crane.turntableUpper[3], crane.turntableUpper[0], crane.counterJibTie)
        assertEquals(counterTie, crane.truss.axialForce(crane.store, crane.counterJibTie), 0.01 * counterTie)
        assertTrue(jibTie > 300e3 && counterTie > 300e3, "hundreds of kN: $jibTie, $counterTie")
    }

    @Test
    fun `the mast's bottom panel carries the crane's weight and overturning moment, mostly in its chords, leaning back unloaded and forward loaded`() {
        val spec = TowerCraneSpec()
        for (load in listOf(0.0, spec.maxLoadTonnes)) {
            val crane = TowerCrane(spec, radius = spec.maxLoadRadius, loadTonnes = load)
            settled(crane)
            val store = crane.store
            // Everything above a cut through the bottom panel: its weight and its moment about
            // the mast's axis (the jib is along +x, so the moment is about z).
            val above = store.liveIds().filter { store.position(it).y > spec.mastHeight / spec.mastPanels / 2 }
            val w = above.sumOf { weight(crane, it) }
            val m = above.sumOf { weight(crane, it) * store.position(it).x }
            // What the cut members hold it up with: each pulls its upper end toward its lower one.
            val base = crane.mast[0].toSet()
            val cut = (0 until crane.truss.size).filter { val mm = crane.truss.members[it]; (mm.a in base) != (mm.b in base) }
            var lift = 0.0; var moment = 0.0; var chordMoment = 0.0
            for (i in cut) {
                val mm = crane.truss.members[i]
                val (lower, upper) = if (mm.a in base) mm.a to mm.b else mm.b to mm.a
                val pull = (store.position(lower) - store.position(upper)).normalized() * crane.truss.axialForce(store, i)
                val at = store.position(upper)
                lift += pull.y
                moment += at.x * pull.y - at.y * pull.x
                if (i in crane.mastBaseChords) chordMoment += at.x * pull.y - at.y * pull.x
            }
            assertEquals(w, lift, 0.01 * w, "load $load t: holds up the weight")
            // 2%: the hook still swings a little from settling, adding a sideways pull of its own.
            assertEquals(m, moment, 0.02 * abs(m) + 1e3, "load $load t: resists the overturning moment")
            assertTrue(chordMoment / moment in 0.9..1.1, "load $load t: the chords carry the moment (${chordMoment / moment})")
            // Balanced like a real crane: unloaded the back chords (x < 0) are the more compressed,
            // loaded the front.
            val chords = crane.mastBaseChords.map { crane.truss.axialForce(store, it) }
            val back = chords[0] + chords[3]
            val front = chords[1] + chords[2]
            if (load == 0.0) assertTrue(back < front && m < 0.0, "leans back unloaded: $chords") else assertTrue(front < back && m > 0.0, "leans forward loaded: $chords")
        }
    }

    @Test
    fun `at the load chart's limits the members work hard but stay well below yield`() {
        val spec = TowerCraneSpec()
        for ((radius, load) in listOf(spec.maxLoadRadius to spec.maxLoadTonnes, spec.maxRadius to spec.tipLoadTonnes)) {
            val crane = TowerCrane(spec, radius = radius, loadTonnes = load)
            settled(crane)
            val peak = (0 until crane.truss.size).filter { crane.parts[it] != TowerCrane.Part.ROPE }.maxOf { abs(crane.truss.stress(crane.store, it)) }
            assertTrue(peak in 90e6..200e6, "peak ${peak / 1e6} MPa with $load t at $radius m")
        }
    }

    @Test
    fun `the load swings with a pendulum's period`() {
        // The bare hook: a heavy load moves the crane it hangs from enough to shorten the period
        // a few percent (a pendulum on a support that gives).
        val crane = TowerCrane(radius = 20.0, hookHeight = 20.0, loadTonnes = 0.0)
        var t = settled(crane)
        val length = (crane.store.position(crane.trolleyNode) - crane.store.position(crane.hookNode)).length()
        // A sideways push, across the jib.
        crane.store.setVelocity(crane.hookNode, Vector3(0.0, 0.0, 0.3))
        val crossings = ArrayList<Double>()
        var last = crane.store.position(crane.hookNode).z - crane.store.position(crane.trolleyNode).z
        t = runUntil(crane, t, t + 30.0) { now ->
            val z = crane.store.position(crane.hookNode).z - crane.store.position(crane.trolleyNode).z
            if (last < 0.0 && z >= 0.0) crossings += now
            last = z
        }
        val periods = crossings.zipWithNext { a, b -> b - a }
        assertTrue(periods.size >= 2, "swung: $crossings")
        assertEquals(2 * PI * sqrt(length / STANDARD_GRAVITY), periods.average(), 0.02 * periods.average())
    }

    @Test
    fun `slewing turns the whole top, and a stopped slew leaves the load swinging`() {
        val crane = TowerCrane(radius = 30.0, hookHeight = 15.0, loadTonnes = 3.0)
        var t = settled(crane)
        crane.slew.target = 60.0
        var peakRate = 0.0
        t = runUntil(crane, t, t + 25.0) { peakRate = maxOf(peakRate, abs(crane.slew.velocity)) }
        assertEquals(60.0, crane.slewDegrees, 1e-9, "arrived")
        assertEquals(crane.spec.slewSpeedDegrees, peakRate, 1e-6, "at full slewing speed on the way")
        // The turntable has turned 60 degrees on the mast top (positive slew turns +x toward -z);
        // the mast itself twists a little under the slewing torque, so the jib's tip whips around
        // that.
        fun angle(a: Vector3, b: Vector3) = atan2(b.z - a.z, b.x - a.x) * 180 / PI
        val store = crane.store
        val turntable = angle(store.position(crane.turntableLower[0]), store.position(crane.turntableLower[1]))
        val mastTop = angle(store.position(crane.mastTop[0]), store.position(crane.mastTop[1]))
        assertEquals(-60.0, turntable - mastTop, 0.2, "turned on the slewing ring")
        val tip = store.position(crane.jibTop.last()!!)
        assertEquals(-60.0, atan2(tip.z, tip.x) * 180 / PI, 3.0, "the tip, give or take the mast's twist")
        // The load lagged behind on the way and swings after the stop.
        var maxOffset = 0.0
        runUntil(crane, t, t + 10.0) {
            val trolley = crane.store.position(crane.trolleyNode)
            val hook = crane.store.position(crane.hookNode)
            maxOffset = maxOf(maxOffset, sqrt((hook.x - trolley.x) * (hook.x - trolley.x) + (hook.z - trolley.z) * (hook.z - trolley.z)))
        }
        assertTrue(maxOffset > 0.1, "swinging: $maxOffset m")
    }

    @Test
    fun `the trolley and hoist go where they're told, and the load's weight comes on the tie`() {
        val crane = TowerCrane(radius = 15.0, hookHeight = 20.0)
        var t = settled(crane)
        val unloadedTie = crane.truss.axialForce(crane.store, crane.jibTie)
        crane.trolley.target = 25.0
        crane.hookHeightTarget = 10.0
        crane.loadTonnes = 4.0
        t = runUntil(crane, t, t + 40.0)
        assertEquals(25.0, crane.radius, 1e-9)
        // Riding the jib at 25 m from the mast's axis (the loaded mast leans the whole jib forward).
        val onJib = crane.trolleyWeights(25.0).fold(Vector3.ZERO) { s, (id, w) -> s + crane.store.position(id) * w }
        assertTrue((crane.store.position(crane.trolleyNode) - onJib).length() < 0.01)
        assertEquals(10.0, crane.store.position(crane.hookNode).y, 0.1, "hook at the asked-for height")
        assertEquals(crane.spec.hookBlockMass + 4000.0, crane.store.mass(crane.hookNode))
        assertTrue(crane.truss.axialForce(crane.store, crane.jibTie) > unloadedTie + 50e3, "heavier on the tie")
    }

    @Test
    fun `the targets stay in range, and a load can't be negative`() {
        val crane = TowerCrane()
        crane.trolley.target = 500.0
        assertEquals(crane.spec.maxRadius, crane.trolley.target)
        crane.hookHeightTarget = -5.0
        assertEquals(crane.spec.minHookHeight, crane.hookHeightTarget)
        assertTrue(runCatching { crane.loadTonnes = -1.0 }.isFailure)
        assertTrue(runCatching { crane.loadTonnes = Double.NaN }.isFailure)
    }

    @Test
    fun `a drive speeds up and slows down at its acceleration, never past its top speed, and lands on the target`() {
        val drive = Drive(0.0, maxSpeed = 2.0, acceleration = 0.5)
        drive.target = 10.0
        var t = 0.0
        var lastV = 0.0
        var peak = 0.0
        while (drive.moving && t < 60.0) {
            drive.advance(0.01)
            val landed = drive.velocity == 0.0 && drive.position == 10.0
            assertTrue(landed || abs(drive.velocity - lastV) <= 0.5 * 0.01 + 1e-12, "acceleration limited")
            assertTrue(drive.position <= 10.0 + 1e-12, "no overshoot")
            peak = maxOf(peak, drive.velocity)
            lastV = drive.velocity
            t += 0.01
        }
        assertEquals(10.0, drive.position)
        assertEquals(2.0, peak, 1e-12)
        // Accelerate 4 s, cruise, then slow down - planned at 0.9 of its deceleration: about 9.2 s.
        assertEquals(9.2, t, 0.1)
        val limited = Drive(5.0, 1.0, 1.0, min = 0.0, max = 8.0)
        limited.target = 20.0
        assertEquals(8.0, limited.target)
    }

    @Test
    fun `the slewing ring's weights put each turntable corner at its rotated place on the mast top`() {
        val crane = TowerCrane()
        val store = crane.store
        for (degrees in listOf(0.0, 30.0, 45.0, 90.0, 200.0)) for (c in 0 until 4) {
            val weights = crane.ringWeights(c, degrees)
            assertEquals(1.0, weights.sumOf { it.second }, 1e-12)
            val target = weights.fold(Vector3.ZERO) { s, (id, w) -> s + store.position(id) * w }
            val p = store.position(crane.turntableLower[c])
            val theta = degrees * PI / 180
            val expected = Vector3(p.x * kotlin.math.cos(theta) + p.z * kotlin.math.sin(theta), p.y, -p.x * kotlin.math.sin(theta) + p.z * kotlin.math.cos(theta))
            assertTrue((target - expected).length() < 1e-9, "corner $c at $degrees: $target vs $expected")
        }
        // The trolley's weights move smoothly through a panel point.
        val atPanel = crane.spec.halfWidth + 5 * crane.spec.jibPanel
        fun trolleyX(r: Double) = crane.trolleyWeights(r).sumOf { (id, w) -> store.position(id).x * w }
        for (r in listOf(atPanel - 1e-9, atPanel, atPanel + 1e-9, 20.0)) assertEquals(r, trolleyX(r), 1e-8)
    }

    @Test
    fun `gravity ramps on smoothly and weighs each particle's current mass`() {
        val store = particlesim.core.ParticleStore()
        val groups = particlesim.core.Groups()
        val id = store.create(mass = particlesim.core.ScalarExpr.of(10.0))
        groups.add("load", id)
        val gravity = RampedGravity(listOf("load"), groups, rampSeconds = 2.0)
        assertEquals(0.0, gravity.factor(0.0))
        assertEquals(0.5, gravity.factor(1.0), 1e-12)
        assertEquals(1.0, gravity.factor(5.0))
        fun force(t: Double): Vector3 {
            val chunk = particlesim.physics.ChunkAccumulator(store.capacity)
            gravity.accumulate(store, groups, t, chunk, 0, 1)
            return chunk.at(store.slotOf(id))
        }
        assertEquals(-10.0 * STANDARD_GRAVITY / 2, force(1.0).y, 1e-9)
        store.setMass(id, particlesim.core.ScalarExpr.of(30.0), 3.0)
        assertEquals(Vector3(0.0, -30.0 * STANDARD_GRAVITY, 0.0), force(3.0))
        groups.setEnabled("load", false)
        assertEquals(Vector3.ZERO, force(3.0), "a disabled group has no weight")
    }

    @Test
    fun `structural damping leaves a rigid slew alone and damps everything else`() {
        val store = particlesim.core.ParticleStore()
        val omega = 0.1
        // On the slewing part, moving exactly as a rigid turn about the y axis would carry it.
        val turning = store.create(position = Vector3(10.0, 40.0, 2.0), velocity = Vector3(omega * 2.0, 0.0, -omega * 10.0), mass = particlesim.core.ScalarExpr.of(5.0))
        // ...and one swaying on top of that.
        val swaying = store.create(position = Vector3(-5.0, 40.0, 0.0), velocity = Vector3(0.0, 0.3, omega * 5.0 + 0.2), mass = particlesim.core.ScalarExpr.of(2.0))
        val mast = store.create(position = Vector3(0.8, 10.0, 0.8), velocity = Vector3(0.1, 0.0, 0.0), mass = particlesim.core.ScalarExpr.of(4.0))
        val groups = particlesim.core.Groups()
        groups.add("top", turning); groups.add("top", swaying); groups.add("mast", mast)
        val damping = SlewFrameDamping("top", "mast", groups, { omega }, coefficient = 0.5)
        val chunk = particlesim.physics.ChunkAccumulator(store.capacity)
        damping.accumulate(store, groups, 0.0, chunk, 0, 1)
        assertTrue(chunk.at(store.slotOf(turning)).length() < 1e-12, "a rigid slew isn't damped")
        val sway = chunk.at(store.slotOf(swaying))
        assertEquals(-0.5 * 2.0 * 0.3, sway.y, 1e-12)
        assertEquals(-0.5 * 2.0 * 0.2, sway.z, 1e-12)
        assertEquals(Vector3(-0.5 * 4.0 * 0.1, 0.0, 0.0), chunk.at(store.slotOf(mast)))
        groups.setEnabled("mast", false)
        val off = particlesim.physics.ChunkAccumulator(store.capacity)
        damping.accumulate(store, groups, 0.0, off, 0, 1)
        assertEquals(Vector3.ZERO, off.at(store.slotOf(mast)), "a disabled group isn't damped")
        assertEquals(sway, off.at(store.slotOf(swaying)))
    }

    @Test
    fun `structural damping with a frame velocity leaves the frame's travel alone and damps movement about it`() {
        val store = particlesim.core.ParticleStore()
        val frame = Vector3(0.0, 0.0, 0.6)
        // Travelling with the frame...
        val carried = store.create(position = Vector3(10.0, 16.0, 2.0), velocity = frame, mass = particlesim.core.ScalarExpr.of(5.0))
        // ...and swaying about it.
        val swaying = store.create(position = Vector3(-5.0, 16.0, 0.0), velocity = frame + Vector3(0.1, -0.2, 0.3), mass = particlesim.core.ScalarExpr.of(2.0))
        val anchor = store.create(position = Vector3(0.0, 0.0, 45.0), velocity = Vector3(0.0, 0.0, 0.4), mass = particlesim.core.ScalarExpr.of(1.0))
        val groups = particlesim.core.Groups()
        groups.add("gantry", carried); groups.add("gantry", swaying); groups.add("rails", anchor)
        val damping = SlewFrameDamping("gantry", "rails", groups, { 0.0 }, coefficient = 0.5, frameVelocity = { frame })
        val chunk = particlesim.physics.ChunkAccumulator(store.capacity)
        damping.accumulate(store, groups, 0.0, chunk, 0, 1)
        assertTrue(chunk.at(store.slotOf(carried)).length() < 1e-12, "travelling with the frame isn't damped")
        val sway = chunk.at(store.slotOf(swaying))
        assertTrue((sway - Vector3(-0.5 * 2.0 * 0.1, 0.5 * 2.0 * 0.2, -0.5 * 2.0 * 0.3)).length() < 1e-12, "$sway")
        assertEquals(Vector3(0.0, 0.0, -0.5 * 1.0 * 0.4), chunk.at(store.slotOf(anchor)), "the fixed group stays put: the frame doesn't carry it")
    }

    private fun anythingFailed(crane: TowerCrane) =
        (0 until crane.truss.size).any { crane.truss.hasBuckled(it) || crane.truss.yieldStrain(it) > 0.0 || crane.truss.isTorn(it) }

    /** Lifts [tonnes] at [radius] - the load coming on from nothing once the crane's ready, the
     * way a lift does - and runs [seconds] on. Returns how far the jib's tip dropped. */
    private fun lift(crane: TowerCrane, radius: Double, tonnes: Double, seconds: Double): Double {
        var t = runUntil(crane, 0.0, crane.readyAt + 1.0)
        val tip = crane.store.position(crane.jibTop.last()!!).y
        crane.loadTonnes = tonnes
        runUntil(crane, t, t + seconds)
        return tip - crane.store.position(crane.jibTop.last()!!).y
    }

    @Test
    fun `with failure on, the crane holds its load chart's limits as the load comes on`() {
        val spec = TowerCraneSpec()
        for ((radius, tonnes) in listOf(spec.maxLoadRadius to spec.maxLoadTonnes, spec.maxRadius to spec.tipLoadTonnes)) {
            val crane = TowerCrane(spec, radius = radius)
            crane.failure = true
            val drop = lift(crane, radius, tonnes, 15.0)
            assertTrue(!anythingFailed(crane), "$tonnes t at $radius m: nothing yields, buckles or tears")
            assertTrue(drop < 3.0, "$tonnes t at $radius m: just bends, $drop m")
        }
    }

    @Test
    fun `with failure on, twice the load chart brings it down, and with failure off it only bends`() {
        val spec = TowerCraneSpec()
        val tonnes = 2 * spec.maxLoadTonnes
        val failing = TowerCrane(spec, radius = spec.maxLoadRadius).apply { failure = true }
        val drop = lift(failing, spec.maxLoadRadius, tonnes, 20.0)
        assertTrue(anythingFailed(failing))
        assertTrue(drop > 10.0, "collapsed: the tip fell $drop m")
        // It lands on the ground rather than falling through it.
        assertTrue(failing.store.liveIds().all { failing.store.position(it).y > -0.5 })

        val elastic = TowerCrane(spec, radius = spec.maxLoadRadius)
        val bent = lift(elastic, spec.maxLoadRadius, tonnes, 20.0)
        assertTrue(!anythingFailed(elastic))
        assertTrue(bent < 3.0, "only bends: $bent m")
    }

    @Test
    fun `a load comes onto the hook over a couple of seconds, not all at once`() {
        val crane = TowerCrane(radius = 15.0)
        var t = runUntil(crane, 0.0, crane.readyAt)
        crane.loadTonnes = 8.0
        t = runUntil(crane, t, t + 1.0)
        val halfway = crane.store.mass(crane.hookNode)
        assertEquals(crane.spec.hookBlockMass + TowerCrane.LOAD_RAMP_RATE * 1.0, halfway, 50.0)
        runUntil(crane, t, t + 2.0)
        assertEquals(crane.spec.hookBlockMass + 8000.0, crane.store.mass(crane.hookNode))
    }
}
