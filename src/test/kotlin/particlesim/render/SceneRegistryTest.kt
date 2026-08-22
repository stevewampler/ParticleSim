package particlesim.render

import particlesim.core.Vector3
import particlesim.physics.Force
import particlesim.physics.UniformGravity
import particlesim.surface.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SceneRegistryTest {

    private fun namedForce(name: String?): Force = UniformGravity("g", Vector3.ZERO, name = name)

    @Test
    fun `only named forces and surfaces are registered`() {
        val named = namedForce("gravity")
        val unnamed = namedForce(null)
        val namedSurface = Surface(emptyList(), name = "mesh")
        val unnamedSurface = Surface(emptyList())

        val registry = SceneRegistry.build(forces = listOf(named, unnamed), surfaces = listOf(namedSurface, unnamedSurface))

        assertEquals(mapOf("gravity" to named), registry.forces)
        assertEquals(mapOf("mesh" to namedSurface), registry.surfaces)
    }

    @Test
    fun `duplicate names within the same kind are rejected`() {
        val a = namedForce("wind")
        val b = namedForce("wind")

        assertFailsWith<IllegalArgumentException> { SceneRegistry.build(forces = listOf(a, b)) }
    }

    @Test
    fun `the same name is allowed across different kinds`() {
        val force = namedForce("wind")
        val surface = Surface(emptyList(), name = "wind")

        val registry = SceneRegistry.build(forces = listOf(force), surfaces = listOf(surface))

        assertEquals(force, registry.forces["wind"])
        assertEquals(surface, registry.surfaces["wind"])
    }

    @Test
    fun `iteration order matches input order, not hash order`() {
        val names = listOf("zeta", "alpha", "mu", "beta")
        val forces = names.map { namedForce(it) }

        val registry = SceneRegistry.build(forces = forces)

        assertEquals(names, registry.forces.keys.toList())
    }

    @Test
    fun `empty input registers nothing`() {
        val registry = SceneRegistry.build()

        assertTrue(registry.forces.isEmpty())
        assertTrue(registry.surfaces.isEmpty())
    }
}
