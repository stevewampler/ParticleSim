package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.lifecycle.Emitter
import particlesim.lifecycle.EmitterCapPolicy
import particlesim.lifecycle.VectorDistribution
import particlesim.physics.NBodyGravity
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §10.4/§9.6: the shared editable-field dispatch every [DemoScene] routes through, tested in
 * isolation from any real scenario. */
class DemoSceneTest {

    @Test
    fun `applies a scalar field edit to the matching named force and reports handled`() {
        val gravity = NBodyGravity("g", g = 1.0, softening = 2.0, name = "gravity")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetScalarField("force", "gravity", "g", 9.0),
            forces = listOf(gravity),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
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
            store = ParticleStore(),
            t = 0.0,
        )
        assertEquals(Vector3(1.0, 2.0, 3.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))
    }

    @Test
    fun `applies a wind velocity edit to the matching named Wind force and reports handled`() {
        val wind = Wind(emptyList(), VectorExpr.of(Vector3.ZERO), name = "wind")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetWindVelocity("wind", VectorExpr.of(Vector3(1.0, 0.0, 0.0))),
            forces = listOf(wind),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
        )
        assertTrue(handled)
        assertEquals(Vector3(1.0, 0.0, 0.0), wind.currentVelocity(t = 0.0))
    }

    @Test
    fun `a wind velocity edit for a name that doesn't match anything is silently ignored, not an error`() {
        val wind = Wind(emptyList(), VectorExpr.of(Vector3.ZERO), name = "wind")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetWindVelocity("nonexistent", VectorExpr.of(Vector3(1.0, 0.0, 0.0))),
            forces = listOf(wind),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
        )
        assertTrue(handled) // the message *type* was recognized, even though nothing matched
        assertEquals(Vector3.ZERO, wind.currentVelocity(t = 0.0))
    }

    @Test
    fun `a wind velocity edit targeting a non-Wind force by the same name is silently ignored`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0), name = "wind")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetWindVelocity("wind", VectorExpr.of(Vector3(1.0, 0.0, 0.0))),
            forces = listOf(gravity),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
        )
        assertTrue(handled)
        assertEquals(Vector3(0.0, -9.8, 0.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))
    }

    @Test
    fun `applies a scalar and vector field edit to the matching named light`() {
        val light = particlesim.render.Light.Point(position = Vector3.ZERO, name = "sun")
        applyEditableFieldMessage(
            SceneControlMessage.SetScalarField("light", "sun", "intensity", 3.0),
            forces = emptyList(),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
            lights = listOf(light),
        )
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetVectorField("light", "sun", "position", Vector3(1.0, 2.0, 3.0)),
            forces = emptyList(),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
            lights = listOf(light),
        )
        assertTrue(handled)
        assertEquals(3.0, light.intensity)
        assertEquals(Vector3(1.0, 2.0, 3.0), light.position)
    }

    @Test
    fun `a field edit for a name that doesn't match anything is silently ignored, not an error`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0), name = "gravity")
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetScalarField("force", "nonexistent", "g", 9.0),
            forces = listOf(gravity),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
        )
        assertTrue(handled) // the message *type* was recognized, even though nothing matched
        assertEquals(Vector3(0.0, -9.8, 0.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))
    }

    @Test
    fun `applies a particle mass edit by id, independent of any named force or constraint`() {
        val store = ParticleStore()
        val id = store.create(position = Vector3.ZERO, mass = ScalarExpr.of(1.0))
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetParticleScalarField(id, "mass", ScalarExpr.of(5.0)),
            forces = emptyList(),
            constraints = emptyList(),
            store = store,
            t = 0.0,
        )
        assertTrue(handled)
        assertEquals(5.0, store.mass(id))
    }

    @Test
    fun `a particle scalar field edit for an id that no longer exists is silently ignored`() {
        val store = ParticleStore()
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetParticleScalarField(999, "mass", ScalarExpr.of(5.0)),
            forces = emptyList(),
            constraints = emptyList(),
            store = store,
            t = 0.0,
        )
        assertTrue(handled) // the message type was recognized, even though the id was stale
    }

    @Test
    fun `returns false for a message type it doesn't recognize, so a scene can fall through to its own handling`() {
        val handled = applyEditableFieldMessage(
            SceneControlMessage.SetGroupEnabled("g", false),
            forces = emptyList(),
            constraints = emptyList(),
            store = ParticleStore(),
            t = 0.0,
        )
        assertFalse(handled)
    }

    private fun emitter(name: String = "fountain") = Emitter(
        name = name,
        group = "sparks",
        rate = ScalarExpr.of(10.0),
        position = VectorDistribution.UniformBox(Vector3.ZERO, Vector3.ZERO),
        velocity = VectorDistribution.UniformBox(Vector3.ZERO, Vector3.ZERO),
        maxAlive = 100,
        masterSeed = 1L,
    )

    @Test
    fun `applies a rate edit to the matching named emitter and reports handled`() {
        val e = emitter()
        val handled = applyEmitterMessage(SceneControlMessage.SetEmitterRate("fountain", ScalarExpr.of(42.0)), listOf(e))
        assertTrue(handled)
        assertEquals(42.0, e.currentRate(t = 0.0))
    }

    @Test
    fun `applies a maxAlive edit to the matching named emitter`() {
        val e = emitter()
        applyEmitterMessage(SceneControlMessage.SetEmitterMaxAlive("fountain", 250), listOf(e))
        assertEquals(250, e.maxAlive)
    }

    @Test
    fun `a non-positive maxAlive edit is rejected, leaving the previous cap in place`() {
        val e = emitter()
        applyEmitterMessage(SceneControlMessage.SetEmitterMaxAlive("fountain", 0), listOf(e))
        assertEquals(100, e.maxAlive)
    }

    @Test
    fun `applies a cap-policy edit to the matching named emitter`() {
        val e = emitter()
        assertEquals(EmitterCapPolicy.STOP, e.currentCapPolicy())
        applyEmitterMessage(SceneControlMessage.SetEmitterCapPolicy("fountain", evictOldest = true), listOf(e))
        assertEquals(EmitterCapPolicy.EVICT_OLDEST, e.currentCapPolicy())
        applyEmitterMessage(SceneControlMessage.SetEmitterCapPolicy("fountain", evictOldest = false), listOf(e))
        assertEquals(EmitterCapPolicy.STOP, e.currentCapPolicy())
    }

    @Test
    fun `an emitter edit for a name that doesn't match anything is silently ignored, not an error`() {
        val e = emitter()
        val handled = applyEmitterMessage(SceneControlMessage.SetEmitterRate("nonexistent", ScalarExpr.of(42.0)), listOf(e))
        assertTrue(handled) // the message *type* was recognized, even though nothing matched
        assertEquals(10.0, e.currentRate(t = 0.0))
    }

    @Test
    fun `applyEmitterMessage returns false for a message type it doesn't recognize`() {
        assertFalse(applyEmitterMessage(SceneControlMessage.SetGroupEnabled("g", false), listOf(emitter())))
    }
}
