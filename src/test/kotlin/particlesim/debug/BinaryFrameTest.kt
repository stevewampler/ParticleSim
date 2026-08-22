package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.FixedPosition
import particlesim.physics.UniformGravity
import particlesim.render.ArrowSample
import particlesim.render.CameraPose
import particlesim.render.Color
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
        assertEquals(listOf(DecodedParticle(a, Vector3(1.0, 2.0, 3.0)), DecodedParticle(b, Vector3(-1.5, 0.25, 100.0))), decoded.particles)
        assertEquals(listOf(DecodedConnection(a, b, Color.DEFAULT_LINE)), decoded.connections, "no lineColors override -> the default line color")
        assertNull(decoded.camera)
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

        assertEquals(listOf(DecodedMesh(wireframe = true, triangles = listOf(Triangle(a, b, c)))), decoded.meshes)
    }

    @Test
    fun `round-trips arrow samples`() {
        val store = ParticleStore()
        val samples = listOf(
            ArrowSample(origin = Vector3(1.0, 0.0, 0.0), vector = Vector3(0.0, 1.0, 0.0)),
            ArrowSample(origin = Vector3(2.0, 0.0, 0.0), vector = Vector3(0.0, 2.0, 0.0)),
        )

        val buffer = BinaryFrame.encode(
            t = 0.0, step = 0L, store = store, ids = emptyList(), connections = emptyList(),
            arrowSamples = samples,
        )
        val decoded = BinaryFrame.decode(buffer)

        assertEquals(samples, decoded.arrows)
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
        assertEquals(listOf("cloth", "pole"), decoded.groups)
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
    fun `decode does not consume the original buffer's position`() {
        val store = ParticleStore()
        val id = store.create(position = Vector3.ZERO)
        val buffer = BinaryFrame.encode(t = 0.0, step = 0L, store = store, ids = listOf(id), connections = emptyList())

        val positionBefore = buffer.position()
        BinaryFrame.decode(buffer)
        assertEquals(positionBefore, buffer.position(), "decode must not mutate the buffer a caller still needs to send")
    }
}
