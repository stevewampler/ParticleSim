package particlesim.record

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.yaml.snakeyaml.Yaml
import particlesim.core.Vector3
import particlesim.lifecycle.EmitterCheckpointState
import java.io.File
import java.io.FileInputStream

/** Reads back a [Checkpoint] written by [CheckpointWriter]. */
object CheckpointReader {

    fun read(basePath: File, allocator: BufferAllocator? = null): Checkpoint {
        val particles = readArrow(CheckpointWriter.arrowFile(basePath), allocator)
        return readSidecar(CheckpointWriter.sidecarFile(basePath), particles)
    }

    private fun readArrow(file: File, allocator: BufferAllocator?): List<CheckpointParticle> {
        val ownsAllocator = allocator == null
        val alloc = allocator ?: RootAllocator(Long.MAX_VALUE)
        try {
            FileInputStream(file).use { input ->
                ArrowFileReader(SeekableReadChannel(input.channel), alloc).use { reader ->
                    val version = reader.vectorSchemaRoot.schema.customMetadata["particlesim.format_version"]
                    require(version == CheckpointSchema.FORMAT_VERSION) {
                        "checkpoint has format version $version, reader expects ${CheckpointSchema.FORMAT_VERSION}"
                    }
                    val blocks = reader.recordBlocks
                    require(blocks.size == 1) { "expected exactly one record batch in a checkpoint, found ${blocks.size}" }
                    reader.loadRecordBatch(blocks[0])
                    val root = reader.vectorSchemaRoot

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

                    return (0 until root.rowCount).map { row ->
                        CheckpointParticle(
                            id = idVec.get(row),
                            position = Vector3(pxVec.get(row), pyVec.get(row), pzVec.get(row)),
                            velocity = Vector3(vxVec.get(row), vyVec.get(row), vzVec.get(row)),
                            mass = massVec.get(row),
                            radius = radiusVec.get(row).let { if (it.isNaN()) null else it },
                            spawnTime = spawnVec.get(row),
                            lifetime = lifeVec.get(row).let { if (it.isNaN()) null else it },
                        )
                    }
                }
            }
        } finally {
            if (ownsAllocator) alloc.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSidecar(file: File, particles: List<CheckpointParticle>): Checkpoint {
        val data = Yaml().load<Map<String, Any?>>(file.readText())
            ?: throw IllegalArgumentException("empty checkpoint sidecar: ${file.path}")

        val version = data["format_version"]?.toString()
        require(version == CheckpointSchema.FORMAT_VERSION) {
            "checkpoint sidecar has format version $version, reader expects ${CheckpointSchema.FORMAT_VERSION}"
        }

        val groupMembership = (data["group_membership"] as? Map<String, List<Int>>).orEmpty()
        val brokenConnections = (data["broken_connections"] as? List<List<Int>>).orEmpty()
            .map { (a, b) -> a to b }
            .toSet()
        val emitters = (data["emitters"] as? List<Map<String, Any?>>).orEmpty().map { e ->
            EmitterCheckpointState(
                name = e["name"] as String,
                accumulator = (e["accumulator"] as Number).toDouble(),
                liveIds = (e["live_ids"] as List<Int>),
                atCap = e["at_cap"] as Boolean,
                rngDrawCount = (e["rng_draw_count"] as Number).toLong(),
            )
        }

        return Checkpoint(
            t = (data["t"] as Number).toDouble(),
            step = (data["step"] as Number).toLong(),
            nextId = (data["next_id"] as Number).toInt(),
            particles = particles,
            groupMembership = groupMembership,
            brokenConnections = brokenConnections,
            emitters = emitters,
        )
    }
}
