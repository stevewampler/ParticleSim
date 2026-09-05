package particlesim.yaml

import particlesim.core.Vector3

/**
 * §4.5's YAML shape library/registry (Phase 8 of the YAML front-end's second pass) — a named,
 * parameterized scene fragment (particles/forces/constraints/colliders/collisions/destroy/
 * emitters/groups, expressed in the exact grammar the rest of this loader already understands)
 * that a scene's `shapes:` section can instantiate one or more times, each at its own placement.
 * Mirrors the Kotlin-side [particlesim.examples.ShapePlacement] convention exactly: an
 * `instance` name dot-namespaces every local name the shape's body declares or references
 * (`"$instanceName.$local"`, unprefixed when absent — see
 * [particlesim.examples.ShapePlacement.name]), and an
 * `offset` translates every literal particle/collider `position` and grid `origin` the body
 * declares (mirroring [particlesim.examples.buildBallBounce]'s own "the floor collider's
 * position moves with placement too, not just the ball" precedent).
 *
 * Implemented as a **pre-processing pass over the raw parsed YAML document**, not a second
 * parser: [expand] rewrites `shape_definitions:`/`shapes:` into the ordinary top-level
 * `particles:`/`forces:`/`constraints:`/`colliders:`/`collisions:`/`destroy:`/`emitters:`/
 * `groups:` lists [YamlLoader.load] already knows how to read — by the time the rest of
 * [YamlLoader.load] runs, it has no idea any of this content came from a shape rather than being
 * authored inline. This reuses every section loader Phases 0-7 already built instead of
 * duplicating their parsing a second time.
 *
 * **Scope, matching what `flag`/`ball_bounce` actually need to express as definitions** (not a
 * speculative general system): a `params:` entry's `type` is informational only, never enforced;
 * `$paramName` substitution is whole-value only (a param can't be embedded inside a larger
 * expression string, e.g. no `"0.02 + $extra"`); a shape body's own
 * `collisions.rest_velocity`/`rest_penetration` are ignored — only `collisions.rules` merges,
 * since rest-velocity/penetration are scene-wide tuning knobs, not shape-local; author ids
 * (`id:`/`select.ids`) aren't namespaced, since neither target shape uses them — two instances
 * of a shape that *did* use `id:` would collide, a known, undocumented-until-needed gap.
 *
 * [NAME_KEYS]' `name` rewrite also reaches an `emitters:` entry's `name:` — which is not just a
 * label there, [particlesim.lifecycle.Emitter] derives its RNG sub-stream from it
 * (`mixSeed(masterSeed, name)`, §14.1's per-emitter-seeded-RNG requirement). Two instances of a
 * shape with an emitter correctly get distinct namespaced names and therefore distinct spawn
 * sequences (the intended §14.4 behavior), but this also means giving an instance an `instance:`
 * name changes that emitter's physics, not just its label — an undocumented-until-needed
 * determinism surprise in the same family as the `id:` gap above, should a shape ever pair
 * `instance:` with an emitter body used both named and unnamed.
 */
internal object ShapeRegistry {

    private val MERGED_LIST_KEYS = listOf("particles", "forces", "constraints", "colliders", "destroy", "emitters", "groups", "lights")

