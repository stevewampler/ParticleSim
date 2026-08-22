package particlesim.render

import particlesim.core.Groups
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.UniformGravity
import particlesim.surface.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SceneRegistryTest {

    private fun namedForce(name: String?): Force = UniformGravity("g", Vector3.ZERO, name = name)
    private fun namedConstraint(name: String?): Constraint = FixedPosition("g", Vector3.ZERO, name = name)

    @Test
    fun `only named forces, constraints, and surfaces are registered`() {
        val namedF = namedForce("gravity")
        val unnamedF = namedForce(null)
        val namedC = namedConstraint("anchor")
        val unnamedC = namedConstraint(null)
        val namedSurface = Surface(emptyList(), name = "mesh")
        val unnamedSurface = Surface(emptyList())

        val registry = SceneRegistry.build(
            forces = listOf(namedF, unnamedF),
            constraints = listOf(namedC, unnamedC),
            surfaces = listOf(namedSurface, unnamedSurface),
        )

        assertEquals(mapOf("gravity" to namedF), registry.forces)
        assertEquals(mapOf("anchor" to namedC), registry.constraints)
        assertEquals(mapOf("mesh" to namedSurface), registry.surfaces)
    }

    @Test
    fun `every group is registered, named or not - a group has no unnamed form`() {
        val groups = Groups()
        groups.add("cloth", 1)
        groups.add("cloth", 2)
        groups.add("pole", 3)

        val registry = SceneRegistry.build(groups = groups)

        assertEquals(mapOf("cloth" to setOf(1, 2), "pole" to setOf(3)), registry.groups)
    }

    @Test
    fun `group member ids resolve eagerly at build time`() {
        val groups = Groups()
        groups.add("g", 1)
        val registry = SceneRegistry.build(groups = groups)

        groups.add("g", 2) // mutating Groups after build() must not retroactively change the snapshot

        assertEquals(setOf(1), registry.groups["g"])
    }

    @Test
    fun `duplicate names within the same kind are rejected`() {
        val a = namedForce("wind")
        val b = namedForce("wind")
        assertFailsWith<IllegalArgumentException> { SceneRegistry.build(forces = listOf(a, b)) }

        val c = namedConstraint("anchor")
        val d = namedConstraint("anchor")
        assertFailsWith<IllegalArgumentException> { SceneRegistry.build(constraints = listOf(c, d)) }
    }

    @Test
    fun `the same name is allowed across different kinds, including a group`() {
        val force = namedForce("wind")
        val constraint = namedConstraint("wind")
        val surface = Surface(emptyList(), name = "wind")
        val groups = Groups().apply { add("wind", 1) }

        val registry = SceneRegistry.build(
            forces = listOf(force), constraints = listOf(constraint), surfaces = listOf(surface), groups = groups,
        )

        assertEquals(force, registry.forces["wind"])
        assertEquals(constraint, registry.constraints["wind"])
        assertEquals(surface, registry.surfaces["wind"])
        assertTrue(registry.groups.containsKey("wind"))
    }

    @Test
    fun `iteration order matches input order, not hash order`() {
        val names = listOf("zeta", "alpha", "mu", "beta")
        val forces = names.map { namedForce(it) }

        val registry = SceneRegistry.build(forces = forces)

        assertEquals(names, registry.forces.keys.toList())
    }

    @Test
    fun `group iteration order matches creation order too`() {
        val groups = Groups()
        listOf("zeta", "alpha", "mu", "beta").forEachIndexed { i, name -> groups.add(name, i) }

        val registry = SceneRegistry.build(groups = groups)

        assertEquals(listOf("zeta", "alpha", "mu", "beta"), registry.groups.keys.toList())
    }

    @Test
    fun `empty input registers nothing`() {
        val registry = SceneRegistry.build()

        assertTrue(registry.forces.isEmpty())
        assertTrue(registry.constraints.isEmpty())
        assertTrue(registry.surfaces.isEmpty())
        assertTrue(registry.groups.isEmpty())
    }
}
