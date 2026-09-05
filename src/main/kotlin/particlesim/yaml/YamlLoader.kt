package particlesim.yaml

import org.yaml.snakeyaml.Yaml
import particlesim.collision.BoxCollider
import particlesim.collision.Collider
import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.ParticleCollisionRule
import particlesim.collision.ParticleCollisionSystem
import particlesim.collision.PlaneCollider
import particlesim.collision.SphereCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.lifecycle.CollisionDestroyRule
import particlesim.lifecycle.DestroyCondition
import particlesim.lifecycle.DestructionSystem
import particlesim.lifecycle.Emitter
import particlesim.lifecycle.EmitterCapPolicy
import particlesim.lifecycle.ScalarDistribution
import particlesim.physics.ConstantForce
import particlesim.physics.Constraint
import particlesim.physics.Drag
import particlesim.physics.FixedPosition
import particlesim.physics.FixedVelocity
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.NBodyGravity
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.render.Color
import particlesim.render.Light
import particlesim.surface.Grid
import kotlin.math.abs
import kotlin.random.Random

data class YamlScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val constraints: List<Constraint>,
    /** Each declared particle grid's `List<List<Int>>` ids, keyed by its `name` — lets a
     * caller (e.g. a golden-file test) sample specific `grid[row][col]` vertices the same way
     * [particlesim.examples.FlagScenario.grid] does, without hardcoding id arithmetic. */
    val grids: Map<String, List<List<Int>>>,
    /** Phase 4 of the YAML front-end's second pass: every top-level `colliders:` declaration,
     * keyed by its (required) name — referenced by Phase 5's `collisions:`/`destroy:` sections. */
    val colliders: Map<String, Collider> = emptyMap(),
    /** Phase 5: `null` when `collisions:` declared no `particle_collider`/`particle_particle`
     * rules respectively — a scene with neither pays nothing extra, same "absent means empty"
     * convention the rest of this loader already uses. */
    val collisionSystem: CollisionSystem? = null,
    val particleCollisionSystem: ParticleCollisionSystem? = null,
    /** Phase 5's `destroy:` section - `null` when empty. */
    val destruction: DestructionSystem? = null,
    /** Phase 6's `emitters:` section - empty when absent. */
    val emitters: List<Emitter> = emptyList(),
    /** Phase 9's `lights:` section - empty when absent, same "viewer falls back to its own
     * default lighting" meaning [particlesim.render.Light]'s own doc comment already gives an
     * empty list from any front-end. */
    val lights: List<Light> = emptyList(),
)

/**
 * The YAML front-end (§4.2), scoped for this pass to exactly what §7.3's flag worked example
 * needs: a particle grid, structural/shear/bend mesh springs, wind, gravity, and a
 * fixed-position constraint. [particlesim.golden.FlagYamlParityTest] loads this scenario from
 * YAML and asserts it matches the *same* checked-in `flag.golden.txt` the Kotlin-built
 * [particlesim.examples.buildFlag] already produces — proving both front-ends build the same
 * in-memory model (§4), not just asserting it. Colliders, emitters, destroy rules, breakable
 * thresholds, N-body/ball-bounce/sparks scenarios, and general bulk-generation shapes
 * (uniform-random-in-volume, explicit particle lists, individual particle declarations) are
 * real §4.2 scope but not covered here — noted in TODO.md as a deliberate second pass, the
 * same framing used for every other phase's worked-example-first scoping.
 *
 * **Group model**: a group's membership comes from a particle generator's own `name`/
 * `edge_groups` (direct assignment), or from a top-level `groups:` entry — either a plain string
 * (§4.2's "declared but currently unmatched" marker, no membership of its own) or
 * `{name, select: {tags/ids/range}}`, §4.2's real selector language (Phase 2 of the second pass —
 * see [resolveGroupsSection]). Both `groups:` forms share one required semantic check: a name
 * with zero members after loading is a **warning**, not a silent no-op; a `group:` reference
 * anywhere else to a name no declaration ever produced is a load-time **error**.
 */
class YamlLoader(private val onWarning: (String) -> Unit = { System.err.println(it) }) {

