package particlesim.render

import particlesim.core.Vector3
import particlesim.physics.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightTest {

    @Test
    fun `an ambient light exposes color and intensity but no position field`() {
        val light = Light.Ambient(color = Color(0.2, 0.3, 0.4), intensity = 0.5)

        assertEquals(
            mapOf(
                "color" to FieldValue.Vector(Vector3(0.2, 0.3, 0.4)),
                "intensity" to FieldValue.Scalar(0.5),
            ),
            light.editableFields(),
        )
    }

    @Test
    fun `a directional or point light also exposes a position field`() {
        val directional = Light.Directional(position = Vector3(1.0, 2.0, 3.0))
        val point = Light.Point(position = Vector3(4.0, 5.0, 6.0))

        assertEquals(FieldValue.Vector(Vector3(1.0, 2.0, 3.0)), directional.editableFields()["position"])
        assertEquals(FieldValue.Vector(Vector3(4.0, 5.0, 6.0)), point.editableFields()["position"])
    }

    @Test
    fun `setField mutates color and intensity in place, reflected on the next editableFields read`() {
        val light = Light.Ambient()

        assertTrue(light.setField("color", FieldValue.Vector(Vector3(1.0, 0.0, 0.0))))
        assertTrue(light.setField("intensity", FieldValue.Scalar(2.5)))

        assertEquals(Color(1.0, 0.0, 0.0), light.color)
        assertEquals(2.5, light.intensity)
    }

    @Test
    fun `setField mutates a positioned light's position in place`() {
        val light = Light.Point(position = Vector3.ZERO)

        assertTrue(light.setField("position", FieldValue.Vector(Vector3(7.0, 8.0, 9.0))))

        assertEquals(Vector3(7.0, 8.0, 9.0), light.position)
    }

    @Test
    fun `setField rejects a position edit on an ambient light, which has no position`() {
        val light = Light.Ambient()

        assertFalse(light.setField("position", FieldValue.Vector(Vector3(1.0, 1.0, 1.0))))
    }

    @Test
    fun `setField rejects an unrecognized field name or a value of the wrong kind`() {
        val light = Light.Ambient()

        assertFalse(light.setField("nope", FieldValue.Scalar(1.0)))
        assertFalse(light.setField("color", FieldValue.Scalar(1.0)))
        assertFalse(light.setField("intensity", FieldValue.Vector(Vector3.ZERO)))
    }
}
