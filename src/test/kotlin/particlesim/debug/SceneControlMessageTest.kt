package particlesim.debug

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `returns null for malformed or unrecognized input, same stance as the other message types`() {
        assertNull(SceneControlMessage.parse("not json at all {{{"))
        assertNull(SceneControlMessage.parse("""{"type": "pause"}"""))
        assertNull(SceneControlMessage.parse("""{"type": "unknown_future_type"}"""))
    }
}
