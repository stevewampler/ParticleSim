package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import particlesim.physics.MeshSprings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The 1940 Tacoma Narrows model's real breaking limits ([TacomaBreakage]). */
class TacomaBreakageTest {
    private fun chords(s: TacomaNarrowsScenario) = s.forces.filterIsInstance<MeshSprings>().single { it.name == "girder-chords" }
    private fun deck(s: TacomaNarrowsScenario) = s.forces.filterIsInstance<MeshSprings>().single { it.name == "deck-structure" }

    @Test
    fun `the limits are the 1941 report's figures`() {
        assertEquals(6.24, TacomaBreakage.HANGER_ULTIMATE_TO_DEAD, 0.01) // 4 x 165,400 / 106,000 lb
        assertEquals(3.47, TacomaBreakage.CABLE_ULTIMATE_TO_DEAD, 0.01) // 235,000 / 67,700 psi
        assertEquals(0.00276, TacomaBreakage.GIRDER_ULTIMATE_STRAIN, 0.00001) // 80,000 psi / E
        assertEquals(0.00138, TacomaBreakage.GIRDER_BUCKLING_STRAIN, 0.00001) // 40,000 psi / E
    }

    @Test
    fun `a hanger breaks past its ultimate tension, and only once armed`() {
        val s = buildTacomaNarrows(breakable = true)
        val hanger = s.breakage.hangers[10]
        val (cableId, deckId) = hanger.connection
        // Stretch it to 7x its dead-load stretch (past the 6.24x limit).
        val p = s.store.position(deckId)
        s.store.setPosition(deckId, p - Vector3(0.0, 6.0 * hanger.deadLoadStretch, 0.0))
        assertFalse(hanger.spring.shouldBreak(s.store), "unarmed limits should be infinite")
        s.breakage.arm()
        assertTrue(hanger.spring.shouldBreak(s.store))
        s.store.setPosition(deckId, p - Vector3(0.0, 4.0 * hanger.deadLoadStretch, 0.0)) // 5x: holds
        assertFalse(hanger.spring.shouldBreak(s.store))
        assertTrue(cableId != deckId)
    }

    @Test
    fun `a bay tears apart only when both its girders have failed`() {
        val s = buildTacomaNarrows(breakable = true)
        val main = s.mainSpan
        val bay = 20
        val a = main[bay]
        val b = main[bay + 1]
        val chords = chords(s)
        val deck = deck(s)
        val centreEdge = deck.edgeIndexOf(a.centre, b.centre)

        chords.breakEdge(chords.edgeIndexOf(a.topLeft, b.topLeft))
        val first = s.breakage.afterStep(emptyList())
        assertEquals(listOf("left girder failed in main span bay $bay"), first)
        assertTrue(deck.isActive(centreEdge), "one buckled girder shouldn't separate the deck")

        chords.breakEdge(chords.edgeIndexOf(a.bottomRight, b.bottomRight))
        val second = s.breakage.afterStep(emptyList())
        assertEquals(listOf("right girder failed in main span bay $bay", "deck tore apart in main span bay $bay"), second)
        assertFalse(deck.isActive(centreEdge))
        assertFalse(chords.isActive(chords.edgeIndexOf(a.topRight, b.topRight)), "the rest of the bay's chords go too")
        assertEquals(8, s.breakage.hiddenTriangles.size, "4 roadway + 4 girder-web panels")
        // The torn-loose rows stop being driven by flutter.
        val flutter = s.flutter!!
        for (row in listOf(bay, bay + 1)) {
            s.store.setVelocity(main[row].topRight, Vector3(0.0, 1.0, 0.0))
            assertEquals(0.0, flutter.moments(s.store, 0.0)[row - 1], 0.0)
        }
        assertTrue(s.breakage.afterStep(emptyList()).isEmpty(), "nothing new to report")
    }

    @Test
    fun `a bay next to a support can't fail - the rigid pin there is an artifact`() {
        val s = buildTacomaNarrows(breakable = true)
        val chords = chords(s)
        val a = s.mainSpan[0]
        val b = s.mainSpan[1]
        chords.breakEdge(chords.edgeIndexOf(a.topLeft, b.topLeft))
        chords.breakEdge(chords.edgeIndexOf(a.topRight, b.topRight))
        assertTrue(s.breakage.afterStep(emptyList()).isEmpty())
    }

    @Test
    fun `once settled and armed, nothing breaks in still air`() {
        val s = buildTacomaNarrows(windVelocity = "[0, 0, 0]", breakable = true)
        val forces = s.forces.toMutableList()
        val integrator = Integrator()
        var t = 0.0
        val events = ArrayList<String>()
        for (i in 0 until (20.0 / TACOMA_DT).toInt()) {
            if (i == (8.0 / TACOMA_DT).toInt()) s.breakage.arm()
            val result = integrator.step(s.store, s.groups, forces, s.constraints, t, TACOMA_DT)
            forces.removeAll(result.brokenForces.toSet())
            events += s.breakage.afterStep(result.brokenForces)
            s.collisions!!.resolve(s.store, s.groups, t, TACOMA_DT)
            t += TACOMA_DT
        }
        assertTrue(events.isEmpty(), "broke in still air: $events")
    }

