package particlesim.render

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Breakable
import particlesim.physics.Force
import particlesim.physics.PairwiseForce
import particlesim.physics.UniformFieldForce
import particlesim.surface.Surface
import particlesim.surface.Triangle

/**
 * §10.2's renderer declarations: standalone, optional, and outside the physics definition —
 * adding/removing/changing one has zero effect on how the simulation runs, only on what's
 * visible. **Nothing renders unless a renderer targets it**; Phase 3's `--render-all` debug
 * mode stays available as a permanent, separate fallback that ignores these entirely (§10.2's
 * own wording), not replaced by this system. A scene can still always build one of these
 * explicitly (e.g. FlagScene's hand-tuned `windArrows`, a textured `SurfaceRenderer`) — that
 * always wins — but a named field force or surface a scene *doesn't* bother to declare one for
 * is no longer invisible to the UI either: [particlesim.debug.DebugRenderer.broadcast] fills in
 * a plain default ([ArrowSampling.defaultGroupsFor], [defaultSurfaceRenderers]) for anything the
 * registry carries that's left uncovered, so §10.3's per-object toggles are driven by the
 * object's *type* (is it a [UniformFieldForce]? a named [Surface]?), not by which scene it's in.
 *
 * Kotlin-DSL-first, same status as every other post-Phase-7 feature: renderers reference
 * groups/forces/surfaces directly (a group by name since [particlesim.core.Groups] already is
 * the universal selector; forces/surfaces as the actual Kotlin objects — [SceneRegistry] gives
 * *named* ones a place to be looked up by §10.3's outliner, but a renderer here still holds the
 * object itself, not a string) rather than through YAML string lookups. YAML `renderers:`
 * support is a deferred second pass.
 */
sealed interface ParticleStyle {
    data object Dot : ParticleStyle

    /** Uses the group's particles' own `radius` by default; [radiusOverride] draws every
     * particle in the group at a fixed size instead. */
    data class Sphere(val radiusOverride: Double? = null) : ParticleStyle
}

data class ParticleRenderer(val group: String, val style: ParticleStyle = ParticleStyle.Dot)

/**
 * Holds the [Surface] itself, not just its `triangles`, so the outliner (§10.3) can answer
 * "is this named surface currently rendered?" by identity — a correlation that's only possible
 * if the renderer and the registry (§10.3's engine-side prerequisite) point at the same object,
 * not two independently-built triangle lists that happen to match.
 *
 * [textureName] is `null` by default (flat shaded color, unchanged behavior) — set to one of
 * [TextureAssets]'s known names to map an image onto the mesh instead (§10.2, `[stretch]`).
 * References an asset by *name*, not raw bytes: the image is served once as a static file by
 * `particlesim.debug.ViewerHttpServer`'s `/textures/` route and cached client-side, not pushed
 * through the per-frame binary protocol the surface's own vertex positions are (an image doesn't
 * change every step the way positions do). Meaningful only alongside [Surface.uvs] — a textured
 * surface with no UV data renders with a degenerate (all-zero) mapping.
 *
 * [material] is `null` by default — see [effectiveMaterial] for what that resolves to and why.
 */
data class SurfaceRenderer(
    val surface: Surface,
    val wireframe: Boolean = false,
    val textureName: String? = null,
    val material: Material? = null,
) {
    /** §10.2's `[stretch]` "Lighting & materials": [material] verbatim when this renderer
     * declared one, otherwise the exact hardcoded appearance this class rendered with before
     * material customization existed — opaque blue-grey for a flat-shaded mesh, or untinted
     * white so a [textureName]'d mesh's own colors show through rather than getting multiplied
     * by a default that was only ever meant for the untextured case. Always resolved to a
     * concrete [Material] here (not left for the wire format or the client to guess at), the
     * same "server computes, client just draws" split every other renderer-declaration field in
     * this codebase already follows. */
    val effectiveMaterial: Material
        get() = material ?: Material(color = if (textureName != null) Material.UNTINTED else Material.DEFAULT_COLOR)
}