    fun load(yamlText: String): YamlScenario {
        val parsedRoot = (Yaml().load<Any?>(yamlText) as? Map<*, *>)
            ?: throw YamlLoadException("root document must be a mapping")

        val version = parsedRoot["version"] ?: throw YamlLoadException("missing required top-level field 'version'")
        if (version != 1) throw YamlLoadException("unsupported version '$version' (only version 1 is supported)")

        // Phase 8's shape registry (§4.5) is a pre-processing pass, not another section loader:
        // it rewrites shape_definitions:/shapes: into ordinary particles:/forces:/etc. entries
        // merged into root, so everything below has no idea any of it came from a shape rather
        // than being authored inline. A document with neither key returns parsedRoot unchanged.
        val root = ShapeRegistry.expand(parsedRoot)

        val store = ParticleStore()
        val groups = Groups()
        val grids = HashMap<String, List<List<Int>>>()
        val declaredGroups = LinkedHashSet<String>()
        // Phase 1 of the YAML front-end's second pass (todo/TODO.md): a tag -> store-ids index
        // and an author-facing id -> store-id map, both loader-local and discarded once load()
        // returns - neither ParticleStore nor Groups gains a tags concept, keeping this entirely
        // a load-time addressing convenience. Populated by loadParticles, consumed below by
        // Phase 2's groups: selector resolution (tags/ids/range).
        val tagIndex = HashMap<String, MutableSet<Int>>()
        val authorIds = HashMap<String, Int>()

        // groups: resolution runs after loadParticles (not before, as the pre-Phase-2 version of
        // this method did) since a selector entry needs tagIndex/authorIds/grids to already be
        // populated - a plain-string entry doesn't strictly need this ordering, but there's no
        // reason to special-case it separately from the selector form it now shares one list with.
        loadParticles(root, store, groups, grids, declaredGroups, tagIndex, authorIds)

        // Phase 6's emitters run before groups:/forces:/etc. resolve group references, not after
        // (unlike every other loadX above) - an emitter's own `group:` is the *target* it spawns
        // into, which is commonly never pre-declared by any particle generator (buildSparks'
        // "sparks" group is a real example: it exists purely because the emitter spawns into it,
        // and gravity/drag still need to reference it by name). loadEmitters registers each
        // emitter's group into declaredGroups as a side effect so requireKnownGroup below accepts
        // it, without adding it to groupNames - a fresh emitter target always has zero members at
        // load time, and warning about that would be a false positive, not a real "stale
        // reference" catch.
        val emitters = loadEmitters(root, declaredGroups)

        val groupNames = resolveGroupsSection(root.requireListOrEmpty("groups", "root"), groups, grids, tagIndex, authorIds, declaredGroups)

        fun requireKnownGroup(name: String, context: String) {
            if (name !in declaredGroups) throw YamlLoadException("$context: unknown group '$name'")
        }

        val forces = loadForces(root, store, grids, ::requireKnownGroup)
        val constraints = loadConstraints(root, store, groups, ::requireKnownGroup)
        val colliders = loadColliders(root)
        val (collisionSystem, particleCollisionSystem) = loadCollisions(root, colliders, ::requireKnownGroup)
        val destruction = loadDestruction(root, colliders, ::requireKnownGroup)
        val lights = loadLights(root)

        for (name in groupNames) {
            if (groups.membersOf(name).isEmpty()) onWarning("group '$name' matches zero particles")
        }

        return YamlScenario(store, groups, forces, constraints, grids, colliders, collisionSystem, particleCollisionSystem, destruction, emitters, lights)
    }

    /** §4.2's group selector language (tags/ids/range), Phase 2 of the YAML front-end's second
     * pass. Each `groups:` entry is either the original plain string (§4.2's "declared but
     * currently unmatched" check — a real group's membership never comes from this form, only
     * from a particle generator's own `name`/`edge_groups`) or `{name, select: {tags: [...],
     * ids: [...], range: {...}}}`, which *does* populate real membership by resolving the
     * selector against Phase 1's [tagIndex]/[authorIds]/`grids`. Both forms return their name for
     * the shared zero-match warning check in [load] — a selector matching nothing is exactly as
     * much a real authoring mistake as a stale `groups:` string, the "more natural home" this
     * class's own doc comment already anticipated before this phase existed. Multiple selector
     * kinds combined in one `select:` block are **unioned** (matches any) — the simplest additive
     * rule, revisable if a scenario ever needs intersection instead. */
    private fun resolveGroupsSection(
        entries: List<*>, groups: Groups, grids: Map<String, List<List<Int>>>,
        tagIndex: Map<String, Set<Int>>, authorIds: Map<String, Int>, declaredGroups: MutableSet<String>,
    ): List<String> {
        val names = ArrayList<String>()
        for ((index, entry) in entries.withIndex()) {
            val context = "groups[$index]"
            when (entry) {
                is String -> {
                    declaredGroups += entry
                    names += entry
                }
                is Map<*, *> -> {
                    val name = entry.requireString("name", context)
                    val select = entry.requireMap("select", context)
                    val matched = LinkedHashSet<Int>()
                    var sawKnownKind = false
                    if (select.containsKey("tags")) {
                        sawKnownKind = true
                        matched += resolveTagSelector(select.requireStringList("tags", "$context.select"), tagIndex)
                    }
                    if (select.containsKey("ids")) {
                        sawKnownKind = true
                        matched += resolveIdSelector(select.requireStringList("ids", "$context.select"), authorIds, "$context.select")
                    }
                    if (select.containsKey("range")) {
                        sawKnownKind = true
                        matched += resolveRangeSelector(select.requireMap("range", "$context.select"), grids, "$context.select")
                    }
                    if (!sawKnownKind) throw YamlLoadException("$context.select: expected at least one of tags, ids, range")
                    matched.forEach { groups.add(name, it) }
                    declaredGroups += name
                    names += name
                }
                else -> throw YamlLoadException("$context: expected a string or a mapping")
            }
        }
        return names
    }

    /** AND across every listed tag — a particle must carry all of them, not just one. Reading
     * an unrecognized tag as "matches nothing" rather than an error keeps this consistent with
     * §4.2's own "zero-match is a warning, not an error" semantics for the group as a whole. */
    private fun resolveTagSelector(tags: List<String>, tagIndex: Map<String, Set<Int>>): Set<Int> {
        if (tags.isEmpty()) return emptySet()
        var result: Set<Int>? = null
        for (tag in tags) {
            val matches = tagIndex[tag] ?: emptySet()
            result = result?.intersect(matches) ?: matches
        }
        return result ?: emptySet()
    }

    /** Unlike an unrecognized tag, an author id that was never declared by a `list`/`single`
     * particle is a load-time **error** — §4.2's "unknown name" tier, not "zero match." */
    private fun resolveIdSelector(ids: List<String>, authorIds: Map<String, Int>, context: String): Set<Int> =
        ids.map { authorIds[it] ?: throw YamlLoadException("$context.ids: unknown id '$it'") }.toSet()

    private fun resolveRangeSelector(range: Map<*, *>, grids: Map<String, List<List<Int>>>, context: String): Set<Int> {
        val gridName = range.requireString("grid", context)
        val grid = grids[gridName] ?: throw YamlLoadException("$context.grid: unknown grid '$gridName'")
        val rowCount = grid.size
        val colCount = if (rowCount == 0) 0 else grid[0].size
        val rowRange = readInclusiveIntRange(range, "rows", 0, rowCount - 1, context)
        val colRange = readInclusiveIntRange(range, "cols", 0, colCount - 1, context)
        val ids = LinkedHashSet<Int>()
        for (r in rowRange) for (c in colRange) ids += grid[r][c]
        return ids
    }

