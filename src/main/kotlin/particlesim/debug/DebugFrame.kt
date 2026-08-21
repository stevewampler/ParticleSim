package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.render.CameraPose
import java.util.Locale

/**
 * Serializes one frame of the Phase 3 debug state stream (§9.1, partial): positions of the
 * given particles and the endpoints of the given connection lines, as JSON text.
 *
 * Connections are plain `(id, id)` pairs, not [particlesim.physics.PairwiseForce] instances
 * directly — [particlesim.physics.MeshSprings] represents many connections as *one* `Force`
 * (§9.3's chunking requirement, see its KDoc), so a single-pair-per-force assumption here
 * would miss a mesh's edges entirely. Callers flatten whatever force shapes they're using
 * (`spring.particleA to spring.particleB`, or `meshSprings.activeConnections()`) into this
 * common list.
 *
 * Still deliberately minimal — no discrete events, no force/collider magnitude data, no binary
 * framing (that's the full §9.1 contract, still ahead). [camera] (§10.1) is the one addition
 * beyond Phase 3's original scope: optional, so a demo with no scripted camera omits the field
 * entirely and the viewer falls back to its own static default rather than requiring every
 * caller to supply one.
 */
object DebugFrame {
    /** [step] is the physics step index this frame was captured after (§9.4) — carried so the
     * viewer can stamp drag messages it sends back with the step they're meant for, without
     * needing to derive it from `t`/`dt` itself (fragile: floating-point `t` accumulation
     * drifting from an integer step count is exactly the kind of mismatch step-stamping exists
     * to avoid). */
    fun render(
        t: Double,
        step: Long,
        store: ParticleStore,
        ids: List<Int>,
        connections: List<Pair<Int, Int>>,
        camera: CameraPose? = null,
    ): String {
        val particles = ids.joinToString(",") { id ->
            val p = store.position(id)
            "{\"id\":$id,\"x\":${fmt(p.x)},\"y\":${fmt(p.y)},\"z\":${fmt(p.z)}}"
        }
        val lines = connections.joinToString(",") { (a, b) -> "{\"a\":$a,\"b\":$b}" }
        val cameraField = if (camera == null) "" else ",\"camera\":${renderCamera(camera)}"
        return "{\"t\":${fmt(t)},\"step\":$step,\"particles\":[$particles],\"connections\":[$lines]$cameraField}"
    }

    private fun renderCamera(camera: CameraPose): String =
        "{\"position\":${renderVector(camera.position)}," +
            "\"lookAt\":${renderVector(camera.lookAt)}," +
            "\"up\":${renderVector(camera.up)}}"

    private fun renderVector(v: particlesim.core.Vector3): String =
        "{\"x\":${fmt(v.x)},\"y\":${fmt(v.y)},\"z\":${fmt(v.z)}}"

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
}
