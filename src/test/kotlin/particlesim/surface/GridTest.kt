package particlesim.surface

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridTest {

    private fun makeGrid(store: ParticleStore, rows: Int, cols: Int): List<List<Int>> =
        (0 until rows).map { r -> (0 until cols).map { c -> store.create(position = Vector3(c.toDouble(), r.toDouble(), 0.0)) } }

    @Test
    fun `triangle count is two per cell`() {
        val store = ParticleStore()
        val ids = makeGrid(store, rows = 4, cols = 5)
        val triangles = Grid.triangles(ids)
        assertEquals((4 - 1) * (5 - 1) * 2, triangles.size)
    }

    @Test
    fun `every triangle winds consistently toward +Z for a flat XY sheet`() {
        val store = ParticleStore()
        val ids = makeGrid(store, rows = 5, cols = 6)
        val triangles = Grid.triangles(ids)

        for (triangle in triangles) {
            val normal = triangle.normal(store)
            assertTrue(normal.z > 0.9, "expected a +Z-facing normal, got $normal for $triangle")
        }
    }

    @Test
    fun `structural edges connect every orthogonal grid neighbor exactly once`() {
        val store = ParticleStore()
        val ids = makeGrid(store, rows = 3, cols = 3)
        val edges = Grid.structuralEdges(ids)
        // 3x3 grid: 2 horizontal edges per row * 3 rows + 2 vertical edges per col * 3 cols = 6 + 6 = 12
        assertEquals(12, edges.size)
    }

    @Test
    fun `shear edges connect the two diagonals of every cell`() {
        val store = ParticleStore()
        val ids = makeGrid(store, rows = 3, cols = 3)
        val edges = Grid.shearEdges(ids)
        assertEquals((3 - 1) * (3 - 1) * 2, edges.size)
    }

    @Test
    fun `bend edges skip exactly one vertex`() {
        val store = ParticleStore()
        val ids = makeGrid(store, rows = 3, cols = 5)
        val edges = Grid.bendEdges(ids)
        // horizontal: 3 rows * (5-2) = 9; vertical: (3-2) * 5 = 5
        assertEquals(9 + 5, edges.size)
    }

    @Test
    fun `uvs cover every vertex, normalized to 0,1 at the grid's corners`() {
        val store = ParticleStore()
        val ids = makeGrid(store, rows = 4, cols = 6)
        val uvs = Grid.uvs(ids)

        assertEquals(4 * 6, uvs.size)
        assertEquals(UV(0.0, 0.0), uvs.getValue(ids[0][0]))
        assertEquals(UV(1.0, 0.0), uvs.getValue(ids[0][5]))
        assertEquals(UV(0.0, 1.0), uvs.getValue(ids[3][0]))
        assertEquals(UV(1.0, 1.0), uvs.getValue(ids[3][5]))
        // mid-grid: exactly halfway along both axes for an even 4x6 layout at row 1.5/col 2.5 -
        // check a specific interior point instead, since there's no exact center cell here.
        assertEquals(UV(2.0 / 5.0, 1.0 / 3.0), uvs.getValue(ids[1][2]))
    }

    @Test
    fun `uvs rejects a grid with fewer than 2 rows or cols, matching triangles' own guard`() {
        val store = ParticleStore()
        assertTrue(runCatching { Grid.uvs(makeGrid(store, rows = 1, cols = 3)) }.isFailure)
        assertTrue(runCatching { Grid.uvs(makeGrid(store, rows = 3, cols = 1)) }.isFailure)
    }
}