/** Every named [Surface] in [surfaces] that isn't already covered by [explicitNames] (a scene's
 * own hand-declared [SurfaceRenderer]s, keyed by the surface name they target — e.g. FlagScene's
 * textured `clothMesh`) gets a plain flat-shaded [SurfaceRenderer] instead. Without this, the
 * outliner's "show mesh" toggle (§10.3) is offered unconditionally for every named surface
 * (same as groups' "show particles") but silently does nothing for one a scene named without
 * ever wiring a mesh renderer for it (`MultiShapeScene`'s flag surface, say) — the same
 * scene-specific gap [ArrowSampling.defaultGroupsFor] closes for field forces, applied here to
 * surfaces. An explicit renderer (a texture, wireframe, custom material) always wins. */
fun defaultSurfaceRenderers(surfaces: Map<String, Surface>, explicitNames: Set<String>): List<SurfaceRenderer> =
    surfaces.filterKeys { it !in explicitNames }.values.map { SurfaceRenderer(it) }

/** What a [LineRenderer]'s color maps from (§10.2). Only [BREAK_PROXIMITY] is implemented —
 * `stretch`/`force` magnitude coloring from the spec's own list is a deferred follow-up: unlike
 * `breakProximity`, neither has a single definition that means the same thing across every
 * [PairwiseForce] type (a `Damper` has no rest length for "stretch" to mean anything against),
 * so it needs its own design pass rather than being guessed at here. */
enum class ColorBy { NONE, BREAK_PROXIMITY }

/** A line between a [PairwiseForce]'s two connected particles (§10.2), optionally colored by
 * [colorBy]. Validates eagerly rather than silently doing nothing: declaring
 * `colorBy = BREAK_PROXIMITY` on a force that isn't [Breakable] is almost certainly an
 * authoring mistake ("why isn't my spring changing color?"), so it fails at construction
 * instead of quietly rendering an uncolored line forever. */
data class LineRenderer(val force: PairwiseForce, val colorBy: ColorBy = ColorBy.NONE) {
    init {
        if (colorBy == ColorBy.BREAK_PROXIMITY) {
            require(force is Breakable) { "colorBy=BREAK_PROXIMITY requires a Breakable force, but $force isn't one" }
        }
    }
}

/** Resolves a [LineRenderer]'s current color, or `null` if it isn't colored (§10.2). */
object LineRendering {
    fun colorFor(renderer: LineRenderer, store: ParticleStore): Color? = when (renderer.colorBy) {
        ColorBy.NONE -> null
        ColorBy.BREAK_PROXIMITY -> ColorRamp.blueOrange((renderer.force as Breakable).breakProximity(store))
    }
}

/** A directional field force sampled on a grid over a region (§10.2) — a field isn't localized
 * to specific particles, so its renderer needs a sampling region+resolution instead of a group
 * target. */
data class ArrowRenderer(
    val force: UniformFieldForce,
    val regionMin: Vector3,
    val regionMax: Vector3,
    val resolution: Double,
) {
    init {
        require(resolution > 0.0) { "resolution must be positive, was $resolution" }
        require(regionMin.x <= regionMax.x && regionMin.y <= regionMax.y && regionMin.z <= regionMax.z) {
            "regionMin must be componentwise <= regionMax"
        }
    }
}

data class ArrowSample(val origin: Vector3, val vector: Vector3)

/** One named force's arrow samples for a frame — [ArrowSample] itself carries no source tag, so
 * without this a per-force visibility toggle (§10.3) couldn't tell which force's arrows a given
 * sample belongs to, the same association a mesh already gets for free via
 * [particlesim.debug.DecodedMesh.name]. [name] is `""` for an unnamed force, the same
 * "not individually reachable in the outliner" convention every other wire-format name uses —
 * an unnamed force's arrows still draw, they just can't be hidden by name. */
data class NamedArrowSamples(val name: String, val samples: List<ArrowSample>)

/** Visual length scale applied to a field force's raw sampled vector (an acceleration in m/s²,
 * a velocity in m/s, whatever the force's own units are) before it reaches the wire, so an
 * arrow reads at a reasonable length against the demo library's typical ~1-10 unit scenes
 * rather than a literal 1:1 mapping (gravity's `9.8` would draw a shaft ten times the flag's own
 * width). The same factor FlagScene's wind/gravity arrows were hand-tuned to before this became
 * a shared default (§10.2) — kept as one constant so every field force, in every scene, scales
 * consistently rather than each scene picking its own number. */
const val DEFAULT_ARROW_VISUAL_SCALE = 0.15

