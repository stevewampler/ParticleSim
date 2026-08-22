package particlesim.examples

import particlesim.physics.Wind
import particlesim.surface.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Structural assertions about `buildFlag`'s output, distinct from [FlagStabilityTest]'s dynamic
 * smoke test. Exists mainly to close a real gap: golden-file tests (`FlagGoldenTest`,
 * `FlagYamlParityTest`) never read `FlagScenario.surface`, so nothing else would notice if
 * wrapping `Grid.triangles(grid)` into a named [particlesim.surface.Surface] (§10.3's
 * name→object registry) accidentally dropped or reordered a triangle along the way.
 */
class FlagTest {

    @Test
    fun `the surface wraps exactly Grid triangles(grid), unmodified`() {
        val flag = buildFlag(rows = 6, cols = 10)

        assertEquals(Grid.triangles(flag.grid), flag.surface.triangles)
    }

    @Test
    fun `the surface and the wind force are named, distinctly from the cloth group`() {
        val flag = buildFlag()

        assertEquals("cloth-mesh", flag.surface.name)
        val wind = flag.forces.filterIsInstance<Wind>().single()
        assertEquals("wind", wind.name)
        assertNotNull(flag.surface.name)
    }

    @Test
    fun `an instance name namespaces the surface and wind names too`() {
        val flag = buildFlag(placement = ShapePlacement(instanceName = "flag1"))

        assertEquals("flag1.cloth-mesh", flag.surface.name)
        val wind = flag.forces.filterIsInstance<Wind>().single()
        assertEquals("flag1.wind", wind.name)
    }
}
