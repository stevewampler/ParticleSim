package particlesim.yaml

import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.expr.ExpressionException
import particlesim.expr.ExpressionParser
import particlesim.lifecycle.ScalarDistribution
import particlesim.lifecycle.VectorDistribution

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

/** A list of plain strings — e.g. a particle generator's `tags:`. Empty (not an error) when
 * absent, same "absent means empty" convention [requireListOrEmpty] itself already uses. */
internal fun Map<*, *>.requireStringList(key: String, context: String): List<String> =
    requireListOrEmpty(key, context).map { it as? String ?: throw YamlLoadException("$context.$key: each entry must be a string") }

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

internal fun Map<*, *>.optionalString(key: String, default: String? = null): String? = when (val v = this[key]) {
    null -> default
    is String -> v
    else -> throw YamlLoadException("$key: expected a string, got ${describe(v)}")
}

internal fun Map<*, *>.optionalInt(key: String, default: Int): Int = when (val v = this[key]) {
    null -> default
    is Int -> v
    else -> throw YamlLoadException("$key: expected an integer, got ${describe(v)}")
}

/** [requireScalarExpr]'s optional counterpart — a field that falls back to [default] rather
 * than erroring when absent, for the many symmetric-with-override fields ([directionalTriple]
 * below being the main consumer) where "not given" is a normal, expected shape rather than an
 * authoring mistake. */
internal fun Map<*, *>.optionalScalarExpr(key: String, context: String, default: ScalarExpr): ScalarExpr = when (val v = this[key]) {
    null -> default
    is Number -> ScalarExpr.of(v.toDouble())
    is String -> parseOrWrap(context, key) { ExpressionParser.parseScalar(v) }
    else -> throw YamlLoadException("$context.$key: expected a number or expression string, got ${describe(v)}")
}

/** [requireVectorExpr]'s optional counterpart — see [optionalScalarExpr]. */
internal fun Map<*, *>.optionalVectorExpr(key: String, context: String, default: VectorExpr): VectorExpr = when (val v = this[key]) {
    null -> default
    is List<*> -> VectorExpr.of(requireVectorLiteral(key, context))
    is String -> parseOrWrap(context, key) { ExpressionParser.parseVector(v) }
    else -> throw YamlLoadException("$context.$key: expected a [x, y, z] list or expression string, got ${describe(v)}")
}

/** The "symmetric value with optional direction-dependent override" shape repeated verbatim on
 * [particlesim.physics.Spring]/[particlesim.physics.Damper]/[particlesim.physics.MeshSprings]'
 * stiffness, damping, and break-threshold constructor parameters: a base field (e.g.
 * `stiffness`), plus `extension_<base>`/`compression_<base>` that each independently default
 * back to the base value when not given. Reads plain [Double]s (not expression-capable — none
 * of stiffness/damping/break-threshold are [particlesim.core.ScalarExpr] fields on the Kotlin
 * side either), returned as `(base, extension, compression)`. Two entry points, matching the two
 * shapes this triple actually appears in on the Kotlin side: [directionalTriple] for a base with
 * its own default (e.g. `breakThreshold = Double.POSITIVE_INFINITY`), [requireDirectionalTriple]
 * for a base with no Kotlin-side default (`stiffness`/`damping` are mandatory constructor
 * parameters). */
internal fun Map<*, *>.directionalTriple(base: String, context: String, default: Double): Triple<Double, Double, Double> {
    val baseValue = optionalDouble(base, default)
    val extension = optionalDouble("extension_$base", baseValue)
    val compression = optionalDouble("compression_$base", baseValue)
    return Triple(baseValue, extension, compression)
}

