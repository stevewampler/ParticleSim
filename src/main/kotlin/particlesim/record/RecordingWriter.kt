package particlesim.record

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import particlesim.core.ParticleStore
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels

/**
 * Writes a simulation run's per-frame particle state as a sequence of sharded Arrow IPC
 * File shards (§9.2): each shard is a complete, independently-readable `.arrow` file whose
 * footer is only written when the shard closes. That bounds crash loss to at most the shard
 * currently being written — every earlier shard is already finalized and safe to read, which
 * is the entire reason for sharding rather than one unbounded file (see
 * `RecordingCrashSafetyTest` for a test that actually exercises this property).
 *
 * Shard rollover happens purely on frame count ([framesPerShard]), never on file size or
 * wall-clock time — this is the same boundary §9.5 hangs checkpoints off of ("a checkpoint
 * is written at each recording shard boundary").
 */
class RecordingWriter(
    private val directory: File,
    private val framesPerShard: Int,
    allocator: BufferAllocator? = null,
) : AutoCloseable {

    init {
        require(framesPerShard > 0) { "framesPerShard must be positive, was $framesPerShard" }
        directory.mkdirs()
    }

    private val ownsAllocator = allocator == null
    private val allocator: BufferAllocator = allocator ?: RootAllocator(Long.MAX_VALUE)

    private var shardIndex = 0
    private var framesInCurrentShard = 0
    private var currentShard: OpenShard? = null

    private class OpenShard(val root: VectorSchemaRoot, val writer: ArrowFileWriter, val out: FileOutputStream)

    fun writeFrame(store: ParticleStore, t: Double, step: Long) {
        val shard = currentShard ?: openShard()

        val ids = store.liveIds()
        val n = ids.size
        val root = shard.root

        val stepVec = root.getVector(RecordingSchema.STEP.name) as BigIntVector
        val tVec = root.getVector(RecordingSchema.T.name) as Float8Vector
        val idVec = root.getVector(RecordingSchema.ID.name) as IntVector
        val pxVec = root.getVector(RecordingSchema.PX.name) as Float8Vector
        val pyVec = root.getVector(RecordingSchema.PY.name) as Float8Vector
        val pzVec = root.getVector(RecordingSchema.PZ.name) as Float8Vector
        val vxVec = root.getVector(RecordingSchema.VX.name) as Float8Vector
        val vyVec = root.getVector(RecordingSchema.VY.name) as Float8Vector
        val vzVec = root.getVector(RecordingSchema.VZ.name) as Float8Vector

        stepVec.allocateNew(n); tVec.allocateNew(n); idVec.allocateNew(n)
        pxVec.allocateNew(n); pyVec.allocateNew(n); pzVec.allocateNew(n)
        vxVec.allocateNew(n); vyVec.allocateNew(n); vzVec.allocateNew(n)

        for (row in 0 until n) {
            val id = ids[row]
            val pos = store.position(id)
            val vel = store.velocity(id)
            stepVec.set(row, step)
            tVec.set(row, t)
            idVec.set(row, id)
            pxVec.set(row, pos.x); pyVec.set(row, pos.y); pzVec.set(row, pos.z)
            vxVec.set(row, vel.x); vyVec.set(row, vel.y); vzVec.set(row, vel.z)
        }
        root.rowCount = n

        shard.writer.writeBatch()
        framesInCurrentShard++

        if (framesInCurrentShard >= framesPerShard) {
            closeCurrentShard()
        }
    }

    private fun openShard(): OpenShard {
        val file = File(directory, shardFileName(shardIndex))
        val root = VectorSchemaRoot.create(RecordingSchema.SCHEMA, allocator)
        val out = FileOutputStream(file)
        val writer = ArrowFileWriter(root, null, Channels.newChannel(out))
        writer.start()
        val shard = OpenShard(root, writer, out)
        currentShard = shard
        framesInCurrentShard = 0
        return shard
    }

    private fun closeCurrentShard() {
        val shard = currentShard ?: return
        shard.writer.end()
        shard.writer.close()
        shard.out.close()
        shard.root.close()
        currentShard = null
        shardIndex++
    }

    /** Finalizes the in-progress shard, if any, so it becomes readable, then releases the
     * allocator if this writer created its own (never if one was passed in — a caller-owned
     * allocator outlives individual writers, the same ownership model already established for
     * [particlesim.physics.Integrator]'s caller-owned executor). A shard with zero frames
     * written to it is never opened, so there's nothing to finalize in that case. */
    override fun close() {
        closeCurrentShard()
        if (ownsAllocator) allocator.close()
    }

    companion object {
        fun shardFileName(index: Int): String = "shard-%05d.arrow".format(index)
    }
}
