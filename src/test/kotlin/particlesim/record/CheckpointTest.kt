package particlesim.record

import particlesim.core.Vector3
import particlesim.examples.SPARKS_DT
import particlesim.examples.SparksScenario
import particlesim.examples.buildSparks
import particlesim.physics.Integrator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §9.5's checkpoint format, proved against sparks (Phase 6's emitter/destruction scenario) —
 * the one worked example with a dynamic particle count, an emitter with its own RNG
 * sub-stream, and a spawn-rate accumulator to round-trip. The core claim under test: resuming
 * from a checkpoint produces a continuation *indistinguishable* from an uninterrupted run —
 * proved by running the same scenario two ways from the same seed (straight through, vs.
 * checkpoint-and-resume partway through) and asserting the final states match exactly, not
 * just approximately.
 */
class CheckpointTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("checkpoint-test", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun stepOnce(scenario: SparksScenario, integrator: Integrator, t: Double, dt: Double) {
        // integrate -> destroy -> emit, matching SparksDebugDemo/SparksStabilityTest's
        // canonical ordering (a particle spawned this step shouldn't be destroy-eligible
        // before it's ever been integrated once).
        integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, dt)
        scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, dt)
        scenario.emitter.update(scenario.store, scenario.groups, t, dt)
    }

    private fun finalState(scenario: SparksScenario): Map<Int, Pair<Vector3, Vector3>> =
        scenario.store.liveIds().associateWith { id -> scenario.store.position(id) to scenario.store.velocity(id) }

    @Test
    fun `resuming from a checkpoint matches an uninterrupted run bit-for-bit`() {
        val masterSeed = 7L
        val checkpointAtStep = 1000
        val extraSteps = 500

        // Reference: one uninterrupted run all the way through.
        val reference = buildSparks(masterSeed)
        val referenceIntegrator = Integrator()
        var t = 0.0
        repeat(checkpointAtStep + extraSteps) {
            stepOnce(reference, referenceIntegrator, t, SPARKS_DT)
            t += SPARKS_DT
        }
        val referenceFinal = finalState(reference)
        // Sanity: the scenario actually exercised spawn *and* destroy in this window, not a
        // degenerate run — otherwise this test would "pass" without proving anything.
        assertTrue(reference.store.nextIdValue > referenceFinal.size, "expected more particles to have ever spawned than are currently alive")
        assertTrue(referenceFinal.isNotEmpty(), "expected at least one live particle at the end")

        // Run up to the checkpoint, snapshot it, and write/read it through real files.
        val checkpointed = buildSparks(masterSeed)
        val checkpointIntegrator = Integrator()
        var tc = 0.0
        repeat(checkpointAtStep) {
            stepOnce(checkpointed, checkpointIntegrator, tc, SPARKS_DT)
            tc += SPARKS_DT
        }
        val checkpoint = captureCheckpoint(
            store = checkpointed.store,
            groups = checkpointed.groups,
            groupNames = listOf("sparks"),
            emitters = listOf(checkpointed.emitter),
            brokenConnections = emptySet(), // sparks has no PairwiseForce to break
            t = tc,
            step = checkpointAtStep.toLong(),
        )
        val dir = tempDir()
        val basePath = File(dir, "checkpoint-0001")
        CheckpointWriter.write(checkpoint, basePath)
        val reloaded = CheckpointReader.read(basePath)

        // Resume onto a completely fresh scenario shell (static definition rebuilt from
        // scratch; only its dynamic state comes from the checkpoint) and continue stepping.
        val resumed = buildSparks(masterSeed)
        applyCheckpoint(resumed.store, resumed.groups, mapOf("fountain" to resumed.emitter), reloaded)
        val resumedIntegrator = Integrator()
        var tr = reloaded.t
        repeat(extraSteps) {
            stepOnce(resumed, resumedIntegrator, tr, SPARKS_DT)
            tr += SPARKS_DT
        }
        val resumedFinal = finalState(resumed)

        assertEquals(referenceFinal.keys, resumedFinal.keys, "live particle ids diverged after resume")
        for (id in referenceFinal.keys) {
            assertEquals(referenceFinal.getValue(id), resumedFinal.getValue(id), "particle $id diverged after resume")
        }
    }
}
