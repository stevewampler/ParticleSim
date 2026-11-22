package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.examples.FIREWORKS_DT
import particlesim.examples.FireworksShow
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle
import kotlin.math.exp

/**
 * §9.6 scene-library demo for [FireworksShow]: a continuous display seen from the crowd, with
 * shells rising from mortars along the ground and bursting overhead against a night sky.
 *
 * Every shell and star is drawn as a glowing dot with a short fading trail behind it
 * ([SceneFrame.trailSeconds]) - the streaks a camera's exposure (or the eye) sees, which is what
 * makes a burst read as a chrysanthemum rather than a cloud of dots. Stars flash white as they
 * ignite and dim as they burn out; crackling glitter twinkles, strobes blink, and palm fronds are
 * drawn larger than other stars.
 *
 * Each shell's burst lights the ground for a moment: a point light at the burst, in its colour,
 * fading over about a tenth of a second. The rest of the time a faint `moonlight` (an ambient
 * light, live-editable from the outliner) is all that shows the ground.
 *
 * `gravity` is live-editable. Unchecking the `stars` group's "enabled" box takes drag off the
 * stars, and unchecking `shells` takes it off the rising shells (gravity acts on the `fireworks`
 * group, so it stays): stars fly far wider, and shells overshoot their break height and burst
 * still climbing.
 */
class FireworksScene(seed: Long = 1L) : DemoScene {
    internal val show = FireworksShow(seed)
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
    private val moonlight = Light.Ambient(color = MOONLIGHT_COLOR, intensity = MOONLIGHT_INTENSITY, name = "moonlight")

    override val dt = FIREWORKS_DT
    override val store: ParticleStore = show.store

