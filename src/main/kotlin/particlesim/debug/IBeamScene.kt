package particlesim.debug

import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.examples.IBeam
import particlesim.examples.STEEL_YIELD_STRESS
import particlesim.physics.Integrator
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.ColorLegend
import particlesim.render.ColorRamp
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.surface.Surface
import particlesim.surface.Triangle

/**
 * §9.6 scene-library demo for [IBeam]: a 4 m steel I-beam (500 mm deep, 300 mm flanges, 100 mm
 * thick flanges and web) resting on two concrete piers, with a weight lowered onto the middle of
 * its top flange. The beam's surface is coloured by von Mises stress, blue for none to red at
 * steel's 250 MPa yield stress and beyond (the colour bar at the bottom right), so the bending
 * shows as compression along the top flange and tension along the bottom, peaking at mid-span,
 * plus the local stress where the weight and the piers press on it.
 *
 * Real steel sags only millimetres under this, so the viewer draws the deformation exaggerated -
 * 100 times by default; DISPLAY's "deformation" slider changes it. The weight's `tonnes` is
 * live-editable from its outliner panel (FORCES: weight): a few hundred tonnes takes the
 * flanges past yield, shown red - the model stays elastic, so past yield it shows what the
 * stress would be, not what a real beam would do.
 *
 * The beam starts unloaded and the weight comes on over about 13 s while the beam settles (its
 * slow sway is the mass-scaled settling described in [IBeam], not a real vibration).
 */
class IBeamScene : DemoScene {
    private val beam = IBeam(origin = Vector3(-2.0, PIER_HEIGHT, -0.15))
    private val lattice = beam.lattice
    private val integrator = Integrator()
    private val skin = lattice.boundarySurface("I-beam")
    private val skinIds = skin.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
    private val restPositions = lattice.restPositions.filterKeys { it in skinIds }

    // The weight: a dark block on the top flange over the nodes that carry it, kept sitting on
    // the flange as it sags.
    private val weightNodes = beam.weightNodes.flatten()
    private val weightBlock: Box
    private val piers: List<Box>
    private val ground: Box

    init {
        val top = PIER_HEIGHT + beam.shape.depth
        val xs = weightNodes.map { lattice.restPositions.getValue(it).x }
        weightBlock = Box(beam.store, Vector3(xs.min(), top, -0.25), Vector3(xs.max(), top + 0.35, 0.25), "weight")
        val ends = listOf(lattice.origin.x, lattice.origin.x + beam.shape.length)
        piers = ends.mapIndexed { i, x -> Box(beam.store, Vector3(x - 0.2, 0.0, -0.35), Vector3(x + 0.2, PIER_HEIGHT, 0.35), "pier ${i + 1}") }
        ground = Box(beam.store, Vector3(-6.0, -0.1, -4.0), Vector3(6.0, 0.0, 4.0), "ground")
    }

    private val weightMesh get() = SurfaceRenderer(
        weightBlock.surface, material = Material(color = Color(0.12, 0.12, 0.14), roughness = 0.6),
        pickable = false, flatShading = true, restPositions = weightBlock.rest, deformationScale = DEFORMATION_SCALE,
    )
    private val pierMeshes = piers.map { SurfaceRenderer(it.surface, material = Material(color = Color(0.55, 0.55, 0.52), roughness = 0.95), pickable = false, flatShading = true) }
    private val groundMesh = SurfaceRenderer(ground.surface, material = Material(color = Color(0.25, 0.3, 0.22), roughness = 1.0), pickable = false, flatShading = true)

    private val lights = listOf(
        Light.Ambient(color = Color(0.85, 0.9, 1.0), intensity = 0.55, name = "sky light"),
        Light.Directional(position = Vector3(3.0, 8.0, 5.0), color = Color(1.0, 0.97, 0.92), intensity = 1.1, name = "sun"),
    )
    private val registry = SceneRegistry.build(
        forces = beam.forces, constraints = beam.constraints, surfaces = listOf(skin), groups = beam.groups, lights = lights,
    )

