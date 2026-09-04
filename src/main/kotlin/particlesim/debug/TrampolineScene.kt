package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.examples.TRAMPOLINE_DT
import particlesim.examples.buildTrampoline
import particlesim.physics.Integrator
import particlesim.render.Color
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer

/**
 * §9.6 scene-library wrapping of [TrampolineDebugDemo]'s worked example - see that file's own
 * doc comment for the re-drop cycle and the rim/ball `sphereRadii` override's reasoning
 * (unchanged here).
 */
class TrampolineScene : DemoScene {
    private val scenario = buildTrampoline()
    private val dropPosition = scenario.store.position(scenario.ballId)
    private val cycleSeconds = 10.0
    private val integrator = Integrator()
    private var cycleStart = 0.0

    // §10.2's `[stretch]` "Lighting & materials," worked example: a dark, slightly glossy fabric
    // mat instead of the flat default blue-grey - roughness lower than the default 0.85 for a
    // faint sheen (a woven mat, not a matte cloth), color low enough to read as "black" without
    // crushing to pure 0 and losing all shading detail under the point light below.
    private val matMesh = SurfaceRenderer(
        scenario.surface, wireframe = false,
        material = Material(color = Color(0.05, 0.05, 0.07), roughness = 0.65),
    )
    // A deliberately different rig from the viewer's own default (dim hemisphere + one flat sun)
    // - proves lights are actually configurable, not just present. A dim, cool ambient fill keeps
    // the mat's dark color from crushing to pure black in shadow; a warm directional sun gives the
    // scene an overall direction; a warm point light positioned above the mat's center - roughly
    // where the ball actually bounces - puts a bright highlight exactly where the eye is drawn,
    // strengthening (not fighting) the mat's own low roughness sheen.
    // Named (unlike most demos' forces/constraints, naming is optional there too, but every
    // light here is named deliberately) so all three reach §10.3's outliner and are individually
    // selectable/editable - the point this scene makes ("lights are actually configurable") isn't
    // fully real until they're reachable from the UI, not just visible in the render.
    private val lights = listOf(
        Light.Ambient(color = Color(0.6, 0.65, 0.78), intensity = 0.35, name = "ambient-fill"),
        Light.Directional(
            position = Vector3(4.0, 8.0, 3.0), color = Color(1.0, 0.96, 0.9), intensity = 0.7,
            name = "sun",
        ),
        // 45.0, not something in the ~0.7 range like its neighbors above: three.js (>=r155, no
        // useLegacyLights) gives PointLight physically-correct candela units with inverse-square
        // distance falloff, while Ambient/Directional keep the old unitless scalar - a point light
        // needs roughly a 4pi x multiplier over the legacy-equivalent value before falloff eats it,
        // hence tens instead of fractions. Tuned live at this mat's ~3m light-to-surface distance;
        // don't "fix" it down to match the other two without re-verifying the highlight is still visible.
        Light.Point(
            position = Vector3(0.0, 3.0, 0.0), color = Color(1.0, 0.85, 0.6), intensity = 45.0,
            name = "bounce-highlight",
        ),
    )
    private val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.surface),
        groups = scenario.groups,
        lights = lights,
    )
    private val allIds = scenario.grid.flatten() + scenario.ballId
    private val rimIds = scenario.groups.membersOf("rim")
    private val sphereRadii = rimIds.associateWith { 0.03 } + (scenario.ballId to 0.12)
    private val visibleIds = rimIds + scenario.ballId

    override val dt = TRAMPOLINE_DT
    override val store: ParticleStore = scenario.store

    override fun ids(): List<Int> = allIds

    override fun step(t: Double) {
        if (t - cycleStart >= cycleSeconds) {
            scenario.store.setPosition(scenario.ballId, dropPosition)
            scenario.store.setVelocity(scenario.ballId, Vector3.ZERO)
            cycleStart = t
        }
        integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, dt)
        scenario.collisions.resolve(scenario.store, scenario.groups, t, dt)
    }

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, scenario.forces, scenario.constraints, scenario.store, t, lights)) return
        if (message is SceneControlMessage.SetGroupEnabled) scenario.groups.setEnabled(message.name, message.enabled)
    }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        sphereRadii = sphereRadii,
        meshes = listOf(matMesh),
        visibleIds = visibleIds,
        registry = registry,
        lights = lights,
    )
}
