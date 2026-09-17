package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Force
import particlesim.physics.SphFluid
import particlesim.physics.UniformGravity

/**
 * §5.5's worked example: a block of fluid particles dropped into an open-topped box, settling
 * under gravity and its own SPH pressure/viscosity into a puddle - the CPU/Kotlin-engine
 * counterpart to threejs's `webgpu_compute_particles_fluid` example. That example runs its whole
 * SPH solver as a WebGPU compute shader; this engine has no per-viewer compute stage (§9's
 * decoupled simulation/visualization architecture keeps the viewer a thin generic renderer), so
 * the same physics runs here as an ordinary [Force] on the CPU instead. Expect a hand-picked,
 * low-thousands particle lattice rather than a GPU-scale one, and a settle that reads as "a
 * fluid" rather than a pixel-identical match to that reference.
 *
 * [SphFluid.restDensity] is measured directly from the lattice this function builds (via
 * [SphFluid.measureDensity]), not a literal borrowed from real water: real water's 1000 kg/m^3
 * describes a spacing/mass this simulation doesn't use, so plugging it straight in would start
 * the fluid far from its own kernel's actual equilibrium and either collapse or blow up on the
 * first step - the same class of "guessed constant vs. measured one" bug `Fire.kt`'s drag
 * coefficient was.
 */
data class FluidScenario(
    val store: ParticleStore,
    val groups: Groups,
    val forces: List<Force>,
    val collisions: CollisionSystem,
    val walls: List<PlaneCollider>,
)

const val FLUID_DT = 5e-4

fun buildFluid(): FluidScenario {
    val store = ParticleStore()
    val groups = Groups()

    val spacing = 0.05
    val particleMass = 1.0
    val smoothingRadius = spacing * 2.0
    val nx = 10
    val ny = 15
    val nz = 10
    val origin = Vector3(-0.5 * (nx - 1) * spacing, 0.15, -0.5 * (nz - 1) * spacing)

    val positions = ArrayList<Vector3>(nx * ny * nz)
    var centerPosition = Vector3.ZERO
    for (ix in 0 until nx) for (iy in 0 until ny) for (iz in 0 until nz) {
        val pos = origin + Vector3(ix * spacing, iy * spacing, iz * spacing)
        positions += pos
        // The geometric center of the block, not `positions[positions.size / 2]` - that flat-
        // array midpoint lands wherever the ix/iy/iz nesting order happens to put it (here, the
        // *bottom face*, iy = 0), which has fewer neighbors than a true interior particle and
        // so measures a density well below the block's real bulk value - calibrating
        // `restDensity` off it made nearly the whole block read as over-compressed from t=0,
        // exploding outward under spurious pressure (confirmed by a diagnostic run: average
        // speed was already ~0.9 after a single step, far more than one step of gravity alone
        // could produce).
        if (ix == nx / 2 && iy == ny / 2 && iz == nz / 2) centerPosition = pos
    }
    for (pos in positions) {
        val id = store.create(position = pos, mass = ScalarExpr.of(particleMass), radius = ScalarExpr.of(spacing / 2.0))
        groups.add("fluid", id)
    }

    // Calibrated from this exact lattice - see this file's own doc comment.
    val restDensity = SphFluid.measureDensity(positions, centerPosition, particleMass, smoothingRadius)

    val sph = SphFluid(
        group = "fluid",
        restDensity = restDensity,
        gasConstant = 0.06,
        viscosity = 0.01,
        smoothingRadius = smoothingRadius,
        name = "sph",
    )
    val gravity = UniformGravity("fluid", Vector3(0.0, -9.8, 0.0), name = "gravity")

    // An open-topped box: floor plus four walls, no ceiling. Each PlaneCollider's outward
    // normal points into the box's interior - the same "solid on one side, open on the other"
    // convention `buildBallBounce`'s own floor uses, just walled on all four sides here instead
    // of just below.
    val halfX = 0.9
    val halfZ = 0.9
    val floor = PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, 0.0)), normal = Vector3(0.0, 1.0, 0.0), name = "floor")
    val wallXNeg = PlaneCollider(VectorExpr.of(Vector3(-halfX, 0.0, 0.0)), normal = Vector3(1.0, 0.0, 0.0), name = "wallXNeg")
    val wallXPos = PlaneCollider(VectorExpr.of(Vector3(halfX, 0.0, 0.0)), normal = Vector3(-1.0, 0.0, 0.0), name = "wallXPos")
    val wallZNeg = PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, -halfZ)), normal = Vector3(0.0, 0.0, 1.0), name = "wallZNeg")
    val wallZPos = PlaneCollider(VectorExpr.of(Vector3(0.0, 0.0, halfZ)), normal = Vector3(0.0, 0.0, -1.0), name = "wallZPos")
    val walls = listOf(floor, wallXNeg, wallXPos, wallZNeg, wallZPos)

    val rules = walls.map { wall ->
        ParticleColliderRule(
            group = "fluid",
            collider = wall,
            restitution = 0.1,
            correctionFactor = 0.5,
            staticFriction = 0.2,
            kineticFriction = 0.2,
        )
    }

    return FluidScenario(
        store = store,
        groups = groups,
        forces = listOf(sph, gravity),
        collisions = CollisionSystem(rules),
        walls = walls,
    )
}