    /** `[lo, hi]`, inclusive both ends, defaulting to the grid's own full extent when [key] is
     * absent (so `range: {grid: wing}` with neither `rows` nor `cols` given selects the whole
     * grid). Out-of-bounds or an inverted `lo > hi` is a load-time error — a genuine authoring
     * mistake, not a "zero match" case, so it doesn't get the warning-only treatment. */
    private fun readInclusiveIntRange(map: Map<*, *>, key: String, fullLo: Int, fullHi: Int, context: String): IntRange {
        val v = map[key] ?: return fullLo..fullHi
        val list = v as? List<*> ?: throw YamlLoadException("$context.$key: expected a [lo, hi] list")
        if (list.size != 2) throw YamlLoadException("$context.$key: expected exactly 2 components, got ${list.size}")
        val lo = (list[0] as? Number)?.toInt() ?: throw YamlLoadException("$context.$key: components must be integers")
        val hi = (list[1] as? Number)?.toInt() ?: throw YamlLoadException("$context.$key: components must be integers")
        if (lo < fullLo || hi > fullHi || lo > hi) {
            throw YamlLoadException("$context.$key: [$lo, $hi] out of bounds for [$fullLo, $fullHi]")
        }
        return lo..hi
    }

    /** §4.2's "particles can be defined individually or generated in bulk" - dispatches on
     * whether `particles:` is the original single-map shorthand (`{grid: {...}}`, kept working
     * unchanged so `flag.yaml` and every existing [particlesim.yaml.YamlLoaderTest] case needs
     * zero changes) or a list of one-or-more generator blocks, discriminated by key exactly like
     * `forces:`/`constraints:` already are. Four generator kinds: `grid` (unchanged), plus three
     * new ones from Phase 1 of the YAML front-end's second pass - `random_volume`
     * (uniform-in-box or uniform-in-sphere, seeded for §11 determinism), `list` (explicit
     * per-particle declarations), `single` (one particle). Every kind accepts an optional
     * `tags:` list (indexed into [tagIndex], consumed by Phase 2's selector resolution); `list`/
     * `single` entries additionally accept an optional author-facing `id:` string (indexed into
     * [authorIds] - never the real [ParticleStore] id, which the store itself assigns). */
    private fun loadParticles(
        root: Map<*, *>, store: ParticleStore, groups: Groups,
        grids: MutableMap<String, List<List<Int>>>, declaredGroups: MutableSet<String>,
        tagIndex: MutableMap<String, MutableSet<Int>>, authorIds: MutableMap<String, Int>,
    ) {
        when (val particlesSection = root["particles"]) {
            null -> return
            is Map<*, *> -> {
                val gridSection = particlesSection.optionalMap("grid") ?: return
                loadGrid(gridSection, "particles.grid", store, groups, grids, declaredGroups, tagIndex)
            }
            is List<*> -> {
                for ((index, entry) in particlesSection.withIndex()) {
                    val map = entry as? Map<*, *> ?: throw YamlLoadException("particles[$index]: expected a mapping")
                    val context = "particles[$index]"
                    when {
                        map.containsKey("grid") ->
                            loadGrid(map.requireMap("grid", context), "$context.grid", store, groups, grids, declaredGroups, tagIndex)
                        map.containsKey("random_volume") ->
                            loadRandomVolume(map.requireMap("random_volume", context), "$context.random_volume", store, groups, declaredGroups, tagIndex)
                        map.containsKey("list") ->
                            loadParticleList(map.requireMap("list", context), "$context.list", store, groups, declaredGroups, tagIndex, authorIds)
                        map.containsKey("single") ->
                            loadSingleParticle(map.requireMap("single", context), "$context.single", store, groups, declaredGroups, tagIndex, authorIds)
                        else -> throw YamlLoadException("$context: unknown particle generator (expected one of: grid, random_volume, list, single)")
                    }
                }
            }
            else -> throw YamlLoadException("particles: expected a mapping or a list")
        }
    }

    private fun loadGrid(
        gridSection: Map<*, *>, context: String, store: ParticleStore, groups: Groups,
        grids: MutableMap<String, List<List<Int>>>, declaredGroups: MutableSet<String>,
        tagIndex: MutableMap<String, MutableSet<Int>>,
    ) {
        val name = gridSection.requireString("name", context)
        val rows = gridSection.requireInt("rows", context)
        val cols = gridSection.requireInt("cols", context)
        val spacing = gridSection.optionalDouble("spacing", 1.0, context)
        val massExpr = gridSection.requireScalarExpr("mass", context)
        val tags = gridSection.requireStringList("tags", context)
        // Phase 8's shape registry is the only current writer of this field - see its own doc
        // comment - translating a whole grid by a shape instance's placement offset, the
        // grid-generation counterpart to particles/colliders' own literal `position` translation.
        val origin = gridSection.optionalVectorLiteral("origin", context, Vector3.ZERO)

        val grid = (0 until rows).map { r ->
            (0 until cols).map { c ->
                val id = try {
                    store.create(position = Vector3(c * spacing, -r * spacing, 0.0) + origin, mass = massExpr)
                } catch (e: IllegalArgumentException) {
                    throw YamlLoadException("$context.mass: ${e.message}")
                }
                groups.add(name, id)
                addTags(tagIndex, tags, id)
                id
            }
        }
        grids[name] = grid
        declaredGroups += name

        for (entry in gridSection.requireListOrEmpty("edge_groups", context)) {
            val eg = entry as? Map<*, *> ?: throw YamlLoadException("$context.edge_groups: each entry must be a mapping")
            val edge = eg.requireString("edge", "$context.edge_groups")
            val groupName = eg.requireString("group", "$context.edge_groups")
            val ids = when (edge) {
                "left" -> grid.map { it.first() }
                "right" -> grid.map { it.last() }
                "top" -> grid.first()
                "bottom" -> grid.last()
                else -> throw YamlLoadException("$context.edge_groups.edge: unknown edge '$edge' (expected left/right/top/bottom)")
            }
            ids.forEach { groups.add(groupName, it) }
            declaredGroups += groupName
        }
    }

