# ParticleSim — Requirements

Status: draft, under active iteration
Target platform: JVM (Java/Kotlin)

## 1. Overview

ParticleSim is a general-purpose particle physics simulator. It simulates
sets of point particles and triangulated surfaces built from particles,
subject to configurable forces and constraints, defined declaratively in
YAML. Simulations can be watched in real time or run headless at large scale
with results recorded for later playback.

## 2. Core Concepts

- **Particle**: the atomic simulated entity. Has 3D position, velocity,
  acceleration, and mass.
- **Force**: something that contributes to a particle's acceleration each
  timestep (gravity, spring, damper, wind, drag, custom).
- **Constraint**: an override that fixes some part of a particle's state
  (position, velocity, or net force) regardless of what forces compute.
- **Surface**: a mesh of triangles whose vertices are particles, used to
  simulate cloth/flags/membranes and to give forces (e.g. wind) a surface to
  act on.
- **Simulation**: a YAML-defined collection of particles, surfaces, forces,
  constraints, and time/run parameters.
- **Group**: a named, reusable set of particles (e.g. by tag or ID range)
  that forces/constraints/renderers can target instead of listing individual
  particles.

## 3. Particle Model

Each particle has:
- `position`: 3D vector (m)
- `velocity`: 3D vector (m/s)
- `acceleration`: 3D vector (m/s²), derived each step from net force / mass
- `mass`: scalar (kg), **may be an expression**, evaluated per timestep

All quantities use SI units throughout, documented explicitly so mixed-unit
bugs aren't possible.

### Mass as an expression

Mass may be a constant or an expression string evaluated at each timestep,
e.g.:

```yaml
mass: 2.5                        # constant
mass: "2.0 + 0.1 * sin(t)"       # function of time
mass: "1.0 + 0.01 * |velocity|"  # function of other particle state [stretch]
```

**Recommendation**: start with expressions that are functions of time `t`
and the particle's own current state (position, velocity, id). Expressions
referencing *other* particles' state or global aggregates (e.g. "average
neighbor mass") should be `[stretch]` — they complicate evaluation order and
performance.

### Other properties

Beyond the core state above, a particle can optionally carry:
- `radius` (m, expression-capable): one canonical size, used by both
  rendering (§10, sphere style) and collision (§12.1) by default — either
  can override it independently if a particle needs to render larger or
  smaller than it collides, but most particles only need to set this once.
- `spawnTime` (s): set automatically when the particle comes into
  existence — `0` for particles declared upfront, or the simulation time
  at which an emitter (§14.1) created it. Used to evaluate `lifetime`.
- `lifetime` (s, optional, expression-capable): if set, the particle is
  automatically destroyed once `t - spawnTime` exceeds it (§14.2).
- `[stretch]` a generic custom-attribute bag (arbitrary key/value data) for
  forces or diagnostics that need per-particle data the core model doesn't
  cover — an extensibility valve rather than a growing list of built-in
  fields.

`id` and `tags`, used for group targeting, are covered in §4.2.

## 4. Simulation Definition Language

A simulation is described using one of **two authoring front-ends** that
both build the same in-memory simulation model (particles, surfaces,
forces, constraints, colliders, groups, time settings) — there is no
functional gap between them by design; the engine, recorded output, and
viewers never know or care which one produced a given run.

- **YAML** — plain, declarative data. The default choice: portable across
  tools, diffable/reviewable, safe to load from an untrusted source (it can
  only ever describe data, never execute code), and the natural interchange
  format if a non-JVM tool ever needs to read or generate scenarios.
- **Kotlin DSL** — a type-safe builder API, written and compiled as real
  Kotlin. The choice when a scenario needs actual logic — functions,
  loops, reusable generators — rather than just data.

### 4.1 Expression language (shared)

To make the YAML front-end "powerful but intuitive" without turning it into
a general scripting language, any field documented as *expression-capable*
accepts either a literal value or a string containing a small, sandboxed
math expression grammar:
- Arithmetic: `+ - * / ^`, parentheses
- Functions: `sin cos tan sqrt abs min max clamp noise(...) ...`
- Built-in variables: `t` (sim time, s), `dt` (timestep, s), and (where
  applicable) the entity's own `position`, `velocity`, `id`
- Vector literals: `[x, y, z]`, with component-wise math

