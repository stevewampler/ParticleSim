package particlesim.record

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.channels.Channels

/**
 * Writes a [Checkpoint] as a pair of files: `<basePath>.arrow` (bulk per-particle columnar
 * state, [CheckpointSchema]) and `<basePath>.yaml` (everything else — t, step, nextId, group
 * membership, broken connections, emitter state). Two files rather than cramming everything
 * into the Arrow file's schema-level metadata, since the sidecar content isn't fixed-shape
 * (a variable number of groups/emitters/broken pairs) the way Arrow's columnar model wants.
 */
object CheckpointWriter {

    fun write(checkpoint: Checkpoint, basePath: File, allocator: BufferAllocator? = null) {
        writeArrow(checkpoint, arrowFile(basePath), allocator)
        writeSidecar(checkpoint, sidecarFile(basePath))
    }

    fun arrowFile(basePath: File): File = File(basePath.parentFile, basePath.name + ".arrow")
    fun sidecarFile(basePath: File): File = File(basePath.parentFile, basePath.name + ".yaml")

    private fun writeArrow(checkpoint: Checkpoint, file: File, allocator: BufferAllocator?) {
        val ownsAllocator = allocator == null
        val alloc = allocator ?: RootAllocator(Long.MAX_VALUE)
        try {
            VectorSchemaRoot.create(CheckpointSchema.SCHEMA, alloc).use { root ->
                val n = checkpoint.particles.size
                val idVec = root.getVector(CheckpointSchema.ID.name) as IntVector
                val pxVec = root.getVector(CheckpointSchema.PX.name) as Float8Vector
                val pyVec = root.getVector(CheckpointSchema.PY.name) as Float8Vector
                val pzVec = root.getVector(CheckpointSchema.PZ.name) as Float8Vector
                val vxVec = root.getVector(CheckpointSchema.VX.name) as Float8Vector
                val vyVec = root.getVector(CheckpointSchema.VY.name) as Float8Vector
                val vzVec = root.getVector(CheckpointSchema.VZ.name) as Float8Vector
                val massVec = root.getVector(CheckpointSchema.MASS.name) as Float8Vector
                val radiusVec = root.getVector(CheckpointSchema.RADIUS.name) as Float8Vector
                val spawnVec = root.getVector(CheckpointSchema.SPAWN_TIME.name) as Float8Vector
                val lifeVec = root.getVector(CheckpointSchema.LIFETIME.name) as Float8Vector

                idVec.allocateNew(n)
                pxVec.allocateNew(n); pyVec.allocateNew(n); pzVec.allocateNew(n)
                vxVec.allocateNew(n); vyVec.allocateNew(n); vzVec.allocateNew(n)
                massVec.allocateNew(n); radiusVec.allocateNew(n); spawnVec.allocateNew(n); lifeVec.allocateNew(n)

                checkpoint.particles.forEachIndexed { row, p ->
                    idVec.set(row, p.id)
                    pxVec.set(row, p.position.x); pyVec.set(row, p.position.y); pzVec.set(row, p.position.z)
                    vxVec.set(row, p.velocity.x); vyVec.set(row, p.velocity.y); vzVec.set(row, p.velocity.z)
                    massVec.set(row, p.mass)
                    radiusVec.set(row, p.radius ?: Double.NaN)
                    spawnVec.set(row, p.spawnTime)
                    lifeVec.set(row, p.lifetime ?: Double.NaN)
                }
                root.rowCount = n

                file.outputStream().use { out ->
                    ArrowFileWriter(root, null, Channels.newChannel(out)).use { writer ->
                        writer.start()
                        writer.writeBatch()
                        writer.end()
                    }
                }
            }
        } finally {
            if (ownsAllocator) alloc.close()
        }
    }

    private fun writeSidecar(checkpoint: Checkpoint, file: File) {
        val data = linkedMapOf<String, Any>(
            "format_version" to CheckpointSchema.FORMAT_VERSION,
            "t" to checkpoint.t,
            "step" to checkpoint.step,
            "next_id" to checkpoint.nextId,
            "group_membership" to checkpoint.groupMembership.mapValues { (_, ids) -> ids },
            "broken_connections" to checkpoint.brokenConnections.map { (a, b) -> listOf(a, b) },
            "emitters" to checkpoint.emitters.map { e ->
                linkedMapOf<String, Any>(
                    "name" to e.name,
                    "accumulator" to e.accumulator,
                    "live_ids" to e.liveIds,
                    "at_cap" to e.atCap,
                    "rng_draw_count" to e.rngDrawCount,
                )
            },
        )
        file.writeText(Yaml().dump(data))
    }
}