    /** Uniform-in-box or uniform-in-sphere bulk generation (§4.2/§14.1's distribution shapes,
     * reused here rather than inventing a second one). `seed` is required, not defaulted - an
     * implicit system-RNG fallback would silently break §11's determinism requirement the
     * moment a scenario using this ever needed to reproduce. */
    private fun loadRandomVolume(
        f: Map<*, *>, context: String, store: ParticleStore, groups: Groups,
        declaredGroups: MutableSet<String>, tagIndex: MutableMap<String, MutableSet<Int>>,
    ) {
        val name = f.requireString("name", context)
        val count = f.requireInt("count", context)
        val seed = f.requireInt("seed", context)
        val massExpr = f.optionalScalarExpr("mass", context, ScalarExpr.of(1.0))
        val velocity = f.optionalVectorExpr("velocity", context, VectorExpr.of(Vector3.ZERO)).evaluate(0.0)
        val tags = f.requireStringList("tags", context)
        val shape = f.requireMap("shape", context)
        val rng = Random(seed)

        // Resolved once, outside the loop - a per-particle `when` below just picks which
        // uniform-sampling function to call with these fixed params, rather than storing a
        // closure per shape kind (which Kotlin's trailing-lambda grammar makes surprisingly
        // easy to write ambiguously here - a bare `{ ... }` as a when-branch's last statement
        // reads as a trailing lambda on the *previous* line's call, not a new expression).
        val isSphere: Boolean
        val boxCenter: Vector3
        val boxHalfExtents: Vector3
        val sphereCenter: Vector3
        val sphereRadius: Double
        when {
            shape.containsKey("box") -> {
                val box = shape.requireMap("box", "$context.shape")
                boxCenter = box.requireVectorLiteral("center", "$context.shape.box")
                boxHalfExtents = box.requireVectorLiteral("half_extents", "$context.shape.box")
                isSphere = false
                sphereCenter = Vector3.ZERO
                sphereRadius = 0.0
            }
            shape.containsKey("sphere") -> {
                val sphere = shape.requireMap("sphere", "$context.shape")
                sphereCenter = sphere.requireVectorLiteral("center", "$context.shape.sphere")
                sphereRadius = sphere.requireDouble("radius", "$context.shape.sphere")
                isSphere = true
                boxCenter = Vector3.ZERO
                boxHalfExtents = Vector3.ZERO
            }
            else -> throw YamlLoadException("$context.shape: unknown shape (expected box or sphere)")
        }

        repeat(count) {
            val position = if (isSphere) uniformInSphere(rng, sphereCenter, sphereRadius) else uniformInBox(rng, boxCenter, boxHalfExtents)
            val id = try {
                store.create(position = position, velocity = velocity, mass = massExpr)
            } catch (e: IllegalArgumentException) {
                throw YamlLoadException("$context.mass: ${e.message}")
            }
            groups.add(name, id)
            addTags(tagIndex, tags, id)
        }
        declaredGroups += name
    }

    /** Explicit per-particle declarations (§4.2). Each entry's own optional `id:` (a plain
     * author-chosen string, resolved to the real [ParticleStore] id it was assigned) feeds
     * [authorIds] for Phase 2's `ids:` selector - an author id reused across two particles is a
     * load-time error, the same "ambiguous reference" tier [SceneRegistry]-style duplicate-name
     * checks already use elsewhere in this codebase. */
    private fun loadParticleList(
        f: Map<*, *>, context: String, store: ParticleStore, groups: Groups,
        declaredGroups: MutableSet<String>, tagIndex: MutableMap<String, MutableSet<Int>>, authorIds: MutableMap<String, Int>,
    ) {
        val name = f.requireString("name", context)
        for ((index, entry) in f.requireListOrEmpty("particles", context).withIndex()) {
            val p = entry as? Map<*, *> ?: throw YamlLoadException("$context.particles[$index]: expected a mapping")
            val entryContext = "$context.particles[$index]"
            val id = createDeclaredParticle(p, entryContext, store)
            groups.add(name, id)
            addTags(tagIndex, p.requireStringList("tags", entryContext), id)
            registerAuthorId(p, entryContext, id, authorIds)
        }
        declaredGroups += name
    }

    private fun loadSingleParticle(
        f: Map<*, *>, context: String, store: ParticleStore, groups: Groups,
        declaredGroups: MutableSet<String>, tagIndex: MutableMap<String, MutableSet<Int>>, authorIds: MutableMap<String, Int>,
    ) {
        val name = f.requireString("name", context)
        val id = createDeclaredParticle(f, context, store)
        groups.add(name, id)
        addTags(tagIndex, f.requireStringList("tags", context), id)
        registerAuthorId(f, context, id, authorIds)
        declaredGroups += name
    }

