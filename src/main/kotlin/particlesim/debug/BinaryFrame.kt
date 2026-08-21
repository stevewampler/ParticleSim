package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.render.CameraPose
import particlesim.render.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary per-frame encoding (§9.1: "a WebSocket using a compact binary framing... since
 * particle state is high-frequency and the bandwidth/parse cost would otherwise bite at large
 * N and in drag-interaction latency"). Same logical content as [DebugFrame]'s JSON text (t,
 * step, particles, connections, optional camera), packed into a fixed-layout little-endian
 * buffer instead of parsed/serialized JSON — this is what the real viewer (this sub-pass)
 * consumes; [DebugFrame] stays as-is for now since nothing else depends on swapping it out.
 *
 * Layout (little-endian throughout):
 * ```
 * f64  t
 * i64  step
 * i32  particleCount
 * particleCount * { i32 id, f64 x, f64 y, f64 z }
 * i32  connectionCount
 * connectionCount * { i32 a, i32 b, f64 r, f64 g, f64 b }
 * u8   hasCamera (0 or 1)
 * if hasCamera: 9x f64 (position.xyz, lookAt.xyz, up.xyz)
 * ```
 *
 * Every connection carries a color rather than an optional/sparse one — §10.2's `breakProximity`
 * line coloring is the exception, not the rule (most connections are never individually
 * declared a renderer), so encoding always resolves a color per connection, defaulting to
 * [Color.DEFAULT_LINE] via [lineColors] when nothing overrides it — simpler for both this
 * encoder and the client than a second, optional color channel alongside a plain one.
 */
object BinaryFrame {
    private const val HEADER_SIZE = 8 + 8 + 4 // t, step, particleCount
    private const val PARTICLE_SIZE = 4 + 8 + 8 + 8 // id, x, y, z
    private const val CONNECTION_HEADER_SIZE = 4 // connectionCount
    private const val CONNECTION_SIZE = 4 + 4 + 8 + 8 + 8 // a, b, r, g, b
    private const val CAMERA_FLAG_SIZE = 1
    private const val CAMERA_SIZE = 9 * 8 // position, lookAt, up

    fun encode(
        t: Double,
        step: Long,
        store: ParticleStore,
        ids: List<Int>,
        connections: List<Pair<Int, Int>>,
        camera: CameraPose? = null,
        lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
    ): ByteBuffer {
        val size = HEADER_SIZE + ids.size * PARTICLE_SIZE +
            CONNECTION_HEADER_SIZE + connections.size * CONNECTION_SIZE +
            CAMERA_FLAG_SIZE + (if (camera != null) CAMERA_SIZE else 0)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putDouble(t)
        buffer.putLong(step)
        buffer.putInt(ids.size)
        for (id in ids) {
            val p = store.position(id)
            buffer.putInt(id)
            buffer.putDouble(p.x); buffer.putDouble(p.y); buffer.putDouble(p.z)
        }
        buffer.putInt(connections.size)
        for (connection in connections) {
            val (a, b) = connection
            val color = lineColors[connection] ?: Color.DEFAULT_LINE
            buffer.putInt(a); buffer.putInt(b)
            buffer.putDouble(color.r); buffer.putDouble(color.g); buffer.putDouble(color.b)
        }
        if (camera != null) {
            buffer.put(1)
            putVector(buffer, camera.position)
            putVector(buffer, camera.lookAt)
            putVector(buffer, camera.up)
        } else {
            buffer.put(0)
        }

        buffer.flip()
        return buffer
    }

    /** Decodes a buffer written by [encode] — not needed by the JS client (which parses the
     * same layout directly via `DataView`), but real, tested infrastructure rather than
     * dead code: round-trip symmetry is exactly what proves the layout is self-consistent
     * (see `BinaryFrameTest`), and it's what a future JVM-side consumer (the `[stretch]`
     * native viewer, or tooling) would use. Never mutates [buffer]'s own position — operates
     * on a [ByteBuffer.duplicate], since a caller may still need to send the original.
     */
    fun decode(buffer: ByteBuffer): DecodedFrame {
        val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val t = buf.double
        val step = buf.long
        val particleCount = buf.int
        val particles = (0 until particleCount).map {
            val id = buf.int
            val x = buf.double
            val y = buf.double
            val z = buf.double
            DecodedParticle(id, Vector3(x, y, z))
        }
        val connectionCount = buf.int
        val connections = (0 until connectionCount).map {
            val a = buf.int
            val b = buf.int
            val color = Color(buf.double, buf.double, buf.double)
            DecodedConnection(a, b, color)
        }
        val hasCamera = buf.get().toInt() != 0
        val camera = if (hasCamera) {
            CameraPose(position = getVector(buf), lookAt = getVector(buf), up = getVector(buf))
        } else {
            null
        }
        return DecodedFrame(t, step, particles, connections, camera)
    }

    private fun putVector(buffer: ByteBuffer, v: Vector3) {
        buffer.putDouble(v.x); buffer.putDouble(v.y); buffer.putDouble(v.z)
    }

    private fun getVector(buffer: ByteBuffer): Vector3 = Vector3(buffer.double, buffer.double, buffer.double)
}

data class DecodedParticle(val id: Int, val position: Vector3)

data class DecodedConnection(val a: Int, val b: Int, val color: Color)

data class DecodedFrame(
    val t: Double,
    val step: Long,
    val particles: List<DecodedParticle>,
    val connections: List<DecodedConnection>,
    val camera: CameraPose?,
)
