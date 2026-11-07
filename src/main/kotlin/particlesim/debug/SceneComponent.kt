package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.physics.Constraint
import particlesim.physics.Force
import particlesim.physics.Integrator
import particlesim.render.CameraFunction
import particlesim.render.Color
import particlesim.render.ConnectionStyle
import particlesim.render.Light
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface

/** What one [SceneComponent] adds to a frame - the per-structure part of a [SceneFrame]. */
data class ComponentFrame(
    val connections: List<Pair<Int, Int>> = emptyList(),
    val connectionStyles: Map<Pair<Int, Int>, ConnectionStyle> = emptyMap(),
    val lineColors: Map<Pair<Int, Int>, Color> = emptyMap(),
    val meshes: List<SurfaceRenderer> = emptyList(),
    val arrowGroups: List<NamedArrowSamples> = emptyList(),
    val shownByDefaultArrows: Set<String> = emptySet(),
)

/**
 * One self-contained, reusable structure in a scene - a bridge, say - with everything a scene
 * would otherwise hand-roll for it: its physics (forces, constraints), how it's drawn (meshes,
 * styled connections, arrows, colors) and anything it does around each step (arming break
 * limits, reacting to broken members, collisions). Several share one [ParticleStore] and
 * [Groups] (each built with its own [particlesim.examples.ShapePlacement], so their names don't
 * collide) and are stepped together by one [ComponentScene] - so a scene with two bridges side
 * by side is just a [ComponentScene] with two components.
 */
interface SceneComponent {
    /** Every force, as built. Broken ones are dropped from the scene's own step list. */
    val forces: List<Force>
    val constraints: List<Constraint>
    /** Surfaces to list in the outliner. */
    val surfaces: List<Surface>
    /** Every particle this component draws or references. */
    val ids: List<Int>
    /** Particles drawn as dots by default. */
    val visibleIds: Set<Int>

    /** Seconds of settling the component needs (in still air, say) before [start]; the scene
     * settles for the longest any of its components asks for. */
    val settleSeconds: Double get() = 0.0
    fun beginSettle() {}
    fun endSettle() {}

    /** Called once, after any settling, before the first [beforeStep]. */
    fun start() {}

    fun beforeStep(t: Double) {}

    /** After each step, with every force that broke in it (the scene's, not just this
     * component's); returns this component's events. */
    fun afterStep(t: Double, dt: Double, brokenForces: List<Force>): List<SimEvent> = emptyList()

    fun frame(t: Double): ComponentFrame
}

/**
 * A [DemoScene] made of [SceneComponent]s that share [store] and [groups]: one integrator step
 * over all their forces, each component's hooks around it, and their frames merged.
 */
class ComponentScene(
    override val store: ParticleStore,
    private val groups: Groups,
    private val components: List<SceneComponent>,
    override val dt: Double,
    private val camera: CameraFunction,
    private val lights: List<Light> = emptyList(),
) : DemoScene {
    private val integrator = Integrator()
    private val forces = components.flatMap { it.forces }.toMutableList()
    private val constraints = components.flatMap { it.constraints }
    private val events = ArrayList<SimEvent>()

    init {
        val settle = components.maxOfOrNull { it.settleSeconds } ?: 0.0
        if (settle > 0.0) {
            components.forEach { it.beginSettle() }
            var t = 0.0
            repeat((settle / dt).toInt()) {
                integrator.step(store, groups, forces, constraints, t, dt)
                t += dt
            }
            components.forEach { it.endSettle() }
        }
        components.forEach { it.start() }
    }

    private val registry = SceneRegistry.build(
        forces = forces.toList(),
        constraints = constraints,
        surfaces = components.flatMap { it.surfaces },
        groups = groups,
        lights = lights,
    )
    private val scene = SceneQueryImpl(store, groups)
    private val allIds = components.flatMap { it.ids }.distinct()
    private val visibleIds = components.flatMapTo(HashSet()) { it.visibleIds }
    // Structure dots start hidden - the components draw their structure as shapes ("show
    // particles" on a group brings them back).
    private val hiddenByDefaultGroups = groupsHiddenByDefaultExcept(groups, emptyList())

    override fun ids(): List<Int> = allIds

    internal fun groupsOf(id: Int): List<String> = groups.names().filter { id in groups.membersOf(it) }

    override fun step(t: Double) {
        for (c in components) c.beforeStep(t)
        val result = integrator.step(store, groups, forces, constraints, t, dt)
        if (result.brokenForces.isNotEmpty()) forces.removeAll(result.brokenForces.toSet())
        for (c in components) events += c.afterStep(t, dt, result.brokenForces)
    }

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, forces, constraints, store, t, lights)) return
        if (message is SceneControlMessage.SetGroupEnabled) groups.setEnabled(message.name, message.enabled)
    }

    override fun frame(t: Double): SceneFrame {
        val parts = components.map { it.frame(t) }
        val frame = SceneFrame(
            connections = parts.flatMap { it.connections },
            connectionStyles = parts.fold(emptyMap()) { acc, p -> acc + p.connectionStyles },
            lineColors = parts.fold(emptyMap()) { acc, p -> acc + p.lineColors },
            meshes = parts.flatMap { it.meshes },
            arrowGroups = parts.flatMap { it.arrowGroups },
            shownByDefaultArrows = parts.flatMapTo(HashSet()) { it.shownByDefaultArrows },
            camera = camera.evaluate(t, scene),
            visibleIds = visibleIds,
            hiddenByDefaultGroups = hiddenByDefaultGroups,
            registry = registry,
            lights = lights,
            events = events.toList(),
        )
        events.clear()
        return frame
    }
}
