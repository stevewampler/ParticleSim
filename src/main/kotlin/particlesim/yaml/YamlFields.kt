package particlesim.yaml

import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.expr.ExpressionException
import particlesim.expr.ExpressionParser

/** Thrown for any structural or semantic problem found while loading a YAML scenario —
 * always at load time (§4.2: "fail fast... with a clear error pointing at the offending
 * field," not a runtime surprise deep in the physics loop). */
class YamlLoadException(message: String) : RuntimeException(message)

/** Small hand-written extraction helpers over the generic `Map`/`List`/scalar structure
 * SnakeYAML produces — deliberately not a data-binding library (Jackson, kotlinx.serialization):
 * owning this keeps every error message pointing at the exact YAML field path, matching §4.2's
 * "fail fast... pointing at the offending field" rather than a framework's generic message. */

internal fun Map<*, *>.requireMap(key: String, context: String): Map<*, *> =
    (this[key] as? Map<*, *>) ?: throw YamlLoadException("$context.$key: expected a mapping")

internal fun Map<*, *>.optionalMap(key: String): Map<*, *>? = this[key] as? Map<*, *>

internal fun Map<*, *>.requireListOrEmpty(key: String, context: String): List<*> = when (val v = this[key]) {
    null -> emptyList<Any?>()
    is List<*> -> v
    else -> throw YamlLoadException("$context.$key: expected a list")
}

internal fun Map<*, *>.requireString(key: String, context: String): String =
    (this[key] as? String) ?: throw YamlLoadException("$context.$key: expected a string, got ${describe(this[key])}")

internal fun Map<*, *>.requireInt(key: String, context: String): Int = when (val v = this[key]) {
    is Int -> v
    null -> throw YamlLoadException("$context.$key: missing required field")
    else -> throw YamlLoadException("$context.$key: expected an integer, got ${describe(v)}")
}

internal fun Map<*, *>.requireDouble(key: String, context: String): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    null -> throw YamlLoadException("$context.$key: missing required field")
    else -> throw YamlLoadException("$context.$key: expected a number, got ${describe(v)}")
}

internal fun Map<*, *>.optionalDouble(key: String, default: Double): Double = when (val v = this[key]) {
    null -> default
    is Number -> v.toDouble()
    else -> throw YamlLoadException("$key: expected a number, got ${describe(v)}")
}

internal fun Map<*, *>.optionalBoolean(key: String, default: Boolean): Boolean = when (val v = this[key]) {
    null -> default
    is Boolean -> v
    else -> throw YamlLoadException("$key: expected a boolean, got ${describe(v)}")
}

/** A literal `[x, y, z]` vector — for the fields (e.g. gravity's acceleration) whose
 * underlying Force/Constraint type isn't itself expression-capable, so an expression string
 * here wouldn't have anywhere to go even if the grammar could parse one. */
internal fun Map<*, *>.requireVectorLiteral(key: String, context: String): Vector3 {
    val v = this[key] as? List<*> ?: throw YamlLoadException("$context.$key: expected a [x, y, z] list, got ${describe(this[key])}")
    if (v.size != 3) throw YamlLoadException("$context.$key: expected exactly 3 components, got ${v.size}")
    val (x, y, z) = v.map { it as? Number ?: throw YamlLoadException("$context.$key: components must be numbers") }
    return Vector3(x.toDouble(), y.toDouble(), z.toDouble())
}

/** An expression-capable scalar field (§4.1): a literal number, or a string parsed by the
 * shared expression grammar. */
internal fun Map<*, *>.requireScalarExpr(key: String, context: String): ScalarExpr = when (val v = this[key]) {
    is Number -> ScalarExpr.of(v.toDouble())
    is String -> parseOrWrap(context, key) { ExpressionParser.parseScalar(v) }
    null -> throw YamlLoadException("$context.$key: missing required field")
    else -> throw YamlLoadException("$context.$key: expected a number or expression string, got ${describe(v)}")
}

/** An expression-capable vector field (§4.1): a literal `[x, y, z]` list, or a string parsed
 * by the shared expression grammar. */
internal fun Map<*, *>.requireVectorExpr(key: String, context: String): VectorExpr = when (val v = this[key]) {
    is List<*> -> VectorExpr.of(requireVectorLiteral(key, context))
    is String -> parseOrWrap(context, key) { ExpressionParser.parseVector(v) }
    null -> throw YamlLoadException("$context.$key: missing required field")
    else -> throw YamlLoadException("$context.$key: expected a [x, y, z] list or expression string, got ${describe(v)}")
}

private inline fun <T> parseOrWrap(context: String, key: String, block: () -> T): T = try {
    block()
} catch (e: ExpressionException) {
    throw YamlLoadException("$context.$key: ${e.message}")
}

private fun describe(v: Any?): String = when (v) {
    null -> "nothing"
    is String -> "string \"$v\""
    is Map<*, *> -> "a mapping"
    is List<*> -> "a list"
    else -> v.toString()
}
