package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.render.Color
import particlesim.render.Material
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle

// Pieces the crane scenes ([TowerCraneScene], [LuffingCraneScene]) share: boxes drawn on the
// structure (ballast, winches, cab, hook block, load) that move with it, and fixed ones (the
// foundation, the ground).

/** A right-handed frame: [origin] and three unit axes. */
internal data class CraneFrame(val origin: Vector3, val x: Vector3, val y: Vector3, val z: Vector3) {
    fun at(local: Vector3): Vector3 = origin + x * local.x + y * local.y + z * local.z
}

/** A frame at [origin] with x along [along] (levelled) and y up. */
internal fun levelFrame(origin: Vector3, along: Vector3): CraneFrame {
    val x = Vector3(along.x, 0.0, along.z).normalized()
    val up = Vector3(0.0, 1.0, 0.0)
    return CraneFrame(origin, x, up, x.cross(up))
}

/** A box of 8 particles (nothing acts on them) in [store], placed by [place] from [frame] and
 * its local extents [min]..[max]. */
internal class MovingBlock(private val store: ParticleStore, var min: Vector3, var max: Vector3, name: String, private val frame: () -> CraneFrame) {
    val ids: List<Int> = (0 until 8).map { store.create(mass = ScalarExpr.of(1.0)) }
    val surface = boxSurface(ids, name)

    fun place() {
        val f = frame()
        for ((c, id) in ids.withIndex()) {
            store.setPosition(id, f.at(boxCorner(c, min, max)))
            store.setVelocity(id, Vector3.ZERO)
        }
    }
}

/** A box of 8 particles in [store] that stays where it's made. */
internal class FixedBox(store: ParticleStore, min: Vector3, max: Vector3, name: String) {
    val ids: List<Int> = (0 until 8).map { c -> store.create(position = boxCorner(c, min, max), mass = ScalarExpr.of(1.0)) }
    val surface = boxSurface(ids, name)
}

/** A flat-shaded, unpickable mesh of [surface]. */
internal fun craneMesh(surface: Surface, color: Color, roughness: Double = 0.8) =
    SurfaceRenderer(surface, material = Material(color = color, roughness = roughness), pickable = false, flatShading = true)

/** Corner [c] of the box [min]..[max]: x from bit 0, y from bit 1, z from bit 2. */
private fun boxCorner(c: Int, min: Vector3, max: Vector3) =
    Vector3(if (c and 1 == 0) min.x else max.x, if (c and 2 == 0) min.y else max.y, if (c and 4 == 0) min.z else max.z)

private fun boxSurface(ids: List<Int>, name: String) =
    Surface(BOX_FACES.flatMap { (a, b, c, d) -> listOf(Triangle(ids[a], ids[b], ids[c]), Triangle(ids[a], ids[c], ids[d])) }, name = name)

// Each face wound outward.
private val BOX_FACES = listOf(
    listOf(0, 4, 6, 2), listOf(1, 3, 7, 5),
    listOf(0, 1, 5, 4), listOf(2, 6, 7, 3),
    listOf(0, 2, 3, 1), listOf(4, 5, 7, 6),
)
