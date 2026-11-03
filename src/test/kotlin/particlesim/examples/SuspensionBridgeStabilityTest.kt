package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §13.1/§13.5 for the rope bridge [buildSuspensionBridge] builds. */
class SuspensionBridgeStabilityTest {

    private fun run(s: SuspensionBridgeScenario, seconds: Double, launchAt: Double? = null, onSample: () -> Unit = {}) {
        val integrator = Integrator()
        val dt = SUSPENSION_BRIDGE_DT
        var t = 0.0
        val launchStep = launchAt?.let { (it / dt).toInt() }
        for (i in 0 until (seconds / dt).toInt()) {
            if (launchStep != null && i < launchStep) {
                s.store.setPosition(s.loadId, s.loadStart)
                s.store.setVelocity(s.loadId, Vector3.ZERO)
            } else if (i == launchStep) {
                s.store.setVelocity(s.loadId, s.loadStartVelocity)
            }
            integrator.step(s.store, s.groups, s.forces, s.constraints, t, dt)
            s.collisions.resolve(s.store, s.groups, t, dt)
            t += dt
            if (i % 100 == 0) onSample()
        }
    }

    @Test
    fun `the rope bridge holds its hanging shape through a load crossing`() {
        val s = buildSuspensionBridge()
        val ids = s.walkway.flatten() + s.handrails.flatten()
        val start = ids.associateWith { s.store.position(it) }
        var maxDisplacement = 0.0
        var maxSpeed = 0.0
        run(s, seconds = 10.0, launchAt = 1.0) {
            for (id in ids) {
                maxDisplacement = maxOf(maxDisplacement, (s.store.position(id) - start.getValue(id)).length())
                maxSpeed = maxOf(maxSpeed, s.store.velocity(id).length())
            }
        }
        assertTrue(maxSpeed < 20.0, "max rope/plank speed $maxSpeed suggests instability at dt=$SUSPENSION_BRIDGE_DT")
        // A rope bridge is meant to give under a load, but it's built in its hanging shape, so
        // it should stay near it rather than dropping or flailing.
        assertTrue(maxDisplacement < 0.4, "rope/plank particle moved $maxDisplacement from its built position")
    }

    @Test
    fun `the load stays on the bridge`() {
        val s = buildSuspensionBridge()
        var lowest = Double.POSITIVE_INFINITY
        var widest = 0.0
        run(s, seconds = 12.0, launchAt = 1.0) {
            val p = s.store.position(s.loadId)
            lowest = minOf(lowest, p.y)
            widest = maxOf(widest, abs(p.x))
        }
        assertTrue(lowest > -1.0, "load fell to y=$lowest - through a gap or off the bridge")
        assertTrue(widest < 0.6, "load reached x=$widest - off the side of a 1.2-wide walkway")
    }

    @Test
    fun `the walkway and handrails sag between the posts`() {
        val s = buildSuspensionBridge(sag = 0.6, railHeight = 1.0)
        run(s, seconds = 2.0)
        val midWalkway = s.store.position(s.walkway[s.walkway.size / 2][0]).y
        val endWalkway = s.store.position(s.walkway[0][0]).y
        assertTrue(midWalkway < endWalkway - 0.4, "walkway mid-span $midWalkway doesn't sag below its end $endWalkway")
        val rail = s.handrails[0]
        val midRail = s.store.position(rail[rail.size / 2]).y
        assertTrue(abs(midRail - midWalkway - 1.0) < 0.15, "handrail ($midRail) isn't about railHeight above the walkway ($midWalkway) at mid-span")
    }

    @Test
    fun `planks are separate with gaps between them`() {
        val s = buildSuspensionBridge(planks = 21)
        assertEquals(42, s.walkway.size, "two rows (near/far edge) per plank")
        assertEquals(2 * 21, s.walkwaySurface.triangles.size, "two triangles per plank, none across the gaps")
        for (tri in s.walkwaySurface.triangles) {
            val plankOf = listOf(tri.a, tri.b, tri.c).map { id -> s.walkway.indexOfFirst { id in it } / 2 }.toSet()
            assertEquals(1, plankOf.size, "triangle $tri spans more than one plank")
        }
    }

    @Test
    fun `every post stays fixed`() {
        val s = buildSuspensionBridge()
        val ids = s.posts.flatten()
        val start = ids.associateWith { s.store.position(it) }
        run(s, seconds = 2.0)
        for (id in ids) assertTrue((s.store.position(id) - start.getValue(id)).length() < 1e-9, "post particle $id moved")
    }
}
