package particlesim.record

import particlesim.core.Vector3
import particlesim.examples.SPARKS_DT
import particlesim.examples.SparksScenario
import particlesim.examples.buildSparks
import particlesim.physics.Integrator
import particlesim.physics.PairwiseForce
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves §9.5's "a checkpoint is written at each recording shard boundary" as an actual wiring
 * between [RecordingWriter] and checkpointing, not just two mechanisms that happen to both
 * exist — [RecordingWriter.onShardComplete] fires a caller-supplied closure exactly at each
 * shard rollover, and this test uses it to write a real [Checkpoint] there, then proves the
 * *automatically-triggered* checkpoint resumes correctly, the same bit-for-bit standard
 * `CheckpointTest` already proved for a manually-chosen checkpoint moment.
 */
class RecordingCheckpointIntegrationTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("recording-checkpoint-test", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun finalState(scenario: SparksScenario): Map<Int, Pair<Vector3, Vector3>> =
        scenario.store.liveIds().associateWith { id -> scenario.store.position(id) to scenario.store.velocity(id) }

    @Test
    fun `a checkpoint taken automatically at a shard boundary resumes correctly`() {
        val masterSeed = 11L
        val framesPerShard = 500
        val totalSteps = 1500 // exactly 3 shards, so shard rollover and the run's end coincide

        // --- Reference: one uninterrupted run, no recording/checkpointing involved at all. ---
        val reference = buildSparks(masterSeed)
        val referenceIntegrator = Integrator()
        var tRef = 0.0
        repeat(totalSteps) {
            referenceIntegrator.step(reference.store, reference.groups, reference.forces, emptyList(), tRef, SPARKS_DT)
            reference.destruction.resolve(reference.store, reference.groups, reference.forces, tRef, SPARKS_DT)
            reference.emitter.update(reference.store, reference.groups, tRef, SPARKS_DT)
            tRef += SPARKS_DT
        }
        val referenceFinal = finalState(reference)

        // --- Recorded run: same scenario, driven through RecordingWriter with a checkpoint
        // taken via onShardComplete at every shard boundary (3 checkpoints expected). ---
        val dir = tempDir()
        val scenario = buildSparks(masterSeed)
        val integrator = Integrator()
        val brokenConnections = mutableSetOf<Pair<Int, Int>>() // sparks has none, but wired for real
        val checkpointsWritten = mutableListOf<Int>()

        RecordingWriter(
            directory = dir,
            framesPerShard = framesPerShard,
            onShardComplete = { shardIndex, t, step ->
                val checkpoint = captureCheckpoint(
                    store = scenario.store,
                    groups = scenario.groups,
                    groupNames = listOf("sparks"),
                    emitters = listOf(scenario.emitter),
                    brokenConnections = brokenConnections.toSet(),
                    t = t,
                    step = step,
                )
                CheckpointWriter.write(checkpoint, File(dir, "checkpoint-%05d".format(shardIndex)))
                checkpointsWritten += shardIndex
            },
        ).use { writer ->
            var t = 0.0
            repeat(totalSteps) { i ->
                val stepResult = integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, SPARKS_DT)
                val destructionResult = scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, SPARKS_DT)
                brokenConnections += (stepResult.brokenForces + destructionResult.danglingForces)
                    .filterIsInstance<PairwiseForce>()
                    .map { it.particleA to it.particleB }
                scenario.emitter.update(scenario.store, scenario.groups, t, SPARKS_DT)

                writer.writeFrame(scenario.store, t, i.toLong())
                t += SPARKS_DT
            }
        }

        assertEquals(listOf(0, 1, 2), checkpointsWritten, "expected exactly 3 shard-boundary checkpoints")

        // --- Resume from the *last* automatically-taken checkpoint onto a completely fresh
        // scenario shell, and confirm it already matches the reference (0 more steps needed,
        // since the last checkpoint coincides with the run's end at exactly 1500 steps). ---
        val lastCheckpoint = CheckpointReader.read(File(dir, "checkpoint-00002"))
        // Frames are written with a 0-based loop index, so the 1500th (final) frame is
        // written as step 1499, not 1500 — the checkpoint's own t/step label reflects that.
        assertEquals((totalSteps - 1).toDouble() * SPARKS_DT, lastCheckpoint.t, 1e-9)

        val resumed = buildSparks(masterSeed)
        applyCheckpoint(resumed.store, resumed.groups, mapOf("fountain" to resumed.emitter), lastCheckpoint)
        val resumedFinal = finalState(resumed)

        assertEquals(referenceFinal.keys, resumedFinal.keys, "live particle ids diverged")
        for (id in referenceFinal.keys) {
            assertEquals(referenceFinal.getValue(id), resumedFinal.getValue(id), "particle $id diverged")
        }
        assertTrue(referenceFinal.isNotEmpty(), "expected at least one live particle at the end")
    }
}