    override val dt = beam.dt
    override val store: ParticleStore = beam.store

    override fun ids(): List<Int> = beam.store.liveIds()

    override fun step(t: Double) {
        integrator.step(beam.store, beam.groups, beam.forces, beam.constraints, t, dt)
        weightBlock.moveTo(Vector3(0.0, weightSag(), 0.0))
    }

    /** How far the top-flange nodes under the weight have sagged, on average (negative: down). */
    internal fun weightSag(): Double =
        weightNodes.sumOf { beam.store.position(it).y - lattice.restPositions.getValue(it).y } / weightNodes.size

    /** Node [id]'s von Mises stress, pascals. */
    internal fun vonMisesAt(id: Int): Double = lattice.nodeStresses(beam.store, listOf(id)).getValue(id).vonMises

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, beam.forces, beam.constraints, beam.store, t, lights)) return
        if (message is SceneControlMessage.SetGroupEnabled) beam.groups.setEnabled(message.name, message.enabled)
    }

    /** Each skin vertex's colour: its von Mises stress over steel's yield stress, through the
     * stress ramp. */
    fun stressColors(): Map<Int, Color> =
        lattice.nodeStresses(beam.store, skinIds).mapValues { (_, s) -> ColorRamp.stress(s.vonMises / STEEL_YIELD_STRESS) }

    override fun frame(t: Double): SceneFrame = SceneFrame(
        camera = CAMERA,
        meshes = listOf(
            SurfaceRenderer(
                skin, material = Material(color = Color(0.5, 0.5, 0.55), roughness = 0.5), flatShading = true,
                vertexColors = stressColors(), legend = LEGEND, restPositions = restPositions, deformationScale = DEFORMATION_SCALE,
            ),
            weightMesh,
        ) + pierMeshes + groundMesh,
        visibleIds = emptySet(),
        lights = lights,
        registry = registry,
    )

    /** A box of 8 particles (nothing acts on them) and its 12 outward-facing triangles. */
    private class Box(store: ParticleStore, min: Vector3, max: Vector3, name: String) {
        private val store = store
        val ids: List<Int> = (0 until 8).map { c ->
            val p = Vector3(if (c and 1 == 0) min.x else max.x, if (c and 2 == 0) min.y else max.y, if (c and 4 == 0) min.z else max.z)
            store.create(position = p, mass = ScalarExpr.of(1.0))
        }
        val rest: Map<Int, Vector3> = ids.associateWith { store.position(it) }
        val surface = Surface(
            FACES.flatMap { (a, b, c, d) -> listOf(Triangle(ids[a], ids[b], ids[c]), Triangle(ids[a], ids[c], ids[d])) },
            name = name,
        )

        fun moveTo(offset: Vector3) {
            for ((id, p) in rest) {
                store.setPosition(id, p + offset)
                store.setVelocity(id, Vector3.ZERO)
            }
        }

        companion object {
            // Corner c has x from bit 0, y from bit 1, z from bit 2; each face wound outward.
            private val FACES = listOf(
                listOf(0, 4, 6, 2), listOf(1, 3, 7, 5), // -x, +x
                listOf(0, 1, 5, 4), listOf(2, 6, 7, 3), // -y, +y
                listOf(0, 2, 3, 1), listOf(4, 5, 7, 6), // -z, +z
            )
        }
    }

    companion object {
        const val PIER_HEIGHT = 1.0
        const val DEFORMATION_SCALE = 100.0
        val LEGEND = ColorLegend("von Mises stress", "MPa", 0.0, STEEL_YIELD_STRESS / 1e6, ColorRamp.STRESS_STOPS)
        val CAMERA = CameraPose(position = Vector3(3.4, 2.3, 4.4), lookAt = Vector3(0.0, 1.2, 0.0))
    }
}
