package particlesim.debug

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Viewer → engine scene-mutation input — a third kind of message on the same bidirectional
 * channel as [DragMessage] (per-particle input) and [TimeControlMessage] (playback pacing),
 * for commands that change the *scene itself*: removing a named
 * [particlesim.collision.Collider], deleting a particle outright (§14.2's "explicit delete via
 * the viewer, alongside interactive dragging" — grouped here with collider removal rather than
 * with [DragMessage] since, unlike a drag target, a delete has no per-step replay stamp to
 * carry and isn't part of that class's Start/Move/End state machine), or restarting the whole
 * demo from its initial state. This is deliberately narrow and demo-specific in what it can
 * express — it has no notion of *which* scene a given command applies to beyond "whatever demo
 * is running," since (unlike drag/time-control) there's only ever one viewer session talking to
 * one running demo process at a time.
 */
sealed interface SceneControlMessage {
    data class RemoveCollider(val name: String) : SceneControlMessage
    data class DeleteParticle(val particleId: Int) : SceneControlMessage
    data object Restart : SceneControlMessage

    /** §10.4's collider activation toggle — distinct from [RemoveCollider]: a deactivated
     * collider stays in the scene (and the outliner) fully able to be turned back on, where
     * removal is permanent for the rest of the run. */
    data class SetColliderActive(val name: String, val active: Boolean) : SceneControlMessage

    /** §10.4's group enable/disable toggle. Applying this is *not* "make [Groups.membersOf]
     * return empty" — that would also blank the group out of the outliner's own member list
     * (the registry snapshot) and the dual-selection lookup a 3D click resolves through,
     * stranding the very checkbox meant to turn it back on. Each demo instead resolves this
     * against [particlesim.core.Groups]' own enabled-state API and the per-step call sites that
     * choose to respect it — see `Groups.isEnabled`/`setEnabled`. */
    data class SetGroupEnabled(val name: String, val enabled: Boolean) : SceneControlMessage

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
                "delete_particle" -> (data["particleId"] as? Number)?.toInt()?.let { DeleteParticle(it) }
                "restart" -> Restart
                "set_collider_active" -> {
                    val name = data["name"] as? String ?: return null
                    val active = data["active"] as? Boolean ?: return null
                    SetColliderActive(name, active)
                }
                "set_group_enabled" -> {
                    val name = data["name"] as? String ?: return null
                    val enabled = data["enabled"] as? Boolean ?: return null
                    SetGroupEnabled(name, enabled)
                }
                else -> null
            }
        }
    }
}