    /** Returns a new root document with `shape_definitions:`/`shapes:` removed and every
     * instantiated shape's (namespaced, offset, param-substituted) body merged into the
     * corresponding top-level section. A document with neither key returns [root] completely
     * unchanged — the common case for a scene with no shapes at all. */
    fun expand(root: Map<*, *>): Map<*, *> {
        val definitionEntries = root.requireListOrEmpty("shape_definitions", "root")
        val instanceEntries = root.requireListOrEmpty("shapes", "root")
        if (definitionEntries.isEmpty() && instanceEntries.isEmpty()) return root

        val definitions = loadDefinitions(definitionEntries)

        val accumulator = LinkedHashMap<String, MutableList<Any?>>()
        for (key in MERGED_LIST_KEYS) accumulator[key] = ((root[key] as? List<*>) ?: emptyList<Any?>()).toMutableList()
        val collisionRules = (((root["collisions"] as? Map<*, *>)?.get("rules") as? List<*>) ?: emptyList<Any?>()).toMutableList()

        for ((index, entry) in instanceEntries.withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("shapes[$index]: expected a mapping")
            val context = "shapes[$index]"
            val use = map.requireString("use", context)
            val definition = definitions[use] ?: throw YamlLoadException("$context.use: unknown shape definition '$use'")
            val instanceName = map.optionalString("instance", context = context)
            val offset = map.optionalVectorLiteral("offset", context, Vector3.ZERO)
            val params = resolveParams(map, definition, context)

            val rewrittenBody = rewrite(definition.body, null, params, offset, instanceName) as Map<*, *>
            for (key in MERGED_LIST_KEYS) {
                val section = rewrittenBody[key] as? List<*> ?: continue
                accumulator.getValue(key).addAll(section)
            }
            val shapeRules = (rewrittenBody["collisions"] as? Map<*, *>)?.get("rules") as? List<*>
            if (shapeRules != null) collisionRules.addAll(shapeRules)
        }

        val expanded = LinkedHashMap<String, Any?>()
        for ((k, v) in root) expanded[k as String] = v
        expanded.remove("shape_definitions")
        expanded.remove("shapes")
        for ((key, list) in accumulator) expanded[key] = list
        if (collisionRules.isNotEmpty()) {
            val existingCollisions = (root["collisions"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            val newCollisions = LinkedHashMap<String, Any?>()
            for ((k, v) in existingCollisions) newCollisions[k as String] = v
            newCollisions["rules"] = collisionRules
            expanded["collisions"] = newCollisions
        }
        return expanded
    }

    private data class ShapeDefinition(val defaults: Map<String, Any?>, val declaredParams: Set<String>, val body: Map<*, *>)

    private fun loadDefinitions(entries: List<*>): Map<String, ShapeDefinition> {
        val definitions = LinkedHashMap<String, ShapeDefinition>()
        for ((index, entry) in entries.withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("shape_definitions[$index]: expected a mapping")
            val context = "shape_definitions[$index]"
            val name = map.requireString("name", context)
            val paramsSpec = map.optionalMap("params") ?: emptyMap<Any?, Any?>()
            val defaults = LinkedHashMap<String, Any?>()
            for ((paramName, spec) in paramsSpec) {
                val specMap = spec as? Map<*, *> ?: throw YamlLoadException("$context.params.$paramName: expected a mapping")
                if (specMap.containsKey("default")) defaults[paramName as String] = specMap["default"]
            }
            val body = map.requireMap("body", context)
            if (definitions.put(name, ShapeDefinition(defaults, paramsSpec.keys.map { it as String }.toSet(), body)) != null) {
                throw YamlLoadException("$context: duplicate shape definition name '$name'")
            }
        }
        return definitions
    }

    private fun resolveParams(instance: Map<*, *>, definition: ShapeDefinition, context: String): Map<String, Any?> {
        val given = instance.optionalMap("params") ?: emptyMap<Any?, Any?>()
        val resolved = HashMap<String, Any?>()
        for (paramName in definition.declaredParams) {
            resolved[paramName] = when {
                given.containsKey(paramName) -> given[paramName]
                definition.defaults.containsKey(paramName) -> definition.defaults.getValue(paramName)
                else -> throw YamlLoadException("$context.params.$paramName: missing required parameter (no default declared)")
            }
        }
        return resolved
    }

    /** The keys this rewrite treats as carrying a local group/grid/collider *name* — either
     * declaring one (`name`) or referencing one declared elsewhere in the same shape body
     * (`group`/`group_a`/`group_b`/`grid`/`collider`). Declarations and references get the
     * *identical* transform (prefix with the instance name), which is what keeps them resolving
     * against each other correctly after rewriting — a reference doesn't need to be
     * distinguished from a declaration for this to work. */
    private val NAME_KEYS = setOf("name", "group", "group_a", "group_b", "grid", "collider")

    private fun rewrite(value: Any?, parentKey: String?, params: Map<String, Any?>, offset: Vector3, instanceName: String?): Any? =
        when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in value) {
                    val key = k as String
                    out[key] = rewrite(v, key, params, offset, instanceName)
                }
                for (nameKey in NAME_KEYS) {
                    val v = out[nameKey]
                    if (v is String && instanceName != null) out[nameKey] = "$instanceName.$v"
                }
                // A grid generator's own body (the map under a "grid" key that looks like a grid,
                // not the `grid: <name>` string reference wind/mesh_springs use) gets its
                // `origin` translated by the placement offset - grid generation has no literal
                // `position` field to offset directly (positions are computed from row/col).
                // Every other position-bearing map (a single/list particle, a collider) uses the
                // plain `position` key instead.
                if (parentKey == "grid" && out.containsKey("rows")) {
                    out["origin"] = addOffset(out["origin"] as? List<*>, offset)
                } else {
                    val pos = out["position"] as? List<*>
                    if (pos != null) out["position"] = addOffset(pos, offset) ?: pos
                }
                out
            }
            is List<*> -> value.map { rewrite(it, null, params, offset, instanceName) }
            is String -> if (value.startsWith("$") && params.containsKey(value.substring(1))) params[value.substring(1)] else value
            else -> value
        }

    private fun addOffset(v: List<*>?, offset: Vector3): List<Double>? {
        val nums = v?.takeIf { it.size == 3 && it.all { n -> n is Number } }?.map { (it as Number).toDouble() } ?: listOf(0.0, 0.0, 0.0)
        return listOf(nums[0] + offset.x, nums[1] + offset.y, nums[2] + offset.z)
    }
}
