package particlesim.render

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Breakable
import particlesim.physics.PairwiseForce
import particlesim.physics.UniformFieldForce
import particlesim.surface.Surface
import particlesim.surface.Triangle

/**
 * §10.2's renderer declarations: standalone, optional, and outside the physics definition —
 * adding/removing/changing one has zero effect on how the simulation runs, only on what's
 * visible. **Nothing renders unless a renderer targets it**; Phase 3's `--render-all` debug
 * mode stays available as a permanent, separate fallback that ignores these entirely (§10.2's
 * own wording), not replaced by this system.
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
 */
data class SurfaceRenderer(val surface: Surface, val wireframe: Boolean = false, val textureName: String? = null)

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
}