    /** Shared by [loadParticleList]/[loadSingleParticle]: `position` is required, `velocity`
     * defaults to zero (evaluated once, not itself expression-capable - [ParticleStore.create]'s
     * own `velocity` parameter is a plain [Vector3], not a [particlesim.core.VectorExpr]),
     * `mass` defaults to [ParticleStore.create]'s own default (`1.0`) rather than being required
     * - unlike `grid`, where mass has always been mandatory and stays that way for backward
     * compatibility. `radius`/`lifetime` are omitted (not defaulted to some literal) when absent,
     * matching [ParticleStore.create]'s own `null`-means-unset convention for both. */
    private fun createDeclaredParticle(f: Map<*, *>, context: String, store: ParticleStore): Int {
        val position = f.requireVectorLiteral("position", context)
        val velocity = f.optionalVectorExpr("velocity", context, VectorExpr.of(Vector3.ZERO)).evaluate(0.0)
        val massExpr = f.optionalScalarExpr("mass", context, ScalarExpr.of(1.0))
        val radiusExpr = if (f["radius"] != null) f.requireScalarExpr("radius", context) else null
        val lifetimeExpr = if (f["lifetime"] != null) f.requireScalarExpr("lifetime", context) else null
        return try {
            store.create(position = position, velocity = velocity, mass = massExpr, radius = radiusExpr, lifetime = lifetimeExpr)
        } catch (e: IllegalArgumentException) {
            throw YamlLoadException("$context.mass: ${e.message}")
        }
    }

    private fun registerAuthorId(f: Map<*, *>, context: String, id: Int, authorIds: MutableMap<String, Int>) {
        val authorId = f.optionalString("id", context = context) ?: return
        if (authorIds.containsKey(authorId)) throw YamlLoadException("$context.id: duplicate author id '$authorId'")
        authorIds[authorId] = id
    }

    private fun addTags(tagIndex: MutableMap<String, MutableSet<Int>>, tags: List<String>, id: Int) {
        for (tag in tags) tagIndex.getOrPut(tag) { mutableSetOf() }.add(id)
    }

    /** Rejection sampling in the enclosing cube - simplest correct way to get a genuinely
     * uniform-by-volume distribution inside a sphere (naively scaling a random direction by a
     * uniform radius biases samples toward the center). */
    private fun uniformInSphere(rng: Random, center: Vector3, radius: Double): Vector3 {
        while (true) {
            val x = rng.nextDouble(-1.0, 1.0)
            val y = rng.nextDouble(-1.0, 1.0)
            val z = rng.nextDouble(-1.0, 1.0)
            if (x * x + y * y + z * z <= 1.0) return center + Vector3(x, y, z) * radius
        }
    }

    private fun uniformInBox(rng: Random, center: Vector3, halfExtents: Vector3): Vector3 =
        center + Vector3(rng.nextDouble(-1.0, 1.0) * halfExtents.x, rng.nextDouble(-1.0, 1.0) * halfExtents.y, rng.nextDouble(-1.0, 1.0) * halfExtents.z)

    private fun loadForces(
        root: Map<*, *>, store: ParticleStore, grids: Map<String, List<List<Int>>>,
        requireKnownGroup: (String, String) -> Unit,
    ): List<Force> {
        val forces = ArrayList<Force>()
        for ((index, entry) in root.requireListOrEmpty("forces", "root").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("forces[$index]: expected a mapping")
            val context = "forces[$index]"
            when {
                map.containsKey("gravity") -> {
                    val f = map.requireMap("gravity", context)
                    val group = f.requireString("group", "$context.gravity")
                    requireKnownGroup(group, "$context.gravity.group")
                    val accel = f.requireVectorLiteral("acceleration", "$context.gravity")
                    forces += UniformGravity(group, accel, name = f.optionalString("name", context = "$context.gravity"))
                }
                map.containsKey("mesh_springs") -> {
                    val f = map.requireMap("mesh_springs", context)
                    val grid = resolveGrid(f, grids, "$context.mesh_springs")
                    val edgeType = f.requireString("edges", "$context.mesh_springs")
                    val edges = when (edgeType) {
                        "structural" -> Grid.structuralEdges(grid)
                        "shear" -> Grid.shearEdges(grid)
                        "bend" -> Grid.bendEdges(grid)
                        else -> throw YamlLoadException(
                            "$context.mesh_springs.edges: unknown edge type '$edgeType' (expected structural/shear/bend)",
                        )
                    }
                    // Phase 3 of the YAML front-end's second pass: mesh_springs now exposes the
                    // full direction-dependent stiffness/damping/break-threshold triple Phase 0's
                    // helpers were built for - stiffness stays mandatory (matches MeshSprings'
                    // own constructor, which has no Kotlin-side default for it), damping and
                    // break_threshold stay optional, defaulting to 0.0/infinity exactly as
                    // MeshSprings' own constructor does.
                    val mc = "$context.mesh_springs"
                    val (stiffness, extStiffness, compStiffness) = f.requireDirectionalTriple("stiffness", mc)
                    val (damping, extDamping, compDamping) = f.directionalTriple("damping", mc, 0.0)
                    val (breakThreshold, extBreak, compBreak) = f.directionalTriple("break_threshold", mc, Double.POSITIVE_INFINITY)
                    forces += MeshSprings(
                        edges, store,
                        stiffness = stiffness, extensionStiffness = extStiffness, compressionStiffness = compStiffness,
                        damping = damping, extensionDamping = extDamping, compressionDamping = compDamping,
                        breakThreshold = breakThreshold, extensionBreakThreshold = extBreak, compressionBreakThreshold = compBreak,
                        name = f.optionalString("name", context = mc),
                    )
                }
                map.containsKey("wind") -> {
                    val f = map.requireMap("wind", context)
                    val wc = "$context.wind"
                    val grid = resolveGrid(f, grids, wc)
                    val triangles = Grid.triangles(grid)
                    val velocity = f.requireVectorExpr("velocity", wc)
                    val density = f.optionalDouble("density", 1.0, wc)
                    forces += Wind(triangles, velocity, density = density, name = f.optionalString("name", context = wc))
                }
                map.containsKey("drag") -> {
                    val f = map.requireMap("drag", context)
                    val dc = "$context.drag"
                    val group = f.requireString("group", dc)
                    requireKnownGroup(group, "$dc.group")
                    val coefficient = f.requireDouble("coefficient", dc)
                    val quadratic = f.optionalBoolean("quadratic", false, dc)
                    forces += Drag(group, coefficient, quadratic = quadratic, name = f.optionalString("name", context = dc))
                }
                map.containsKey("nbody_gravity") -> {
                    val f = map.requireMap("nbody_gravity", context)
                    val nc = "$context.nbody_gravity"
                    val group = f.requireString("group", nc)
                    requireKnownGroup(group, "$nc.group")
                    // 6.674e-11 mirrors NBodyGravity's own constructor default exactly (no
                    // named constant on that side to reference - it's an inline literal there
                    // too); DEFAULT_SOFTENING is a real exposed constant, used directly.
                    val g = f.optionalDouble("g", 6.674e-11, nc)
                    val softening = f.optionalDouble("softening", NBodyGravity.DEFAULT_SOFTENING, nc)
                    forces += NBodyGravity(group, g = g, softening = softening, name = f.optionalString("name", context = nc))
                }
                map.containsKey("constant_force") -> {
                    // §6's "fixed force" - implemented as a Force (ConstantForce), not a
                    // Constraint, since it's just an externally supplied force term, not a
                    // pinned state (see requirements.md §6's own distinction).
                    val f = map.requireMap("constant_force", context)
                    val cc = "$context.constant_force"
                    val group = f.requireString("group", cc)
                    requireKnownGroup(group, "$cc.group")
                    val force = f.requireVectorLiteral("force", cc)
                    forces += ConstantForce(group, force, name = f.optionalString("name", context = cc))
                }
                else -> throw YamlLoadException(
                    "$context: unknown force type (expected one of: gravity, mesh_springs, wind, drag, nbody_gravity, constant_force)",
                )
            }
        }
        return forces
    }