`noise(...)` is **seeded, deterministic gradient noise** (value or simplex,
implementation's choice), indexed by position and/or time depending on its
arguments — not a call to a system RNG. It has to behave this way or it
silently breaks the determinism requirement in §11 the moment anyone uses
it for a gusty wind field (§5.2) or similar.

Every expression is **type-checked at parse time** — scalar vs. vector
mismatches (e.g. adding a vector to a scalar, or a field expecting a
scalar getting a vector expression) are a load-time schema error (§4.2),
not a runtime surprise. Mixing scalar and vector operands is the most
likely authoring mistake in this grammar, and it should fail the moment
the simulation is loaded, not four thousand steps into a run.

This one expression engine is shared across mass, force magnitude/
direction, constraint values, and anything else marked expression-capable,
so the mental model is consistent everywhere in the DSL — and it's exactly
what the Kotlin DSL also accepts wherever a plain literal isn't enough but
a full lambda would be overkill (§4.3).

**Scene queries (camera only)**: camera expressions (§10.1) additionally
get a *scene query* API — `position(id)`, `centroid(group)`,
`normal(surface, triangleIndex)`, and similar — to reference other
particles/groups/surfaces by name, not just an entity's own state. This is
a deliberately different risk profile than letting a *physical* quantity
like mass (§3) depend on other particles: camera expressions are evaluated
once per frame purely for rendering and never feed back into the physics
step, so the performance/determinism concerns that keep cross-particle
mass expressions `[stretch]` don't apply here.

**Implementation**: a small hand-rolled recursive-descent parser, not an
existing general-purpose JVM expression library. The grammar is
deliberately tiny (arithmetic, ~10 functions, vectors, scene queries), and
since it must stay safely sandboxed for untrusted YAML, owning the parser
guarantees the sandbox boundary is exactly what's intended — no risk of a
general-purpose library exposing more than meant to (e.g. via reflection).

### 4.2 YAML front-end

Particles can be defined individually or generated in bulk (grids, random
distributions within a volume, explicit lists) — bulk generation is
essential for large-N scenarios. Every particle can carry an optional `id`
and one or more `tags`; `groups` are named selectors over tags/ids/ranges
that other sections (forces, constraints, colliders, renderers) reference.

**Groups are a first-class runtime concept**, not just a YAML-authoring
convenience resolved once at load time: emitters (§14.1) spawn new
particles into a group *during* a run, so group membership has to be
something the live engine can update, not a static list baked in from
parsing.

**Validation**: the YAML schema is formally defined (e.g. JSON Schema or a
Kotlin data-class-driven schema) so malformed simulations fail fast with a
clear error pointing at the offending field, rather than failing deep
inside the physics loop. This covers structural/syntax errors; two
*semantic* cases need to be checked too, since they're the most common
authoring mistakes that a schema alone won't catch:
- A group selector (tag/id/range) that matches **zero particles** is a
  warning, not a silent no-op — a typo'd tag name should be visible
  immediately, not discovered by "why isn't this force doing anything."
- A renderer (§10.2) or force reference targeting an **unknown name** is a
  load-time error, not something that's only noticed when nothing renders.

### 4.3 Kotlin DSL front-end

The Kotlin DSL is a type-safe builder using trailing-lambda syntax, the
same style as the Gradle Kotlin DSL or Ktor's routing DSL — it stays
readable for simple scenes while giving full access to Kotlin (functions,
loops, `for`/`when`, reusable generators, external libraries) for complex
ones. Anywhere the YAML expression grammar (§4.1) would be used, the Kotlin
DSL instead accepts a **native Kotlin lambda** — this is the direct answer
to "can we just define functions": yes, as real closures, not expression
strings.

```kotlin
simulation {
    duration = 6.0
    dt = 0.005

    val pole = particles.grid(rows = 1, cols = 20, spacing = 0.1) { row, col ->
        position = Vector3(col * 0.1, 2.0, 0.0)
    }.group("pole-edge")

    val flag = particles.grid(rows = 15, cols = 20, spacing = 0.1) { row, col ->
        position = Vector3(col * 0.1, 2.0 - row * 0.1, 0.0)
        mass = { t -> 0.02 + 0.002 * sin(t) }   // native function, not a string
    }.group("flag")

    surface("flag-cloth", from = flag)
    constraints { fixedPosition(group = pole) }

    forces {
        gravity(group = flag, accel = Vector3(0.0, -9.8, 0.0))
        wind(group = "flag-cloth") { t ->
            val gust = 1.0 + 0.5 * sin(t * 2.0)
            Vector3(3.0 * gust, 0.0, 1.5 * sin(t * 0.7))
        }
    }
}
```

Compare to the flag example in §7.3 — same scenario, same underlying model,
just authored with real code instead of data when the loop-driven grid
generation and closures are worth it.

**Trust boundary**: unlike YAML, a Kotlin DSL file *is* JVM code — running
one means compiling and executing arbitrary logic, the same trust model as
running someone's `build.gradle.kts`. It should only be used for scenarios
the user authors or explicitly trusts, never for loading a simulation
definition from an untrusted source (that's exactly the case YAML stays
safe for).

`[stretch]` **Export to YAML**: a Kotlin DSL script can programmatically
generate a scene (e.g. procedurally placing thousands of particles) and
then serialize the resulting model out to a YAML file — bridging the two
front-ends when a Kotlin-authored scenario later needs to be shared,
diffed, or handed to a non-JVM tool.

### 4.4 Choosing between them

Default to YAML for simple, shareable, hand-editable scenes and for
anything that should remain safely loadable as plain data. Reach for the
Kotlin DSL when a scenario is naturally *generated* rather than *described*
— procedural particle layouts, parametric sweeps across many similar runs,
or force/mass logic that's awkward to express in the expression grammar.
Both remain first-class; neither is meant to fall behind the other in
capability.

### 4.5 Shape library

A **shape** is a reusable, parameterized scene fragment — particles,
forces, constraints, surfaces, and colliders bundled as one unit and
referenced by name when building a scene, rather than authored inline
every time. A flag, a flagpole, a tire, a ball are all shapes: each is a
self-contained little scenario (in the flag's case, literally §7.3's
worked example) that a larger scene can drop in one or more copies of,
at a chosen position, without re-describing its internals.

For the Kotlin DSL, this is barely a new mechanism — it's what
`buildFlag`/`buildBallBounce`/`buildSparks`-style functions already are
(a function that returns a self-contained scenario fragment), just
formalized enough to *compose*: a scene that wants three flags and two
balls needs to instantiate a shape more than once without their
particles/groups colliding. That means every shape instantiation takes
a **placement** (a position, and where relevant an orientation, applied
to every particle/collider the shape creates) and an **instance name**
used to namespace its internal group names, dotted (`"$instanceName.$local"`)
— a flag's `cloth`/`pole` groups become `flag1.cloth`/`flag1.pole` for one
instance and `flag2.cloth`/`flag2.pole` for another. No instance name
(the default) leaves names unprefixed, exactly reproducing a shape's
original single-instance behavior — the same particle-by-stable-id
referencing already used everywhere else means nothing outside the shape
needs to know its ids, only its namespaced group names. Implemented as
`ShapePlacement` (`particlesim.examples`), proven against two different
shapes (`buildFlag`, `buildBallBounce`) sharing one scene
(`ShapeCompositionTest`, `MultiShapeDebugDemo`).

For YAML, this needs an actual **shape library/registry**: a named,
versioned catalog of shape definitions (each itself expressible in the
existing YAML grammar, parameterized the way a bulk-generation block
already is, §4.2) that a scene's `shapes:` section can reference by name
plus parameters plus placement. Kotlin-DSL-first, same status as every
other YAML-side feature since Phase 7 (§4.4) — the DSL's function-based
version is what gets built first; a formal YAML shape registry is a
second pass once the DSL side has proven out what a shape actually needs
to parameterize (a flag's rows/cols/spacing is obvious; a tire's
parameters aren't decided yet).

## 5. Forces

Forces are optional and composable — a simulation declares whichever set it
needs. Any individual force declaration can optionally carry a `name`, so
it can be referenced later — most importantly by a renderer (§10.2).
Unnamed forces behave identically; they just can't be individually
targeted for visualization.

### 5.1 Pairwise forces (between two specific particles or within a group)
- **Spring**: Hooke's law about a configurable `restLength` — the natural
  length at which the force on both particles is zero. Stiffness is
  **direction-dependent**: `stiffness` is the default/symmetric spring
  constant (expression-capable, as always), with optional
  `extensionStiffness` and `compressionStiffness` independently overriding
  it for the stretched (`length > restLength`) and compressed
  (`length < restLength`) cases respectively — whichever of the two isn't
  given falls back to `stiffness`. This is what lets a rope or cable go
  slack under compression (`compressionStiffness ≈ 0`) while staying stiff
  under tension, or lets a surface's structural springs (§7.1) buckle more
  softly than they stretch. Can optionally be breakable — see §5.4.
- **Damper**: force proportional to relative velocity along the
  connecting axis; typically paired with a spring to prevent oscillation
  blow-up. Damping coefficient is **direction-dependent**, similar in
  spirit to the spring's stiffness split but keyed on a different signal:
  `damping` is the default/symmetric coefficient (expression-capable), with
  optional `extensionDamping` and `compressionDamping` independently
  overriding it depending on the *sign of the relative velocity* along the
  axis at that step — extending (separating, length increasing) vs.
  compressing (approaching, length decreasing) — rather than on position
  relative to `restLength` the way spring stiffness (§5.1) is. Whichever
  isn't given falls back to `damping`. This is the standard behavior of a
  real shock absorber, which is typically tuned softer on the compression
  (bump) stroke and stiffer on the extension (rebound) stroke, or vice
  versa. Applies whether or not the damper is paired with a spring, since
  it depends only on relative velocity, not rest length.

### 5.2 Field forces (applied to a group of particles)
- **Uniform gravity**: constant acceleration vector (e.g. `[0, -9.8, 0]`),
  applied to a named group — not necessarily all particles, so some can be
  exempt (e.g. anchored points, or objects meant to float).
- **Wind**: a force field with direction and strength, both **expression-
  capable** (function of time and/or position) so wind can gust, oscillate,
  or vary spatially. Applies to particles directly and/or to surface
  triangles (see §7.2). *(New requirement, resolved)* `velocity` is
  live-editable from the viewer (§10.4) as a single expression string —
  the whole `VectorExpr` (e.g. `[15*sin(t), 0, 15*cos(t)]`), not
  decomposed into named direction/gust sub-parameters. Decomposing gust
  into independent amplitude/frequency numbers was considered and
  rejected: the shared expression language (§4.1) already lets one string
  encode any gust/direction behavior, so a second, narrower gust-specific
  UI would just duplicate that grammar for no added capability — the same
  "one opaque expression, edited wholesale" pattern already used for
  particle mass/radius (§9.4/§10.4) and emitter rate. `density` stays a
  separate, independently editable numeric field exactly as it worked
  before this change.
- **Drag / air resistance**: force opposing velocity, proportional to speed
  (linear) or speed² (quadratic), useful for damping runaway simulations and
  for realistic cloth/flag behavior.
- **N-body gravity**: pairwise Newtonian gravity between particles in a
  group (`F = G·m1·m2/r²`). Distinct from uniform gravity. Expensive at
  scale — see §9.3 on spatial partitioning / performance.

### 5.3 Custom forces `[stretch]`
Expression-defined force fields for cases not covered above, plus the
plugin escape hatch from §4.1 for arbitrary JVM logic.

### 5.4 Breakable forces

A spring or damper connection can be declared **breakable**. `breakThreshold`
(expression-capable — e.g. a maximum stretch/compression distance or force
magnitude) is the default/symmetric limit, checked every step; optional
`extensionBreakThreshold` and `compressionBreakThreshold` independently
override it for the stretched and compressed cases, mirroring the
direction-dependent stiffness in §5.1 — whichever isn't given falls back
to `breakThreshold`. Once the relevant threshold is exceeded, the
connection is permanently removed from the simulation for the rest of the
run. It does not reform.

- This pairs naturally with §5.1's asymmetric stiffness: a rope can have a
  finite `extensionBreakThreshold` (snaps under too much pull) while
  `compressionBreakThreshold` is left effectively unbounded, since a slack
  rope (`compressionStiffness ≈ 0`) has nothing to meaningfully break under
  compression in the first place.
- Breaking emits a discrete **event** into the per-frame state stream
  (§9.1), alongside the continuous particle and camera data, so a viewer
  can react (visual/audio cue) and a recorded run preserves exactly when
  and where each break happened. A spring/damper renderer (§10.2) can also
  show the break *coming* — a `breakProximity`-colored line gives a visual
  warning before the event fires, not just a reaction after.
- Since structural springs inside a surface are generated automatically
  from mesh topology (§7.1), marking them breakable is what makes a
  surface **tearable** for free — e.g. the flag from §7.3 tearing loose
  from its pole-side edge, or ripping mid-sheet, under a strong enough wind
  gust — without needing separate tearing logic.
- Removing a force can't inject energy, but its *neighbors* can still see a
  sharp force discontinuity the instant it's gone — a spring that was
  holding a taut connection back suddenly isn't, and the particles on
  either end can whip hard enough to trip the blow-up detector in §13.2,
  especially in a cascading tear. That's expected, not a bug, but it means
  breaking isn't quite the stability non-event it might look like.
- **Ordering**: if multiple connections exceed their threshold in the same
  step, all of them break simultaneously at the end of that step, using
  the forces computed at the step's start — not evaluated one at a time
  with forces recomputed in between. One-at-a-time evaluation would make
  the outcome depend on an arbitrary iteration order; end-of-step batch
  removal keeps it deterministic (§11) regardless of how breaks are
  discovered internally.
- Break checks happen once per **physics step** — i.e. once per sub-step
  when sub-stepping (§8, §13.1) is active, not once per rendered/recorded
  frame. This means a sub-stepped run can observe a break at a different
  moment than a non-sub-stepped run of the same scene would, since the
  physics is genuinely being evaluated at finer granularity — sub-stepping
  isn't a pure stability knob with zero behavioral consequence.
- `[stretch]` Extending breakability to the custom forces in §5.3, or to
  constraints (§6) themselves (e.g. an anchor that gives way under enough
  load), rather than just spring/damper connectors.

## 6. Constraints

A constraint pins some aspect of a particle's state, overriding what the
force/integration step would otherwise produce:
- **Fixed position**: particle does not move (e.g. a flag's pole-side edge).
  Position may itself be an expression of time, allowing controlled/scripted
  motion (e.g. shaking the pole) `[stretch]`.
- **Fixed velocity**: particle moves at a constant (or expression-defined)
  velocity regardless of forces acting on it.
- **Fixed force**: a specific external force is always applied to the
  particle in addition to (or instead of) computed forces — useful for
  actuators or scripted disturbances.

Constraints are declared per-particle or per-group, same selector mechanism
as forces. Particles under a **fixed position or fixed velocity** constraint
are treated as infinite mass in collision response (§12.5) — they can't be
pushed by a collision, only push back. A **fixed force** constraint is
excluded from this rule: it doesn't pin any state, it's just an externally
supplied force term, so a particle with a small constant thruster force
attached must still behave as normal (movable, non-infinite) mass in
collisions.

## 7. Surfaces

A **surface** is a set of triangles whose three vertices are each a
particle reference. Surfaces let a mesh of particles be treated as a
continuous sheet for rendering and for forces that need a surface (area,
normal) to act on — most importantly wind pressure.

### 7.1 Structural forces
Surfaces are typically held together by springs+dampers along triangle
edges (structural + shear + bend springs, the standard mass-spring cloth
model) — declared using the same spring/damper primitives from §5.1,
generated automatically from the mesh topology rather than listed edge by
edge.

### 7.2 Surface forces (wind pressure)
Wind (and other field forces) can act on a triangle as a whole: force is
computed from wind velocity relative to the triangle, its normal, and its
area, then distributed to the three vertex particles. This is what makes a
flag "catch" the wind rather than each vertex reacting independently.

Two requirements this depends on: the force must be **two-sided** — a
fluttering flag flips which way its triangles face many times a second, so
the pressure calculation has to use the sign of (wind · normal) rather than
assuming the normal always points into the wind, or the flag would stop
catching wind mid-flap. And triangles generated from a surface's mesh
topology (§7.1) must have **consistent winding order**, so every triangle's
normal points the same logical way (e.g. outward) — inconsistent winding
would make neighboring triangles compute wind force in opposite directions
for what's supposed to be one continuous sheet.

### 7.3 Worked example: a flag

> A rectangular grid of particles forms a triangulated sheet. Particles
> along the left edge are constrained to fixed positions along a pole.
> Structural/shear/bend springs hold the sheet's shape. A wind force with
> time-varying direction and strength acts on every triangle. Run in
> real-time viz mode to watch it ripple.

This scenario should work as a first-class example in the eventual example
library — it exercises surfaces, constraints, structural springs, and
variable field forces together.

## 8. Time Control & Integration

- Simulation `duration` (s) and `dt` (fixed timestep, s) are required
  top-level YAML settings.
- **Integration method** is configurable: semi-implicit (symplectic) Euler
  as the default (stable and cheap for spring systems), with RK4 available
  for higher accuracy `[stretch]`, and explicit Euler available for
  teaching/comparison purposes `[stretch]`. See §13.1 for the stability
  limits this default is subject to and when to reach for the alternatives.
- **Sub-stepping**: stiff spring systems (e.g. taut flag cloth) may need a
  smaller physics dt than the recording/render dt — sub-stepping should be
  supported so visual/recording rate and stability rate can differ
  `[stretch]`. Not a pure stability knob with no other effect, though: it
  changes how often physics is actually evaluated, which changes exactly
  when a break threshold (§5.4) gets crossed.

## 9. Execution Modes

### 9.1 Real-time / interactive
Simulation steps and a viewer renders it live. Time control (pause,
speed multiplier, step-once) is expected. The sim engine and viewer are
**decoupled**: the engine exposes state through a stable interface — a
WebSocket using a compact binary framing (not JSON, since particle state
is high-frequency and the bandwidth/parse cost would otherwise bite at
large N and in drag-interaction latency, §9.4) for the web (three.js)
viewer (§10), the sole first-class viewer — so the engine never assumes
anything about who's attached. `[stretch]` A native viewer, if ever built,
should reuse this same protocol (over localhost, or in-process) rather
than getting a second interface — decoupling the engine from any specific
viewer is still the point, even with only one viewer being built now. Each
frame of state carries continuous data
(particle/surface state, the computed camera pose from §10.1) plus
discrete **events** (e.g. force breaks, §5.4, and particle spawn/destroy,
§14.2), so viewers and recordings have one channel to consume rather than
several. Named forces with an active renderer (§10.2) also contribute
their current value (e.g. a spring's live stretch/force magnitude, a
wind sample) to this same stream — forces have no other presence in the
state stream otherwise, so this only costs anything for a force someone
actually chose to visualize.

For interactive mode specifically, this interface is **bidirectional**: the
viewer also sends a small set of input commands back to the engine —
currently just picked-particle drag targets (§9.4) — rather than being a
pure one-way broadcast. Manual camera control (§10.1) does *not* go through
this channel; it's resolved entirely on the viewer side, since it never
affects the simulation itself.

**Pacing policy**: real-time mode targets sim-time tracking wall-clock time
1:1. If the physics can't keep up, the engine never skips or coarsens
physics steps to catch up — that would silently break determinism (§11).
Instead it falls behind and lets rendered/recorded frames drop (the viewer
shows the most recent state it has, rather than every step), which costs
smoothness, not correctness. A `duration` (§8) is required for a run, so an
open-ended interactive session with no fixed end is `[stretch]` — for now,
set a long `duration` and pause/step manually via the time controls above.

### 9.2 Batch / record-and-playback
For large particle counts where real-time computation isn't feasible, the
engine runs headless, computing and storing per-frame (or per-N-frames)
particle state to disk. A separate playback viewer then scrubs/plays the
recorded run — same viewer, decoupled from live vs. recorded source.
- **Recording format: Arrow IPC *File* format, sharded** into fixed-size
  chunks (e.g. one file per N frames) — not Parquet, and not a single
  unsharded stream. Each frame is one record batch, written incrementally
  as it's produced (unlike Parquet, which is organized around row groups
  and doesn't append naturally frame-by-frame during a long headless run).
  The File format's footer gives real random-access seeking — necessary
  for playback scrubbing (§9.4) — without a custom index; pure Arrow IPC
  *streaming* format has no footer and can't seek, which rules it out
  despite being simpler. **Sharding** is what makes this robust to a long
  run crashing mid-write: an Arrow IPC File is only readable once its
  footer is written, so an unsharded file would lose everything if the
  process dies before closing; sharding bounds the loss to at most one
  in-progress shard, with everything before it staying valid. A shard
  boundary is also where a full state checkpoint is written (§9.5).
  Scrubbing to frame N is shard arithmetic (fixed frames/shard) plus that
  shard's own footer lookup.
- **Every frame carries an explicit particle-id column**, not just a
  per-frame alive-set flag — positional identity across frames can't be
  relied on once particle count is dynamic (§14), so a given row has to
  say *which* particle it is, not just that some particle occupies that
  slot this frame.
- `[stretch]` **Parquet export**: after a run (or from its shards),
  convert to a single Parquet file for pandas/Spark-style analytical
  tooling — this is where the original "readable by non-JVM tools"
  motivation lives, without forcing the live write path to fight Parquet's
  row-group structure or pulling `parquet-mr`'s heavier dependency
  footprint into the recording hot path. Arrow IPC files are already
  directly readable from Python via `pyarrow` without this conversion, so
  export is a convenience, not the primary interchange mechanism.
- Selective recording: choose which attributes to persist (position always;
  velocity/mass/force optional) to control file size `[stretch]`.
- `[stretch]` Compression (Arrow IPC supports LZ4/ZSTD natively via writer
  options) and/or delta-encoding between frames.

### 9.3 Performance for large N
- **Particle memory layout**: physics-hot per-particle fields — position,
  velocity, acceleration, mass, radius — are stored as **struct-of-arrays**
  (parallel primitive `DoubleArray`s indexed by particle slot), not one
  object per particle. On the JVM, object-per-particle means an object
  header plus a separate heap object for each `Vector3` field, which costs
  GC pressure and cache-unfriendly pointer chasing in exactly the loop
  that runs every step for every particle. This is the single decision
  that determines whether the 50,000-particle example in §10.2 is actually
  reachable, so it's made now rather than left to a later refactor.
  - **Particles are referenced everywhere by their stable `id` (§4.2),
    never by object reference** — forces, constraints, groups, and
    renderers already operate on ids/selectors, not particle objects. This
    is what keeps the SoA decision from leaking into every other system:
    nothing outside the storage layer ever holds a direct reference into
    it, so the backing representation can be optimized without touching
    anything downstream.
  - An internal `ParticleStore`-type abstraction owns the backing arrays
    and exposes id-keyed accessors. The Kotlin DSL's particle builder
    (§4.3) reads and writes through a thin per-particle handle backed by
    it, so `position = Vector3(...)` in DSL code (as in the §4.3 example)
    still reads like an ordinary field assignment despite writing into SoA
    arrays underneath.
  - **Sparse/cold per-particle data** — tags, per-particle renderer
    overrides, the `[stretch]` custom-attribute bag (§3) — does *not* need
    this treatment. It's not iterated every step for every particle, so an
    ordinary id-keyed map is fine and considerably simpler than forcing
    everything into parallel arrays.
  - **Spawn/destroy** (§14) needs an id→slot indirection: ids are never
    reused (§14.3), but slot indices in the backing arrays should be, via
    a free-list of reclaimed slots, to avoid unbounded array growth over a
    long-running emitter-heavy simulation.
- Pairwise forces (N-body gravity, and naively, spring lookups) are O(n²)
  without care. **Collision** (§12.4) now has a uniform-grid broad phase
  (`particlesim.collision.SpatialGrid`) — done, not `[stretch]`, since a
  concrete scenario (`SpatialGridDebugDemo`, 2000 particles) made the
  brute-force cost real. **N-body gravity does not share this structure**,
  a deliberate revision of this section's original plan: a uniform grid is
  *exact* for collision (a hard physical cutoff — two spheres can only
  overlap within `radiusA + radiusB`, so cell size just needs to exceed
  that and nothing is ever missed) but gravity sums a contribution from
  every pair with no cutoff at all, so handing it only grid-neighbor pairs
  would silently drop every long-range term — a correctness bug, not "a
  suboptimal cell size," which is what this section originally assumed.
  Accelerating gravity at scale therefore still needs its own structure —
  Barnes-Hut (aggregating distant mass rather than dropping it) rather
  than a second, differently-tuned grid — and remains `[stretch]`, not
  attempted yet.
- **Multi-threaded force accumulation, without sacrificing determinism**
  (§11): naive parallel reduction — multiple threads racing to add into a
  shared per-particle force accumulator — is not reproducible, since
  floating-point addition isn't associative and the arrival order depends
  on OS thread scheduling. The fix is **fixed-chunk deterministic
  reduction**: partition the particle/pair set into a *fixed number* of
  logical chunks — a constant, independent of how many threads or cores
  are actually available — where each chunk accumulates into its own
  private per-particle accumulator array (cheap given the SoA layout
  above: just more `DoubleArray`s indexed the same way). Once every chunk
  finishes, chunks are summed into the final per-particle force in fixed
  chunk-index order, regardless of which thread finished which chunk when.
  Result: bit-identical output for a given seed and chunk count, on any
  machine — not just reruns on the same one — for the cost of a few extra
  arrays and an ordered merge pass, not a re-architecture.
- Particle count is not necessarily fixed for the duration of a run (§14) —
  spatial partitioning and any preallocated buffers need to support
  insert/remove, not just a static build at `t = 0`.
- `[stretch]` GPU compute (e.g. via a compute shader) for very large N.

### 9.4 Interactive particle manipulation

In real-time mode, a viewer can let the user click-select a particle and
drag it with the mouse to see how that motion propagates through connected
forces (springs, surfaces) to the rest of the system — a direct way to
probe a simulation's behavior without editing YAML/Kotlin and re-running.

- **Picking** (raycasting from screen space to the nearest particle) is
  resolved entirely on the viewer side, using state it already has — the
  engine doesn't need to know about cameras or screen coordinates.
- **While held**, the picked particle is driven exactly like a fixed-
  position constraint (§6), except the target position comes from the live
  input stream (§9.1) each frame instead of an expression — reusing the
  existing constraint mechanism rather than adding a parallel one. Each
  drag target the viewer sends is stamped with the **physics step index**
  it's meant for, not just a wall-clock timestamp — necessary for a
  recorded run to replay the drag against the exact same step it actually
  happened on (§11), since viewer frame rate and physics step rate aren't
  the same thing.
- **On release**, the constraint is removed and the particle's velocity is
  set from its recent drag motion (estimated from the last frame or two of
  positions), so letting go while moving imparts a natural "throw" instead
  of a dead stop.
- Only meaningful with a live engine attached — applies to §9.1, not
  headless batch runs (§9.2). For a recorded run to replay exactly when it
  included drag input, the drag targets themselves must be part of what's
  recorded, same as any other event affecting the run (§5.4).
- `[stretch]` Forking: scrub a recorded playback to a chosen frame and
  branch off into a new live interactive session starting from that
  recorded state, rather than manipulation only being available during the
  original live run. Built on the checkpoint mechanism in §9.5, not the
  per-frame recording alone — see there for why.

### 9.5 Checkpointing

A **checkpoint** is a complete, resumable snapshot of simulation state at
one instant — categorically different from §9.2's per-frame recording,
which is rendering-oriented (positions/attributes over time for playback)
rather than a full internal-state dump. Two things depend on this being a
real mechanism rather than something inferred from playback data: resuming
a long batch run after a crash (§13.2), and forking a new live session off
a recorded point (§9.4, `[stretch]`).

- **Cadence**: a checkpoint is written at every recording shard boundary
  (§9.2) — reuses the recording's existing periodic sync point rather than
  a second independent schedule.
- **What's captured**: full per-particle state (id, position, velocity,
  radius, `spawnTime`, `lifetime`), current group membership (§4.2 — this
  is dynamic and can't be rederived from the static YAML/Kotlin definition
  alone, since emitters mutate it at runtime), the set of already-broken
  spring/damper connections (§5.4 — a one-way runtime mutation not present
  in the original definition), each emitter's spawn-accumulator phase and
  RNG sub-stream state (§14.1, §11), and the current sim time `t` /
  physics step index.
- **What's deliberately *not* captured, because it's cheap to recompute**:
  mass — a pure function of `(t, particle state)` per §3, recomputed on
  resume rather than stored — and the particle store's internal slot/
  free-list bookkeeping (§9.3): since everything outside the store already
  references particles only by stable `id`, never by slot, the store
  rebuilds itself from the checkpoint's particle list in any order.
- **Format**: the bulk per-particle data is naturally columnar, so it
  reuses the same Arrow IPC tooling as §9.2, plus a small JSON sidecar for
  the non-columnar scene metadata (broken-connection pairs, per-emitter
  state, `t`, format version) — avoids a third serialization format for
  one small piece of state.
- **Resuming a batch run** (§13.2): restart pointing at the last complete
  checkpoint plus the same simulation definition; the engine reconstructs
  state and continues stepping, appending a *new* shard rather than
  touching the (possibly corrupt) interrupted one.
- **Forking from playback** (§9.4): because determinism (§11) holds
  exactly — including under multi-threading, per §9.3's fixed-chunk
  reduction — a fork doesn't need a checkpoint at the precise requested
  frame. It resumes from the nearest earlier checkpoint and
  deterministically fast-forwards to the target frame (reproducing the
  recorded frames exactly along the way) before handing control to live
  input. This is what makes the fork idea buildable on a bounded number of
  checkpoints instead of needing one every single frame.

### 9.6 Scene library & switching

A **scene library** is a named catalog of complete, runnable simulations —
every worked example and demo this project builds belongs in it, present
and future — distinct from §4.5's shape library: a shape is a composable
*fragment* (particles/forces/constraints/surface/colliders) meant to be
instantiated one or more times *into* a larger scene; a scene-library
entry is a whole simulation in its own right, including things a shape
doesn't carry — spawn/emitter logic (§14.1), interactive drag rules
(§9.4), scripted camera (§10.1), and its own registry (§10.3). The
simplest possible scene-library entry (a single shape run standalone,
nothing else) is a special case of the general one, not the general case
itself.

- **Selectable and switchable at any time**: the running viewer offers a
  picker (§10.3) listing every scene in the library by name; choosing one
  tears down whatever's currently running and starts the chosen scene from
  its initial state, without dropping the viewer's connection or requiring
  a page reload — the same connection just starts receiving frames from a
  different simulation. Deliberately not "restart the process and
  reconnect": an interactive session is meant to survive a scene change
  the same way it already survives §9.1's pause/step/speed controls.
- **What a switch resets**: sim time `t`/step back to zero; the particle
  store/groups/forces/constraints rebuilt from the new scene's own
  definition (never merged with the old one); the registry (§10.3)
  rebuilt to match; the camera reset to the new scene's own scripted pose
  (§10.1), if it has one. Viewer-side ephemeral state the new scene has no
  way to know about — selection, hidden-group/-surface toggles, the event
  log — is cleared too, the same "nothing carries over" rule applied
  consistently on both sides of the connection.
- **One dispatch/reset path, not one per scene**: every scene's viewer
  input (drag targets, §9.4; scene-control edits, §10.4) is drained and
  applied through one generic mechanism shared by every scene, not — as
  today's individual demos each do it — a hand-rolled copy of that
  dispatch loop per demo, each handling its own subset of message types.
  That duplication is exactly what let one demo silently ignore a whole
  class of edits before anyone noticed (a real bug hit during §10.4's own
  build-out); a scene library would only multiply the number of copies
  that duplication risks, so unifying the dispatch path is part of this
  requirement, not a separate cleanup.
- **Determinism** (§9.3, §11) holds per scene exactly as it already does
  for a single long-lived run: loading the same named scene twice must
  produce identical frames from `t=0`, the same guarantee already
  required of a fresh process start.
- **Both authoring front-ends** (§4.4): a scene-library entry is addressed
  by name regardless of whether it's defined via the Kotlin DSL (a
  function returning a scenario — today's only mechanism, e.g. the
  existing `buildFlag`-style builders) or, once YAML scenes exist, a YAML
  file — matching §4.4's "DSL first, YAML once the shape has stabilized"
  precedent rather than building two separate catalogs.

## 10. Visualization & Rendering

- Supports both **2D** (e.g. top-down or side projection) and **3D** views
  of the same simulation.
- **Render styles** for particles: `dot`, `sphere` (using the particle's
  `radius`, §3, by default; can be overridden independently), with the
  style system designed to add more later (`[stretch]`: velocity
  trails/traces, custom meshes/glTF per particle). These, and force
  visualization, are configured through **renderers** (§10.2) — nothing
  renders unless something explicitly targets it.
- Surfaces render as shaded/wireframe triangle meshes (also configured via
  a renderer, §10.2).
- Color can be mapped from an attribute (velocity magnitude, mass, force
  magnitude) — a small, reusable "color by attribute" mechanism `[stretch]`.
- Basic scene aids: ground grid, axes.
- As decided in §9.1, the **web viewer (WebGL/three.js)** is the sole
  first-class viewer — orbit/pan/zoom camera controls, raycasting-based
  picking (§9.4), and mesh/sphere/line/arrow rendering are all close to
  built-in there, and building against the WebSocket binary protocol from
  day one keeps the engine/viewer decoupling (§9.1) real rather than
  aspirational. A native (JavaFX/LWJGL) viewer is `[stretch]` — the state
  contract stays clean enough that one stays possible later, but it isn't
  being built now.

### 10.1 Camera

The camera has both an automated (scripted) mode and a manual (user-
driven) mode; a viewer can switch between them at any time.

**Automated**: camera position and orientation (e.g. a look-at target plus
an up vector) are declared the same way as any other expression-capable
quantity (§4.1), or, in the Kotlin DSL (§4.3), a native lambda — so a
camera can be a function of time, or of scene state via the scene-query
API (§4.1): follow a particle's position, orbit around a group's centroid,
align to a surface's normal, and so on.

```kotlin
camera {
    lookAt(target = position("flag-tip"), up = Vector3(0.0, 1.0, 0.0))
    position { t ->
        centroid("flag") + Vector3(sin(t * 0.3) * 5.0, 2.0, cos(t * 0.3) * 5.0)
    }
}
```

The **engine**, not the viewer, evaluates the camera function each step,
and includes the resulting pose (plain position + orientation numbers) in
the per-frame state stream (§9.1) alongside particle data. This keeps
viewers simple — no expression/lambda evaluator needed client-side, so a
web viewer behaves identically whether the camera was authored via a YAML
expression or a Kotlin lambda — and makes a recorded run's camera path
replay exactly during playback (§9.2), regardless of authoring format.

**Manual**: a viewer supports direct camera control (orbit/pan/zoom, or
fly-style movement) as standard viewer-local interaction. This never
round-trips through the engine or affects the recorded stream, since it's
purely a matter of how the (already-known) scene is being looked at —
unlike interactive particle manipulation (§9.4), which does affect the
simulation. Interacting with the viewport switches the viewer into manual
mode; an explicit action returns it to the scripted camera.

### 10.2 Renderers

A **renderer** is a standalone, optional declaration: it targets an
existing group, named force (§5), or surface by name and says how to draw
it. Renderers live entirely outside the physics definition — adding,
removing, or changing one has zero effect on how the simulation runs, only
on what's visible, the same decoupling principle as §9.1's engine/viewer
split. **Nothing renders unless a renderer targets it** — an unconfigured
particle group, force, or surface is simply invisible. This is deliberate:
a 50,000-particle N-body run doesn't need (or want) every particle
rendered, and most forces — especially ambient ones like uniform gravity —
are rarely worth visualizing at all.

**Debug-render override**: opt-in rendering is the right default for a
finished scenario, but it means every *new* scenario shows nothing at all
until an author has also written renderers for it — and for a physics
simulator, watching it is the primary debugging tool, not a nice-to-have.
A debug/`--render-all` mode ignores renderer declarations entirely and
draws every particle as a dot, every pairwise force as a line, and every
collider as wireframe, so there's always a way to *see* a simulation while
it's being built, before its real renderers exist.

**Particle & surface renderers**: `dot`, `sphere` (§10, using the group's
particles' `radius` by default), or a shaded/wireframe mesh for a surface.
*(New requirement)* A surface mesh should also be able to render with an
**image texture** instead of (or as) its shaded material — e.g. an actual
flag graphic mapped onto §7.3's flag surface, rather than a flat solid
color. This needs two things neither exists today: **UV coordinates**
per triangle vertex (`Surface`/`Triangle` currently carry none — for a
grid-generated shape like the flag, UVs fall out directly from each
vertex's row/col position in the grid, so no separate authoring is
needed for that case, but an arbitrary/non-grid surface would need them
supplied explicitly) and a way to **reference an image asset** from a
surface's renderer declaration (a file path or URL, alongside `kind:
mesh` in the YAML example below) that reaches the browser client — most
naturally served once as a static asset and referenced by URL rather than
pushed through the binary per-frame protocol (§9.1) the way per-frame
mesh/particle state is, since a texture image doesn't change every step
the way vertex positions do. Related to, but distinct from, the
`[stretch]` **Lighting & materials** item below: that's about general
per-object material properties (color, shininess) with no asset
involved, this is specifically about mapping an external image onto a
mesh's surface.

**Force renderers**:
- Directional field forces (wind, uniform gravity) render as **arrows**
  sampled on a grid over a region — since a field isn't localized to
  specific particles, its renderer needs a sampling region and resolution
  rather than just a group target. `[stretch]` streamlines as a denser
  alternative to discrete arrows.
- Pairwise forces (spring/damper, §5.1) render as a **line** between the
  two connected particles, optionally colored and/or thickness-mapped to a
  derived attribute of the connection's current state:
  - `stretch` / `force`: current deformation or force magnitude.
  - `breakProximity` (only meaningful on a breakable connection, §5.4): the
    ratio of current deformation (or force) to whichever break threshold
    applies right now — `extensionBreakThreshold` or
    `compressionBreakThreshold` — using the same direction-dependent logic
    §5.4 already uses to decide when it actually breaks, so `0` is at rest
    and `1` is the instant before breaking. This is what lets a spring
    visibly redden (or thin, or thicken — the mapping direction is a style
    choice) as it approaches its limit, e.g. a flag's structural springs
    (§7.1) warning of an imminent tear (§5.4) well before it happens,
    rather than the break being a total surprise.
  - Default mapping is a perceptually-ordered, colorblind-safe gradient
    (e.g. a blue → orange or viridis-style scale) as the chosen attribute
    goes 0 → 1 — deliberately *not* the intuitive green → yellow → red,
    which is close to worst-case for deuteranopia and one of the most
    common color vision deficiencies. Same "map a derived attribute to
    color/thickness" idea as particle color-by-attribute, just applied to
    a connection instead of a single particle.
- N-body gravity and custom forces (§5.3) are lower priority / `[stretch]`
  — N-body isn't localized to a renderable pair (force exists between
  every pair), and custom forces don't have a defined shape yet.
- `[stretch]` **Lighting & materials**: today's renderer draws flat/unlit
  dots, spheres, and meshes with one fixed appearance. Configurable light
  sources (ambient, directional, point — the standard three.js set, since
  §9.1/§10 already commits to that as the sole first-class viewer) and
  per-object/per-group material properties (base color, shininess/
  opacity, or eventually a plugged-in shader) would let a scene actually
  look considered rather than uniformly flat-shaded. These would be
  additional renderer-adjacent declarations once built — e.g. a
  scene-level `lights:` list, and a `material:` block alongside a
  renderer's existing `kind`/`colorBy` — not a special case bolted on
  outside the renderer system. Deferred the same way §5.2's spatially-
  varying wind and §12.4's particle-vs-surface collision were: no scene
  built so far has needed it, and designing a lighting/material system
  ahead of a concrete visual target would be guessing at its shape rather
  than responding to one.

```yaml
renderers:
  - target: flag              # particle group (§4.2)
    kind: sphere
  - target: wind               # named force (§5)
    kind: arrows
    region: { min: [-5, -1, -5], max: [5, 5, 5] }
    resolution: 1.0
  - target: flag-cloth         # surface (§7)
    kind: mesh
  - target: flag-structural-springs   # named breakable force (§5, §5.4)
    kind: line
    colorBy: breakProximity
```

> Extending the flag example (§7.3): the flag surface itself renders as a
> shaded mesh, the pole-edge particles render as small spheres so the
> anchor is visible, and the wind force gets an arrow renderer sampled
> around the flag. The individual cloth particles have no renderer of
> their own — the mesh already shows them — but the structural springs
> (§7.1) do get a `breakProximity`-colored line renderer, so in a strong
> enough gust you'll see the cloth redden right at the seam that's about
> to tear (§5.4) before it actually does.

### 10.3 Viewer UI

Renderers (§10.2) decide *what* draws; this section is how a person
actually controls and inspects that in the running viewer — global
display toggles, per-object visibility, selection, and enough live
readout to debug a scene without a separate tool.

- **Scene picker**: a persistent control listing every scene in the
  library (§9.6) by name; switching is available at any time the viewer
  is connected, not just at startup.
- **Global toggles**: scene aids not tied to any particular object —
  the ground grid and axes (§10) — can be switched on/off independent of
  everything else.
- **An outliner**: a persistent, always-available list of every group,
  named force, constraint, collider, and surface in the scene, regardless
  of whether anything currently renders it. This is the answer to "how do
  I reach an object's UI when it isn't visible" — right-click-in-3D (below)
  only works on something already on screen, so a parallel list is what
  makes an *invisible* force or an unrendered group's settings reachable
  at all. Selecting an entry opens the same per-object panel a right-click
  would.
- **Per-object panel**: for a group, force, constraint, collider, or
  surface — independent visibility toggles for whatever that object type
  can render (a group's particles, a force's arrows/lines, a surface's
  mesh), plus whatever renderer-specific settings apply (§10.2's `kind`,
  `colorBy`, sphere-vs-dot, region/resolution for arrows). Reachable via
  the outliner or via right-click.
- **Right-click to open**: right-clicking a rendered object in the 3D
  view opens its per-object panel directly, without going through the
  outliner first — the fast path for something already visible. This
  includes a directional field force's arrow renderer (§10.2, e.g. wind):
  right-clicking any one sampled arrow selects the *force* that produced
  it (by the arrow group's source-force name), the same way right-
  clicking a mesh triangle already selects its owning surface — a field
  force has no single particle to click, so its arrows are the only
  clickable surface it has.
- **Selection & inspection**: selecting an object (via the 3D view or the
  outliner) shows live numeric readout for it — a particle's position/
  velocity, a force's current magnitude, a breakable connection's current
  `breakProximity` — useful for debugging a scene without external
  tooling.
- **Color legend**: whenever a `colorBy` gradient (§10.2) is active
  anywhere in the scene, a small legend shows what the gradient's two
  ends mean, so "blue → orange" isn't something to memorize per scene.
- **Stats overlay**: live particle count, step rate, and how far physics
  time is lagging wall-clock time — the last of these is a direct,
  user-visible readout of §9.1's pacing policy (frames drop before
  physics ever coarsens), rather than a silent internal detail.
- **Camera bookmarks**: save and return to a handful of named manual
  camera views (§10.1) — e.g. top-down, side-on, following a group's
  centroid — instead of re-orbiting from scratch every time.
- **Time controls** (pause, speed multiplier, step-once) are already
  specified in §9.1 but are UI surface, not just engine capability —
  listed here as part of what this viewer needs to actually expose, not
  a new requirement.
- **Live parameter tweaking**: editing an entity's numeric parameters
  from its per-object panel, with the change taking effect in the
  running simulation. Deliberately distinct from everything else in this
  section: every other item here is the viewer *reading* engine state,
  the same direction §9.1's state stream already flows; this is the
  viewer *writing back*, generalizing §9.4's drag-target channel
  (currently the only case of viewer-to-engine input, narrowly typed to
  "a position") into something that can carry an arbitrary named
  parameter edit instead. Fully specified below (§10.4) — no longer
  `[stretch]` as a design, though none of it is built yet.
- **Per-entity-type UI modules, self-registered**: each entity kind's
  per-object panel is owned by a module —
  `{kind, getNames(registry)?, renderPanel(), updateLive()?}` — that
  registers itself with the viewer via `registerEntityKind`, claiming the
  outliner section the page already declares for that kind rather than
  building DOM from scratch, so registering is purely additive to what's
  already on the page. Introducing a new visualizable kind means writing
  and registering a module, not editing shared dispatch code every other
  kind already depends on. `getNames` is the one optional hook: a kind
  with no outliner presence (particles — see "Selection & inspection"
  above; there are potentially thousands, never listed) omits it, and is
  reachable only via 3D-view selection setting `selectedKind`/`selectedName`
  directly, never via an outliner click. Proven against three kinds —
  **groups** (a visibility toggle, live "N drawable" info text, and live
  centroid/avg-speed inspection, exercising every hook a module can have),
  **forces** (a per-force arrow-visibility toggle, real only when that
  force has arrow samples in the current frame — see §10.2's arrow-sample
  wire format, now grouped by source-force name for exactly this reason),
  and **constraints/surfaces/colliders** (migrated onto this same module
  system from an older hardcoded dispatch path that predated it — see
  `todo/TODO.md`). Each kind's own module is also where kind-specific
  rendering settings would live (e.g. a future lighting/materials control
  panel, once that `[stretch]` item is built) instead of one more
  hardcoded case.

### 10.4 Live editing (viewer writes back into the running simulation)

Specified in full via a direct entity-by-entity walkthrough with the user;
nothing below is built yet. Two cross-cutting decisions apply to every
entity type before the per-type detail:

- **"Pause on edit: Yes/No"** is a single simulation-wide toggle, not a
  per-entity setting — when on, any edit anywhere pauses the run (via the
  existing time controls, §10.3) so the effect can be inspected at rest;
  when off, edits apply to the live run.
- **Edits are queued and applied at a step boundary, never mid-step** —
  the same pattern §9.4's drag target and §10.3's `SceneControlMessage`
  already use (drained once per frame/physics-step at a defined point,
  not mutated at an arbitrary time from the WebSocket I/O thread). This
  isn't just consistency for its own sake: §9.3/§11's fixed-chunk
  deterministic reduction assumes every chunk reads the same parameter
  value for a given step, so a force/constraint parameter mutated
  mid-step, read by chunk 0 before the edit and chunk 3 after, would
  silently break both determinism and chunk-order-independence. Applying
  every edit only between steps avoids the question entirely.

Most of what follows also surfaces a real implementation gap, not just a
UI one: `Spring`/`Damper`/`MeshSprings`' stiffness/damping and most
force/constraint fields (`UniformGravity.acceleration`, `NBodyGravity.g`/
`softening`, `Wind.density`, `FixedVelocity.velocity`, ...) are `private
val`s fixed at construction today. Making any of them live-editable means
making them mutable state the physics loop reads fresh each step, subject
to the queued-at-a-boundary rule above — a real code change per field,
not just wiring an existing value into the wire protocol. Fields already
expression-capable (`Wind.velocity`, any collider's position, `Emitter
.rate`) are comparatively cheap, since the machinery to vary them already
exists; a plain constant is the more common case, though.

**Colliders** (`PlaneCollider`/`SphereCollider`/`BoxCollider`):
- Position — already expression-capable; live-editing means authoring/
  overriding that expression.
- Orientation (a plane's `normal`) — currently a fixed `Vector3` set at
  construction, not an expression. Live-editing this needs the same
  expression-capable treatment position already has, not just a mutable
  field.
- Shape fields (`radius`, `halfExtents`).
- **Activation**: a live boolean, distinct from `SceneControlMessage
  .RemoveCollider`'s permanent removal — a deactivated collider is fully
  inert *and* hidden, reactivatable from its own tab. No special handling
  for anything resting on it: deactivating a collider a pile is resting
  on means the pile falls through immediately, the same as if the
  collider had never been there. This is intended behavior, not an edge
  case to guard against.
- Per-rule physics parameters (restitution, friction, damping) belong to
  the collider *rule*, not the collider itself, and one collider can be
  referenced by multiple rules — out of scope for this pass; only the
  collider's own geometry/activation are covered here.

**Particles & groups**:
- **Selection is dual**: picking a particle — from a group's member list
  in its own tab, or by clicking it in the 3D view — selects both the
  particle and its group, opening both panels together. Both entry
  points drive the same selection state.
- **Mass, radius**: editable both per-particle (an override) and
  per-group (bulk, applied to every current member) — both controls
  shown together whenever a particle is selected.
- **Position, velocity**: per-particle only, no group-level bulk control.
  The existing drag-and-throw interaction (§9.4) *is* this entity's
  position/velocity editing UI — not replaced by a numeric-only form.
- **A group's springs/dampers**: a group's tab also exposes the
  stiffness/damping parameters of every `Spring`/`Damper`/`MeshSprings`
  where **all** endpoints belong to that group — found by scanning
  membership (these forces target explicit particle ids, not a group
  name, so there's no live "targets this group" reference to follow).
  A spring with endpoints split across two different groups belongs to
  neither group's tab under this rule; that case doesn't arise in any
  shape built so far (every structural spring set is generated within
  one shape's own group), so it isn't specced further here.
- **Group enable/disable**: same fully-inert-and-hidden/reactivatable-
  from-its-tab semantics as a collider's activation, above.
- Rendering: a per-particle render override, plus per-group color
  override and visibility toggle.

**Forces** (`UniformGravity`, `NBodyGravity`, `Wind` — standalone, not a
surface's auto-generated `MeshSprings`, which is covered under "particles
& groups" above via its owning group): the existing per-force tab
(§10.3's self-registered module, currently just an arrow-visibility
toggle) gains real numeric-parameter editing — `acceleration`, `g`/
`softening` respectively, and for `Wind`, `density` (built) plus
`velocity` (§5.2, new requirement — built) as a single editable
expression-string field, mirroring particle mass/radius and emitter rate
rather than decomposing into separate direction/gust parameters. `Wind`
is associated with a specific surface via its triangle list, the
same "belongs to" lookup as a group's springs, not a named-group target
like the other two. Right-clicking one of `Wind`'s sampled arrows
(§10.2/§10.3, built) opens this same tab directly, the arrow-picking
counterpart to right-clicking a mesh triangle to open its surface's tab.

**Constraints**:
- `FixedPosition`: only the shared-position variant (one `Vector3` for
  the whole group) is editable. The per-particle-pinned variant (e.g.
  `atCurrentPositions`, used for the flag's pole edge) is view-only —
  editing it would mean editing individual entries in a map, which isn't
  in scope here.
- `FixedVelocity`: the group's `velocity` is editable.
- `DragConstraint`: **no tab at all**. It's explicitly documented as an
  ephemeral, viewer-driven object never meant to be outliner-listed —
  the existing drag-and-throw interaction is already its complete UI.

**Surfaces**: keep their own tab, separate from the underlying group's —
a mesh render-style toggle (shaded vs. wireframe) and read-only
generation parameters (grid dimensions, etc. — not live-editable, since
the mesh is built once at construction). No dedicated mass control: a
surface's particle mass is edited entirely through its group's tab
(above), and its structural stiffness/damping through that same group's
spring/damper controls.

**Emitters**: not currently in the outliner at all (no `EMITTERS`
section exists alongside groups/forces/constraints/surfaces/colliders)
— this is a new tab category. Editable: `rate` (already expression-
capable) and `maxAlive`/`capPolicy`. The per-spawn distributions
(`position`, `velocity`, `mass`, `radius`, `lifetime` — each a
`VectorDistribution`/`ScalarDistribution`, not a single value) are
**not** live-editable for now — a distribution is a shape/range, not a
value a simple control naturally edits, and nothing has needed it yet.
Any edit here only ever affects particles spawned *after* the edit;
already-alive particles from this emitter are never retroactively
touched.

## 11. Non-Functional Requirements

- **Units**: SI throughout (meters, kg, seconds, Newtons); documented in one
  place, no implicit unit conversions.
- **Coordinate convention**: right-handed, **+Y up** — pinned explicitly
  rather than left implicit in examples, since handedness/up-axis matters
  the moment anything crosses a tool boundary (glTF import, §10; a web
  viewer using three.js, which is also +Y-up by convention; any exported
  recording data, §9.2).
- **Determinism**: given the same YAML and a fixed random seed, a run
  should be exactly reproducible — important for debugging and for any
  stochastic force (e.g. turbulent wind noise) or spawning behavior
  (emitters, §14.1). This holds even under multi-threading, via the
  fixed-chunk deterministic reduction in §9.3 — determinism was
  deliberately not relaxed for parallel runs. `noise()` (§4.1) is
  inherently safe here since it's a pure function of position/time, not a
  stateful stream; emitters (§14.1) need their own per-emitter RNG
  sub-stream instead of a single shared one, since a shared stream
  consumed by whichever emitter/thread gets there first isn't
  reproducible.
- **Validation**: YAML schema validation with actionable error messages
  (§4.2).
- **Format versioning**: both the YAML schema (§4.2) and the recording
  format (§9.2) carry an explicit version field from the start. Adding one
  later, once files exist in the wild, means writing a migration; adding
  one now is a single field.
- **Extensibility**: new force types, constraint types, and render styles
  should be addable without changing the YAML parser core — a
  registry/plugin pattern internally, even before the `[stretch]` public
  plugin escape hatch is exposed.
- **Diagnostics**: logging of total system energy/momentum over time —
  promoted out of `[stretch]` to a core requirement. It's useful for
  sanity-checking that forces are implemented correctly (a spring-only
  system without damping should conserve energy; if it doesn't, something's
  wrong), and it doubles as the cheapest correctness test this project has
  (§13.5) — worth having from the start, not deferred. See §15 for how
  this and other checks turn into an actual testing strategy.

## 12. Collision Detection & Response

Collisions are **opt-in per pair of groups** — most simulations (e.g. the
flag example in §7.3) never enable collision at all, so it must add zero
overhead when unused.

### 12.1 Collidable particles

A particle that should participate in collisions needs a `radius` (§3) —
by default the same canonical radius used for rendering, though collision
can override it independently if a particle should collide as a different
size than it renders. Particles without a `radius` never participate in
collision detection.

### 12.2 Colliders

Not everything worth colliding against should have to be built out of
simulated particles. A new top-level `colliders` concept defines
infinite-mass geometry — infinite mass meaning collision forces never
displace it, not that it can't be authored to move (§12.5):
- **Plane** (e.g. ground, walls): point + normal.
- **Box**: axis-aligned or oriented, min/max extents.
- **Sphere**: center + radius.

Colliders don't have simulated mass or inertia — nothing pushes back on
one — but the point/center fields are **expression-capable**, so a moving
wall or floor is possible without making it a full particle. The
collider's motion is properly accounted for in collision response, not
just its position — see §12.5. Colliders are separate from `surfaces`
(§7): a surface is made of simulated particles and deforms; a collider is
a geometric primitive with no particles behind it, moving or not. The
ground grid already mentioned as a scene aid in §10 is a natural default
render of a plane collider.

### 12.3 Collision groups & filtering

Using the same group-selector mechanism as forces (§5) and constraints
(§6), a `collisions` section declares which group pairs are checked against
each other, e.g. "particles in group `debris` collide with collider
`ground` and with each other, but not with group `flag`". This keeps
collision checks scoped to what the simulation actually needs and is the
main lever for performance.

### 12.4 Detection

- **Broad phase**: a uniform grid (§9.3, `particlesim.collision.SpatialGrid`)
  sized to the finer of the two group's contact radii — done for
  particle-particle collision (`ParticleCollisionSystem`); `SurfaceCollisionSystem`
  is still brute-force, since a single surface's triangle count hasn't
  needed it yet. **Not** shared with N-body gravity — see §9.3's own note on
  why a hard-cutoff grid isn't valid for gravity's unbounded interaction
  range, a correction to this section's original "one structure serves
  both" plan.
- **Narrow phase**: sphere–sphere (particle–particle), sphere–plane,
  sphere–box, sphere–sphere-collider (particle–static sphere).
- **Timing**: discrete, checked once per integration step by default —
  simplest to implement and reason about. Fast-moving particles — or now,
  fast-moving colliders (§12.5) — can tunnel through a thin collider (or a
  particle) between steps; continuous collision detection (CCD) to
  prevent this is `[stretch]`, along with §8's sub-stepping as a cheaper
  partial mitigation (smaller dt = less tunneling risk either way).
- **Particle vs. triangulated surface** (a particle colliding with a flag
  or cloth, rather than with a plane/box/sphere collider) is `[stretch]` —
  meaningfully harder (point-vs-triangle tests, deformable target) than
  particle vs. static primitive. No longer purely speculative: §12.8's
  trampoline worked example needs exactly this, so it's the concrete
  consumer that's been missing so far (the same "deferred until something
  concretely needs it" pattern §5.2's wind position-dependence already
  followed) — still not scheduled, but no longer a guess at future need.
- **Surface self-collision** (cloth colliding with itself) is `[stretch]`
  and likely last in priority — expensive and only needed for
  heavily-folding cloth scenarios.

### 12.5 Response

- **Moving colliders**: every "relative velocity along the normal"
  computation in this section is between the particle and the *collider's*
  velocity, not an assumed-stationary surface — the same relative-velocity
  pattern §5.1's damper already uses between two particles, just with a
  collider standing in for the second side. A collider's instantaneous
  velocity is a **finite difference** of its position expression
  (`(pos_now - pos_prev) / dt`, at the current physics step's `dt`,
  §8/§13.1) — not a symbolic derivative, which would mean adding
  differentiation to the expression engine (§4.1) for a case finite
  difference already handles at the same numerical fidelity everything
  else in this engine already operates at. Velocity defaults to zero on a
  collider's very first evaluation, before a previous position exists.
  This is what makes a moving wall or paddle actually transfer momentum
  instead of just visually repositioning underneath a particle that never
  reacts to it.
- **Restitution**: a coefficient `e` per collision-group-pair (0 =
  perfectly inelastic, 1 = perfectly elastic), applied along the collision
  normal using standard impulse-based resolution. Expression-capable (§4.1)
  like everything else in the DSL — e.g. restitution that varies with
  impact speed — rather than a plain constant.
- **Damping** (optional): a collision-group-pair can also declare a damper
  along the collision normal, reusing the same asymmetric damping fields
  as a pairwise damper (§5.1) — `damping`, `extensionDamping`,
  `compressionDamping` — keyed the same way, by the sign of relative
  velocity at contact: still closing/penetrating (`compressionDamping`)
  vs. separating/rebounding (`extensionDamping`). This layers a
  continuous, velocity-dependent dissipation on top of restitution's
  instantaneous impulse — useful for a "soft" contact that absorbs energy
  differently going in than coming back out, e.g. a ball that lands with a
  mushy thud but rebounds crisply.
- **Friction** (tangential, opposing relative sliding velocity at the
  contact point) is `[stretch]`. Once promoted to core, it should follow
  the same asymmetric spirit as restitution/damping above, but keyed on
  the split that's physically relevant for a *tangential* force:
  **static vs. kinetic** friction coefficients (`staticFriction`,
  `kineticFriction`), not compression/extension — friction acts
  perpendicular to the collision normal, so the along-the-normal
  stretch/compression split from §5.1's damper doesn't apply here the same
  way. `staticFriction` governs while relative tangential velocity is
  ~zero (holding); `kineticFriction` takes over once sliding starts — the
  standard Coulomb friction model, and why real contacts tend to "stick"
  until enough force overcomes static friction, then slide more easily.
  Expression-capable like everything else once built.
- **Penetration correction**: since discrete detection can let particles
  overlap slightly before a collision is caught, apply a small positional
  correction (push particles/collider surface apart along the normal) in
  addition to the velocity impulse, to avoid visible sinking/jitter — see
  §13.4 for why this correction must be applied gradually, not all at once.
  On its own, this is exactly the recipe for a particle at rest to jitter
  forever rather than actually stop — see §12.7 for what closes that gap.
- **Constrained particles** (§6, fixed position/velocity) behave as
  infinite mass in collision response — they affect what they collide with
  but are never themselves moved by a collision, consistent with how they
  already ignore forces.

### 12.6 Worked example: a ball bouncing on the ground

> A single particle with `radius: 0.2` falls under uniform gravity toward a
> plane collider at `y = 0`. A `collisions` rule between the particle's
> group and the ground collider sets `restitution: 0.7`, plus asymmetric
> collision damping (§12.5): `compressionDamping: 3.0` (soft, absorbing
> impact) and `extensionDamping: 0.2` (crisp rebound). Run in real-time viz
> mode to watch it bounce, settle noticeably faster than restitution alone
> would produce, and come to rest.

This is a good complementary example to the flag (§7.3): minimal setup,
exercises colliders, restitution, and asymmetric collision damping without
needing surfaces or springs — and, with §12.7, actually comes to rest
rather than jittering forever.

### 12.7 Resting contact & sleeping

Discrete collision detection (§12.4) plus gravity plus fractional
penetration correction (§12.5) is, on its own, the standard recipe for a
particle at rest on a surface to jitter forever instead of stopping — each
step it sinks slightly, gets partially pushed back out, and the resulting
micro-bounce never quite decays to zero. Two complementary mechanisms
close this gap.

**Contact velocity clamping**, every step: when a particle's relative
normal velocity at a contact is below a small threshold `restVelocity`,
and it's within `restPenetration` of the surface, the normal component of
its velocity is zeroed directly rather than having restitution (§12.5)
applied to it — there's no physically meaningful bounce to compute for a
velocity already indistinguishable from rest. This alone stops the jitter
from growing, but the particle still does full physics work every step.

**Sleeping**, temporal hysteresis: a particle that has satisfied the
velocity-clamping condition continuously for `restDuration` is marked
**asleep** — excluded from force accumulation and integration entirely,
effectively frozen at its current position, until something wakes it.
This is *not* the same as a fixed-position constraint (§6): it's a
temporary, engine-managed optimization rather than an authored guarantee,
and it reverses on its own the moment the particle is disturbed.
- **Waking propagates**: an awake particle colliding with a sleeping one
  wakes it (checked during the normal broad-phase pass, §12.4, which still
  runs for sleeping particles even though force accumulation doesn't); a
  spring/damper connecting an awake particle to a sleeping one wakes the
  sleeping end, since the awake end pulling on it means it isn't actually
  at equilibrium; and an interactive drag (§9.4) or any runtime change to
  a particle's forces/constraints wakes it immediately.
- `restVelocity`, `restPenetration`, and `restDuration` are global
  defaults, not per-collision-pair — this is a stability/performance
  mechanism, not another creative dial, so simple beats configurable here.
- **Determinism** (§11) still holds: a particle's sleep/wake state each
  step is decided from the same fixed-chunk-reduced state (§9.3), not a
  separate racy check, so which particles are asleep at a given step is
  exactly reproducible like everything else.
- Sleeping particles skip the force/integration hot loop entirely, which
  is also a genuine performance win at scale (§9.3) beyond just fixing the
  jitter — a large pile of settled particles costs almost nothing once
  they're asleep.

### 12.8 Worked example: a trampoline

> A taut, roughly square surface (§7) — much stiffer structural springs
> than the flag's, and its rim particles pinned to a fixed frame (§6)
> instead of just one edge — sits horizontally a short drop below a ball
> (§12.6's single collidable particle). The ball falls onto the trampoline
> surface, the surface deforms downward under the impact, and its own
> restoring spring force launches the ball back up — a bounce that comes
> from the surface's actual deformation, not a fixed-shape collider's
> restitution coefficient.

This is the concrete scenario that promotes particle-vs-triangulated-
surface collision (§12.4) out of pure speculation: the ball needs to
collide with the trampoline's current (deformed) shape every step, not a
static proxy shape standing in for it. Everything else the trampoline
needs already exists once that lands — a surface (§7) with structural
springs (§7.1) and a rim pinned via `FixedPosition` (§6) is the same
mechanism the flag's pole edge already uses, and the ball itself is
§12.6's worked example unchanged. A shape library entry (§4.5) for
"trampoline" is the natural way to package it once built, the same as a
flag or a ball would be.

## 13. Numerical Stability

Yes, this is achievable, but it isn't a property of any single component —
it falls out of deliberate choices across integration, timestep, spring
stiffness, mass, and force singularities, several of which are already
mentioned elsewhere in this doc. This section consolidates the concrete
techniques and points back to where each already lives.

### 13.1 Integrator choice & the stiffness/timestep relationship

- Explicit (forward) Euler is excluded as an option entirely — it's
  unstable for even moderately stiff springs, which rules it out for
  anything involving §7's structural springs.
- Semi-implicit (symplectic) Euler, the current default (§8), is only
  **conditionally stable**: stable as long as `dt` is small enough relative
  to a spring's stiffness and the masses it connects — roughly
  `dt < 2·√(m/k)` per spring, using whichever of `extensionStiffness` /
  `compressionStiffness` (§5.1) is larger for that spring, since that's the
  binding constraint. It's a good default because it's cheap and,
  unlike explicit Euler, tends to dissipate rather than inject energy, so
  it degrades gracefully rather than exploding outright near the limit —
  but the limit still exists.
- **This formula is optimistic, not a design target.** `dt < 2·√(m/k)` is
  the marginal-stability limit for one isolated spring, using its own mass.
  A spring connecting two particles is actually governed by their *reduced*
  mass, and a coupled mesh — the structural/shear/bend network in §7.1 is
  exactly this case — is bounded by the largest eigenvalue of the whole
  mesh's stiffness matrix, which is meaningfully tighter than any
  single-spring estimate. Marginal stability also isn't *usable* stability:
  whatever `dt` auto sub-stepping derives from this bound should apply a
  safety factor (roughly 0.1–0.5× the formula's result), not use it as-is.
- For scenes that push stiffness toward that limit (e.g. a taut, high-
  stiffness flag cloth), two complementary strategies:
  - **Auto sub-stepping** (§8, `[stretch]`): derive a safe per-step `dt`
    from the stiffest spring/lightest mass currently in the scene and
    automatically sub-step within one recorded/rendered frame, instead of
    requiring the author to hand-tune `dt` against a formula.
  - **Implicit integration** `[stretch]`: backward Euler / implicit
    midpoint, unconditionally stable regardless of stiffness, at the cost
    of solving a small linear system per step — the standard approach in
    production cloth solvers (Baraff & Witkin-style). Worth offering as a
    selectable integrator for scenes where sub-stepping alone gets too slow.
    Confirmed as a real future enhancement rather than core v1 scope: the
    primary target (real-time flags/cloth at moderate stiffness) should be
    well served by sub-stepping alone.

### 13.2 Runtime safety net

- **Mass** (§3) can be an expression; nothing stops an author from writing
  one that reaches zero or negative at some `t`. The engine must guard
  against non-positive mass — reject at validation time when statically
  checkable (e.g. a constant), otherwise clamp to a small positive epsilon
  and warn at runtime — rather than dividing by zero.
- **Force singularities**: both spring forces and N-body gravity (§5) have
  a `1/r` or `1/r²` term that spikes as two particles approach the same
  position. Apply a small softening length (a minimum `r` used in the force
  calculation) — the standard N-body technique — rather than letting a
  near-coincidence produce an unbounded force. The default softening length
  is **configurable per force type**, not one global constant — springs
  and N-body gravity operate at very different distance scales, so a
  single default couldn't sensibly serve both.
- **Blow-up detection**: check for NaN/Infinity in position or velocity
  after each step and fail fast with an error naming the offending
  particle and force, rather than silently propagating garbage. This
  matters most for batch/record mode (§9.2), where an undetected blow-up
  could waste a long headless run before anyone notices at playback — and
  since a run can resume from its last checkpoint (§9.5) rather than
  restarting from scratch, failing fast here costs at most the frames
  since the last shard boundary, not the whole run.
- **Expression evaluation is also NaN/Infinity-checked**, at the moment any
  expression-capable field (§4.1) evaluates — not just downstream once a
  bad value has already propagated into a position/velocity blow-up. The
  error names the specific field path (e.g. `flag.mass`, `wind.strength`)
  that produced it, catching a broken expression at its source instead of
  wherever its effects happen to surface a few steps later.

### 13.3 Precision

Use double precision (64-bit) for all particle state and force
accumulation, not single precision. Long runs (large `duration` over small
`dt`, §8) accumulate floating-point error over many steps, and double
precision is cheap on the JVM relative to the stability headroom it buys.

### 13.4 Collision-specific stability

Penetration correction (§12.5) can itself introduce jitter if applied too
aggressively — fully resolving penetration in a single step can produce
bouncing that wasn't physically there. Correction strength should be
tunable (e.g. correct only a fraction of the penetration per step) rather
than hard-coded to fully resolve in one step.

### 13.5 Diagnostics as an early-warning system

The energy/momentum logging required by §11 doubles as a stability
diagnostic: a spring-only system that's supposed to conserve energy but
visibly gains it over time is a direct, early signal that `dt` is too
large for the current stiffness — well before the simulation visibly
explodes.

## 14. Particle Creation & Destruction

Particles don't have to all exist for the full duration of a run — new ones
can be spawned during a simulation (sparks, smoke, fireworks) and existing
ones removed (expired, left a region, destroyed on impact). This turns the
particle set from a fixed collection declared once into a population that
changes over time.

### 14.1 Creation: emitters

An **emitter** is the primary spawning mechanism: a spawn rate
(particles/sec, expression-capable so it can vary over time — bursts,
ramps) plus initial-property distributions for each new particle's
position (within a region/volume), velocity, mass, radius, and lifetime.
The core distribution shapes supported natively are **uniform-in-box**,
**uniform-in-sphere**, and **point-with-spread** (a fixed origin plus a
random cone/range, typical for sparks and debris) — covering the common
fire/smoke/spark/firework cases. Anything more exotic (custom shapes,
non-uniform distributions) is left to the Kotlin DSL's programmatic
generators (§4.3), consistent with the YAML-vs-Kotlin split in §4.4.

Newly spawned particles are automatically added to a target group (§4.2),
so any force, constraint, collision rule, or renderer already targeting
that group applies to them with no extra wiring — reusing the existing
group mechanism rather than special-casing spawned particles.

Each emitter draws from its own independent, deterministically-seeded RNG
sub-stream (§11, §14.4) — not a single stream shared across emitters —
so the same master seed reproduces the same spawn sequence for a given
emitter regardless of how many other emitters or threads are running.

`[stretch]` Explicit/event-triggered spawn — e.g. spawning fragment
particles when a spring breaks (§5.4) — is a natural extension but more
speculative than rate-based emitters; scope emitters as the core mechanism
first.

**Particle budget**: an emitter with an expression-capable spawn rate and
no `lifetime` set on its spawned particles will otherwise happily allocate
until the process runs out of memory. Every emitter needs a hard cap on
live particle count, with a defined policy for what happens at the cap —
stop emitting, or evict the oldest — plus a warning when it's hit, rather
than that being a silent behavior change.

### 14.2 Destruction

A particle can be destroyed by:
- **Lifetime expiry**: once `t - spawnTime` exceeds its `lifetime` (§3), if
  one was set.
- **Expression condition**: an expression-capable per-group destroy
  condition evaluated each step (e.g. leaves a bounding region), reusing
  the shared expression engine (§4.1).
- **Collision-triggered**: destroyed on impact with a specific
  collider/group (§12) — e.g. a spark disappearing when it hits the
  ground.
- `[stretch]` **Interactive**: explicit delete via the viewer, alongside
  interactive dragging (§9.4).

### 14.3 Cleanup semantics

- Destroying a particle automatically removes any forces or constraints
  that reference it, rather than leaving a dangling reference — the same
  cleanup already required when a spring breaks (§5.4).
- Particle IDs (§4.2) are never reused while any live reference could still
  point to them, to avoid a stale reference silently resolving to a
  different particle later.
- A particle that's also a surface vertex (§7) can't be safely removed
  without invalidating its triangles. Scope destruction to free
  (non-surface) particles for now; destroying surface vertices stays a
  documented `[stretch]` goal — e.g. a chunk of debris tearing off a flag —
  rather than being dropped from the roadmap, since it needs its own
  mesh-repair design that isn't worth blocking v1 on.

### 14.4 Architectural ripple

Allowing the particle count to change during a run touches several things
already designed around a fixed, upfront N:
- **Performance** (§9.3): spatial partitioning and any preallocated buffers
  need to support insert/remove, not just a static build at `t = 0`.
- **Recording/playback** (§9.2): the columnar per-frame format needs a
  per-frame alive-set, or spawn/destroy events layered on top — the same
  discrete-event channel already carrying force breaks and camera pose
  (§9.1) is the natural place for these.
- **Determinism** (§11): each emitter draws from its **own independent RNG
  sub-stream**, seeded deterministically from the run's master seed plus
  the emitter's stable name/id — not one RNG shared across emitters (or
  threads), since whichever one happens to consume the next value first is
  a function of scheduling, not the seed.
- **Stability** (§13): a newly spawned particle can appear coincident with
  another particle or a collider surface — already covered by the
  softening length (§13.2) and gradual penetration correction (§13.4)
  designed for exactly this kind of near-coincidence, no special spawn-time
  handling needed.

## 15. Testing & Validation Strategy

Physics bugs are unusually cheap to catch here, for two reasons already
decided elsewhere in this doc: determinism (§11) holds exactly, so a given
scenario either produces byte-identical results or a real behavior change
happened — no flaky in-between to account for — and several forces and
collision responses have known closed-form solutions to check against,
rather than needing to eyeball a render. This section is deliberately
lean for a solo project: no CI pipeline, no coverage targets, just the
specific tests that catch real physics bugs cheaply.

### 15.1 Analytic tests

The highest-value, cheapest tests: run a short scenario and compare
against a known closed-form result, with a tolerance appropriate to the
integrator's accuracy (§13.1).
- **Harmonic oscillator**: a single spring-mass system's period should
  converge toward `2π√(m/k)` as `dt` shrinks.
- **Projectile motion**: uniform gravity only, apex height and range
  against `v²sin²θ/2g` and `v²sin2θ/g`.
- **Two-body circular orbit**: N-body gravity (§5.2) between two
  particles — orbital radius should stay stable over many periods, which
  also makes it a good integrator-drift regression check.
- **Bounce apex ratio**: using the §12.6 ball-bounce fixture directly,
  successive bounce heights should follow `h_n = h_0 · e^(2n)` for
  restitution `e` (§12.5) — the cheapest possible test of collision
  response.
- **Energy/momentum conservation**: promotes the runtime diagnostic
  already required in §11/§13.5 from *logged* to *asserted* — a closed
  spring-only system without damping should conserve energy within a
  bounded tolerance, not drift monotonically.

### 15.2 Golden-file regression tests

A small handful of representative scenarios (the flag, §7.3; the ball
bounce, §12.6; an N-body config; an emitter-heavy scenario, §14.1) run for
a fixed short duration, sampling a compact set of values (positions/
velocities of a few named particles at a few sampled times) into a plain,
diffable text format — deliberately *not* reusing the production Arrow IPC
recording (§9.2) for this, since a golden test wants a minimal, stable
artifact decoupled from that format's own evolution. Compare against a
checked-in reference. A failing golden test means either a real bug or an
intentional behavior change; regenerating the reference is a deliberate,
reviewed action, never automatic.

### 15.3 Component tests

Ordinary unit tests for the pieces most likely to have localized bugs: the
expression parser (§4.1 — parsing, evaluation, and the parse-time
scalar/vector type-checking already required there), YAML schema
validation (§4.2 — malformed input produces the expected error), and
force/collision geometry in isolation (spring force magnitude for known
inputs, sphere–plane/sphere–box intersection correctness, §12.4). These
don't need a running simulation at all, just JUnit 5 (already wired into
the build) against individual functions.

### 15.4 When this gets built

Tests start in **Phase 2**, alongside the integrator and forces they're
testing — not deferred to a later "polish" pass. A physics engine without
tests from the start accumulates bugs faster than it can be trusted.
Golden-file infrastructure can start as soon as there's a deterministic
run to sample, using the simple text format above; it doesn't need to
wait for §9.2's real recording format to exist.

## 16. Open Questions (to resolve while iterating on this doc)

- **§10.3 UI implementation approach**: whether the outliner/inspector/
  per-object panels are built as part of the three.js viewer page itself
  or as a separate control-panel layer (e.g. a small UI library
  purpose-built for exactly this kind of live-scene-tree/property-panel
  need) is unresolved — affects how much of §10.3 is "more JS in the
  existing viewer" vs. a new piece of the stack.
