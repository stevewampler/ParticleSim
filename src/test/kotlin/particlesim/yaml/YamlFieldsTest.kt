package particlesim.yaml

import particlesim.core.ScalarExpr
import particlesim.core.VectorExpr
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Phase 0 of the YAML front-end's second pass: the shared optional-field/directional-triple
 * helpers every later force/constraint/collider/emitter loader builds on. Tested in isolation
 * against plain `Map` literals, the same generic structure SnakeYAML itself produces, rather
 * than through a full `YamlLoader.load()` call - these are the extraction primitives, not
 * scenario-level behavior (that's `YamlLoaderTest`'s job). */
class YamlFieldsTest {

    @Test
    fun `optionalString returns the value when present, the default when absent`() {
        assertEquals("hi", mapOf("k" to "hi").optionalString("k"))
        assertEquals(null, emptyMap<String, Any?>().optionalString("k"))
        assertEquals("fallback", emptyMap<String, Any?>().optionalString("k", "fallback"))
    }

    @Test
    fun `optionalString rejects a non-string value`() {
        assertFailsWith<YamlLoadException> { mapOf("k" to 5).optionalString("k") }
    }

    @Test
    fun `optionalInt returns the value when present, the default when absent`() {
        assertEquals(7, mapOf("k" to 7).optionalInt("k", 0))
        assertEquals(0, emptyMap<String, Any?>().optionalInt("k", 0))
    }

    @Test
    fun `optionalInt rejects a non-integer value`() {
        assertFailsWith<YamlLoadException> { mapOf("k" to "nope").optionalInt("k", 0) }
    }

    @Test
    fun `optionalScalarExpr accepts a literal number, an expression string, or falls back to default`() {
        assertEquals(2.0, mapOf("k" to 2.0).optionalScalarExpr("k", "ctx", ScalarExpr.of(0.0)).evaluate(0.0))
        assertEquals(5.0, mapOf("k" to "2.0 + 3.0").optionalScalarExpr("k", "ctx", ScalarExpr.of(0.0)).evaluate(0.0))
        assertEquals(9.0, emptyMap<String, Any?>().optionalScalarExpr("k", "ctx", ScalarExpr.of(9.0)).evaluate(0.0))
    }

    @Test
    fun `optionalScalarExpr rejects a malformed expression string`() {
        assertFailsWith<YamlLoadException> { mapOf("k" to "1 + ").optionalScalarExpr("k", "ctx", ScalarExpr.of(0.0)) }
    }

    @Test
    fun `optionalVectorExpr accepts a literal list, an expression string, or falls back to default`() {
        val literal = mapOf("k" to listOf(1.0, 2.0, 3.0)).optionalVectorExpr("k", "ctx", VectorExpr.of(Vector3.ZERO))
        assertEquals(Vector3(1.0, 2.0, 3.0), literal.evaluate(0.0))

        val expr = mapOf("k" to "[t, 0, 0]").optionalVectorExpr("k", "ctx", VectorExpr.of(Vector3.ZERO))
        assertEquals(Vector3(4.0, 0.0, 0.0), expr.evaluate(4.0))

        val default = VectorExpr.of(Vector3(9.0, 9.0, 9.0))
        assertEquals(Vector3(9.0, 9.0, 9.0), emptyMap<String, Any?>().optionalVectorExpr("k", "ctx", default).evaluate(0.0))
    }

    @Test
    fun `directionalTriple defaults extension and compression to the base value when neither is given`() {
        val (base, ext, comp) = mapOf("stiffness" to 200.0).directionalTriple("stiffness", "ctx", 0.0)
        assertEquals(Triple(200.0, 200.0, 200.0), Triple(base, ext, comp))
    }

    @Test
    fun `directionalTriple lets extension and compression independently override the base`() {
        val map = mapOf("stiffness" to 200.0, "extension_stiffness" to 220.0, "compression_stiffness" to 180.0)
        assertEquals(Triple(200.0, 220.0, 180.0), map.directionalTriple("stiffness", "ctx", 0.0))
    }

    @Test
    fun `directionalTriple falls back to its own default when the base itself is absent`() {
        assertEquals(Triple(0.0, 0.0, 0.0), emptyMap<String, Any?>().directionalTriple("break_threshold", "ctx", 0.0))
    }

    @Test
    fun `requireDirectionalTriple requires the base field`() {
        assertFailsWith<YamlLoadException> { emptyMap<String, Any?>().requireDirectionalTriple("stiffness", "ctx") }
    }

    @Test
    fun `requireDirectionalTriple resolves the same defaulting as directionalTriple once the base is given`() {
        val map = mapOf("damping" to 1.0, "extension_damping" to 1.2)
        assertEquals(Triple(1.0, 1.2, 1.0), map.requireDirectionalTriple("damping", "ctx"))
    }
}
