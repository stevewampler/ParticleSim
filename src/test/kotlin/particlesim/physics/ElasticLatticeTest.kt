package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ElasticLattice]: springs assembled cube by cube, calibrated to a Young's modulus, with
 * stress read back from the spring tensions. */
class ElasticLatticeTest {
    private val e = 200e9
    private val a = 0.05

    private fun box(nx: Int, ny: Int, nz: Int): Set<LatticeIndex> =
        (0 until nx).flatMap { i -> (0 until ny).flatMap { j -> (0 until nz).map { k -> LatticeIndex(i, j, k) } } }.toSet()

    private fun lattice(cells: Set<LatticeIndex>, store: ParticleStore = ParticleStore(), groups: Groups = Groups()) =
        ElasticLattice(store, groups, "solid", cells, a, Vector3(1.0, 2.0, 3.0), e, nodeMass = 1.0)

    /** Moves every node to where the uniform strain (exx, eyy, ezz) takes it. */
    private fun strain(lattice: ElasticLattice, store: ParticleStore, exx: Double, eyy: Double, ezz: Double) {
        for ((id, rest) in lattice.restPositions) {
            val d = rest - lattice.origin
            store.setPosition(id, lattice.origin + Vector3(d.x * (1 + exx), d.y * (1 + eyy), d.z * (1 + ezz)))
        }
    }

    private fun netForces(lattice: ElasticLattice, store: ParticleStore, groups: Groups): Map<Int, Vector3> {
        val chunk = ChunkAccumulator(store.capacity)
        lattice.accumulate(store, groups, 0.0, chunk, 0, 1)
        return lattice.nodeIds.associateWith { chunk.at(store.slotOf(it)) }
    }

    @Test
    fun `a spring's stiffness is the sum of the shares of the cubes it borders`() {
        val k = ElasticLattice.bulkStiffness(e, a)
        val one = lattice(box(1, 1, 1))
        assertEquals(8, one.nodeIds.size)
        assertEquals(24, one.springCount)
        val byLength = one.springs().groupBy { (p, q, _) -> (one.restPositions.getValue(p) - one.restPositions.getValue(q)).length() > a * 1.2 }
        assertTrue(byLength.getValue(false).all { abs(it.third - k / 4) < 1e-6 * k }, "edges get a quarter")
        assertTrue(byLength.getValue(true).all { abs(it.third - k / 2) < 1e-6 * k }, "face diagonals get a half")

        // Deep inside a 3x3x3 block, every spring around the middle node is full strength.
        val block = lattice(box(3, 3, 3))
        val middle = block.nodeId(1, 1, 1)!!
        val around = block.springs().filter { it.first == middle || it.second == middle }
        assertEquals(ElasticLattice.MAX_SPRINGS_PER_NODE, around.size)
        assertTrue(around.all { abs(it.third - k) < 1e-6 * k })
    }

    @Test
    fun `stretched evenly along x and free to narrow, every node balances and the stress is E times the strain`() {
        val store = ParticleStore(); val groups = Groups()
        val bar = lattice(box(6, 3, 4), store, groups)
        val eps = 1e-4
        // Poisson's ratio 1/4: free sides narrow by a quarter of the stretch.
        strain(bar, store, eps, -0.25 * eps, -0.25 * eps)
        val forces = netForces(bar, store, groups)
        val endFace = bar.nodeIds.filter { bar.indexOf(it)!!.i == 0 || bar.indexOf(it)!!.i == 6 }.toSet()
        val k = ElasticLattice.bulkStiffness(e, a)
        // Every node off the stretched ends carries no net force (to first order in the strain):
        // the sides really are free.
        for ((id, f) in forces) if (id !in endFace) assertTrue(f.length() < 1e-3 * k * a * eps, "node ${bar.indexOf(id)} has $f")
        // The pull on one end face, over its area, is E times the strain.
        val pull = forces.filterKeys { bar.indexOf(it)!!.i == 6 }.values.fold(Vector3.ZERO) { s, f -> s + f }
        val area = (3 * a) * (4 * a)
        assertEquals(-e * eps, pull.x / area, e * eps * 1e-3)
        assertEquals(0.0, pull.y, 1e-6 * e * eps * area)

        // Stress read back from the springs, per cube and at every node, inside and on the skin.
        for (s in bar.cellStresses(store)) {
            assertEquals(e * eps, s.xx, e * eps * 1e-3)
            assertEquals(0.0, s.yy, e * eps * 1e-3)
            assertEquals(0.0, s.zz, e * eps * 1e-3)
            assertEquals(0.0, s.xy, e * eps * 1e-3)
        }
        for (s in bar.nodeStresses(store).values) {
            assertEquals(e * eps, s.xx, e * eps * 1e-3)
            assertEquals(e * eps, s.vonMises, e * eps * 1e-3)
        }
    }

