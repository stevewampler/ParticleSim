package particlesim.debug

import particlesim.collision.BoxCollider
import particlesim.collision.Collider
import particlesim.collision.PlaneCollider
import particlesim.collision.SphereCollider
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.lifecycle.EmitterCapPolicy
import particlesim.render.ArrowSample
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.NamedArrowSamples
import particlesim.physics.EditableFields
import particlesim.physics.FieldValue
import particlesim.physics.Wind
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
 * particleCount * { i32 id, f64 x, f64 y, f64 z, f64 vx, f64 vy, f64 vz, f64 mass, f64 radius }
 *                   (radius is NaN when the particle has none — matches ParticleStore.radius's
 *                   own NaN-means-unset sentinel, no new encoding invented)
 * i32  connectionCount
 * connectionCount * { i32 a, i32 b, f64 r, f64 g, f64 b, i32 nameLen, nameLen UTF-8 bytes }
 *                     (nameLen 0 = this connection isn't tagged back to a named
 *                     Spring/Damper/MeshSprings — same "empty name never matches anything"
 *                     convention an unnamed mesh's own nameLen already uses)
 * u8   hasCamera (0 or 1); if set: 9x f64 (position.xyz, lookAt.xyz, up.xyz)
 * i32  sphereCount
 * sphereCount * { i32 id, f64 radius }
 * i32  meshCount
 * meshCount * { u8 wireframe, i32 nameLen, nameLen UTF-8 bytes, i32 triangleCount,
 *               triangleCount * { i32 a, i32 b, i32 c } }
 * i32  arrowGroupCount
 * arrowGroupCount * { i32 nameLen, nameLen UTF-8 bytes, i32 sampleCount,
 *                      sampleCount * { f64 ox, oy, oz, f64 vx, vy, vz } }
 * u8   hasVisibleIdsFilter (0 or 1); if set: i32 visibleCount, visibleCount * i32 id
 * i32  registryForceCount;      registryForceCount      * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  registryConstraintCount; registryConstraintCount * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  registrySurfaceCount;    registrySurfaceCount    * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  registryGroupCount;      registryGroupCount      * { i32 nameLen, nameLen UTF-8 bytes,
 *                                                            i32 memberCount, memberCount * i32 id }
 * i32  registryColliderCount;   registryColliderCount   * { i32 nameLen, nameLen UTF-8 bytes, u8 active }
 * i32  registryGroupEnabledCount; registryGroupEnabledCount * { i32 nameLen, nameLen UTF-8 bytes, u8 enabled }
 * i32  registryFieldCount;      registryFieldCount      * { u8 kind (0=force, 1=constraint),
 *                                                            i32 nameLen, nameLen UTF-8 bytes,
 *                                                            i32 fieldNameLen, fieldNameLen UTF-8 bytes,
 *                                                            u8 valueKind (0=scalar, 1=vector),
 *                                                            valueKind==0: f64 value
 *                                                            valueKind==1: f64 x, y, z }
 * i32  registryEmitterCount; registryEmitterCount * { i32 nameLen, nameLen UTF-8 bytes, f64 rate,
 *                                                       i32 rateSourceLen, rateSourceLen UTF-8 bytes,
 *                                                       i32 maxAlive, u8 evictOldest }
 * i32  registryWindCount; registryWindCount * { i32 nameLen, nameLen UTF-8 bytes,
 *                                                 f64 vx, vy, vz,
 *                                                 i32 velocitySourceLen, velocitySourceLen UTF-8 bytes }
 * i32  registryParticleExprCount; registryParticleExprCount * { i32 particleId,
 *                                                                 i32 fieldLen, fieldLen UTF-8 bytes,
 *                                                                 i32 sourceLen, sourceLen UTF-8 bytes }
 *                                  (§10.4's new "show the current expression source"
 *                                  requirement — one entry per particle whose mass/radius was
 *                                  last set from a parsed expression string; empty for every
 *                                  particle that never had one, which is the common case, and
 *                                  a `*SourceLen 0` above means the same for a named
 *                                  emitter/wind that never had one either, same "empty means
 *                                  absent" convention every other optional string in this
 *                                  format already uses)
 * i32  colliderCount
 * colliderCount * { u8 kind (0=plane, 1=sphere, 2=box), i32 nameLen, nameLen UTF-8 bytes,
 *                    f64 px, py, pz,
 *                    kind==0: f64 nx, ny, nz, f64 renderHalfSize
 *                    kind==1: f64 radius
 *                    kind==2: f64 hx, hy, hz }
 * i32  eventCount
 * eventCount * { u8 kind (0=forceBreak, 1=particleDestroyed, 2=particleSpawned),
 *                kind==0: i32 nameLen, nameLen UTF-8 bytes
 *                kind==1 or 2: i32 particleId }
 * i32  availableSceneCount; availableSceneCount * { i32 nameLen, nameLen UTF-8 bytes }
 * i32  activeSceneNameLen; activeSceneNameLen UTF-8 bytes
 *                           (§9.6's scene library — an empty list and an empty name for every
 *                           demo that isn't a `SceneLibrary`-backed runner, the same "absent
 *                           means empty" convention the rest of this format already uses rather
 *                           than a new sentinel)
 * ```
 *
 * The event section is §9.1's discrete-event channel ([SimEvent]) — everything above it in this
 * frame is continuous state (this instant's values); events are "something happened between the
 * previous frame and this one" and only appear the frame they happened, never resent. A caller
 * passing multiple physics steps' worth of events into one [encode] call (every demo's frame
 * covers `stepsPerFrame` steps, §9.1's pacing policy) is expected to have already collected them
 * across all of that frame's steps — this layer doesn't itself batch per-step, it just encodes
 * whatever list it's handed.
 *
 * The collider section is §10.2's "debug/`--render-all` mode... draws every collider as
 * wireframe" — [Collider] has no renderer-declaration equivalent of its own (unlike particles/
 * surfaces/forces), so this is unconditional, not opt-in: any [particlesim.collision.Collider]
 * a caller passes gets drawn, there's no way to reference one by name from a renderer
 * declaration the way §10.2 lets you target a group or surface. A plane is infinite, so the
 * viewer draws a finite quad centered on its position — [PLANE_RENDER_HALF_SIZE] sets that
 * quad's half-extent, sent over the wire (rather than duplicated as a second hardcoded
 * constant in the JS client) so there's exactly one source of truth for it; this is a debug
 * visualization choice, not a physical or DSL-exposed property of the collider itself.
 * [particlesim.debug.DebugRenderer.broadcast] filters this list to `active` colliders only
 * (§10.4) before it ever reaches [encode] — a deactivated collider is meant to be hidden as well
 * as inert, and hiding it here (rather than sending an `active` flag per entry and pushing the
 * skip into client JS) means the geometry payload for an inactive collider is never sent at all.
 * It still exists in the *registry* section below regardless of `active`, which is what the
 * outliner's reactivate toggle reads from.
 *
 * The registry section (§10.3's outliner prerequisite, [SceneRegistry]) carries names — plus,
 * for groups only, current member ids, since a group's §10.3 visibility toggle needs to know
 * *which particles* it hides, not just that the group exists (constraints/surfaces have no such
 * client-side toggle yet, so they stay name-only; a *named* force's arrow visibility is instead
 * keyed by the arrow-group section's own name field below, not this registry). Colliders are the
 * one other kind that carries a per-entry flag here: §10.4's activation toggle needs the outliner
 * to know a deactivated collider's current state even though (per the collider section below) it
 * no longer appears in the wireframe draw list while inactive — losing that name from *every*
 * per-frame section would strand the toggle with nothing to re-enable (an inactive collider still
 * needs a name to reach it via §10.4's write-back messages, same reasoning as a group's checkbox
 * needing to persist regardless of that group's own current membership). No other per-frame
 * numeric state (a force's live magnitude, a constraint's current target) is attached here yet —
 * that data either has a home elsewhere in this same frame (a named force's line/arrow renderer,
 * if it has one) or doesn't exist yet (a separate, later piece of §10.4). Sent unconditionally
 * (no `has`-flag, unlike `camera`/`visibleIds`)
 * because an absent registry and an empty one mean the same thing here, matching how
 * `sphereRadii`/`meshes`/`arrowSamples` already default to "present but zero-length" rather than
 * a nullable flag — every demo built before this defaults to an empty [SceneRegistry] and pays 4
 * zero-valued `i32`s per frame, not a new branch to skip. A mesh's `nameLen` is `0` (not a
 * `has`-flag either) when its [SurfaceRenderer.surface] is unnamed — an empty name never matches
 * anything in the outliner, so the two states collapse harmlessly into one.
 *
 * The field-value section (§10.4) is this frame's *read path* for live editing: a flattened
 * `(kind, name, field) -> value` list, one entry per [particlesim.physics.EditableFields] field
 * a named force or constraint currently exposes — not nested under the force/constraint name
 * lists above, since most forces/constraints expose none and a flat list of only the ones that
 * do is simpler to decode than a per-entity field count that's usually zero. This is a snapshot
 * of the *current* value, computed fresh every frame directly from the live object — never
 * cached — so an edit applied by one client shows up in every other connected client's next
 * frame without any extra invalidation logic.
 *
 * Every particle carries velocity alongside position — unconditionally, doubling the per-particle
 * payload from 28 to 52 bytes, not opt-in — so that §10.3's selection & inspection ("a particle's
 * position/velocity... live numeric readout") has something to read without a second wire
 * section keyed by id. Mass and radius ([ParticleStore.mass]/[ParticleStore.radius]) are the
 * identical case, one step further down §3's list of expression-capable per-particle fields —
 * added here rather than as a `(kind, name, field)` field-value entry like a force/constraint's,
 * since particles are id-addressed with no name and a selection-scoped per-client emission
 * (the alternative) would need per-connection frame customization this codebase has nowhere else
 * ([particlesim.debug.DebugRenderer.broadcast] sends one identical buffer to every connection).
 * Growing `PARTICLE_SIZE` from 52 to 68 bytes is the more expensive of this frame's per-particle
 * additions (it scales with every particle, not just a named group's members like the registry
 * section does), worth remembering if a future large-N scenario ever needs to trim frame size —
 * accepted for now since this is a debug tool talking to `localhost`, not a bandwidth-constrained
 * remote link. This is a *different* radius than the [sphereRadii] section below: that one is an
 * opt-in, author-declared render size (§10.2); this is the physics/collision radius. Editing one
 * doesn't move the other — a demo that passes an explicit `sphereRadii` override (e.g.
 * `FlagDebugDemo`'s pole spheres) will show no visible change when a particle's collision radius
 * is edited, an accepted gap, not a bug.
 *
 * [connectionNames] tags a connection back to the named [particlesim.physics.Spring]/
 * [particlesim.physics.Damper]/[particlesim.physics.MeshSprings] it belongs to — infrastructure
 * for a not-yet-built feature (a group's own tab surfacing "every spring/damper where all
 * endpoints belong to this group," see `todo/TODO.md`), not consumed by any panel yet. Keyed by
 * `(a, b)` exactly like [lineColors] already is, including the same limitation: two different
 * named forces sharing one connection pair (e.g. a `Spring` and a `Damper` between the same two
 * particles) collide in one map key, and whichever was inserted last wins — accepted here for
 * the same reason [lineColors] already accepts it (a connection is visually one line; today's
 * demos never actually name both a spring and a damper on the same pair, so this has never
 * mattered in practice, checked by inspection rather than assumed).
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
    private const val PARTICLE_SIZE = 4 + 8 + 8 + 8 + 8 + 8 + 8 + 8 + 8 // id, x, y, z, vx, vy, vz, mass, radius
    private const val CONNECTION_HEADER_SIZE = 4 // connectionCount
    private const val CONNECTION_FIXED_SIZE = 4 + 4 + 8 + 8 + 8 // a, b, r, g, b (nameLen+bytes is variable)
    private const val CAMERA_FLAG_SIZE = 1
    private const val CAMERA_SIZE = 9 * 8 // position, lookAt, up
    private const val SPHERE_HEADER_SIZE = 4
    private const val SPHERE_SIZE = 4 + 8 // id, radius
    private const val MESH_HEADER_SIZE = 4
    private const val MESH_ENTRY_HEADER_SIZE = 1 + 4 // wireframe, triangleCount
    private const val TRIANGLE_SIZE = 4 + 4 + 4 // a, b, c
    private const val ARROW_GROUP_HEADER_SIZE = 4 // arrowGroupCount
    private const val ARROW_GROUP_SAMPLE_COUNT_SIZE = 4
    private const val ARROW_SIZE = 8 * 6 // origin xyz, vector xyz
    private const val VISIBLE_FLAG_SIZE = 1
    private const val VISIBLE_HEADER_SIZE = 4
    private const val REGISTRY_LIST_HEADER_SIZE = 4 // count, once per kind
    private const val STRING_HEADER_SIZE = 4 // nameLen
    private const val COLLIDER_HEADER_SIZE = 4
    private const val COLLIDER_ENTRY_HEADER_SIZE = 1 + 24 // kind, position
    private const val PLANE_KIND: Byte = 0
    private const val SPHERE_KIND: Byte = 1
    private const val BOX_KIND: Byte = 2
    private const val EVENT_HEADER_SIZE = 4
    private const val EVENT_KIND_SIZE = 1
    private const val EVENT_PARTICLE_ID_SIZE = 4
    private const val FORCE_BREAK_KIND: Byte = 0
    private const val PARTICLE_DESTROYED_KIND: Byte = 1
    private const val PARTICLE_SPAWNED_KIND: Byte = 2
    private const val FORCE_KIND: Byte = 0
    private const val CONSTRAINT_KIND: Byte = 1
    private const val SCALAR_KIND: Byte = 0
    private const val VECTOR_KIND: Byte = 1

    /** Half-extent of the finite quad drawn for an (infinite) [PlaneCollider] — a debug-render
     * choice, not a modeled property; see this file's own doc comment. */
    const val PLANE_RENDER_HALF_SIZE = 3.0

    fun encode(
        t: Double,
        step: Long,
        store: ParticleStore,
        ids: List<Int>,
        connections: List<Pair<Int, Int>>,
        camera: CameraPose? = null,
        lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
        connectionNames: Map<Pair<Int, Int>, String> = emptyMap(),
        sphereRadii: Map<Int, Double> = emptyMap(),
        meshes: List<SurfaceRenderer> = emptyList(),
        arrowGroups: List<NamedArrowSamples> = emptyList(),
        visibleIds: Set<Int>? = null,
        registry: SceneRegistry = SceneRegistry.build(),
        colliders: List<Collider> = emptyList(),
        events: List<SimEvent> = emptyList(),
        availableScenes: List<String> = emptyList(),
        activeScene: String = "",
    ): ByteBuffer {
        val fieldEntries = collectEditableFields(registry)
        val emitterEntries = collectEmitterEntries(registry, t)
        val windEntries = collectWindEntries(registry, t)
        val particleExpressionEntries = collectParticleExpressionSources(store)
        val size = HEADER_SIZE + ids.size * PARTICLE_SIZE +
            CONNECTION_HEADER_SIZE + connections.sumOf { CONNECTION_FIXED_SIZE + stringSize(connectionNames[it] ?: "") } +
            CAMERA_FLAG_SIZE + (if (camera != null) CAMERA_SIZE else 0) +
            SPHERE_HEADER_SIZE + sphereRadii.size * SPHERE_SIZE +
            MESH_HEADER_SIZE + meshes.sumOf {
                MESH_ENTRY_HEADER_SIZE + stringSize(it.surface.name ?: "") + it.surface.triangles.size * TRIANGLE_SIZE
            } +
            ARROW_GROUP_HEADER_SIZE + arrowGroups.sumOf {
                stringSize(it.name) + ARROW_GROUP_SAMPLE_COUNT_SIZE + it.samples.size * ARROW_SIZE
            } +
            VISIBLE_FLAG_SIZE + (if (visibleIds != null) VISIBLE_HEADER_SIZE + visibleIds.size * 4 else 0) +
            nameListSize(registry.forces.keys) + nameListSize(registry.constraints.keys) +
            nameListSize(registry.surfaces.keys) + groupListSize(registry.groups) +
            boolNameListSize(registry.colliders.keys) + boolNameListSize(registry.groupEnabled.keys) +
            fieldEntryListSize(fieldEntries) +
            emitterEntryListSize(emitterEntries) +
            windEntryListSize(windEntries) +
            particleExpressionEntryListSize(particleExpressionEntries) +
            COLLIDER_HEADER_SIZE + colliders.sumOf { colliderEntrySize(it) } +
            EVENT_HEADER_SIZE + events.sumOf { eventEntrySize(it) } +
            nameListSize(availableScenes) + stringSize(activeScene)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putDouble(t)
        buffer.putLong(step)
        buffer.putInt(ids.size)
        for (id in ids) {
            val p = store.position(id)
            val v = store.velocity(id)
            buffer.putInt(id)
            buffer.putDouble(p.x); buffer.putDouble(p.y); buffer.putDouble(p.z)
            buffer.putDouble(v.x); buffer.putDouble(v.y); buffer.putDouble(v.z)
            buffer.putDouble(store.mass(id))
            buffer.putDouble(store.radius(id) ?: Double.NaN)
        }
        buffer.putInt(connections.size)
        for (connection in connections) {
            val (a, b) = connection
            val color = lineColors[connection] ?: Color.DEFAULT_LINE
            buffer.putInt(a); buffer.putInt(b)
            buffer.putDouble(color.r); buffer.putDouble(color.g); buffer.putDouble(color.b)
            putString(buffer, connectionNames[connection] ?: "")
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
            putString(buffer, mesh.surface.name ?: "")
            buffer.putInt(mesh.surface.triangles.size)
            for (tri in mesh.surface.triangles) {
                buffer.putInt(tri.a); buffer.putInt(tri.b); buffer.putInt(tri.c)
            }
        }
        buffer.putInt(arrowGroups.size)
        for (group in arrowGroups) {
            putString(buffer, group.name)
            buffer.putInt(group.samples.size)
            for (sample in group.samples) {
                putVector(buffer, sample.origin)
                putVector(buffer, sample.vector)
            }
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
        buffer.putInt(registry.groups.size)
        for ((name, memberIds) in registry.groups) {
            putString(buffer, name)
            buffer.putInt(memberIds.size)
            for (id in memberIds) buffer.putInt(id)
        }
        buffer.putInt(registry.colliders.size)
        for ((name, collider) in registry.colliders) {
            putString(buffer, name)
            buffer.put(if (collider.active) 1 else 0)
        }
        buffer.putInt(registry.groupEnabled.size)
        for ((name, enabled) in registry.groupEnabled) {
            putString(buffer, name)
            buffer.put(if (enabled) 1 else 0)
        }
        buffer.putInt(fieldEntries.size)
        for (entry in fieldEntries) {
            buffer.put(if (entry.kind == "force") FORCE_KIND else CONSTRAINT_KIND)
            putString(buffer, entry.name)
            putString(buffer, entry.field)
            when (val value = entry.value) {
                is FieldValue.Scalar -> { buffer.put(SCALAR_KIND); buffer.putDouble(value.value) }
                is FieldValue.Vector -> { buffer.put(VECTOR_KIND); putVector(buffer, value.value) }
            }
        }
        buffer.putInt(emitterEntries.size)
        for (entry in emitterEntries) {
            putString(buffer, entry.name)
            buffer.putDouble(entry.rate)
            putString(buffer, entry.rateSource ?: "")
            buffer.putInt(entry.maxAlive)
            buffer.put(if (entry.evictOldest) 1 else 0)
        }
        buffer.putInt(windEntries.size)
        for (entry in windEntries) {
            putString(buffer, entry.name)
            putVector(buffer, entry.velocity)
            putString(buffer, entry.velocitySource ?: "")
        }
        buffer.putInt(particleExpressionEntries.size)
        for (entry in particleExpressionEntries) {
            buffer.putInt(entry.particleId)
            putString(buffer, entry.field)
            putString(buffer, entry.source)
        }
        buffer.putInt(colliders.size)
        for (collider in colliders) {
            when (collider) {
                is PlaneCollider -> buffer.put(PLANE_KIND)
                is SphereCollider -> buffer.put(SPHERE_KIND)
                is BoxCollider -> buffer.put(BOX_KIND)
            }
            putString(buffer, collider.name ?: "")
            putVector(buffer, collider.position)
            when (collider) {
                is PlaneCollider -> { putVector(buffer, collider.unitNormal); buffer.putDouble(PLANE_RENDER_HALF_SIZE) }
                is SphereCollider -> buffer.putDouble(collider.radius)
                is BoxCollider -> putVector(buffer, collider.halfExtents)
            }
        }
        buffer.putInt(events.size)
        for (event in events) {
            when (event) {
                is SimEvent.ForceBreak -> { buffer.put(FORCE_BREAK_KIND); putString(buffer, event.name) }
                is SimEvent.ParticleDestroyed -> { buffer.put(PARTICLE_DESTROYED_KIND); buffer.putInt(event.particleId) }
                is SimEvent.ParticleSpawned -> { buffer.put(PARTICLE_SPAWNED_KIND); buffer.putInt(event.particleId) }
            }
        }
        putNameList(buffer, availableScenes)
        putString(buffer, activeScene)

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
            val vx = buf.double
            val vy = buf.double
            val vz = buf.double
            val mass = buf.double
            val radius = buf.double
            DecodedParticle(id, Vector3(x, y, z), Vector3(vx, vy, vz), mass, if (radius.isNaN()) null else radius)
        }
        val connectionCount = buf.int
        val connections = (0 until connectionCount).map {
            val a = buf.int
            val b = buf.int
            val color = Color(buf.double, buf.double, buf.double)
            val name = getString(buf)
            DecodedConnection(a, b, color, name.ifEmpty { null })
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
            val name = getString(buf)
            val triangleCount = buf.int
            val triangles = (0 until triangleCount).map { Triangle(buf.int, buf.int, buf.int) }
            DecodedMesh(wireframe, triangles, name)
        }
        val arrowGroupCount = buf.int
        val arrowGroups = (0 until arrowGroupCount).map {
            val name = getString(buf)
            val sampleCount = buf.int
            val samples = (0 until sampleCount).map { ArrowSample(origin = getVector(buf), vector = getVector(buf)) }
            DecodedArrowGroup(name, samples)
        }
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
            groups = getGroupList(buf),
            colliders = getColliderRegistryList(buf),
            groupEnabled = getBoolNameList(buf),
            fields = getFieldEntryList(buf),
            emitters = getEmitterEntryList(buf),
            winds = getWindEntryList(buf),
            particleExpressions = getParticleExpressionEntryList(buf),
        )
        val colliderCount = buf.int
        val colliders = (0 until colliderCount).map {
            val kind = buf.get()
            val name = getString(buf)
            val position = getVector(buf)
            when (kind) {
                PLANE_KIND -> DecodedCollider.Plane(name, position, normal = getVector(buf), renderHalfSize = buf.double)
                SPHERE_KIND -> DecodedCollider.Sphere(name, position, radius = buf.double)
                BOX_KIND -> DecodedCollider.Box(name, position, halfExtents = getVector(buf))
                else -> error("unknown collider kind byte: $kind")
            }
        }
        val eventCount = buf.int
        val events = (0 until eventCount).map {
            when (val kind = buf.get()) {
                FORCE_BREAK_KIND -> SimEvent.ForceBreak(getString(buf))
                PARTICLE_DESTROYED_KIND -> SimEvent.ParticleDestroyed(buf.int)
                PARTICLE_SPAWNED_KIND -> SimEvent.ParticleSpawned(buf.int)
                else -> error("unknown event kind byte: $kind")
            }
        }
        val availableScenes = getNameList(buf)
        val activeScene = getString(buf)
        return DecodedFrame(
            t, step, particles, connections, camera, spheres, meshes, arrowGroups, visibleIds, registry, colliders, events,
            availableScenes, activeScene,
        )
    }

    private fun putVector(buffer: ByteBuffer, v: Vector3) {
        buffer.putDouble(v.x); buffer.putDouble(v.y); buffer.putDouble(v.z)
    }

    private fun getVector(buffer: ByteBuffer): Vector3 = Vector3(buffer.double, buffer.double, buffer.double)

    private fun stringSize(s: String) = STRING_HEADER_SIZE + s.toByteArray(StandardCharsets.UTF_8).size

    private fun nameListSize(names: Collection<String>) =
        REGISTRY_LIST_HEADER_SIZE + names.sumOf { stringSize(it) }

    private fun groupListSize(groups: Map<String, Set<Int>>) =
        REGISTRY_LIST_HEADER_SIZE + groups.entries.sumOf { (name, members) -> stringSize(name) + 4 + members.size * 4 }

    /** Size of a `{name, u8 bool}` list — [registry.colliders]'s `active` flag and
     * [registry.groupEnabled] share this shape (the size doesn't depend on the flag's value,
     * only the name), so one helper covers both instead of two near-identical ones. */
    private fun boolNameListSize(names: Collection<String>) =
        REGISTRY_LIST_HEADER_SIZE + names.sumOf { stringSize(it) + 1 }

    /** §10.4's read path, flattened: one entry per [EditableFields] field a named force or
     * constraint currently exposes. Recomputed fresh every [encode] call — see this file's own
     * doc comment on why that's deliberate, not a missed caching opportunity. */
    private fun collectEditableFields(registry: SceneRegistry): List<RegistryFieldEntry> {
        val entries = ArrayList<RegistryFieldEntry>()
        for ((name, force) in registry.forces) {
            if (force !is EditableFields) continue
            for ((field, value) in force.editableFields()) entries += RegistryFieldEntry("force", name, field, value)
        }
        for ((name, constraint) in registry.constraints) {
            if (constraint !is EditableFields) continue
            for ((field, value) in constraint.editableFields()) entries += RegistryFieldEntry("constraint", name, field, value)
        }
        return entries
    }

    private fun fieldEntryListSize(entries: List<RegistryFieldEntry>): Int =
        REGISTRY_LIST_HEADER_SIZE + entries.sumOf { entry ->
            1 + stringSize(entry.name) + stringSize(entry.field) + 1 + when (entry.value) {
                is FieldValue.Scalar -> 8
                is FieldValue.Vector -> 24
            }
        }

    /** §10.4's emitter read path: one entry per named [particlesim.lifecycle.Emitter] in the
     * registry (every emitter is named - see [SceneRegistry]'s own doc comment - so unlike
     * [collectEditableFields] there's no per-entity opt-in check here). `rate` is the live
     * evaluated number at this frame's [t]; `rateSource` (§10.4, new requirement) is the
     * expression string it was last set from, alongside it rather than instead of it. */
    private fun collectEmitterEntries(registry: SceneRegistry, t: Double): List<RegistryEmitterEntry> =
        registry.emitters.map { (name, emitter) ->
            RegistryEmitterEntry(
                name, emitter.currentRate(t), emitter.currentRateSource(), emitter.maxAlive,
                emitter.currentCapPolicy() == EmitterCapPolicy.EVICT_OLDEST,
            )
        }

    private fun emitterEntryListSize(entries: List<RegistryEmitterEntry>): Int =
        REGISTRY_LIST_HEADER_SIZE + entries.sumOf { stringSize(it.name) + 8 + stringSize(it.rateSource ?: "") + 4 + 1 }

    /** §10.4's `Wind.velocity` read path: one entry per named [Wind] force in the registry -
     * kept off the generic `fields`/[EditableFields] list the same reason [collectEmitterEntries]
     * keeps `rate` off it: the value is a live-evaluated expression, and [collectEditableFields]
     * has no `t` to evaluate one against. `velocity` is the live evaluated vector at this
     * frame's [t]; `velocitySource` (§10.4, new requirement) is the expression string it was
     * last set from. `density` is unaffected - it keeps traveling in the ordinary `fields` list
     * exactly as before. */
    private fun collectWindEntries(registry: SceneRegistry, t: Double): List<RegistryWindEntry> =
        registry.forces.entries.mapNotNull { (name, force) ->
            (force as? Wind)?.let { RegistryWindEntry(name, it.currentVelocity(t), it.currentVelocitySource()) }
        }

    private fun windEntryListSize(entries: List<RegistryWindEntry>): Int =
        REGISTRY_LIST_HEADER_SIZE + entries.sumOf { stringSize(it.name) + 24 + stringSize(it.velocitySource ?: "") }

    /** §10.4's new "show the current expression source" requirement, particle mass/radius's
     * read path - see [RegistryParticleExpressionEntry]'s own doc comment for why this list is
     * naturally sparse rather than one entry per live particle. */
    private fun collectParticleExpressionSources(store: ParticleStore): List<RegistryParticleExpressionEntry> {
        val entries = ArrayList<RegistryParticleExpressionEntry>()
        for ((id, source) in store.massSources()) entries += RegistryParticleExpressionEntry(id, "mass", source)
        for ((id, source) in store.radiusSources()) entries += RegistryParticleExpressionEntry(id, "radius", source)
        return entries
    }

    private fun particleExpressionEntryListSize(entries: List<RegistryParticleExpressionEntry>): Int =
        REGISTRY_LIST_HEADER_SIZE + entries.sumOf { 4 + stringSize(it.field) + stringSize(it.source) }

    private fun colliderEntrySize(collider: Collider): Int {
        val shapeSize = when (collider) {
            is PlaneCollider -> 24 + 8
            is SphereCollider -> 8
            is BoxCollider -> 24
        }
        return COLLIDER_ENTRY_HEADER_SIZE + stringSize(collider.name ?: "") + shapeSize
    }

    private fun eventEntrySize(event: SimEvent): Int = EVENT_KIND_SIZE + when (event) {
        is SimEvent.ForceBreak -> stringSize(event.name)
        is SimEvent.ParticleDestroyed -> EVENT_PARTICLE_ID_SIZE
        is SimEvent.ParticleSpawned -> EVENT_PARTICLE_ID_SIZE
    }

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

    private fun getGroupList(buffer: ByteBuffer): List<DecodedGroupEntry> {
        val count = buffer.int
        return (0 until count).map {
            val name = getString(buffer)
            val memberCount = buffer.int
            val memberIds = (0 until memberCount).map { buffer.int }.toSet()
            DecodedGroupEntry(name, memberIds)
        }
    }

    private fun getColliderRegistryList(buffer: ByteBuffer): List<DecodedColliderEntry> {
        val count = buffer.int
        return (0 until count).map {
            val name = getString(buffer)
            val active = buffer.get().toInt() != 0
            DecodedColliderEntry(name, active)
        }
    }

    /** Decodes a `{name, u8 bool}` list — see [boolNameListSize] for why colliders' `active`
     * flag has its own typed decoder ([getColliderRegistryList]) while [registry.groupEnabled]
     * (no other per-entry data to carry) uses this generic one instead. */
    private fun getFieldEntryList(buffer: ByteBuffer): List<DecodedFieldEntry> {
        val count = buffer.int
        return (0 until count).map {
            val kind = if (buffer.get() == FORCE_KIND) "force" else "constraint"
            val name = getString(buffer)
            val field = getString(buffer)
            val value = if (buffer.get() == SCALAR_KIND) FieldValue.Scalar(buffer.double) else FieldValue.Vector(getVector(buffer))
            DecodedFieldEntry(kind, name, field, value)
        }
    }

    private fun getEmitterEntryList(buffer: ByteBuffer): List<DecodedEmitterEntry> {
        val count = buffer.int
        return (0 until count).map {
            val name = getString(buffer)
            val rate = buffer.double
            val rateSource = getString(buffer).ifEmpty { null }
            val maxAlive = buffer.int
            val evictOldest = buffer.get().toInt() != 0
            DecodedEmitterEntry(name, rate, rateSource, maxAlive, evictOldest)
        }
    }

    private fun getWindEntryList(buffer: ByteBuffer): List<DecodedWindEntry> {
        val count = buffer.int
        return (0 until count).map {
            val name = getString(buffer)
            val velocity = getVector(buffer)
            val velocitySource = getString(buffer).ifEmpty { null }
            DecodedWindEntry(name, velocity, velocitySource)
        }
    }

    private fun getParticleExpressionEntryList(buffer: ByteBuffer): List<DecodedParticleExpressionEntry> {
        val count = buffer.int
        return (0 until count).map {
            val particleId = buffer.int
            val field = getString(buffer)
            val source = getString(buffer)
            DecodedParticleExpressionEntry(particleId, field, source)
        }
    }

    private fun getBoolNameList(buffer: ByteBuffer): Map<String, Boolean> {
        val count = buffer.int
        val result = LinkedHashMap<String, Boolean>(count)
        repeat(count) {
            val name = getString(buffer)
            val value = buffer.get().toInt() != 0
            result[name] = value
        }
        return result
    }
}

data class DecodedParticle(val id: Int, val position: Vector3, val velocity: Vector3, val mass: Double, val radius: Double?)

data class DecodedConnection(val a: Int, val b: Int, val color: Color, val forceName: String? = null)

data class DecodedSphere(val id: Int, val radius: Double)

/** [name] is `""` when the mesh's [particlesim.surface.Surface] is unnamed — see [BinaryFrame]'s
 * own doc comment for why that collapses with "no name" instead of using a separate flag. */
data class DecodedMesh(val wireframe: Boolean, val triangles: List<Triangle>, val name: String = "")

/** One named group and its current member ids (§10.3's group visibility toggle needs to know
 * which particles a group's checkbox actually hides). */
data class DecodedGroupEntry(val name: String, val memberIds: Set<Int>)

/** One force's arrow samples, decoded — see [particlesim.render.NamedArrowSamples] for why the
 * name travels alongside the samples instead of being a separate lookup. [name] is `""` for an
 * unnamed force, same convention as [DecodedMesh.name]. */
data class DecodedArrowGroup(val name: String, val samples: List<ArrowSample>)

/** A [particlesim.collision.Collider], decoded for §10.2's debug-render-all wireframe drawing
 * — see [BinaryFrame]'s own doc comment for why this is unconditional rather than opt-in like
 * every other renderer here. [name] is `""` for an unnamed collider, same convention as
 * [DecodedMesh.name]. */
sealed class DecodedCollider {
    abstract val name: String
    abstract val position: Vector3

    data class Plane(override val name: String, override val position: Vector3, val normal: Vector3, val renderHalfSize: Double) : DecodedCollider()
    data class Sphere(override val name: String, override val position: Vector3, val radius: Double) : DecodedCollider()
    data class Box(override val name: String, override val position: Vector3, val halfExtents: Vector3) : DecodedCollider()
}

/** One [particlesim.physics.EditableFields] field's current value, keyed by which named force
 * or constraint owns it — see [BinaryFrame]'s own doc comment on the field-value section for why
 * this is a flat list rather than nested under the owning entity. Used internally by [encode]
 * only (never decoded back into this type — the wire-facing counterpart is [DecodedFieldEntry]). */
private data class RegistryFieldEntry(val kind: String, val name: String, val field: String, val value: FieldValue)

/** [RegistryFieldEntry], decoded. [kind] is `"force"` or `"constraint"`. */
data class DecodedFieldEntry(val kind: String, val name: String, val field: String, val value: FieldValue)

/** One named [particlesim.lifecycle.Emitter]'s §10.4 read path - used internally by [encode]
 * only, mirroring [RegistryFieldEntry]'s split from its decoded counterpart. [rateSource] is
 * §10.4's new "show the current expression source" requirement - `null` (encoded as an empty
 * string, same "empty means absent" convention [DecodedMesh.name] already uses) when [rate]
 * wasn't set from a parsed expression string. */
private data class RegistryEmitterEntry(val name: String, val rate: Double, val rateSource: String?, val maxAlive: Int, val evictOldest: Boolean)

/** [RegistryEmitterEntry], decoded. `evictOldest == true` means
 * [particlesim.lifecycle.EmitterCapPolicy.EVICT_OLDEST], matching
 * [particlesim.debug.SceneControlMessage.SetEmitterCapPolicy]'s own convention. */
data class DecodedEmitterEntry(val name: String, val rate: Double, val rateSource: String?, val maxAlive: Int, val evictOldest: Boolean)

/** One named [Wind] force's §10.4 `velocity` read path - used internally by [encode] only,
 * mirroring [RegistryEmitterEntry]'s split from its decoded counterpart. [velocitySource] is
 * [RegistryEmitterEntry.rateSource]'s counterpart for [velocity]. */
private data class RegistryWindEntry(val name: String, val velocity: Vector3, val velocitySource: String?)

/** [RegistryWindEntry], decoded. */
data class DecodedWindEntry(val name: String, val velocity: Vector3, val velocitySource: String? = null)

/** One particle's mass or radius §10.4 expression-source read path - the id-addressed
 * counterpart to [RegistryEmitterEntry.rateSource]/[RegistryWindEntry.velocitySource]. Unlike
 * those two (one entry per named force, source always present in the list, empty string when
 * absent), this list only ever contains entries for particles that actually have a known source
 * ([particlesim.core.ParticleStore.massSources]/[particlesim.core.ParticleStore.radiusSources]
 * are already sparse) - naturally empty for a scene where mass/radius has never been live-edited
 * via an expression string, so this section costs nothing in the common case. [field] is
 * `"mass"` or `"radius"`. */
private data class RegistryParticleExpressionEntry(val particleId: Int, val field: String, val source: String)

/** [RegistryParticleExpressionEntry], decoded. */
data class DecodedParticleExpressionEntry(val particleId: Int, val field: String, val source: String)

/** One named collider's §10.4 activation state — kept in the registry (unlike the unconditional
 * wireframe [DecodedCollider] section) so an inactive collider's name is still reachable to
 * reactivate it, even though it's no longer drawn. */
data class DecodedColliderEntry(val name: String, val active: Boolean)

/** §10.3's outliner data — see [SceneRegistry] for what "named" means per kind and why groups
 * carry member ids while the other three kinds are plain name lists. */
data class DecodedRegistry(
    val forces: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val surfaces: List<String> = emptyList(),
    val groups: List<DecodedGroupEntry> = emptyList(),
    val colliders: List<DecodedColliderEntry> = emptyList(),
    val groupEnabled: Map<String, Boolean> = emptyMap(),
    val fields: List<DecodedFieldEntry> = emptyList(),
    val emitters: List<DecodedEmitterEntry> = emptyList(),
    val winds: List<DecodedWindEntry> = emptyList(),
    val particleExpressions: List<DecodedParticleExpressionEntry> = emptyList(),
)

data class DecodedFrame(
    val t: Double,
    val step: Long,
    val particles: List<DecodedParticle>,
    val connections: List<DecodedConnection>,
    val camera: CameraPose?,
    val spheres: List<DecodedSphere> = emptyList(),
    val meshes: List<DecodedMesh> = emptyList(),
    val arrowGroups: List<DecodedArrowGroup> = emptyList(),
    val visibleIds: Set<Int>? = null,
    val registry: DecodedRegistry = DecodedRegistry(),
    val colliders: List<DecodedCollider> = emptyList(),
    val events: List<SimEvent> = emptyList(),
    val availableScenes: List<String> = emptyList(),
    val activeScene: String = "",
)
