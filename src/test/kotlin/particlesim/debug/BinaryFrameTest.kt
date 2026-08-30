package particlesim.debug

import particlesim.collision.BoxCollider
import particlesim.collision.PlaneCollider
import particlesim.collision.SphereCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.FixedPosition
import particlesim.physics.UniformGravity
import particlesim.render.ArrowSample
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §9.1's binary per-frame encoding — proved by round-trip (encode then decode) rather than
 * asserting a specific byte layout, since the layout itself isn't part of any external
 * contract yet (only this codebase's own encoder/decoder pair, and the JS client's parser,
 * need to agree on it). */
class BinaryFrameTest {

    @Test
    fun `round-trips particles and connections exactly`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(1.0, 2.0, 3.0))
        val b = store.create(position = Vector3(-1.5, 0.25, 100.0))

        val buffer = BinaryFrame.encode(t = 1.5, step = 42L, store = store, ids = listOf(a, b), connections = listOf(a to b))
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(1.5, decoded.t)
        assertEquals(42L, decoded.step)
        assertEquals(
            listOf(
                DecodedParticle(a, Vector3(1.0, 2.0, 3.0), Vector3.ZERO, mass = 1.0, radius = null),
                DecodedParticle(b, Vector3(-1.5, 0.25, 100.0), Vector3.ZERO, mass = 1.0, radius = null),
            ),
            decoded.particles,
            "neither particle was given a velocity -> Vector3.ZERO",
        )
        assertEquals(listOf(DecodedConnection(a, b, Color.DEFAULT_LINE)), decoded.connections, "no lineColors override -> the default line color")
        assertNull(decoded.camera)
    }

    @Test
    fun `a particle's velocity round-trips alongside its position`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO, velocity = Vector3(4.0, -5.0, 6.5))

        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = listOf(a), connections = emptyList())
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(Vector3(4.0, -5.0, 6.5), decoded.particles.single().velocity)
    }

    @Test
    fun `a particle's mass and radius round-trip, radius null when unset`() {
        val store = ParticleStore()
        val withRadius = store.create(mass = ScalarExpr.of(2.5), radius = ScalarExpr.of(0.75))
        val withoutRadius = store.create(mass = ScalarExpr.of(3.0))

        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = listOf(withRadius, withoutRadius), connections = emptyList())
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(2.5, decoded.particles[0].mass)
        assertEquals(0.75, decoded.particles[0].radius)
        assertEquals(3.0, decoded.particles[1].mass)
        assertNull(decoded.particles[1].radius)
    }

    @Test
    fun `round-trips an empty frame`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList())
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(0.0, decoded.t)
        assertEquals(0L, decoded.step)
        assertEquals(emptyList(), decoded.particles)
        assertEquals(emptyList(), decoded.connections)
        assertNull(decoded.camera)
    }

    @Test
    fun `round-trips a camera pose when present`() {
        val store = ParticleStore()
        val camera = CameraPose(position = Vector3(5.0, 6.0, 7.0), lookAt = Vector3(0.0, 1.0, 0.0), up = Vector3(0.0, 1.0, 0.0))
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), camera = camera)
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(camera, decoded.camera)
    }

    @Test
    fun `a lineColors override resolves to that color, other connections still get the default`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3.ZERO)
        val c = store.create(position = Vector3.ZERO)
        val red = Color(1.0, 0.0, 0.0)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = listOf(a, b, c),
            connections = listOf(a to b, b to c),
            lineColors = mapOf((a to b) to red),
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(
            listOf(DecodedConnection(a, b, red), DecodedConnection(b, c, Color.DEFAULT_LINE)),
            decoded.connections,
        )
    }

    @Test
    fun `a connectionNames entry tags that connection back to its owning force, other connections stay unnamed`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3.ZERO)
        val c = store.create(position = Vector3.ZERO)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = listOf(a, b, c),
            connections = listOf(a to b, b to c),
            connectionNames = mapOf((a to b) to "link-0"),
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(
            listOf(
                DecodedConnection(a, b, Color.DEFAULT_LINE, forceName = "link-0"),
                DecodedConnection(b, c, Color.DEFAULT_LINE, forceName = null),
            ),
            decoded.connections,
        )
    }

    @Test
    fun `round-trips sphere radii`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3.ZERO)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = listOf(a, b), connections = emptyList(),
            sphereRadii = mapOf(a to 0.5),
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(listOf(DecodedSphere(a, 0.5)), decoded.spheres, "only a has a declared radius")
    }

    @Test
    fun `round-trips meshes, including the wireframe flag`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3.ZERO)
        val c = store.create(position = Vector3.ZERO)
        val mesh = SurfaceRenderer(surface = Surface(listOf(Triangle(a, b, c))), wireframe = true)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = listOf(a, b, c), connections = emptyList(),
            meshes = listOf(mesh),
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(
            listOf(DecodedMesh(wireframe = true, triangles = listOf(Triangle(a, b, c)), name = "")),
            decoded.meshes,
            "an unnamed Surface decodes to an empty-string mesh name, not a missing field",
        )
    }

    @Test
    fun `a mesh's surface name round-trips, so the viewer can correlate a mesh back to its registry entry`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3.ZERO)
        val b = store.create(position = Vector3.ZERO)
        val c = store.create(position = Vector3.ZERO)
        val mesh = SurfaceRenderer(surface = Surface(listOf(Triangle(a, b, c)), name = "cloth-mesh"), wireframe = false)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = listOf(a, b, c), connections = emptyList(),
            meshes = listOf(mesh),
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals("cloth-mesh", decoded.meshes.single().name)
    }

    @Test
    fun `round-trips a named force's arrow samples`() {
        val store = ParticleStore()
        val samples = listOf(
            ArrowSample(origin = Vector3(1.0, 0.0, 0.0), vector = Vector3(0.0, 1.0, 0.0)),
            ArrowSample(origin = Vector3(2.0, 0.0, 0.0), vector = Vector3(0.0, 2.0, 0.0)),
        )

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(),
            arrowGroups = listOf(NamedArrowSamples("wind", samples)),
        )
        val decoded = BinaryFrame.decode(buffer)

        val group = decoded.arrowGroups.single()
        assertEquals("wind", group.name)
        assertEquals(samples, group.samples)
    }

    @Test
    fun `arrow groups are empty by default, and multiple groups (including an unnamed one) round-trip independently`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(),
        )
        assertEquals(emptyList(), BinaryFrame.decode(buffer).arrowGroups)

        val windSamples = listOf(ArrowSample(origin = Vector3.ZERO, vector = Vector3(1.0, 0.0, 0.0)))
        val unnamedSamples = listOf(ArrowSample(origin = Vector3(1.0, 1.0, 1.0), vector = Vector3(0.0, 0.0, 2.0)))
        val multiBuffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(),
            arrowGroups = listOf(NamedArrowSamples("wind", windSamples), NamedArrowSamples("", unnamedSamples)),
        )
        val decoded = BinaryFrame.decode(multiBuffer).arrowGroups
        assertEquals(2, decoded.size)
        assertEquals("wind", decoded[0].name)
        assertEquals(windSamples, decoded[0].samples)
        assertEquals("", decoded[1].name)
        assertEquals(unnamedSamples, decoded[1].samples)
    }

    @Test
    fun `visibleIds is null by default, meaning every demo built before this is unaffected`() {
        val store = ParticleStore()
        val id = store.create(position = Vector3.ZERO)
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = listOf(id), connections = emptyList())

        assertEquals(null, BinaryFrame.decode(buffer).visibleIds)
    }

    @Test
    fun `an explicit visibleIds set round-trips exactly`() {
        val store = ParticleStore()
        val cloth = store.create(position = Vector3.ZERO)
        val pole = store.create(position = Vector3.ZERO)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = listOf(cloth, pole), connections = emptyList(),
            visibleIds = setOf(pole),
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(setOf(pole), decoded.visibleIds, "only the pole particle should be marked visible as a standalone dot")
    }

    @Test
    fun `an empty registry round-trips as four empty lists, unchanged from every demo built before this`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList())

        val decoded = BinaryFrame.decode(buffer).registry
        assertEquals(DecodedRegistry(), decoded)
    }

    @Test
    fun `a populated registry round-trips names for every kind, unnamed entries excluded`() {
        val store = ParticleStore()
        val groups = Groups().apply { add("cloth", 1); add("pole", 2) }
        val registry = SceneRegistry.build(
            forces = listOf(UniformGravity("cloth", Vector3.ZERO, name = "gravity"), UniformGravity("cloth", Vector3.ZERO)),
            constraints = listOf(FixedPosition("pole", Vector3.ZERO, name = "pole-anchor")),
            surfaces = listOf(Surface(emptyList(), name = "cloth-mesh")),
            groups = groups,
        )

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), registry = registry,
        )
        val decoded = BinaryFrame.decode(buffer).registry

        assertEquals(listOf("gravity"), decoded.forces)
        assertEquals(listOf("pole-anchor"), decoded.constraints)
        assertEquals(listOf("cloth-mesh"), decoded.surfaces)
        assertEquals(listOf("cloth", "pole"), decoded.groups.map { it.name })
    }

    @Test
    fun `a group's current member ids round-trip alongside its name`() {
        val store = ParticleStore()
        val groups = Groups().apply { add("cloth", 1); add("cloth", 2); add("pole", 3) }
        val registry = SceneRegistry.build(groups = groups)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), registry = registry,
        )
        val decoded = BinaryFrame.decode(buffer).registry

        assertEquals(
            listOf(DecodedGroupEntry("cloth", setOf(1, 2)), DecodedGroupEntry("pole", setOf(3))),
            decoded.groups,
        )
    }

    @Test
    fun `registry names round-trip non-ASCII bytes exactly`() {
        val store = ParticleStore()
        val registry = SceneRegistry.build(surfaces = listOf(Surface(emptyList(), name = "flügel-mesh")))

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), registry = registry,
        )
        val decoded = BinaryFrame.decode(buffer).registry

        assertEquals(listOf("flügel-mesh"), decoded.surfaces)
    }

    @Test
    fun `a collider's name and active flag round-trip in the registry, regardless of the wireframe list`() {
        val store = ParticleStore()
        val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
        floor.active = false
        val registry = SceneRegistry.build(colliders = listOf(floor))

        // The wireframe list is what a caller like DebugRenderer.broadcast would already have
        // filtered to active-only - the registry section must still carry "floor" regardless.
        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(),
            registry = registry, colliders = emptyList(),
        )
        val decoded = BinaryFrame.decode(buffer).registry

        assertEquals(listOf(DecodedColliderEntry("floor", active = false)), decoded.colliders)
    }

    @Test
    fun `group enabled state round-trips in the registry, defaulting to true`() {
        val store = ParticleStore()
        val groups = Groups().apply { add("a", 1); add("b", 2) }
        groups.setEnabled("b", false)
        val registry = SceneRegistry.build(groups = groups)

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), registry = registry,
        )
        val decoded = BinaryFrame.decode(buffer).registry

        assertEquals(mapOf("a" to true, "b" to false), decoded.groupEnabled)
    }

    @Test
    fun `an EditableFields force's current values round-trip as a flat field-entry list`() {
        val store = ParticleStore()
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0), name = "gravity")
        val fixedVelocity = particlesim.physics.FixedVelocity("g", Vector3(1.0, 0.0, 0.0), name = "wind-hold")
        val registry = SceneRegistry.build(forces = listOf(gravity), constraints = listOf(fixedVelocity))

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), registry = registry,
        )
        val decoded = BinaryFrame.decode(buffer).registry

        assertEquals(
            setOf(
                DecodedFieldEntry("force", "gravity", "acceleration", particlesim.physics.FieldValue.Vector(Vector3(0.0, -9.8, 0.0))),
                DecodedFieldEntry("constraint", "wind-hold", "velocity", particlesim.physics.FieldValue.Vector(Vector3(1.0, 0.0, 0.0))),
            ),
            decoded.fields.toSet(),
        )
    }

    @Test
    fun `a force with no EditableFields contributes nothing to the field-entry list`() {
        val store = ParticleStore()
        val registry = SceneRegistry.build(forces = listOf(particlesim.physics.Drag("g", coefficient = 1.0, name = "drag")))

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), registry = registry,
        )
        assertEquals(emptyList(), BinaryFrame.decode(buffer).registry.fields)
    }

    @Test
    fun `round-trips a plane collider with its render half-size`() {
        val store = ParticleStore()
        val floor = PlaneCollider(VectorExpr.of(Vector3(0.0, 1.0, 0.0)), normal = Vector3(0.0, 1.0, 0.0), name = "floor")

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), colliders = listOf(floor),
        )
        val decoded = BinaryFrame.decode(buffer).colliders.single() as DecodedCollider.Plane

        assertEquals("floor", decoded.name)
        assertEquals(Vector3(0.0, 1.0, 0.0), decoded.position)
        assertEquals(Vector3(0.0, 1.0, 0.0), decoded.normal)
        assertEquals(BinaryFrame.PLANE_RENDER_HALF_SIZE, decoded.renderHalfSize)
    }

    @Test
    fun `round-trips a sphere and a box collider, unnamed`() {
        val store = ParticleStore()
        val sphere = SphereCollider(VectorExpr.of(Vector3(1.0, 2.0, 3.0)), radius = 0.5)
        val box = BoxCollider(VectorExpr.of(Vector3(-1.0, 0.0, 0.0)), halfExtents = Vector3(1.0, 2.0, 3.0))

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(), colliders = listOf(sphere, box),
        )
        val decoded = BinaryFrame.decode(buffer).colliders

        val decodedSphere = decoded[0] as DecodedCollider.Sphere
        assertEquals("", decodedSphere.name)
        assertEquals(Vector3(1.0, 2.0, 3.0), decodedSphere.position)
        assertEquals(0.5, decodedSphere.radius)

        val decodedBox = decoded[1] as DecodedCollider.Box
        assertEquals(Vector3(-1.0, 0.0, 0.0), decodedBox.position)
        assertEquals(Vector3(1.0, 2.0, 3.0), decodedBox.halfExtents)
    }

    @Test
    fun `no colliders passed round-trips to an empty list, not an error`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList())
        assertEquals(emptyList(), BinaryFrame.decode(buffer).colliders)
    }

    @Test
    fun `no events passed round-trips to an empty list, not an error`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList())
        assertEquals(emptyList(), BinaryFrame.decode(buffer).events)
    }

    @Test
    fun `all three event kinds round-trip, in order, alongside continuous frame data`() {
        val store = ParticleStore()
        val events = listOf(
            SimEvent.ParticleSpawned(7),
            SimEvent.ForceBreak("wind"),
            SimEvent.ParticleDestroyed(3),
            SimEvent.ForceBreak(""), // an unnamed force can still break - just not individually traceable
        )

        val buffer = BinaryFrame.encode(
            t = 1.5, step = 42L, store = store, ids = emptyList(), connections = emptyList(),
            events = events,
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(events, decoded.events)
        // The event section doesn't clobber or get clobbered by the rest of the frame.
        assertEquals(1.5, decoded.t)
        assertEquals(42L, decoded.step)
    }

    @Test
    fun `no scene library passed round-trips to an empty scene list and an empty active name`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList())
        val decoded = BinaryFrame.decode(buffer)
        assertEquals(emptyList(), decoded.availableScenes)
        assertEquals("", decoded.activeScene)
    }

    @Test
    fun `availableScenes and activeScene round-trip alongside the rest of the frame`() {
        val store = ParticleStore()
        val buffer = BinaryFrame.encode(
            t = 1.5, step = 42L, store = store, ids = emptyList(), connections = emptyList(),
            availableScenes = listOf("flag", "ballBounce", "trampoline", "sparks"),
            activeScene = "trampoline",
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(listOf("flag", "ballBounce", "trampoline", "sparks"), decoded.availableScenes)
        assertEquals("trampoline", decoded.activeScene)
        // The scene-library section doesn't clobber or get clobbered by the rest of the frame.
        assertEquals(1.5, decoded.t)
        assertEquals(42L, decoded.step)
    }

    @Test
    fun `decode does not consume the original buffer's position`() {
        val store = ParticleStore()
        val id = store.create(position = Vector3.ZERO)
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = listOf(id), connections = emptyList())

        val positionBefore = buffer.position()
        BinaryFrame.decode(buffer)
        assertEquals(positionBefore, buffer.position(), "decode must not mutate the buffer a caller still needs to send")
    }
}