    private fun loadConstraints(
        root: Map<*, *>, store: ParticleStore, groups: Groups,
        requireKnownGroup: (String, String) -> Unit,
    ): List<Constraint> {
        val constraints = ArrayList<Constraint>()
        for ((index, entry) in root.requireListOrEmpty("constraints", "root").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("constraints[$index]: expected a mapping")
            val context = "constraints[$index]"
            when {
                map.containsKey("fixed_position") -> {
                    val f = map.requireMap("fixed_position", context)
                    val fpc = "$context.fixed_position"
                    val group = f.requireString("group", fpc)
                    requireKnownGroup(group, "$fpc.group")
                    val name = f.optionalString("name", context = fpc)
                    constraints += if (f.optionalBoolean("at_current_positions", false, fpc)) {
                        FixedPosition.atCurrentPositions(group, store, groups, name = name)
                    } else {
                        FixedPosition(group, f.requireVectorLiteral("position", fpc), name = name)
                    }
                }
                map.containsKey("fixed_velocity") -> {
                    val f = map.requireMap("fixed_velocity", context)
                    val fvc = "$context.fixed_velocity"
                    val group = f.requireString("group", fvc)
                    requireKnownGroup(group, "$fvc.group")
                    val velocity = f.requireVectorLiteral("velocity", fvc)
                    constraints += FixedVelocity(group, velocity, name = f.optionalString("name", context = fvc))
                }
                else -> throw YamlLoadException("$context: unknown constraint type (expected one of: fixed_position, fixed_velocity)")
            }
        }
        return constraints
    }

    /** Phase 4's top-level `colliders:` section (§12.2) — named, infinite-mass geometry,
     * referenced by name from Phase 5's `collisions:`/`destroy:` sections rather than declared
     * inline there, the same "declare once, reference by name" shape `groups:` already uses.
     * `position` is [particlesim.core.VectorExpr] (moving colliders, §12.2/§12.5); every other
     * shape parameter (a plane's `normal`, a sphere's `radius`, a box's `half_extents`) is fixed
     * at construction, matching what [particlesim.collision.Collider] itself marks
     * expression-capable. `name` is required here (unlike a force's optional `name`) since a
     * collider with no name could never be referenced by the sections that need to target it. */
    private fun loadColliders(root: Map<*, *>): Map<String, Collider> {
        val colliders = LinkedHashMap<String, Collider>()
        for ((index, entry) in root.requireListOrEmpty("colliders", "root").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("colliders[$index]: expected a mapping")
            val context = "colliders[$index]"
            val collider: Collider = when {
                map.containsKey("plane") -> {
                    val f = map.requireMap("plane", context)
                    val position = f.requireVectorExpr("position", "$context.plane")
                    val normal = f.requireVectorLiteral("normal", "$context.plane")
                    PlaneCollider(position, normal, name = f.requireString("name", "$context.plane"))
                }
                map.containsKey("sphere") -> {
                    val f = map.requireMap("sphere", context)
                    val position = f.requireVectorExpr("position", "$context.sphere")
                    val radius = f.requireDouble("radius", "$context.sphere")
                    SphereCollider(position, radius, name = f.requireString("name", "$context.sphere"))
                }
                map.containsKey("box") -> {
                    val f = map.requireMap("box", context)
                    val position = f.requireVectorExpr("position", "$context.box")
                    val halfExtents = f.requireVectorLiteral("half_extents", "$context.box")
                    BoxCollider(position, halfExtents, name = f.requireString("name", "$context.box"))
                }
                else -> throw YamlLoadException("$context: unknown collider type (expected one of: plane, sphere, box)")
            }
            if (colliders.put(collider.name!!, collider) != null) {
                throw YamlLoadException("$context: duplicate collider name '${collider.name}'")
            }
        }
        return colliders
    }

    private fun resolveGrid(f: Map<*, *>, grids: Map<String, List<List<Int>>>, context: String): List<List<Int>> {
        val name = f.requireString("grid", context)
        return grids[name] ?: throw YamlLoadException("$context.grid: unknown grid '$name'")
    }

