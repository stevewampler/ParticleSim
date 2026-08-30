package particlesim.debug

import particlesim.core.ScalarExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SceneControlMessageTest {

    @Test
    fun `parses remove_collider with its name`() {
        assertEquals(
            SceneControlMessage.RemoveCollider("wall-x-neg"),
            SceneControlMessage.parse("""{"type": "remove_collider", "name": "wall-x-neg"}"""),
        )
    }

    @Test
    fun `rejects remove_collider with no name at all`() {
        assertNull(SceneControlMessage.parse("""{"type": "remove_collider"}"""))
    }

    @Test
    fun `parses delete_particle with its id`() {
        assertEquals(
            SceneControlMessage.DeleteParticle(42),
            SceneControlMessage.parse("""{"type": "delete_particle", "particleId": 42}"""),
        )
    }

    @Test
    fun `rejects delete_particle with no particleId at all`() {
        assertNull(SceneControlMessage.parse("""{"type": "delete_particle"}"""))
    }

    @Test
    fun `parses restart`() {
        assertEquals(SceneControlMessage.Restart, SceneControlMessage.parse("""{"type": "restart"}"""))
    }

    @Test
    fun `parses load_scene with its name`() {
        assertEquals(
            SceneControlMessage.LoadScene("trampoline"),
            SceneControlMessage.parse("""{"type": "load_scene", "name": "trampoline"}"""),
        )
    }

    @Test
    fun `rejects load_scene with no name at all`() {
        assertNull(SceneControlMessage.parse("""{"type": "load_scene"}"""))
    }

    @Test
    fun `parses set_particle_scalar_field with a plain-number expression as a Constant`() {
        assertEquals(
            SceneControlMessage.SetParticleScalarField(42, "mass", ScalarExpr.Constant(9.0)),
            SceneControlMessage.parse("""{"type": "set_particle_scalar_field", "particleId": 42, "field": "mass", "expression": "9.0"}"""),
        )
    }

    @Test
    fun `parses set_particle_scalar_field with a time-varying expression as OfTime`() {
        val parsed = SceneControlMessage.parse(
            """{"type": "set_particle_scalar_field", "particleId": 7, "field": "radius", "expression": "1.0 + sin(t)"}""",
        )
        val message = assertIs<SceneControlMessage.SetParticleScalarField>(parsed)
        assertEquals(7, message.particleId)
        assertEquals("radius", message.field)
        assertIs<ScalarExpr.OfTime>(message.expr)
        assertEquals(1.0, message.expr.evaluate(0.0), 1e-9)
    }

    @Test
    fun `rejects set_particle_scalar_field with malformed expression syntax`() {
        assertNull(
            SceneControlMessage.parse(
                """{"type": "set_particle_scalar_field", "particleId": 42, "field": "mass", "expression": "not an expression((("}""",
            ),
        )
    }

    @Test
    fun `rejects set_particle_scalar_field with a vector expression typed into a scalar field`() {
        assertNull(
            SceneControlMessage.parse(
                """{"type": "set_particle_scalar_field", "particleId": 42, "field": "mass", "expression": "[1, 2, 3]"}""",
            ),
        )
    }

    @Test
    fun `rejects set_particle_scalar_field missing any required key`() {
        assertNull(SceneControlMessage.parse("""{"type": "set_particle_scalar_field", "field": "mass", "expression": "1.0"}"""))
        assertNull(SceneControlMessage.parse("""{"type": "set_particle_scalar_field", "particleId": 42, "expression": "1.0"}"""))
        assertNull(SceneControlMessage.parse("""{"type": "set_particle_scalar_field", "particleId": 42, "field": "mass"}"""))
    }

    @Test
    fun `returns null for malformed or unrecognized input, same stance as the other message types`() {
        assertNull(SceneControlMessage.parse("not json at all {{{"))
        assertNull(SceneControlMessage.parse("""{"type": "pause"}"""))
        assertNull(SceneControlMessage.parse("""{"type": "unknown_future_type"}"""))
    }
}
