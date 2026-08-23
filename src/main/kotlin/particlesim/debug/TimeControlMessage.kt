package particlesim.debug

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Viewer → engine time control (§9.1/§10.3: "pause, speed multiplier, step-once"), the second
 * kind of input on this bidirectional channel alongside [DragMessage] — a client sends whichever
 * `type` applies and this parser routes it, independent of [DragMessage]'s own parse attempt on
 * the same text (see `TimeControl`'s own doc comment for why both are tried on every message
 * rather than one being assumed).
 *
 * Unlike [DragMessage], these carry no `step` stamp: they're viewer-driven meta-controls, not
 * per-particle physics input that a future recording/playback integration would need to replay
 * against an exact step index.
 */
sealed interface TimeControlMessage {
    data object Pause : TimeControlMessage
    data object Resume : TimeControlMessage
    data class SetSpeed(val multiplier: Double) : TimeControlMessage
    data object StepOnce : TimeControlMessage

    companion object {
        /** Returns `null` for anything malformed or unrecognized, same "ignore, don't tear down
         * the connection" stance as [DragMessage.parse]. */
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): TimeControlMessage? {
            val data = try {
                Yaml().load<Any?>(text) as? Map<String, Any?>
            } catch (e: YAMLException) {
                null
            } ?: return null

            return when (data["type"]) {
                "pause" -> Pause
                "resume" -> Resume
                "set_speed" -> {
                    val multiplier = (data["multiplier"] as? Number)?.toDouble() ?: return null
                    // A zero or negative multiplier isn't "very slow" or "reverse" - pause already
                    // exists for "stop," and this engine has no reverse-time concept at all.
                    if (multiplier <= 0.0) return null
                    SetSpeed(multiplier)
                }
                "step_once" -> StepOnce
                else -> null
            }
        }
    }
}
