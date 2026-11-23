package particlesim.debug

import particlesim.collision.BoxCollider
import particlesim.collision.PlaneCollider
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.examples.IBeam
import particlesim.physics.LatticeIndex
import particlesim.render.CameraPose
import particlesim.render.Color
import particlesim.render.ColorLegend
import particlesim.render.ColorRamp
import particlesim.render.Light
import particlesim.render.Material
import particlesim.render.ScalarField
import particlesim.render.SceneOption
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import particlesim.render.srgbToLinear
import particlesim.surface.Surface
import particlesim.surface.Triangle
import kotlin.math.sqrt

/**
 * §9.6 scene-library demo for [IBeam]: a 4 m steel I-beam (500 mm deep, 300 mm flanges, 100 mm
 * thick flanges and web) resting on two 0.5 m concrete piers, with a weight lowered onto the
 * middle of its top flange.
 *
 * **Colour.** The beam's surface can be coloured by any of [FIELDS], picked from the colour
 * legend's menu at the bottom right: von Mises stress (blue for none to red at the yield stress),
 * signed bending stress along the beam (compression blue, tension red), shear stress in the web,
 * or permanent stretch where the steel has yielded. "auto-scale" stretches the colours over the
 * frame's own range. The values are computed here; the viewer colours them.
 *
 * **Options** (the viewer's "options" menus):
 * - `failure`: `off` keeps the steel elastic however heavy the weight; `on` lets it yield at
 *   its yield stress and tear at its rupture strain (both editable on FORCES: steel). With the
 *   default 250 MPa steel, beam theory has this beam first yielding at about 270 t and collapsing
 *   above about 360 t: it sags, folds in the middle and cracks open until the middle rests on
 *   the ground, its ends still on the piers (the roller end slides inward as it folds, up to the
 *   pier's edge) - in slow motion, since the masses are scaled ([IBeam]). The piers are low so
 *   that the fold reaches the ground: gravity on pieces of the scaled-up mass would crush them,
 *   so torn-off pieces don't fall on their own.
 * - `springs`: `hidden` shows the surface; `stretch` or `force` shows the springs along the cube
 *   edges instead, coloured by how far each is stretched beyond its rest length or by its tension,
 *   with a legend of their own.
 * - `section`: cuts the beam open to show the stress inside it. `mid-span`, `quarter span` and
 *   `near support` cut across it (at the middle, three quarters of the way along, and half its
 *   depth from the roller end - by symmetry the same as a quarter and near the other end), and
 *   draw only the part on the pinned side; `lengthwise` cuts along the middle of the web and
 *   draws only the back half. The cut face is coloured like the rest of the surface, from the
 *   stress at the nodes there, inside the steel. The springs, and the weight when the cut goes
 *   through it, are cut the same way.
 *
 * Real steel sags only millimetres, so the viewer draws every node's movement exaggerated -
 * 100 times by default (DISPLAY's "deformation" slider). Once the beam has torn or sagged more
 * than 2 cm, real movement is visible and the exaggeration drops to 1.
 *
 * The weight's `tonnes` is live-editable (FORCES: weight).
 */
class IBeamScene : DemoScene {
    private val origin = Vector3(-2.0, PIER_HEIGHT, -0.15)
    private val pierCentresX = listOf(origin.x, origin.x + 4.0)
    private val beam = IBeam(
        origin = origin,
        colliders = listOf(PlaneCollider(VectorExpr.of(Vector3.ZERO), Vector3(0.0, 1.0, 0.0), name = "ground")) +
            pierCentresX.mapIndexed { i, x ->
                // Its top a little below the beam: it only catches a beam that has failed, and
                // doesn't add a second support to one that's merely bending.
                val top = PIER_HEIGHT - PIER_CATCH_GAP
                BoxCollider(VectorExpr.of(Vector3(x, top / 2, 0.0)), Vector3(PIER_HALF_X, top / 2, PIER_HALF_Z), name = "pier ${i + 1}")
            },
        // The roller end stops at the edge of its pier.
        rollerTravel = PIER_HALF_X,
    )
    private val lattice = beam.lattice

    private var failure = false
    private var springs = SPRING_MODES.first()
    private var section = SECTIONS.first()
    private var exaggerationOff = false

    private val shape = beam.shape

