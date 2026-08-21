package particlesim.debug

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import particlesim.core.Vector3

/**
 * Viewer → engine drag input (§9.4), the one case where this bidirectional channel carries
 * anything back. Each message is stamped with the physics step index it's meant for — kept
 * in the wire format even though *live* mode (the only mode this drives today) just applies
 * whichever message arrived most recently rather than doing step-ordered replay; a future
 * recording/playback integration (not built yet — §9.2's recording format has no discrete-
 * event channel to carry drag input through) will need that stamp, so the protocol carries it
 * from the start rather than bolting it on later.
 *
 * Parsed with SnakeYAML (already a dependency, added for Phase 7's YAML front-end) rather than
 * adding a JSON library — the same call already made for the checkpoint sidecar (§9.5), just
 * parsing here instead of dumping.
 */
sealed interface DragMessage {
    data class Start(val particleId: Int, val step: Long, val target: Vector3) : DragMessage
    data class Move(val step: Long, val target: Vector3) : DragMessage
    data class End(val step: Long) : DragMessage

    companion object {
        /** Returns `null` for anything malformed or unrecognized — a client sending garbage
         * (or a future message type an older server doesn't know about yet) is silently
         * ignored, not a reason to tear down the whole WebSocket connection. */
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): DragMessage? {
            val data = try {
                Yaml().load<Any?>(text) as? Map<String, Any?>
            } catch (e: YAMLException) {
                null
            } ?: return null

            val step = (data["step"] as? Number)?.toLong() ?: return null
            return when (data["type"]) {
                "drag_start" -> {
                    val particleId = (data["particleId"] as? Number)?.toInt() ?: return null
                    val target = vectorOf(data) ?: return null
                    Start(particleId, step, target)
                }
                "drag_move" -> vectorOf(data)?.let { Move(step, it) }
                "drag_end" -> End(step)
                else -> null
            }
        }

        private fun vectorOf(data: Map<String, Any?>): Vector3? {
            val x = (data["x"] as? Number)?.toDouble() ?: return null
            val y = (data["y"] as? Number)?.toDouble() ?: return null
            val z = (data["z"] as? Number)?.toDouble() ?: return null
            return Vector3(x, y, z)
        }
    }
}