    @Test
    fun `at the documented 45 degree twist the bridge holds for two minutes`() {
        // The finding this scene records: with the report's limits and the flutter's limit cycle
        // fitted to its "alternating 45° torsion", the model sits right at its limit - in the
        // steady failure wind the first quarter-point girders buckle only after ~4 minutes
        // (measured), and pushing the twist ~12° further brings the whole cascade (girders,
        // hangers, the deck falling away bay by bay). Here: settle, seed, arm 5 s later, then
        // 2 minutes at the failure wind with the twist at ~45° and nothing broken.
        val s = buildTacomaNarrows(windVelocity = "[$TACOMA_FAILURE_WIND, 0, 0]", breakable = true)
        val forces = s.forces.toMutableList()
        val integrator = Integrator()
        s.wind.setVelocity(particlesim.core.VectorExpr.of(Vector3.ZERO))
        var t = 0.0
        repeat((8.0 / TACOMA_DT).toInt()) { integrator.step(s.store, s.groups, forces, s.constraints, t, TACOMA_DT); t += TACOMA_DT }
        s.wind.setVelocity(particlesim.expr.ExpressionParser.parseVector("[$TACOMA_FAILURE_WIND, 0, 0]"))
        s.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)
        t = 0.0
        val q = s.mainSpan[s.mainSpan.size / 4]
        val tq = s.mainSpan[3 * s.mainSpan.size / 4]
        fun tilt(row: DeckRow) = (s.store.position(row.topRight) - s.store.position(row.topLeft)).let { kotlin.math.atan2(it.y, it.x) }
        var peak = 0.0
        val events = ArrayList<String>()
        for (i in 0 until (120.0 / TACOMA_DT).toInt()) {
            if (i == (5.0 / TACOMA_DT).toInt()) s.breakage.arm()
            val result = integrator.step(s.store, s.groups, forces, s.constraints, t, TACOMA_DT)
            forces.removeAll(result.brokenForces.toSet())
            events += s.breakage.afterStep(result.brokenForces)
            s.collisions!!.resolve(s.store, s.groups, t, TACOMA_DT)
            t += TACOMA_DT
            if (t > 60.0 && i % 20 == 0) peak = maxOf(peak, abs(tilt(q) - tilt(tq)) / 2)
        }
        assertTrue(abs(Math.toDegrees(peak) - 45.0) < 5.0, "one-node twist peaked at ${Math.toDegrees(peak)}°, fitted to the report's 45°")
        assertTrue(events.isEmpty(), "broke within 2 minutes at 45°: $events")
    }

    @Test
    fun `utilization is each member's load over its real limit, armed or not`() {
        val s = buildTacomaNarrows()
        val u = s.breakage.utilization(s.store)
        val hanger = s.breakage.hangers[10]
        val cable = s.breakage.cables[30]
        // As built, every cable and hanger carries exactly its dead load.
        assertEquals(1.0 / TacomaBreakage.HANGER_ULTIMATE_TO_DEAD, u.getValue(hanger.connection), 1e-9)
        assertEquals(1.0 / TacomaBreakage.CABLE_ULTIMATE_TO_DEAD, u.getValue(cable.connection), 1e-9)
        val chords = chords(s)
        val main = s.mainSpan
        val chord = main[20].topLeft to main[21].topLeft
        assertEquals(0.0, u.getValue(chord), 1e-9, "chords are built unstressed")
        assertEquals(4 * (s.spans.sumOf { it.size - 1 }) + s.breakage.hangers.size + s.breakage.cables.size, u.size)

        // Squeeze a chord by half the buckling strain, stretch it by half the ultimate strain.
        val p = s.store.position(main[21].topLeft)
        val length = (p - s.store.position(main[20].topLeft)).length()
        s.store.setPosition(main[21].topLeft, p - Vector3(0.0, 0.0, 0.5 * TacomaBreakage.GIRDER_BUCKLING_STRAIN * length))
        assertEquals(0.5, s.breakage.utilization(s.store).getValue(chord), 1e-3)
        s.store.setPosition(main[21].topLeft, p + Vector3(0.0, 0.0, 0.5 * TacomaBreakage.GIRDER_ULTIMATE_STRAIN * length))
        assertEquals(0.5, s.breakage.utilization(s.store).getValue(chord), 1e-3)

        // A broken chord drops out.
        chords.breakEdge(chords.edgeIndexOf(chord.first, chord.second))
        assertTrue(chord !in s.breakage.utilization(s.store))
    }
}
