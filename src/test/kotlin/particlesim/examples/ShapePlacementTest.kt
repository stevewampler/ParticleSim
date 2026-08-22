package particlesim.examples

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals

class ShapePlacementTest {

    @Test
    fun `default placement leaves names unprefixed`() {
        assertEquals("cloth", ShapePlacement().name("cloth"))
        assertEquals(Vector3.ZERO, ShapePlacement().offset)
    }

    @Test
    fun `an instance name prefixes local names with a dot`() {
        val placement = ShapePlacement(instanceName = "flag1")
        assertEquals("flag1.cloth", placement.name("cloth"))
        assertEquals("flag1.pole", placement.name("pole"))
    }

    @Test
    fun `two placements with different instance names never collide`() {
        val a = ShapePlacement(instanceName = "flag1")
        val b = ShapePlacement(instanceName = "flag2")
        assertEquals("flag1.cloth", a.name("cloth"))
        assertEquals("flag2.cloth", b.name("cloth"))
    }
}
