package particlesim.record

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

/**
 * Columnar schema for a checkpoint's bulk per-particle state (§9.5) — one Arrow record batch,
 * one row per particle alive at the checkpoint moment. Reuses the same Arrow IPC tooling
 * [RecordingSchema] does, but is a *different* schema: a checkpoint additionally carries
 * radius/spawnTime/lifetime (needed to fully reconstruct a particle, not just play it back)
 * and mass (see [particlesim.core.ParticleStore.restoreParticle]'s doc comment for why this
 * deviates from §9.5's own "mass not captured" list). Non-columnar scene metadata (t, step,
 * nextId, group membership, broken connections, emitter state) lives in a separate YAML
 * sidecar — [CheckpointWriter]/[CheckpointReader] — rather than being force-fit into more
 * Arrow columns.
 */
internal object CheckpointSchema {
    private fun float64(name: String) =
        Field(name, FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null)

    val ID: Field = Field("id", FieldType.notNullable(ArrowType.Int(32, true)), null)
    val PX: Field = float64("px")
    val PY: Field = float64("py")
    val PZ: Field = float64("pz")
    val VX: Field = float64("vx")
    val VY: Field = float64("vy")
    val VZ: Field = float64("vz")
    val MASS: Field = float64("mass")
    val RADIUS: Field = float64("radius") // NaN = unset
    val SPAWN_TIME: Field = float64("spawn_time")
    val LIFETIME: Field = float64("lifetime") // NaN = unset

    const val FORMAT_VERSION = "1"

    val SCHEMA = Schema(
        listOf(ID, PX, PY, PZ, VX, VY, VZ, MASS, RADIUS, SPAWN_TIME, LIFETIME),
        mapOf("particlesim.format_version" to FORMAT_VERSION),
    )
}
