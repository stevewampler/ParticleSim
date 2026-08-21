package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.surface.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshSpringsTest {

    @Test
    fun `applies spring plus damper force per edge, striding chunks like NBodyGravity`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(2.0, 0.0, 0.0), velocity = Vector3(1.0, 0.0, 0.0)) // stretched, separating
        val mesh = MeshSprings(listOf(Grid.Edge(a, b)), store, stiffness = 10.0, damping = 2.0)
        // restLength captured at construction = 2.0, so it's currently at rest length; move it after.
        store.setPosition(b, Vector3(3.0, 0.0, 0.0)) // now stretched by 1.0 relative to restLength=2.0

        val chunkCount = 4
        val merged = ChunkAccumulator(store.capacity)
        for (chunkIndex in 0 until chunkCount) {
            val chunk = ChunkAccumulator(store.capacity)
            mesh.accumulate(store, groups, 0.0, chunk, chunkIndex, chunkCount)
            chunk.addInto(merged)
        }

        // displacement = 1.0, k=10 -> spring force magnitude 10; relative velocity along +x = 1.0, damping=2 -> 2
        // total force on b = -(10*1.0) - (2*1.0) = -12 along x
        assertEquals(Vector3(-12.0, 0.0, 0.0), merged.at(store.slotOf(b)))
        assertEquals(Vector3(12.0, 0.0, 0.0), merged.at(store.slotOf(a)))
    }

    @Test
    fun `edge count matches the input edge list`() {
        val store = ParticleStore()
        val ids = (0 until 3).map { store.create(position = Vector3(it.toDouble(), 0.0, 0.0)) }
        val edges = listOf(Grid.Edge(ids[0], ids[1]), Grid.Edge(ids[1], ids[2]))
        val mesh = MeshSprings(edges, store, stiffness = 5.0)
        assertEquals(2, mesh.edgeCount)
        assertEquals(setOf(ids[0] to ids[1], ids[1] to ids[2]), mesh.activeConnections().toSet())
    }

    @Test
    fun `a broken edge stops contributing force on the next accumulate call`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val mesh = MeshSprings(listOf(Grid.Edge(a, b)), store, stiffness = 100.0, extensionBreakThreshold = 0.5)

        store.setPosition(b, Vector3(2.0, 0.0, 0.0)) // displacement 1.0 > 0.5 threshold

        val chunk1 = ChunkAccumulator(store.capacity)
        mesh.accumulate(store, groups, 0.0, chunk1, 0, 1)
        // Still applies force the step it broke — deactivation is decided within this same
        // call (after computing the force), so it already shows as inactive right after, but
        // that's "in effect starting next step", not "this step's force never happened".
        assertTrue(chunk1.at(store.slotOf(b)).x < 0.0)
        assertTrue(mesh.activeConnections().isEmpty())

        val chunk2 = ChunkAccumulator(store.capacity)
        mesh.accumulate(store, groups, 0.0, chunk2, 0, 1)
        assertEquals(Vector3.ZERO, chunk2.at(store.slotOf(b)))
        assertTrue(mesh.activeConnections().isEmpty())
    }

    @Test
    fun `unbroken edges are unaffected by a sibling edge breaking`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(2.0, 0.0, 0.0))
        val c = store.create(position = Vector3(1.0, 0.0, 0.0))
        val mesh = MeshSprings(
            listOf(Grid.Edge(a, b), Grid.Edge(a, c)),
            store, stiffness = 100.0, extensionBreakThreshold = 0.5,
        )
        store.setPosition(b, Vector3(4.0, 0.0, 0.0)) // a-b displacement now 2.0, breaks

        val chunk = ChunkAccumulator(store.capacity)
        mesh.accumulate(store, groups, 0.0, chunk, 0, 1)
        mesh.accumulate(store, groups, 0.0, ChunkAccumulator(store.capacity), 0, 1) // second step

        assertFalse(a to b in mesh.activeConnections())
        assertTrue(a to c in mesh.activeConnections())
    }

    // --- activeConnectionsWithBreakProximity (§10.2's breakProximity line-renderer coloring) --

    @Test
    fun `breakProximity is 0 at rest and rises toward 1 as an edge approaches its threshold`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0)) // restLength captured as 1.0
        val mesh = MeshSprings(listOf(Grid.Edge(a, b)), store, stiffness = 10.0, extensionBreakThreshold = 1.0)

        assertEquals(0.0, mesh.activeConnectionsWithBreakProximity(store).single().third)

        store.setPosition(b, Vector3(1.5, 0.0, 0.0)) // displacement 0.5 of threshold 1.0
        assertEquals(0.5, mesh.activeConnectionsWithBreakProximity(store).single().third, 1e-12)
    }

    @Test
    fun `breakProximity is 0 for an edge with an unbounded (never-breaks) threshold`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1000.0, 0.0, 0.0))
        val mesh = MeshSprings(listOf(Grid.Edge(a, b)), store, stiffness = 1.0) // default infinite threshold

        assertEquals(0.0, mesh.activeConnectionsWithBreakProximity(store).single().third)
    }

    @Test
    fun `a broken edge is excluded from activeConnectionsWithBreakProximity, same as activeConnections`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val mesh = MeshSprings(listOf(Grid.Edge(a, b)), store, stiffness = 100.0, extensionBreakThreshold = 0.5)
        store.setPosition(b, Vector3(2.0, 0.0, 0.0)) // displacement 1.0 > threshold 0.5

        mesh.accumulate(store, groups, 0.0, ChunkAccumulator(store.capacity), 0, 1) // deactivates the edge

        assertTrue(mesh.activeConnectionsWithBreakProximity(store).isEmpty())
    }
}
