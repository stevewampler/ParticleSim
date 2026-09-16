# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

The Kotlin 2.0.20 compiler can't yet target JVM 23 bytecode, so
`build.gradle.kts` pins `jvmToolchain(22)` rather than the JDK 23 also
installed on this machine. An earlier attempt kept the toolchain at 23 and
only overrode Kotlin's `compilerOptions.jvmTarget` to 22 — that compiled
fine until real Kotlin source existed, at which point Gradle's
auto-generated `compileJava` task (still targeting 23) and `compileKotlin`
(targeting 22) started disagreeing and failing the build. Pinning the
toolchain itself avoids the split. Don't revert to the split form without
checking whether the Kotlin plugin has since added JVM 23 support.

Progress against the design is tracked in **`todo/TODO.md`** (living
checklist, phased by implementation dependency) — check it before assuming
what's implemented. `docs/manual.md` is a stub; it should only ever
describe behavior that's actually implemented and tested, never aspirational
design from the requirements doc.

The phase order is deliberately **physics-first**: the expression parser
and YAML front-end come after the physics model (Phase 7, not Phase 1),
since the Kotlin DSL already covers every expression-capable field via
native lambdas with no parser needed — building YAML/expression-parsing
early would mean designing both against a model that hasn't stabilized
yet. A bare debug renderer (Phase 3) comes right after the physics core,
well before the real interactive loop or renderer system, so there's
visual feedback for everything built afterward. See `todo/TODO.md`'s
header note for the full reasoning before resequencing anything.

Analytic tests and the golden-file regression harness start in Phase 2,
alongside the forces/integrator they cover — not deferred to a later pass.
Component tests, by contrast, land with whatever they cover as soon as it
exists (Phase 1's `ParticleStore`/`Groups`/DSL tests are already in place;
see `todo/requirements.md` §15 for the specific analytic tests, golden-file
approach, and component tests expected). Don't add a force, constraint, or
collision behavior without its corresponding analytic or component test
from §15.1/§15.3 where one is specified.

## Architecture reference

The full design is captured in **`todo/requirements.md`** — read it before
implementing anything, since it's the only source of intended architecture
right now and is under active iteration with the user (do not treat it as
final/frozen). Key decisions already locked in there:

- **Scenes are authored in Kotlin only.** A simulation (particles, surfaces,
  forces, constraints, colliders, time/run settings) is built with the
  type-safe Kotlin DSL (§4.3) or a hand-written builder in
  `particlesim.examples` — there is no YAML scene format. A YAML front-end
  existed for a while (`todo/TODO.md`'s Phase 7 and its "second pass"
  section, both now marked superseded) and was deliberately removed in
  favor of a single authoring surface; don't reintroduce a declarative
  scene-data format without discussing it first. SnakeYAML is still a
  dependency, but only for two things unrelated to scene authoring: parsing
  the viewer's WebSocket control messages and the checkpoint's `.yaml`
  metadata sidecar (§9.5).
- **One shared expression language**: any expression-capable field
  (particle mass, force magnitude/direction, constraint values, collider
  position, and any field the viewer's live-editing panel accepts typed
  input for) takes either a literal or a small sandboxed math expression
  string (e.g. `"2.0 + 0.1 * sin(t)"`) evaluated by a single shared engine —
  not a general scripting language. The Kotlin DSL accepts a native lambda
  in the same spots instead of an expression string. Keep new
  expression-capable fields consistent with this one grammar rather than
  inventing per-feature parsing.
- **Decoupled simulation/visualization**: the physics engine exposes state
  through a stable interface (WebSocket, binary framing) that the web
  viewer (WebGL/three.js) consumes — the sole first-class viewer; a native
  JavaFX/LWJGL viewer was cut in favor of it and is `[stretch]`, kept
  possible only by not baking viewer-specific assumptions into the engine.
  Two execution modes share this contract: real-time interactive stepping, and
  headless batch record-to-disk with separate playback. Per-frame state
  carries continuous data (particle/surface state, computed camera pose,
  §10.1) plus discrete events (e.g. force breaks, §5.4) on one channel. In
  interactive mode the interface is **bidirectional**: the viewer sends
  input back (currently just drag targets, §9.4) — the only case where the
  engine consumes viewer input rather than just producing state. Manual
  camera control is resolved viewer-side only and never crosses this
  interface, since it can't affect the physics.