    /** The node layer each across-the-beam [SECTIONS] choice cuts at. */
    private val crossCuts = mapOf(
        "mid-span" to shape.lengthCells / 2,
        "quarter span" to shape.lengthCells * 3 / 4,
        "near support" to shape.lengthCells - shape.depthCells / 2,
    )
    private val webMiddle = shape.flangeWidthCells / 2

    /** Whether node [n] is on the drawn side of the [section] cut (or on it). */
    private fun keepsNode(n: LatticeIndex): Boolean {
        crossCuts[section]?.let { return n.i <= it }
        return section != "lengthwise" || n.k <= webMiddle
    }

    /** Whether cube [c] is on the drawn side of the [section] cut. */
    private fun keepsCell(c: LatticeIndex): Boolean {
        crossCuts[section]?.let { return c.i < it }
        return section != "lengthwise" || c.k < webMiddle
    }

    // The skin, rebuilt only when more of the beam has come apart or the cut moves.
    private var skinKey: Pair<Int, String>? = null
    private var skin = lattice.boundarySurface(SKIN_NAME)
    private var skinIds: Set<Int> = emptySet()

    private fun refreshSkin() {
        val key = lattice.failedCells().size to section
        if (key == skinKey) return
        skinKey = key
        skin = lattice.boundarySurface(SKIN_NAME, ::keepsCell)
        skinIds = skin.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet()
    }

    private val edgeSprings = (0 until lattice.springCount).filter { lattice.isEdge(it) }

    // The weight: a dark block on the top flange over the nodes that carry it, moved with them.
    private val weightNodes = beam.weightNodes.flatten()
    private val weightBlock: Box
    // The weight cut by the mid-span and lengthwise sections: its pinned-side and back halves.
    private val weightLeftHalf: Box
    private val weightBackHalf: Box
    private val piers: List<Box>
    private val ground: Box

    init {
        refreshSkin()
        val top = PIER_HEIGHT + beam.shape.depth
        val xs = weightNodes.map { lattice.restPositions.getValue(it).x }
        weightBlock = Box(beam.store, Vector3(xs.min(), top, -0.25), Vector3(xs.max(), top + 0.35, 0.25), "weight")
        val midX = origin.x + crossCuts.getValue("mid-span") * shape.spacing
        weightLeftHalf = Box(beam.store, Vector3(xs.min(), top, -0.25), Vector3(midX.coerceIn(xs.min(), xs.max()), top + 0.35, 0.25), "weight")
        val midZ = origin.z + webMiddle * shape.spacing
        weightBackHalf = Box(beam.store, Vector3(xs.min(), top, -0.25), Vector3(xs.max(), top + 0.35, midZ), "weight")
        piers = pierCentresX.mapIndexed { i, x -> Box(beam.store, Vector3(x - PIER_HALF_X, 0.0, -PIER_HALF_Z), Vector3(x + PIER_HALF_X, PIER_HEIGHT, PIER_HALF_Z), "pier ${i + 1}") }
        ground = Box(beam.store, Vector3(-6.0, -0.1, -4.0), Vector3(6.0, 0.0, 4.0), "ground")
    }

    private val restPositions = lattice.restPositions + weightBlock.rest + weightLeftHalf.rest + weightBackHalf.rest

    private val weightMaterial = Material(color = Color(0.12, 0.12, 0.14), roughness = 0.6)
    private val weightMeshes = mapOf(
        weightBlock to SurfaceRenderer(weightBlock.surface, material = weightMaterial, pickable = false, flatShading = true),
        weightLeftHalf to SurfaceRenderer(weightLeftHalf.surface, material = weightMaterial, pickable = false, flatShading = true),
        weightBackHalf to SurfaceRenderer(weightBackHalf.surface, material = weightMaterial, pickable = false, flatShading = true),
    )

    /** The weight as drawn: cut in half when the section goes through it. */
    private fun weightMesh(): SurfaceRenderer = weightMeshes.getValue(
        when (section) {
            "mid-span" -> weightLeftHalf
            "lengthwise" -> weightBackHalf
            else -> weightBlock
        },
    )
    private val pierMeshes = piers.map { SurfaceRenderer(it.surface, material = Material(color = Color(0.55, 0.55, 0.52), roughness = 0.95), pickable = false, flatShading = true) }
    private val groundMesh = SurfaceRenderer(ground.surface, material = Material(color = Color(0.25, 0.3, 0.22), roughness = 1.0), pickable = false, flatShading = true)