    @Test
    fun `stress read at the nodes follows a stress that varies across the solid, right out to the surface`() {
        // Bending-like strain: stretch along x growing linearly with height, through a block.
        val store = ParticleStore(); val groups = Groups()
        val block = lattice(box(4, 6, 3), store, groups)
        val curvature = 1e-3 // strain per metre of height
        val midY = block.origin.y + 3 * a
        for ((id, rest) in block.restPositions) {
            val d = rest - block.origin
            val exx = curvature * (rest.y - midY)
            // Each node moved by its own local strain - fine for the small strains here.
            store.setPosition(id, block.origin + Vector3(d.x * (1 + exx), d.y, d.z))
        }
        val top = block.nodeIds.filter { block.indexOf(it)!!.j == 6 && block.indexOf(it)!!.i == 2 }
        val stresses = block.nodeStresses(store, top)
        // At the top surface; averaging the cubes the node touches would read it half a cube low.
        val expected = e * curvature * (3 * a)
        // With no sideways narrowing allowed, a straight stretch reads (C11/E) higher: C11 = 1.2 E.
        for (id in top) assertEquals(1.2 * expected, stresses.getValue(id).xx, 0.03 * expected)
    }

    @Test
    fun `the skin is every outer cube face, wound to face outward`() {
        val one = lattice(box(1, 1, 1))
        val skin = one.boundarySurface("skin")
        assertEquals(12, skin.triangles.size)
        val centre = one.cellCentre(LatticeIndex(0, 0, 0))
        for (tri in skin.triangles) {
            val pa = one.restPositions.getValue(tri.a); val pb = one.restPositions.getValue(tri.b); val pc = one.restPositions.getValue(tri.c)
            val normal = (pb - pa).cross(pc - pa)
            assertTrue(normal.dot(pa - centre) > 0.0, "faces outward")
        }
        // Two cubes side by side: the face between them isn't skin.
        assertEquals(20, lattice(box(2, 1, 1)).boundarySurface().triangles.size)
    }

    @Test
    fun `a skin keeping only some cubes caps the cut with the interior face, wound outward`() {
        val bar = lattice(box(3, 1, 1))
        val cut = bar.boundarySurface { it.i < 2 }
        // Two cubes' worth of skin: 4 sides x 2 cubes + both ends.
        assertEquals(20, cut.triangles.size)
        val capNodes = bar.nodeIds.filter { bar.indexOf(it)!!.i == 2 }.toSet()
        val cap = cut.triangles.filter { setOf(it.a, it.b, it.c).all { id -> id in capNodes } }
        assertEquals(2, cap.size)
        for (tri in cap) {
            val pa = bar.restPositions.getValue(tri.a)
            val normal = (bar.restPositions.getValue(tri.b) - pa).cross(bar.restPositions.getValue(tri.c) - pa)
            assertTrue(normal.x > 0.0, "faces out of the cut, along +x")
        }
        // Nothing beyond the cut.
        assertTrue(cut.triangles.none { t -> listOf(t.a, t.b, t.c).any { bar.indexOf(it)!!.i == 3 } })
    }

    @Test
    fun `node volume is an eighth of each cube it touches`() {
        val block = lattice(box(2, 2, 2))
        val v = a * a * a
        assertEquals(v, block.nodeVolume(block.nodeId(1, 1, 1)!!), 1e-15)
        assertEquals(v / 8, block.nodeVolume(block.nodeId(0, 0, 0)!!), 1e-15)
        assertEquals(v / 2, block.nodeVolume(block.nodeId(1, 0, 1)!!), 1e-15)
        assertEquals(8 * v, block.nodeIds.sumOf { block.nodeVolume(it) }, 1e-12)
    }

