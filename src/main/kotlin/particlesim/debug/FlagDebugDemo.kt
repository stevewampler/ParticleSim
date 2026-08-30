package particlesim.debug

import particlesim.core.Vector3
import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import particlesim.physics.Constraint
import particlesim.physics.DragConstraint
import particlesim.physics.EditableFields
import particlesim.physics.FieldValue
import particlesim.physics.Integrator
import particlesim.physics.Wind
import particlesim.render.ArrowRenderer
import particlesim.render.ArrowSampling
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.NamedArrowSamples
import particlesim.render.SceneQueryImpl
import particlesim.render.SceneRegistry
import particlesim.render.SurfaceRenderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A visible run of §7.3's flag worked example through Phase 3's debug renderer: `./gradlew
 * runFlagDemo`, then open the URL it prints.
 *
 * Also §10.1's first worked example: a scripted camera orbiting the cloth's centroid while
 * looking at the free corner (farthest from the pole, so the most visually dynamic point) —
 * almost exactly the requirements doc's own motivating example for camera scripting.
 *
 * And §9.4's drag, wired the same way `DragDebugDemo` already does it — a cloth particle can
 * be grabbed and pulled, same as the spring-chain's, but here it demonstrates propagation
 * through a whole sheet instead of a line.
 *
 * And §10.2's full renderer-declaration set — almost exactly the requirements doc's own
 * *extended* flag example: "the flag surface itself renders as a shaded mesh, the pole-edge
 * particles render as small spheres so the anchor is visible, and the wind force gets an
 * arrow renderer sampled around the flag." `visibleIds` is what makes the cloth particles
 * mesh-only: only the pole group is in it, so cloth particles carry position data (for the
 * mesh's vertices and the structural lines) without ever drawing their own dot. Picking still
 * works on mesh-only particles — the viewer's raycaster hits mesh faces too, resolving to
 * whichever vertex is nearest the hit point (see viewer.html's `pickParticle`).
 *
 * **No `breakProximity`-colored structural springs here**, unlike an earlier version of this
 * demo — tried and reverted. `MeshSprings`' structural damping (1.0, well under critical for
 * its stiffness/mass) means a sudden reposition — exactly what dragging does every step —
 * causes real overshoot/ringing, not just a smooth approach to the new target; that overshoot
 * transiently spiked displacement well past thresholds that looked safe from watching wind
 * alone (0.02 broke under wind by itself; 0.1 still broke under a deliberately modest,
 * gradual drag). Raising the threshold further would have kept "surviving drag" and "showing
 * wind-driven color" in tension indefinitely — wind's own peak displacement is tiny, so any
 * threshold loose enough to survive dragging's overshoot makes wind's contribution negligible
 * anyway. `MeshSprings.activeConnectionsWithBreakProximity` and `ColorRamp` are still fully
 * implemented and tested independently (`MeshSpringsTest`, `ColorRampTest`) — this demo just
 * doesn't lean on a finite threshold anymore, since permanently tearing the flag from ordinary
 * dragging was a worse experience than never showing tension color.
 *
 * And §10.3's time controls: pause/resume, a speed multiplier, and step-once, applied via
 * [ViewerInput.timeControl] on this loop's own `stepsPerFrame` — `FLAG_DT` itself is never
 * touched (§9.1's pacing policy). This is the loop those controls were designed against, not
 * retrofitted onto it after the fact — [ViewerInput] itself came later, consolidating what this
 * demo (and `DragDebugDemo`'s/`ParticleCollisionDebugDemo`'s own hand-rolled versions) already
 * did into one class every demo shares.
 */
fun main() {
    val scenario = buildFlag(rows = 8, cols = 14)
    val structural = scenario.meshSprings[0]
    val wind = scenario.forces.filterIsInstance<Wind>().single()
    val flagTip = scenario.grid.last().last()
    val scene = SceneQueryImpl(scenario.store, scenario.groups)
    val camera = CameraFunction { t, s ->
        CameraPose(
            position = s.centroid("cloth") + Vector3(sin(t * 0.3) * 5.0, 2.0, cos(t * 0.3) * 5.0),
            lookAt = s.position(flagTip),
        )
    }

    val poleIds = scenario.groups.membersOf("pole")
    // A cosmetic render size, not a physics one - pole particles carry no ParticleStore radius
    // of their own (they're FixedPosition anchors, never collidable), so without this explicit
    // override they'd fall back to the viewer's plain default dot size (§10.4's radius/render
    // unification only applies when a particle actually has a physics radius to show).
    val poleSphereRadii = poleIds.associateWith { 0.03 }
    val clothMesh = SurfaceRenderer(scenario.surface, wireframe = false)
    // A modest region around the flag's own footprint (x: 0..~2.1, y: 0..~-1.2) at a resolution
    // sparse enough not to clutter a flag this size with dozens of overlapping arrows.
    val windArrows = ArrowRenderer(wind, regionMin = Vector3(-0.5, -2.0, -1.0), regionMax = Vector3(2.5, 0.5, 1.0), resolution = 1.0)
    // Wind's raw m/s magnitude (~6-8) would draw arrows several times the flag's own size;
    // scaled down purely for this demo's display, not a claim about the physical value itself.
    val arrowVisualScale = 0.15
    // §10.3's outliner data: this scenario's own named force (wind), named constraint (the pole
    // anchor), named surface (the cloth mesh), and every group — all real, not hypothetical,
    // since buildFlag names these itself now (§10.3's name→object registry prerequisite).
    val registry = SceneRegistry.build(
        forces = scenario.forces,
        constraints = scenario.constraints,
        surfaces = listOf(scenario.surface),
        groups = scenario.groups,
    )

    val viewerInput = ViewerInput()
    val renderer = DebugRenderer(onTextMessage = viewerInput::onTextMessage)
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / FLAG_DT).toInt())

    var t = 0.0
    var step = 0L
    var activeDrag: DragConstraint? = null
    val frameNanos = 1_000_000_000L / framesPerSecond
    val allIds = scenario.grid.flatten()
    while (true) {
        val frameStart = System.nanoTime()
        // §10.4's live-editing write path - this demo never drained sceneControlQueue before
        // (confirmed via git history when the connection-naming work landed), which is exactly
        // why the panel's number inputs used to silently revert: the server kept re-broadcasting
        // the unedited value every frame because nothing ever applied the edit. Dispatches by
        // (kind, name) against whichever force/constraint actually implements EditableFields,
        // rather than one hardcoded name check per force the way the single-force debug demos
        // do it - this scenario has several named, editable forces (structural/shear/bend/wind).
        for (message in viewerInput.sceneControlQueue.drainAll()) {
            when (message) {
                is SceneControlMessage.SetScalarField -> {
                    val target: EditableFields? = when (message.kind) {
                        "force" -> scenario.forces.find { it.name == message.name } as? EditableFields
                        "constraint" -> scenario.constraints.find { it.name == message.name } as? EditableFields
                        else -> null
                    }
                    target?.setField(message.field, FieldValue.Scalar(message.value))
                }
                is SceneControlMessage.SetVectorField -> {
                    val target: EditableFields? = when (message.kind) {
                        "force" -> scenario.forces.find { it.name == message.name } as? EditableFields
                        "constraint" -> scenario.constraints.find { it.name == message.name } as? EditableFields
                        else -> null
                    }
                    target?.setField(message.field, FieldValue.Vector(message.value))
                }
                is SceneControlMessage.SetGroupEnabled -> scenario.groups.setEnabled(message.name, message.enabled)
                is SceneControlMessage.SetParticleScalarField -> {
                    if (scenario.store.contains(message.particleId)) {
                        when (message.field) {
                            "mass" -> scenario.store.setMass(message.particleId, message.expr, t)
                            "radius" -> scenario.store.setRadius(message.particleId, message.expr, t)
                        }
                    }
                }
                is SceneControlMessage.RemoveCollider -> {} // this demo has no colliders
                is SceneControlMessage.SetColliderActive -> {} // this demo has no colliders
                is SceneControlMessage.DeleteParticle -> {} // not a feature of this demo (no destruction system wired)
                SceneControlMessage.Restart -> {} // not built for this demo - see DragDebugDemo for the pattern if needed
                is SceneControlMessage.LoadScene -> {} // §9.6 scene switching - this standalone demo isn't in the library, see SceneLibraryDebugDemo
                is SceneControlMessage.SetEmitterRate -> {} // this demo has no emitters
                is SceneControlMessage.SetEmitterMaxAlive -> {} // this demo has no emitters
                is SceneControlMessage.SetEmitterCapPolicy -> {} // this demo has no emitters
                // Not wired here (this demo hand-rolls its own dispatch above rather than
                // calling applyEditableFieldMessage) - see FlagScene for the wired equivalent.
                is SceneControlMessage.SetWindVelocity -> {}
            }
        }
        // §9.1's pacing policy, honored literally: dt (FLAG_DT) never changes here - only how
        // many whole dt-steps run this tick. A paused/step-once frame can run zero steps, in
        // which case t/step don't advance and the broadcast below just resends the frozen state
        // (camera and wind-arrow sampling, both keyed on t, freeze along with it for free).
        repeat(viewerInput.timeControl.stepsThisFrame(stepsPerFrame)) {
            for (message in viewerInput.dragQueue.drainAll()) {
                when (message) {
                    is DragMessage.Start -> {
                        // The pole edge is already FixedPosition-constrained — dragging it
                        // would just fight that constraint, same reasoning as DragDebugDemo
                        // excluding its own pinned anchor.
                        if ("pole" !in scenario.groups.groupsOf(message.particleId)) {
                            activeDrag = DragConstraint(message.particleId, message.target)
                        }
                    }
                    is DragMessage.Move -> activeDrag?.updateTarget(message.target, FLAG_DT)
                    is DragMessage.End -> {
                        activeDrag?.let { scenario.store.setVelocity(it.particleId, it.releaseVelocity()) }
                        activeDrag = null
                    }
                }
            }
            val constraints: List<Constraint> = activeDrag?.let { scenario.constraints + it } ?: scenario.constraints
            integrator.step(scenario.store, scenario.groups, scenario.forces, constraints, t, FLAG_DT)
            t += FLAG_DT
            step++
        }
        val arrowSamples = ArrowSampling.sample(windArrows, t).map { it.copy(vector = it.vector * arrowVisualScale) }
        val structuralConnections = structural.activeConnections()
        renderer.broadcast(
            t, step, scenario.store, allIds,
            connections = structuralConnections,
            // Ties each drawn line back to "structural" (buildFlag names all three MeshSprings,
            // but shear/bend never contribute connections above - see the connections= line -
            // so they'd tag nothing anyway) for the cloth group's own spring/damper tab (§10.4).
            connectionNames = structural.name?.let { name -> structuralConnections.associateWith { name } } ?: emptyMap(),
            camera = camera.evaluate(t, scene),
            sphereRadii = poleSphereRadii,
            meshes = listOf(clothMesh),
            // wind.name is non-null (buildFlag names it "wind") - §10.3's per-force arrow
            // visibility toggle keys off this exact name in the outliner's forces section.
            arrowGroups = listOf(NamedArrowSamples(wind.name ?: "", arrowSamples)),
            visibleIds = poleIds,
            registry = registry,
        )
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
