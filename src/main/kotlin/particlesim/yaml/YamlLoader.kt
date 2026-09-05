package particlesim.yaml

import org.yaml.snakeyaml.Yaml
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.ConstantForce
import particlesim.physics.Constraint
import particlesim.physics.Drag
import particlesim.physics.FixedPosition
import particlesim.physics.Force
import particlesim.physics.MeshSprings
import particlesim.physics.NBodyGravity
import particlesim.physics.UniformGravity
import particlesim.physics.Wind
import particlesim.surface.Grid
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
        val root = (Yaml().load<Any?>(yamlText) as? Map<*, *>)
            ?: throw YamlLoadException("root document must be a mapping")

        val version = root["version"] ?: throw YamlLoadException("missing required top-level field 'version'")
        if (version != 1) throw YamlLoadException("unsupported version '$version' (only version 1 is supported)")

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

        val groupNames = resolveGroupsSection(root.requireListOrEmpty("groups", "root"), groups, grids, tagIndex, authorIds, declaredGroups)

        fun requireKnownGroup(name: String, context: String) {
            if (name !in declaredGroups) throw YamlLoadException("$context: unknown group '$name'")
        }

        val forces = loadForces(root, store, grids, ::requireKnownGroup)
        val constraints = loadConstraints(root, store, groups, ::requireKnownGroup)

        for (name in groupNames) {
            if (groups.membersOf(name).isEmpty()) onWarning("group '$name' matches zero particles")
        }

        return YamlScenario(store, groups, forces, constraints, grids)
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
        val spacing = gridSection.optionalDouble("spacing", 1.0)
        val massExpr = gridSection.requireScalarExpr("mass", context)
        val tags = gridSection.requireStringList("tags", context)

        val grid = (0 until rows).map { r ->
            (0 until cols).map { c ->
                val id = try {
                    store.create(position = Vector3(c * spacing, -r * spacing, 0.0), mass = massExpr)
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
        val authorId = f.optionalString("id") ?: return
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
                    forces += UniformGravity(group, accel, name = f.optionalString("name"))
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
                        name = f.optionalString("name"),
                    )
                }
                map.containsKey("wind") -> {
                    val f = map.requireMap("wind", context)
                    val grid = resolveGrid(f, grids, "$context.wind")
                    val triangles = Grid.triangles(grid)
                    val velocity = f.requireVectorExpr("velocity", "$context.wind")
                    val density = f.optionalDouble("density", 1.0)
                    forces += Wind(triangles, velocity, density = density, name = f.optionalString("name"))
                }
                map.containsKey("drag") -> {
                    val f = map.requireMap("drag", context)
                    val group = f.requireString("group", "$context.drag")
                    requireKnownGroup(group, "$context.drag.group")
                    val coefficient = f.requireDouble("coefficient", "$context.drag")
                    val quadratic = f.optionalBoolean("quadratic", false)
                    forces += Drag(group, coefficient, quadratic = quadratic, name = f.optionalString("name"))
                }
                map.containsKey("nbody_gravity") -> {
                    val f = map.requireMap("nbody_gravity", context)
                    val group = f.requireString("group", "$context.nbody_gravity")
                    requireKnownGroup(group, "$context.nbody_gravity.group")
                    // 6.674e-11 mirrors NBodyGravity's own constructor default exactly (no
                    // named constant on that side to reference - it's an inline literal there
                    // too); DEFAULT_SOFTENING is a real exposed constant, used directly.
                    val g = f.optionalDouble("g", 6.674e-11)
                    val softening = f.optionalDouble("softening", NBodyGravity.DEFAULT_SOFTENING)
                    forces += NBodyGravity(group, g = g, softening = softening, name = f.optionalString("name"))
                }
                map.containsKey("constant_force") -> {
                    // §6's "fixed force" - implemented as a Force (ConstantForce), not a
                    // Constraint, since it's just an externally supplied force term, not a
                    // pinned state (see requirements.md §6's own distinction).
                    val f = map.requireMap("constant_force", context)
                    val group = f.requireString("group", "$context.constant_force")
                    requireKnownGroup(group, "$context.constant_force.group")
                    val force = f.requireVectorLiteral("force", "$context.constant_force")
                    forces += ConstantForce(group, force, name = f.optionalString("name"))
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
                    val group = f.requireString("group", "$context.fixed_position")
                    requireKnownGroup(group, "$context.fixed_position.group")
                    constraints += if (f.optionalBoolean("at_current_positions", false)) {
                        FixedPosition.atCurrentPositions(group, store, groups)
                    } else {
                        FixedPosition(group, f.requireVectorLiteral("position", "$context.fixed_position"))
                    }
                }
                else -> throw YamlLoadException("$context: unknown constraint type (expected: fixed_position)")
            }
        }
        return constraints
    }

    private fun resolveGrid(f: Map<*, *>, grids: Map<String, List<List<Int>>>, context: String): List<List<Int>> {
        val name = f.requireString("grid", context)
        return grids[name] ?: throw YamlLoadException("$context.grid: unknown grid '$name'")
    }
}
