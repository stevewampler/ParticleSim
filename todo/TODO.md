# ParticleSim — Implementation TODO

Status: not started — tracks build progress against `todo/requirements.md`.
This file is the checklist; `requirements.md` stays the design source of
truth. Don't duplicate design detail here — reference the section number
and link back.

Ordered by implementation dependency (each phase needs the ones above it).
`[stretch]` items match the tag in `requirements.md` and are intentionally
deferred past a working core.

**Guiding principles behind this order** (resolved from what was an open
question in `requirements.md` §16 — see conversation history for the full
reasoning):
- **Physics before authoring surfaces.** The expression parser and YAML
  front-end are built *after* the physics model has stabilized (Phase 7),
  not before — building them first risks designing both against a model
  that's still moving. This is cheaper than it sounds: the Kotlin DSL
  supports every expression-capable field from Phase 1 via native lambdas,
  which need no parser at all, so nothing is blocked on YAML existing —
  YAML just becomes a second, equally-capable front-end once the full set
  of expression-capable fields is actually known (§4.1, §4.3, §4.4).
- **A debug renderer early, not last.** For a physics simulator, watching
  it is the primary debugging tool — Phase 3 stands up just enough of the
  state stream to draw dots/lines/wireframe, long before the real
  interactive loop, camera, or opt-in renderer system exist.
- **Spatial partitioning moves to Phase 5**, sitting with collision — its
  first real consumer — rather than the general execution-engine phase.

## Phase 0 — Project setup
- [x] Choose & scaffold build tool (Gradle + Kotlin DSL)
- [x] `src/main/kotlin`, `src/test/kotlin` layout
- [x] IntelliJ Gradle import (supersedes the current bare `.iml`) — open
      the project in IntelliJ and let it detect/import `build.gradle.kts`

