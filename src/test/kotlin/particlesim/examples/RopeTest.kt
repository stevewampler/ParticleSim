package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RopeTest {

    @Test
    fun `rope particles are evenly spaced between the two anchors before any stepping`() {
        val top = Vector3(0.0, 3.0, 0.0)
        val bottom = Vector3(0.0, 1.0, 0.0)
        val rope = buildRope(topAnchor = top, bottomAnchor = bottom, segments = 4)
        assertEquals(5, rope.ropeIds.size) // segments + 1 endpoints

        val positions = rope.ropeIds.map { rope.store.position(it) }
        assertEquals(top, positions.first())
        assertEquals(bottom, positions.last())
        assertEquals(Vector3(0.0, 2.0, 0.0), positions[2]) // midpoint
    }

    @Test
    fun `too few segments is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            buildRope(topAnchor = Vector3(0.0, 3.0, 0.0), bottomAnchor = Vector3(0.0, 1.0, 0.0), segments = 0)
        }
    }

    @Test
    fun `the placement offset applies to both anchors and every particle in between`() {
        val top = Vector3(0.0, 3.0, 0.0)
        val bottom = Vector3(0.0, 1.0, 0.0)
        val offset = Vector3(5.0, 0.0, -2.0)
        val plain = buildRope(topAnchor = top, bottomAnchor = bottom, segments = 4)
        val offsetRope = buildRope(topAnchor = top, bottomAnchor = bottom, segments = 4, placement = ShapePlacement(offset = offset))

        plain.ropeIds.zip(offsetRope.ropeIds).forEach { (a, b) ->
            assertEquals(plain.store.position(a) + offset, offsetRope.store.position(b))
        }
    }

    @Test
    fun `both anchor particles never move under stepping, even under gravity`() {
        val rope = buildRope(topAnchor = Vector3(0.0, 3.0, 0.0), bottomAnchor = Vector3(0.5, 1.0, 0.0), segments = 6)
        val topId = rope.ropeIds.first()
        val bottomId = rope.ropeIds.last()
        val topBefore = rope.store.position(topId)
        val bottomBefore = rope.store.position(bottomId)

        val integrator = Integrator()
        var t = 0.0
        repeat(2000) {
            integrator.step(rope.store, rope.groups, rope.forces, rope.constraints, t, 1e-3)
            t += 1e-3
        }

        assertEquals(topBefore, rope.store.position(topId))
        assertEquals(bottomBefore, rope.store.position(bottomId))
    }

    @Test
    fun `left slack, the rope sags below the straight line between its anchors and settles without blowing up`() {
        val top = Vector3(0.0, 3.0, 0.0)
        val bottom = Vector3(0.5, 1.0, 0.0)
        val rope = buildRope(topAnchor = top, bottomAnchor = bottom, segments = 8)
        val midId = rope.ropeIds[rope.ropeIds.size / 2]
        val straightLineMidY = (top.y + bottom.y) / 2.0

        val integrator = Integrator()
        var t = 0.0
        val dt = 1e-3
        repeat(4000) { // 4 seconds - long enough to sag and settle
            integrator.step(rope.store, rope.groups, rope.forces, rope.constraints, t, dt)
            t += dt
        }

        val finalPositions = rope.ropeIds.map { rope.store.position(it) }
        finalPositions.forEach { p -> assertTrue(p.isFinite(), "particle position should stay finite: $p") }
        assertTrue(
            rope.store.position(midId).y < straightLineMidY,
            "a slack rope's midpoint should sag below the straight line between its anchors",
        )
    }

    @Test
    fun `an instance name namespaces the rope group`() {
        val rope = buildRope(
            topAnchor = Vector3(0.0, 3.0, 0.0),
            bottomAnchor = Vector3(0.0, 1.0, 0.0),
            segments = 4,
            placement = ShapePlacement(instanceName = "rope1"),
        )
        // An interior particle (not one of the two anchors, which also carry "rope1.rope-anchors").
        val interiorId = rope.ropeIds[2]
        assertEquals(setOf("rope1.rope"), rope.groups.groupsOf(interiorId))
    }

    @Test
    fun `each anchor particle belongs to both the rope group and the anchors group`() {
        val rope = buildRope(
            topAnchor = Vector3(0.0, 3.0, 0.0),
            bottomAnchor = Vector3(0.0, 1.0, 0.0),
            segments = 4,
            placement = ShapePlacement(instanceName = "rope1"),
        )
        assertEquals(setOf("rope1.rope", "rope1.rope-anchors"), rope.groups.groupsOf(rope.ropeIds.first()))
        assertEquals(setOf("rope1.rope", "rope1.rope-anchors"), rope.groups.groupsOf(rope.ropeIds.last()))
    }
}
