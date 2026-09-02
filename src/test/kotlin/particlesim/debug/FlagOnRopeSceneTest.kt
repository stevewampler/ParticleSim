package particlesim.debug

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * requirements.md §7.3's "connect the flag to the top portion of the rope" requirement, tested
 * against [buildFlagOnRopeScenario] directly (not through [FlagOnRopeScene]'s narrow [DemoScene]
 * surface) so these can assert on the flag/rope/pole ids the composition wires together.
 */
class FlagOnRopeSceneTest {

    @Test
    fun `each flag pole-edge row starts at the same height as its corresponding rope particle`() {
        val scenario = buildFlagOnRopeScenario()
        for (row in scenario.flagGrid.indices) {
            val flagY = scenario.store.position(scenario.flagGrid[row][0]).y
            val ropeY = scenario.store.position(scenario.rope.ropeIds[row]).y
            assertTrue(
                kotlin.math.abs(flagY - ropeY) < 1e-9,
                "row $row: flag edge y=$flagY should match rope y=$ropeY (per-segment spacing was chosen to match flagSpacing)",
            )
        }
    }

    @Test
    fun `a rope with too few segments for the flag's row count is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            buildFlagOnRopeScenario(flagRows = 8, ropeSegments = 3)
        }
    }

    @Test
    fun `the flag's own pole-anchor constraint is not forwarded - only the pole and rope anchors are`() {
        val scenario = buildFlagOnRopeScenario()
        assertNotEquals(0, scenario.constraints.size)
        val names = scenario.constraints.mapNotNull { it.name }
        assertTrue(names.none { it == "flag.pole-anchor" }, "flag's own pole-anchor constraint should not be forwarded: $names")
        assertTrue(names.any { it.contains("rope-anchor") }, "rope's anchor constraint should still be present: $names")
    }

    @Test
    fun `the flag's pole-edge particles move over time instead of holding a frozen vertical line`() {
        val scenario = buildFlagOnRopeScenario()
        val integrator = Integrator()
        val edgeId = scenario.flagGrid[scenario.flagGrid.size - 1][0] // bottom row, farthest from the rope's own pinned anchors
        val before = scenario.store.position(edgeId)

        var t = 0.0
        val dt = 1e-3
        repeat(2000) { // 2 seconds
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
            t += dt
        }

        assertNotEquals(before, scenario.store.position(edgeId), "a dynamic rope attachment should let this particle move, unlike the old FixedPosition pin")
    }

    @Test
    fun `flag-on-rope scenario runs for several seconds without blowing up`() {
        val scenario = buildFlagOnRopeScenario()
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val dt = 1e-3
        val steps = (4.0 / dt).toInt() // 4 seconds of sim time

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
            t += dt
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the flag+rope+pole assembly is unstable")
    }

    @Test
    fun `a rope particle pushed through the flag surface is pushed back out on the next resolve`() {
        val scenario = buildFlagOnRopeScenario()
        // Pick an interior rope particle (not an anchor) far enough down the rope that it isn't
        // one of the rows already wired to the flag by an attachment spring, so this contact is
        // purely the collision system's own doing, not confounded by spring pull.
        val ropeId = scenario.rope.ropeIds.last()
        val triangle = scenario.flagSurface.triangles.first()
        val surfacePoint = (scenario.store.position(triangle.a) + scenario.store.position(triangle.b) + scenario.store.position(triangle.c)) * (1.0 / 3.0)

        // Shove the rope particle to sit exactly on the surface, well inside its own collision
        // radius - a deliberate, unambiguous penetration rather than a borderline one.
        scenario.store.setPosition(ropeId, surfacePoint)
        val before = scenario.store.position(ropeId)

        scenario.collisions.resolve(scenario.store, scenario.groups, t = 0.0, dt = 1e-3)

        val after = scenario.store.position(ropeId)
        assertNotEquals(before, after, "resolve() should have corrected the rope particle out of the flag surface it was forced into")
    }
}
