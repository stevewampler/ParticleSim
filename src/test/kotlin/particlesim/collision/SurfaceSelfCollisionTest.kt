package particlesim.collision

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.surface.Grid
import particlesim.surface.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [SurfaceSelfCollisionSystem] covers §12.4's "Surface self-collision," proven directly here
 * (not just via the flag worked example) so the exclusion-topology mechanism itself is pinned
 * down independent of any one mesh's tuning.
 */
class SurfaceSelfCollisionTest {

    /** A flat 4x4 grid, spacing 0.2 - every non-adjacent vertex-to-triangle distance on a flat
     * sheet is well over any reasonable [SurfaceSelfCollisionRule.thickness], so this is a
     * "nothing is actually colliding" fixture unless the exclusion topology is broken. */
    private fun buildFlatGrid(store: ParticleStore = ParticleStore()): Pair<List<List<Int>>, Surface> {
        val rows = 4
        val cols = 4
        val spacing = 0.2
        val grid = (0 until rows).map { r ->
            (0 until cols).map { c ->
                store.create(position = Vector3(c * spacing, 0.0, r * spacing), mass = ScalarExpr.of(0.01))
            }
        }
        return grid to Surface(Grid.triangles(grid))
    }

    @Test
    fun `a flat resting mesh produces zero corrections`() {
        val store = ParticleStore()
        val (grid, surface) = buildFlatGrid(store)
        val before = grid.flatten().associateWith { store.position(it) }

        val system = SurfaceSelfCollisionSystem(listOf(SurfaceSelfCollisionRule(surface = surface, thickness = 0.05, excludeRings = 2)))
        system.resolve(store)

        for (id in grid.flatten()) {
            assertEquals(before.getValue(id), store.position(id), "vertex $id should be untouched on a flat, unfolded mesh")
        }
    }

    @Test
    fun `excludeRings=0 falsely fires on a flat mesh's own local curvature-free neighborhood`() {
        // Deliberately the wrong config (no ring exclusion beyond a vertex's own triangles) at a
        // thickness comparable to the grid spacing - proves the exclusion mechanism is actually
        // load-bearing, not merely present. A vertex's immediate 1-ring neighbor triangles sit
        // well within 0.15 of it even on a perfectly flat sheet (spacing 0.2, diagonal ~0.28),
        // so without ring exclusion beyond ring 0 this must fire.
        val store = ParticleStore()
        val (grid, surface) = buildFlatGrid(store)
        val before = grid.flatten().associateWith { store.position(it) }

        val system = SurfaceSelfCollisionSystem(listOf(SurfaceSelfCollisionRule(surface = surface, thickness = 0.15, excludeRings = 0)))
        system.resolve(store)

        val anyMoved = grid.flatten().any { (store.position(it) - before.getValue(it)).length() > 1e-12 }
        assertTrue(anyMoved, "excludeRings=0 at this thickness should have fired on local curvature - if not, the exclusion topology isn't being applied at all")
    }

    @Test
    fun `a vertex folded onto a topologically-distant triangle is pushed back out, and the triangle reacts`() {
        val store = ParticleStore()
        val (grid, surface) = buildFlatGrid(store)

        // Corner (0,0) is 6 mesh-edge hops from the (3,2) corner's triangles - far outside
        // excludeRings=2 - so folding it directly onto that triangle is a genuine, non-adjacent
        // self-intersection, not local curvature.
        val foldedVertex = grid[0][0]
        val targetTriangle = surface.triangles.first { it.a == grid[3][2] || it.b == grid[3][2] || it.c == grid[3][2] }
        val trianglePositionsBefore = listOf(targetTriangle.a, targetTriangle.b, targetTriangle.c).associateWith { store.position(it) }
        val centroidBefore = targetTriangle.centroid(store)
        store.setPosition(foldedVertex, centroidBefore)

        val system = SurfaceSelfCollisionSystem(listOf(SurfaceSelfCollisionRule(surface = surface, thickness = 0.05, excludeRings = 2)))
        system.resolve(store)

        assertNotEquals(centroidBefore, store.position(foldedVertex), "the folded vertex should have been pushed back out of the triangle it landed on")
        // Newton's third law check: unlike SurfaceCollisionSystem's query-only correction, at
        // least one of the target triangle's own vertices must have moved too (see
        // SurfaceSelfCollisionSystem's own doc comment for why the correction is split).
        val triangleVertexMoved = listOf(targetTriangle.a, targetTriangle.b, targetTriangle.c)
            .any { id -> (store.position(id) - trianglePositionsBefore.getValue(id)).length() > 1e-12 }
        assertTrue(triangleVertexMoved, "the target triangle's own vertices should have reacted too, not just the folded vertex")
    }
}