    private val lights = listOf(
        Light.Ambient(color = Color(0.85, 0.9, 1.0), intensity = 0.55, name = "sky light"),
        Light.Directional(position = Vector3(3.0, 8.0, 5.0), color = Color(1.0, 0.97, 0.92), intensity = 1.1, name = "sun"),
    )

    override val dt = beam.dt
    override val store: ParticleStore = beam.store

    override fun ids(): List<Int> = beam.store.liveIds()

    override fun step(t: Double) {
        beam.step(t)
        val offset = weightOffset()
        for (box in weightMeshes.keys) box.moveTo(offset)
    }

    /** How far the top-flange nodes under the weight have moved, on average. */
    private fun weightOffset(): Vector3 {
        var sum = Vector3.ZERO
        for (id in weightNodes) sum += beam.store.position(id) - lattice.restPositions.getValue(id)
        return sum * (1.0 / weightNodes.size)
    }

    /** How far the top-flange nodes under the weight have sagged, on average (negative: down). */
    internal fun weightSag(): Double = weightOffset().y

    /** Node [id]'s von Mises stress, pascals. */
    internal fun vonMisesAt(id: Int): Double = lattice.nodeStresses(beam.store, listOf(id)).getValue(id).vonMises

    internal val hasTorn: Boolean get() = lattice.hasTorn

    /** The beam model itself, for tests and probes. */
    internal val model: IBeam get() = beam

    override fun handleControl(message: SceneControlMessage, t: Double) {
        if (applyEditableFieldMessage(message, beam.forces, beam.constraints, beam.store, t, lights)) return
        when (message) {
            is SceneControlMessage.SetGroupEnabled -> beam.groups.setEnabled(message.name, message.enabled)
            is SceneControlMessage.SetSceneOption -> when (message.name) {
                FAILURE_OPTION -> if (message.choice in ON_OFF) { failure = message.choice == "on"; lattice.failure = failure }
                SPRINGS_OPTION -> if (message.choice in SPRING_MODES) springs = message.choice
                SECTION_OPTION -> if (message.choice in SECTIONS) section = message.choice
            }
            else -> {}
        }
    }

    /** The surface's colour fields, in [FIELDS] order, over the skin's vertices. */
    internal fun scalarFields(): List<ScalarField> {
        val stresses = lattice.nodeStresses(beam.store, skinIds)
        val yieldMPa = lattice.yieldStress / 1e6
        val shearYieldMPa = yieldMPa / sqrt(3.0)
        val plastic = lattice.nodePlasticStrains(skinIds)
        return listOf(
            ScalarField(FIELDS[0], "MPa", 0.0, yieldMPa, ColorRamp.STRESS_STOPS, stresses.mapValues { it.value.vonMises / 1e6 }),
            ScalarField(FIELDS[1], "MPa", -yieldMPa, yieldMPa, ColorRamp.SIGNED_STOPS, stresses.mapValues { it.value.xx / 1e6 }),
            ScalarField(FIELDS[2], "MPa", -shearYieldMPa, shearYieldMPa, ColorRamp.SIGNED_STOPS, stresses.mapValues { it.value.xy / 1e6 }),
            ScalarField(FIELDS[3], "%", 0.0, lattice.ruptureStrain * 100, ColorRamp.STRESS_STOPS, plastic.mapValues { it.value * 100 }),
        )
    }

    private fun springLines(): Triple<List<Pair<Int, Int>>, Map<Pair<Int, Int>, Color>, ColorLegend> {
        val yieldStrain = lattice.yieldStress / lattice.youngsModulus
        val byStretch = springs == "stretch"
        // Full scale: a full-strength spring at the yield strain.
        val full = if (byStretch) yieldStrain else lattice.bulkStiffness * yieldStrain * lattice.spacing
        val connections = ArrayList<Pair<Int, Int>>(edgeSprings.size)
        val colors = HashMap<Pair<Int, Int>, Color>(edgeSprings.size * 2)
        for (s in edgeSprings) {
            if (lattice.isTorn(s)) continue
            val ends = lattice.springEnds(s)
            if (!keepsNode(lattice.indexOf(ends.first)!!) || !keepsNode(lattice.indexOf(ends.second)!!)) continue
            val value = if (byStretch) lattice.elasticStrain(beam.store, s) else lattice.tension(beam.store, s)
            connections += ends
            // Line colours travel as linear values; the ramp is display values, like its legend.
            colors[ends] = ColorRamp.signed(value / full).srgbToLinear()
        }
        val legend = if (byStretch) {
            ColorLegend("spring stretch", "%", -yieldStrain * 100, yieldStrain * 100, ColorRamp.SIGNED_STOPS)
        } else {
            ColorLegend("spring force", "kN", -full / 1000, full / 1000, ColorRamp.SIGNED_STOPS)
        }
        return Triple(connections, colors, legend)
    }