    @Test
    fun `with failure on, a spring stretched past yield takes a permanent set and holds the yield force`() {
        val store = ParticleStore(); val groups = Groups()
        val bar = lattice(box(3, 2, 2), store, groups)
        val yieldStrain = bar.yieldStress / e
        val xEdges = (0 until bar.springCount).filter { s ->
            val (p, q) = bar.springEnds(s)
            bar.isEdge(s) && bar.indexOf(p)!!.i != bar.indexOf(q)!!.i
        }
        // Off: stretched 3x the yield strain, nothing yields.
        strain(bar, store, 3 * yieldStrain, 0.0, 0.0)
        netForces(bar, store, groups)
        assertTrue((0 until bar.springCount).all { bar.plasticStrain(it) == 0.0 })
        assertEquals(3 * yieldStrain, bar.elasticStrain(store, xEdges.first()), 1e-9)

        // On: each spring along the stretch yields, keeping the yield strain as its elastic part.
        bar.failure = true
        netForces(bar, store, groups)
        for (s in xEdges) {
            assertEquals(2 * yieldStrain, bar.plasticStrain(s), 1e-9)
            assertEquals(yieldStrain, bar.elasticStrain(store, s), 1e-9)
        }
        // Let go: it doesn't spring all the way back - the springs that yielded are now too long
        // and push (compression), a permanent set.
        strain(bar, store, 0.0, 0.0, 0.0)
        for (s in xEdges) assertTrue(bar.tension(store, s) < 0.0)
        assertTrue(bar.nodePlasticStrains(bar.nodeIds).values.max() > 1.9 * yieldStrain)
    }

    @Test
    fun `stretched past the rupture strain, springs tear, their cubes fail and the skin opens up`() {
        val store = ParticleStore(); val groups = Groups()
        val bar = lattice(box(3, 2, 2), store, groups)
        bar.failure = true
        val intactSkin = bar.boundarySurface().triangles.size
        // Stretch only the middle cube column (nodes at i = 2 and 3 pulled along) by 25%.
        for ((id, rest) in bar.restPositions) if (bar.indexOf(id)!!.i >= 2) store.setPosition(id, rest + Vector3(0.25 * a, 0.0, 0.0))
        netForces(bar, store, groups)
        assertTrue(bar.hasTorn)
        val torn = (0 until bar.springCount).filter { bar.isTorn(it) }
        assertTrue(torn.all { s -> val (p, q) = bar.springEnds(s); setOf(bar.indexOf(p)!!.i, bar.indexOf(q)!!.i) == setOf(1, 2) })
        assertEquals(0.0, bar.tension(store, torn.first()))
        assertEquals((0 until 2).flatMap { j -> (0 until 2).map { k -> LatticeIndex(1, j, k) } }.toSet(), bar.failedCells())
        // The failed middle cubes drop out; the two 1x2x2 end blocks each show a closed skin of
        // 16 squares, including the faces where the middle used to be.
        assertEquals(2 * 16 * 2, bar.boundarySurface().triangles.size)
        assertEquals(64, intactSkin)
        // Tearing is for good, even with failure switched off again.
        bar.failure = false
        netForces(bar, store, groups)
        assertTrue(torn.all { bar.isTorn(it) })
    }

    @Test
    fun `yield stress and rupture strain are live-editable`() {
        val bar = lattice(box(1, 1, 1))
        assertEquals(mapOf("yieldStressMPa" to FieldValue.Scalar(250.0), "ruptureStrainPercent" to FieldValue.Scalar(20.0)), bar.editableFields())
        assertTrue(bar.setField("yieldStressMPa", FieldValue.Scalar(355.0)))
        assertEquals(355e6, bar.yieldStress)
        assertTrue(bar.setField("ruptureStrainPercent", FieldValue.Scalar(10.0)))
        assertEquals(0.1, bar.ruptureStrain, 1e-12)
        assertTrue(!bar.setField("yieldStressMPa", FieldValue.Scalar(-1.0)))
    }
}