    /** Phase 5's `collisions:` section — two of the four rule/system pairs §12.3 describes
     * (`particle_collider`/`ParticleColliderRule`→`CollisionSystem`, `particle_particle`/
     * `ParticleCollisionRule`→`ParticleCollisionSystem`). `surface_collider`/`surface_self`
     * are deliberately not wired here: neither has a named-surface YAML reference to target yet
     * (surfaces are still implicit, built from a grid), and no target scenario needs either —
     * the same "wait for a real consumer" deferral this codebase already applies elsewhere.
     * `rest_velocity`/`rest_penetration` are one shared top-level pair, not per-rule, since both
     * systems already default to the identical values on the Kotlin side. Returns
     * `(null, null)` when `collisions:` is absent or has no rules of either kind - a scene with
     * neither pays nothing extra. */
    private fun loadCollisions(
        root: Map<*, *>, colliders: Map<String, Collider>, requireKnownGroup: (String, String) -> Unit,
    ): Pair<CollisionSystem?, ParticleCollisionSystem?> {
        val section = root.optionalMap("collisions") ?: return null to null
        val restVelocity = section.optionalDouble("rest_velocity", 0.01, "collisions")
        val restPenetration = section.optionalDouble("rest_penetration", 0.005, "collisions")
        val particleColliderRules = ArrayList<ParticleColliderRule>()
        val particleParticleRules = ArrayList<ParticleCollisionRule>()
        for ((index, entry) in section.requireListOrEmpty("rules", "collisions").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("collisions.rules[$index]: expected a mapping")
            val context = "collisions.rules[$index]"
            when {
                map.containsKey("particle_collider") -> {
                    val f = map.requireMap("particle_collider", context)
                    val fc = "$context.particle_collider"
                    val group = f.requireString("group", fc)
                    requireKnownGroup(group, "$fc.group")
                    val colliderName = f.requireString("collider", fc)
                    val collider = colliders[colliderName] ?: throw YamlLoadException("$fc.collider: unknown collider '$colliderName'")
                    particleColliderRules += ParticleColliderRule(
                        group = group, collider = collider,
                        restitution = f.requireDouble("restitution", fc),
                        compressionDamping = f.optionalDouble("compression_damping", 0.0, fc),
                        extensionDamping = f.optionalDouble("extension_damping", 0.0, fc),
                        correctionFactor = f.optionalDouble("correction_factor", 0.2, fc),
                        staticFriction = f.optionalDouble("static_friction", 0.0, fc),
                        kineticFriction = f.optionalDouble("kinetic_friction", 0.0, fc),
                    )
                }
                map.containsKey("particle_particle") -> {
                    val f = map.requireMap("particle_particle", context)
                    val fc = "$context.particle_particle"
                    val groupA = f.requireString("group_a", fc)
                    requireKnownGroup(groupA, "$fc.group_a")
                    val groupB = f.optionalString("group_b", context = fc) ?: groupA
                    if (groupB != groupA) requireKnownGroup(groupB, "$fc.group_b")
                    particleParticleRules += ParticleCollisionRule(
                        groupA = groupA, groupB = groupB,
                        restitution = f.requireDouble("restitution", fc),
                        compressionDamping = f.optionalDouble("compression_damping", 0.0, fc),
                        extensionDamping = f.optionalDouble("extension_damping", 0.0, fc),
                        correctionFactor = f.optionalDouble("correction_factor", 0.2, fc),
                        staticFriction = f.optionalDouble("static_friction", 0.0, fc),
                        kineticFriction = f.optionalDouble("kinetic_friction", 0.0, fc),
                    )
                }
                else -> throw YamlLoadException("$context: unknown collision rule type (expected one of: particle_collider, particle_particle)")
            }
        }
        val collisionSystem = if (particleColliderRules.isEmpty()) null else CollisionSystem(particleColliderRules, restVelocity, restPenetration)
        val particleCollisionSystem = if (particleParticleRules.isEmpty()) null else ParticleCollisionSystem(particleParticleRules, restVelocity, restPenetration)
        return collisionSystem to particleCollisionSystem
    }

    /** Phase 5's `destroy:` section (§14.2). `outside_box` is a **structural** shape, not a
     * boolean-expression grammar — widening `ScalarExpr`/`VectorExpr`'s `evaluate(t)` signature
     * to carry per-particle position/velocity/comparisons was already ruled out for this pass
     * (see `todo/TODO.md`'s Phase-7 note on `t` being the only working built-in variable);
     * `outside_box`
     * covers `buildSparks`' actual predicate (`abs(x)>10 || abs(z)>10`, an axis-aligned box
     * exit test) without inventing more than that one real consumer needs. `on_collision` maps
     * directly onto [CollisionDestroyRule]. Returns `null` when `destroy:` is empty/absent. */
    private fun loadDestruction(
        root: Map<*, *>, colliders: Map<String, Collider>, requireKnownGroup: (String, String) -> Unit,
    ): DestructionSystem? {
        val entries = root.requireListOrEmpty("destroy", "root")
        if (entries.isEmpty()) return null
        val destroyConditions = ArrayList<DestroyCondition>()
        val collisionDestroyRules = ArrayList<CollisionDestroyRule>()
        for ((index, entry) in entries.withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("destroy[$index]: expected a mapping")
            val context = "destroy[$index]"
            val group = map.requireString("group", context)
            requireKnownGroup(group, "$context.group")
            when {
                map.containsKey("outside_box") -> {
                    val f = map.requireMap("outside_box", context)
                    val oc = "$context.outside_box"
                    val center = f.requireVectorLiteral("center", oc)
                    val halfExtents = f.requireVectorLiteral("half_extents", oc)
                    destroyConditions += DestroyCondition(group) { s, id, _ ->
                        val p = s.position(id)
                        abs(p.x - center.x) > halfExtents.x || abs(p.y - center.y) > halfExtents.y || abs(p.z - center.z) > halfExtents.z
                    }
                }
                map.containsKey("on_collision") -> {
                    val f = map.requireMap("on_collision", context)
                    val colliderName = f.requireString("collider", "$context.on_collision")
                    val collider = colliders[colliderName] ?: throw YamlLoadException("$context.on_collision.collider: unknown collider '$colliderName'")
                    collisionDestroyRules += CollisionDestroyRule(group, collider)
                }
                else -> throw YamlLoadException("$context: unknown destroy condition type (expected one of: outside_box, on_collision)")
            }
        }
        return DestructionSystem(destroyConditions, collisionDestroyRules)
    }

