package particlesim.collision

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §9.3's broad-phase grid, tested directly against the exactness property its own doc comment
 * claims: nothing within [cellSize] of a query position is ever missed. */
class SpatialGridTest {

    @Test
    fun `an empty grid returns no neighbors anywhere`() {
        val grid = SpatialGrid(cellSize = 1.0)
        assertEquals(emptyList(), grid.neighbors(Vector3.ZERO))
    }

    @Test
    fun `a particle is its own neighbor at its own position`() {
        val grid = SpatialGrid(cellSize = 1.0)
        grid.insert(1, Vector3(0.1, 0.1, 0.1))
        assertEquals(listOf(1), grid.neighbors(Vector3(0.1, 0.1, 0.1)))
    }

    @Test
    fun `two particles within cellSize of each other are found regardless of which side of a cell boundary each sits on`() {
        val grid = SpatialGrid(cellSize = 1.0)
        // 0.9 sits in cell 0, 1.1 sits in cell 1 - adjacent cells, distance 0.2 apart.
        grid.insert(1, Vector3(0.9, 0.0, 0.0))
        grid.insert(2, Vector3(1.1, 0.0, 0.0))
        assertEquals(setOf(1, 2), grid.neighbors(Vector3(0.9, 0.0, 0.0)).toSet())
        assertEquals(setOf(1, 2), grid.neighbors(Vector3(1.1, 0.0, 0.0)).toSet())
    }

    @Test
    fun `two particles two or more cells apart on one axis are never returned as neighbors of each other`() {
        val grid = SpatialGrid(cellSize = 1.0)
        grid.insert(1, Vector3(0.0, 0.0, 0.0))
        grid.insert(2, Vector3(2.5, 0.0, 0.0)) // cell 2, two cells away from cell 0
        assertTrue(2 !in grid.neighbors(Vector3(0.0, 0.0, 0.0)))
        assertTrue(1 !in grid.neighbors(Vector3(2.5, 0.0, 0.0)))
    }

    @Test
    fun `diagonal neighbors across a corner are found too, not just axis-aligned ones`() {
        val grid = SpatialGrid(cellSize = 1.0)
        grid.insert(1, Vector3(0.9, 0.9, 0.9))
        grid.insert(2, Vector3(1.1, 1.1, 1.1)) // one cell away on all three axes
        assertTrue(2 in grid.neighbors(Vector3(0.9, 0.9, 0.9)))
    }

    @Test
    fun `multiple particles in the same cell are all returned`() {
        val grid = SpatialGrid(cellSize = 1.0)
        grid.insert(1, Vector3(0.1, 0.1, 0.1))
        grid.insert(2, Vector3(0.2, 0.2, 0.2))
        grid.insert(3, Vector3(0.3, 0.3, 0.3))
        assertEquals(setOf(1, 2, 3), grid.neighbors(Vector3(0.15, 0.15, 0.15)).toSet())
    }

    @Test
    fun `negative-coordinate positions are bucketed correctly, not off by one across zero`() {
        val grid = SpatialGrid(cellSize = 1.0)
        // -0.1 and 0.1 are on opposite sides of zero but only 0.2 apart - must land as neighbors.
        grid.insert(1, Vector3(-0.1, -0.1, -0.1))
        grid.insert(2, Vector3(0.1, 0.1, 0.1))
        assertTrue(2 in grid.neighbors(Vector3(-0.1, -0.1, -0.1)))
    }

    @Test
    fun `a non-positive cellSize is rejected rather than silently degenerating`() {
        try {
            SpatialGrid(cellSize = 0.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `nearest matches a brute-force search, whatever the cell size`() {
        val random = kotlin.random.Random(7)
        val points = (0 until 400).associateWith { Vector3(random.nextDouble(-20.0, 20.0), random.nextDouble(-5.0, 5.0), random.nextDouble(-20.0, 20.0)) }
        for (cellSize in listOf(0.5, 2.0, 9.0)) {
            val grid = SpatialGrid(cellSize)
            for ((id, p) in points) grid.insert(id, p)
            for (query in listOf(0, 17, 211, 399)) {
                val at = points.getValue(query)
                for ((k, maxDistance) in listOf(1 to 100.0, 7 to 100.0, 20 to 4.0)) {
                    val expected = points.filterKeys { it != query }.map { (id, p) -> id to (p - at).length() }
                        .filter { it.second <= maxDistance }.sortedWith(compareBy({ it.second }, { it.first })).take(k).map { it.first }
                    assertEquals(expected, grid.nearest(at, k, maxDistance, exclude = query) { points.getValue(it) }, "cell $cellSize, query $query, k $k")
                }
            }
        }
    }

    @Test
    fun `nearest breaks distance ties by id and leaves out the excluded id`() {
        val grid = SpatialGrid(cellSize = 1.0)
        val points = mapOf(5 to Vector3(1.0, 0.0, 0.0), 3 to Vector3(-1.0, 0.0, 0.0), 4 to Vector3(0.0, 1.0, 0.0), 9 to Vector3.ZERO)
        for ((id, p) in points) grid.insert(id, p)
        assertEquals(listOf(3, 4), grid.nearest(Vector3.ZERO, 2, 10.0, exclude = 9) { points.getValue(it) })
    }

    @Test
    fun `nearest returns fewer than k when fewer lie within reach`() {
        val grid = SpatialGrid(cellSize = 1.0)
        val points = mapOf(1 to Vector3(0.5, 0.0, 0.0), 2 to Vector3(30.0, 0.0, 0.0))
        for ((id, p) in points) grid.insert(id, p)
        assertEquals(listOf(1), grid.nearest(Vector3.ZERO, 5, 10.0, exclude = -1) { points.getValue(it) })
    }
}