    override fun frame(t: Double): SceneFrame {
        refreshSkin()
        if (!exaggerationOff && !exaggerates(lattice.hasTorn, weightSag())) exaggerationOff = true
        val showSprings = springs != "hidden"
        val lines = if (showSprings) springLines() else null
        val beamMesh = SurfaceRenderer(
            // Unpickable: it's drawn with its deformation exaggerated, so a click on it would land
            // far from the node really there.
            skin, material = Material(color = Color(0.5, 0.5, 0.55), roughness = 0.5), flatShading = true, pickable = false,
            scalarFields = scalarFields(),
        )
        return SceneFrame(
            camera = CAMERA,
            connections = lines?.first ?: emptyList(),
            lineColors = lines?.second ?: emptyMap(),
            lineLegend = lines?.third,
            meshes = (if (showSprings) emptyList() else listOf(beamMesh)) + weightMesh() + pierMeshes + groundMesh,
            visibleIds = emptySet(),
            lights = lights,
            restPositions = restPositions,
            deformationScale = if (exaggerationOff) 1.0 else DEFORMATION_SCALE,
            options = listOf(
                SceneOption(FAILURE_OPTION, ON_OFF, if (failure) "on" else "off"),
                SceneOption(SPRINGS_OPTION, SPRING_MODES, springs),
                SceneOption(SECTION_OPTION, SECTIONS, section),
            ),
            // While the springs show, the skin is left out altogether: listed, it would get a
            // default mesh drawn anyway, and that mesh's edges would hide the springs on it.
            registry = SceneRegistry.build(
                forces = beam.forces, constraints = beam.constraints, surfaces = if (showSprings) emptyList() else listOf(skin),
                groups = beam.groups, lights = lights,
            ),
        )
    }

    /** A box of 8 particles (nothing acts on them) and its 12 outward-facing triangles. */
    private class Box(private val store: ParticleStore, min: Vector3, max: Vector3, name: String) {
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
        /** Low enough that a beam folding in the middle reaches the ground while its roller end
         * (sliding inward as it folds) is still on its pier. */
        const val PIER_HEIGHT = 0.5
        const val PIER_HALF_X = 0.2
        const val PIER_HALF_Z = 0.35
        /** How far below the beam the piers' collision tops sit (see the colliders above). */
        const val PIER_CATCH_GAP = 0.02
        const val DEFORMATION_SCALE = 100.0
        /** Sag past which real movement shows, and exaggeration stops. */
        const val EXAGGERATION_LIMIT = 0.02
        const val SKIN_NAME = "I-beam"
        const val FAILURE_OPTION = "failure"
        const val SPRINGS_OPTION = "springs"
        val ON_OFF = listOf("off", "on")
        val SPRING_MODES = listOf("hidden", "stretch", "force")
        const val SECTION_OPTION = "section"
        val SECTIONS = listOf("off", "mid-span", "quarter span", "near support", "lengthwise")
        val FIELDS = listOf("von Mises stress", "bending stress σxx", "web shear τxy", "permanent stretch")

        /** Whether deformation is still worth exaggerating: not once the beam has torn or sagged
         * past [EXAGGERATION_LIMIT] (negative [sag] is down) - real movement shows by then, and
         * 100 times it would fling the beam across the scene. */
        fun exaggerates(torn: Boolean, sag: Double): Boolean = !torn && sag >= -EXAGGERATION_LIMIT
        val CAMERA = CameraPose(position = Vector3(3.4, 1.8, 4.4), lookAt = Vector3(0.0, 0.7, 0.0))
    }
}
