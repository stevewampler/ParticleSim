package particlesim.physics

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** §13.2's runtime safety net for mass, exercised directly against [ParticleStore]. */
class MassGuardTest {

    @Test
    fun `non-positive constant mass is rejected at creation`() {
        val store = ParticleStore()
        assertFailsWith<IllegalArgumentException> {
            store.create(mass = ScalarExpr.of(0.0))
        }
    }

    @Test
    fun `non-positive dynamic mass is clamped and warned, not thrown`() {
        val warnings = mutableListOf<String>()
        val store = ParticleStore(onWarning = { warnings.add(it) })
        val id = store.create(mass = ScalarExpr.of { -1.0 })
        assertEquals(ParticleStore.MASS_EPSILON, store.mass(id))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `dynamic mass evaluating to NaN throws instead of clamping`() {
        val store = ParticleStore()
        assertFailsWith<IllegalStateException> {
            store.create(mass = ScalarExpr.of { Double.NaN })
        }
    }

    @Test
    fun `refreshDynamicMass reclamps a mass that goes non-positive mid-run`() {
        val warnings = mutableListOf<String>()
        val store = ParticleStore(onWarning = { warnings.add(it) })
        val id = store.create(mass = ScalarExpr.of { t -> 2.0 - t })

        assertEquals(2.0, store.mass(id))
        store.refreshDynamicMass(3.0)
        assertEquals(ParticleStore.MASS_EPSILON, store.mass(id))
        assertEquals(1, warnings.size)
    }
}