    override fun ids(): List<Int> = show.store.liveIds()

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, show.forces, emptyList(), show.store, t, listOf(moonlight))) return
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
        val glitter = show.glitterIds
        val strobes = show.strobeIds
        val fronds = show.palmFrondIds
        for ((id, base) in show.starColorsById) {
            val color = starColorAt(base, t - show.store.spawnTime(id), show.store.lifetime(id) ?: 1.0)
            colors[id] = when (id) {
                in glitter -> twinkle(color, id, t)
                in strobes -> strobe(color, id, t)
                else -> color
            }
            radii[id] = if (id in fronds) FROND_DRAW_RADIUS else STAR_DRAW_RADIUS
        }
        val frame = SceneFrame(
            camera = CAMERA,
            sphereRadii = radii,
            particleColors = colors,
            meshes = listOf(groundMesh),
            lights = listOf(moonlight) + show.recentFlashes.mapNotNull { flashLight(it, t) },
            visibleIds = fireworks.toSet(),
            skyColor = NIGHT_SKY,
            trailSeconds = TRAIL_SECONDS,
            registry = SceneRegistry.build(forces = show.forces, groups = show.groups, surfaces = listOf(ground), lights = listOf(moonlight)),
            events = events.toList(),
        )
        events.clear()
        return frame
    }

    companion object {
        const val GROUND_HALF_SIZE = 400.0
        const val STAR_DRAW_RADIUS = 0.35
        const val SHELL_DRAW_RADIUS = 0.4
        const val FROND_DRAW_RADIUS = 0.7
        const val TRAIL_SECONDS = 0.7
        // Linear values: the viewer converts to sRGB for display, which lifts dark colours a lot.
        // These show as a deep navy, like the night sky in a fireworks photograph.
        val NIGHT_SKY = Color(0.003, 0.008, 0.02)
        /** Pale enough for a burst's flash to show on; the faint moonlight keeps it near black
         * the rest of the time. */
        val GROUND_COLOR = Color(0.35, 0.33, 0.3)
        val MOONLIGHT_COLOR = Color(0.5, 0.6, 1.0)
        const val MOONLIGHT_INTENSITY = 0.02
        /** A burst flash's peak, in the viewer's point-light units (candela; three.js lights fall
         * off with the inverse square of distance) - 100 m up, about 3 lux on the ground below. */
        const val FLASH_PEAK_INTENSITY = 30000.0
        /** How fast a burst's flash fades: its intensity falls by e every this many seconds. */
        const val FLASH_DECAY_SECONDS = 0.1
        /** A rising shell's burning fuse: a dim, warm spark. */
        val SHELL_COLOR = Color(0.9, 0.55, 0.25)
        /** From the crowd, a little way back from the mortar line, looking up at the bursts -
         * tilted up about 27°, so with the viewer's 60° field of view a strip of ground shows along
         * the bottom (for the flashes to light) and the highest bursts still fit at the top. */
        val CAMERA = CameraPose(position = Vector3(0.0, 3.0, 140.0), lookAt = Vector3(0.0, 72.0, 0.0))

        /** A star's colour [age] seconds into its [burn]: a white flash as it lights, its own
         * [base] colour, then dimming over the last third as it burns out. */
        fun starColorAt(base: Color, age: Double, burn: Double): Color {
            val f = (age / burn).coerceIn(0.0, 1.0)
            val flash = (1.0 - age / FLASH_SECONDS).coerceIn(0.0, 1.0)
            val brightness = if (f < FADE_START) 1.0 else 1.0 - (f - FADE_START) / (1.0 - FADE_START) * 0.85
            fun channel(c: Double) = ((c + (1.0 - c) * flash) * brightness).coerceIn(0.0, 1.0)
            return Color(channel(base.r), channel(base.g), channel(base.b))
        }

        /** The point light lighting the ground for [flash] at time [t], or null once it has
         * faded away (or before it happens). Its colour is the burst's, washed halfway to white
         * - a burst's first instant is mostly white-hot. */
        fun flashLight(flash: particlesim.examples.BurstFlash, t: Double): Light.Point? {
            val age = t - flash.time
            if (age < 0.0) return null
            val intensity = FLASH_PEAK_INTENSITY * exp(-age / FLASH_DECAY_SECONDS)
            if (intensity < FLASH_PEAK_INTENSITY * 0.01) return null
            val c = flash.color
            return Light.Point(
                position = flash.position,
                color = Color((c.r + 1.0) / 2.0, (c.g + 1.0) / 2.0, (c.b + 1.0) / 2.0),
                intensity = intensity,
            )
        }

        /** Glitter's twinkle: each star's brightness jumps at random between 25% and 100%
         * [TWINKLE_RATE] times a second. Hashed from the id and the time slot rather than drawn
         * from a random generator, so a frame's look depends only on its time. */
        fun twinkle(color: Color, id: Int, t: Double): Color =
            scaled(color, 0.25 + 0.75 * hash01(id, (t * TWINKLE_RATE).toLong()))

        /** A strobe's blink: lit for the first [STROBE_DUTY] of each 1/[STROBE_RATE] s cycle and
         * nearly dark the rest, each star at its own phase (hashed from its id) so a burst
         * shimmers rather than flashing in unison. */
        fun strobe(color: Color, id: Int, t: Double): Color {
            val cycle = t * STROBE_RATE + hash01(id, -1L)
            val lit = cycle - kotlin.math.floor(cycle) < STROBE_DUTY
            return if (lit) color else scaled(color, STROBE_OFF_LEVEL)
        }

        private fun scaled(color: Color, level: Double) = Color(color.r * level, color.g * level, color.b * level)

        /** A repeatable pseudo-random number in [0, 1) for a star [id] and a time [slot]. */
        private fun hash01(id: Int, slot: Long): Double {
            var h = id.toLong() * -0x61c8864680b583ebL + slot * 0x9E3779B1L
            h = (h xor (h ushr 31)) * -0x4b47d5b1c1c1c1c1L
            h = h xor (h ushr 29)
            return (h ushr 11).toDouble() / (1L shl 53).toDouble()
        }

        const val TWINKLE_RATE = 20.0
        const val STROBE_RATE = 7.0
        const val STROBE_DUTY = 0.3
        const val STROBE_OFF_LEVEL = 0.03
        private const val FLASH_SECONDS = 0.12
        private const val FADE_START = 0.65
    }
}