- **Checkpointing is a distinct mechanism from playback recording** (§9.5):
  recording (§9.2) is a rendering-oriented time series; a checkpoint is a
  full resumable state snapshot (particle state, live group membership,
  broken connections, emitter accumulator/RNG state), written at each
  recording shard boundary. It's what makes both crash-resume for long
  batch runs (§13.2) and forking a live session off a recorded frame
  (§9.4, `[stretch]`) possible — the latter relies on determinism (above)
  holding exactly, since a fork fast-forwards from the nearest checkpoint
  rather than needing one at every frame.
- **Sleeping particles are not the same as constrained ones** (§12.7 vs.
  §6): a particle at rest on a collider is temporarily excluded from
  force/integration by the engine as an optimization, and wakes itself
  automatically when disturbed (by collision, a connected spring, or
  interactive drag). A fixed-position/velocity constraint is an authored,
  permanent guarantee. Don't conflate the two internally even though both
  mean "this particle doesn't move right now."
- **Moving colliders get velocity via finite difference of position, not
  a derivative added to the expression engine** (§12.5): a collider's
  position is already expression-capable (§12.2), and its instantaneous
  velocity for collision response is just `(pos_now - pos_prev) / dt`.
  Don't add symbolic/automatic differentiation to the expression parser
  (§4.1) for this — it was deliberately rejected in favor of reusing the
  same discrete-stepping numerics already used everywhere else.
- **Groups as the universal selector**: particles are targeted by
  forces, constraints, and collision rules through named groups (tag/id
  selectors), not by listing individual particles — reuse this mechanism
  rather than adding a parallel targeting scheme for new features. The same
  reuse principle applies elsewhere: interactive particle dragging (§9.4)
  is implemented as a live-driven fixed-position constraint (§6), not a
  separate mechanism.
- **Shared spatial partitioning**: N-body gravity and collision broad-phase
  are meant to reuse one spatial index rather than each maintaining their
  own.
- **Particle count is dynamic, not fixed at start**: emitters can spawn
  particles and several triggers can destroy them (§14) — internal
  structures (spatial index, recording format) must support insert/remove
  over a run's lifetime, not just a static N declared upfront. Destroying a
  particle cleans up any forces/constraints referencing it automatically,
  the same cleanup already required when a spring breaks (§5.4) — reuse
  that path rather than adding a second cleanup mechanism.
- **Particles are struct-of-arrays internally, referenced only by stable
  id** (§9.3): physics-hot fields (position, velocity, acceleration, mass,
  radius) live in parallel primitive arrays behind a `ParticleStore`-type
  abstraction, not as one object per particle — that's what makes the
  large-N targets in this doc reachable on the JVM. Forces, constraints,
  groups, and renderers must only ever reference particles by `id`, never
  by object reference, so this storage detail stays contained. Sparse
  per-particle data (tags, renderer overrides) doesn't need this treatment
  — an ordinary id-keyed map is fine for anything not iterated every step.
- **Determinism holds even under multi-threading** (§9.3, §11) — not
  relaxed for parallel runs. Force accumulation uses a fixed number of
  logical chunks (independent of actual thread/core count), each with its
  own private per-particle accumulator array, merged in fixed chunk-index
  order once every chunk finishes. Never reduce into a shared accumulator
  from multiple threads directly — floating-point addition isn't
  associative, so the result would depend on scheduling. Each emitter
  (§14.1) similarly needs its own independently-seeded RNG sub-stream, not
  a stream shared across emitters/threads.

Section numbers in `todo/requirements.md` are cross-referenced heavily
within the doc itself (e.g. constraints reference how they behave in
collision response) — keep those references intact when editing it.
