package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.FieldValue
import particlesim.physics.Integrator
import particlesim.physics.UniformGravity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The 1950 bridge's breaking limits ([TacomaBreakage] with trusses) and its pinned bearings. */
class TacomaBreakage1950Test {
    private val events = ArrayList<String>()
    private var forces: MutableList<particlesim.physics.Force>? = null

    /** Steps [s], dropping broken forces from the step list as a scene does. */
    private fun step(s: TacomaNarrows1950Scenario, integrator: Integrator, from: Double, seconds: Double, each: (Double) -> Unit = {}): Double {
        val forces = forces ?: s.forces.toMutableList().also { forces = it }
        var t = from
        repeat((seconds / TACOMA_DT).toInt()) {
            each(t)
            val result = integrator.step(s.store, s.groups, forces, s.constraints, t, TACOMA_DT)
            forces.removeAll(result.brokenForces.toSet())
            events += s.breakage.afterStep(result.brokenForces)
            t += TACOMA_DT
        }
        return t
    }

    private fun setGravity(s: TacomaNarrows1950Scenario, g: Double) {
        for (f in s.forces) if (f is UniformGravity) f.setField("acceleration", FieldValue.Vector(Vector3(0.0, -g, 0.0)))
    }

    @Test
    fun `the trusses are pinned at their bearings, not clamped`() {
        val s = buildTacomaNarrows1950()
        val held = s.groups.membersOf("deck-supports").toSet()
        for (span in s.spans) for (row in listOf(span.first(), span.last())) {
            assertEquals(setOf(row.bottomLeft, row.bottomRight), row.all.filter { it in held }.toSet())
        }
    }

    @Test
    fun `pinned bearings keep the main span's chords beside the towers in range as gravity doubles`() {
        // Clamped (the whole end row held), the chords in the main-span bays next to the towers
        // reached ~5x their limit here, while mid-span ones sat at ~0.2. (The short side spans
        // bend more sharply for the same sag and do reach their limits at 2g - under overload
        // they're the weak point.)
        val s = buildTacomaNarrows1950(windVelocity = "[0, 0, 0]", flutter = false)
        val integrator = Integrator()
        step(s, integrator, 0.0, 25.0) { t -> setGravity(s, 9.8 * (1.0 + ((t - 10.0) / 10.0).coerceIn(0.0, 1.0))) }
        val mainSpanIds = s.mainSpan.flatMap { it.all }.toSet()
        val chords = s.trussChords.filter { it.first in mainSpanIds }.toSet()
        val worst = s.utilization().filterKeys { it in chords }.values.max()
        assertTrue(worst < 1.0, "worst main-span chord at 2g: $worst")
    }

    @Test
    fun `the diagonals are rated, the verticals aren't`() {
        val s = buildTacomaNarrows1950()
        val u = s.utilization()
        assertTrue(s.trussDiagonals.all { it in u })
        val diagonals = s.trussDiagonals.toSet()
        assertTrue(s.trussWebs.filter { it !in diagonals }.none { it in u })
        // Lightly loaded at rest - well under a tenth of the limit.
        assertTrue(s.trussDiagonals.all { u.getValue(it) < 0.1 })
    }

    @Test
    fun `arming puts the steel strain limits on the chords and diagonals`() {
        val s = buildTacomaNarrows1950()
        s.breakage.arm()
        for (members in listOf(s.chords, s.diagonals)) {
            assertEquals(FieldValue.Scalar(TacomaBreakage.GIRDER_ULTIMATE_STRAIN), members.editableFields()["extensionBreakThreshold"])
            assertEquals(FieldValue.Scalar(TacomaBreakage.GIRDER_BUCKLING_STRAIN), members.editableFields()["compressionBreakThreshold"])
        }
    }

    @Test
    fun `a broken diagonal fails its truss, and a bay with both trusses failed tears apart`() {
        val s = buildTacomaNarrows1950()
        val (a, b) = s.mainSpan[20] to s.mainSpan[21]
        val (left, right) = s.trussDiagonals.filter { (x, y) -> setOf(x, y) == setOf(a.topLeft, b.bottomLeft) || setOf(x, y) == setOf(a.bottomLeft, b.topLeft) }.single() to
            s.trussDiagonals.filter { (x, y) -> setOf(x, y) == setOf(a.topRight, b.bottomRight) || setOf(x, y) == setOf(a.bottomRight, b.topRight) }.single()
        s.diagonals.breakEdge(s.diagonals.edgeIndexOf(left.first, left.second))
        assertEquals(listOf("left truss failed in main span bay 20"), s.breakage.afterStep(emptyList()))
        s.diagonals.breakEdge(s.diagonals.edgeIndexOf(right.first, right.second))
        assertEquals(listOf("right truss failed in main span bay 20", "deck tore apart in main span bay 20"), s.breakage.afterStep(emptyList()))
        // Its roadway and both truss faces (4 + 4 triangles) go; its chords stop being rated.
        assertEquals(8, s.breakage.hiddenTriangles.size)
        assertFalse(s.chords.isActive(s.chords.edgeIndexOf(a.bottomLeft, b.bottomLeft)))
        assertTrue((a.topLeft to b.topLeft) !in s.utilization())
    }

    @Test
    fun `armed, the bridge stands in the failure wind for a minute`() {
        val s = buildTacomaNarrows1950(windVelocity = "[0, 0, 0]", breakable = true)
        val integrator = Integrator()
        var t = step(s, integrator, 0.0, 8.0)
        s.wind.setVelocity(particlesim.expr.ExpressionParser.parseVector("[$TACOMA_FAILURE_WIND, 0, 0]"))
        s.seedOneNodeTwist(TACOMA_SCENE_SEED_RATE)
        t = step(s, integrator, t, 5.0)
        s.breakage.arm()
        step(s, integrator, t, 60.0)
        assertEquals(0, s.breakage.brokenHangerCount + s.breakage.brokenCableCount + s.breakage.failedGirderCount)
        assertTrue(s.utilization().values.max() < 0.7)
    }

    @Test
    fun `armed, four times gravity breaks it, and the pieces land in the water`() {
        val s = buildTacomaNarrows1950(windVelocity = "[0, 0, 0]", breakable = true)
        val integrator = Integrator()
        val t = step(s, integrator, 0.0, 8.0)
        s.breakage.arm()
        setGravity(s, 4 * 9.8)
        val collisions = assertNotNull(s.collisions)
        step(s, integrator, t, 15.0) { collisions.resolve(s.store, s.groups, it, TACOMA_DT) }
        assertTrue(s.breakage.brokenHangerCount + s.breakage.brokenCableCount + s.breakage.failedGirderCount > 0)
        val waterY = -TACOMA_1950_DECK_HEIGHT_ABOVE_WATER
        val pieces = s.groups.membersOf("deck") + s.groups.membersOf("cables")
        assertTrue(pieces.all { s.store.position(it).y > waterY - 0.05 }, "nothing sinks below the water")
        assertTrue(pieces.any { s.store.position(it).y < waterY + 0.05 }, "something has fallen to the water; events $events")
    }
}
