package particlesim.record

import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema

/**
 * Columnar per-frame schema for §9.2's recording format: one Arrow record batch per
 * simulation frame, one row per live particle. `step`/`t` are denormalized onto every row
 * of a frame's batch rather than stored once per batch via Arrow's lower-level per-batch
 * `appMetadata` API — a deliberate simplicity tradeoff (a few bytes of duplication per
 * particle, not per byte of the file) over the extra API surface the metadata route needs.
 * Known gap from this choice: a frame with zero live particles has no rows to carry
 * `step`/`t` on, so it loses its timestamp entirely (see [particlesim.record.RecordingReader]).
 * Ball-bounce (this sub-pass's proof scenario) never reaches zero particles, so this doesn't
 * bite here; it's deferred to whichever later sub-pass first needs a scenario that can.
 *
 * The `id` column exists even though ball-bounce's particle count never changes — positional
 * identity across frames can't be relied on in general once particle count is dynamic (§14),
 * and this schema is meant to serve every scenario, not just this one.
 */
internal object RecordingSchema {
    private fun float64(name: String) =
        Field(name, FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null)

    val STEP: Field = Field("step", FieldType.notNullable(ArrowType.Int(64, true)), null)
    val T: Field = float64("t")
    val ID: Field = Field("id", FieldType.notNullable(ArrowType.Int(32, true)), null)
    val PX: Field = float64("px")
    val PY: Field = float64("py")
    val PZ: Field = float64("pz")
    val VX: Field = float64("vx")
    val VY: Field = float64("vy")
    val VZ: Field = float64("vz")

    /** §9.2's format version field: embedded as Arrow schema-level metadata (carried in every
     * shard's footer) rather than a separate sidecar file, since it's a property of the
     * columnar schema itself — a reader that doesn't recognize the version can reject a shard
     * before ever trying to interpret its columns. */
    const val FORMAT_VERSION = "1"

    val SCHEMA = Schema(listOf(STEP, T, ID, PX, PY, PZ, VX, VY, VZ), mapOf("particlesim.format_version" to FORMAT_VERSION))
}
