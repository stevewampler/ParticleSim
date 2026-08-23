package particlesim.collision

import particlesim.core.Vector3
import kotlin.math.floor

/**
 * A uniform-grid broad phase for particle-particle collision (§9.3, §12.4) — buckets particle
 * ids by which [cellSize]-sized cell their current position falls in, so [neighbors] only has to
 * look at a position's own cell and its 26 neighbors instead of every other particle.
 *
 * **Exact, not approximate, for the positions it was built from** — but this technique does not
 * carry over to N-body gravity (deliberately out of scope here; see [ParticleCollisionSystem]'s
 * own note). Gravity sums a contribution from every pair with no cutoff distance, so silently
 * dropping far-apart pairs would change the physics, not just speed it up; requirements.md §9.3
 * calls out Barnes-Hut, a genuinely different algorithm, for that case. Collision has a real,
 * physical cutoff instead: two spheres can only overlap if their centers are within
 * `radiusA + radiusB` of each other. As long as [cellSize] is at least the largest possible
 * `radiusA + radiusB` in play, any pair that could overlap *at the moment this grid was built* has
 * centers within [cellSize] of each other, and two cells more than one apart on any axis are
 * always at least [cellSize] apart — so the 3x3x3 neighborhood around a cell is guaranteed to
 * contain everything that could possibly overlap something in it, with nothing missed. Over-
 * inclusion (a same- or neighbor-cell particle that turns out not to actually overlap) is fine and
 * expected — the narrow phase in [ParticleCollisionSystem.resolve] filters those out the same way
 * it always has.
 *
 * That "at the moment this grid was built" qualifier is a real, accepted gap, not just a caveat
 * for form's sake: [ParticleCollisionSystem.resolve] builds one grid per rule and then mutates
 * positions as it resolves each candidate pair (a penetration correction can nudge a particle
 * into a *third* particle it wasn't overlapping when the grid was built). The old brute-force
 * candidatePairs re-read live positions for every pair regardless, so it could occasionally catch
 * a contact created mid-call; a pair that wasn't already a grid candidate at generation time never
 * gets tested here. Re-querying per pair would defeat the point of having a broad phase, and this
 * project's single-pass-per-step collision resolution is already documented elsewhere
 * ([particlesim.debug.ParticleCollisionDebugDemo]) as an approximation, not an iterative solver —
 * this is one more instance of that same accepted limitation, not a new one.
 *
 * Rebuilt fresh every call rather than incrementally maintained (§9.3's "needs to support
 * insert/remove, not just a static build at t=0") — positions move every physics step regardless,
 * so there's no cheaper *correct* alternative to a full rebuild, and a particle spawned or
 * destroyed since the last build is simply reflected in the next one with no separate
 * remove/insert bookkeeping to get wrong.
 */
class SpatialGrid(private val cellSize: Double) {
    init {
        require(cellSize > 0.0) { "cellSize must be positive, was $cellSize" }
    }

    private data class Cell(val x: Long, val y: Long, val z: Long)

    private val buckets = HashMap<Cell, MutableList<Int>>()

    private fun cellOf(position: Vector3): Cell =
        Cell(floor(position.x / cellSize).toLong(), floor(position.y / cellSize).toLong(), floor(position.z / cellSize).toLong())

    fun insert(id: Int, position: Vector3) {
        buckets.getOrPut(cellOf(position)) { ArrayList() }.add(id)
    }

    /**
     * Every distinct id in the 3x3x3 neighborhood of cells around [position] — a superset of
     * "everything that could overlap a sphere centered here," per this class's own doc comment.
     * Order is unspecified (bucket insertion order); a caller needing deterministic pair
     * ordering (§11) must impose it itself, the same way [ParticleCollisionSystem] does.
     */
    fun neighbors(position: Vector3): List<Int> {
        val center = cellOf(position)
        val result = ArrayList<Int>()
        for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
            buckets[Cell(center.x + dx, center.y + dy, center.z + dz)]?.let { result.addAll(it) }
        }
        return result
    }
}
