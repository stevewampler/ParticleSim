package particlesim.render

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Breakable
import particlesim.physics.PairwiseForce
import particlesim.physics.UniformFieldForce
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
 * the universal selector; forces/surfaces as the actual Kotlin objects, since neither has a
 * name→object registry yet) rather than through YAML string lookups. YAML `renderers:` support
 * is a deferred second pass.
 */
sealed interface ParticleStyle {
    data object Dot : ParticleStyle

    /** Uses the group's particles' own `radius` by default; [radiusOverride] draws every
     * particle in the group at a fixed size instead. */
    data class Sphere(val radiusOverride: Double? = null) : ParticleStyle
}

data class ParticleRenderer(val group: String, val style: ParticleStyle = ParticleStyle.Dot)

data class SurfaceRenderer(val triangles: List<Triangle>, val wireframe: Boolean = false)

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
