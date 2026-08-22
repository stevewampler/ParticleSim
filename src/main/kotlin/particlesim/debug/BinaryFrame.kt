package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.render.ArrowSample
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Triangle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary per-frame encoding (§9.1: "a WebSocket using a compact binary framing... since
 * particle state is high-frequency and the bandwidth/parse cost would otherwise bite at large
 * N and in drag-interaction latency"). Carries everything the viewer needs to draw a frame,
 * including the §10.2 renderer-declaration data (sphere radii, surface meshes, arrow samples,
 * per-line color) computed server-side — the client never evaluates a renderer declaration
 * itself, only draws whatever numbers it's sent.
 *
 * Layout (little-endian throughout):
 * ```
 * f64  t
 * i64  step
 * i32  particleCount
 * particleCount * { i32 id, f64 x, f64 y, f64 z }
 * i32  connectionCount
 * connectionCount * { i32 a, i32 b, f64 r, f64 g, f64 b }
 * u8   hasCamera (0 or 1); if set: 9x f64 (position.xyz, lookAt.xyz, up.xyz)
 * i32  sphereCount
 * sphereCount * { i32 id, f64 radius }
 * i32  meshCount
 * meshCount * { u8 wireframe, i32 triangleCount, triangleCount * { i32 a, i32 b, i32 c } }
 * i32  arrowCount
 * arrowCount * { f64 ox, oy, oz, f64 vx, vy, vz }
 * u8   hasVisibleIdsFilter (0 or 1); if set: i32 visibleCount, visibleCount * i32 id
 * i32  registryForceCount;      registryForceCount      * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  registryConstraintCount; registryConstraintCount * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  registrySurfaceCount;    registrySurfaceCount    * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  registryGroupCount;      registryGroupCount      * { i32 nameLen, nameLen UTF-8 bytes }
 * ```
 *
 * The registry section (§10.3's outliner prerequisite, [SceneRegistry]) carries *names only* —
 * one flat list per kind, no per-frame numeric state (a force's live magnitude, a constraint's
 * current target) attached. That data already has a home elsewhere in this same frame (a named
 * force's line/arrow renderer, if it has one) or doesn't exist yet (there's no per-object
 * inspection readout wired up yet — a separate, later piece of §10.3). Sent unconditionally
 * (no `has`-flag, unlike `camera`/`visibleIds`) because an absent registry and an empty one mean
 * the same thing here, matching how `sphereRadii`/`meshes`/`arrowSamples` already default to
 * "present but zero-length" rather than a nullable flag — every demo built before this defaults
 * to an empty [SceneRegistry] and pays 4 zero-valued `i32`s per frame, not a new branch to skip.
 *
 * [visibleIds], when supplied, is the *only* set of particles the viewer draws as a standalone
 * dot/sphere — every particle still travels in the main particle list (needed for connection
 * endpoints and mesh vertices regardless), but one with no renderer of its own (§10.2: "the
 * individual cloth particles have no renderer of their own — the mesh already shows them")
 * stays invisible as a dot. `null` (the default) draws every particle, unchanged from every
 * demo built before this — a real behavior change only for a caller that opts in.
 */
object BinaryFrame {
    private const val HEADER_SIZE = 8 + 8 + 4 // t, step, particleCount
    private const val PARTICLE_SIZE = 4 + 8 + 8 + 8 // id, x, y, z
    private const val CONNECTION_HEADER_SIZE = 4 // connectionCount
    private const val CONNECTION_SIZE = 4 + 4 + 8 + 8 + 8 // a, b, r, g, b
    private const val CAMERA_FLAG_SIZE = 1
    private const val CAMERA_SIZE = 9 * 8 // position, lookAt, up
    private const val SPHERE_HEADER_SIZE = 4
    private const val SPHERE_SIZE = 4 + 8 // id, radius
    private const val MESH_HEADER_SIZE = 4
    private const val MESH_ENTRY_HEADER_SIZE = 1 + 4 // wireframe, triangleCount
    private const val TRIANGLE_SIZE = 4 + 4 + 4 // a, b, c
    private const val ARROW_HEADER_SIZE = 4
    private const val ARROW_SIZE = 8 * 6 // origin xyz, vector xyz
    private const val VISIBLE_FLAG_SIZE = 1
    private const val VISIBLE_HEADER_SIZE = 4
    private const val REGISTRY_LIST_HEADER_SIZE = 4 // count, once per kind
    private const val STRING_HEADER_SIZE = 4 // nameLen

    fun encode(
        t: Double,
        step: Long,
        store: ParticleStore,
        ids: List<Int>,
        connections: List<Pair<Int, Int>>,
        camera: CameraPose? = null,
        lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
        sphereRadii: Map<Int, Double> = emptyMap(),
        meshes: List<SurfaceRenderer> = emptyList(),
        arrowSamples: List<ArrowSample> = emptyList(),
        visibleIds: Set<Int>? = null,
        registry: SceneRegistry = SceneRegistry.build(),
    ): ByteBuffer {
        val size = HEADER_SIZE + ids.size * PARTICLE_SIZE +
            CONNECTION_HEADER_SIZE + connections.size * CONNECTION_SIZE +
            CAMERA_FLAG_SIZE + (if (camera != null) CAMERA_SIZE else 0) +
            SPHERE_HEADER_SIZE + sphereRadii.size * SPHERE_SIZE +
            MESH_HEADER_SIZE + meshes.sumOf { MESH_ENTRY_HEADER_SIZE + it.surface.triangles.size * TRIANGLE_SIZE } +
            ARROW_HEADER_SIZE + arrowSamples.size * ARROW_SIZE +
            VISIBLE_FLAG_SIZE + (if (visibleIds != null) VISIBLE_HEADER_SIZE + visibleIds.size * 4 else 0) +
            nameListSize(registry.forces.keys) + nameListSize(registry.constraints.keys) +
            nameListSize(registry.surfaces.keys) + nameListSize(registry.groups)
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
        buffer.putInt(sphereRadii.size)
        for ((id, radius) in sphereRadii) {
            buffer.putInt(id); buffer.putDouble(radius)
        }
        buffer.putInt(meshes.size)
        for (mesh in meshes) {
            buffer.put(if (mesh.wireframe) 1 else 0)
            buffer.putInt(mesh.surface.triangles.size)
            for (tri in mesh.surface.triangles) {
                buffer.putInt(tri.a); buffer.putInt(tri.b); buffer.putInt(tri.c)
            }
        }
        buffer.putInt(arrowSamples.size)
        for (sample in arrowSamples) {
            putVector(buffer, sample.origin)
            putVector(buffer, sample.vector)
        }
        if (visibleIds != null) {
            buffer.put(1)
            buffer.putInt(visibleIds.size)
            for (id in visibleIds) buffer.putInt(id)
        } else {
            buffer.put(0)
        }
        putNameList(buffer, registry.forces.keys)
        putNameList(buffer, registry.constraints.keys)
        putNameList(buffer, registry.surfaces.keys)
        putNameList(buffer, registry.groups)

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
        val sphereCount = buf.int
        val spheres = (0 until sphereCount).map { DecodedSphere(buf.int, buf.double) }
        val meshCount = buf.int
        val meshes = (0 until meshCount).map {
            val wireframe = buf.get().toInt() != 0
            val triangleCount = buf.int
            val triangles = (0 until triangleCount).map { Triangle(buf.int, buf.int, buf.int) }
            DecodedMesh(wireframe, triangles)
        }
        val arrowCount = buf.int
        val arrows = (0 until arrowCount).map { ArrowSample(origin = getVector(buf), vector = getVector(buf)) }
        val hasVisibleIds = buf.get().toInt() != 0
        val visibleIds = if (hasVisibleIds) {
            val visibleCount = buf.int
            (0 until visibleCount).map { buf.int }.toSet()
        } else {
            null
        }
        val registry = DecodedRegistry(
            forces = getNameList(buf),
            constraints = getNameList(buf),
            surfaces = getNameList(buf),
            groups = getNameList(buf),
        )
        return DecodedFrame(t, step, particles, connections, camera, spheres, meshes, arrows, visibleIds, registry)
    }

    private fun putVector(buffer: ByteBuffer, v: Vector3) {
        buffer.putDouble(v.x); buffer.putDouble(v.y); buffer.putDouble(v.z)
    }

    private fun getVector(buffer: ByteBuffer): Vector3 = Vector3(buffer.double, buffer.double, buffer.double)

    private fun stringSize(s: String) = STRING_HEADER_SIZE + s.toByteArray(StandardCharsets.UTF_8).size

    private fun nameListSize(names: Collection<String>) =
        REGISTRY_LIST_HEADER_SIZE + names.sumOf { stringSize(it) }

    private fun putString(buffer: ByteBuffer, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        buffer.putInt(bytes.size)
        buffer.put(bytes)
    }

    private fun putNameList(buffer: ByteBuffer, names: Collection<String>) {
        buffer.putInt(names.size)
        for (name in names) putString(buffer, name)
    }

    private fun getString(buffer: ByteBuffer): String {
        val len = buffer.int
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun getNameList(buffer: ByteBuffer): List<String> {
        val count = buffer.int
        return (0 until count).map { getString(buffer) }
    }
}

data class DecodedParticle(val id: Int, val position: Vector3)

data class DecodedConnection(val a: Int, val b: Int, val color: Color)

data class DecodedSphere(val id: Int, val radius: Double)

data class DecodedMesh(val wireframe: Boolean, val triangles: List<Triangle>)

/** §10.3's outliner data — see [SceneRegistry] for what "named" means per kind and why groups
 * are a plain list rather than paired with anything else. */
data class DecodedRegistry(
    val forces: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val surfaces: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
)

data class DecodedFrame(
    val t: Double,
    val step: Long,
    val particles: List<DecodedParticle>,
    val connections: List<DecodedConnection>,
    val camera: CameraPose?,
    val spheres: List<DecodedSphere> = emptyList(),
    val meshes: List<DecodedMesh> = emptyList(),
    val arrows: List<ArrowSample> = emptyList(),
    val visibleIds: Set<Int>? = null,
    val registry: DecodedRegistry = DecodedRegistry(),
)
