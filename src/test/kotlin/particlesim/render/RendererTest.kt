package particlesim.render

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Spring
import particlesim.physics.UniformFieldForce
import particlesim.physics.UniformGravity
import particlesim.surface.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RendererTest {

    @Test
    fun `a colorBy=NONE line renderer has no color`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0, breakThreshold = 1.0)
        val renderer = LineRenderer(spring)

        assertNull(LineRendering.colorFor(renderer, store))
    }

    @Test
    fun `a colorBy=BREAK_PROXIMITY line renderer colors from the force's current breakProximity`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.5, 0.0, 0.0)) // displacement 0.5 of threshold 1.0
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0, breakThreshold = 1.0)
        val renderer = LineRenderer(spring, ColorBy.BREAK_PROXIMITY)

        assertEquals(ColorRamp.blueOrange(0.5), LineRendering.colorFor(renderer, store))
    }

    @Test
    fun `declaring BREAK_PROXIMITY on a non-breakable force fails at construction, not silently`() {
        val nonBreakable = object : particlesim.physics.PairwiseForce {
            override val particleA = 0
            override val particleB = 1
        }
        assertFailsWith<IllegalArgumentException> { LineRenderer(nonBreakable, ColorBy.BREAK_PROXIMITY) }
    }

    @Test
    fun `arrow sampling covers the whole region at the given resolution`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0))
        val renderer = ArrowRenderer(
            force = gravity,
            regionMin = Vector3(0.0, 0.0, 0.0),
            regionMax = Vector3(1.0, 0.0, 0.0),
            resolution = 0.5,
        )
        val samples = ArrowSampling.sample(renderer, t = 0.0)

        assertEquals(3, samples.size) // x = 0.0, 0.5, 1.0
        assertEquals(setOf(0.0, 0.5, 1.0), samples.map { it.origin.x }.toSet())
        for (s in samples) assertEquals(Vector3(0.0, -9.8, 0.0), s.vector)
    }

    @Test
    fun `arrow renderer rejects a non-positive resolution rather than looping forever`() {
        val gravity = UniformGravity("g", Vector3.ZERO)
        assertFailsWith<IllegalArgumentException> {
            ArrowRenderer(gravity, Vector3.ZERO, Vector3(1.0, 1.0, 1.0), resolution = 0.0)
        }
    }

    @Test
    fun `arrow sampling reflects a time-varying field force at the sampled t`() {
        val timeVarying = object : UniformFieldForce {
            override fun sampleAt(position: Vector3, t: Double): Vector3 = Vector3(t, 0.0, 0.0)
        }
        val renderer = ArrowRenderer(timeVarying, Vector3.ZERO, Vector3.ZERO, resolution = 1.0)
        val samples = ArrowSampling.sample(renderer, t = 4.0)

        assertEquals(1, samples.size)
        assertEquals(Vector3(4.0, 0.0, 0.0), samples[0].vector)
    }

    // §10.2's `[stretch]` "Lighting & materials" -----------------------------------------------

    @Test
    fun `an untextured surface with no declared material resolves to the historical default blue-grey`() {
        val surface = Surface(emptyList())
        val renderer = SurfaceRenderer(surface)

        assertEquals(Material.DEFAULT_COLOR, renderer.effectiveMaterial.color)
    }

    @Test
    fun `a textured surface with no declared material resolves to untinted white, not the untextured default`() {
        val surface = Surface(emptyList())
        val renderer = SurfaceRenderer(surface, textureName = "flag-stripes")

        assertEquals(Material.UNTINTED, renderer.effectiveMaterial.color)
    }

    @Test
    fun `an explicitly declared material wins regardless of whether the surface is textured`() {
        val surface = Surface(emptyList())
        val custom = Material(color = Color(1.0, 0.0, 0.0), roughness = 0.2, opacity = 0.5)

        assertEquals(custom, SurfaceRenderer(surface, material = custom).effectiveMaterial)
        assertEquals(custom, SurfaceRenderer(surface, textureName = "flag-stripes", material = custom).effectiveMaterial)
    }

    @Test
    fun `material rejects roughness or opacity outside 0,1`() {
        assertFailsWith<IllegalArgumentException> { Material(roughness = 1.5) }
        assertFailsWith<IllegalArgumentException> { Material(roughness = -0.1) }
        assertFailsWith<IllegalArgumentException> { Material(opacity = 1.1) }
        assertFailsWith<IllegalArgumentException> { Material(opacity = -0.1) }
    }
}