    /** Phase 6's `emitters:` section (§14.1) — wires [Emitter]'s full constructor. `mass`
     * defaults to `Constant(1.0)` when absent, matching the Kotlin constructor's own default;
     * `radius`/`lifetime` stay `null` when absent, matching its nullable defaults exactly (a
     * spawned particle with no radius/lifetime is a real, common case — most YAML scenes won't
     * set one or the other). `rate` is the one expression-capable field ([ScalarExpr], so
     * bursts/ramps like `buildSparks`' `20.0 + 15.0*sin(t*0.5)` are directly expressible as a
     * string); `master_seed` is required, not defaulted, the same §11-determinism reasoning
     * [loadRandomVolume] already applies to its own `seed:`. Registers each emitter's `group`
     * into [declaredGroups] as a side effect — see the call site in [load] for why. */
    private fun loadEmitters(root: Map<*, *>, declaredGroups: MutableSet<String>): List<Emitter> {
        val emitters = ArrayList<Emitter>()
        for ((index, entry) in root.requireListOrEmpty("emitters", "root").withIndex()) {
            val f = entry as? Map<*, *> ?: throw YamlLoadException("emitters[$index]: expected a mapping")
            val context = "emitters[$index]"
            val name = f.requireString("name", context)
            val group = f.requireString("group", context)
            declaredGroups += group
            val rate = f.requireScalarExpr("rate", context)
            val position = f.requireVectorDistribution("position", context)
            val velocity = f.requireVectorDistribution("velocity", context)
            val mass = f.optionalScalarDistribution("mass", context) ?: ScalarDistribution.Constant(1.0)
            val radius = f.optionalScalarDistribution("radius", context)
            val lifetime = f.optionalScalarDistribution("lifetime", context)
            val maxAlive = f.requireInt("max_alive", context)
            val capPolicyStr = f.optionalString("cap_policy", "stop", context)
            val capPolicy = when (capPolicyStr) {
                "stop" -> EmitterCapPolicy.STOP
                "evict_oldest" -> EmitterCapPolicy.EVICT_OLDEST
                else -> throw YamlLoadException("$context.cap_policy: unknown cap policy '$capPolicyStr' (expected stop or evict_oldest)")
            }
            val masterSeed = f["master_seed"] as? Number ?: throw YamlLoadException("$context.master_seed: missing required field")
            emitters += Emitter(
                name = name, group = group, rate = rate, position = position, velocity = velocity,
                mass = mass, radius = radius, lifetime = lifetime,
                maxAlive = maxAlive, capPolicy = capPolicy, masterSeed = masterSeed.toLong(),
                onWarning = onWarning,
            )
        }
        return emitters
    }

    /** Phase 9's `lights:` section — §10.2's `[stretch]` "Lighting & materials," last of the
     * five items TODO.md tracked as this front-end's deferred second pass. Every [Light] field
     * is a plain value on the Kotlin side (no [particlesim.core.ScalarExpr]/
     * [particlesim.core.VectorExpr] anywhere on `Light` — position/color/intensity are only
     * live-editable via §10.4's `EditableFields` mechanism, a completely different, viewer-side-
     * only path, not something YAML would author as an expression), so this is a direct 1:1
     * mapping: `ambient`/`directional`/`point`, each with an optional `color`/`intensity`/`name`
     * (`color` defaulting to white, `intensity` to `1.0` — [Light]'s own constructor defaults),
     * `directional`/`point` additionally requiring `position`. */
    private fun loadLights(root: Map<*, *>): List<Light> {
        val lights = ArrayList<Light>()
        for ((index, entry) in root.requireListOrEmpty("lights", "root").withIndex()) {
            val map = entry as? Map<*, *> ?: throw YamlLoadException("lights[$index]: expected a mapping")
            val context = "lights[$index]"
            lights += when {
                map.containsKey("ambient") -> {
                    val f = map.requireMap("ambient", context)
                    val ac = "$context.ambient"
                    Light.Ambient(color = readColor(f, ac), intensity = f.optionalDouble("intensity", 1.0, ac), name = f.optionalString("name", context = ac))
                }
                map.containsKey("directional") -> {
                    val f = map.requireMap("directional", context)
                    val dc = "$context.directional"
                    Light.Directional(
                        position = f.requireVectorLiteral("position", dc),
                        color = readColor(f, dc),
                        intensity = f.optionalDouble("intensity", 1.0, dc),
                        name = f.optionalString("name", context = dc),
                    )
                }
                map.containsKey("point") -> {
                    val f = map.requireMap("point", context)
                    val pc = "$context.point"
                    Light.Point(
                        position = f.requireVectorLiteral("position", pc),
                        color = readColor(f, pc),
                        intensity = f.optionalDouble("intensity", 1.0, pc),
                        name = f.optionalString("name", context = pc),
                    )
                }
                else -> throw YamlLoadException("$context: unknown light type (expected one of: ambient, directional, point)")
            }
        }
        return lights
    }

    private fun readColor(f: Map<*, *>, context: String): Color {
        val v = f.optionalVectorLiteral("color", context, Vector3(1.0, 1.0, 1.0))
        return Color(v.x, v.y, v.z)
    }
}
