package particlesim.lifecycle

import particlesim.physics.EditableFields

/**
 * Something that spawns particles by its own rules rather than at an [Emitter]'s rate (§14.1) -
 * e.g. [particlesim.examples.FireworksShow]'s launcher, whose bursts appear wherever a shell
 * happens to be when its fuse runs out. It still belongs with the emitters:
 * - The viewer lists it under EMITTERS, by [name], with its [EditableFields] as the panel's
 *   editable fields (§10.3/§10.4), edited live like a force's.
 * - A §9.5 checkpoint carries its state: [captureState] gives everything it needs to carry on
 *   exactly as it would have (its random stream's position, what it has in flight), as plain
 *   YAML-friendly values - numbers, strings, booleans, and lists and string-keyed maps of them -
 *   and [restoreState] puts that back into a freshly built one, after the checkpoint's particles
 *   and groups are restored.
 */
interface CustomEmitter : EditableFields {
    val name: String

    fun captureState(): Map<String, Any?>

    fun restoreState(state: Map<String, Any?>)
}
