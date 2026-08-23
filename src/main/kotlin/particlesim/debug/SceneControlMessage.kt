package particlesim.debug

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Viewer → engine scene-mutation input — a third kind of message on the same bidirectional
 * channel as [DragMessage] (per-particle input) and [TimeControlMessage] (playback pacing),
 * for commands that change the *scene itself*: removing a named
 * [particlesim.collision.Collider], or restarting the whole demo from its initial state.
 * Unlike those two, this is deliberately narrow and demo-specific in what it can express — it
 * has no notion of *which* scene a given `restart`/`remove_collider` applies to beyond "whatever
 * demo is running," since (unlike drag/time-control) there's only ever one viewer session
 * talking to one running demo process at a time.
 */
sealed interface SceneControlMessage {
    data class RemoveCollider(val name: String) : SceneControlMessage
    data object Restart : SceneControlMessage

    companion object {
        /** Returns `null` for anything malformed or unrecognized, same "ignore, don't tear down
         * the connection" stance as [DragMessage.parse]/[TimeControlMessage.parse]. */
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): SceneControlMessage? {
            val data = try {
                Yaml().load<Any?>(text) as? Map<String, Any?>
            } catch (e: YAMLException) {
                null
            } ?: return null

            return when (data["type"]) {
                "remove_collider" -> (data["name"] as? String)?.let { RemoveCollider(it) }
                "restart" -> Restart
                else -> null
            }
        }
    }
}
