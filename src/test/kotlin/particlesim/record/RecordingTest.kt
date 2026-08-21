package particlesim.record

import particlesim.examples.BALL_BOUNCE_DT
import particlesim.examples.buildBallBounce
import particlesim.physics.Integrator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §9.2's recording format, proved against ball-bounce (static particle count — the simplest
 * scenario that can prove the sharded Arrow IPC mechanism itself works, before a later
 * sub-pass proves the id-column/dynamic-population and checkpoint pieces against sparks,
 * which is the scenario that actually needs them).
 */
class RecordingTest {

    private fun tempDir(): File = createTempDirectory()

    private fun createTempDirectory(): File {
        val dir = File.createTempFile("recording-test", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun `recorded frames exactly match a live run`() {
        val dir = tempDir()
        val scenario = buildBallBounce()
        val integrator = Integrator()

        data class LiveFrame(val step: Long, val t: Double, val id: Int, val pos: particlesim.core.Vector3, val vel: particlesim.core.Vector3)
        val live = ArrayList<LiveFrame>()

        val steps = 300
        RecordingWriter(dir, framesPerShard = 50).use { writer ->
            var t = 0.0
            for (step in 0 until steps) {
                writer.writeFrame(scenario.store, t, step.toLong())
                live += LiveFrame(step.toLong(), t, scenario.ballId, scenario.store.position(scenario.ballId), scenario.store.velocity(scenario.ballId))

                integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, BALL_BOUNCE_DT)
                scenario.collisions.resolve(scenario.store, scenario.groups, t, BALL_BOUNCE_DT)
                t += BALL_BOUNCE_DT
            }
        }

        val recorded = RecordingReader(dir).readAllFrames()

        assertEquals(steps, recorded.size)
        for (i in 0 until steps) {
            val expected = live[i]
            val frame = recorded[i]
            assertEquals(expected.step, frame.step, "frame $i step")
            assertEquals(expected.t, frame.t, "frame $i t")
            assertEquals(1, frame.particles.size, "frame $i particle count")
            val p = frame.particles[0]
            assertEquals(expected.id, p.id, "frame $i id")
            assertEquals(expected.pos, p.position, "frame $i position")
            assertEquals(expected.vel, p.velocity, "frame $i velocity")
        }
    }

    @Test
    fun `shards split at exactly framesPerShard frames`() {
        val dir = tempDir()
        val scenario = buildBallBounce()

        RecordingWriter(dir, framesPerShard = 10).use { writer ->
            var t = 0.0
            repeat(25) { step ->
                writer.writeFrame(scenario.store, t, step.toLong())
                t += BALL_BOUNCE_DT
            }
        }

        val reader = RecordingReader(dir)
        assertEquals(3, reader.shardCount()) // 10 + 10 + 5
        assertEquals(10, reader.framesInShard(0))
        assertEquals(10, reader.framesInShard(1))
        assertEquals(5, reader.framesInShard(2))
    }

    @Test
    fun `readFrame supports random access into a later shard without reading earlier ones`() {
        val dir = tempDir()
        val scenario = buildBallBounce()
        val integrator = Integrator()

        val expectedPositions = ArrayList<particlesim.core.Vector3>()
        RecordingWriter(dir, framesPerShard = 10).use { writer ->
            var t = 0.0
            repeat(35) { step ->
                writer.writeFrame(scenario.store, t, step.toLong())
                expectedPositions += scenario.store.position(scenario.ballId)
                integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, BALL_BOUNCE_DT)
                scenario.collisions.resolve(scenario.store, scenario.groups, t, BALL_BOUNCE_DT)
                t += BALL_BOUNCE_DT
            }
        }

        // Frame 23 lives in shard 2 (frames 20-29), at index 3 within that shard.
        val frame = RecordingReader(dir).readFrame(shardIndex = 2, frameIndexInShard = 3)
        assertEquals(23L, frame.step)
        assertEquals(expectedPositions[23], frame.particles[0].position)
    }

    @Test
    fun `a shard that never had its footer written is unreadable, but every earlier shard still is`() {
        val dir = tempDir()
        val scenario = buildBallBounce()

        val writer = RecordingWriter(dir, framesPerShard = 10)
        var t = 0.0
        // Completes shard 0 (10 frames) and starts shard 1 with 3 more frames.
        repeat(13) { step ->
            writer.writeFrame(scenario.store, t, step.toLong())
            t += BALL_BOUNCE_DT
        }
        // Deliberately no writer.close() here — simulates a crash mid-shard-1, before its
        // footer is written. Shard 0 was already finalized when its frame count hit 10.

        val reader = RecordingReader(dir)
        assertEquals(10, reader.framesInShard(0)) // fully readable

        assertTrue(File(dir, RecordingWriter.shardFileName(1)).exists()) // bytes are on disk...
        assertFailsWith<Exception> { reader.framesInShard(1) } // ...but unreadable without a footer
    }

    @Test
    fun `a shard with a mismatched format version is rejected, not silently misread`() {
        val dir = tempDir()
        val scenario = buildBallBounce()
        RecordingWriter(dir, framesPerShard = 10).use { writer ->
            writer.writeFrame(scenario.store, 0.0, 0L)
        }

        // Confirms the version actually written round-trips correctly for a normal read...
        RecordingReader(dir).readAllFrames()

        // ...then overwrites shard 0 with the identical column layout but a different
        // declared format_version, and confirms a reader expecting the current version
        // refuses to interpret it rather than silently reading columns from a future format.
        val staleSchema = org.apache.arrow.vector.types.pojo.Schema(RecordingSchema.SCHEMA.fields, mapOf("particlesim.format_version" to "999"))
        val allocator = org.apache.arrow.memory.RootAllocator(Long.MAX_VALUE)
        org.apache.arrow.vector.VectorSchemaRoot.create(staleSchema, allocator).use { root ->
            val shardFile = File(dir, RecordingWriter.shardFileName(0))
            shardFile.outputStream().use { out ->
                org.apache.arrow.vector.ipc.ArrowFileWriter(root, null, java.nio.channels.Channels.newChannel(out)).use { w ->
                    w.start()
                    root.rowCount = 0
                    w.writeBatch()
                    w.end()
                }
            }
        }
        allocator.close()

        assertFailsWith<IllegalArgumentException> { RecordingReader(dir).framesInShard(0) }
    }
}
