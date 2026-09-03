package particlesim.render

/**
 * §10.2's `[stretch]` "Lighting & materials": per-surface base color, roughness, and opacity —
 * the properties requirements.md calls out ("shininess/opacity"; roughness is three.js's
 * inverse-shininess PBR parameter, matching `MeshStandardMaterial`, the material every shaded
 * surface here already uses).
 *
 * [SurfaceRenderer.material] is nullable, not an instance of this class directly, precisely
 * because "the default" isn't one fixed value: an untextured mesh's historical default is an
 * opaque blue-grey ([DEFAULT_COLOR] — the viewer's original hardcoded `solidMeshMaterial`
 * color); a textured mesh's is untinted white ([UNTINTED], so the image's own colors show
 * through unmultiplied rather than getting tinted by a default that was only ever meant for the
 * untextured case) — see [SurfaceRenderer.effectiveMaterial].
 */
data class Material(
    val color: Color = DEFAULT_COLOR,
    val roughness: Double = 0.85,
    val opacity: Double = 1.0,
) {
    init {
        require(roughness in 0.0..1.0) { "roughness must be in [0,1], was $roughness" }
        require(opacity in 0.0..1.0) { "opacity must be in [0,1], was $opacity" }
    }

    companion object {
        val DEFAULT_COLOR = Color(0x33 / 255.0, 0x88 / 255.0, 0xff / 255.0)
        val UNTINTED = Color(1.0, 1.0, 1.0)
    }
}
