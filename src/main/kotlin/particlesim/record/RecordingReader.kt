package particlesim.record

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import particlesim.core.Vector3
import java.io.File
import java.io.FileInputStream

/**
 * Reads back a directory of shards written by [RecordingWriter]. [readFrame] supports true
 * random access to any frame in any shard — the reason §9.2 picked the Arrow IPC *File*
 * format over its streaming variant, which only supports sequential reads: the file's footer
 * carries a block index, so a target batch can be loaded directly without reading anything
 * before it.
 */
class RecordingReader(
    private val directory: File,
    allocator: BufferAllocator? = null,
) : AutoCloseable {

    private val ownsAllocator = allocator == null
    private val allocator: BufferAllocator = allocator ?: RootAllocator(Long.MAX_VALUE)

    /** Number of shard files present, in index order. A missing shard index ends the count
     * rather than being skipped over — a real run never produces gaps, so treating one as
     * "no more shards" is simpler than reconciling a sparse sequence. */
    fun shardCount(): Int {
        var count = 0
        while (File(directory, RecordingWriter.shardFileName(count)).exists()) count++
        return count
    }

    fun framesInShard(shardIndex: Int): Int =
        withReader(shardIndex) { reader -> reader.recordBlocks.size }

    fun readFrame(shardIndex: Int, frameIndexInShard: Int): RecordedFrame =
        withReader(shardIndex) { reader ->
            val blocks = reader.recordBlocks
            require(frameIndexInShard in blocks.indices) {
                "shard $shardIndex has ${blocks.size} frames, requested index $frameIndexInShard"
            }
            reader.loadRecordBatch(blocks[frameIndexInShard])
            frameFromRoot(reader.vectorSchemaRoot)
        }

    /** Reads every frame from every shard, in order. Convenience for tests and small runs —
     * a real batch-scale consumer would call [readFrame] shard-by-shard instead of holding
     * every frame in memory at once. */
    fun readAllFrames(): List<RecordedFrame> {
        val frames = ArrayList<RecordedFrame>()
        for (shardIndex in 0 until shardCount()) {
            withReader(shardIndex) { reader ->
                for (block in reader.recordBlocks) {
                    reader.loadRecordBatch(block)
                    frames += frameFromRoot(reader.vectorSchemaRoot)
                }
            }
        }
        return frames
    }

    private fun <T> withReader(shardIndex: Int, block: (ArrowFileReader) -> T): T {
        val file = File(directory, RecordingWriter.shardFileName(shardIndex))
        require(file.exists()) { "no such shard: $shardIndex (looked for ${file.path})" }
        FileInputStream(file).use { input ->
            ArrowFileReader(SeekableReadChannel(input.channel), allocator).use { reader ->
                val version = reader.vectorSchemaRoot.schema.customMetadata["particlesim.format_version"]
                require(version == RecordingSchema.FORMAT_VERSION) {
                    "shard $shardIndex has format version $version, reader expects ${RecordingSchema.FORMAT_VERSION}"
                }
                return block(reader)
            }
        }
    }

    private fun frameFromRoot(root: VectorSchemaRoot): RecordedFrame {
        val n = root.rowCount
        if (n == 0) return RecordedFrame(step = 0L, t = 0.0, particles = emptyList())

        val stepVec = root.getVector(RecordingSchema.STEP.name) as BigIntVector
        val tVec = root.getVector(RecordingSchema.T.name) as Float8Vector
        val idVec = root.getVector(RecordingSchema.ID.name) as IntVector
        val pxVec = root.getVector(RecordingSchema.PX.name) as Float8Vector
        val pyVec = root.getVector(RecordingSchema.PY.name) as Float8Vector
        val pzVec = root.getVector(RecordingSchema.PZ.name) as Float8Vector
        val vxVec = root.getVector(RecordingSchema.VX.name) as Float8Vector
        val vyVec = root.getVector(RecordingSchema.VY.name) as Float8Vector
        val vzVec = root.getVector(RecordingSchema.VZ.name) as Float8Vector

        val particles = (0 until n).map { row ->
            RecordedParticle(
                id = idVec.get(row),
                position = Vector3(pxVec.get(row), pyVec.get(row), pzVec.get(row)),
                velocity = Vector3(vxVec.get(row), vyVec.get(row), vzVec.get(row)),
            )
        }
        return RecordedFrame(step = stepVec.get(0), t = tVec.get(0), particles = particles)
    }

    override fun close() {
        if (ownsAllocator) allocator.close()
    }
}
