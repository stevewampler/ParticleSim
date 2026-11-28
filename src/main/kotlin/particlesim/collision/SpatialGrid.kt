package particlesim.collision

import particlesim.core.Vector3
import kotlin.math.abs
import kotlin.math.ceil
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

    /**
     * Every id in the cells within [radius] of [position] (a cube of them) - a superset of the
     * ids within [radius]; the caller checks the distances. Order is bucket order, so a caller
     * whose result depends on order must impose one itself (§11).
     */
    fun within(position: Vector3, radius: Double): List<Int> {
        val center = cellOf(position)
        val reach = ceil(radius / cellSize).toLong()
        val result = ArrayList<Int>()
        for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
            buckets[Cell(center.x + dx, center.y + dy, center.z + dz)]?.let { result.addAll(it) }
        }
        return result
    }

    /**
     * The (up to) [k] ids nearest [position] within [maxDistance] of it, nearest first, leaving
     * out [exclude] - a topological neighbourhood (a fixed *count* of neighbours, however far
     * apart they are, as starlings use: see [particlesim.physics.Flocking]) rather than
     * [neighbors]' fixed radius. [positionOf] gives each inserted id's position (the grid keeps
     * only ids).
     *
     * Exact: it searches outward ring by ring (the cells at Chebyshev distance 0, 1, 2, ... from
     * [position]'s cell) and stops once it holds [k] candidates and the k-th is no farther than
     * anything an unsearched ring could hold - every point beyond ring `r` is at least
     * `r * cellSize` away, wherever in its cell [position] sits. So [cellSize] only affects speed:
     * about the typical neighbour spacing is best. Ties in distance go to the lower id, so the
     * result doesn't depend on insertion order (§11). Each ring visits only its own shell of
     * cells (its six faces), not the whole cube it bounds, so a lone straggler far from the rest
     * costs about the number of cells within [maxDistance], not that times the number of rings.
     *
     * [accept], when given (an id and its position), leaves out any id it rejects, as if it weren't there (a bird's blind
     * angle, say): the result is then the [k] nearest *accepted* ids. Rejecting candidates never
     * breaks the stopping rule above, which only needs the k-th kept candidate to be close enough.
     */
    fun nearest(
        position: Vector3, k: Int, maxDistance: Double, exclude: Int,
        accept: ((Int, Vector3) -> Boolean)? = null, positionOf: (Int) -> Vector3,
    ): List<Int> {
        if (k <= 0) return emptyList()
        val center = cellOf(position)
        val maxSq = maxDistance * maxDistance
        val ids = IntArray(k)
        val distSq = DoubleArray(k)
        var count = 0
        val maxRing = ceil(maxDistance / cellSize).toLong() + 1
        var ring = 0L
        while (ring <= maxRing) {
            for (dx in -ring..ring) for (dy in -ring..ring) {
                // On an x or y face, the whole z column belongs to this ring's shell; inside
                // them, only its two z faces do.
                val onSide = abs(dx) == ring || abs(dy) == ring
                val dzStep = if (onSide) 1L else 2 * ring
                var dz = -ring
                while (dz <= ring) {
                    val bucket = buckets[Cell(center.x + dx, center.y + dy, center.z + dz)]
                    dz += dzStep
                    if (bucket == null) continue
                    for (id in bucket) {
                        if (id == exclude) continue
                        val p = positionOf(id)
                        val d = (p - position).lengthSquared()
                        if (d > maxSq) continue
                        if (count == k && (d > distSq[k - 1] || (d == distSq[k - 1] && id > ids[k - 1]))) continue
                        if (accept != null && !accept(id, p)) continue
                        // Insertion into the sorted (distance, id) list, dropping the farthest if full.
                        var i = if (count < k) count++ else k - 1
                        while (i > 0 && (distSq[i - 1] > d || (distSq[i - 1] == d && ids[i - 1] > id))) {
                            distSq[i] = distSq[i - 1]
                            ids[i] = ids[i - 1]
                            i--
                        }
                        distSq[i] = d
                        ids[i] = id
                    }
                }
            }
            val reach = ring * cellSize
            if (count == k && distSq[k - 1] <= reach * reach) break
            ring++
        }
        return ids.take(count)
    }
}
