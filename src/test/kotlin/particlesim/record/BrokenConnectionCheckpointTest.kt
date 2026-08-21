package particlesim.record

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Integrator
import particlesim.physics.Spring
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §9.5's "set of already-broken connections" piece, using a minimal synthetic two-particle
 * breakable spring — sparks (this sub-pass's main proof scenario) has no [particlesim.physics.PairwiseForce]
 * at all, so it can't exercise this on its own. A broken [Spring]/[particlesim.physics.Damper]
 * is fully removed from the caller's active force list (unlike `MeshSprings`, which just flips
 * an `active` bit and stays in its own force), so "already broken" has to be captured as
 * particle-id pairs and re-applied by filtering a freshly-rebuilt force list — see
 * [filterBrokenConnections].
 */
class BrokenConnectionCheckpointTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("checkpoint-broken-test", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun `a broken connection is captured and excluded from a freshly rebuilt force list on resume`() {
        val store = ParticleStore()
        val groups = Groups()
        val idA = store.create(position = Vector3(0.0, 0.0, 0.0))
        val idB = store.create(position = Vector3(5.0, 0.0, 0.0)) // far past restLength + breakThreshold

        // "Static definition": rest length 1.0, breaks past 0.1m of displacement — with idB
        // starting 5m away, this breaks on the very first step.
        var forces: List<particlesim.physics.Force> = listOf(Spring(idA, idB, restLength = 1.0, stiffness = 10.0, breakThreshold = 0.1))
        val brokenConnections = mutableSetOf<Pair<Int, Int>>()

        val integrator = Integrator()
        val dt = 1e-3
        val result = integrator.step(store, groups, forces, emptyList(), 0.0, dt)
        brokenConnections += result.brokenForces.filterIsInstance<particlesim.physics.PairwiseForce>()
            .map { it.particleA to it.particleB }
        forces = forces - result.brokenForces.toSet()

        assertTrue(brokenConnections.isNotEmpty(), "expected the spring to have broken on the first step")
        assertTrue(forces.isEmpty(), "expected the broken spring to already be out of the active force list")

        val checkpoint = captureCheckpoint(
            store = store, groups = groups, groupNames = emptyList(), emitters = emptyList(),
            brokenConnections = brokenConnections, t = dt, step = 1L,
        )
        val dir = tempDir()
        val basePath = File(dir, "checkpoint-broken")
        CheckpointWriter.write(checkpoint, basePath)
        val reloaded = CheckpointReader.read(basePath)

        assertEquals(setOf(idA to idB), reloaded.brokenConnections)

        // Resume: rebuild the *same static definition* fresh (a brand-new Spring instance
        // between the same two ids, exactly as a real resume would reconstruct forces from
        // the scenario's YAML/Kotlin definition) and confirm filtering drops it.
        val freshStore = ParticleStore()
        val freshGroups = Groups()
        applyCheckpoint(freshStore, freshGroups, emptyMap(), reloaded)
        val freshForces = listOf(Spring(idA, idB, restLength = 1.0, stiffness = 10.0, breakThreshold = 0.1))
        val filtered = filterBrokenConnections(freshForces, reloaded.brokenConnections)

        assertTrue(filtered.isEmpty(), "the rebuilt spring should have been filtered out as already-broken")
        // Positions restored exactly as captured (post-step, since the spring still applied
        // its force for the one step it broke on — removal only takes effect starting next).
        assertEquals(store.position(idA), freshStore.position(idA))
        assertEquals(store.position(idB), freshStore.position(idB))
    }
}
