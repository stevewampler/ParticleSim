package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.examples.FIREWORKS_DT
import particlesim.examples.FireworksShow
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.Material
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle

/**
 * §9.6 scene-library demo for [FireworksShow]: a continuous display seen from the crowd, with
 * shells rising from mortars along the ground and bursting overhead against a night sky.
 *
 * Every shell and star is drawn as a glowing dot with a short fading trail behind it
 * ([SceneFrame.trailSeconds]) - the streaks a camera's exposure (or the eye) sees, which is what
 * makes a burst read as a chrysanthemum rather than a cloud of dots. Stars flash white as they
 * ignite and dim as they burn out.
 *
 * `gravity` is live-editable. Unchecking the `stars` group's "enabled" box takes drag off the
 * stars (gravity acts on the `fireworks` group, so it stays): they fly far wider.
 */
class FireworksScene(seed: Long = 1L) : DemoScene {
    private val show = FireworksShow(seed)
    private val events = mutableListOf<SimEvent>()

    // Dark ground under the mortars, on four particles nothing acts on.
    private val groundIds = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sz) ->
        show.store.create(position = Vector3(sx * GROUND_HALF_SIZE, 0.0, sz * GROUND_HALF_SIZE), mass = ScalarExpr.of(1.0))
            .also { show.groups.add("ground", it) }
    }
    private val ground = Surface(
        listOf(Triangle(groundIds[0], groundIds[2], groundIds[1]), Triangle(groundIds[0], groundIds[3], groundIds[2])),
        name = "ground",
    )
    private val groundMesh = SurfaceRenderer(ground, material = Material(color = GROUND_COLOR, roughness = 1.0), pickable = false)

    override val dt = FIREWORKS_DT
    override val store: ParticleStore = show.store

    override fun ids(): List<Int> = show.store.liveIds()

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, show.forces, emptyList(), show.store, t)) return
        if (message is SceneControlMessage.SetGroupEnabled) show.groups.setEnabled(message.name, message.enabled)
    }

    override fun step(t: Double) {
        val result = show.step(t, dt)
        for (id in result.destroyedIds) events += SimEvent.ParticleDestroyed(id)
        for (id in result.spawnedIds) events += SimEvent.ParticleSpawned(id)
    }

    override fun frame(t: Double): SceneFrame {
        val fireworks = show.groups.membersOf(FireworksShow.ALL_GROUP)
        val colors = HashMap<Int, Color>(fireworks.size * 2)
        val radii = HashMap<Int, Double>(fireworks.size * 2)
        for (shell in show.liveShells) {
            colors[shell.id] = SHELL_COLOR
            radii[shell.id] = SHELL_DRAW_RADIUS
        }
        for ((id, base) in show.starColorsById) {
            colors[id] = starColorAt(base, t - show.store.spawnTime(id), show.store.lifetime(id) ?: 1.0)
            radii[id] = STAR_DRAW_RADIUS
        }
        val frame = SceneFrame(
            camera = CAMERA,
            sphereRadii = radii,
            particleColors = colors,
            meshes = listOf(groundMesh),
            visibleIds = fireworks.toSet(),
            skyColor = NIGHT_SKY,
            trailSeconds = TRAIL_SECONDS,
            registry = SceneRegistry.build(forces = show.forces, groups = show.groups, surfaces = listOf(ground)),
            events = events.toList(),
        )
        events.clear()
        return frame
    }

    companion object {
        const val GROUND_HALF_SIZE = 400.0
        const val STAR_DRAW_RADIUS = 0.35
        const val SHELL_DRAW_RADIUS = 0.4
        const val TRAIL_SECONDS = 0.7
        // Linear values: the viewer converts to sRGB for display, which lifts dark colours a lot.
        // These show as a deep navy, like the night sky in a fireworks photograph.
        val NIGHT_SKY = Color(0.003, 0.008, 0.02)
        val GROUND_COLOR = Color(0.002, 0.002, 0.003)
        /** A rising shell's burning fuse: a dim, warm spark. */
        val SHELL_COLOR = Color(0.9, 0.55, 0.25)
        /** From the crowd, a little way back from the mortar line, looking up at the bursts. */
        val CAMERA = CameraPose(position = Vector3(0.0, 2.0, 120.0), lookAt = Vector3(0.0, 108.0, 0.0))

        /** A star's colour [age] seconds into its [burn]: a white flash as it lights, its own
         * [base] colour, then dimming over the last third as it burns out. */
        fun starColorAt(base: Color, age: Double, burn: Double): Color {
            val f = (age / burn).coerceIn(0.0, 1.0)
            val flash = (1.0 - age / FLASH_SECONDS).coerceIn(0.0, 1.0)
            val brightness = if (f < FADE_START) 1.0 else 1.0 - (f - FADE_START) / (1.0 - FADE_START) * 0.85
            fun channel(c: Double) = ((c + (1.0 - c) * flash) * brightness).coerceIn(0.0, 1.0)
            return Color(channel(base.r), channel(base.g), channel(base.b))
        }

        private const val FLASH_SECONDS = 0.12
        private const val FADE_START = 0.65
    }
}