## Phase 1 — Core model & Kotlin DSL foundation
- [x] Particle model: position/velocity/acceleration/mass, radius,
      spawnTime/lifetime (§3) — struct-of-arrays backing behind a
      `ParticleStore` abstraction, stable-id references everywhere
      (never object references), id→slot free-list for spawn/destroy
      (§9.3). `particlesim.core`: `Vector3`, `ScalarExpr` (constant vs.
      function-of-time, ahead of Phase 7's parser), `GrowableDoubleArray`,
      `ParticleStore`. Acceleration is stored but treated as a per-step
      scratch buffer (recomputed from force/mass), not checkpoint state.
- [x] Groups as a first-class runtime concept (§4.2) — `particlesim.core.Groups`,
      id-keyed both directions (group→members, id→groups) so destroy-time
      cleanup can't leak stale membership onto a recycled slot.
- [x] Kotlin DSL builders (§4.3) — `particlesim.dsl`: `simulation { }`,
      `particles.single { }` / `particles.grid(rows, cols, spacing) { row, col -> }`,
      `.group("name")` chaining. Literal and lambda mass/radius/lifetime
      both supported, as function-call style (`mass(2.5)`, `mass { t -> ... }`)
      rather than `=` assignment — Kotlin has no implicit literal→wrapper-type
      conversion, so assignment syntax would need property-delegate overloads;
      deferred until a real scene surfaces an actual ergonomics complaint.
- [x] Component tests: particle store spawn/destroy slot reuse, id
      stability (§15.3) — `ParticleStoreTest`, `GroupsTest` (incl. the
      slot-recycling/group-membership-leak scenario), `DslTest`, `Vector3Test`.
      20 tests, `./gradlew test` green.

## Phase 2 — Physics core
- [x] Spring/damper with asymmetric stiffness/damping (§5.1) —
      `particlesim.physics.Spring`/`Damper`, direction-dependent
      stiffness/damping, force-magnitude component tests in
      `ForceComponentTest`.
- [~] Field forces: uniform gravity, wind, drag, N-body gravity (§5.2) —
      `UniformGravity`, `Drag` (linear + quadratic), `NBodyGravity` done.
      **Wind is not implemented**: §5.2 requires direction/strength to be
      expression-capable as functions of *time and position*, but
      `ScalarExpr` (Phase 1) is time-only. Needs a position-aware
      `VectorExpr` that doesn't exist yet — deferred to Phase 4, where
      surfaces are wind's first real consumer anyway, rather than building
      it speculatively now.
- [ ] Breakable forces, asymmetric break thresholds, deterministic
      end-of-step batch break ordering (§5.4) — not started; second pass.
- [x] Constraints: fixed position/velocity/force — only position/velocity
      count as infinite mass in collisions, fixed-force does not (§6) —
      `Constraint` (two-stage: velocity-then-position, matching semi-
      implicit Euler's own update order), `FixedPosition`, `FixedVelocity`.
      "Fixed force" is implemented as `ConstantForce` in the accumulation
      pass instead of a third constraint mechanism, since it never pins
      state — it's just another additive force term. Collision's infinite-
      mass treatment of position/velocity constraints is Phase 5 scope,
      not yet wired (nothing to wire it to before collision exists).
- [x] Semi-implicit Euler integrator, force accumulation designed around
      fixed-chunk accumulator arrays from the start even before
      multi-threading is turned on (§9.3, §11) — `Integrator`,
      `ChunkAccumulator`, `Force.accumulate(..., chunkIndex, chunkCount)`.
      Each force strides its *own* work items (group members / pairs)
      across chunks, not the force list itself — chunking by force
      declaration would leave N-body gravity unparallelized. A single
      `Spring`/`Damper` (one pair = one atomic work item) always
      contributes on `chunkIndex == 0`; **Phase 4's auto-generated mesh
      springs must be one `Force` striding its own pair list like
      `NBodyGravity`, not hundreds of individual `Spring` instances that
      would all collide on chunk 0** — noted here so it's visible before
      surfaces get built, not discovered after.
- [~] Stability safeguards (§13.1, §13.2): mass guard (constant
      non-positive mass rejected at creation; dynamic non-positive mass
      clamped to `MASS_EPSILON` + warned at every re-evaluation; NaN/
      Infinity from either always throws) — done, `MassGuardTest`.
      Per-force-type softening — done (`NBodyGravity.softening`,
      `Spring`/`Damper`'s `minLength`, independent defaults). NaN/Inf
      detection on position/velocity after each step — done
      (`BlowUpException`, `BlowUpTest`), but **only names the particle,
      not the force** that caused it — full field-path attribution
      (e.g. `flag.mass`) is only in place for mass, since expression
      evaluation is the one place a field is still individually
      identifiable before forces get summed into one accumulator.
      Per-force attribution for position/velocity blow-ups is a follow-up,
      not done.
- [x] Energy/momentum diagnostics — core, not stretch (§11, §13.5) —
      `Diagnostics.kineticEnergy`/`momentum`; potential energy is
      force-specific (`Spring.potentialEnergy`), not a generic `Force` API,
      since not every force has one (drag is dissipative by design).
- [x] Analytic tests: harmonic oscillator period, projectile apex/range,
      two-body circular orbit stability, energy-conservation assertion
      (§15.1) — all four passing (`HarmonicOscillatorTest`,
      `ProjectileMotionTest`, `TwoBodyOrbitTest`, `EnergyConservationTest`).
- [ ] Golden-file regression harness: plain-text sampled-state format,
      checked-in references, deliberate-regeneration workflow (§15.2) —
      not started. §15.2's own scenario list (flag, ball bounce) is Phase
      4/5 material anyway; when this is built, start with an N-body
      scenario since that's already exercisable now, and add the others
      as their phases land rather than treating this as one all-at-once
      deliverable.

## Phase 3 — Debug rendering
- [ ] Minimal one-way WebSocket state stream: positions (and basic
      force/collider data) only — no camera pose, events, or bidirectional
      input yet; the full contract is Phase 8's job (§9.1, partial)
- [ ] Debug-render override in the web viewer (`--render-all`): every
      particle as a dot, every pairwise force as a line, every collider as
      wireframe, ignoring the (not-yet-built) opt-in renderer declarations
      (§10.2)

## Phase 4 — Surfaces
- [ ] Triangulated surfaces, auto-generated structural springs (§7.1)
- [ ] Wind pressure on triangles — two-sided, consistent mesh winding
      (§7.2)
- [ ] Flag worked example running end-to-end, visually checked via
      Phase 3's debug renderer, added as a golden-file scenario (§7.3,
      §15.2)

## Phase 5 — Collision
- [ ] Shared spatial partitioning (sized to collision granularity) — its
      first real consumer, not the general execution engine (§9.3)
- [ ] Colliders: plane, box, sphere, expression-capable moving position
      (§12.2)
- [ ] Collision groups & filtering (§12.3)
- [ ] Broad/narrow phase detection (§12.4)
- [ ] Response: restitution, asymmetric damping, moving-collider velocity
      via finite difference of position (§12.5)
- [ ] Resting contact: velocity clamping every step, sleep after
      `restDuration`, wake propagation via collision/springs/drag (§12.7)
- [ ] Ball-bouncing worked example, visually checked via Phase 3's debug
      renderer (§12.6)
- [ ] Analytic test: bounce apex ratio vs. `e²` using the §12.6 fixture,
      component tests for sphere-plane/sphere-box intersection (§15.1,
      §15.3)

## Phase 6 — Particle lifecycle
- [ ] Emitters: rate + uniform-box/sphere/point-spread distributions,
      particle budget cap + policy, independent per-emitter RNG sub-stream,
      added as a golden-file scenario (§14.1, §15.2)
- [ ] Destruction: lifetime, expression, collision-triggered (§14.2)
- [ ] Cleanup semantics on destroy (§14.3)

## Phase 7 — Expression language & YAML front-end
- [ ] Hand-rolled expression parser — incl. `noise()` as seeded
      deterministic gradient noise, scalar/vector type-checking at parse
      time (§4.1). The full set of expression-capable fields is known by
      now from Phases 2–6, rather than guessed at upfront.
- [ ] Wire the parser into every expression-capable field already built
      via Kotlin lambdas in Phases 2–6, bringing YAML to full parity with
      the Kotlin DSL (§4.1, §4.3, §4.4)
- [ ] YAML front-end + schema validation, incl. version field, zero-match
      selector warning, unknown-name load errors, load-time rejection of
      statically-checkable bad values (e.g. a literal negative mass) (§4.2)
- [ ] Component tests: expression parser (incl. type-checking), YAML
      schema validation error cases (§15.3)

## Phase 8 — Full execution engine
- [ ] Real-time interactive loop: full state stream contract (camera pose,
      events), bidirectional input, wall-clock pacing policy (drop frames,
      never coarsen physics) — upgrades Phase 3's bare-bones stream (§9.1)
- [ ] Batch/record mode: sharded Arrow IPC File format, per-frame
      particle-id column, format version field (§9.2)
- [ ] Checkpointing: full-state snapshot at each shard boundary (group
      membership, broken connections, emitter accumulator+RNG state,
      t/step index), resume-from-checkpoint on crash (§9.5, §13.2)
- [ ] Multi-threaded force accumulation: turn on the fixed-chunk reduction
      designed back in Phase 2 — fixed logical chunk count, per-chunk
      private accumulators, fixed chunk-index merge order, deterministic
      across machines/thread counts, not just reruns (§9.3)
- [ ] Interactive particle drag, step-index-stamped drag targets for
      exact replay (viewer input → engine) (§9.4)
- [ ] `[stretch]` Parquet export (post-hoc conversion from Arrow shards,
      for pandas/Spark-style tooling) (§9.2)

## Phase 9 — Full visualization
- [ ] Camera: scripted (engine-evaluated) + manual (viewer-local) (§10.1)
- [ ] Renderers: particle/surface (dot/sphere/mesh) — the real opt-in
      system; Phase 3's debug-render override stays available as a
      permanent fallback alongside it, not replaced (§10.2)
- [ ] Renderers: force (arrows for fields, lines w/ colorblind-safe
      breakProximity gradient for springs/dampers) (§10.2)
- [ ] Web viewer (WebGL/three.js) + binary WebSocket protocol, full
      orbit/picking/camera controls — sole first-class viewer, upgrading
      from Phase 3's bare-bones renderer (§9.1, §10)

## Docs (ongoing, not a phase)
- [ ] Keep `todo/requirements.md` current as design decisions change
- [x] `docs/manual.md` stub created
- [ ] Fill in real manual content once there's a runnable, visualizable
      scenario (Phase 3 onward); grow it alongside real, tested examples
      only

## Deferred `[stretch]` (see requirements.md for each)
- [ ] Native viewer (JavaFX/LWJGL) — cut in favor of the web viewer as
      sole first-class target (§9.1, §10); reuses the same WebSocket
      protocol if ever built
- [ ] Implicit integration (§13.1)
- [ ] Friction — static/kinetic (§12.5)
- [ ] Continuous collision detection (§12.4)
- [ ] Particle-vs-surface collision, surface self-collision (§12.4)
- [ ] Surface-vertex destruction / mesh repair (§14.3)
- [ ] GPU compute (§9.3)
- [ ] Interactive delete (§9.4, §14.2)
- [ ] Playback-fork-to-live: resume from nearest checkpoint, deterministic
      fast-forward to the exact target frame, then hand off to live input
      (§9.4, §9.5)
- [ ] Export Kotlin-authored scene to YAML (§4.3)