internal fun Map<*, *>.requireDirectionalTriple(base: String, context: String): Triple<Double, Double, Double> {
    val baseValue = requireDouble(base, context)
    val extension = optionalDouble("extension_$base", baseValue)
    val compression = optionalDouble("compression_$base", baseValue)
    return Triple(baseValue, extension, compression)
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

/** §14.1's three native distribution shapes (Phase 6 of the YAML front-end's second pass) —
 * `box`/`sphere`/`spread` map onto [VectorDistribution.UniformBox]/[VectorDistribution.UniformSphere]/
 * [VectorDistribution.PointWithSpread] respectively. `spread`'s angle is authored in degrees
 * (`spread_angle_degrees`) and converted via [Math.toRadians] here, matching how
 * [particlesim.examples.buildSparks] itself is authored (`Math.toRadians(25.0)`) rather than
 * asking a YAML author to pre-convert to radians by hand. */
internal fun Map<*, *>.requireVectorDistribution(key: String, context: String): VectorDistribution =
    parseVectorDistribution(requireMap(key, context), "$context.$key")

private fun parseVectorDistribution(v: Map<*, *>, context: String): VectorDistribution = when {
    v.containsKey("box") -> {
        val box = v.requireMap("box", context)
        VectorDistribution.UniformBox(box.requireVectorLiteral("center", "$context.box"), box.requireVectorLiteral("half_extents", "$context.box"))
    }
    v.containsKey("sphere") -> {
        val sphere = v.requireMap("sphere", context)
        VectorDistribution.UniformSphere(sphere.requireVectorLiteral("center", "$context.sphere"), sphere.requireDouble("radius", "$context.sphere"))
    }
    v.containsKey("spread") -> {
        val spread = v.requireMap("spread", context)
        val sc = "$context.spread"
        VectorDistribution.PointWithSpread(
            direction = spread.requireVectorLiteral("direction", sc),
            spreadAngleRadians = Math.toRadians(spread.requireDouble("spread_angle_degrees", sc)),
            minMagnitude = spread.requireDouble("min_magnitude", sc),
            maxMagnitude = spread.requireDouble("max_magnitude", sc),
        )
    }
    else -> throw YamlLoadException("$context: unknown distribution kind (expected one of: box, sphere, spread)")
}

/** §14.1's two native scalar distribution shapes — `constant`/`range` map onto
 * [ScalarDistribution.Constant]/[ScalarDistribution.UniformRange]. */
internal fun Map<*, *>.requireScalarDistribution(key: String, context: String): ScalarDistribution =
    parseScalarDistribution(requireMap(key, context), "$context.$key")

/** [requireScalarDistribution]'s optional counterpart, for `radius`/`lifetime` — both `null` on
 * [particlesim.lifecycle.Emitter]'s own constructor when not given, unlike `mass`, which has a
 * real default ([particlesim.lifecycle.ScalarDistribution.Constant]`(1.0)`) rather than being
 * nullable - callers pass that default explicitly rather than this function inventing one. */
internal fun Map<*, *>.optionalScalarDistribution(key: String, context: String): ScalarDistribution? {
    val v = optionalMap(key) ?: return null
    return parseScalarDistribution(v, "$context.$key")
}

private fun parseScalarDistribution(v: Map<*, *>, context: String): ScalarDistribution = when {
    v.containsKey("constant") -> ScalarDistribution.Constant(v.requireDouble("constant", context))
    v.containsKey("range") -> {
        val range = v["range"] as? List<*> ?: throw YamlLoadException("$context.range: expected a [min, max] list")
        if (range.size != 2) throw YamlLoadException("$context.range: expected exactly 2 components, got ${range.size}")
        val min = (range[0] as? Number)?.toDouble() ?: throw YamlLoadException("$context.range: components must be numbers")
        val max = (range[1] as? Number)?.toDouble() ?: throw YamlLoadException("$context.range: components must be numbers")
        ScalarDistribution.UniformRange(min, max)
    }
    else -> throw YamlLoadException("$context: unknown distribution kind (expected one of: constant, range)")
}

private fun describe(v: Any?): String = when (v) {
    null -> "nothing"
    is String -> "string \"$v\""
    is Map<*, *> -> "a mapping"
    is List<*> -> "a list"
    else -> v.toString()
}
