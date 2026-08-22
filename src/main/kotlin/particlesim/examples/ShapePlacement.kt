package particlesim.examples

import particlesim.core.Vector3

/**
 * A shape's identity within a larger, composed scene (§4.5) — where its particles/colliders
 * sit, and how its internal group/collider names stay distinct from any other shape instance
 * sharing the same scene. Every `build*` function in this package accepts one of these (plus a
 * shared `store`/`groups`, so multiple shapes' particles can coexist with distinct ids) as
 * trailing, defaulted parameters — `ShapePlacement()` (zero offset, no instance name) is
 * exactly what every pre-existing single-shape caller already got, so nothing built before
 * this needed to change.
 *
 * **Position only, deliberately** — no orientation yet. Nothing built so far (`flag`,
 * `ballBounce`) has a shape whose meaning changes under rotation (a flag's pole is always
 * vertical; a ball is a point), so adding rotation now would be guessing at a need rather than
 * building for a concrete one — the same "wait for a real consumer" call already made for
 * spatially-varying wind (§5.2) and N-body/custom force renderers (§10.2).
 *
 * **Naming convention** (§16's previously-open question, resolved here): `instanceName` is
 * prepended to a shape's own local group/collider names as `"$instanceName.$local"` — dotted,
 * matching requirements.md §4.5's own illustrative example (`flag1.cloth`). `instanceName =
 * null` (the default) leaves names completely unprefixed, reproducing a shape's original,
 * single-instance behavior exactly.
 */
data class ShapePlacement(
    val offset: Vector3 = Vector3.ZERO,
    val instanceName: String? = null,
) {
    /** Resolves one of a shape's own local names (a group name, a collider name, ...) against
     * this placement's instance namespace. */
    fun name(local: String): String = if (instanceName == null) local else "$instanceName.$local"
}
