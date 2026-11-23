package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.surface.Surface
import particlesim.surface.Triangle
import kotlin.math.abs
import kotlin.math.sqrt

/** One cube of an [ElasticLattice], or one of its corner nodes: cube (i, j, k) spans nodes
 * (i, j, k) to (i+1, j+1, k+1). */
data class LatticeIndex(val i: Int, val j: Int, val k: Int)

/** A symmetric stress tensor in pascals, tension positive. */
data class Stress(val xx: Double, val yy: Double, val zz: Double, val xy: Double, val yz: Double, val zx: Double) {
    /** The von Mises equivalent stress: the single number compared against a ductile metal's
     * yield stress (steel yields when it reaches about 250 MPa, whatever the mix of tension,
     * compression and shear that makes it up). */
    val vonMises: Double
        get() = sqrt(0.5 * ((xx - yy) * (xx - yy) + (yy - zz) * (yy - zz) + (zz - xx) * (zz - xx)) + 3.0 * (xy * xy + yz * yz + zx * zx))

    companion object {
        val ZERO = Stress(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * A solid block of elastic material (§5.1, §7) - steel, say - modelled as particles on a cubic
 * lattice joined by springs, built from a set of filled cubes ([cells]) of side [spacing]. One
 * [Force] for every spring in it, striding them across chunks like [MeshSprings].
 *
 * **Springs, cube by cube.** Each filled cube adds a spring share along its 12 edges (`k/4`) and
 * its 12 face diagonals (`k/2`), and a spring's stiffness is the sum of what every cube it
 * borders adds. Deep inside, an edge borders four cubes and a face diagonal two, so every spring
 * there has stiffness `k`; on the outside they border fewer and come out softer, as the half or
 * quarter of material around them should. Equal edge and diagonal stiffness makes the lattice
 * respond the same in every direction, with
 *
 *     E = 2.5 k / spacing,   Poisson's ratio = 1/4
 *
 * (a lattice of central springs can't have any other Poisson's ratio; steel's is 0.3). So
 * `k = E * spacing / 2.5` for a given [youngsModulus].
 *
 * **Stress.** [cellStresses] is each cube's average stress, from the tensions in its own share of
 * its springs: `sigma = (1/V) * sum of T * d (x) d / |d|` over them, `T` a spring share's tension
 * and `d` its current length and direction, `V` the cube's volume. That's exact for a cube
 * stretched evenly. [nodeStresses] carries that to the nodes by fitting a straight-line variation
 * through the cubes around each node and reading it off at the node (superconvergent patch
 * recovery) - unlike averaging the cubes a node touches, which would read the stress half a cube
 * inside, it finds the stress at the surface itself, where bending stress peaks.
 *
 * **Mass.** Every node gets the same [nodeMass]. Static stress doesn't depend on mass at all, so
 * a solid that only needs to settle under its load can use a far heavier mass than the real
 * material's to make its fastest vibration slow enough for a sensible time step ("mass scaling",
 * as explicit finite-element codes do for slow loading) - [stableNodeMass] gives the mass for a
 * given step. Weight from the real material then has to be applied as a force of its own, not
 * by gravity on [nodeMass]; [nodeVolume] gives each node's share of the material for that.
 *
 * [stiffnessDamping] (seconds) adds a dashpot across every spring proportional to its stiffness,
 * which damps the lattice's fast vibrations; damping of the slow, whole-body motion is better
 * done with linear [Drag].
 */
class ElasticLattice(
    store: ParticleStore,
    groups: Groups,
    val group: String,
    cells: Set<LatticeIndex>,
    val spacing: Double,
    val origin: Vector3,
    val youngsModulus: Double,
    nodeMass: Double,
    private val stiffnessDamping: Double = 0.0,
    override val name: String? = null,
) : Force {
    /** A spring's stiffness deep inside: [youngsModulus] times [spacing] / 2.5. */
    val bulkStiffness: Double = bulkStiffness(youngsModulus, spacing)

    val cells: List<LatticeIndex> = cells.sortedWith(compareBy({ it.i }, { it.j }, { it.k }))
    private val cellSet = cells.toSet()

    private val nodeIdByIndex = LinkedHashMap<LatticeIndex, Int>()
    private val nodeIndexById = HashMap<Int, LatticeIndex>()

    init {
        require(spacing > 0.0 && youngsModulus > 0.0 && nodeMass > 0.0)
        val corners = sortedSetOf(compareBy<LatticeIndex>({ it.i }, { it.j }, { it.k }))
        for (c in this.cells) for (d in CORNERS) corners += LatticeIndex(c.i + d.i, c.j + d.j, c.k + d.k)
        for (n in corners) {
            val id = store.create(position = restPositionOf(n), mass = ScalarExpr.of(nodeMass))
            groups.add(group, id)
            nodeIdByIndex[n] = id
            nodeIndexById[id] = n
        }
    }

    /** Every node's particle id, in lattice order. */
    val nodeIds: List<Int> = nodeIdByIndex.values.toList()

    fun nodeId(index: LatticeIndex): Int? = nodeIdByIndex[index]
    fun nodeId(i: Int, j: Int, k: Int): Int? = nodeIdByIndex[LatticeIndex(i, j, k)]
    fun indexOf(id: Int): LatticeIndex? = nodeIndexById[id]

    fun restPositionOf(index: LatticeIndex): Vector3 =
        origin + Vector3(index.i * spacing, index.j * spacing, index.k * spacing)

    /** Where each node sat when built, before any load. */
    val restPositions: Map<Int, Vector3> = nodeIdByIndex.entries.associate { (n, id) -> id to restPositionOf(n) }

    /** The volume of material a node stands for: an eighth of each filled cube it's a corner of. */
    fun nodeVolume(id: Int): Double {
        val n = nodeIndexById.getValue(id)
        val touching = CORNERS.count { d -> LatticeIndex(n.i - d.i, n.j - d.j, n.k - d.k) in cellSet }
        return touching / 8.0 * spacing * spacing * spacing
    }

    // ---- Springs: one entry per distinct node pair, stiffness summed over the cubes it borders.

    private val bondA: IntArray
    private val bondB: IntArray
    private val bondRest: DoubleArray
    private val bondStiffness: DoubleArray
    /** Per cube, its 24 springs (bond indices) and the stiffness share it gave each. */
    private val cellBonds: IntArray
    private val cellBondShare: DoubleArray

    init {
        val bondIndex = LinkedHashMap<Pair<Int, Int>, Int>()
        val a = ArrayList<Int>(); val b = ArrayList<Int>(); val stiffness = ArrayList<Double>()
        cellBonds = IntArray(this.cells.size * BONDS_PER_CELL)
        cellBondShare = DoubleArray(this.cells.size * BONDS_PER_CELL)
        for ((ci, c) in this.cells.withIndex()) {
            for ((bi, bond) in CELL_BONDS.withIndex()) {
                val (p, q, share) = bond
                val idP = nodeIdByIndex.getValue(LatticeIndex(c.i + p.i, c.j + p.j, c.k + p.k))
                val idQ = nodeIdByIndex.getValue(LatticeIndex(c.i + q.i, c.j + q.j, c.k + q.k))
                val key = if (idP < idQ) idP to idQ else idQ to idP
                val index = bondIndex.getOrPut(key) {
                    a += key.first; b += key.second; stiffness += 0.0
                    a.size - 1
                }
                stiffness[index] = stiffness[index] + share * bulkStiffness
                cellBonds[ci * BONDS_PER_CELL + bi] = index
                cellBondShare[ci * BONDS_PER_CELL + bi] = share * bulkStiffness
            }
        }
        bondA = a.toIntArray()
        bondB = b.toIntArray()
        bondStiffness = stiffness.toDoubleArray()
        bondRest = DoubleArray(bondA.size) { (store.position(bondB[it]) - store.position(bondA[it])).length() }
    }

    val springCount: Int get() = bondA.size

    /** Each spring's endpoints and its stiffness (summed over the cubes it borders). */
    fun springs(): List<Triple<Int, Int, Double>> = bondA.indices.map { Triple(bondA[it], bondB[it], bondStiffness[it]) }

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        if (!groups.isEnabled(group)) return
        var i = chunkIndex
        while (i < bondA.size) {
            val a = bondA[i]
            val b = bondB[i]
            val pa = store.position(a)
            val pb = store.position(b)
            val dx = pb.x - pa.x; val dy = pb.y - pa.y; val dz = pb.z - pa.z
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            val inv = 1.0 / length
            val nx = dx * inv; val ny = dy * inv; val nz = dz * inv
            var tension = bondStiffness[i] * (length - bondRest[i])
            if (stiffnessDamping != 0.0) {
                val va = store.velocity(a)
                val vb = store.velocity(b)
                val opening = (vb.x - va.x) * nx + (vb.y - va.y) * ny + (vb.z - va.z) * nz
                tension += stiffnessDamping * bondStiffness[i] * opening
            }
            val f = Vector3(nx * tension, ny * tension, nz * tension)
            chunk.add(store.slotOf(a), f)
            chunk.add(store.slotOf(b), -f)
            i += chunkCount
        }
    }

    // ---- Stress.

    /** Each cube's average stress, in [cells] order. Elastic stress only: the damping
     * dashpots' share (zero once the solid has settled) is left out. */
    fun cellStresses(store: ParticleStore): Array<Stress> {
        val volume = spacing * spacing * spacing
        val positions = HashMap<Int, Vector3>(nodeIds.size * 2)
        for (id in nodeIds) positions[id] = store.position(id)
        return Array(cells.size) { ci ->
            var xx = 0.0; var yy = 0.0; var zz = 0.0; var xy = 0.0; var yz = 0.0; var zx = 0.0
            for (s in 0 until BONDS_PER_CELL) {
                val bond = cellBonds[ci * BONDS_PER_CELL + s]
                val pa = positions.getValue(bondA[bond])
                val pb = positions.getValue(bondB[bond])
                val dx = pb.x - pa.x; val dy = pb.y - pa.y; val dz = pb.z - pa.z
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                // T * d (x) d / |d|
                val w = cellBondShare[ci * BONDS_PER_CELL + s] * (length - bondRest[bond]) / length
                xx += w * dx * dx; yy += w * dy * dy; zz += w * dz * dz
                xy += w * dx * dy; yz += w * dy * dz; zx += w * dz * dx
            }
            Stress(xx / volume, yy / volume, zz / volume, xy / volume, yz / volume, zx / volume)
        }
    }

    /** For each node, the cubes its stress is read from and each one's weight. */
    private val nodeWeights: Map<Int, List<Pair<Int, Double>>> by lazy {
        val cellIndex = this.cells.withIndex().associate { (i, c) -> c to i }
        nodeIdByIndex.entries.associate { (n, id) -> id to recoveryWeights(n, cellIndex) }
    }

    /** Fits `sigma = a0 + a . (x - node)` by least squares through the centres of the cubes in
     * the 4x4x4 block around node [n] and returns the weights giving `a0`, or - where those
     * centres don't span 3D well enough to fit a slope (a solid only one cube thick there) - an
     * even average of the cubes the node touches. */
    private fun recoveryWeights(n: LatticeIndex, cellIndex: Map<LatticeIndex, Int>): List<Pair<Int, Double>> {
        val patch = ArrayList<Pair<Int, DoubleArray>>()
        for (di in -2..1) for (dj in -2..1) for (dk in -2..1) {
            val index = cellIndex[LatticeIndex(n.i + di, n.j + dj, n.k + dk)] ?: continue
            // Cube centre relative to the node, in units of spacing.
            patch += index to doubleArrayOf(1.0, di + 0.5, dj + 0.5, dk + 0.5)
        }
        val m = Array(4) { DoubleArray(4) }
        for ((_, row) in patch) for (r in 0 until 4) for (c in 0 until 4) m[r][c] += row[r] * row[c]
        val inverseRow0 = invertRow0(m)
        if (inverseRow0 != null) {
            return patch.map { (index, row) -> index to (0 until 4).sumOf { inverseRow0[it] * row[it] } }
        }
        val touching = CORNERS.mapNotNull { d -> cellIndex[LatticeIndex(n.i - d.i, n.j - d.j, n.k - d.k)] }
        return touching.map { it to 1.0 / touching.size }
    }

    /** Stress at each of [ids] (default: every node), by the patch fit through [cellStresses]
     * described above. */
    fun nodeStresses(store: ParticleStore, ids: Collection<Int> = nodeIds): Map<Int, Stress> {
        val perCell = cellStresses(store)
        val result = HashMap<Int, Stress>(ids.size * 2)
        for (id in ids) {
            var xx = 0.0; var yy = 0.0; var zz = 0.0; var xy = 0.0; var yz = 0.0; var zx = 0.0
            for ((ci, w) in nodeWeights.getValue(id)) {
                val s = perCell[ci]
                xx += w * s.xx; yy += w * s.yy; zz += w * s.zz; xy += w * s.xy; yz += w * s.yz; zx += w * s.zx
            }
            result[id] = Stress(xx, yy, zz, xy, yz, zx)
        }
        return result
    }

    /** The cube's centre at rest. */
    fun cellCentre(cell: LatticeIndex): Vector3 =
        origin + Vector3((cell.i + 0.5) * spacing, (cell.j + 0.5) * spacing, (cell.k + 0.5) * spacing)

    // ---- Skin.

    /** The solid's outer skin: every cube face with no filled cube on its other side, as two
     * triangles wound to face outward. */
    fun boundarySurface(name: String? = null): Surface {
        val triangles = ArrayList<Triangle>()
        for (c in cells) {
            for (axis in 0 until 3) for (sign in intArrayOf(-1, 1)) {
                if (offset(c, axis, sign) in cellSet) continue
                val u = (axis + 1) % 3
                val v = (axis + 2) % 3
                val base = if (sign > 0) offset(c, axis, 1) else c
                val pu = offset(base, u, 1)
                val puv = offset(pu, v, 1)
                val pv = offset(base, v, 1)
                val quad = if (sign > 0) listOf(base, pu, puv, pv) else listOf(base, pv, puv, pu)
                val ids = quad.map { nodeIdByIndex.getValue(it) }
                triangles += Triangle(ids[0], ids[1], ids[2])
                triangles += Triangle(ids[0], ids[2], ids[3])
            }
        }
        return Surface(triangles, name)
    }

    companion object {
        private const val BONDS_PER_CELL = 24
        const val MAX_SPRINGS_PER_NODE = 18

        private val CORNERS: List<LatticeIndex> =
            (0..1).flatMap { i -> (0..1).flatMap { j -> (0..1).map { k -> LatticeIndex(i, j, k) } } }

        /** A cube's 12 edges (each a quarter share) and 12 face diagonals (each a half share),
         * as corner offsets. */
        private val CELL_BONDS: List<Triple<LatticeIndex, LatticeIndex, Double>> = buildList {
            for ((pi, p) in CORNERS.withIndex()) for (q in CORNERS.drop(pi + 1)) {
                when (abs(p.i - q.i) + abs(p.j - q.j) + abs(p.k - q.k)) {
                    1 -> add(Triple(p, q, 0.25))
                    2 -> add(Triple(p, q, 0.5))
                }
            }
        }

        private fun offset(c: LatticeIndex, axis: Int, by: Int) = when (axis) {
            0 -> LatticeIndex(c.i + by, c.j, c.k)
            1 -> LatticeIndex(c.i, c.j + by, c.k)
            else -> LatticeIndex(c.i, c.j, c.k + by)
        }

        /** The first row of the inverse of the symmetric 4x4 [m], or null if [m] is too close to
         * singular to trust. */
        private fun invertRow0(m: Array<DoubleArray>): DoubleArray? {
            val n = 4
            val a = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) m[r][c] else if (c - n == r) 1.0 else 0.0 } }
            val scale = (0 until n).maxOf { abs(m[it][it]) }
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-6 * scale) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                val p = a[col][col]
                for (c in 0 until 2 * n) a[col][c] /= p
                for (r in 0 until n) if (r != col) {
                    val f = a[r][col]
                    if (f != 0.0) for (c in 0 until 2 * n) a[r][c] -= f * a[col][c]
                }
            }
            return DoubleArray(n) { a[0][n + it] }
        }

        /** A spring's stiffness deep inside a lattice of material [youngsModulus] at [spacing]. */
        fun bulkStiffness(youngsModulus: Double, spacing: Double): Double = youngsModulus * spacing / 2.5

        /** The lightest node mass that keeps a lattice of material [youngsModulus] at [spacing]
         * stable at time step [dt], with margin: a node deep inside has 18 springs of
         * [bulkStiffness] on it, and at this mass the lattice's fastest vibration turns about one
         * radian per step (semi-implicit Euler's limit is two). */
        fun stableNodeMass(youngsModulus: Double, spacing: Double, dt: Double): Double =
            2.0 * MAX_SPRINGS_PER_NODE * bulkStiffness(youngsModulus, spacing) * dt * dt
    }
}
