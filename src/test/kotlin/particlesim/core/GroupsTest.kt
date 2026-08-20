package particlesim.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupsTest {

    @Test
    fun `add and membersOf`() {
        val groups = Groups()
        groups.add("g", 1)
        groups.add("g", 2)
        assertEquals(setOf(1, 2), groups.membersOf("g"))
        assertEquals(setOf("g"), groups.groupsOf(1))
    }

    @Test
    fun `remove drops a single membership`() {
        val groups = Groups()
        groups.add("g", 1)
        groups.remove("g", 1)
        assertTrue(groups.membersOf("g").isEmpty())
        assertTrue(groups.groupsOf(1).isEmpty())
    }

    @Test
    fun `removeParticle drops membership from every group at once`() {
        val groups = Groups()
        groups.add("a", 1)
        groups.add("b", 1)
        groups.removeParticle(1)
        assertTrue(groups.membersOf("a").isEmpty())
        assertTrue(groups.membersOf("b").isEmpty())
    }

    @Test
    fun `destroying a particle and recycling its slot does not leak group membership to the newcomer`() {
        val store = ParticleStore()
        val groups = Groups()

        val a = store.create()
        groups.add("g", a)

        store.destroy(a)
        groups.removeParticle(a)

        val b = store.create()
        // Confirms the scenario is actually exercised: b really did reuse a's freed slot,
        // not just get a fresh one behind an id/slot design that only looks safe.
        assertEquals(1, store.capacity)

        assertFalse(groups.membersOf("g").contains(b))
        assertFalse(groups.membersOf("g").contains(a))
        assertTrue(groups.membersOf("g").isEmpty())
    }
}
