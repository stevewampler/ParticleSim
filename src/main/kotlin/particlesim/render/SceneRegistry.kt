package particlesim.render

import particlesim.core.Groups
import particlesim.physics.Constraint
import particlesim.physics.Force
import particlesim.surface.Surface

/**
 * §10.3's outliner needs "every group, named force, constraint, ... and surface" reachable
 * regardless of what's currently rendered — this is the read-only projection that answers that,
 * built from the same `forces`/`constraints`/`surfaces` lists and `Groups` instance a scene
 * already assembles (e.g. `FlagScenario.forces`) rather than requiring a second, separate
 * registration call threaded through every builder function.
 *
 * **Groups are the one kind collected differently.** A [Force]/[Constraint]/[Surface] is
 * *optionally* named — an unnamed one still works physically (it's still stepped/drawn), it's
 * just not individually reachable by someone browsing the scene, the same "nothing shows up
 * unless it opts in" policy §10.2 already applies to renderers themselves — so those three kinds
 * are filtered to named entries and exposed as `Map<String, T>`. A group has no such thing as
 * "unnamed": its name *is* its identity ([Groups] is keyed by name from the moment a member is
 * first added, per [Groups.names]), so every group it holds is collected, exposed as a plain
 * `Set<String>` rather than a map — there's no separate object to look up beyond the name and
 * whatever [Groups.membersOf] already answers with it.
 *
 * **Granularity is "one [Force]/[Constraint] object," not one physical connection.**
 * [particlesim.physics.MeshSprings] is a single `Force` representing an entire mesh's worth of
 * edges, so naming it registers "the flag's structural springs" as one outliner entry, not one
 * per edge — matching §10.3's own "a force's current magnitude" framing (one number per force,
 * not per connection). Reaching a specific edge within a named `MeshSprings` needs a different
 * mechanism if that's ever wanted; this registry deliberately doesn't attempt that finer
 * addressing.
 *
 * Names are unique **within their own kind** (forces, constraints, surfaces), not globally — a
 * force and a surface may share a name with no ambiguity, since the outliner lists them in
 * separate sections. A duplicate name within the same kind is an authoring mistake (an ambiguous
 * outliner entry with no way to tell which object it actually refers to) and fails eagerly at
 * construction, the same "validates eagerly" stance [LineRenderer] already takes. Groups need no
 * such check — [Groups] itself can't hold two different things under one name, by construction.
 *
 * Iteration order matches input order ([LinkedHashMap], not a plain `HashMap`) — deliberate, so
 * the outliner lists things in a stable, scene-authored order rather than hash order.
 */
class SceneRegistry private constructor(
    val forces: Map<String, Force>,
    val constraints: Map<String, Constraint>,
    val surfaces: Map<String, Surface>,
    val groups: Set<String>,
) {
    companion object {
        fun build(
            forces: List<Force> = emptyList(),
            constraints: List<Constraint> = emptyList(),
            surfaces: List<Surface> = emptyList(),
            groups: Groups = Groups(),
        ): SceneRegistry =
            SceneRegistry(
                forces = uniqueByName(forces.filter { it.name != null }) { it.name!! },
                constraints = uniqueByName(constraints.filter { it.name != null }) { it.name!! },
                surfaces = uniqueByName(surfaces.filter { it.name != null }) { it.name!! },
                groups = groups.names(),
            )

        private fun <T> uniqueByName(items: List<T>, nameOf: (T) -> String): Map<String, T> {
            val result = LinkedHashMap<String, T>()
            for (item in items) {
                val name = nameOf(item)
                require(result.put(name, item) == null) {
                    "duplicate name \"$name\" - names must be unique within their own kind"
                }
            }
            return result
        }
    }
}
