package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.surface.Grid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §9.3/§11's actual guarantee is bit-identical output "for a given seed **and chunk
 * count**" — reproducible across reruns/machines/thread counts at a *fixed* chunk count,
 * not bit-identical *across different* chunk counts. That second thing isn't achievable:
 * different chunk counts sum the same set of pairwise contributions in a different grouping
 * (e.g. `(0,5)` and `(4,5)` may land in the same chunk at one chunk count and different
 * chunks at another), and floating-point addition isn't associative — confirmed empirically
 * here first (an earlier bit-exact-across-chunk-counts version of this test failed with
 * last-bit-only differences, not the large discrepancies a real striding bug would produce).
 *
 * So this file checks two different things:
 * - [assertReproducibleAtFixedChunkCount]: the real contract — the same chunk count run
 *   twice must be bit-identical. Trivially true today since nothing is threaded yet, but
 *   documents the property Phase 8 must preserve.
 * - [assertConsistentAcrossChunkCounts]: a *tolerance*-based sanity check across different
 *   chunk counts (1, 4, 7 — the latter doesn't evenly divide the work-item counts below,
 *   which is where off-by-one striding bugs live). A dropped or double-counted work item
 *   would show up as a discrepancy many orders of magnitude larger than floating-point
 *   reordering noise, so this still catches the real bug the bit-exact version was chasing.
 */
class ChunkStridingTest {

    private fun accumulateAll(store: ParticleStore, groups: Groups, force: Force, chunkCount: Int): ChunkAccumulator {
        val merged = ChunkAccumulator(store.capacity)
        for (chunkIndex in 0 until chunkCount) {
            val chunk = ChunkAccumulator(store.capacity)
            force.accumulate(store, groups, 0.0, chunk, chunkIndex, chunkCount)
            chunk.addInto(merged)
        }
        return merged
    }

    private fun assertReproducibleAtFixedChunkCount(store: ParticleStore, groups: Groups, force: Force, ids: List<Int>, chunkCount: Int) {
        val first = accumulateAll(store, groups, force, chunkCount)
        val second = accumulateAll(store, groups, force, chunkCount)
        for (id in ids) {
            val slot = store.slotOf(id)
            assertEquals(first.at(slot), second.at(slot), "chunkCount=$chunkCount was not reproducible for particle $id")
        }
    }

    private fun assertConsistentAcrossChunkCounts(
        store: ParticleStore, groups: Groups, force: Force, ids: List<Int>, epsilon: Double = 1e-6,
    ) {
        val reference = accumulateAll(store, groups, force, 1)
        for (chunkCount in listOf(4, 7)) {
            val result = accumulateAll(store, groups, force, chunkCount)
            for (id in ids) {
                val slot = store.slotOf(id)
                val expected = reference.at(slot)
                val actual = result.at(slot)
                assertTrue(
                    abs(expected.x - actual.x) < epsilon && abs(expected.y - actual.y) < epsilon && abs(expected.z - actual.z) < epsilon,
                    "chunkCount=$chunkCount diverged too far from chunkCount=1 for particle $id: expected $expected, got $actual",
                )
            }
        }
    }

    @Test
    fun `NBodyGravity accumulation is consistent and reproducible across chunk counts`() {
        val store = ParticleStore()
        val groups = Groups()
        val ids = (0 until 10).map { i ->
            store.create(position = Vector3(i * 1.3, (i % 3) * 0.7, -i * 0.4), mass = ScalarExpr.of(1.0 + i * 0.2))
        }
        ids.forEach { groups.add("bodies", it) }
        val force = NBodyGravity("bodies", g = 1.0, softening = 1e-3)

        assertConsistentAcrossChunkCounts(store, groups, force, ids)
        assertReproducibleAtFixedChunkCount(store, groups, force, ids, chunkCount = 4)
    }

    @Test
    fun `MeshSprings accumulation is consistent and reproducible across chunk counts`() {
        val store = ParticleStore()
        val groups = Groups()
        val ids = (0 until 11).map { i ->
            store.create(position = Vector3(i * 0.3, (i % 4) * 0.25, (i % 2) * 0.1))
        }
        val edges = ids.zipWithNext { a, b -> Grid.Edge(a, b) } + Grid.Edge(ids[0], ids[5]) + Grid.Edge(ids[2], ids[9])
        val force = MeshSprings(edges, store, stiffness = 20.0, damping = 1.0)
        // Displace after capturing rest lengths so the force is actually nonzero.
        ids.forEachIndexed { i, id -> store.setPosition(id, store.position(id) + Vector3(0.05 * i, -0.03 * i, 0.02 * i)) }

        assertConsistentAcrossChunkCounts(store, groups, force, ids)
        assertReproducibleAtFixedChunkCount(store, groups, force, ids, chunkCount = 4)
    }

    @Test
    fun `Wind accumulation is consistent and reproducible across chunk counts`() {
        val store = ParticleStore()
        val groups = Groups()
        val ids = (0 until 4).map { r -> (0 until 4).map { c -> store.create(position = Vector3(c.toDouble(), r.toDouble(), 0.0)) } }
        val triangles = Grid.triangles(ids)
        val force = Wind(triangles, VectorExpr.of(Vector3(1.0, 0.5, 3.0)), density = 1.2)
        val flat = ids.flatten()

        assertConsistentAcrossChunkCounts(store, groups, force, flat)
        assertReproducibleAtFixedChunkCount(store, groups, force, flat, chunkCount = 4)
    }
}
