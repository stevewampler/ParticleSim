package particlesim.debug

import particlesim.core.Vector3
import particlesim.physics.NBodyGravity
import particlesim.physics.UniformGravity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §10.4/§9.6: the shared (kind, name) -> EditableFields dispatch every [DemoScene] routes
 * through, tested in isolation from any real scenario. */
class DemoSceneTest {

    @Test
    fun `applies a scalar field edit to the matching named force and reports handled`() {
        val gravity = NBodyGravity("g", g = 1.0, softening = 2.0, name = "gravity")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetScalarField("force", "gravity", "g", 9.0),
            forces = listOf(gravity),
            constraints = emptyList(),
        )
        assertTrue(handled)
        assertEquals(9.0, (gravity.editableFields()["g"] as particlesim.physics.FieldValue.Scalar).value)
    }

    @Test
    fun `applies a vector field edit to the matching named force`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0), name = "gravity")
        applyEditableFieldMessage(
            SceneControlMessage.SetVectorField("force", "gravity", "acceleration", Vector3(1.0, 2.0, 3.0)),
            forces = listOf(gravity),
            constraints = emptyList(),
        )
        assertEquals(Vector3(1.0, 2.0, 3.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))
    }

    @Test
    fun `a field edit for a name that doesn't match anything is silently ignored, not an error`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0), name = "gravity")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetScalarField("force", "nonexistent", "g", 9.0),
            forces = listOf(gravity),
            constraints = emptyList(),
        )
        assertTrue(handled) // the message *type* was recognized, even though nothing matched
        assertEquals(Vector3(0.0, -9.8, 0.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))
    }

    @Test
    fun `returns false for a message type it doesn't recognize, so a scene can fall through to its own handling`() {
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetGroupEnabled("g", false),
            forces = emptyList(),
            constraints = emptyList(),
        )
        assertFalse(handled)
    }
}
