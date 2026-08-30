package particlesim.debug

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.expr.ExpressionException
import particlesim.expr.ExpressionParser

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

    /** §10.4's numeric field editing write path — [kind] is `"force"` or `"constraint"`,
     * matching [particlesim.debug.DecodedFieldEntry.kind] on the read side. Two message types
     * (scalar vs. vector), not one carrying a variant payload, because SnakeYAML/JSON has no
     * cheap way to express "this field is either a bare number or an {x,y,z} object" other than
     * checking which keys showed up — simpler to just let the client pick the right message
     * shape for the field it's editing. */
    data class SetScalarField(val kind: String, val name: String, val field: String, val value: Double) : SceneControlMessage
    data class SetVectorField(val kind: String, val name: String, val field: String, val value: Vector3) : SceneControlMessage

    /** §10.4's particle mass/radius live editing — id-addressed, unlike [SetScalarField]/
     * [SetVectorField]'s name-addressing, since particles have no name. Carries a parsed
     * [ScalarExpr], not a raw `Double`: the edit input is an expression string (e.g. `"sin(t)"`),
     * parsed here via [ExpressionParser.parseScalar] rather than as a plain number, so a full
     * replace of the particle's stored expression is the time-variance-preserving option with no
     * separate override layer needed (see `ParticleStore.setMass`/`setRadius`). [field] is
     * `"mass"` or `"radius"`. */
    data class SetParticleScalarField(val particleId: Int, val field: String, val expr: ScalarExpr) : SceneControlMessage

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
                "set_scalar_field" -> {
                    val kind = data["kind"] as? String ?: return null
                    val name = data["name"] as? String ?: return null
                    val field = data["field"] as? String ?: return null
                    val value = (data["value"] as? Number)?.toDouble() ?: return null
                    SetScalarField(kind, name, field, value)
                }
                "set_vector_field" -> {
                    val kind = data["kind"] as? String ?: return null
                    val name = data["name"] as? String ?: return null
                    val field = data["field"] as? String ?: return null
                    val target = vectorOf(data) ?: return null
                    SetVectorField(kind, name, field, target)
                }
                "set_particle_scalar_field" -> {
                    val particleId = (data["particleId"] as? Number)?.toInt() ?: return null
                    val field = data["field"] as? String ?: return null
                    val expression = data["expression"] as? String ?: return null
                    val expr = try {
                        ExpressionParser.parseScalar(expression)
                    } catch (e: ExpressionException) {
                        return null
                    }
                    SetParticleScalarField(particleId, field, expr)
                }
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