object ArrowSampling {
    /** Every grid point across [renderer]'s region at its resolution, paired with the force's
     * value there (§10.2). Every [UniformFieldForce] implementation today is spatially uniform
     * (see that interface's own doc comment), so every sample currently shares one vector
     * value — the grid-of-points structure is still built generically so a future spatially-
     * varying force (gusty wind, §5.2) works here with no change to this function. */
    fun sample(renderer: ArrowRenderer, t: Double): List<ArrowSample> {
        val samples = ArrayList<ArrowSample>()
        var x = renderer.regionMin.x
        while (x <= renderer.regionMax.x) {
            var y = renderer.regionMin.y
            while (y <= renderer.regionMax.y) {
                var z = renderer.regionMin.z
                while (z <= renderer.regionMax.z) {
                    val origin = Vector3(x, y, z)
                    samples += ArrowSample(origin, renderer.force.sampleAt(origin, t))
                    z += renderer.resolution
                }
                y += renderer.resolution
            }
            x += renderer.resolution
        }
        return samples
    }

    /** Every named [UniformFieldForce] in [forces] that isn't already covered by [explicitNames]
     * (a scene's own hand-declared [ArrowRenderer]s, keyed by force name — e.g. FlagScene's
     * hand-tuned `windArrows`) gets a default sampling region/resolution derived from [store]'s
     * current [ids] bounding box, scaled by [DEFAULT_ARROW_VISUAL_SCALE]. This is what makes the
     * "show arrows" toggle (§10.3) real for *every* field force in *every* scene regardless of
     * whether that scene's author remembered to build an [ArrowRenderer] for it — the UI is
     * driven by force type ([UniformFieldForce]), not by what any one scene happens to declare.
     * An explicit renderer always wins; this only fills the gap for anything left uncovered. */
    fun defaultGroupsFor(
        forces: Map<String, Force>,
        explicitNames: Set<String>,
        store: ParticleStore,
        ids: List<Int>,
        t: Double,
    ): List<NamedArrowSamples> {
        if (ids.isEmpty()) return emptyList()
        val (regionMin, regionMax) = defaultRegion(store, ids)
        val resolution = defaultResolution(regionMin, regionMax)
        return forces.mapNotNull { (name, force) ->
            if (name in explicitNames || force !is UniformFieldForce) return@mapNotNull null
            val renderer = ArrowRenderer(force, regionMin, regionMax, resolution)
            val samples = sample(renderer, t).map { it.copy(vector = it.vector * DEFAULT_ARROW_VISUAL_SCALE) }
            NamedArrowSamples(name, samples)
        }
    }

    /** [ids]'s current bounding box (§10.2's default arrow region), expanded by a margin so the
     * sampled grid doesn't sit exactly on the scene's own particles — at least 25% of each
     * dimension's own span, floored at 0.3 so a scene with a near-degenerate extent (a single
     * particle, or one flattened onto a plane) still gets a usable, non-zero-size region. */
    private fun defaultRegion(store: ParticleStore, ids: List<Int>): Pair<Vector3, Vector3> {
        var min = store.position(ids[0])
        var max = min
        for (id in ids) {
            val p = store.position(id)
            min = Vector3(minOf(min.x, p.x), minOf(min.y, p.y), minOf(min.z, p.z))
            max = Vector3(maxOf(max.x, p.x), maxOf(max.y, p.y), maxOf(max.z, p.z))
        }
        val margin = Vector3(
            maxOf((max.x - min.x) * 0.25, 0.3),
            maxOf((max.y - min.y) * 0.25, 0.3),
            maxOf((max.z - min.z) * 0.25, 0.3),
        )
        return (min - margin) to (max + margin)
    }

    /** A resolution giving roughly 3 samples across the region's longest axis — dense enough to
     * read as a field rather than one lonely arrow, sparse enough not to flood a large scene with
     * thousands of them. Floored at 0.3 for the same near-degenerate-extent reason [defaultRegion]
     * floors its margin. */
    private fun defaultResolution(regionMin: Vector3, regionMax: Vector3): Double {
        val span = maxOf(regionMax.x - regionMin.x, regionMax.y - regionMin.y, regionMax.z - regionMin.z)
        return maxOf(span / 3.0, 0.3)
    }
}
