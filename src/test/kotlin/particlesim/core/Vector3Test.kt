package particlesim.core

import kotlin.test.Test
import kotlin.test.assertEquals

class Vector3Test {
    @Test
    fun `arithmetic operators are componentwise`() {
        val a = Vector3(1.0, 2.0, 3.0)
        val b = Vector3(4.0, 5.0, 6.0)
        assertEquals(Vector3(5.0, 7.0, 9.0), a + b)
        assertEquals(Vector3(-3.0, -3.0, -3.0), a - b)
        assertEquals(Vector3(2.0, 4.0, 6.0), a * 2.0)
        assertEquals(Vector3(-1.0, -2.0, -3.0), -a)
    }

    @Test
    fun `dot and length`() {
        val a = Vector3(3.0, 4.0, 0.0)
        assertEquals(25.0, a.dot(a))
        assertEquals(5.0, a.length())
    }

    @Test
    fun `normalized is unit length and preserves direction`() {
        val a = Vector3(0.0, 5.0, 0.0)
        assertEquals(Vector3(0.0, 1.0, 0.0), a.normalized())
    }

    @Test
    fun `normalizing the zero vector returns zero instead of NaN`() {
        assertEquals(Vector3.ZERO, Vector3.ZERO.normalized())
    }
}
