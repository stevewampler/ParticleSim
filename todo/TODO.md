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
- [x] Field forces: uniform gravity, wind, drag, N-body gravity (§5.2) —
      `UniformGravity`, `Drag` (linear + quadratic), `NBodyGravity` done in
      Phase 2. Wind landed in Phase 4 (`particlesim.physics.Wind`), keyed
      on triangles rather than particles directly, since surfaces (§7.2)
      turned out to be its only real consumer. **Correction to this
      bullet's original note**: it assumed wind was blocked on a
      position-aware expression type before building `VectorExpr` (Phase
      4) and seeing what §7.3's flag actually needed — turned out wind
      only needs to vary in *time* (§5.2 marks position-dependence
      optional, "and/or position"), so plain `VectorExpr` (time-only,
      mirroring `ScalarExpr`) was enough. Spatially-varying wind (gusts
      that differ across a sheet) is still unbuilt.
- [x] Breakable forces, asymmetric break thresholds, deterministic
      end-of-step batch break ordering (§5.4) — `Breakable` interface,
      implemented by `Spring` (threshold = displacement from `restLength`,
      matching its existing extension/compression split) and `Damper`
      (threshold = relative-velocity magnitude, since a damper has no rest
      length to measure a displacement against — documented in its KDoc as
      a deliberate reading of §5.4's "distance or force magnitude" phrasing).
      `Integrator.step` now returns `StepResult(brokenForces)`: breaks are
      checked once, against the same pre-integration state the forces were
      accumulated from, in fixed forces-list order — deterministic
      regardless of how many exceed threshold in one step. The integrator
      stays stateless; the caller drops broken forces from its own active
      list before the next `step` call, so a broken connection still
      applies its force the step it broke (never retroactively) and stops
      contributing starting the next one. `BreakableForceTest`.
      Discrete break *events* on a per-frame state stream, and the
      `breakProximity` renderer warning, are Phase 8/9 (no stream/viewer
      exists yet).
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
- [x] Golden-file regression harness: plain-text sampled-state format,
      checked-in references, deliberate-regeneration workflow (§15.2) —
      `particlesim.golden.GoldenFile` (test-only, `src/test/kotlin`, not
      shipped with the engine — a golden test deliberately doesn't reuse
      the production recording format, §9.2): fixed-precision plain-text
      lines (`t=... label pos=(...) vel=(...)`), checked-in references
      under `src/test/resources/golden/`, `regenerate()` kept separate
      from `assertMatchesReference()` so overwriting a reference is never
      a side effect of running the suite. One scenario built —
      `NBodyGoldenTest`, a three-body config sampled at 4 times — since
      §15.2's other named scenarios (flag, ball bounce) need Phase 4/5 to
      exist first; add them as those phases land rather than treating this
      as one all-at-once deliverable. Confirmed bit-exact/reproducible
      across repeated runs.

## Phase 3 — Debug rendering
- [x] Minimal one-way WebSocket state stream: positions (and basic
      force/collider data) only — no camera pose, events, or bidirectional
      input yet; the full contract is Phase 8's job (§9.1, partial) —
      `particlesim.debug.DebugServer` (`org.java-websocket:Java-WebSocket`,
      the project's first runtime dependency — a single small jar, not a
      framework, chosen so Phase 8's bidirectional upgrade is an
      `onMessage` override on the same class, not a rewrite). JSON text
      frames for now, not §9.1's eventual compact binary framing — that's
      explicitly attributed to the *full* contract, which is Phase 8's
      job. Collider data isn't in the frame — colliders don't exist yet
      (Phase 5).
- [x] Debug-render override in the web viewer (`--render-all`): every
      particle as a dot, every pairwise force as a line, every collider as
      wireframe, ignoring the (not-yet-built) opt-in renderer declarations
      (§10.2) — `src/main/resources/particlesim/viewer.html`
      (three.js via CDN import map), served over `http://localhost` by
      `ViewerHttpServer` (JDK's built-in `HttpServer`, no dependency) —
      not `file://`, which blocks ES module `<script>` tags in Chrome.
      `particlesim.debug.DebugRenderer` ties both servers together;
      `DebugRendererDemo` (`./gradlew run`) is a runnable example — a
      pinned chain of spring-connected particles swinging under gravity.
      **Colliders and surfaces aren't drawn** — neither exists yet
      (Phase 4/5); those branches of `--render-all` land with the things
      they draw, not stubbed out now. Surfaces landed with Phase 4/9/10's
      mesh renderer; **colliders landed later, alongside particle-vs-
      particle collision below** (Phase 5) — see that item's own note.
      Verified: `DebugFrame`'s serialization is unit-tested; the running
      demo was confirmed end-to-end from the server side (HTTP page
      serves, a raw WebSocket client receives well-formed frames showing
      the anchored particle staying fixed while the rest of the chain
      moves under gravity/spring forces, including out-of-plane z motion
      at the free end — added specifically so a y/z axis mixup in the
      viewer couldn't hide behind an all-XY test scene). Visually
      confirmed in-browser by the user: dots and connecting lines render
      and swing correctly at `http://localhost:8888`.

## Phase 4 — Surfaces
- [x] Triangulated surfaces, auto-generated structural springs (§7.1) —
      grid-only mesh generation (`particlesim.surface.Grid`/`Triangle`;
      arbitrary/general mesh triangulation is out of scope, matching
      §7.3's flag being the only shape this project targets). Structural,
      shear, and bend edge topology (`Grid.structuralEdges`/`shearEdges`/
      `bendEdges`) generated separately from `MeshSprings`, the `Force`
      that turns any edge list into one chunk-striding spring+damper
      system (`particlesim.physics.MeshSprings`) — combining spring and
      damper per edge (not two separate `Force`s, unlike single explicit
      `Spring`/`Damper`) so a broken edge can't have its damper keep
      damping a connection its spring stopped resisting. Per-edge
      breakability is self-managed inside `MeshSprings` (deactivate in
      place, not routed through `Integrator`'s external `Breakable`
      mechanism, which is for removing a whole `Force`) — see its KDoc for
      why in-place mutation is still safe once Phase 8 threads chunks.
      Winding-consistency and edge-topology-count component tests
      (`GridTest`).
- [x] Wind pressure on triangles — two-sided, consistent mesh winding
      (§7.2) — `particlesim.physics.Wind`, `F = density * area *
      (relativeWind · normal) * normal`; two-sidedness falls out of this
      formula being quadratic in the normal (flipping winding flips the
      normal twice, canceling out) rather than needing a special case —
      verified directly in `WindTest` by reversing a triangle's winding
      and asserting the same force. Added `particlesim.core.VectorExpr`
      (time-only, mirroring `ScalarExpr`) for wind's direction/strength —
      see the corrected note on Phase 2's field-forces bullet for why
      time-only turned out to be enough.
- [x] Flag worked example running end-to-end, visually checked via
      Phase 3's debug renderer, added as a golden-file scenario (§7.3,
      §15.2) — `particlesim.examples.buildFlag` (shared by the demo and
      its stability test), `./gradlew runFlagDemo`. `dt` (1e-3) chosen
      from §13.1's stability budget before tuning stiffness, not after;
      `FlagStabilityTest` runs it 4 sim-seconds and checks for both hard
      (`BlowUpException`) and soft (unbounded speed growth) instability.
      Also added `FixedPosition.atCurrentPositions` (pins each group
      member to wherever it individually already is, not one shared
      point) — needed because the pole edge is a column of particles at
      different heights, and useful generally beyond this scenario.
      `FlagGoldenTest` samples 3 named vertices (pinned pole-top, free
      corner, mid-sheet) at 4 sample times — not the whole ~100-particle
      sheet, matching `NBodyGoldenTest`'s established pattern (step-index
      sampling, not accumulated float time). Verified server-side
      (particle/connection counts and content confirmed via a raw
      WebSocket client) — **visual confirmation of the actual three.js
      render is the user's call**, same caveat as Phase 3's chain demo.
      Also added the `PairwiseForce`-vs-many-pairs-per-force resolution
      the Phase 2 TODO note anticipated: `DebugFrame`/`DebugRenderer` now
      take plain `(id, id)` pairs rather than `PairwiseForce` instances,
      so `MeshSprings.activeConnections()` (many pairs, one `Force`) and
      a single `Spring`/`Damper` (one pair) can both feed the same debug
      view.

## Phase 5 — Collision

First pass covers a single group colliding against static/moving colliders
— everything §12.6's ball-bounce example actually needs. Particle-vs-particle
collision, the spatial index, collision-group filtering, and full sleeping
are real §12 scope but nothing in this phase's worked example needs them, so
they're a deliberate second pass rather than oversights — see the unchecked
items below for exactly what's deferred and why.

- [x] Colliders: plane, box, sphere, expression-capable moving position
      (§12.2) — `particlesim.collision.Collider` (sealed) / `PlaneCollider`
      / `SphereCollider` / `BoxCollider`. Only position is expression-capable
      (`VectorExpr`), matching what §12.2 actually marks as such — a
      plane's normal, a sphere's radius, a box's half-extents are fixed at
      construction. Box is axis-aligned only; §12.2 allows "axis-aligned or
      oriented" so this satisfies the spec as written rather than being a
      narrowing — nothing else in the project has an orientation
      representation yet (no quaternion/matrix anywhere), so oriented boxes
      aren't reachable without adding one first.
- [x] Moving-collider velocity via finite difference of position, not
      symbolic differentiation of the expression (§12.5) — `Collider.advance(t,
      dt)` evaluates the position expression and diffs against the previous
      call's result; defaults to zero on a collider's first `advance` call
      even if `t != 0`, since there's no genuine previous step yet. Must be
      called exactly once per step (`CollisionSystem.resolve` does this
      itself, deduplicated by collider identity, before any contact queries).
- [x] Broad/narrow phase detection: sphere-plane, sphere-sphere,
      sphere-box, all against a single moving/static collider (§12.4) —
      `Collider.contact(sphereCenter, sphereRadius): Contact?`, one
      override per shape, pure geometry with no `ParticleStore` or
      simulation loop involved (so it's independently component-testable).
      "Broad phase" in the sense of skipping non-collidable particles is
      just "no radius set → skip" (§12.1) — an actual spatial-index broad
      phase is deferred (see below), since checking every particle in a
      group against N colliders is fine until a scenario has enough
      particles/colliders to need better than that.
- [x] Response: restitution + optional asymmetric compression/extension
      damping, particle-vs-collider only (§12.5) — `CollisionSystem`,
      `ParticleColliderRule`. **Collision is a separate call the caller
      makes after `Integrator.step()` completes, not a stage inside it** —
      `integrator.step(...)` then `collisions.resolve(...)`. Collision
      needs colliders, rules, and per-contact state the integrator has no
      business knowing about; keeping it a separate call keeps the
      integrator's existing contract (forces in, state out) untouched.
      Damping reuses §5.1's asymmetric-damper naming but isn't the same
      mechanism — it's a one-shot attenuation folded directly into the
      restitution impulse (`relVelAfter = restitution·|relVelBefore| /
      sqrt(1 + damping)`), not an `F·dt/mass` force impulse. This is
      deliberate: a force integrated over a single discrete detection step
      would vanish as `dt` shrinks, which isn't a coherent model for "how
      much this contact absorbs" — the spec doesn't give an exact formula
      for this term, so this is a documented design choice, not a
      transcription of §12.5. `c=0` reduces to plain restitution; larger
      `c` smoothly pulls the outgoing speed toward zero with no clamping
      needed (denominator is always ≥ 1). Verified against the "constrained
      particle behaves as infinite mass" equivalence advisor flagged: a
      collider *is* infinite mass by construction, so this pass's
      particle-vs-collider response already satisfies that check trivially
      — the real test of it lands with particle-vs-particle collision
      (second pass), where a fixed particle's *actual* mass must be
      overridden to infinite in the two-body impulse formula instead.
      The divisor went through two revisions after the user reported the
      ball-bounce demo looking like it "wasn't bouncing" — a legitimate
      bug report, not a demo-froze repeat of the port issue below. A plain
      `/(1 + damping)` divisor, checked against §12.6's own parameters
      (`compressionDamping: 3.0`), removes ~82% of the impact speed on the
      very first bounce — visually indistinguishable from "drops and
      stops," not the demo's own "watch it bounce, settle noticeably
      faster" description. Softened to `/sqrt(1 + damping)`: `compressionDamping:
      3.0` now halves the outgoing speed per bounce (several visible
      bounces, clearly faster than undamped restitution's ~20-bounce
      settle) and `extensionDamping: 0.2` stays close to a crisp full
      rebound, both empirically checked against the running demo the same
      way the flag's wind strength was tuned in Phase 4.
- [x] Resting contact: velocity clamping every step (§12.7, half of it) —
      global (not per-rule) `restVelocity`/`restPenetration` thresholds on
      `CollisionSystem`; when both are below threshold the normal velocity
      component is clamped straight to zero instead of having
      restitution/damping applied, so numerical noise can't "re-bounce" an
      already-resting particle. Sleeping (temporal hysteresis via
      `restDuration`, wake propagation through collision/springs/drag) is
      the other half of §12.7 and is **not** built — it's an optimization
      with three separate wake paths to get right, touches force
      accumulation and the integrator's particle iteration, and nothing in
      this phase's worked example needs it (velocity clamping alone is
      enough for the ball to actually come to rest). Deferred until a
      scenario has enough resting particles to need the optimization.
- [x] Penetration correction is gradual, not instantaneous (§13.4) —
      `ParticleColliderRule.correctionFactor` (default 0.2), applied as a
      fraction of the detected penetration per step rather than snapping
      the particle fully out on contact.
- [x] Ball-bouncing worked example, visually checked via Phase 3's debug
      renderer (§12.6) — `particlesim.examples.buildBallBounce`
      (`./gradlew runBallBounceDemo`): radius 0.2, gravity, a plane
      collider at y=0, restitution 0.7, compressionDamping 3.0,
      extensionDamping 0.2, matching §12.6's parameters. The demo resets
      the drop on a fixed cycle (well past the settle time) rather than
      running once — the ball settles to rest within a few seconds, easy
      to miss if the viewer tab is opened even slightly after `main`
      starts, which is exactly what made the damping-formula bug above
      hard to distinguish at first from just "opened the page too late."
- [x] Analytic test: bounce apex ratio vs. `e²` using the §12.6 fixture,
      component tests for sphere-plane/sphere-box intersection (§15.1,
      §15.3) — `BounceApexRatioTest` (dedicated zero-damping scenario, not
      the demo's parameters — damping intentionally pulls the ratio away
      from the pure `e^(2n)` curve, so testing it against the demo's own
      settings would be testing the wrong thing), `ColliderTest`.
      `BounceApexRatioTest` initially failed by ~8% at the second bounce
      with a *correct* simulation and a *wrong* test formula: `h_n =
      h_0·e^(2n)` implicitly assumes a point particle bouncing exactly at
      `y=0`, but a radius-0.2 ball's center bounces at `y=radius`. Fixed by
      applying the decay to the fall distance above that offset
      (`radius + (dropHeight - radius)·e^(2n)`) instead of to `dropHeight`
      directly — a reminder that "the physics looks wrong" and "the test's
      idealization doesn't match the fixture" are both worth checking
      before assuming which one is at fault.
- [x] Shared spatial partitioning for collision broad-phase (§9.3, §12.4) —
      `particlesim.collision.SpatialGrid`, a uniform grid keyed by cell,
      rebuilt fresh every call rather than incrementally maintained (cheap
      here since positions move every step regardless, and it gets §9.3's
      "must support insert/remove, not just a static t=0 build" for free —
      an emitter spawn or a destroy since the last build is just reflected
      in the next one). `ParticleCollisionSystem.candidatePairs` is its
      first real consumer, replacing what used to be a brute-force `i<j` /
      full-cross-product double loop, exactly the trigger this entry
      previously named ("revisit once a scenario's particle count makes
      O(n²) pairwise checks the actual bottleneck").

      **Scoped to collision only, deliberately not `NBodyGravity`** — a
      grid with cell size >= the largest possible contact distance is
      *exact* for collision (two spheres can only overlap within that
      distance, so nothing is ever missed), but gravity sums a contribution
      from every pair with no cutoff; feeding it only grid-neighbor pairs
      would silently drop every long-range term, changing the physics, not
      just speeding it up. That's why §9.3 itself calls out Barnes-Hut
      specifically for N-body gravity rather than a plain uniform grid —
      genuinely different algorithm, a separate future item if gravity at
      scale ever becomes the bottleneck, not bundled into this pass.

      **Correctness is proven bit-for-bit, not just "produces a plausible
      result"** — `SpatialGridRegressionTest` runs a mixed same-group/
      cross-group scene through `resolve()` for 200 steps and asserts the
      exact final positions/velocities match values captured from the
      pre-grid brute-force implementation. This matters because `respond()`
      mutates the store as it iterates, so *pair order* affects the result
      whenever one particle has two simultaneous contacts in the same step
      (floating-point addition isn't associative) — a scene sparse enough
      that every contact is isolated would pass even with a broken sort, so
      the regression scene deliberately packs 55 particles into a small
      box (confirmed via scratch instrumentation: dozens of steps end up
      with a particle in >= 2 simultaneous contacts) rather than using a
      more "realistic," spread-out one. Fixed by sorting each particle's
      grid-neighbor query back into ascending list-index order before
      emitting pairs, reproducing the old double loop's `(i, j)` visitation
      order exactly. `SpatialGridTest` covers the grid in isolation
      (exactness at cell boundaries, negative coordinates, diagonal
      neighbors, multiple occupants per cell).

      **N-scaling, measured with a scratch benchmark (not shipped — timing
      assertions are flaky)**: generating candidate pairs the old
      brute-force way vs. a full grid-accelerated `resolve()` call (pair
      generation + narrow phase + response together), same random scene,
      JIT-warmed:

      | N    | brute-force candidatePairs only | grid-accelerated resolve() |
      |------|----------------------------------|------------------------------|
      | 200  | ~250-320us                       | ~330-410us                   |
      | 500  | ~600-820us                       | ~450-550us                   |
      | 1000 | ~12-15ms                         | ~1.2ms                        |
      | 2000 | ~22-25ms                         | ~2.4-3.9ms                    |
      | 4000 | ~90-94ms                         | ~3.8-4.2ms                    |

      Grid loses at very small N (constant overhead: 27 `Cell` allocations
      and a hash lookup per neighbor query vs. brute force's trivial
      pairwise checks) but the crossover is around N=500-1000, and by
      N=4000 the grid is ~20x faster despite doing strictly more work per
      call (narrow phase + response, not just pair generation). Not
      revisited further (e.g. bit-packing cell coordinates into a `Long`
      key instead of a data-class `Cell`) since nothing currently needs
      more headroom than this.

      Caveat on the table itself: the benchmark scales the scene's box size
      by `sqrt(n/200)` to try to hold density constant, but that's the
      2D formula in a 3D scene — density actually *drops* ~4.4x from N=200
      to N=4000 (should have been `cbrt`). Brute force's column is
      unaffected (it enumerates every pair regardless of geometry), so the
      crossover point and the order-of-magnitude conclusion both survive,
      but the grid column at N=4000 benefits from having fewer particles
      per cell than a truly constant-density scene would show - the real
      win at high, constant density is probably somewhat smaller than
      ~20x, not larger.

      **Concrete consumer**: `particlesim.debug.SpatialGridDebugDemo`
      (`./gradlew runSpatialGridDemo`) — 2000 particles in a sealed,
      zero-gravity box, deliberately *not* `ParticleCollisionDebugDemo`
      scaled up (that demo's own doc comment already documents what a deep
      pile does under this engine's single-pass-per-step resolution; a
      sealed floating cloud stays permanently sparse relative to the box
      volume instead, while still paying the same per-step candidate-
      generation cost a pile would, since that cost doesn't depend on how
      many pairs actually overlap). Verified live in Chrome at N=2000: runs
      correctly for an extended period with no NaN/explosion artifacts,
      restart rebuilds the exact same deterministic layout, double-click
      delete correctly drops the live count (2000 -> 1999), no console
      errors.

      **What the live demo does *not* show, stated honestly**: it does not
      hold 60fps real-time on the machine it was verified on. Checked
      whether this was a broad-phase regression by running the completely
      unmodified `ParticleCollisionDebugDemo` (18 particles, brute-force
      scale) side by side — it shows the *same* ~75%-of-real-time "lag"
      stat. Whatever that fixed per-frame ceiling actually is (not
      profiled — orthogonal to this task), it exists identically at 18
      particles and at 2000, so it isn't the thing this work was meant to
      fix, and the benchmark table above is the real evidence for the
      broad-phase win, not the on-screen frame rate.
- [x] Particle-vs-particle collision (§12.4/§12.5, the two-body case) —
      `particlesim.collision.ParticleCollisionRule`/`ParticleCollisionSystem`.
      Candidate pairs now come from `SpatialGrid` (see the shared
      spatial-partitioning entry above) rather than the brute-force double
      loop this originally shipped with — `SurfaceCollisionSystem` is
      still brute-force, unchanged, since one ball against a few hundred
      trampoline triangles doesn't need the same treatment. The
      same-group case (`groupB` defaults to `groupA`, e.g. "debris with
      each other") uses the triangular `i<j` pairing `NBodyGravity` already
      established to avoid double-counting; a cross-group rule assumes the
      two groups are disjoint (documented, and pinned by a test showing
      the actual behavior when they overlap, rather than left silently
      unhandled). Real two-body impulse (`J = deltaRelVel/(invMassA +
      invMassB)`), reusing the same restitution/asymmetric-damping/
      rest-clamp formulas as `ParticleColliderRule`/`SurfaceCollisionSystem`.
      **"Constrained particles behave as infinite mass in collision
      response" (§12.5) is now implemented**, not just noted as
      outstanding: `Constraint` gained `pinnedIds(groups): Set<Int>`
      (default empty), overridden by `FixedPosition`/`FixedVelocity`/
      `DragConstraint`; `ParticleCollisionSystem.resolve` takes the step's
      live `constraints` list specifically to compute this, zeroing a
      pinned particle's inverse mass so it affects what it collides with
      but is never itself moved — verified by a test asserting a pinned
      particle's position/velocity are bit-identical before and after, and
      the free particle it hits bounces exactly like it hit an immovable
      wall. 13 new tests (`ParticleCollisionSystemTest`): elastic
      velocity-swap, tangential-untouched, compression damping, the
      rest-clamp's *relative*-velocity-not-either-particle's-own subtlety
      (same lesson `SurfaceCollisionSystemTest` already learned), the
      pinned-particle case, both-sides-pinned (no divide-by-zero),
      self-collision within one group, cross-group filtering, the
      documented overlapping-groups behavior, and two §15.1 analytic
      checks — momentum conservation (any restitution/damping) and, for a
      perfectly elastic/undamped unequal-mass case, kinetic energy
      conservation to 1e-9, the discriminating check that would catch a
      wrong `invMassSum` or a non-unit normal that the momentum test alone
      cannot.
      Demo: `ParticleCollisionDebugDemoKt` / `./gradlew
      runParticleCollisionDemo` — balls dropped one at a time (not all at
      once) into a four-walled pen, piling up on the floor. Getting the
      demo to actually look right (not just pass unit tests) took real
      iteration, documented in the demo's own doc comment: dropping all 18
      balls simultaneously from a packed stack caused deep, simultaneous
      multi-body overlaps that this project's single-pass-per-step
      resolution (§12.4's deliberately "simplest to implement" discrete
      detection, not an iterative solver) doesn't fully untangle in one
      pass, and with floor/ball friction still `[stretch]`, any resulting
      horizontal drift never decays — confirmed live via a disposable
      WebSocket script watching balls end up hundreds of meters out at a
      constant speed. Staggering the spawn reduced but didn't eliminate
      this (a new ball landing on a settled pile still nudges neighbors
      sideways with nothing to damp it), so four wall colliders pen the
      pile in — containing the demo rather than papering over a math bug,
      since every pairwise formula was independently verified correct.
      Final live check: 18 balls settled to the correct resting height
      (0.14996, matching radius 0.15) with speeds around 0.03-0.15 m/s and
      trending down, all within the walls.
      **Follow-up, from real user testing**: the demo shipped without two
      things that turned out to matter once someone actually watched it —
      pause didn't work (this demo's `DebugRenderer` had no `onTextMessage`
      handler at all, so a `TimeControlMessage` the client sent just went
      nowhere; fixed by wiring `TimeControl` in exactly like `FlagDebugDemo`
      already does) and the pen's walls were invisible (colliders have
      never had any renderer at all — Phase 3's own `--render-all` item
      above promised "every collider as wireframe" but that branch was
      never built, since nothing needed it until now). Closed the second
      gap for real, not just for this demo: `BinaryFrame` gained a
      collider section (kind-tagged: plane/sphere/box, §12.2's three
      primitives), `PlaneCollider.unitNormal` went public (matching
      `SphereCollider`/`BoxCollider`'s already-public shape fields) so the
      encoder can read it, and the viewer renders each as a pooled
      wireframe object plus a `colliders` global toggle alongside
      grid/axes — a plane's infinite extent is drawn as a finite quad
      whose half-size (`BinaryFrame.PLANE_RENDER_HALF_SIZE`) is sent over
      the wire rather than duplicated as a second hardcoded constant in
      the JS client. This is unconditional, not opt-in like every other
      §10.2 renderer — a `Collider` has no name-targeted renderer
      declaration to attach to, so any collider a demo passes just draws.
      3 new `BinaryFrameTest` round-trip cases (plane-with-render-size,
      sphere+box unnamed, empty-list default). Verified live end-to-end:
      a WebSocket script watching the real running demo confirmed the
      step counter freezes bit-exactly under pause and resumes correctly,
      and the decoded collider list matched all 5 real colliders (the
      floor plus 4 walls) with correct positions/normals.
      **Now also visually confirmed in-browser** (the user enabled Chrome
      automation for this session, closing the standing gap noted above):
      the floor + 4 walls render as a wireframe box around the ball pile;
      clicking pause froze `step` exactly (46592 held across a 2s wait,
      button flipped to "▶ play") and resume advanced it again; toggling
      the new "colliders" checkbox off/on actually hides/shows the
      wireframe, not just relabels it. First real in-browser check of any
      viewer feature this project has had — every prior Phase 9/10 item's
      "browser-verified" note up to now meant the *user* looked at it and
      reported back, not that this assistant directly observed the page.
- [x] Collision groups & filtering beyond a single rule's group/collider
      pairing (§12.3) — landed as part of the item above:
      `ParticleCollisionRule(groupA, groupB)` *is* the group-vs-group
      filtering mechanism (a rule declares exactly which two groups check
      against each other, same selector reuse as forces/constraints), not
      a separate feature built on top of it.

## Phase 6 — Particle lifecycle
- [x] Emitters: rate + uniform-box/sphere/point-spread distributions,
      particle budget cap + policy, independent per-emitter RNG sub-stream,
      added as a golden-file scenario (§14.1, §15.2) — `particlesim.lifecycle`:
      `VectorDistribution` (UniformBox/UniformSphere/PointWithSpread, uniform
      over solid angle within the spread cone, not uniform in angle — a wide
      cone shouldn't over-sample its own edge), `ScalarDistribution`
      (Constant/UniformRange — "point-with-spread" collapses to a plain 1D
      range, no separate scalar variant needed), `Emitter`. Spawn rate is a
      fractional-accumulator model (`accumulator += rate(t)·dt`, spawn once
      per whole unit crossed) so a rate far below `1/dt` still spawns at the
      right long-run average instead of rounding to zero every step. Each
      emitter's RNG sub-stream is seeded via a SplitMix64-style mix of the
      master seed and the emitter's name, not `masterSeed xor name.hashCode()`
      — plain XOR only changes the low 32 bits (`hashCode()`'s width), so two
      emitters in one run got seeds differing only in their low bits, and
      `Random` streams seeded that close can start out correlated — exactly
      the "different emitters, suspiciously similar sequences" bug
      determinism exists to rule out, just disguised as near-duplicates
      instead of caught as exact ones. `EmitterTest` asserts both directions
      (same name+seed → identical sequence; different name, same seed →
      different sequence), not just the second. Particle budget cap policies
      (`STOP`, `EVICT_OLDEST`) are per-emitter, not global — each emitter
      tracks its own spawned-and-still-alive ids (oldest first, for eviction
      order); live count is derived by pruning that list against
      `store.contains()` each `update()` call rather than requiring
      destruction to notify the emitter when it removes something — a
      cross-system contract that's easy to forget once and have the cap
      silently throttle emission to zero forever after. `STOP` clamps unspent
      accumulator budget while blocked so clearing the cap later resumes at
      the steady rate instead of releasing a backlog burst; the cap-hit
      warning is edge-triggered (fires once when newly at cap, not once per
      blocked/evicted spawn) to avoid spamming `onWarning` every step of a
      long-running full emitter.
- [x] Destruction: lifetime, expression, collision-triggered (§14.2) —
      `particlesim.lifecycle.DestructionSystem`, `DestroyCondition` (a native
      lambda predicate per group, ahead of Phase 7's expression parser, same
      stand-in already used for mass/radius/wind), `CollisionDestroyRule`
      (reuses Phase 5's `Collider.contact()` narrow-phase geometry, minus the
      physical response). Composed the same way Phase 5's collision
      resolution is — a separate call the caller makes, not folded into
      `Integrator.step()` or `DestructionSystem` owning the emitter loop
      itself. **Per-step order is `destroy → emit`, deliberately not
      `emit → destroy`**: a particle spawned this step needs to be
      integrated at least once before it's eligible for its own
      lifetime/collision check, otherwise a near-zero sampled lifetime, or a
      spawn position already touching a destroy-collider, kills it before it
      was ever simulated.
- [x] Cleanup semantics on destroy (§14.3) — `Groups.removeParticle` (Phase
      1) already covers every force/constraint that targets by group
      membership (`UniformGravity`, `NBodyGravity`, `MeshSprings`, `Wind`,
      `FixedPosition`/`FixedVelocity`, including `FixedPosition`'s
      per-particle-position map — it's only ever consulted while iterating
      *live* group members, so a destroyed id's stale map entry is simply
      never reached again, no explicit cleanup needed there). The one real
      gap is `PairwiseForce`s (`Spring`, `Damper`) that hold `idA`/`idB`
      directly, bypassing groups entirely — `DestructionResult.danglingForces`
      reports any of those referencing a destroyed id, mirroring
      `StepResult.brokenForces` exactly: the caller drops them from its own
      active force list before the next `Integrator.step` call. **Scoped to
      free (non-surface) particles, per spec** — nothing in
      `DestructionSystem` checks whether a destroyed id is a surface-mesh
      vertex, so a destroy rule whose group includes flag-cloth particles
      would leave `MeshSprings`/`Wind` holding a dangling id (throws on the
      next `accumulate`). Not guarded against: this phase's worked example
      never touches surfaces, and surface-vertex removal needs its own
      mesh-repair design that §14.3 already marks `[stretch]` — documented
      in `DestructionSystem`'s KDoc rather than silently assumed away.
- [x] Worked example + golden-file scenario (§14.1, §15.2) — a spark
      fountain, `particlesim.examples.buildSparks` (`./gradlew
      runSparksDemo`): one `Emitter` spraying upward in a cone
      (`PointWithSpread`) at a rate that pulses over time
      (`20 + 15·sin(0.5t)`) rather than staying constant, specifically to
      exercise "expression-capable... bursts, ramps" rather than just
      picking a constant. Sparks fall under gravity+drag and leave the
      simulation via whichever of two mechanisms comes first — lifetime
      expiry or ground contact (§14.2's own "a spark disappearing when it
      hits the ground" example) — plus a bounding-region `DestroyCondition`
      backstop for stray fast sparks, exercising all three §14.2 mechanisms
      together. `SparksGoldenTest` samples *live count + mean position/
      velocity* rather than named particles by id, unlike the flag/N-body
      goldens — which particles are still alive at a given sample time
      depends on randomly-drawn lifetimes and where each one landed, so a
      particle picked up front has no guarantee of surviving to the next
      sample; aggregate state is well-defined at every sample regardless of
      individual particle lifecycles, and still moves if spawn/destroy logic
      regresses. `SparksStabilityTest` runs 10s and checks the population
      stays at/under the emitter's cap and that destruction is actually
      firing (far more ids issued than are currently alive), separately from
      the golden file's short 1s window.
      One bug, self-caught before this ever reached a demo/golden file: the
      scenario's *first* draft spawned particles in a small sphere centered
      directly on the ground collider's position, so most sparks were born
      already overlapping the floor and got destroyed before ever visibly
      existing — golden output showed `alive=0` at the first sample. Fixed
      by spawning well clear of the floor (a real flight arc, not a
      coin-flip on spawn-position overlap) rather than by touching the
      destroy logic, since the destroy logic itself was correct — the
      scenario's own parameters were the bug. (The `destroy → emit`
      ordering above was designed in from the start, based on the
      architecture review before any lifecycle code was written — not a
      bug this scenario caught.)

## Phase 7 — Expression language & YAML front-end

Scoped narrowly on purpose: instead of schema coverage for every
force/constraint/collider/emitter type built across Phases 2–6, this phase
gets exactly one worked example — the flag (§7.3) — expressible in YAML, and
*proves* parity by loading it and running it through the flag's existing
golden-file test, asserting byte-identical output against the same checked-in
reference `buildFlag` already produces. That's a stronger proof than partial
schema breadth would be, and it's a deliberately reusable pattern: colliders,
emitters, destroy rules, breakable thresholds, other bulk-generation shapes,
and the N-body/ball-bounce/sparks scenarios all still need YAML coverage —
second pass, same framing as every other phase's worked-example-first scoping
this project has used since Phase 5.

- [x] Hand-rolled expression parser — incl. `noise()` as seeded
      deterministic gradient noise, scalar/vector type-checking at parse
      time (§4.1) — `particlesim.expr`: `Lexer`, `Parser` (recursive-descent;
      precedence loosest→tightest is `+`/`-` < `*`/`/` < unary `-` < `^`,
      with `^` right-associative and unary minus binding *looser* than `^`
      so `-2^2 = -4` not `4`), `Ast` (each node resolves its scalar/vector
      `type` and `isConstant` structurally as it's constructed — a
      scalar/vector mismatch throws immediately during parsing, never on
      first `evaluate`), `Noise` (1-3 argument value noise — the spec's
      "value or simplex, implementation's choice" hedge, chosen because
      value noise is far simpler to hand-roll correctly than gradient/
      simplex noise while still being a pure, deterministic, non-RNG
      function of its arguments; hashed lattice points via the same
      SplitMix64-style mixing Phase 6's `Emitter` uses for seed derivation,
      smoothstep-interpolated). `ExpressionParser.parseScalar`/`parseVector`
      bridge into the *exact* `ScalarExpr`/`VectorExpr` types every force/
      constraint/collider/emitter already consumes — not a parallel
      evaluation path — folding a `t`-independent result to `.Constant`
      (matching what a literal already gets) rather than always producing
      `.OfTime`.
      **Only `t` is a working built-in variable.** §4.1 also lists `dt` and
      "the entity's own position, velocity, id (where applicable)" —
      none of those are reachable without first widening `ScalarExpr`/
      `VectorExpr`'s `evaluate(t)` signature, which every force/collider/
      emitter built in Phases 2–6 consumes; that's a breaking change to the
      core evaluation contract with zero current consumers, not something
      to make partially/silently work here. `dt` is a recognized identifier
      that fails with a clear "not available yet" message (not a silent
      wrong value, and not indistinguishable from an actual typo);
      position/velocity/id aren't in the grammar at all yet. Camera scene
      queries (§4.1's `position(id)`, `centroid(group)`, etc.) are
      out of scope for the same reason one level up — there's no camera
      system yet (Phase 8) for them to query.
- [x] YAML front-end + schema validation, incl. version field, zero-match
      selector warning, unknown-name load errors, load-time rejection of
      statically-checkable bad values (e.g. a literal negative mass) (§4.2)
      — `particlesim.yaml.YamlLoader`, scoped to exactly what §7.3's flag
      needs: a particle grid (with `edge_groups` for "column 0 also joins
      group X," the flag's pole-edge pattern — a narrow, rectangular-grid-
      only mechanism, not a general selector), `gravity`/`mesh_springs`/
      `wind` forces, `fixed_position` (incl. `at_current_positions`).
      SnakeYAML only for text→generic-Map/List parsing; all schema
      validation and binding into the simulation model is hand-written, the
      same "own the sandbox boundary" choice already made for the
      expression parser rather than a data-binding library. **No tag/id/
      range selector language exists yet** — group membership in this
      schema comes only from a grid's own `name`/`edge_groups`, plus an
      optional top-level `groups:` list whose only purpose is making a
      group's "declared but currently unmatched" state distinguishable
      from "never declared anywhere," so the two required semantic checks
      have a real difference to key off: a name in `groups:` with zero
      members after loading → **warning**; a `group:` reference anywhere
      else to a name no declaration ever produced → load-time **error**. A
      real selector system would give the zero-match warning a more
      natural home; this is the narrowest schema that demonstrates both
      checks exactly as specified rather than conflating them. Gravity's
      `acceleration` is a literal-only `[x,y,z]` (mirroring
      `UniformGravity` itself not being expression-capable, a Phase 2
      decision this phase didn't reopen); `wind.velocity` and `mass` accept
      either a literal or an expression string, reusing
      `ExpressionParser`. A literal negative mass is rejected by letting
      `ParticleStore.create`'s existing validation throw (synchronously,
      during loading) rather than duplicating that rule a second time in
      the loader — one source of truth for "mass must be positive."
- [x] Parity proof — `particlesim.golden.FlagYamlParityTest` loads
      `src/test/resources/yaml/flag.yaml` (hand-written to match
      `buildFlag`'s exact parameters *and* force order — force
      accumulation order affects the bit pattern of a floating-point sum,
      so matching values alone wouldn't be enough), runs it through the
      same sampling `FlagGoldenTest` uses, and asserts it against the
      *same* checked-in `flag.golden.txt` — passed byte-identical on the
      first run. If this ever needs a separate reference file, the two
      front-ends have diverged.
- [x] Component tests: expression parser (incl. type-checking), YAML
      schema validation error cases (§15.3) — `ExpressionParserTest` (27
      tests: precedence/associativity incl. `2^3^2=512` and `-2^2=-4`,
      vector/scalar type errors at parse time, `noise()` determinism/
      range/continuity, constant-folding), `YamlLoaderTest` (14 tests:
      version field, both required semantic checks with a case
      distinguishing "declared-empty warns" from "undeclared errors,"
      malformed-expression-string and unknown-grid-name errors, literal
      negative mass rejection).
- [ ] **Second pass** (not this phase): wire every other expression-capable
      field (colliders' moving position, emitters' rate/distributions,
      destroy conditions, breakable thresholds, N-body/collision/
      resting-contact parameters) into the YAML schema; general
      bulk-generation shapes beyond a rectangular grid (uniform-random-in-
      volume, explicit particle lists, individual particle declarations);
      a real tag/id/range selector language (§4.2) to replace this phase's
      narrow name-based group model; YAML coverage for the ball-bounce and
      sparks scenarios, each getting their own golden-file parity proof
      the same way the flag did.

## Phase 8 — Full execution engine
- [x] Real-time interactive loop: full state stream contract (camera pose,
      events), bidirectional input, wall-clock pacing policy (drop frames,
      never coarsen physics) — upgrades Phase 3's bare-bones stream (§9.1).
      Fulfilled incrementally rather than in one pass, which is why this
      checkbox sat unchecked long after most of the pieces actually
      landed: camera pose (Phase 9's scripted-camera work), bidirectional
      input (§9.4's drag channel, §10.3's scene-control/time-control
      channels), and wall-clock pacing (every debug demo's own frame
      loop, §9.1) were already live. **The discrete-event channel
      ("events") was the one genuinely missing piece** — see the new
      entry immediately below for that work.
- [x] Discrete-event channel for the interactive stream (§9.1: "each
      frame of state carries continuous data... plus discrete events
      (e.g. force breaks, §5.4, and particle spawn/destroy, §14.2)") —
      the live viewer's wire protocol only ever carried continuous
      per-frame state; a force breaking or a particle being destroyed
      happened silently, indistinguishable from any other frame. Scoped
      to the *interactive* half only — the batch recording format's own
      discrete-event gap (§9.2, noted below under Batch/record mode)
      is separate and still open.
      **New `particlesim.debug.SimEvent`** (`ForceBreak(name)`,
      `ParticleDestroyed(particleId)`, `ParticleSpawned(particleId)`),
      reused directly for both encode and decode (no `Decoded*` twin,
      unlike `Collider` — an event carries no engine behavior to strip
      out, same reasoning `ArrowSample` already established). New
      `BinaryFrame` wire section: `eventCount * {kind byte, then either
      a name string or a particle id}`, appended after colliders.
      Un-batched at this layer — `encode` just serializes whatever list
      it's handed; the caller (every demo's own frame loop) is
      responsible for collecting one frame's worth of events across
      that frame's `stepsThisFrame` physics steps before broadcasting,
      the same way meshes/arrow samples already get computed once per
      frame from post-step state.
      **`Emitter.update` now returns `EmitResult(spawnedIds,
      evictedIds)`** instead of `Unit` — a small, additive signature
      change (no existing call site used the old return value). Also
      fixed a real latent bug surfaced while making this change: the
      `STOP`-cap branch did a bare `return` mid-`while`, which would
      have silently discarded any ids already spawned earlier in that
      same `update` call the moment the cap was hit - now returns
      `EmitResult(spawned, evicted)` so nothing collected so far is lost.
      `EmitterTest` gained cases for both this correctness fix and the
      `EVICT_OLDEST` spawn/evict correspondence, checking `store.contains`
      on every returned id rather than trusting the counts alone.
      **Two live consumers, chosen deliberately over touching a working
      demo's physics behavior** (see below): `SparksDebugDemo` produces
      spawn/destroy events *continuously* with no interaction needed —
      its loop already calls `destruction.resolve`/`emitter.update`
      every step, so wiring the channel in was purely additive.
      `DragDebugDemo`'s existing interactive-delete path (§14.2) now also
      emits a `ParticleDestroyed`, giving a second, user-triggered
      consumer and confirming the channel isn't tied to *how* a particle
      died.
      **`ForceBreak` was defined and wire-tested (`BinaryFrameTest`) but
      deliberately left unemitted by any live demo this pass** — no
      interactive demo set a finite `breakThreshold`, and no demo loop
      had ever captured `Integrator.step`'s returned `StepResult` at
      all, so §5.4's "caller must remove a broken force from its active
      list" contract had never actually been discharged anywhere. See
      the new entry below ("Breakable structural springs live in
      DragDebugDemo") for where this got closed out.
      **Client-side**: a new "events" section in the existing
      bottom-left control panel, a capped rolling log (last 20, newest
      first, `EVENT_LOG_MAX`) so a continuously-spawning demo like Sparks
      doesn't grow the log/DOM without bound. `frame.events` is this
      frame's fresh list only (server never resends past events, see
      `BinaryFrame`'s own doc comment) — appended, not replayed — so a
      paused frame (zero physics steps) correctly contributes zero new
      log lines instead of repeating the same entries every broadcast.
      Reset alongside the stats panel on reconnect and on restart.
      **Verified live in Chrome, checking correspondence not just
      appearance** (a log that prints plausible-looking ids while wired
      to the wrong list would look identical to a working one otherwise):
      on `SparksDebugDemo`, paused and stepped through several frames,
      confirming a "destroyed"/"spawned" pair appearing in the log
      exactly matched the particle count staying net-unchanged between
      those steps; on `DragDebugDemo`, double-clicked a chain link and
      confirmed the log's new `particle N destroyed` line matched the
      link that actually vanished (count dropped 12→11, chain visibly
      split into two pieces). No console errors on either demo.
- [x] Breakable structural springs live in `DragDebugDemo`, closing out
      `ForceBreak`'s deferred wiring above — user asked specifically to
      "see a force... approach, reach, and exceed its breaking point,"
      which meant landing three things together: a real finite
      threshold on a live demo, the break-proximity color renderer
      (§10.2, defined and unit-tested via `ColorRampTest` since Phase 9
      but never actually driven by a live demo before this), and
      finally discharging `Integrator.step`'s "remove a broken force
      from the active list" contract that no demo loop had ever done.
      **Why `DragDebugDemo` and not `FlagDebugDemo`** (which already has
      a `structuralBreakThreshold` param, left at its infinite default):
      Flag's structural springs use damping well *under* critical, and
      an earlier attempt there found that a sudden reposition — exactly
      what dragging does every step — rings/overshoots enough to trip a
      break threshold falsely; reverted rather than papering over it
      with an unrealistically loose threshold. `DragDebugDemo`'s chain
      is already damped *above* critical for exactly this reason, so the
      failure mode that sank the earlier attempt doesn't apply here.
      **Each `Spring` individually named** (`"link-0"`, `"link-1"`, ...,
      built by a small local `buildChain` helper shared between initial
      construction and restart) so a `ForceBreak` event names *which*
      link snapped. `breakThreshold` (0.5, chosen empirically live in
      Chrome rather than derived — loose enough that the chain's own
      resting sag under gravity never approaches it, tight enough that a
      deliberate drag reaches it quickly) applies to `Spring` only, not
      `Damper` — a damper's break threshold is a relative-velocity
      quantity, a different kind of "too far" than the stretch-distance
      snap the user asked to see.
      **Break handling lives inside the physics-step loop, not after
      it** — §5.4's break check is once-per-physics-step, so a spring
      breaking on, say, step 3 of a frame's 16 must stop contributing
      force for the remaining 13, not linger until the next broadcast.
      When a `Spring` breaks, its index-aligned `Damper` (same pair,
      same array index — `buildChain` builds both lists in lockstep)
      is removed too, the same "one physical connection, both forces go
      together" reasoning `DeleteParticle`'s `danglingForces` cleanup
      already established, reused rather than reinvented.
      **Verified live in Chrome, robustly**: resting chain stayed pure
      blue (proximity ≈ 0) across multiple restarts, confirming the
      threshold isn't so tight that ordinary hanging ever approaches it.
      Dragging a link (via `left_click_drag`, both a small ~70px and a
      large ~290px screen-space pull) reliably snapped one or both of
      its neighboring springs, each producing a correctly-named
      `ForceBreak` event (`"force 'link-6' broke"`, etc.) at the correct
      sim time, with `particles: 12` staying unchanged throughout
      (confirming force-break destroys a *connection*, not a particle —
      distinct from the destroy-event semantics verified separately
      above). One trial produced `link-6` and `link-5` breaking at two
      different timestamps a full second apart rather than
      simultaneously, which is only possible if each spring's
      break-proximity was genuinely being evaluated continuously and
      independently, not as a single coupled event.
      **What wasn't caught, and why, stated plainly rather than
      glossed over**: a screenshot of a spring mid-transition — visibly
      orange, stretched, but not yet broken — proved elusive through
      this tool's synthetic `left_click_drag`. Investigating why led to
      a real finding: `DragConstraint.applyPosition` is fully
      kinematic — `store.setPosition(particleId, currentTarget)` teleports
      the dragged particle to the current target every step, with no
      gradual catch-up — so a spring's stretch jumps to its near-final
      value within the first step or two after any `drag_move`, rather
      than ramping up smoothly the way a real spring pulling toward a
      *moving* target might suggest. A live human dragging with a
      physical mouse sends many incremental positions as the cursor
      glides, so the color visibly ramps blue→orange in that case; a
      single automated drag call effectively jumps straight from "rest"
      to "final drag position," leaving only a razor-thin, unpredictable
      window where the connection is stretched-but-intact - not
      reliably long enough to land a screenshot in. The mechanism itself
      isn't in question (blue-at-rest and the independently-timed
      breaks above already confirm continuous, correct evaluation, and
      `ColorRampTest` independently proves the interpolation math) —
      this is a limitation of scripted verification, not the feature,
      and is exactly what a real user dragging by hand will see work as
      intended.
- [~] Batch/record mode: sharded Arrow IPC File format, per-frame
      particle-id column, format version field (§9.2). **First sub-pass
      done** (recording only — no checkpoint/resume, no discrete-event
      channel yet); scoped narrowly to ball-bounce (static particle count)
      to prove the sharded-Arrow mechanism itself before a second sub-pass
      proves the dynamic-population/checkpoint pieces against sparks,
      which is the scenario that actually needs them.
      **Dependency spike first**: added `arrow-vector`/`arrow-memory-netty`
      18.1.0 and wrote a throwaway round-trip test *before* designing
      anything around it, since arrow-memory's off-heap allocator reaches
      into `java.nio` via reflection and JDK 16+'s strong encapsulation
      blocks that by default. Confirmed real failure
      (`InaccessibleObjectException`) on first run; fixed with
      `--add-opens=java.base/java.nio=ALL-UNNAMED` and
      `.../java.lang=ALL-UNNAMED`, applied to `tasks.test` and every
      `JavaExec` task in `build.gradle.kts` (a `val arrowAddOpens` shared
      between them) — the spike test itself was then deleted.
      **`particlesim.record`**: `RecordingSchema` (one Arrow record batch
      per frame, one row per live particle: `step`/`t`/`id`/px,py,pz/
      vx,vy,vz; `id` column present even though ball-bounce's population
      never changes, since positional identity across frames can't be
      relied on once particle count is dynamic elsewhere — §14).
      `RecordingWriter(directory, framesPerShard)`: rolls to a new
      `shard-NNNNN.arrow` file purely on frame count (never size or wall
      clock — the same boundary §9.5 will hang checkpoints off of), each
      shard's Arrow-file footer written only when the shard closes.
      `RecordingReader`: `readFrame(shardIndex, frameIndexInShard)` does
      true random access via the file's footer-based block index (loads
      one target batch without reading anything before it) — the
      documented reason §9.2 picked the *File* IPC variant over the
      streaming one, so it seemed worth actually proving rather than just
      building sequential-only `readAllFrames` and asserting it.
      **Format version**: embedded as Arrow schema-level metadata
      (`particlesim.format_version`, carried in every shard's own footer)
      rather than a separate sidecar file, since it's a property of the
      columnar schema itself; `RecordingReader` checks it on every shard
      open and rejects a mismatch before trying to interpret any column.
      **Crash-boundary property actually tested, not just asserted**:
      `RecordingTest` completes shard 0, writes a few frames into shard 1,
      then abandons the writer *without* calling `close()` (no `.end()` on
      shard 1, simulating a crash mid-shard) — confirms shard 0 still
      reads back exactly and shard 1 (bytes on disk, footer missing)
      throws rather than being silently misread.
      **Known gap, deliberately deferred, not silently shipped**: `step`/
      `t` are denormalized onto every row of a frame's batch rather than
      stored once via Arrow's lower-level per-batch `appMetadata` API — if
      a frame's row count is ever zero (impossible for ball-bounce, since
      it never destroys its one particle), that frame loses its timestamp
      under the current scheme. Left as-is since sub-pass A's scenario
      can't hit it; sub-pass B (sparks) will need either to fix this or to
      prove it truly doesn't come up before checkpointing is built on top.
      **Not yet built**: discrete-event channel (breaks/spawn/destroy —
      needs a scenario that actually produces events, which ball-bounce
      doesn't), checkpointing, resume. The *interactive* stream's own
      discrete-event channel is now built (see Phase 8's "Discrete-event
      channel for the interactive stream" entry above) - this recording
      format's gap is the separate batch/Arrow-IPC side, still open.
- [x] Checkpointing: full-state snapshot at each shard boundary (group
      membership, broken connections, emitter accumulator+RNG state,
      t/step index), resume-from-checkpoint on crash (§9.5, §13.2).
      Second sub-pass of the batch/record item above, proven against
      `sparks` (the only worked example with dynamic population and an
      emitter to round-trip) plus one small synthetic scenario for the one
      piece sparks can't exercise on its own (broken connections — sparks
      has no `PairwiseForce` at all).
      **Core proof**: `CheckpointTest` runs the sparks scenario two ways
      from the same seed — 1500 steps straight through, vs. 1000 steps,
      checkpoint-through-real-files, resume onto a *completely fresh*
      `buildSparks()` scenario shell, then 500 more steps — and asserts
      the two final states (live ids, positions, velocities) match
      *exactly*. Passed on the first run. Population genuinely churns in
      this window (spawns and destroys both happen; asserted, not
      assumed), so this isn't a degenerate no-op proof.
      **`particlesim.record`**: `CheckpointSchema` (Arrow columns for
      per-particle bulk state — id/position/velocity/mass/radius/
      spawnTime/lifetime — separate from `RecordingSchema`, since a
      checkpoint needs more than a played-back frame does), `Checkpoint`/
      `CheckpointParticle` data classes, `captureCheckpoint`/
      `applyCheckpoint`/`filterBrokenConnections` free functions,
      `CheckpointWriter`/`CheckpointReader` (one `.arrow` file for the
      bulk particle columns + one `.yaml` sidecar for everything
      non-columnar: t, step, nextId, group membership, broken connections,
      emitter state).
      **Two design gaps found and resolved while implementing** (both
      correct, but worth flagging since neither is literally what §9.5's
      bullet list says):
      1. **`mass` *is* captured, contradicting §9.5's explicit "mass —
         recomputed from expression" line.** That's the right call for a
         mass that's a genuinely re-evaluable function of time, shared
         across particles — but an emitter-spawned particle's mass
         (§14.1) is sampled *once* from a `ScalarDistribution` at spawn
         time and baked in as a particle-specific constant; there's no
         shared expression left to re-evaluate for a particle that
         already existed before the checkpoint. `ParticleStore.restoreParticle`'s
         doc comment covers this in full, including the resulting gap: a
         particle whose mass is a genuine time-varying `ScalarExpr.OfTime`
         does *not* survive a checkpoint round-trip with that
         time-variance intact — harmless today since no worked example
         gives an emitter-spawned particle a dynamic mass, but a real gap
         if one ever does.
      2. **The sidecar is YAML, not the literally-specified JSON** —
         reusing SnakeYAML (already a dependency, added for Phase 7)
         rather than adding a JSON library for a same-shaped need. Nothing
         in §9.5 requires JSON specifically for external-tool
         compatibility as far as this implementation found; flagging the
         deviation here rather than silently renaming it.
      **`ParticleStore.nextId` gap** (anticipated in this bullet before
      implementation, confirmed real): not recoverable from the
      alive-particle-id set alone, since ids are never reused and the
      highest-numbered particles may have already been destroyed by
      checkpoint time. `restoreParticle`/`advanceNextIdTo` (new `internal`
      methods) handle this — the latter must move forward only, checked
      with a `require`.
      **Broken connections**: `Checkpoint.brokenConnections` is a
      `Set<Pair<Int,Int>>` the *caller* accumulates step-by-step (folding
      in both `StepResult.brokenForces` and `DestructionResult.danglingForces`,
      filtered to `PairwiseForce`) — nothing in the engine remembers a
      break after the force is dropped from the active list, so this
      can't be discovered after the fact by the checkpoint mechanism
      itself. `BrokenConnectionCheckpointTest`: a synthetic two-particle
      breakable spring, broken on step 1, checkpointed, and resumed onto a
      freshly-rebuilt (same static definition) force list — confirms
      `filterBrokenConnections` correctly excludes the rebuilt spring.
      **Emitter RNG checkpointing — the trickiest piece**: `kotlin.random.Random`
      has no public API to serialize its internal state. Solved with
      `CountingRandom` (`particlesim.lifecycle`), a `Random` subclass that
      counts calls to `nextDouble()` — the one primitive every
      `VectorDistribution`/`ScalarDistribution` sampler in this codebase
      actually calls (confirmed by reading `Distribution.kt`, not
      assumed) — and can rebuild+fast-forward a fresh stream to any
      captured draw count by literally replaying that many draws.
      `CountingRandomTest` proves the fast-forwarded stream's *next*
      values are identical to the original stream's actual continuation,
      not just similarly-distributed. `Emitter` gained `captureState()`/
      `restoreState()` plus `EmitterCheckpointState` (accumulator phase,
      this emitter's own live-spawned ids in spawn order — needed for
      correct future `EVICT_OLDEST` behavior, which a generic *unordered*
      group-membership capture wouldn't preserve — the at-cap warning
      flag, and the RNG draw count).
      **Wired into `RecordingWriter`'s shard rollover** (§9.5's "a
      checkpoint is written at each recording shard boundary" as an
      actual mechanism, not two features that happen to coexist):
      `RecordingWriter` gained an optional
      `onShardComplete: ((shardIndex: Int, t: Double, step: Long) -> Unit)?`
      constructor param, invoked right after a shard's footer is
      finalized (so the shard it closes is already safe to read) — with
      the completed shard's index and the `t`/`step` of the last frame
      written into it, but deliberately *no* particle/scene state of its
      own. `RecordingWriter` has no reason to know about `Groups`,
      emitters, or an accumulated broken-connections set, so the callback
      is a closure over whatever the caller's own step loop is already
      tracking, not a self-contained checkpoint call — same "own the
      boundary, don't reach across it" instinct as everywhere else broken
      forces/destroyed particles are the *caller's* bookkeeping
      responsibility (`StepResult.brokenForces`,
      `DestructionResult.danglingForces`). Also fires for a *partial*
      final shard finalized by `close()` (a run ending mid-shard is a
      legitimate boundary too), verified directly in `RecordingTest` (25
      frames at `framesPerShard=10`: two full-shard fires at frame indices
      9/19, one partial-shard fire at 24 when the writer closes).
      `RecordingCheckpointIntegrationTest`: drives sparks through a real
      `RecordingWriter` for 1500 steps at `framesPerShard=500` (exactly 3
      shards), writing a real checkpoint file pair at every boundary via
      the callback; confirms exactly 3 checkpoints were written, then
      resumes from the *last automatically-triggered* one onto a
      completely fresh scenario shell and confirms it already matches an
      independent uninterrupted reference run's final state exactly — the
      same bit-for-bit proof `CheckpointTest` already established for a
      manually-chosen checkpoint moment, now shown to hold for the
      automatic wiring too. Passed on the first run.
      **Still open**: the real engine loop's crash-resume wiring (§13.2 —
      "failing fast costs at most the frames since the last shard
      boundary" — there's no long-running engine loop yet for this to
      attach to) and playback-fork-to-live are both listed separately
      below/in the deferred `[stretch]` section and weren't started here.
- [x] Multi-threaded force accumulation: turn on the fixed-chunk reduction
      designed back in Phase 2 — fixed logical chunk count, per-chunk
      private accumulators, fixed chunk-index merge order, deterministic
      across machines/thread counts, not just reruns (§9.3). Done first,
      ahead of the other Phase 8 items below, since it's the most
      self-contained piece and Phase 2's `ChunkAccumulator`/chunk-striding
      design already anticipated it — this was "turn it on," not design it.
      `Integrator` gained an optional `executor: ExecutorService?`
      constructor param (default `null` = every pre-Phase-8 call site's
      behavior, unchanged): non-null submits each chunk's work to it and
      joins every chunk's `Future` before the merge, which was *already*
      written in fixed chunk-index order rather than completion order, so
      it needed no changes at all. `chunkCount` and the executor's actual
      thread/pool size are independent on purpose (§9.3) — chunk count is
      what determinism is keyed on and must stay fixed across runs/
      machines; how many threads service those chunks can be anything,
      including fewer than `chunkCount` (some threads process more than
      one chunk, sequentially).
      **Ownership is caller's, not `Integrator`'s** — no lifecycle method
      was added to `Integrator` itself. A one-off `Integrator()` (nearly
      every test) never touches a thread pool at all; the real engine loop
      (later Phase 8 items) can share *one* executor across its whole
      lifetime instead of each `Integrator` owning a short-lived pool.
      **Thread-safety of the one piece of state a chunk's accumulate path
      actually mutates** — `MeshSprings.active` (an edge's break flag) —
      was verified by inspection, not assumed: every write is `active[i]`
      for the *same* `i` chunk-striding already gives that chunk exclusive
      ownership of, every read the break decision depends on is either
      `active[i]` or immutable per-edge data, and `activeConnections()`
      (which reads the whole array) is never called from inside
      `accumulate` — only by a caller after the step (and its chunk join)
      completes. No other force holds mutable state touched during
      accumulation. `ExecutionException` from a chunk's `Future.get()` is
      unwrapped (`e.cause ?: e`) before rethrowing, so a force's own
      exception type (e.g. `BlowUpException`) surfaces as itself whether
      the run was sequential or parallel — a catch block for a specific
      exception type shouldn't have to know which.
      `ParallelIntegratorTest`: runs the flag scenario (chosen because its
      3 `MeshSprings` instances are the one real concurrency stress case)
      for 500 steps and checks every live particle's position/velocity is
      *exactly* equal, not just close, across two configurations —
      `chunkCount=4` sequential vs. 8 threads, and `chunkCount=7` sequential
      vs. exactly 2 threads (the asymmetric case: one worker processes
      chunks 0/2/4/6, the other 1/3/5, so completion order diverges from
      submission order the most — the configuration that would have
      exposed a merge that accidentally depended on completion order
      instead of the fixed index order it's actually written to use). Plus
      two tests confirming a deliberately-thrown exception surfaces as its
      own type on both the parallel and sequential paths.
      **Measured, not assumed, whether this actually helps**: 500 steps of
      the flag scenario (~112 particles) at `chunkCount=4`, sequential
      ≈ 14.6–15.7ms vs. 4 threads ≈ 21.5–22.5ms — parallel was *slower*,
      thread-dispatch overhead dominating the genuinely tiny per-step work
      at this N. Expected and fine: the TODO item was "turn on the
      mechanism and prove it's still deterministic," not "prove a
      speedup" — whether multi-threading actually pays off is an
      N-dependent question with no large-N scenario yet to answer it, so
      the honest status is "correct and available," not "faster."
- [~] Interactive particle drag, step-index-stamped drag targets for
      exact replay (viewer input → engine) (§9.4). Reuses the constraint
      mechanism per §9.4's own framing ("driven exactly like a
      fixed-position constraint, except the target comes from the live
      input stream") rather than a parallel one.
      **`DragConstraint`** (`particlesim.physics`): pins *one specific
      id*, not a `Groups` selector like every other constraint — a
      deliberate deviation, since a drag session is ad hoc and freely
      reassigned particle-to-particle over its lifetime, so a throwaway
      one-off group per drag would just accumulate stale entries.
      Position/velocity pinned every step exactly like `FixedPosition`
      (so a connected spring only ever feels the dragged particle's
      *position* changing, never a velocity it doesn't actually have);
      `releaseVelocity()` recovers a throw velocity from the two most
      recent targets via finite difference — the same idea moving
      colliders already use for velocity (§12.5), reused rather than
      adding a second analytic-velocity path.
      **Wire protocol**: `DragMessage` (`particlesim.debug`) parses
      viewer→engine `drag_start`/`drag_move`/`drag_end` JSON, each
      stamped with the step it's for. Parsed with SnakeYAML (already a
      dependency) rather than a JSON library — same call already made for
      the checkpoint sidecar. `DragMessageQueue` is the thread-safe
      hand-off from `DebugServer`'s WebSocket I/O thread to the physics
      loop's own thread — a real concurrency concern, not a formality:
      `DebugServer.onMessage` fires on the WebSocket library's thread,
      the step loop runs on a different one, and start/end ordering
      matters (a click-and-immediately-release shouldn't coalesce away),
      which rules out a plain "latest wins" reference.
      `DebugServer`/`DebugRenderer` gained an `onTextMessage` callback —
      the exact `onMessage` upgrade Phase 3 anticipated, not a new class —
      deliberately ignorant of `DragMessage` itself, so it stays reusable
      for any future viewer input. `DebugFrame`'s JSON gained a `step`
      field so the viewer can stamp outgoing messages without deriving a
      step count from `t`/`dt` itself (fragile: floating-point drift).
      **`DragDebugDemo`** (`./gradlew runDragDemo`): the same spring-chain
      scenario as the default `run` demo (one end pinned, hanging under
      gravity) — chosen over ball-bounce specifically because it has
      connected forces to propagate through, matching §9.4's own
      motivating example ("see how motion propagates through connected
      forces... to the rest of the system"); dragging an isolated single
      particle wouldn't demonstrate that at all. Picking/raycasting and
      the drag-plane projection (through the picked point, facing the
      camera — the standard way to turn a 2D mouse position into a 3D
      target) are added to `viewer.html`, resolved entirely
      client-side per §9.4 ("the engine doesn't need to know about
      cameras or screen coordinates").
      **Verification**: Chrome browser automation wasn't available in
      this environment (extension not connected), so the full
      server-side pipeline was verified instead with a throwaway raw
      WebSocket client (connect, send synthetic `drag_start`/`drag_move`/
      `drag_end` messages, inspect the resulting broadcast frames) —
      confirmed (1) a dragged particle is pinned exactly at the sent
      target while held, (2) neighboring chain particles visibly get
      pulled toward it through the springs (propagation actually works,
      not just the pin itself), and (3) releasing while still moving
      imparts a real throw — the particle kept moving on its own in
      frames captured well after release, not frozen. This proves the
      constraint/queue/protocol/server wiring end to end. **What it can't
      prove**: whether the actual mouse-driven picking/dragging *feels*
      right in a real browser (raycasting against the right dot, the
      drag plane tracking the cursor naturally, no jank) — that needs a
      human trying it, still pending. 40 test classes, 192 tests green
      (12 new: `ConstraintTest` +4, `DragMessageTest` +6, `DragMessageQueueTest`
      +3, minus the pre-existing `DebugFrameTest` cases updated for the
      new `step` field).
      **Not done**: replay-exactness for recorded runs — the message
      protocol carries the step stamp §9.2's future events channel would
      need, but nothing consumes it in step-ordered-queue fashion yet
      (live mode just applies whichever message arrived most recently);
      genuinely blocked on the same missing discrete-event channel noted
      throughout Phase 8's recording work, not new to this piece.
- [ ] `[stretch]` Parquet export (post-hoc conversion from Arrow shards,
      for pandas/Spark-style tooling) (§9.2)

## Phase 9 — Full visualization
- [x] Camera: scripted (engine-evaluated) + manual (viewer-local) (§10.1).
      First sub-pass of Phase 9 — camera picked first since the frame
      protocol needs a camera field before renderer declarations (next
      sub-pass) have anything meaningful to add camera-relative info to.
      **`particlesim.render`**: `SceneQuery` (read-only — deliberately
      narrower than `ParticleStore`/`Groups`, since camera evaluation
      must never be able to mutate simulation state), `SceneQueryImpl`
      (`position(id)`, `centroid(group)` — `normal(surface, triangleIndex)`
      from the spec's own list isn't built yet, no consumer needs it
      before a camera actually orbits a surface). `CameraPose` (plain
      position/lookAt/up numbers — what actually gets serialized, not a
      matrix). `CameraFunction`: a `fun interface` taking `(t, SceneQuery)
      -> CameraPose`, evaluated by the **engine** every broadcast (a
      simplification of the requirements doc's own nested
      `camera { position { t -> ... }; lookAt(...) }` builder — same
      "simplify the sugar, keep the semantics" call already made for
      Phase 1's `mass(...)`/`mass { t -> ... }` function-call style
      instead of property assignment). **YAML camera expressions
      (scene-query grammar extension) are a deferred second pass**, same
      status as every other post-Phase-7 YAML gap — this sub-pass only
      wires up the Kotlin-DSL/native-lambda path.
      **Wired into the existing debug-frame protocol, not a new one**:
      `DebugFrame`/`DebugRenderer` gained an optional `camera: CameraPose?`
      parameter — `null` (the default) omits the JSON field entirely, so
      every pre-existing demo (ball-bounce, sparks, the plain spring-chain,
      drag) needed zero changes. `viewer.html` applies the camera
      pose when the field is present and otherwise leaves its static
      default camera untouched.
      **Worked example**: `FlagDebugDemo` now scripts a camera orbiting
      the cloth's centroid while looking at the flag's free corner —
      almost exactly the requirements doc's own motivating example for
      camera scripting (`centroid("flag") + Vector3(sin(t*0.3)*5, 2,
      cos(t*0.3)*5)`, looking at a named point).
      **Verification**: Chrome automation still wasn't available in this
      environment, so (as with §9.4's drag work) the server-side pipeline
      was verified with a raw WebSocket client sampling the running
      `FlagDebugDemo`'s frames over several seconds — confirmed the
      camera field is present, its position traces a smooth arc (not
      static, not jumping), and `lookAt` tracks the flag tip's actual
      live (wind-blown) position, not a fixed point. Actual visual
      quality in a browser (does the orbit look smooth, is the flag
      framed well) is still unconfirmed by a human.
      **Manual/viewer-local camera mode landed later, in the web-viewer
      sub-pass** (below) once orbit controls actually existed for
      "manual" to mean something — see that bullet for the scripted-vs-
      manual toggle itself. Marking this bullet done now that both
      halves exist.
- [x] Renderers: particle/surface (dot/sphere/mesh) — the real opt-in
      system; Phase 3's debug-render override stays available as a
      permanent fallback alongside it, not replaced (§10.2). Wired end to
      end in this phase's fifth sub-pass — see the notes under the "force
      renderers" bullet below, which covers this phase's work as a whole.
- [x] Renderers: force (arrows for fields, lines w/ colorblind-safe
      breakProximity gradient for springs/dampers) (§10.2). Second
      sub-pass of Phase 9 — **declaration types and computation logic
      only, proven with unit tests; not yet wired into any wire protocol
      or viewer**, deliberately deferred to the third sub-pass (web
      viewer upgrade) so this data flows through the *new* binary
      protocol once, not bolted onto the old JSON debug-frame format now
      and redone later.
      **`particlesim.render`**: `ParticleRenderer`/`ParticleStyle`
      (Dot/Sphere), `SurfaceRenderer` (a `List<Triangle>` directly, same
      as `Wind` already takes — surfaces have no name→object registry
      yet), `LineRenderer` (a `PairwiseForce` + `ColorBy`), `ArrowRenderer`
      (a new `UniformFieldForce` + region + resolution). Kotlin-DSL-first
      like everything since Phase 7 — renderers reference the actual
      force/group/triangle objects, not YAML string names; YAML
      `renderers:` support is a deferred second pass, same status as
      every other post-Phase-7 YAML gap.
      **`Breakable` gained `breakProximity(store): Double`** (alongside
      the existing `shouldBreak`) — `0` at rest, `1` the instant before
      breaking, possibly `>1` transiently the one step a connection
      actually breaks (its force still applies that step, §5.4).
      Implemented on `Spring`/`Damper` using the same direction-dependent
      threshold logic `shouldBreak` already has (extension vs.
      compression) — an infinite (never-breaks) threshold returns `0.0`
      rather than dividing into a technically-correct-but-meaningless
      value.
      **New `UniformFieldForce` interface** (`sampleAt(position, t):
      Vector3`) — implemented by `UniformGravity` and `Wind`, both
      already spatially uniform today (Wind's `velocity` field, not the
      resulting per-triangle pressure force `accumulate` computes, which
      isn't a spatial field in the same sense). `position` is accepted
      but currently unused by both — ready for a future spatially-varying
      force (gusty wind, a known unbuilt gap since Phase 2/4) without an
      interface change.
      **`ColorRamp.blueOrange`**: the spec's own suggested colorblind-safe
      gradient (Okabe-Ito palette's blue/orange pair, not the intuitive-
      but-deuteranopia-hostile green→yellow→red), clamping its input
      first so a `>1` breakProximity still resolves to a valid color.
      **`LineRenderer` validates eagerly**: declaring
      `colorBy=BREAK_PROXIMITY` on a force that isn't `Breakable` fails at
      construction (`require`), not silently — an authoring mistake
      that's much easier to debug as an immediate exception than as "why
      isn't my spring changing color?" discovered later.
      **Deferred, not built**: `stretch`/`force`-magnitude `colorBy`
      variants from the spec's own list — unlike `breakProximity`,
      neither has one definition that means the same thing across every
      `PairwiseForce` type (`Damper` has no rest length for "stretch" to
      mean anything against), so they need their own design pass rather
      than being guessed at here. N-body/custom-force renderers are
      `[stretch]` per the spec itself.
- [x] Web viewer (WebGL/three.js) + binary WebSocket protocol, full
      orbit/picking/camera controls — sole first-class viewer, upgrading
      from Phase 3's bare-bones renderer (§9.1, §10). Third sub-pass of
      Phase 9 (mesh/sphere/arrow/colored-line rendering landed in the
      fourth and fifth sub-passes below). Binary protocol + orbit
      controls done and server-side verified — see
      below.
      **`particlesim.debug.BinaryFrame`**: replaces `DebugFrame`'s JSON
      text entirely (deleted, along with its test — genuinely unused
      once nothing called it, not left as dead code) with a fixed-layout
      little-endian buffer (`f64 t, i64 step, i32 particleCount,
      particleCount*{i32 id, f64 x,y,z}, i32 connectionCount,
      connectionCount*{i32 a,b}, u8 hasCamera, [9x f64 if set]`) — §9.1's
      own stated reason: JSON's bandwidth/parse cost "would otherwise
      bite at large N and in drag-interaction latency." `encode`/`decode`
      round-trip-tested (`BinaryFrameTest`), including that `decode`
      never mutates a buffer's position a caller might still need to
      send. `DebugServer.broadcastFrame` now takes a `ByteBuffer`
      (`WebSocketServer.broadcast(ByteBuffer)` already existed in the
      Java-WebSocket dependency — no new dependency needed).
      **Verified two ways**: the JVM-side round-trip test above, and
      (since Chrome automation still wasn't available) a raw
      `WebSocketClient` connected to a *live* `FlagDebugDemo` and
      decoded real frames off the wire with `BinaryFrame.decode` —
      confirmed correct particle count (112), connection count (202),
      and a smoothly-changing camera pose matching the flag demo's known
      shape, not just a self-consistent encode/decode round-trip in
      isolation.
      **Client (`viewer.html`)**: `ws.binaryType = "arraybuffer"`
      plus a hand-written `decodeFrame` mirroring `BinaryFrame`'s layout
      exactly via `DataView` (little-endian) — this half is **unverified
      by anything other than careful transliteration**, since it can
      only really be proven by a browser actually rendering something,
      which this environment couldn't do.
      **Orbit controls added** (three.js's `OrbitControls` addon, loaded
      via the same CDN import map already in use) — §10.1's scripted-vs-
      manual toggle: `OrbitControls` stays *enabled* at all times so its
      own "start" event can detect "the user just grabbed the viewport"
      even while scripted mode has been driving the camera, but its
      internal state is only ever *applied* to the camera
      (`controls.update()`) while in manual mode, and the scripted pose
      from `applyFrame` is only ever applied while in scripted mode —
      never both, so they can't fight each other. An always-visible
      "return to scripted camera" button is the spec's required
      "explicit action returns to scripted" (§10.1). A drag on a
      particle calls `stopPropagation()` in the capture phase to stop
      that same pointerdown from also starting an orbit gesture — a
      standard pattern, but genuinely untested interaction-feel-wise for
      the same reason as the rest of this bullet.
      **Fourth sub-pass: `breakProximity`-colored lines wired end to
      end** — the one piece of the previous sub-pass's renderer
      declarations actually connected to the wire protocol and viewer so
      far, chosen first because it's the spec's own most emphasized
      renderer feature (a full paragraph plus a dedicated flag worked
      example: "in a strong enough gust you'll see the cloth redden right
      at the seam that's about to tear").
      **`MeshSprings.activeConnectionsWithBreakProximity(store)`** (new):
      per-edge `breakProximity`, since `MeshSprings` isn't itself
      `Breakable` — it represents *many* independently-breakable edges as
      one `Force` (§9.3's chunking requirement), so there's no single
      proximity value for "the force" as a whole the way there is for a
      lone `Spring`/`Damper`. Mirrors `Breakable.breakProximity`'s
      semantics (direction-dependent threshold, `0.0` for an unbounded
      threshold) rather than being a new, different rule.
      **`buildFlag` gained an optional `structuralBreakThreshold`**
      (default `Double.POSITIVE_INFINITY`, i.e. unchanged from before) —
      confirmed behavior-preserving for every existing caller by rerunning
      `FlagGoldenTest`/`FlagYamlParityTest` after the change, not just
      assumed from the default value alone. `FlagDebugDemo` alone passes
      a finite value (`0.02`, ~13% of the structural restLength) purely
      to have something worth coloring.
      **`BinaryFrame`'s connections section now always carries a color**
      (extended from `{i32 a, i32 b}` to `{i32 a, i32 b, f64 r, f64 g,
      f64 b}`) rather than adding a second, sparse color channel —
      resolved from an optional `lineColors: Map<Pair<Int,Int>, Color>`
      at encode time, defaulting to `Color.DEFAULT_LINE` (the viewer's
      original uncolored blue) for any connection not in the map, so
      every other existing demo needed zero changes (empty map is the
      default). `DebugRenderer.broadcast` threads `lineColors` through
      the same way.
      **Client**: `viewer.html`'s line material switched to
      `vertexColors: true` with a parallel color `BufferAttribute`
      alongside position; `decodeFrame` reads the 3 extra `f64`s per
      connection. Unverified beyond careful transliteration, same
      Chrome-automation caveat as the rest of this phase's client-side
      work.
      **Verified server-side**: sampled `FlagDebugDemo`'s actual
      connection colors over 8 seconds via a raw `WebSocketClient` —
      confirmed real, varying color (red channel ranging roughly 0 to
      0.35, never fully saturated) rather than uniformly blue or
      uniformly maxed out. One edge incidentally exceeded the threshold
      and broke during sampling (connection count dropped from 202 to
      201 and stayed there) — an unplanned but welcome extra proof that
      `MeshSprings`' per-edge breaking still works correctly under this
      wiring.
      **Later reverted** once drag was combined with this same demo (see
      the "post-Phase-9 fixes" note near the end of this section) — a
      finite structural break threshold and free-form dragging turned out
      to be in real tension, not just a tuning problem. Left here as the
      honest record of what was tried and why it didn't survive contact
      with actual interactive use, not edited away.
      **Fifth sub-pass: everything else wired — `ParticleRenderer`
      (sphere sizing), `SurfaceRenderer` (shaded mesh), `ArrowRenderer`
      (field sampling), and a dot-visibility filter.** Phase 9 is now
      fully wired end to end, not just declared.
      **`BinaryFrame` gained three more sections** (spheres `{i32 id,
      f64 radius}`, meshes `{u8 wireframe, i32 triangleCount, triangles}`,
      arrows `{f64 ox,oy,oz, f64 vx,vy,vz}`) plus an optional
      `visibleIds` filter (`u8 flag, [i32 count, ids]`) — `null` (every
      demo before this) draws every particle as a dot, unchanged;
      non-null draws *only* the listed ids, letting a particle with no
      renderer of its own (§10.2: "the individual cloth particles have
      no renderer of their own — the mesh already shows them") stay
      invisible as a standalone dot while still traveling in the base
      particle list for mesh-vertex/line-endpoint lookups. All four
      additions are pre-resolved data in `BinaryFrame.encode`'s
      parameters (sphere radii, `SurfaceRenderer` triangle lists, sampled
      `ArrowSample`s) — the encoder never evaluates a renderer
      declaration itself, matching the existing `lineColors` pattern.
      **`FlagDebugDemo` now implements the requirements doc's own
      *extended* flag example almost exactly**: the cloth surface renders
      as a shaded mesh (`SurfaceRenderer(scenario.triangles)`), the pole
      particles render as small spheres (`poleSphereRadii`, radius 0.03)
      so the anchor stays visible, wind gets an arrow renderer sampled
      around the flag's footprint (`ArrowRenderer` over `Wind`, which
      already implemented `UniformFieldForce` from the previous
      sub-pass), and `visibleIds = poleIds` hides the cloth particles'
      own dots since the mesh already shows them. Wind's raw ~6-8 m/s
      magnitude is scaled ×0.15 purely for this demo's arrow *display* —
      a presentation choice made in the demo, not a change to
      `ArrowSampling`'s actual physical output.
      **Picking survives mesh-only rendering**: with cloth particles
      invisible as dots, the viewer's raycaster now also intersects
      surface mesh objects, resolving a mesh-face hit to whichever of
      its three vertex particles is nearest the click point
      (`pickParticle` in `viewer.html`, using the raycast hit's own
      local face-vertex indices paired positionally with the triangle's
      recorded particle ids — not a search/guess). Mesh geometry itself
      is built with *shared* (indexed) vertices per unique particle id,
      not one disconnected triangle per face, so `computeVertexNormals()`
      produces smooth per-vertex shading instead of flat per-facet
      shading.
      **Verified server-side**: sampled a live `FlagDebugDemo` frame and
      confirmed every number was sane, not just present — exactly 8
      pole spheres all at radius 0.03, exactly 1 mesh with exactly 182
      triangles (the closed-form correct count for an 8×14 grid:
      `(8-1)*(14-1)*2`), exactly 8 visible ids (matching the pole count
      precisely), and 36 arrow samples all sharing one vector (wind is
      spatially uniform today, so every sample *should* match — and did).
      **Still unverified**: the actual visual result and interaction feel
      in a real browser (does the mesh shade convincingly, do arrows read
      as wind, does mesh-face picking feel as precise as dot-picking) —
      Chrome automation wasn't available in this environment for any of
      Phase 9's client-side work, a standing caveat repeated across every
      sub-pass here, not new to this one.
      **Deferred, documented, not silently dropped**: N-body/custom-force
      renderers (`[stretch]` per the spec itself), `stretch`/`force`
      colorBy variants (no single cross-`PairwiseForce`-type definition,
      per the previous sub-pass's note), spatially-varying arrow fields
      (blocked on `UniformFieldForce` gaining real position-dependence,
      itself blocked on a concrete scenario needing gusty/spatial wind),
      and YAML renderer declarations (same deferred-to-a-second-pass
      status as every other post-Phase-7 YAML gap).

**Post-Phase-9 fixes, from actually trying the finished viewer** (the
first real human feedback on any of this phase's client-side work, since
Chrome automation wasn't available to this session):
- Wind's arrow heads were close to a third of the arrow's own length —
  reduced the head length/width fractions and their absolute caps.
- Added a viewer-local "show mesh edges" checkbox overlaying a wireframe
  on the otherwise-solid cloth mesh — independent of `SurfaceRenderer`'s
  own `wireframe` flag, which picks solid-vs-wireframe-only for a whole
  mesh, not an overlay toggle.
- "The flag stopped looking like a surface" while dragging was
  z-fighting: the structural lines share exact vertex positions with the
  solid mesh, and with no `polygonOffset` on the mesh material the two
  flickered at the same depth — much more visible once dragging deformed
  the surface unevenly. Fixed with `polygonOffset` on the solid material,
  the standard fix for coplanar solid+line rendering.
- **A real, more fundamental bug**: dragging still left the flag
  *permanently* deformed even after the z-fighting fix, and the
  structural-line pattern visibly changed shape — both traced to the
  fourth sub-pass's finite `structuralBreakThreshold` (see above).
  `MeshSprings`' structural damping is well under critical for its
  stiffness/mass, so a sudden reposition — what dragging does every step
  — causes real overshoot, not a smooth approach; that transiently spiked
  displacement past thresholds that had looked safe watching wind alone
  (0.02 already broke under wind by itself; 0.1, tried as a fix, still
  broke under a deliberately modest, gradual drag). Since wind's own peak
  displacement is tiny, any threshold loose enough to survive dragging's
  overshoot makes wind's color contribution negligible anyway — chasing
  a higher number wasn't going to resolve the tension. Reverted to no
  finite threshold in this demo; verified by replaying the exact
  aggressive drag that broke things before and confirming the flag now
  settles back with all 202 connections intact. `breakProximity`
  coloring itself remains real, implemented, and independently tested
  (`MeshSpringsTest`, `ColorRampTest`) — just not exercised by this
  particular demo anymore, since permanently tearing the flag from
  ordinary dragging was a worse experience than never showing tension
  color.

## Phase 10 — Viewer UI (§10.3, new requirements)

First sub-pass landed: the three viewer-local pieces that need zero wire-
protocol change and no new engine-side naming/identity concept, all in
`viewer.html`. Everything past this point (outliner, per-object
panels, right-click, selection/inspection, color legend) is blocked on a
real prerequisite, not just unstarted — see the note below.

- [x] Global toggles (grid, axes) — `gridHelper`/`axesHelper` promoted from
      anonymous `scene.add(new THREE.GridHelper(...))` calls to named
      consts so a checkbox's `change` listener can flip `.visible`
      directly; no other state to keep in sync.
- [x] Stats overlay: particle count, step rate, physics-vs-wall-clock lag
      (a user-visible readout of §9.1's drop-frames-not-physics pacing
      policy). Two things worth recording since they're easy to get wrong:
      **lag** is the *drift* between wall-clock-elapsed and sim-time-
      elapsed since an anchor frame, not `now - t` — anchored fresh on
      every `ws.onopen` (including reconnect-to-a-different-process), so a
      viewer that attaches mid-run reads ~0 lag at that moment instead of
      "however far into the run it is." **Step rate** is `Δstep/Δwallclock`
      off the frame's own `step` counter, sampled roughly once a second —
      deliberately not frames-received-per-second, which differs from the
      true physics rate by exactly `stepsPerFrame` and would misreport
      whenever rendered frames drop (by design, §9.1).
- [x] Camera bookmarks: save/restore a handful of named manual views.
      Session-only (in-memory `Map`, no persistence — nothing in §10.3
      asks for cross-reload persistence and no other viewer state has it
      either). Restoring one calls `enterManualCamera()` first — the
      existing scripted/manual split (§10.1) only ever applies a scripted
      pose while `cameraMode === "scripted"`, so setting the camera while
      still scripted would just get overwritten by the very next frame,
      a trap that's easy to hit by testing only from manual mode.
- [x] **Dev-loop fix, not spec'd but needed to build the above sanely**:
      `ViewerHttpServer` cached `viewer.html`'s bytes once at
      construction; changed to re-read from the classpath per request.
      A demo process runs for minutes while the HTML itself gets
      iterated on many times, and re-reading means `./gradlew
      processResources` (well under a second) picks up an edit instead
      of a full demo restart (~20s TIME_WAIT wait for the WebSocket
      port to clear). Verified live: edited the page title, ran
      `processResources` with the demo still running, `curl`'d the
      unchanged-JVM server and got the new title back.
      **Verification, honestly**: Chrome automation is still not actually
      connected in this environment (`tabs_context_mcp` fails with
      "Browser extension is not connected," checked twice before and
      after this sub-pass) — the same standing limitation as all of
      Phase 9's client-side work, not newly discovered. What *was*
      verified: `node --check` on the extracted module script (valid
      syntax), every `getElementById` call cross-checked against an
      actual `id=` in the HTML (no null-reference typos), a live `curl`
      against the running demo confirming the new markup is actually
      served, and the full 238-test suite staying green (no engine code
      changed except `ViewerHttpServer`). **Not verified**: whether the
      panel is readable/usable, whether the lag/rate numbers look sane
      against a real running demo, whether bookmark restore actually
      feels right — all of that needs a human (or a working browser
      connection) opening the page.
- [x] **Prerequisite closed**: name→object registry for forces and
      surfaces. Two gaps, closed separately:
      **Surfaces had no identity at all** — just a bare `List<Triangle>`
      passed around, unlike `Force`/`Collider`, which already carried an
      optional `name: String?`. New `particlesim.surface.Surface`
      (`data class Surface(val triangles: List<Triangle>, val name:
      String? = null)`) gives a mesh the same optional-name convention.
      `SurfaceRenderer` now holds the `Surface` itself, not a raw
      triangle list, specifically so the outliner can later answer "is
      this named surface currently rendered?" by object identity rather
      than two independently-built triangle lists that happen to match.
      `Wind` deliberately keeps taking `List<Triangle>` unchanged — it
      only ever acts on geometry and already has its own identity via
      its own `name`, so it doesn't need to know about `Surface` at all.
      **`SceneRegistry`** (`particlesim.render`): the actual registry —
      `SceneRegistry.build(forces, surfaces)` filters to entries with a
      non-null `name` (an unnamed force/surface still works physically,
      it's just not individually reachable, the same "nothing shows up
      unless it opts in" policy §10.2 already applies to renderers) and
      returns `Map<String, Force>`/`Map<String, Surface>`, `LinkedHashMap`-
      backed so outliner order matches scene-authored order rather than
      hash order. Names are unique **within their own kind only** — a
      force and a surface may share a name with zero ambiguity, since
      the outliner lists them in separate sections; a duplicate *within*
      one kind fails eagerly at `build()` (an ambiguous outliner entry is
      an authoring mistake, not something to render silently-wrong).
      Granularity is documented explicitly in the class doc: one
      `MeshSprings` instance (hundreds of edges) registers as *one* named
      force, matching §10.3's "a force's current magnitude" framing —
      reaching a single edge within it needs a different mechanism this
      registry deliberately doesn't attempt.
      **`buildFlag` is the first real (not hypothetical) consumer**: its
      cloth mesh is now a named `Surface` (`"cloth-mesh"`, deliberately
      *not* reusing the cloth particle group's own `"cloth"` name — two
      outliner entries both labeled `flag.cloth`, one a Group and one a
      Surface, would look like a bug to anyone with no reason to know
      groups and surfaces are different namespaces underneath) and its
      `Wind` force is now named `"wind"`, both correctly re-namespaced
      under an `instanceName` via the existing `ShapePlacement` mechanism
      (`FlagTest`, 3 new tests).
      **Verified two ways**: `SceneRegistryTest` (5 tests — named-only
      filtering, duplicate-within-kind rejection, same-name-across-kinds
      allowed, `LinkedHashMap` order preservation, empty input) plus a
      live server-side check against a running `FlagDebugDemo` confirming
      the `Surface` refactor changed nothing observable over the wire —
      112 particles, 1 mesh at exactly 182 triangles, 8 spheres, 36
      arrows, identical to Phase 9's own previously-verified numbers.
      **Deliberately out of scope for this pass** (per its own framing —
      a prerequisite design pass, not the outliner itself): `Constraint`
      still has no `name` field at all (unlike `Force`/`Collider`), and
      `Groups` has no way to enumerate *all* group names (only look up a
      specific one) — both real gaps for a complete outliner, neither
      blocking what forces/surfaces needed. Nothing here touches
      `BinaryFrame`'s wire layout or `viewer.html` — transmitting
      the registry and actually building outliner UI is the next
      sub-pass, not bundled into this one.
- [x] **Prerequisite closed, second half**: `Constraint` gained a `name`
      field (mirroring `Force`/`Collider`'s existing `name: String? =
      null` convention exactly), and `Groups` gained `names(): Set<String>`.
      `FixedPosition` and `FixedVelocity` both take an optional `name`
      constructor param (threaded through `FixedPosition`'s two
      construction paths — the public constructor and
      `atCurrentPositions`, both tested separately so the threading isn't
      just assumed). `DragConstraint` deliberately does *not* get a `name`
      param — it's hardcoded `null`, since a drag session is the same ad
      hoc, freely-reassigned-particle-to-particle target its own existing
      doc comment already describes as deliberately not using a named
      selector; exposing a name param would be dead API surface with no
      legitimate caller.
      **`Groups.names()`** returns every group that has ever had a member
      added, whether or not it's currently non-empty — a group emptied
      out via `remove`/`removeParticle` still appears, since there's no
      separate "undeclare a group" operation and an outliner shouldn't
      make a scene-authored group silently vanish just because its
      current membership is temporarily zero.
      **Verified**: 7 new tests (`GroupsTest` ×4, `ConstraintTest` ×3);
      full suite now 253 green.
      **Still not done**: `SceneRegistry` itself doesn't yet accept
      constraints or a `Groups` instance — it only covers forces/surfaces
      from the first half of this prerequisite. Extending it to all four
      kinds, plus actually wiring the registry into the wire protocol and
      viewer, is what the outliner item below still needs.
- [x] **Prerequisite closed, third half**: `SceneRegistry` now covers all
      four outliner kinds — `build(forces, constraints, surfaces, groups)`.
      Groups are collected differently from the other three, deliberately:
      a `Force`/`Constraint`/`Surface` is *optionally* named and filtered
      to named-only entries (`Map<String, T>`, same eager duplicate-name
      rejection as before), but a group has no unnamed form at all — its
      name *is* its identity in `Groups` from the moment a member is
      first added — so every group is collected regardless, exposed as a
      plain `Set<String>` (`registry.groups`) rather than a map, since
      there's no separate object to look up beyond the name and whatever
      `Groups.membersOf` already answers with it. No duplicate-name check
      needed for groups either, for the same reason: `Groups` can't hold
      two different things under one name by construction.
      **Verified**: `SceneRegistryTest` extended to 6 tests (named-only
      filtering now spans forces/constraints/surfaces together, groups
      registered unconditionally, duplicate rejection checked for both
      forces and constraints, same-name-across-all-four-kinds including a
      group, insertion order, empty input across all four). Full suite
      now 254 green.
- [x] **Registry wired into the wire protocol, and a basic (read-only)
      outliner landed in the viewer** — the transport half of what was
      left above, plus the first real piece of §10.3's actual UI.
      **`BinaryFrame`** gained a registry section: four name lists (forces,
      constraints, surfaces, groups), each `i32 count` + that many
      `{ i32 byteLen, UTF-8 bytes }` strings — the wire format's first use
      of variable-length string data, everything before this being fixed-
      size primitives. Deliberately **names only, no per-frame numeric
      state** — a force's live magnitude already has a home if it has a
      line/arrow renderer, and there's no per-object inspection readout
      yet (a separate, later piece of §10.3) — so this section doesn't
      try to carry both. Sent unconditionally like `sphereRadii`/`meshes`/
      `arrowSamples` (no `has`-flag): an absent registry and an empty one
      mean the same thing, so every demo built before this now pays 4
      zero-valued `i32`s per frame rather than a new branch to skip.
      `DebugRenderer.broadcast` gained a trailing `registry: SceneRegistry
      = SceneRegistry.build()` param threading straight to `encode`.
      **A real bug found and fixed along the way**: `Groups.names()`
      returned `HashMap` key order, not creation order — silently
      violating `SceneRegistry`'s own already-documented "stable, scene-
      authored order, not hash order" guarantee for the other three
      kinds. Caught by a wire round-trip test asserting a specific group
      order (`assertEquals(listOf("cloth", "pole"), ...)` failed as
      `[pole, cloth]`) — a `Set`-vs-`Set` comparison elsewhere had masked
      it since `Set` equality ignores order. Fixed by switching `Groups`'
      backing map to `LinkedHashMap`; regression test added directly to
      `GroupsTest` so this can't quietly regress back to `HashMap` later.
      **`buildFlag` gained one more name**: the pole `FixedPosition`
      constraint is now `"pole-anchor"` (previously unnamed) — needed a
      real named constraint to prove the registry's constraints kind
      against an actual demo, not a synthetic test fixture.
      **`FlagDebugDemo` is the real (not hypothetical) consumer**: builds
      a `SceneRegistry` from `scenario.forces/constraints/surface/groups`
      and passes it to `broadcast`.
      **Viewer**: `viewer.html`'s `decodeFrame` gained `readString`/
      `readNameList` (via `TextDecoder`, mirroring `BinaryFrame.decode`
      exactly) and a new `#outliner` panel (top-left) listing all four
      kinds under labeled sections — read-only, no per-object panels/
      right-click/selection/color-legend yet, all still separate future
      work. Skips re-rendering its `<ul>`s when the registry's JSON-
      stringified contents haven't changed frame-to-frame, since names
      are structurally static for a run and rebuilding four lists 60
      times a second for unchanging data would be pure waste.
      **Verified three ways**: `BinaryFrameTest` (+3 — empty registry
      round-trips as four empty lists, a populated one round-trips
      correctly with unnamed entries excluded, non-ASCII UTF-8 bytes
      round-trip exactly), a live server-side WebSocket check against a
      running `FlagDebugDemo` confirming the *actual* decoded registry
      off the wire (`forces=[wind]`, `constraints=[pole-anchor]`,
      `surfaces=[cloth-mesh]`, `groups=[cloth, pole]`) matches exactly
      what the scenario declares, and the usual no-browser fallback
      (`node --check` on the extracted module script, every
      `getElementById` cross-checked against a real `id=`, a live `curl`
      confirming the new outliner markup is served). Chrome automation is
      still not connected in this environment — checked again before
      starting this piece — so whether the panel is actually readable/
      usable in a browser remains unverified by a human. Full suite now
      258 green.
      **Deliberately not done here**: per-object panels, right-click-to-
      open, selection & inspection (live numeric readout), and the color
      legend are all real, separate pieces of §10.3 — this pass only
      gets names onto the screen, not interaction with them.
- [x] **Per-object panels — scoped to what's actually wire-representable,
      not all four kinds equally.** Outliner entries are now clickable;
      clicking one opens a shared panel showing that object's name and,
      for the two kinds where a real toggle is possible, a working
      visibility checkbox. **Groups and surfaces got real toggles; forces
      and constraints got an honest note instead of a fake one** — the
      asymmetry is deliberate, not an oversight:
      - **Groups**: `SceneRegistry.groups` changed from `Set<String>` to
        `Map<String, Set<Int>>` (name → current member ids, resolved
        eagerly at `build()` time). **Found a real bug while wiring
        this**: `Groups.membersOf` returns a *live* reference to its own
        internal mutable set (unlike `Groups.names()`, which already
        copies) — the first version of this snapshot silently aliased
        live data instead of freezing it, caught by a test that mutated
        `Groups` after `build()` and expected the registry to be
        unaffected. Fixed with an explicit `.toSet()` copy at the
        `SceneRegistry` call site (not inside `membersOf` itself, which
        is a hot path called every physics step — copying there would
        cost real allocation for no benefit to its actual callers).
      - **Surfaces**: `SurfaceRenderer` already held the `Surface` object
        (from the earlier registry-design pass, specifically so this
        correlation would exist) — a mesh's wire entry just needed its
        surface's name added (`u8 wireframe, i32 nameLen, nameLen UTF-8
        bytes, ...`; `""` when unnamed, collapsing with "no name" the
        same way the registry section already does for an unnamed force).
      - **Forces**: connections and arrow samples carry no source-force
        tag on the wire — a real, separate protocol extension, not done
        here. The panel shows "visibility toggle not yet available for
        forces" instead of a checkbox that would do nothing.
      - **Constraints**: §10.2 defines no constraint renderer at all —
        there's nothing to toggle, not just nothing wired up yet. The
        panel shows "no renderer defined for constraints (§10.2)".
      **Composition, not replacement**: a group's checkbox intersects
      with whatever the server's own `visibleIds` already decided — e.g.
      `FlagDebugDemo` sends `visibleIds = poleIds` specifically to hide
      cloth dots because the mesh already shows them, and leaving "cloth"
      checked in the panel must not repaint those 112 dots on top of the
      mesh. A particle draws only if the server permits it *and* nothing
      in the panel hides it.
      **Client-side correctness details**: the outliner's existing
      "skip re-render when unchanged" check used a raw
      `JSON.stringify(registry)` signature, which can't detect a group's
      membership changing (`Set` doesn't serialize meaningfully) — fixed
      by flattening member ids into a sorted array before signing, so
      this stays correct once something (a future emitter) actually
      changes membership between frames, not just today when it never
      does. The mesh-edges-overlay checkbox listener no longer writes
      `overlay.visible` directly — `applyFrame` is now its single writer,
      since a mesh's overlay visibility depends on *both* that checkbox
      and the new per-surface hide toggle, and two independent writers
      to the same property would race.
      **Verified three ways**: `BinaryFrameTest` (+3 — a mesh's surface
      name round-trips, an unnamed surface decodes as `""` not a missing
      field, a group's member ids round-trip alongside its name),
      `SceneRegistryTest` (+2 — member ids resolve eagerly and survive a
      `Groups` mutation afterward, group iteration order matches creation
      order), and a live server-side WebSocket check against a running
      `FlagDebugDemo` confirming the actual wire data (`mesh names=
      [cloth-mesh]`, `mesh triangle counts=[182]`, `registry.groups=
      [(cloth, 112), (pole, 8)]`) matches the scenario exactly. The usual
      no-browser fallback otherwise (`node --check`, `getElementById`
      cross-check, live `curl` of the new panel markup) — Chrome
      automation is still not connected in this environment, checked
      again before starting this piece, so whether clicking an outliner
      entry actually opens the panel and whether the checkbox visibly
      changes what's drawn remains unverified by a human. Full suite now
      262 green.
      **Still separate, real future work**: selection & inspection's live
      numeric readout, the color legend, and a force-visibility toggle
      (needs the source-tag protocol extension noted above).
- [x] **First real human browser testing of this feature — two findings,
      both root-caused by reading the code, not guessed**:
      1. **Toggling "cloth"/"pole" looked inconsistent.** Root cause: in
         `Flag.kt`, every pole-edge particle is a member of *both* the
         "cloth" group (added first, with the rest of the grid) *and*
         the "pole" group (added after) — a pre-existing Phase 1 design
         choice, not introduced by this feature. `FlagDebugDemo` also
         sends `visibleIds = poleIds`, so cloth's other 104 members are
         never drawn as dots regardless of any toggle. Net effect: both
         checkboxes control the *same* 8 dots via the hide-toggle's union
         rule, and whether "cloth" visibly does anything depends on
         "pole"'s current state too — confusing, but not random.
         **Fix (user chose "clarify the panel" over changing `Flag.kt`'s
         group model or leaving it unexplained)**: the group panel now
         shows a live `"N particle(s) — M drawable as a dot right now"`
         line, so the gap between total membership and what's actually
         eligible to draw is visible immediately instead of discovered by
         confused clicking. Updates every frame via a *new, separate*
         `updateGroupPanelInfo()` — deliberately touching only that text
         node, never recreating the checkbox element the way a full
         `renderObjectPanel()` rebuild would. Recreating the checkbox
         every frame was considered and rejected: a WebSocket frame
         arriving between a person's mousedown and mouseup could detach
         the very checkbox the browser is mid-click on, turning an
         occasional click into a silently lost one — a second, subtler
         possible source of exactly the "sometimes works" symptom this
         fix was meant to explain, not something to risk introducing.
      2. **"cloth-mesh: show mesh" didn't hide everything visually
         called "the mesh."** The grid-pattern lines that persisted are
         the structural spring *connections* (`structural.activeConnections()`
         in `FlagDebugDemo`) — a `Force`'s line rendering, entirely
         separate from the `Surface`'s shaded mesh the toggle actually
         controls. The structural force isn't named, so it has no
         outliner entry and no toggle yet — exactly the already-documented
         "forces: visibility toggle not yet available" gap, not a defect
         in the surface toggle itself (which correctly hides only the
         mesh, as designed). **User chose to leave this as a documented
         gap** rather than extend scope now to name/tag forces.
      Also fixed, surfaced by "confirm nothing's stale during testing":
      `ViewerHttpServer` sent no cache headers at all, so a browser could
      silently serve a stale cached copy of the page across a reload —
      added `Cache-Control: no-store`, verified via `curl -D -`.
- [x] **Right-click-to-open**: right-clicking a rendered object in the 3D
      view opens its per-object panel directly, no outliner click needed
      — "the fast path for something already visible" (§10.3's own
      framing). Reuses the same raycast targets §9.4's drag already picks
      against (dots + mesh objects), but resolves differently depending
      on what's hit:
      - **A mesh hit** opens *that* surface's panel directly — unambiguous,
        since a mesh belongs to exactly one `Surface` (`obj.userData.
        surfaceName`, set alongside the existing `triangleVertexIds` when
        each mesh's geometry is built). An unnamed mesh (`""`) has no
        panel to open, so the click is a no-op, consistent with unnamed
        objects being unreachable everywhere else in the outliner.
      - **A dot hit** opens the *most specific* (smallest) named group
        containing that particle, not just any containing group — group
        membership can legitimately overlap (a flag's pole-edge particles
        are members of both "cloth" and "pole"), and the smaller, more
        specific group is the more likely intended target of a click
        directly on it. A particle in no named group at all is a no-op.
      `event.preventDefault()` on every `contextmenu` event regardless of
      hit/miss — this canvas has its own right-click action, so the
      browser's native menu never makes sense here, not just when
      something's actually picked.
      **Verified**: the usual no-browser fallback (`node --check`,
      `getElementById` cross-check, live `curl` confirming the handler
      and `surfaceName` tagging are served) plus the full Kotlin suite
      staying green (262 — this change touches only `viewer.html`,
      no engine code). Whether right-clicking actually opens the correct
      panel in a real browser is for the human tester to confirm, same
      standing caveat as every other piece of this phase's client-side
      work.
- [x] **Selection & inspection**: selecting a group or surface shows a
      live numeric readout, updated every frame. Required a real
      protocol extension first — `BinaryFrame` never transmitted velocity
      at all, only position, even though §10.3 explicitly asks for "a
      particle's position/velocity." Every particle now carries velocity
      unconditionally (`PARTICLE_SIZE` 28 → 52 bytes; the most expensive
      of this frame's per-particle additions so far, since it scales with
      *every* particle, not just a named group's members the way the
      registry section does — worth remembering for a future large-N
      scenario). `DecodedParticle` gained a `velocity` field;
      `ParticleStore.velocity(id)` (already existed, used elsewhere) is
      the source.
      **The readout itself is extended sensibly from "a particle" to
      "whatever's actually selectable today"**, since there's no
      individual-particle selection yet — only groups/surfaces/forces/
      constraints:
      - **Groups**: centroid position + average member speed, computed
        client-side from the (now velocity-carrying) particle list
        filtered to the group's member ids.
      - **Surfaces**: triangle count + average speed across the mesh's
        unique vertex ids (deduplicated from its triangle list, so a
        shared vertex isn't counted — and therefore weighted — more than
        once).
      - **Forces/constraints**: still just the existing informational
        note, now covering both the missing visibility toggle *and* the
        missing inspection data with one honest sentence each — both are
        blocked on the same missing wire piece (no source-tag on
        connections/arrow samples for forces; no renderer at all for
        constraints, §10.2).
      **Same single-writer discipline as the earlier "N drawable" fix,
      deliberately repeated**: `updateInspection()` updates only the
      readout's own text node every frame, never recreating the checkbox
      next to it — recreating an interactive element on a timer this
      tight is exactly the class of bug (a WebSocket frame landing
      between someone's mousedown and mouseup, detaching the checkbox
      mid-click) already root-caused once this phase.
      **Verified three ways**: `BinaryFrameTest` (+1 — a particle's
      velocity round-trips alongside its position; the existing
      particles-and-connections test updated to assert `Vector3.ZERO`
      velocity explicitly rather than silently ignoring the new field),
      the usual no-browser fallback (`node --check`, `getElementById`
      cross-check, live `curl` confirming the new functions are served —
      caught and fixed a self-inflicted verification bug along the way:
      forgot to run `processResources` after editing the HTML before
      starting the demo, so the first `curl` check reported 0 matches
      against a stale build), and a live server-side WebSocket check
      against a running `FlagDebugDemo` confirming actual physical
      correctness, not just wire round-tripping: pole particles
      (`FixedPosition`-constrained) read exactly `0.0` m/s, cloth-only
      particles read `0.03`–`6.97` m/s under wind — a real, physically
      sane spread, not placeholder data. Full suite now 263 green.
- [x] **Color legend**: a small panel (bottom-right) shows the blue→orange
      gradient and what its two ends mean, appearing only when a
      `colorBy` gradient is actually active anywhere in the scene.
      **No protocol change needed** — `BinaryFrame`'s connections already
      carry a resolved `r,g,b` per connection, so "is a gradient active"
      is detected purely client-side: any connection whose color isn't
      exactly `Color.DEFAULT_LINE`'s RGB (`isDefaultLineColor` in
      `viewer.html`, mirroring the Kotlin constant's exact values).
      **A real, explicitly-flagged limitation**: this heuristic is sound
      *today* only because exactly one `colorBy` variant exists
      (`BREAK_PROXIMITY`) — the wire carries no per-connection tag saying
      *which* colorBy produced a color, just the resolved RGB. If a
      second variant (`stretch`/`force` magnitude, both already noted as
      deferred in Phase 9) is ever added, "not the default color" stops
      meaning "breakProximity specifically," and this needs to become
      variant-aware (which gradient, which two labels) — documented
      directly in the code, not left to be rediscovered later.
      **Verified two ways, since no current demo exercises breakProximity
      coloring live** (`FlagDebugDemo` reverted it, per Phase 9's own
      notes above): a new `ColorRampTest` proves `blueOrange`'s output
      never coincides with `DEFAULT_LINE` at any sampled point — not by
      merely checking they're different values, but by confirming
      `DEFAULT_LINE`'s blue channel (1.0) exceeds either gradient
      endpoint's (0.698 max), which bounds the whole interpolated segment
      below it, so nothing on the gradient can ever collide with the
      "uncolored" case the legend needs to distinguish from. Separately,
      a live server-side WebSocket check against a running
      `FlagDebugDemo` confirmed the negative case for real: all 202
      structural connections decode as exactly `Color.DEFAULT_LINE`, so
      the legend would correctly stay hidden — the positive case (does it
      actually *appear* when it should) remains unverified by an actual
      colored demo or a human in a browser. Full suite now 264 green.
- [x] **Time controls**: pause/resume, a speed multiplier, and step-once,
      as a new bidirectional-channel message type (`TimeControlMessage`)
      applied uniformly via a new `TimeControl` class rather than each
      demo reimplementing pause/speed logic independently.
      **§9.1's pacing policy held to literally**: `dt` itself is never
      touched anywhere in this — a speed multiplier changes how many
      whole `dt`-steps `TimeControl.stepsThisFrame` returns per broadcast
      tick, never the size of one step (coarsening `dt` "would silently
      break determinism," the exact thing §9.1 rules out). A non-integer
      multiplier (e.g. 0.33x) still averages out correctly over many
      frames via a fractional `stepDebt` accumulator carried between
      calls, rather than always rounding the same direction every frame.
      **Step-once resolved a real ambiguity deliberately**: it advances
      one *nominal frame's* worth of steps (`stepsPerFrame`, ~16 at
      60fps/`dt=1e-3`), not one raw `dt`-step — a single 1e-3s step is
      visually imperceptible, and step-once exists specifically to be a
      visible debugging aid. It also **always leaves the engine paused
      afterward**, regardless of whether it was already paused or
      running when clicked — "step" means "advance by exactly one
      visible increment, then hold," one predictable outcome either way,
      not two different behaviors depending on prior state. Multiple
      queued step-once clicks each advance one more frame (not
      coalesced).
      **Threading mirrors `DragMessageQueue`'s existing model**: the
      WebSocket I/O thread calls `TimeControl.apply` as messages arrive,
      the physics loop's own thread calls `stepsThisFrame` once per
      broadcast tick — `paused`/`speedMultiplier` are `@Volatile` for
      that cross-thread visibility, `stepDebt` is untouched by `apply`
      so it needs no synchronization of its own.
      **Wired into `FlagDebugDemo` only** (this phase's established
      pattern — every other Phase 10 feature was proven on this one demo
      before wider rollout): `onTextMessage` now tries *both*
      `DragMessage.parse` and `TimeControlMessage.parse` on every
      incoming message, since the same channel now carries two kinds of
      viewer input, not just drag targets.
      **Viewer UI**: play/pause and step buttons plus five speed presets
      (0.25x–4x) in `#controlPanel`. `isPaused` is tracked purely
      client-side (no server-authoritative pause-state readback — fine
      for a single-viewer debug tool, a second concurrent viewer would
      show its own guess) so the stats panel can show `"— (paused)"`
      instead of a misleadingly growing lag number while paused, and
      `resetStats()` is called on resume so the paused duration itself
      never counts as "falling behind."
      **Verified three ways**: `TimeControlMessageTest` (5) and
      `TimeControlTest` (7) — including that step-once wins over both an
      active pause *and* an active speed multiplier, that pausing
      doesn't disturb `stepDebt` (it resumes exactly where it left off),
      and that a fractional multiplier's *average* rate over 1000
      simulated frames matches the theoretical rate within 10 steps.
      The usual no-browser fallback (`node --check`, `getElementById`
      cross-check, live `curl`). And a live server-side WebSocket check
      against a running `FlagDebugDemo` sending real pause/resume/
      set_speed/step_once commands and reading the engine's actual
      `step` counter back: pause produced an *exact* zero step delta
      over a full second, step-once advanced by *exactly* 16 (matching
      `stepsPerFrame` precisely), the engine stayed paused immediately
      after step-once, and running at 4x produced *exactly* 4.0x the
      step-rate measured at 1x (3072 vs 768) — the proportional scaling
      is the load-bearing signal here, not the absolute rate, which fell
      short of the theoretical ~1000 steps/s at 1x for reasons unrelated
      to `TimeControl` (the demo's own `stepsPerFrame = ((1.0/60)/dt)
      .toInt()` truncates 16.67 down to 16, a pre-existing, unrelated
      rounding artifact, plus ordinary sampling jitter in a 1-second
      window) — worth knowing about but not this feature's bug to fix.
      Full suite now 276 green.
      **Still not done, matching Phase 10's own last-resort framing**:
      whether the buttons are actually usable and whether pausing/
      stepping *feels* right in a real browser is for a human to confirm
      — same standing caveat as every other client-side piece of this
      phase. Not wired into any demo besides `FlagDebugDemo`.
      **Follow-up, from real user testing**: the user reported "none of
      the playback buttons work" after running various demos from
      IntelliJ — true for every demo except `FlagDebugDemo`,
      `ParticleCollisionDebugDemo`, and `DragDebugDemo` (the only three
      that had ever been individually wired), exactly the gap the note
      above already flagged as a real risk. Fixed by extracting a shared
      `ViewerInput` class (parses `TimeControlMessage`/`DragMessage`/
      `SceneControlMessage` and routes each to its own queue/handler) and
      using it in *every* debug demo — `DebugRendererDemo`,
      `BallBounceDebugDemo`, `SparksDebugDemo`, `TrampolineDebugDemo`,
      `MultiShapeDebugDemo` gained pause/speed/step-once for the first
      time; `FlagDebugDemo`/`DragDebugDemo`/`ParticleCollisionDebugDemo`
      had their own hand-rolled `onTextMessage` dispatch consolidated
      onto the same shared class instead of three near-identical private
      copies. This closes the actual failure mode, not just this one
      report of it: a *new* demo now gets pause/speed/step-once/drag/
      scene-control for free from its first `DebugRenderer(onTextMessage
      = viewerInput::onTextMessage)` line, rather than being one missed
      wiring step away from silently-dead playback buttons the way six
      of eight demos turned out to be. Verified live in a real browser
      against `SparksDebugDemo` (previously totally unwired): pause froze
      `t`/`step`/`particles` exactly across a 2s wait, resume advanced
      them and resumed spawning new sparks. 332 tests still green (no
      new tests — `ViewerInput` is pure composition of three already-
      tested parsers/queues, nothing new to verify in isolation).
- [x] Removable colliders + scene restart from the viewer (§10.3, new
      requirement) — a third kind of viewer-to-engine input,
      `SceneControlMessage` (`remove_collider`/`restart`), alongside
      `DragMessage` (per-particle input) and `TimeControlMessage`
      (playback pacing): this one mutates the *scene* itself. Requested
      directly by the user after trying `ParticleCollisionDebugDemo`
      (Phase 5) — "I'd like the ability to remove the walls and to
      restart the simulation from within the viewer," a natural follow-on
      to a demo whose whole premise (walls containing a ball pile) begs
      "what does it do without them?"
      The outliner gained a fifth section, COLLIDERS, listing every named
      `Collider` from the existing wire-protocol section (§10.2's earlier
      collider-wireframe work) with a per-item "remove" action in its
      object panel — clicking it sends `remove_collider` and closes the
      panel optimistically, same immediacy as every other button on this
      page. A new "↺ restart" button sits next to pause/step.
      `SceneControlMessageQueue` mirrors `DragMessageQueue` exactly and
      for the same reason: both a restart and a collider removal mutate
      state (`ParticleStore`, `Groups`, the live collider list) that the
      physics loop's own thread is concurrently reading mid-step, so
      applying either directly from `DebugServer`'s WebSocket I/O thread
      would be a real race — messages are queued and only drained/applied
      once per step, on the physics thread. `ParticleCollisionDebugDemo`
      tracks a canonical (never-mutated) full collider+rule set and a
      live, mutable one; removal filters the live set and rebuilds
      `CollisionSystem` from it (cheap - it holds no expensive
      precomputed state); restart resets the live set back to the
      canonical one *and* replaces `store`/`groups` wholesale with fresh
      instances (old particle ids are meaningless after a restart, not
      reused) and re-seeds the spawn RNG so a restart reproduces the
      exact same run, not a continuation of wherever the random stream
      happened to be.
      4 new `SceneControlMessageTest` parser cases. Verified directly in
      a real Chrome browser (now connected for this session): removed
      all four walls one at a time via the outliner, watching each
      disappear from both the wireframe and the list immediately, then
      watched the pile — already at rest under friction — stay
      completely still with nothing containing it anymore (a nice
      incidental confirmation friction is doing real work, not just
      looking settled); then clicked restart and confirmed the scene
      came back at `t=0`/`step=0` with all 5 colliders restored and balls
      spawning fresh from the top. Scoped to `ParticleCollisionDebugDemo`
      only, matching Phase 10's established "prove on one demo before
      wider rollout" pattern (`TimeControl` did the same before spreading
      further) — no other demo currently has removable colliders or a
      restart affordance.
- [x] Self-registering per-entity-type UI modules + per-force arrow
      visibility toggle (§10.3, new requirement — promoted out of
      `[stretch]` for a concrete first consumer, same pattern as friction
      and removable colliders before it) — user asked for both "the
      ability to show/hide the wind vectors on the flag demo" and, more
      generally, for each entity kind's viewer UI to be "a self-contained
      module that registers itself with the viewer" instead of one
      hardcoded `if/else` dispatch every kind lives inside. Built both
      together rather than either alone, using the wind toggle as the
      new architecture's real first consumer instead of a toy example.
      **Wire protocol change first**: `frame.arrows` was a flat
      `List<ArrowSample>` with no source tag, so a "per-force" toggle
      without one would really be "hide every arrow, mislabeled" the
      moment a scene had two arrow-emitting forces — exactly the gap an
      existing code comment already flagged ("needs a source-tag protocol
      extension"). Added `particlesim.render.NamedArrowSamples(name,
      samples)`; `BinaryFrame`'s arrow section is now grouped by force
      name (`arrowGroupCount * {name, sampleCount, samples}`) instead of
      one flat list — same `""`-for-unnamed convention `DecodedMesh`
      already established, so an unnamed arrow-emitting force still draws
      but isn't individually toggleable. `DebugRenderer.broadcast` and
      `FlagDebugDemo` updated to the new `arrowGroups` param (`wind.name`
      is `"wind"`, set by `buildFlag`). New `BinaryFrameTest` cases cover
      a single named group, an empty default, and multiple groups
      (including one unnamed) round-tripping independently.
      **Client-side module registry**: `registerEntityKind(module)`
      lets a kind declare `{kind, getNames(registry), renderPanel(),
      updateLive()?}` and claims the outliner section the page's HTML
      already declares for it (`#outlinerGroups`, `#outlinerForces`, …)
      rather than building DOM from scratch — registering is purely
      additive to what the page already renders, not a reshuffling of
      section order/headers. `updateOutliner`/`renderObjectPanel` now
      loop over registered modules instead of hardcoding every kind by
      name.
      **Scoped to two modules, not all five**, on advisor review before
      writing code: migrating "groups" (a toggle *plus* live info text
      *plus* live inspection — exercising every hook a module can have)
      and "forces" (the actual new toggle) proves the shape without
      risking three already-working panels (constraints/surfaces/
      colliders) in one big-bang pass for no functional gain. Those three
      stay on the pre-existing hardcoded `if/else` path in
      `renderObjectPanel`/`updateOutliner` — genuinely unmigrated, not
      secretly broken; noted here rather than left silently inconsistent.
      A force's toggle is only real when it has arrow samples this frame
      (`latestArrowGroups`) — a force with no arrow renderer (e.g. plain
      uniform gravity) still gets the old informational note instead of a
      checkbox that would silently do nothing.
      **Verified live in Chrome** against a freshly relaunched
      `FlagDebugDemo`: opened the "wind" force's panel (new, previously
      just a note) and confirmed a "show arrows" checkbox appears;
      unchecking it removed every wind arrow from the scene while the
      cloth mesh, pole dots, and scripted camera kept rendering
      unaffected (ruling out "hides everything" masquerading as "hides
      arrows"); re-checking brought them back. Also re-verified the
      migrated "groups" module (cloth's toggle + live "N drawable" text +
      live centroid/avg-speed all updated correctly) and confirmed the
      unmigrated surfaces/constraints panels still render exactly as
      before (no regression from the module-lookup fallback path). No
      console errors on a fresh page load or after interaction.
- [x] Migrate constraints/surfaces/colliders to the self-registering
      entity-kind module system above — no longer `[stretch]`/low-reward:
      §10.4's live-editing spec (below) needs a real per-object panel for
      colliders and constraints (activation toggle, numeric parameter
      controls), which the old hardcoded dispatch path isn't built to
      carry. This is now a real prerequisite for that work, not deferred
      mechanical cleanup with no consumer.
      Done in `b445b778` ("Migrate constraints/surfaces/colliders to
      self-registering entity-kind modules") — pure code-move, no behavior
      change, verified live in Chrome across `FlagDebugDemo` (groups,
      forces, constraints, surface mesh) and `SpatialGridDebugDemo`
      (collider active toggle + remove). Checkbox was left stale until now;
      the code has been merged since 2026-08-26.
- [x] Live parameter tweaking (viewer writes back into a running
      entity's numeric parameters) — fully specified via a direct
      entity-by-entity walkthrough with the user (requirements.md §10.4).
      Implemented on the `viewer-live-editing` branch, in this order:
    - [x] Collider activation: `Collider.active` (mutable, `contact()`
          returns `null` unconditionally while inactive, a single choke
          point every existing caller already goes through). Hidden while
          inactive too — `DebugRenderer.broadcast` filters the wireframe
          draw list to `active` colliders before it reaches
          `BinaryFrame.encode`; the *registry* section carries every
          collider's name + active flag regardless, specifically so a
          deactivated collider's own outliner entry (and its reactivate
          checkbox) never disappears alongside its wireframe. Write path
          reuses the existing `SceneControlMessage`/`SceneControlMessageQueue`
          channel (`SetColliderActive`) rather than a new message type -
          this is the same "scene mutation" category `RemoveCollider`/
          `Restart` already live in. **Verified live in Chrome**
          (`SpatialGridDebugDemo`, 2000-ball sealed box): unchecking
          "floor"'s new "active" checkbox removed its wireframe from the
          scene and the entire ball cloud immediately poured out through
          the now-open bottom face - the "pile falls straight through,
          not a case this guards against" behavior from the spec, not
          just a UI checkbox with nothing behind it. Re-checking it
          restored the wireframe and re-contained the balls. No console
          errors.
    - [x] Group enable/disable: `Groups.isEnabled`/`setEnabled`, a
          *separate* flag from `membersOf` (deliberately - filtering
          `membersOf` itself would blank a disabled group out of the
          registry snapshot and the dual-selection lookup a 3D click
          resolves through, stranding the very checkbox meant to turn it
          back on). Gated at every per-step call site that resolves a
          named group each step: `UniformGravity`/`Drag`/`NBodyGravity`/
          `ConstantForce`'s `accumulate`, `FixedPosition`/`FixedVelocity`'s
          `applyPosition`/`applyVelocity`/`pinnedIds`,
          `CollisionSystem.resolve`, `SurfaceCollisionSystem.resolve`, and
          `ParticleCollisionSystem.candidatePairs` (either side disabled →
          the whole rule contributes no pairs that step). Deliberately
          *not* gated: `MeshSprings`/`Spring`/`Damper` (built once from a
          fixed particle-id pair, not a live group lookup) and `Wind`
          (bound to a fixed triangle list) - disabling a group does not
          freeze its own structural springs or wind loading, an accepted
          gap, not an oversight (see `Groups.isEnabled`'s doc comment). A
          disabled group's members stay ordinary particles otherwise -
          base integration isn't gated by group membership at all, so
          they keep coasting on residual velocity, exactly like a
          deactivated collider's "no special-casing, it just falls
          through" stance. Write path: `SceneControlMessage.SetGroupEnabled`,
          same channel as collider activation. **Verified live in
          Chrome** on the same running demo: unchecking "balls"'s new
          "enabled" checkbox made the entire 2000-particle cloud pass
          straight through every wall of the box *and* through each
          other (both `CollisionSystem` and `ParticleCollisionSystem`
          skipped it in the same step) - re-checking it recovered normal
          containment. Component test coverage:
          `ForceComponentTest`'s "group disable" test (gravity
          contributes zero force while disabled, resumes when
          re-enabled) and `GroupsTest`'s enable/disable tests (including
          that `membersOf` itself is untouched by disabling).
    - [x] Numeric field editing: a new opt-in `EditableFields` capability
          interface (`particlesim.physics`, alongside `UniformFieldForce`/
          `Breakable`) - `editableFields(): Map<String, FieldValue>` (read)
          and `setField(field, value): Boolean` (write, returns `false`
          for an unknown field name or wrong value kind rather than
          silently no-op'ing - a client typo is detectable, not
          swallowed). `FieldValue` is `Scalar(Double)` or `Vector(Vector3)`
          - the only two shapes anything editable needs. Implemented on
          `UniformGravity.acceleration`, `NBodyGravity.g`/`softening`,
          `Wind.density`, `FixedVelocity.velocity` (each field promoted
          from `private val` to `private var`); deferred: `Spring`/
          `Damper` stiffness/damping (need the group-ownership scan first)
          and `FixedPosition` (the shared-vs-per-particle split needs its
          own decision about what the panel shows when `position ==
          null`). Read path: a new flat per-frame wire section, one entry
          per `(kind, name, field) -> current value` for every named
          force/constraint that implements `EditableFields` - recomputed
          fresh every frame directly off the live object, never cached,
          so one client's edit shows up in every other connected client's
          next frame for free. Write path: `SceneControlMessage
          .SetScalarField`/`SetVectorField`, same channel as the
          activation/enable toggles above. **Client-side pitfall caught
          before it shipped**: numeric field *values* are deliberately
          excluded from `registrySignature` (only field *names/kinds* would
          matter there, and nothing currently needs even that) - values
          change on every edit, and if the panel rebuilt on every value
          change it would destroy and recreate the very input the user is
          mid-keystroke in. Values instead refresh via each panel's
          `updateLive` hook, which skips an input that currently has
          focus (`document.activeElement !== input`), the same "never
          recreate what the user might be interacting with" discipline
          the groups panel's checkbox already established. **Verified
          live in Chrome**: named `ParticleCollisionDebugDemo`'s gravity
          force `"gravity"` (previously unnamed) and added it to that
          demo's registry - opened its panel, saw the acceleration vector
          pre-filled `(0, -9.8, 0)`, edited Y to `15` (flipping gravity
          upward with no ceiling collider in this demo) and watched the
          entire 18-ball pile rocket off-screen; typing in the field was
          never clobbered by the ~60fps live-data refresh; restarting the
          demo correctly reset the field back to its authored `-9.8`. No
          console errors. Component + wire round-trip test coverage:
          `ForceComponentTest`'s four `EditableFields` tests (per-class
          get/set, including rejecting a wrong value kind and an unknown
          field name) and `BinaryFrameTest`'s three new round-trip tests
          (collider active-flag persists in the registry regardless of
          the wireframe list, group enabled state round-trips, and an
          `EditableFields` force/constraint's current values round-trip
          as the flat field-entry list; a force with no `EditableFields`
          contributes nothing).
          **Scalar path separately verified live in Chrome** (the vector
          verification above never exercises the JS decoder's scalar
          branch, which has its own manual offset arithmetic distinct
          from the vector branch's - a wrong offset there wouldn't throw,
          it would silently misparse every section that follows in the
          same frame): relaunched `FlagDebugDemo`, opened the "wind"
          force's panel, confirmed `density` was pre-filled at its
          authored `1.2`, edited it to `15` and watched the flag stiffen
          into a near-flat pose under the much stronger wind pressure -
          and confirmed the mesh, arrow samples, and event log all still
          rendered normally afterward (the real assertion: proves the
          scalar entry's offset arithmetic didn't corrupt the wireframe
          collider/event sections that decode after it). No console
          errors either run.
    - [x] Pause on edit: a client-only `pauseOnEdit` toggle plus a
          `sendEdit()` wrapper (used by every §10.4 edit call site instead
          of `send()` directly) that sends the existing pause
          `TimeControlMessage` before the edit itself whenever the toggle
          is on and the sim isn't already paused - zero server-side work,
          exactly as scoped. **Verified live in Chrome**: with the toggle
          checked, deactivating a collider immediately flipped the
          play/pause button to "play" and steps/s dropped to 0 - the edit
          and the pause both visibly took effect from one click.
    - [x] Module migration: constraints/surfaces/colliders now render via
          the same self-registering `entityKindModules` system
          groups/forces already used, rather than the old hardcoded
          per-kind branches in `renderObjectPanel`/`updateOutliner`/
          `updateInspection` - a pure code-move, no behavior change
          intended (each module's `renderPanel`/`updateLive` body is the
          same code that used to live in the hardcoded branch).
          `updateInspection`/`renderObjectPanel` collapse to a single
          `entityKindModules.get(selectedKind)` lookup with no more
          per-kind branching, and the now-redundant `outlinerSections`
          map is gone. **Verified live in Chrome, across two demos since
          no single existing demo wires every kind into its `broadcast()`
          call**: first tried `MultiShapeDebugDemo`, which turned out to
          call `renderer.broadcast(t, step, store, allIds, connections())`
          with no `registry`/`meshes`/`colliders` arguments at all - every
          outliner section is empty on that demo regardless of this
          migration, a pre-existing gap unrelated to this change, not a
          regression. Switched to `FlagDebugDemo` (wires groups/forces/
          constraints/a surface mesh) - outliner populated, panels for
          all four kinds opened correctly via both an outliner click and
          a right-click in the 3D view. Then `SpatialGridDebugDemo` (has
          a named "floor" collider) to confirm the migrated collider
          panel specifically - active checkbox, shape info, and remove
          button all worked. No console errors either run.
    - [~] Particles/groups: dual selection, per-particle mass/radius
          editing, and the particle panel's own module shape are now
          implemented and verified live in Chrome, across four rounds of
          direct design walkthrough with the user (multi-group selection,
          the `entityKindModules`/outliner-shape gap, mass/radius storage,
          and wire addressing) followed by one implementation pass. A
          group's tab surfacing spring/damper params for every
          fully-contained Spring/Damper/MeshSprings is now built too (see
          the dedicated bullet below); still not built: per-particle
          render override, per-group color override + visibility toggle.
          - **Wire**: `BinaryFrame`'s per-particle payload grew from 52 to
            68 bytes - `mass`/`radius` (`f64`, radius `NaN` when unset,
            matching `ParticleStore`'s own sentinel) travel unconditionally
            alongside `id`/position/velocity for every live particle every
            frame, the same precedent already established for why velocity
            itself is unconditional ("so selection & inspection has
            something to read without a second wire section keyed by id").
            Selection-scoped emission was considered and rejected -
            `DebugRenderer.broadcast` sends one identical buffer to every
            connection today, and scoping mass/radius to whichever particle
            one client has selected would mean building per-client frame
            customization from scratch for this one panel. This radius is
            physics/collision (`ParticleStore.radius`), independent of the
            pre-existing `sphereRadii` *render*-size section - editing one
            doesn't move the other, confirmed as an accepted gap rather
            than a bug (a demo with an explicit `sphereRadii` override,
            e.g. `FlagDebugDemo`'s pole spheres, would show no visible
            change from a radius edit, though no demo wiring particle
            edits currently has one). Write path is a new id-addressed
            `SceneControlMessage.SetParticleScalarField(particleId, field,
            expr: ScalarExpr)`, distinct from the name-addressed
            `SetScalarField`/`SetVectorField` (which carry an
            already-evaluated value, not an expression string) - parsed via
            `ExpressionParser.parseScalar` inside `SceneControlMessage
            .parse` itself, returning `null` (dropping the message) on
            `ExpressionException`, the same stance every other malformed
            message already gets. No central dispatcher exists to plug
            into (confirmed by grepping every `SetScalarField` call site) -
            `ParticleCollisionDebugDemo`, `DragDebugDemo`, and
            `SpatialGridDebugDemo` each got their own `when (message)`
            branch, though the branch body is uniform across all three
            (`store.setMass(message.particleId, message.expr, t)`/
            `setRadius`, no per-entity name matching needed since particles
            are id-addressed).
          - **`ParticleStore.setMass`/`setRadius`**: a full replace of the
            particle's stored `ScalarExpr` (via the same `setOrClearExpr`
            promotion/demotion `create` already uses), evaluated once at
            `t`, rejecting (`false`, no mutation) on NaN/Infinite, and for
            mass specifically on non-positive too - deliberately not
            `evaluateMassGuarded`'s throw-for-constant/clamp-for-dynamic
            split, which is right for authoring/per-step re-evaluation but
            wrong for live-edit input arriving over the wire. `setRadius`
            has no positivity guard, matching the absence of one anywhere
            else in this class. Component-tested (`ParticleStoreTest`) and
            wire-round-tripped (`BinaryFrameTest`).
          - **The edit input is an expression string** (a plain `<input
            type="text">`, not `type="number"`), parsed server-side rather
            than sent as a bare double - `"2.0 + 0.1*sin(t)"` is exactly as
            valid an edit as `"5.0"`, which is what actually resolved the
            original "replace outright vs. overlay a time-varying
            expression" question: a full replace *is* the time-variance-
            preserving option once the replacement can itself be dynamic,
            so no override layer was ever needed. The panel always shows
            the live evaluated number, never the original source text
            (`ParticleStore` retains none, matching how a force/
            constraint's `editableFields()` already behave) - re-editing a
            currently-dynamic mass/radius means typing a fresh formula
            blind, an accepted tradeoff over adding per-particle string
            storage for just that one display case.
          - **The particle panel** (`registerEntityKind({kind: "particles",
            ...})`, no `getNames` - see below) shows live id/position/
            velocity, the two editable fields above, and every containing
            group as a clickable link (ordered smallest-membership-first,
            reusing the exact heuristic the old right-click behavior used
            to pick a single winner, without forcing that single choice) -
            resolving the multi-group question as "pick one deterministic
            order, let the user reselect from the particle's own panel,"
            not "render every containing group's panel at once" (the
            object panel is single-selection top to bottom;
            `objectPanelInfoEl`/`objectPanelInspectEl` are singleton
            globals, one `renderPanel()`/`updateLive()` call per
            selection - simultaneous multi-panel rendering would need real
            structural change for this one case).
          - **`getNames` is now optional** on the `entityKindModules`
            contract (updated in both this file and `requirements.md`) -
            "particles" is the one kind with no outliner section at all
            (3D-pick-only, N potentially in the thousands), reachable only
            by something setting `selectedKind`/`selectedName` directly.
            `updateOutliner`'s per-module render loop just skips a module
            with no `getNames`.
          - **Right-click on a particle now opens its own panel**, not its
            disambiguated group's - a deliberate change to the previously-
            shipped, previously-Chrome-verified group-opening behavior,
            following directly from §10.3's literal "right-clicking a
            rendered object opens its per-object panel directly" (a dot is
            a rendered object) and from the particle-panel-lists-groups
            design above (which presupposes landing on the particle panel,
            not the reverse). Nothing became unreachable - the outliner
            still lists every group by name - and a particle in zero
            groups, previously silently uninspectable at all, now just
            opens its own panel with an empty group list.
          - **Selection clears when the selected particle is destroyed**,
            via the wire's existing `particleDestroyed` event, mirroring
            how `updateOutliner` already clears a removed-and-selected
            collider. Implementing this surfaced a real pre-existing gap
            in two demos: `ParticleCollisionDebugDemo` and
            `SpatialGridDebugDemo` both deleted a particle
            (`SceneControlMessage.DeleteParticle`) without ever emitting a
            `SimEvent.ParticleDestroyed` onto the wire (unlike
            `DragDebugDemo`, which already did) - so a destroyed particle's
            panel just froze on stale data instead of closing. Fixed in
            both, matching `DragDebugDemo`'s existing pattern.
          - **Verified live in Chrome**: `FlagDebugDemo` for the read/
            selection side (right-clicking a cloth particle opens its own
            panel; right-clicking the pole-edge column - a member of both
            "cloth" and "pole" - shows both as separate links, and clicking
            one switches to that group's panel) and `SpatialGridDebugDemo`
            for the write side (editing a ball's mass to a plain number
            visibly changed its collision behavior and the field kept
            showing the live value; editing it to a time-varying expression
            like `"2 + sin(t)"` was accepted and visibly ticked over time;
            `"0"`/`"-1"` were rejected with the field snapping back, no
            console errors; double-click-deleting the selected ball now
            closes its panel instead of leaving blank input fields). Also
            confirmed, separately from this feature: `FlagDebugDemo` has
            never drained `sceneControlQueue` at any point in its git
            history, meaning an earlier TODO entry's "verified live in
            Chrome against FlagDebugDemo" claim for wind-density field
            editing could not have exercised the write path it claimed to -
            flagged here as a correction to the historical record, not
            fixed (out of scope for this round; `FlagDebugDemo` still can't
            process any `SceneControlMessage`, including collider/group/
            field edits).
          Still unchecked: whether a connection (`(id, id)` pair) is
          name-tagged back to its owning Spring/Damper/MeshSprings on the
          wire - needed for "every spring/damper where all endpoints
          belong to this group" and not yet confirmed one way or the other.
    - [x] Two follow-on fixes from using the particle panel above, raised
          directly by the user rather than found independently:
          - **Particle radius and render radius unified.** The viewer's
            dot scale used to come only from an opt-in, author-declared
            `sphereRadii` map (§10.2), entirely independent of
            `ParticleStore.radius` - so editing a particle's radius
            (§10.4, previous entry) changed nothing visible for any demo
            using it. Root cause, found by checking rather than assumed:
            `ParticleCollisionDebugDemo`/`SpatialGridDebugDemo` both
            computed `sphereRadii` from a *static* `ids.associateWith {
            radius }` (a hardcoded local constant, re-sent unchanged
            every frame), never reading the live per-particle value back
            out of the store. Fix: the client now falls back to the
            particle's own live `radius` (already on the wire, unchanged
            from the previous entry) whenever no explicit `sphereRadii`
            entry exists for that id, and both demos' now-redundant
            static maps were removed - an edited radius is immediately
            visible with no further wire changes needed. `FlagDebugDemo`'s
            pole spheres and `TrampolineDebugDemo`'s rim/ball spheres keep
            their explicit overrides untouched (checked, not assumed:
            those particles genuinely have no `ParticleStore` radius of
            their own - pinned anchors, not collidable spheres - so the
            override is still load-bearing, not redundant).
          - **A selected surface reveals its own vertices as dots.** A
            mesh-only particle (the flag's cloth, hidden via `visibleIds`
            since the mesh already shows it) had no dot at all to
            right-click, making it unreachable through the particle panel
            despite `ParticleStore.radius` having nothing to do with it -
            purely a §10.2 mesh-only-rendering visibility question, not a
            radius one, contrary to how the request was first framed.
            Fix is entirely client-side (every particle's position already
            travels in the wire regardless of visibility): while a surface
            is the current selection, its vertex ids are added to the
            visible set, overriding both the server's own `visibleIds`
            exclusion and any `hiddenGroups` checkbox - an explicit "let
            me get at this surface's particles" action wins over an
            unrelated visibility preference. The surface panel's live
            inspection text notes this so it isn't a silent surprise.
            Deselecting the surface hides them again automatically (recomputed
            fresh from the current selection every frame).
          **Verified live in Chrome** against `FlagDebugDemo` (selecting
          the cloth surface revealed its particles as dots, one was
          right-clicked and its own panel opened; a pole/flag particle's
          empty radius field renders as empty, not a stray value; the
          pole's small anchor spheres are unaffected) and
          `SpatialGridDebugDemo` (editing a ball's radius now visibly
          resizes its dot).
    - [x] Connection name-tagging on the wire - resolves this section's own
          long-standing open question ("whether a connection is name-tagged
          back to its owning Spring/Damper/MeshSprings"), built as pure
          infrastructure ahead of its actual consumer (a group's tab
          surfacing "every spring/damper where all endpoints belong to
          this group," still not built - same "infra before UI" pattern
          `EditableFields` itself followed). `BinaryFrame.encode` gained
          `connectionNames: Map<Pair<Int, Int>, String> = emptyMap()`,
          keyed and defaulted exactly like the existing `lineColors` -
          including that map's same limitation: two different named
          forces sharing one connection pair collide, last-writer-wins.
          Checked rather than assumed that this doesn't matter today -
          grepped every demo's springs/dampers for a shared pair, found
          none (`DragDebugDemo`'s dampers are unnamed and never drawn as
          their own line; nothing else names both a spring and a damper on
          the same pair). Wire adds one `i32 nameLen` + UTF-8 bytes per
          connection (empty string = untagged, same convention as an
          unnamed mesh's own `nameLen == 0`). **Verified live in Chrome**
          against `DragDebugDemo` (its spring chain already names each
          link `"link-0"`, `"link-1"`, ...) - logged the decoded
          `frame.connections` array directly (removed after verifying,
          not shipped) and confirmed real `forceName` values decoded
          correctly with no corruption to sections that decode after the
          connection list in the same frame, the same specific risk the
          original scalar-field wire-path bug taught this project to
          check for rather than assume away.
    - [x] Group's spring/damper tab - the connection-naming infra's actual
          consumer, built and verified live in Chrome. `Spring` exposes
          `extensionStiffness`/`compressionStiffness`, `Damper` exposes
          `extensionDamping`/`compressionDamping`, `MeshSprings` exposes
          all four, via `EditableFields` - deliberately not the base
          `stiffness`/`damping` constructor defaults, which are never read
          again once the extension/compression pair is set and would be a
          silent no-op to edit. `buildFlag`'s three `MeshSprings` are now
          named (`structural`/`shear`/`bend`); `FlagGoldenTest`/
          `FlagYamlParityTest` re-run clean after that change, not assumed
          safe. Client computes "fully contained" per force name by
          grouping this frame's tagged `connections` by `forceName` and
          checking every one's both endpoints are in the selected group's
          `memberIds` - a name with zero tagged connections this frame
          never appears at all (a broken `MeshSprings` or an untagged
          force), avoiding the vacuous-truth trap of "every connection in
          an empty set is inside the group." `renderEditableFields`/
          `updateEditableFieldsLive` (previously always reading/writing
          one shared `currentFieldInputs` global) now take an explicit
          inputs-store parameter, so the group panel can track several
          named forces' fields independently in one panel without the
          last one clobbering the others; existing forces/constraints
          call sites pass `currentFieldInputs` explicitly to keep their
          old behavior. Only `structural` ever appears in the cloth
          group's tab, not `shear`/`bend` - `FlagDebugDemo` only ever
          draws `structural`'s connections as lines (a pre-existing,
          deliberate choice against visual clutter), so the other two
          never get wire-tagged connections to check containment against;
          they're still named now and appear individually in the outliner
          forces list with their own editable fields, just not in a
          group's tab. Two real bugs surfaced during Chrome verification,
          both fixed: (1) `FlagDebugDemo` had never drained
          `sceneControlQueue` at all (confirmed via git history) - every
          §10.4 edit sent to it, not just this feature's, silently
          reverted every frame because nothing ever applied it; added the
          dispatch loop, resolving force/constraint targets generically by
          name rather than one hardcoded check per force. (2) the
          outliner/object-panel column and the bottom-left control panel
          were two independently `position: fixed` elements with no
          awareness of each other's height, so this feature's extra
          content ran the object panel into the control panel; wrapped
          both in a flex column (`#leftColumn`) so they share the
          available vertical space and the object panel scrolls
          internally instead of overlapping.
    - [x] `FixedPosition`'s shared-position variant made editable
          (requirements.md §10.4: "only the shared-position variant... is
          editable. The per-particle-pinned variant... is view-only").
          `FixedPosition` now implements `EditableFields`, exposing a
          `position` vector field only when `perParticlePosition == null`;
          `setField` rejects (`false`) on the per-particle variant, an
          unknown field, or the wrong value kind, same discipline as every
          other `EditableFields` implementer. Wiring through
          `collectEditableFields`/`DemoScene.target`/`applyEditableFieldMessage`
          needed no changes - both are already generic over any
          `Constraint` that happens to implement `EditableFields`.
          Component-tested (`ConstraintTest`): the shared variant's field
          round-trips and rejects a wrong-kind/unknown field; the
          per-particle variant (`atCurrentPositions`) reports an empty map
          and rejects any `setField` call. `BinaryFrameTest` re-run clean
          after the change (its existing `FixedPosition("pole", ...,
          name = "pole-anchor")` fixture is the shared-position variant, so
          it now emits one more registry field entry) - not assumed safe.
          No demo had a *named* shared-position `FixedPosition` to verify
          against (every existing one is either unnamed or
          `atCurrentPositions`) - named `DragScene`'s single-particle
          "anchor" constraint (`name = "anchor"`) for this, and gave
          `DragScene` its first real `SceneRegistry` (it previously sent
          none at all, so nothing in it was outliner-reachable) plus wired
          `fixedConstraints` into `applyEditableFieldMessage` (previously
          `emptyList()`). **Verified live in Chrome** against the `drag`
          scene: "anchor" appears under CONSTRAINTS, its panel shows
          `position: 0, 4, 0`; editing the y field to `6` immediately
          snapped the anchor there, which was forceful enough to break
          `link-0`'s spring (logged in EVENTS) - real proof the edit
          reached the running simulation, not just the display. No console
          errors.
    - [x] Surfaces' mesh-style toggle (shaded vs. wireframe, requirements.md
          §10.4). Resolved as **viewer-local, not a new wire message** -
          unlike collider/group activation, render style can't affect
          physics and doesn't need syncing across clients, the same
          principle that already keeps manual camera control off this
          interface (see this file's CLAUDE.md-level framing). `FieldValue`
          being `Scalar`/`Vector` only (no `Boolean`) was a signal to keep
          this off the `EditableFields` mechanism entirely, not an
          obstacle to add a third variant for. Implementation: a
          `surfaceWireframeOverride: Map<name, boolean>` alongside the
          existing `hiddenGroups`/`hiddenSurfaces`/`hiddenForces` viewer-
          local sets; the per-frame mesh material pick
          (`obj.material = ... ? wireframeMeshMaterial() :
          solidMeshMaterial()`) now checks the override first, falling back
          to the server-authored `meshDecl.wireframe` default. The
          surfaces panel gained a "wireframe" checkbox next to "show mesh",
          checked state reflecting the *effective* style (override if
          present, else the server default) at render time - no
          `updateLive` refresh, matching the existing precedent of the
          collider panel's "active" checkbox (also set once per selection,
          not live-refreshed). Falls back to `false` if `latestMeshes` has
          no entry for the selected surface this frame (can't happen for
          any demo today - every scene sends its one named surface's mesh
          unconditionally every frame, and no `SurfaceRenderer(...,
          wireframe = true)` exists anywhere in this codebase to make the
          fallback's value even visible - but worth revisiting if a future
          demo ever constructs one wireframe-by-default). **Verified live
          in Chrome** against the
          `flag` scene's "cloth-mesh": checking "wireframe" switched the
          mesh from solid blue shading to wireframe-only rendering with the
          selected surface's vertex dots still on top; unchecking reverted
          it. No console errors.
    - [x] Emitters as a new outliner category with rate/maxAlive/capPolicy
          (requirements.md §10.4). **This note's own premise was stale** -
          the second stale claim caught this session (see the constraints/
          surfaces/colliders migration entry above for the first): `buildSparks`
          (`particlesim/examples/Sparks.kt`) has had a real, *named*
          (`"fountain"`) `Emitter` since Phase 6, exercised in both
          `SparksDebugDemo` and the scene library's `SparksScene` - no new
          demo needed, confirmed by reading the code rather than trusting
          this note.
          `Emitter.rate`/`maxAlive`/`capPolicy` promoted from `val` to
          mutable, with `currentRate(t)`/`currentCapPolicy()` reads and
          `setRate`/`setMaxAlive`/`setCapPolicy` writes - `maxAlive` stays a
          public property (`private set`) since `SparksStabilityTest`
          already read it directly; `rate`/`capPolicy` stay private,
          reached only through the new accessors. `setMaxAlive` rejects
          non-positive values, same "reject rather than accept a value
          that breaks the class's own invariants" stance as
          `ParticleStore.setMass`.
          **`capPolicy` went over the wire as a `Boolean` (`evictOldest`),
          not a new enum `FieldValue` variant** - the open design question
          the user pre-approved deciding. `rate` is expression-capable (a
          `ScalarExpr`, evaluated fresh each frame at the current `t`) and
          `capPolicy` is a two-valued enum, so neither fits
          `FieldValue`'s Scalar/Vector split the way `EditableFields`
          assumes; bending that mechanism to fit felt worse than three
          purpose-built messages (`SetEmitterRate`/`SetEmitterMaxAlive`/
          `SetEmitterCapPolicy`, parsed in `SceneControlMessage.parse`) and
          a new `applyEmitterMessage` dispatcher in `DemoScene.kt`,
          parallel to `applyEditableFieldMessage` but kept separate since
          emitters aren't `EditableFields`. `evictOldest: true` means
          `EmitterCapPolicy.EVICT_OLDEST`, the same boolean-toggle
          convention as `SetColliderActive`/`SetGroupEnabled` rather than
          inventing an enum wire type for one two-valued field.
          `SceneRegistry` gained `emitters: Map<String, Emitter>` -
          collected unconditionally (unlike forces/constraints/surfaces),
          since `Emitter.name` is non-nullable: every emitter is named from
          construction, so there's no "unnamed, not outliner-reachable"
          case to filter out here. `BinaryFrame` gained a new per-frame
          registry section (name, live-evaluated rate, maxAlive,
          evictOldest), decoded into `DecodedEmitterEntry` - `collectEmitterEntries`
          takes `t` (already threaded through `encode`) to evaluate `rate`
          fresh each frame, mirroring how a particle's mass/radius are
          shown as their live evaluated number, never the source
          expression. `SparksScene` gained its first real `SceneRegistry`
          (forces, **groups** - initially missed in review, since
          `scenario.groups` wasn't passed and the "sparks" group was
          invisible in the outliner despite every spawned particle
          belonging to it - and the emitter), built fresh inside `frame(t)`
          rather than cached at construction, since group membership and
          the live particle set change every step here; `SceneRegistry`'s
          own doc comment on stale member-id snapshots updated to point at
          this as the resolved case, not a hypothetical future gap.
          Client: new EMITTERS outliner section; a per-object panel with a
          plain-text `rate` field (expression-string editing, same
          convention as a particle's mass/radius - parsed server-side via
          `ExpressionParser.parseScalar`, always showing the live evaluated
          number), a `number`-type `maxAlive` field, and an "evict oldest
          at cap" checkbox; values refresh via `updateLive` (skipping a
          focused input), the emitter *name list* (not the live values)
          is part of `registrySignature` so an emitter appearing/
          disappearing still triggers a rebuild.
          Every standalone (non-scene-library) demo's exhaustive
          `SceneControlMessage` `when` needed a new no-op branch for the
          three message types (`RemoveCollider`-style "this demo has no
          emitters" comments) - mechanical, no behavior change.
          Component-tested: `EmitterTest`-adjacent coverage in
          `DemoSceneTest` (rate/maxAlive/capPolicy edits apply, a
          non-positive `maxAlive` is rejected, an unmatched name is a
          silent no-op, an unrecognized message type falls through),
          `SceneControlMessageTest` (all three new message types parse and
          reject malformed/missing-key input), and `BinaryFrameTest`
          (round-trips name/rate/maxAlive/evictOldest; an empty emitter
          list round-trips to an empty list). **Verified live in Chrome**
          against the `sparks` scene: "fountain" appears under EMITTERS
          with its live-ticking rate (`20 + 15·sin(0.5t)`); editing rate to
          a constant `200` and maxAlive to `15` capped the live particle
          count at 15 with STOP behavior (particles cycling via natural
          lifetime expiry, not eviction); checking "evict oldest at cap"
          immediately switched to same-`t` spawn/destroy pairs (the
          eviction signature), still pinned at 15. Switching to `flag`
          confirmed the section correctly falls back to "(none)" for a
          scene with no emitters. No console errors.

## Scene library (§9.6, new requirement) — not yet phased
- [x] Engine-side switching mechanism, scoped to the four demos that
      already have a `buildX(): XScenario` builder (`flag`, `ballBounce`,
      `trampoline`, `sparks`) - user's explicit scope choice over doing all
      eight demos in one pass, since the other four (`Drag`,
      `ParticleCollision`, `SpatialGrid`, `MultiShape`) build their
      scenario ad hoc inline in `main()` and carry real demo-specific
      interactive logic (spawn timers, collider rules, drag-exclusion)
      that doesn't yet reduce to the shape below.
      - **`DemoScene`** (`particlesim.debug`): one runnable scene -
        `dt`, `store`, `ids()`, `step(t)`, `handleControl(message, t)`
        (defaults to a no-op), `frame(t): SceneFrame`. Deliberately has
        no drag hook: `DragMessage.Move`'s target-velocity estimate is
        dt-sensitive and needs draining at physics-step cadence, so a
        scene that supports dragging (`FlagScene`) is handed the shared
        `DragMessageQueue` directly and drains it itself inside `step`,
        rather than the runner draining it once per frame and losing
        that per-step granularity.
      - **`SceneFrame`**: everything a scene hands back to `frame()` -
        connections, camera, sphereRadii, meshes, arrowGroups,
        connectionNames, visibleIds, registry, colliders, events - all
        defaulted the same way `DebugRenderer.broadcast`'s own optional
        params are, so the runner makes exactly one `broadcast` call for
        every scene instead of each scene independently coupling to that
        signature (the per-scene duplication §9.6 exists to eliminate,
        moved one level down - caught in review before implementing).
      - **`SceneLibrary`**: holds the named factory map and the one live
        `scene`; `load`/`restart` both discard the current instance and
        construct a fresh one from its factory (no scene implements its
        own reset logic) and zero `t`/`step`; `handle` intercepts
        `LoadScene`/`Restart` itself and forwards every other
        `SceneControlMessage` to `scene.handleControl` - the one
        dispatch path every scene's edits go through. Component-tested
        (`SceneLibraryTest`) against a stub scene, independent of any
        real physics.
      - **`SceneControlMessage.LoadScene(name)`** (wire type
        `load_scene`) added to the sealed interface - required adding a
        `LoadScene` branch to the four still-standalone demos' own
        `sceneControlQueue when` blocks too (a no-op there), since Kotlin
        enforces exhaustiveness on a sealed interface.
      - **`FlagScene`/`BallBounceScene`/`TrampolineScene`/`SparksScene`**:
        faithful ports of the four existing demos' `main()` bodies onto
        `DemoScene`, including `FlagDebugDemo`'s just-fixed generic
        `EditableFields` dispatch (§10.4, previous entry).
      - **`SceneLibraryDebugDemo`** (`./gradlew runSceneLibraryDemo`):
        the generic runner - one `ViewerInput`/`DebugRenderer`, an
        `if (message ...)`-free per-frame loop (`library.handle`,
        `library.advanceOneStep`, `library.scene.frame`), `stepsPerFrame`
        recomputed every frame from whichever scene is active (`dt`
        differs by >10x between `FLAG_DT` and `TRAMPOLINE_DT`).
      - **Verified**: full test suite green, plus a manual WebSocket
        smoke test (Python `websockets`, scratch venv) against the live
        `runSceneLibraryDemo` process - confirmed `load_scene` switches
        particle count/dt/`t`-reset correctly across all four scenes,
        `restart` reloads a fresh instance of the active scene, and an
        unknown scene name is ignored (stays on whatever was active)
        rather than crashing the connection. The original four standalone
        `run*Demo` gradle tasks are untouched and still work.
- [x] Viewer UI: a "scene" section at the top of the control panel
      (hidden unless a frame reports non-empty `availableScenes`), a
      `<select>` populated from the wire and kept in sync with
      `activeScene`. `BinaryFrame` gained `availableScenes`/`activeScene`
      (a name list plus one string, appended after everything else in the
      frame - `stringSize(activeScene)` for "none"/empty, same convention
      as every other optional name in this format) - round-tripped in
      `BinaryFrameTest`, and independently checked against a live
      `runSceneLibraryDemo` process via a Python port of the exact field
      order the JS decoder uses, confirming the buffer decodes to exactly
      zero bytes remaining before touching the JS side at all. Picking an
      entry sends `load_scene` and resets every bit of viewer-side state
      the new scene has no way to know about (stats, event log,
      selection, hidden-group/-surface/-force toggles, camera) via one
      shared `resetViewerStateForSceneChange()`, also now used by
      `restart` (the same "reload by name" operation underneath).
      Two bugs caught live in Chrome and fixed:
      - **Camera stuck on switch**: the reset was gated on already being
        in scripted mode, so once a person had ever manually orbited the
        view (any scene, any time before), `cameraMode` stayed `"manual"`
        indefinitely and the reset silently no-oped forever after. Fixed
        by unconditionally forcing scripted mode back on during a scene
        switch, not just resetting the pose while already in it.
      - **Particle mass/radius edits reverting on every non-flag scene**:
        `applyEditableFieldMessage` (the shared dispatch added to kill
        `FlagScene`'s copy-pasted force/constraint dispatch) didn't yet
        cover `SetParticleScalarField` - that path was still only wired
        inside `FlagScene`'s own `handleControl`, hand-copied there and
        nowhere else, so `TrampolineScene`/`BallBounceScene`/
        `SparksScene` silently dropped every particle edit - the exact
        `FlagDebugDemo` bug from earlier this session, recurring for a
        different message type in new code. Folded into the same shared
        function rather than extracting yet another one-off copy;
        `DemoSceneTest` covers it directly, and verified end-to-end
        against the live trampoline scene over a raw WebSocket connection
        (edit a mat particle's mass, confirm it holds across several
        subsequent frames instead of reverting).
- [x] The other four demos (`Drag`, `ParticleCollision`, `SpatialGrid`,
      `MultiShape`) now in the library too, as `DragScene`/
      `ParticleCollisionScene`/`SpatialGridScene`/`MultiShapeScene` -
      rather than extracting a `buildX(): XScenario` first (none of these
      four had one; each built its scenario ad hoc inline in `main()`),
      each demo's `main()` body was ported onto `DemoScene` directly, one
      field per local `var`/`val` it used. Every original standalone
      `run*Demo` gradle task is untouched and still works - this is a
      second, additional way to reach the same scenarios, not a
      replacement.
      - **Restart got simpler, not harder**: all four standalone demos
        had a hand-rolled `SceneControlMessage.Restart` branch that
        rebuilt every mutable var from scratch (`ParticleCollisionDebugDemo`/
        `SpatialGridDebugDemo`'s re-seeded `Random`, `DragDebugDemo`'s
        whole chain). `SceneLibrary.restart()` already discards the
        active scene and constructs a fresh instance from its factory
        (see the entry above) - that branch is simply gone in each
        `*Scene`, and what were `var store`/`var groups`/etc go back to
        being plain `val` properties. Only state that legitimately
        mutates *within* one instance's lifetime (spawned-ball count,
        live collider list, the chain's current springs/dampers after a
        break or delete) stays a `var`.
      - **`applyEditableFieldMessage` gained the `SetParticleScalarField`
        case** it was still missing (folded into the same function
        rather than the fix from the entry above only covering scalar/
        vector fields) - `SpatialGridScene`/`DragScene` wire it in the
        same one call every other scene now uses.
      - **`MultiShapeScene` got real outliner/edit presence it never had
        standalone** - the original `MultiShapeDebugDemo` never built a
        `SceneRegistry` or drained `sceneControlQueue` at all (the very
        gap this session's first round found and worked around by
        testing against `FlagDebugDemo` instead). Wrapping it now wires
        both in: the four composed shapes' own named forces/constraints/
        surface (`buildFlag`'s "wind"/"structural"/etc., now namespaced
        `flag.wind` etc. by `ShapePlacement`'s instance-naming) are
        outliner-reachable and editable like every other library scene.
        Colliders aren't wired - `buildTire`/`buildBallBounce` don't
        expose their own `Collider` objects (only wrapped inside a
        `CollisionSystem`), and changing those shapes' return shape to
        expose them is out of scope here.
      - **Two file-local `private data class`es needed renaming**
        (`NamedColliderRule`→`ScenePlaneColliderRule` in
        `ParticleCollisionScene`, `BoxColliderRule`→`SpatialGridColliderRule`
        in `SpatialGridScene`) - a Kotlin gotcha caught by the compiler,
        not live testing: a top-level `private` class is file-scoped for
        *access*, but its name still has to be unique across the whole
        package, so reusing the standalone demo's exact class name in
        the new scene file was a real redeclaration error.
      - **Verified**: full test suite green, plus an end-to-end WebSocket
        smoke test against all eight library scenes together - every
        one's wire frame decodes with zero byte drift (forces/
        constraints/colliders/groups sections all correctly populated
        per scene), and each of the four new scenes' own interactive
        mechanic specifically exercised: `dragScene` start/move/end on a
        non-anchor link, `particleCollision`'s `delete_particle` (victim
        removed, `ParticleDestroyed` event seen), `spatialGrid`'s
        `remove_collider('floor')`. **Confirmed live in Chrome** by the
        user too - the picker lists all eight, `drag`'s click-and-drag,
        `particleCollision`'s collider removal/delete, `spatialGrid`'s
        2000-ball box, and `multiShape`'s new outliner presence all work
        as rendered, not just at the wire level.
- [x] Standalone `run*Demo` gradle tasks and their `*DebugDemo.kt`
      `main()`s removed, now that all eight scenes above were confirmed
      reachable through the picker losslessly - every entry above kept
      saying "the original standalone task is untouched, this is a
      second way to reach it," which was true but meant keeping two
      parallel copies of the same scenario indefinitely. The actual cost
      showed up repeatedly this session: adding one new
      `SceneControlMessage` variant (`SetWindVelocity`, §10.4) meant
      touching an exhaustive-`when` branch in four *separate* standalone
      demos on top of the one real dispatch site in `DemoScene`/
      `SceneLibrary`, purely to keep them compiling - pure upkeep tax,
      not anything the standalone copies used for their own logic. Removed
      `BallBounceDebugDemo.kt`, `DragDebugDemo.kt`, `FlagDebugDemo.kt`,
      `MultiShapeDebugDemo.kt`, `ParticleCollisionDebugDemo.kt`,
      `SparksDebugDemo.kt`, `SpatialGridDebugDemo.kt`,
      `TrampolineDebugDemo.kt` and their eight `build.gradle.kts` tasks
      (`runFlagDemo` etc.) - confirmed via `grep` that nothing outside
      each file itself (no import, no test) referenced any of them.
      `DebugRendererDemo`/`./gradlew run` (the original Phase 3 bare
      renderer, never part of the scene library) is untouched - it isn't
      a duplicate of anything the picker reaches.

      Added `args: Array<String>` to `SceneLibraryDebugDemo.main` so a
      single demo can still be launched directly without clicking through
      the picker after startup - `./gradlew runSceneLibraryDemo
      --args="trampoline"` starts on that scene instead of the `flag`
      default. An unrecognized name prints the valid scene list to
      stderr and returns without starting the server, rather than either
      silently falling back to the default or crashing with a bare
      `IllegalArgumentException` from `SceneLibrary`'s own `require`.
      **Verified**: `./gradlew compileKotlin test` green after the
      removal (nothing broke); `./gradlew tasks --group=application` now
      lists only `run`/`runSceneLibraryDemo`; `--args="bogus-scene"`
      printed the expected error and exited immediately without opening
      a port; `--args="trampoline"` **verified live in Chrome** - the
      viewer loaded directly onto the trampoline scene (mat/rim/ball
      groups, `rim-anchor` constraint, matching outliner) rather than the
      flag default, and switching scenes afterward via the picker's
      `<select>` still worked normally. No console errors.

## Shape library (§4.5, new requirement) — not yet phased
- [x] Kotlin DSL: `ShapePlacement` (`particlesim.examples`) — an
      `offset: Vector3` and an optional `instanceName: String?`, resolving
      a shape's local names via `name(local) = "$instanceName.$local"`
      (unprefixed when `instanceName` is null). Position-only placement,
      deliberately — no shape built so far has a meaning that changes
      under rotation, so orientation is left out until something
      concretely needs it (§4.5's own stated reasoning, not a gap found
      later).
      **`buildFlag`/`buildBallBounce` both refactored** to accept a
      shared `store: ParticleStore = ParticleStore()`, `groups: Groups =
      Groups()`, and `placement: ShapePlacement = ShapePlacement()` —
      all three default to exactly the original single-instance,
      fresh-store behavior, confirmed by rerunning `FlagGoldenTest`/
      `FlagYamlParityTest` after the change rather than assuming the
      defaults were harmless. `buildBallBounce`'s floor collider moves
      and gets renamed with `placement` too, not just the ball itself —
      each instance is a self-contained ball-and-its-own-floor.
      **Proven, not just unit-tested**: `ShapeCompositionTest` (two flags
      sharing a store get disjoint ids and correctly namespaced groups;
      a flag and a ball-bounce step together for 100 steps with zero
      cross-talk — the ball falls under its own gravity, the flag's pole
      stays exactly fixed, neither force list affects the other's
      particles) and `MultiShapeDebugDemo` (`./gradlew runMultiShapeDemo`
      — two flags plus a ball-bounce in one live scene), verified
      server-side against the running demo: exactly 121 particles
      (2×60 + 1) and 208 structural connections (2×104, the exact
      closed-form count for a 6×10 grid), the ball falling from its own
      offset and settling at exactly `radius` above its own floor without
      drifting in x/z.
      **`buildSparks` not yet refactored** — deferred, not forgotten;
      proving the pattern against two structurally-different shapes
      (a static mesh vs. a single collidable particle) was enough to
      validate it without touching every existing example.
- [x] `tire`, `flagpole` shapes — two genuinely new shapes (not refactors
      of an existing builder), both following the same `store`/`groups`/
      `placement` trailing-params convention from the start.
      **`buildTire`** (`particlesim.examples.Tire`): a closed ring of
      `segments` particles (must be even, ≥4) lying flat in the X/Z plane,
      held together by neighbor structural springs around the rim *and*
      a "diameter" brace spring between each particle and its exact
      opposite — neighbor-only springs offer almost no resistance to the
      ring collapsing flat under gravity/impact, so the diameter braces
      are load-bearing, not decorative. Dropped onto the ground via the
      same `ParticleColliderRule`/`PlaneCollider` mechanism `buildBallBounce`
      already uses, just applied to the whole rim group. Deliberately
      doesn't roll or stand on edge — that needs friction/rotational
      dynamics this engine doesn't have yet (§12.5's friction is
      `[stretch]`).
      **`buildFlagpole`** (`particlesim.examples.Flagpole`): a static
      vertical line of particles pinned with `FixedPosition.atCurrentPositions`,
      growing *upward* from `placement.offset` (offset is the pole's base,
      not its top — chosen so a scene author plants it like a real pole).
      Purely a visual/structural anchor; a `buildFlag` instance is lined up
      alongside it by placement only, the two aren't physically connected.
      **Verified, not just unit-tested**: `TireTest`/`FlagpoleTest` (10
      tests — ring geometry, odd/too-few-segments rejection, offset
      semantics, an 8000-step fall-and-settle integration test for the
      tire, instance-name namespacing for both) all pass, and
      `MultiShapeDebugDemo` was extended to compose a flagpole + flag
      (flag's pole-edge starts exactly at the flagpole's top) with a tire
      and the existing ball-bounce, all in one shared scene. Verified
      server-side against the running demo: 136 particles (7 pole + 112
      flag + 16 tire + 1 ball) and 224 connections (6 pole segments + 202
      flag structural springs + 16 tire rim edges, both exact closed-form
      counts), the flagpole's particles bit-identical across a multi-second
      gap (a true fixed anchor), the tire settled at ~0.05 (its own
      particle radius) above the ground, and the ball resting at exactly
      its placed offset with no cross-talk between shapes.
- [ ] YAML shape library/registry — second pass, same status as every
      other post-Phase-7 YAML gap; needs the DSL side built first to know
      what a shape actually needs to parameterize

## Force-arrow selection, wind gust/direction editing, and textured surfaces (§5.2/§10.2/§10.3/§10.4, new requirements) — not yet phased
Raised by the user in three `/btw`-style comments during the
`viewer-live-editing` work, recorded in `requirements.md` (§5.2, §10.2,
§10.3, §10.4) rather than implemented immediately - captured here so the
next session picks them up instead of losing them.
- [x] Right-click a force's sampled arrow (wind, or any other directional
      field force) to select and open *that force's* per-object panel,
      the arrow-picking counterpart to right-clicking a mesh triangle to
      open its surface's panel (requirements.md §10.3). Purely a
      `viewer.html` change - the arrow group's source-force name was
      already on the wire (`frame.arrowGroups[i].name`), nothing
      server-side needed touching. The per-frame arrow-update loop
      (`applyFrame`) now tracks each flattened sample's owning group name
      alongside it and tags the corresponding `ArrowHelper`'s
      `userData.forceName` (`""` for unnamed, same convention as a mesh's
      `userData.surfaceName`). `arrowObjects` added to the `contextmenu`
      handler's raycast target list (not `pickParticle`'s - dragging a
      force arrow makes no sense, only right-click-to-open needed it).
      **A real subtlety, not just wiring**: a raycast against an
      `ArrowHelper` never hits the `ArrowHelper` itself - it's a plain
      `Object3D` wrapping a `Line` and a cone `Mesh`, neither of which
      carries the tag, so a hit resolves to one of those children
      instead. Added `resolveForceName(object)`, which walks up
      `.parent` from the hit object until it finds an ancestor with
      `userData.forceName` set - handles a hit on either the shaft or the
      arrowhead without assuming three.js's internal `ArrowHelper`
      structure. **Verified live in Chrome** against `FlagDebugDemo`'s
      wind arrows (via `SceneLibraryDebugDemo`'s `flag` scene):
      right-clicking an arrowhead (cone) opened the wind panel; so did
      right-clicking a bare shaft (line) on a different arrow, after
      first selecting a different force to confirm the click actually
      caused the switch rather than it already being selected; particle
      and surface right-click-to-open still worked unaffected (their own
      raycast logic wasn't touched, only appended to). No console errors.

      **Follow-up fix**: the initial version raycast dots, mesh, and
      arrows together in one `intersectObjects` call and took the
      distance-sorted closest hit - reported by the user as "selecting
      the flag selects the wind instead." Arrow origins sit *exactly on*
      the mesh vertices they sample, so a right-click on the flag and a
      right-click on the wind arrow rooted there hit the ray at
      essentially the same depth, and which one distance-sorting picked
      was numerical noise, not something aimable. Fixed by raycasting
      solid geometry (dots, mesh) and arrows as two separate passes, with
      arrows only considered as a fallback when the solid pass hits
      nothing - a solid surface is always the more specific target.
      **Verified live in Chrome**: right-clicking the flag mesh in a
      region where several wind arrows visibly crossed directly over it
      (the flag heavily wind-blown and bunched up) correctly opened
      `cloth-mesh`, not `wind`; right-clicking a bare arrow shaft away
      from the mesh still opened `wind`, confirming arrow-picking itself
      wasn't disabled, just deprioritized. No console errors.
- [x] `Wind`'s velocity independently live-editable from the viewer, not
      just `density` (requirements.md §5.2/§10.4). The user resolved the
      open design question directly rather than decomposing "gust" into
      named amplitude/frequency sub-parameters: edit the entire
      `Wind.velocity` `VectorExpr` as one text field (e.g.
      `[15*sin(t), 0, 15*cos(t)]`), parsed server-side by the existing
      shared expression engine - the same "one opaque expression, edited
      wholesale" pattern already used for particle mass/radius and
      emitter rate, not a new mechanism. `density` was explicitly kept as
      a separate, independently-editable field, untouched by this change.
      **What was built**: `Wind.velocity` changed from `private val` to
      `private var`, with `currentVelocity(t)`/`setVelocity(expr)` added
      (`sampleAt`/`accumulate` now read through `currentVelocity`); a new
      `SceneControlMessage.SetWindVelocity(name, expr)` wire message
      (Wind-specific rather than a generalized field-edit message, same
      rationale as the emitter messages) dispatched from
      `applyEditableFieldMessage` in `DemoScene.kt`; a new `winds` binary
      registry section (`BinaryFrame.kt`) carrying each named `Wind`'s
      live-evaluated velocity per frame so the viewer can display the
      *current* value of a time-varying expression, not just what was
      last typed; and a `viewer.html` velocity text input in the forces
      panel (alongside the existing `density` field), refreshed from the
      registry every frame unless focused, mirroring the mass/radius
      inputs' "raw value, not toFixed" display convention - and, like
      those, an accepted tradeoff that submitting a no-op edit while the
      expression is time-varying freezes it at the momentarily-displayed
      constant. Component tests added: `WindTest.kt` (`currentVelocity`
      reflects a live-evaluated time-varying expression; `setVelocity`
      replaces the expression outright and the new value actually drives
      `accumulate`, not just `currentVelocity`; `setVelocity` leaves
      `density`'s `editableFields()` entry untouched),
      `SceneControlMessageTest.kt` (parses a constant vector literal and a
      time-varying expression; rejects a scalar expression in this vector
      field, malformed syntax, and missing keys),
      `DemoSceneTest.kt` (dispatches to the matching named `Wind`; a
      non-matching name and a non-`Wind` force with the same name are
      both silently ignored, not errors), `BinaryFrameTest.kt` (a named
      `Wind`'s live-evaluated velocity round-trips through the wire
      independent of `density`, which still round-trips via the ordinary
      `fields` list; no winds round-trips to an empty list). **Verified
      live in Chrome** against `SceneLibraryDebugDemo`'s `flag` scene:
      editing velocity to a constant `[0, 20, 0]` visibly reoriented all
      wind arrows and billowed the flag mesh; editing to a time-varying
      `[15*sin(t), 0, 15*cos(t)]` showed the displayed value ticking live
      frame to frame (e.g. 9.84 → 12.01); editing `density` to `3`
      afterward updated independently without disturbing the ongoing
      gusting velocity, confirming the two fields stay decoupled as
      required. No console errors observed.

      **Follow-up fix**: typing a bare scalar expression (e.g. `sin(t)`)
      into this field did nothing visible - `ExpressionParser.parseVector`
      rejects it server-side (it's a scalar, not a vector), and every
      expression-editable field in this app silently drops a message that
      fails to parse, so the UI gave zero feedback. Added a minimal
      client-side check in `viewer.html`, scoped to this field only:
      `Parser.parsePrimary`'s `LBRACKET` case is the *only* production
      that yields `ValueType.VECTOR`, so anything not starting with `[` is
      guaranteed to be rejected server-side - checking that one structural
      fact client-side (not re-implementing the grammar) lets the input
      show a red outline and an explanatory tooltip instead of silently
      no-op'ing, and skips sending the edit at all. Deliberately narrow:
      this does *not* catch malformed syntax or bad arity/identifiers
      inside the brackets (e.g. `[1, 2]`, `[a, b, c]`) - those still parse
      server-side and still fail silently, the same as every other
      expression field (mass, radius, emitter rate), none of which got
      this treatment since a bare scalar is valid input for a scalar
      field. The invalid-state flag suppresses `updateLive`'s per-frame
      overwrite so the bad text and its red outline survive until
      corrected, and clears on the next keystroke or a successful edit.
      **Verified live in Chrome**: typing `sin(t)` and blurring the field
      showed the red outline and tooltip immediately and left the text in
      place across several seconds of running frames (proving `updateLive`
      wasn't stomping it); correcting it to `[0, 20, 0]` cleared the
      outline, sent the edit, and visibly reoriented the wind arrows and
      flag mesh. No console errors.

      **Second follow-up fix** (this note's "malformed syntax... still
      fails silently" turned out to have a reachable, reported instance):
      the user set `density` to `0` and `velocity` to `[0,0,0]` while
      paused, unpaused, and watched `velocity` keep changing with
      x > 1.0 instead of staying `[0,0,0]`. Root cause: a single click
      into the field (not a triple-click, not Tab) places the caret
      wherever the click landed rather than selecting the existing text -
      every one of these fields displays the live *evaluated* value, so
      the old text is still there to type into/onto. Clicking near the
      end of e.g. `[7.4, 0, 2.1]` and typing `[0,0,0]` produced
      `[7.4, 0, 2.1][0,0,0]` - a string that still starts with `[` (so it
      passed the check above) but is malformed server-side, fails
      `ExpressionParser.parseVector`, and is silently dropped - leaving
      the *original* time-varying expression running untouched, which is
      exactly "kept changing, x > 1.0." Confirmed by deliberately
      reproducing this exact interaction before fixing it. Strengthening
      the client-side syntax check further isn't viable - `[1,0,0] +
      [0,1,0]` is legal grammar, so "must be exactly one bracket pair"
      would reject valid input; only real parsing distinguishes the two,
      which is exactly what re-implementing the grammar client-side
      would mean. Fixed instead with `selectAllOnFocus(input)`, a shared
      helper now called from every §10.4 raw-evaluated-value input
      (wind velocity, particle mass/radius, emitter rate, and the
      generic `EditableFields` scalar/vector inputs density goes
      through): selects the whole value on focus, and - because the
      same click's `mouseup` normally fires right after `focus` and
      collapses that selection back to a caret - suppresses that one
      `mouseup`'s default caret placement via a "just focused" flag, so
      a plain single click, not just Tab or a triple-click, now replaces
      the whole value instead of appending to it. **Verified live in
      Chrome** with the exact discriminating test (a single `left_click`
      then `type`, deliberately avoiding triple-click/Tab, which would
      have passed even without the fix): clicking into the velocity
      field and typing `[0,0,0]` now replaces the text cleanly instead of
      concatenating; density `0` and velocity `[0,0,0]` both held stable
      through several seconds of running simulation after unpausing;
      re-verified that normal editing (a fresh time-varying expression)
      still works afterward. No console errors.
- [x] `[stretch]` Texture-mapped surfaces - an image (e.g. a flag graphic)
      rendered onto a surface's mesh instead of/alongside its flat shaded
      color (requirements.md §10.2, marked `[stretch]` at the user's
      request). Closed both gaps identified when this was deferred:
      **UV coordinates**: new `particlesim.surface.UV(u, v)` and
      `Surface.uvs: Map<Int, UV>?` - sparse, id-keyed the same way other
      per-particle-but-not-every-particle data already is in this codebase
      (`ParticleStore`'s mass/radius source maps), not a parallel array
      indexed like `triangles`, since most surfaces will never populate it.
      `Grid.uvs(ids)` generates them for the grid-generated case directly
      from each vertex's row/col position (`c/(cols-1), r/(rows-1)`),
      normalized `[0,1]` the way every texture-mapping library (including
      three.js) expects - arbitrary-mesh UV generation was never solved
      and still isn't needed. `buildFlag` attaches `Grid.uvs(grid)`
      unconditionally, the same way it computes `triangles` once
      regardless of who ends up rendering the surface - static geometry
      metadata is cheap either way.
      **Image asset delivery**: new `particlesim.render.TextureAssets`,
      a static file served once and referenced by URL, not pushed through
      the binary per-frame protocol (§9.1) - the image doesn't change
      every step. Procedurally generated in-process (`BufferedImage` +
      `ImageIO`, no external fetch, no checked-in binary): a 256x160
      red/white striped pattern with a blue canton block, deliberately not
      a reproduction of any real flag (8 stripes not 13, no stars,
      different proportions/colors - canton-plus-stripes is a layout
      family shared by many national flags, not one specific symbol).
      Cached in memory after first generation. `ViewerHttpServer` gained a
      `/textures/` route serving `TextureAssets.pngBytes(name)` with
      `Cache-Control: public, max-age=3600`, 404 for unknown names.
      **Wire-cost gating, the actual design decision**: `SurfaceRenderer`
      gained `textureName: String? = null`. `BinaryFrame` only emits a
      mesh's `textureUrl` (`/textures/<name>.png`) and per-vertex `uvs`
      when that mesh's own `textureName` is set - gated on the *renderer's*
      opt-in, not on `Surface.uvs` nullness, even though `buildFlag` now
      populates `uvs` unconditionally. This distinction is load-bearing:
      `flagOnRope`/`multiShape` both reuse `buildFlag`'s surface without
      setting `textureName`, so without the renderer-gated check they'd
      have silently started sending UV data over the wire for a mesh
      nothing maps it onto - caught in review before shipping and covered
      by a dedicated regression test (`BinaryFrameTest`: "a surface with
      populated uvs but no textureName still sends zero uvs").
      **Client side** (`viewer.html`): a `textureCache`/`getTexture(url)`
      pair (`THREE.TextureLoader`, cached by URL) feeding a
      `texturedMeshMaterial(url)` (`THREE.MeshStandardMaterial` with
      `.map` set), selected in the mesh material logic ahead of the
      existing solid/wireframe choice whenever a decoded mesh carries a
      non-empty `textureUrl`. `buildMeshGeometry` attaches a `uv`
      `BufferAttribute` only when the decoded mesh's `uvs` map is
      non-empty (missing per-vertex entries fall back to `(0,0)`).
      **Bug found and fixed before shipping**: the loaded texture needs
      `texture.colorSpace = THREE.SRGBColorSpace` set explicitly (three.js
      r152+) or it renders through the wrong gamma - dark/desaturated,
      not an obvious error. Set immediately after `textureLoader.load(url)`
      inside `getTexture()`.
      Wired into `FlagScene` only (`clothMesh = SurfaceRenderer(...,
      textureName = TextureAssets.FLAG_STRIPES)`), the same "wiring a
      second consumer, pick it up when needed" scoping the rope/pole and
      self-collision items above used - `flagOnRope`/`multiShape` don't
      opt in yet.
      New tests: `GridTest` (uvs cover every vertex, normalized `[0,1]` at
      the grid's corners; rejects grids under 2x2 matching `triangles`'
      own guard), `TextureAssetsTest` (known name returns a decodable PNG
      over 100 bytes; unknown name returns null; repeated calls return
      byte-identical cached data), `BinaryFrameTest` (untextured mesh
      decodes to empty `textureUrl`/no uvs; populated `uvs` without
      `textureName` still sends zero uvs - the gating regression above; a
      textured mesh's `textureUrl` and per-vertex uvs round-trip).
      **Verified live in Chrome**: loaded `flag`, confirmed the mesh
      renders with the striped texture correctly mapped across the
      billowing/folding cloth (not just at rest), matching the raw
      `/textures/flag-stripes.png` asset's colors after the colorSpace
      fix (compared side by side in separate tabs); toggled "show mesh
      edges" and the wireframe override both directions via the surface's
      right-click panel, both still working with a texture applied; no
      console errors in a fresh tab. (An earlier same-session check in a
      tab that had been open *before* the wire-format change showed ~1244
      frozen `RangeError` console entries at one timestamp - the interactive
      per-frame stream carries no version field, so a stale tab decodes new
      frames with pre-change JS until reloaded; not a bug in the shipped
      code, confirmed by the fresh tab being clean throughout.)
      **Follow-up, same session**: swapped `FlagScene`'s texture from the
      procedural stripes above to an actual American flag image the user
      pasted directly in chat. `TextureAssets` gained a second name,
      `USA_FLAG`, alongside `FLAG_STRIPES` - not a replacement, since
      `FLAG_STRIPES`'s own reason to exist (an asymmetric high-contrast
      pattern that makes a UV mapping mistake obvious) still holds and
      stays tested. This is the project's first-ever checked-in binary
      asset (`src/main/resources/particlesim/textures/us-flag.png`) rather
      than a procedurally-generated one - legitimate here specifically
      because the user supplied the source image directly (never fetched
      or guessed from a URL). The pasted image (1588x1588, sticker-style
      with padding/drop-shadow around the actual flag) was cropped to its
      real content bounds (1560x1041) and downscaled to 512x342 via a
      throwaway `jshell` script (flattened onto an opaque white
      background first, dropping the shadow/alpha fringe, then encoded as
      `TYPE_INT_RGB` PNG) - the same "spike script, run once, delete"
      pattern the self-collision tuning work used, not checked in.
      `TextureAssets.loadUsaFlag()` reads the resource from the classpath
      once (`getResourceAsStream`) and caches it the same way the
      generated textures are cached, so `pngBytes(name)` callers don't
      need to know which kind of texture a name refers to.
      New test: `TextureAssetsTest` ("the checked-in USA flag resource
      also returns decodable, non-trivial PNG bytes").
      **Verified live in Chrome**: loaded `flag`, confirmed the actual
      stars-and-stripes flag renders correctly mapped across the
      wind-billowed, folding cloth (matching the checked-in asset, no
      seams/stretching visible even where the surface curls), no console
      errors in a fresh tab. **Missed at the time**: the flag was actually
      upside down (canton at the bottom) - not caught by this verification
      pass, only noticed afterward by the user. Fixed in the next
      follow-up below.
      **Second follow-up, same session - the upside-down bug, plus
      `flagOnRope`**: `Grid.uvs`'s `v = r / (rows - 1)` put row 0 (the
      pole-top edge, world-space *top* of the flag) at `v=0`. Three.js's
      default `Texture.flipY = true` puts the *first* (topmost) row of the
      source image's own pixels at `v=1`, not `v=0` - so row 0 (world-top)
      was landing on the *last* row of the source image (the bottom
      stripe) instead of the first (the canton), flipping the whole image
      vertically. No error, no distortion, just wrong - every triangle
      still had valid, consistent UVs, which is exactly why the live
      verification above didn't catch it (nothing looked "broken," it
      just looked like a flag flying upside down, easy to read as
      correct at a glance). Fixed by flipping the formula to
      `v = 1 - r / (rows - 1)`, so row 0 now lands on `v=1`. `GridTest`'s
      corner-value assertions inverted to match (`ids[0][*]` now `v=1`,
      `ids[rows-1][*]` now `v=0`); the interior mid-grid assertion needed
      `1.0 - 1.0/3.0` rather than the algebraically-equal `2.0/3.0` literal
      to avoid a one-ULP floating-point mismatch against the production
      formula's own rounding.
      Also textured `FlagOnRopeScene`'s flag with the same `USA_FLAG`
      asset (`scenario.flagSurface` already carries `Grid.uvs` from
      `buildFlag`, so no new UV work needed) and set its `SceneFrame.
      visibleIds` to just the pole + rope ids, dropping the flag grid's
      own particle dots now that the textured mesh already shows it - the
      same reasoning `FlagScene` already applies to its own cloth
      (`visibleIds = poleIds`), extended here to also keep the rope's
      dots visible since nothing else renders the rope's individual
      vertices. Deliberately *not* `emptySet()` - the pole/rope have no
      mesh of their own, and excluding a particle from `visibleIds`
      server-side is permanent for that frame (the client's own per-group
      "show particles" toggle only ever *hides further*, never restores
      something the server already excluded - confirmed by reading
      `viewer.html`'s `hiddenGroups` handling before relying on it).
      **Verified live in Chrome**: loaded `flag`, confirmed the canton is
      now at the top-left near the pole, right-side up. Switched to
      `flagOnRope` (via a direct `<select>` value + `change` event, since
      the native dropdown isn't drivable through click coordinates),
      manually orbited the camera (the scripted default camera doesn't
      frame this scene's flag), and confirmed: the flag renders with the
      same upright texture: no grid-vertex dots on the flag while the
      rope's own dots remain visible and draggable-looking as before; no
      console errors.
      **Third follow-up, same session - hide all of `flagOnRope`'s
      particle dots, and a general per-surface polyline toggle**: the
      user asked for two more things in one message - every particle
      (not just the flag's) invisible by default in `flagOnRope`, and a
      way to turn any surface's structural-spring lines on/off, generally,
      not a one-off fix. `FlagOnRopeScene.frame`'s `visibleIds` changed
      from `poleIds + ropeIds` to `emptySet()` - a real, discussed
      trade-off, not a silent one: the pole/rope dots become unreachable
      through the UI in this scene (the client's per-group "show
      particles" toggle can only hide further, never restore something
      `visibleIds` already excluded, same limitation noted in the
      previous follow-up), matching "all of the object's dots invisible"
      literally rather than adding a reveal path nobody asked for.
      **The polyline toggle is entirely client-side, no wire change**: a
      connection whose endpoints are both vertices of a mesh, adjacent
      within one of that mesh's own triangle edges, *is* one of that
      surface's polylines - `viewer.html`'s `applyFrame` derives this
      itself every frame from `frame.meshes[].triangles` (three edges per
      triangle, `"minId-maxId" -> surface name`) rather than the server
      tagging anything new. This was deliberately **not** built by
      repurposing `connectionNames`/`c.forceName`, even though FlagScene
      already tags its structural connections that way: that tag is
      load-bearing for the groups panel's spring/damper tab
      (`containedForceNamesForGroup` looks up `renderEditableFields("force",
      forceName, ...)` by that exact string), so retagging it with a
      surface name instead would have silently broken editing a group's
      spring/damper fields. Deriving the association from mesh topology
      instead needed no new field, no `BinaryFrame`/`BinaryFrameTest`
      changes, and works for any connection from any force, not just the
      ones this codebase happens to name.
      New `shownSurfaceLines` (viewer-local, alongside `hiddenGroups`/
      `hiddenSurfaces`/`hiddenForces`) is the one visibility set with
      **inverted polarity** on purpose - every other one defaults empty =
      "everything shown, opt in to hide"; this one defaults empty =
      "everything hidden, opt in to show," which is what makes "invisible
      by default" require zero seeding logic and no per-scene server
      signal at all. `addPanelToggle` gained an `invert` parameter (default
      `false`, so every existing caller's behavior is unchanged) rather
      than a second near-duplicate function. The surfaces panel's new
      "show polylines" checkbox only appears when the selected surface
      actually owns a connection this frame (`latestSurfaceLineOwners`),
      the same "don't offer a toggle that would do nothing" precedent the
      forces panel's `hasArrows`-gated "show arrows" toggle already set.
      **A real, accepted consequence, not a bug**: since the mechanism is
      generic and starts every surface's polylines hidden, `FlagScene`'s
      own flag - which used to show its structural grid lines by default -
      now starts with them hidden too, the same as `flagOnRope`. The user
      asked for the *general* capability, not a scene-scoped exception, so
      this is the honest result of one consistent default rather than
      bespoke per-scene plumbing.
      **Verified live in Chrome** (after actually confirming the reported
      bug first: reloading `flag` right after the polyline change showed
      no grid lines by default, and zooming on the flag with `flag.
      cloth-mesh` selected showed lines appear when "show polylines" was
      checked and disappear again when unchecked - toggled both directions,
      not just "the lines are gone"). Switched to `flagOnRope` (had to
      kill a stale demo server process still holding the WebSocket port
      first - the viewer showed "disconnected - retrying", not a code
      bug): confirmed zero dots anywhere (flag, pole, and rope all bare),
      confirmed the flag's own polylines are hidden by default same as
      `flag`, selected `flag.cloth-mesh` in the outliner, confirmed the
      "show polylines" checkbox is present and toggling it on/off shows/
      hides the lines there too. No console errors from the app itself
      (one unrelated Chrome-extension-internal exception observed, not
      from this page's own code).

## Expression-source visibility, flag pole/rope/self-collision, and surface break-limit editing (§7.3/§10.4/§12.4, new requirements) — not yet phased
Raised by the user directly, recorded in `requirements.md` rather than
implemented immediately - captured here so the next session picks them
up instead of losing them. The user listed "the flag's particles should
not be able to penetrate the flag surface" twice in slightly different
wording; recorded once below, not as two separate items.
- [x] Show a field's **current expression source**, not just its live
      evaluated value, in every §10.4 expression-editable control
      (requirements.md §10.4's new cross-cutting bullet). Covers all
      three fields that were actually expression-string-editable already:
      particle mass/radius, `Emitter.rate`, `Wind.velocity` (collider
      position live-editing isn't built at all yet, so it's out of scope
      here - nothing to show a source *for*).
      **Presentation, decided**: a second read-only line (`expr: <source>`,
      dimmed) placed right below the existing live-value input, never
      replacing it - the live number/vector is still "the right thing to
      show at rest" (requirements.md's own reasoning: freezing the
      display at a stale formula while a time-varying value keeps moving
      underneath it would be misleading in its own way), so this is
      purely additive. The row is always created (never conditionally
      appended) and toggled via the DOM `hidden` attribute in a shared
      `updateExpressionSourceRow` helper instead, so a source that only
      becomes known *after* the panel is already open (the user's own
      edit just landed) can still appear without needing a full panel
      rebuild - `renderPanel` only re-runs on selection change or a
      registry *signature* change, which deliberately excludes per-field
      values.
      **`ScalarExpr`/`VectorExpr` now retain the source**: both gain a
      `source: String?` (null for a Kotlin-literal/native-lambda value,
      set only by `ExpressionParser.parseScalar`/`parseVector`).
      **The `Constant` equality trap, caught before it shipped**: `source`
      is deliberately kept *out* of `Constant`'s primary constructor even
      though `Constant` is a `data class` - putting it there would fold
      `source` into the generated `equals`/`hashCode`, silently breaking
      `SceneControlMessageTest`'s existing `SetParticleScalarField`/
      `SetEmitterRate` round-trip assertions (which compare a
      parser-produced constant against a directly-constructed one with no
      source). Instead `source` is a body `var` with an `internal set`,
      populated via a new `ScalarExpr.of(value, source)`/
      `VectorExpr.of(value, source)` factory - excluded from a data
      class's generated equality entirely since it isn't a primary
      constructor property. A new regression test
      (`ExpressionParserTest`) asserts a parsed constant still equals a
      literal one with the same value.
      **Wind/Emitter were nearly free**: both already retain their live
      `VectorExpr`/`ScalarExpr` in a field (`Wind.velocity`,
      `Emitter.rate`), so `currentVelocitySource()`/`currentRateSource()`
      are one-line accessors reading `.source` off what's already there.
      **Particle mass/radius needed real storage**: `ParticleStore`
      evaluates a constant expression once at creation and never retains
      the `ScalarExpr` itself (only a *time-varying* one survives, in the
      existing `massExprs`/`radiusExprs` maps) - so a constant's source
      text would otherwise vanish immediately. Fixed with two new sparse
      `HashMap<Int, String>` maps (`massSourceById`/`radiusSourceById`,
      updated in `create`/`setMass`/`setRadius`/`destroy`) that retain the
      source string for *either* kind, separately from the (unretained)
      constant value itself.
      **Wire protocol**: `RegistryEmitterEntry`/`DecodedEmitterEntry` gain
      `rateSource`, `RegistryWindEntry`/`DecodedWindEntry` gain
      `velocitySource` (both `""` on the wire = absent, the format's
      existing convention) - free to add since both lists already exist
      per-frame. A genuinely new section,
      `RegistryParticleExpressionEntry`/`DecodedParticleExpressionEntry`
      (id-addressed `{particleId, field, source}`), was added for
      particle mass/radius - naturally sparse (only ever contains entries
      for particles actually live-edited via an expression string), so it
      costs nothing in the common case of a scene where this never
      happened. `BinaryFrame`'s own layout doc comment was extended to
      cover this new section *and* retroactively document the
      pre-existing emitter/wind sections, which had never been added to
      the ASCII layout diagram when they first landed.
      New tests: `ExpressionParserTest` (source round-trips for both
      constant/time-varying scalar and vector expressions, a
      directly-constructed value has no source, the `Constant` equality
      regression guard above), `ParticleStoreTest` (source recorded for
      both constant/time-varying `setMass`/`setRadius`, cleared by a
      literal replacement or by `destroy`, not recorded for a rejected
      edit), `WindTest`/`EmitterTest` (`currentVelocitySource`/
      `currentRateSource` null-then-set), and five new `BinaryFrameTest`
      cases covering the full wire round-trip for all three fields
      (including the "no known source" empty-list/null case for each).
      **Verified live in Chrome**: opened the `flag` scene's `wind` force
      panel, edited `velocity` to `"[6.0 + 2.0*sin(t), 0.3*sin(t*1.3),
      0.8*cos(t*0.9)]"` - a new `expr: ...` line appeared showing exactly
      that string beside the still-live-updating `velocity:` readout, and
      the wind arrows visibly changed direction confirming the edit took.
      Right-clicked a flag particle, edited `mass` to `"0.005 +
      0.001*sin(t)"` - the `expr:` line appeared under `mass:` with
      `radius:` (never touched) showing none. Opened the `sparks` scene's
      `fountain` emitter panel, edited `rate` to `"20 + 10*sin(t)"` - the
      `expr:` line appeared and the live `rate:` number changed to track
      the new formula (confirmed against the emitter's actual live values
      before/after, not just visually). No console errors from the app
      in any case (only an unrelated Chrome-extension "disconnected port"
      error, unaffiliated with this page).
- [x] **Follow-up, later session**: turn `Wind.velocity`'s single
      combined-vector edit box into three per-axis boxes (x/y/z), each
      showing *that axis's own expression source* rather than the live
      value, with the live evaluated vector moved to a new read-only
      `value: [x, y, z]` row underneath - the mirror image of the
      previous item's layout for this one field specifically (there,
      the input held the value and a companion row held the source;
      here, the three inputs hold the source(s) and a companion row
      holds the value). User-requested directly, not derived from
      requirements.md.
      **No server-side change at all.** `Wind.velocity` is still one
      `VectorExpr` with one combined source string
      (`ExpressionParser.parseVector` still parses a whole `"[x, y, z]"`
      literal) - `SceneControlMessage.SetWindVelocity`,
      `BinaryFrame`/`BinaryFrameTest`, and `Wind.kt` are all untouched.
      The three-box UI is client-side sugar over the exact same wire
      message: `viewer.html`'s new `splitVectorLiteralSource` parses a
      known `"[a, b, c]"` source into its three top-level comma-separated
      parts for display (bracket/paren-depth-aware, so a comma nested
      inside a function call isn't mistaken for an axis separator - not a
      re-implementation of the grammar, just enough structure-tracking to
      find the three top-level commas), and editing any axis reassembles
      all three boxes' current text into one `"[x, y, z]"` string sent
      through the unchanged `set_wind_velocity` message.
      **Deliberately not done by retagging `connectionNames`-style
      per-component sources onto `VectorExpr` itself** - the model change
      would have been sizeable (`VectorExpr`/`ScalarExpr` are used far
      beyond Wind: `Camera`, `Collider`, emitters, YAML loading) for a
      UI-scoped ask, and would only even be *possible* when the top-level
      parsed expression really is a 3-element bracket literal in the
      first place - `"[1,0,0] + [0,1,0]*sin(t)"` is legal vector-typed
      grammar too (§10.4's earlier "kept changing" bug fix already
      established this), and there's no way to isolate "the X part" of
      an expression like that without symbolic differentiation, which
      §12.5 already rejected adding to this parser for an unrelated
      reason. `splitVectorLiteralSource` returns `null` for anything that
      isn't literally an `[a, b, c]` literal, and every axis box falls
      back to that axis's own live value formatted as a plain numeric
      literal instead - itself already a valid expression, so editing
      from there works exactly like the field did before any source was
      known at all (true for every demo's own default `Wind`, which is
      built from a native Kotlin lambda with no source until first
      edited).
      **Per-axis live-update granularity, not "any box focused blocks all
      three"**: matches `updateEditableFieldsLive`'s own existing x/y/z
      vector-field pattern - editing x doesn't freeze a still-time-varying
      y or z from refreshing underneath it.
      **The old "must start with `[`" client-side check was removed
      entirely, not adapted** - it existed only to catch "typed a bare
      scalar into the one combined vector box," a mistake class that
      can't happen anymore now that each box is independently already a
      scalar expression. A malformed formula in one axis now fails
      silently server-side, same as every other expression field (mass,
      radius, emitter rate) already does with no client-side detection -
      not a new gap, just no longer a special case for this one field.
      **Verified live in Chrome**: opened `flag`'s `wind` panel - with no
      known source yet, all three boxes showed the live value as a plain
      number and visibly ticked every frame (expected: nothing to hold
      steady without a known formula). Edited `x` to
      `"10.0 + 2.0*sin(t*2.0)"` - the wind visibly changed, the `x` box
      kept showing that exact formula text (not a number) on every
      subsequent live update, and `value:` below tracked its live
      evaluated number changing over time. Edited `y` to `"0.5*cos(t)"`
      immediately after - `x`'s box was untouched by the `y` edit,
      confirming per-axis independence, and `value:` reflected both
      axes' live numbers simultaneously. `z` (never edited) stayed at
      its snapshotted numeric literal throughout. No console errors from
      the app itself (one unrelated Chrome-extension "disconnected port"
      exception observed, as in earlier sessions).
- [x] **Follow-up, immediately after the item above**: the user tried it
      live and found the fallback-to-value behavior itself was the
      problem, not just an edge case - `buildFlag`'s own `wind` (every
      flag-family scene's only `Wind`) was built from a native Kotlin
      lambda, so it had no source at all until the user's *own* first
      edit, and every fresh scene load showed three ticking numbers
      instead of the real formula. "I want to see the current expression
      to easily edit it" doesn't hold if the default demo never has one
      to show.
      **Fixed at the source, not the display**: `Flag.kt`'s `wind` is now
      built via `ExpressionParser.parseVector("[6.0 + 2.0*sin(t*0.7),
      0.3*sin(t*1.3), 0.8*cos(t*0.9)]")` instead of
      `VectorExpr.of { t -> Vector3(...) }` - the same formula, just
      authored as a parsed expression string so `VectorExpr.source` is
      populated from scene construction, not only after a live edit. This
      is exactly `FlagYamlParityTest`'s own `flag.yaml` wind-velocity
      string, verbatim - both front-ends already prove (via that test)
      that a parsed `"[...]"` literal evaluates bit-identically to the
      lambda form it replaces, so this wasn't taken on faith: confirmed
      by running the full suite (including `FlagGoldenTest`/
      `FlagYamlParityTest`) unchanged and green. No wire, `SceneFrame`,
      or `BinaryFrame` change - this is a one-line authoring change in
      one demo, using machinery (`ExpressionParser`) that already existed
      for the YAML front-end. `sin`/`cos`/`VectorExpr` imports dropped
      from `Flag.kt` as now-unused (only `Vector3` is still needed there,
      for particle placement and gravity).
      **Scoped to `buildFlag` specifically, not a sweep of every
      native-lambda-built expression field in this codebase** - it's the
      only one with a live-editing UI at all today (`Wind.velocity` is
      still the sole `VectorExpr`-editable field; scalar fields like
      emitter rate or particle mass are always either a literal or
      already source-tracked). A future field gaining this same
      no-source-until-edited gap should get the same fix once it actually
      has an editing UI to matter for, not preemptively here.
      **Verified live in Chrome**: loaded `flag` fresh (no edits at all)
      and selected `wind` - `x`/`y`/`z` immediately showed
      `6.0 + 2.0*sin(t*0.7)`, `0.3*sin(t*1.3)`, `0.8*cos(t*0.9)` rather
      than ticking numbers, with `value:` below tracking the live
      evaluated vector as before. Switched to `flagOnRope` (which reuses
      `buildFlag`'s wind unchanged) and confirmed the identical formulas
      appear there too, unedited. No console errors from the app itself.
- [x] Flag surface self-collision: the flag's own particles can't pass
      through its own surface as it billows/folds (requirements.md
      §12.4's "Surface self-collision" bullet). Genuinely new physics, not
      a wiring exercise like the rope/pole item above - nothing like a
      surface colliding with itself existed anywhere in this codebase.
      New `particlesim.collision.SurfaceSelfCollisionRule`/
      `SurfaceSelfCollisionSystem`, mirroring `SurfaceCollisionSystem`'s
      own restitution/damping/rest-clamp formulas (§12.7) but for a
      genuinely new case: both sides of a contact are the *same* kind of
      finite-mass cloth particle, not a query particle against a
      surface owned by someone else, so **the positional correction is
      split across both sides by inverse mass, not applied query-only**
      like `SurfaceCollisionSystem.respond` - leaving the triangle's own
      vertices uncorrected would silently favor whichever vertex's query
      happened to run first in a step's iteration order.
      **The real new mechanism is the exclusion topology**: a vertex is
      trivially "penetrating" its own incident triangles (zero distance,
      by construction) and its immediate neighborhood, whose ordinary
      resting curvature routinely sits closer than any reasonable
      thickness with no real fold involved - `excludeRings` walks the
      mesh's *triangle-edge* adjacency graph (two triangles adjacent iff
      they share an edge, not merely a vertex - tighter than shared-vertex
      adjacency, which fans out through high-valence vertices much faster
      than the surface itself spreads) outward from each vertex's own
      triangles, and only triangles beyond that topological radius are
      checked. Precomputed once per rule at construction (a `Surface`'s
      triangle list is fixed for a scenario's lifetime, §14.3), not
      recomputed per step.
      **Parameters tuned empirically against real data, not guessed** -
      via a throwaway scratch `main()` + temporary Gradle task (both
      deleted after use, same "spike test... then deleted" pattern the
      Arrow recording work used): at `thickness` comparable to the flag's
      own row spacing (0.15), `excludeRings=0` fired on literally every
      single one of 4000 steps from step 0 (pure local-curvature false
      positives, confirming the exclusion mechanism is load-bearing, not
      decorative), `excludeRings=1` still fired on 3999/4000, and
      `excludeRings=2` dropped to 698/4000 starting around step 905 -
      genuine folds only - with `excludeRings=3`/`4` producing bit-identical
      results, confirming 2 is the minimum that actually excludes local
      curvature. Landed with `thickness=0.05` (~1/3 of row spacing) and
      `excludeRings=2`. Performance: raw physics throughput drops ~10x
      with self-collision on (~17,700 -> ~1,777 steps/s in the same
      scratch harness), but that's still comfortably above the flag
      scene's live-demo ceiling (~850-900 steps/s even with no
      self-collision, bound by rendering/broadcast overhead per Phase 5's
      own "same lag stat at 18 vs 2000 particles" finding) - confirmed
      live, steps/s read ~411-508 with self-collision on, not the
      catastrophic drop a naive O(V*T) brute force could have caused.
      **Wired into `FlagScene`, not `FlagScenario`/`buildFlag`** - a
      deliberate scoping choice: `buildFlag` is also consumed by
      `FlagGoldenTest`/`FlagYamlParityTest` (byte-identical-output proofs
      that never call any `resolve()`), `FlagOnRopeScene`, and
      `MultiShapeScene`, none of which should carry this by default. Built
      from `scenario.surface` inside `FlagScene` the same way `clothMesh`/
      `windArrows`/`camera` already are, and resolved once per physics
      step (`FlagScene.step()`, right after `integrator.step()` -
      `SceneLibrary.advanceOneStep()` calls `scene.step(t)` once per
      physics step, not once per frame, confirmed by reading the runner
      loop rather than assumed). `flagOnRope`/`multiShape` deliberately
      don't get this yet - same "wiring a second consumer, pick it up
      when needed" scoping the rope/pole item above used.
      New tests: `SurfaceSelfCollisionTest` (a flat resting mesh produces
      zero corrections - the discriminating negative case a positive-only
      test suite would miss; `excludeRings=0` at a thickness comparable to
      grid spacing does fire on a flat mesh's own local curvature,
      confirming the exclusion mechanism is actually applied; a vertex
      folded directly onto a topologically-distant triangle is pushed back
      out *and* the triangle's own vertices react), `FlagSelfCollisionStabilityTest`
      (a separate file from `FlagStabilityTest`, which documents the
      no-self-collision baseline and shouldn't itself change - 4 seconds
      with self-collision wired in, same `maxSpeed < 50` smoke check).
      **Verified live in Chrome**: loaded `flag`, confirmed no console
      errors over an extended run. Used the live-editable `wind` panel
      (§10.4) to drive a strong reversing wind (`[-25, 0, 0]` then
      `[30, 0, 0]`) specifically to force a tight self-fold - the sheet
      visibly curled into a rolled/tube shape rather than collapsing flat
      through itself, held that rounded shape (not a flattened double
      layer) under continued wind, and stayed stable (no spikes, no
      NaN/blow-up) for the duration observed. The Chrome extension
      connection dropped mid-session (an environment issue, not
      reproducible from the app side - no error on the page itself)
      before a second reversal could be captured; not re-attempted, since
      the automated test suite (including the two discriminating
      self-collision tests above) and the live behavior already observed
      cover the mechanism's correctness independently of that one
      interrupted browser session.
      **Follow-up fixes from a second review pass, before this was
      considered done** (all four caught before shipping, not filed as
      separate bugs):
      1. The original fold test placed the query vertex *exactly* on the
         triangle's closest point, so `dist ≈ 0` and `deepestContact`
         fell back to its degenerate-normal case (`(0,1,0)`) - which
         happens to be the physically correct direction on that test's
         flat, y=0 fixture, so the test passed for the wrong reason and
         wouldn't have caught a sign error or missing normalization in
         the real (non-degenerate) branch. Fixed by offsetting the vertex
         off the triangle's plane by less than `thickness` instead, and
         asserting the *distance to the triangle strictly increased*
         after `resolve()`, not just that the position changed.
      2. §12.5's "constrained particles behave as infinite mass in
         collision response" (already implemented for
         `ParticleCollisionSystem`, CLAUDE.md-locked) was silently not
         honored here - the flag's own pole-edge column is
         `FixedPosition`-pinned and is a real triangle vertex in
         self-contacts, but was absorbing a finite share of the impulse/
         positional correction that the constraint then discarded on the
         very next step, silently under-correcting the free side.
         `resolve()` now takes the step's live `groups`/`constraints`
         (mirroring `ParticleCollisionSystem`'s own signature exactly)
         and zeroes a pinned vertex's inverse mass via
         `Constraint.pinnedIds`, with the same "every side pinned - an
         immovable object meeting one" divide-by-zero guard
         `ParticleCollisionSystem.respond` already uses. New test: a
         pinned query vertex never moves and the triangle it lands near
         absorbs the entire correction instead of splitting it.
      3. `compressionDamping` was dead weight at the shipped
         `restitution = 0.0` default - the formula multiplied the whole
         damped term by `restitution`, so any `compressionDamping` value
         produced identical output. Removed rather than documented around
         (§12.5-adjacent CLAUDE.md minimalism: no knob that does nothing
         at every value this rule ships with) - the doc comment now
         explains restitution = 0.0 as a hard inelastic stop, and notes
         where a damping term would need to come back if a future
         scenario wants genuine bounce.
      4. `FlagScene`'s own comment overstated the tuning data: it claimed
         `excludeRings = 2` was "confirmed necessary," but the scratch
         run only showed rings 0-1 falsely firing at `thickness = 0.15`
         (comparable to row spacing) - at the *shipped* `thickness =
         0.05`, rings 0/1/2 all produced identical results, so 2 is a
         safety margin at these parameters, not a measured floor. Comment
         corrected to say exactly that, so a future tuner raising
         `thickness` toward spacing knows that's when the floor starts to
         bind and re-checking is warranted.
- [x] Put the flag on a pole the way `MultiShapeScene` already composes
      `buildFlagpole` + `buildFlag` by placement (requirements.md §7.3) -
      confirmed this part needed nothing new: `MultiShapeScene` already
      does exactly this (`buildFlag`'s placement offset is
      `Vector3(0, poleHeight, 0)`, putting the flag's top row level with
      the pole's own top). Not yet pulled into the flag scene proper or
      the new `poleRope` scene below - still open, tracked in the next
      item now that the rope it needs to attach to actually exists.
- [x] A new **rope** shape (`particlesim.examples.buildRope`) - a chain
      of particles between two fixed anchors, connected by individual
      `Spring`/`Damper` pairs per link (the same per-edge pattern
      `buildTire` already uses for a similarly small edge count, not
      `MeshSprings`' one-`Force`-for-thousands treatment a mesh needs).
      **Slack, not rigid**: `compressionStiffness = 0.0` by default,
      requirements.md §5.4's own stated reasoning for why a rope
      shouldn't resist compression the way a rod would - with both ends
      pinned, this is what lets it sag into a natural drape under gravity
      instead of holding a straight rigid line. Anchors are relative to
      `ShapePlacement.offset`, the same convention every other shape
      uses. Stiffness/mass picked against the same §13.1 budget
      `buildFlag`'s structural springs already use
      (`2*sqrt(0.005/200) ≈ 0.02`, ~20x margin over `FLAG_DT = 1e-3`),
      since this shape's first consumer will share that dt.
      **A real physical subtlety, not just wiring, caught during live
      verification**: the first version of the demo scene below placed
      the rope's top and bottom anchors on the exact same vertical line
      (same x/z, only y differing) - a degenerate case for a slack chain,
      since the straight-line rest configuration is then already
      parallel to gravity, with no lateral direction to sag *into*. Live
      in Chrome it showed up as a settled, motionless rope bunched
      exactly along its initial straight layout (`avg speed: 0.000 m/s`
      in the group panel, centroid unchanged from the initial average) -
      correct-looking arithmetic, wrong physical setup. Fixed by giving
      the bottom anchor a genuine sideways offset from the top anchor
      (matching a real halyard: a pulley at the pole's top centerline,
      a cleat mounted on the pole's *side* partway down) - not by
      changing `buildRope` itself, which already handled a diagonal
      anchor pair correctly (see `RopeTest`'s own diagonal setup, which
      caught none of this because it was never degenerate to begin with).
      6 new tests (`RopeTest`): even spacing between anchors before
      stepping, too-few-segments rejection, placement offset applied to
      both anchors and every particle between them, both anchors
      provably immovable under stepping (bit-identical position after
      2000 steps under gravity), a diagonal-anchor rope sags below the
      straight line between its anchors and stays finite over 4
      simulated seconds, and instance-name namespacing (including that
      an anchor particle correctly belongs to *both* the rope group and
      a separate anchors-only group, needed for the `FixedPosition` call,
      while an interior particle belongs only to the former).
      **New library scene, `poleRope`** (`particlesim.debug.PoleRopeScene`,
      reachable via the picker or `./gradlew runSceneLibraryDemo
      --args="poleRope"`) composes `buildFlagpole` + `buildRope` alone -
      no flag yet, deliberately: verifying the rope's own shape/behavior
      in isolation first, before wiring anything to it, mirrors the
      staged approach `RopeTest` already takes at the unit level, and
      keeps the well-known-good `flag` scene untouched as a reference
      while this settles (per-scene isolation, not a replacement).
      **Verified live in Chrome**: 18 particles (7 pole + 11 rope) load
      and render as two line-connected chains via the outliner's
      `pole.pole`/`rope.rope`/`rope.rope-anchors` groups and
      `rope.rope-anchor` constraint; after the anchor-geometry fix above,
      the rope visibly drapes into a diagonal bow between its two anchors
      rather than sitting perfectly straight, holds that shape unchanged
      after 5+ further seconds of running (settled, not oscillating or
      drifting), and the pole's own particles stay exactly fixed
      throughout. No console errors.
- [x] Attach the flag's pole-side edge to the rope's top portion instead
      of directly to the pole via `FixedPosition.atCurrentPositions`
      (requirements.md §7.3) - a real behavior change to the existing
      flag worked example (the attachment becomes dynamic/swaying, not
      rigidly fixed), not just an added decoration alongside it.
      Landed as `particlesim.debug.buildFlagOnRopeScenario` +
      `FlagOnRopeScene` (`particlesim/debug/FlagOnRopeScene.kt`), reachable
      via the picker or `./gradlew runSceneLibraryDemo
      --args="flagOnRope"` - a plain top-level function colocated with its
      one consumer scene (not a new `examples/build*` shape, since unlike
      `buildRope`/`buildFlagpole`/`buildFlag` this composition has exactly
      one consumer and isn't meant to be composed further), so
      `FlagOnRopeSceneTest` can assert on its internals through a real
      return value rather than reaching through `DemoScene`'s narrow
      public surface. Composes `buildFlagpole` + `buildRope` + `buildFlag`
      and deliberately never forwards `FlagScenario.constraints` (the
      flag's own `pole-anchor` `FixedPosition` pin) into the scenario's own
      constraints - instead, each `flagGrid[row][0]` pole-edge particle is
      connected to `rope.ropeIds[row]` by its own `Spring`/`Damper` pair
      (stiffness/damping matching the flag's own structural springs, 200.0
      / 1.0), so the flag now hangs from a dynamic rope instead of a rigid
      pin.
      **Row-for-row vertical alignment is exact by construction**: the
      rope's `ropeSegments` (13) and its bottom anchor's height are chosen
      so `(topAnchor.y - bottomAnchor.y) / ropeSegments` exactly equals
      the flag's own row `spacing` (0.15) - every attached row's rope
      particle sits at exactly the same height as its flag-edge
      counterpart, verified in `FlagOnRopeSceneTest` (`each flag pole-edge
      row starts at the same height as its corresponding rope particle`,
      checked to 1e-9). Only a small, deliberate sideways gap remains (the
      rope drifts toward `ropeBottomOffsetX = 0.15` while the flag's edge
      stays at `x = 0`, at most ~0.08 by the flag's lowest attached row) -
      what gives the attachment springs a small nonzero rest length
      (computed from the particles' actual initial distance, per-row, at
      construction) rather than a rigid zero-length pin. The rope has more
      segments (13) than the flag has rows (8), so its bottom portion
      continues on past the flag's lowest attached row down to its own
      real anchor "partway up the pole" - the requirement's literal "top
      portion of the rope" phrasing, kept visually distinct in the scene's
      connection list. A `require` rejects a rope with too few segments to
      reach every flag row (checked before any particles are built).
      Five new component/stability tests in
      `src/test/kotlin/particlesim/debug/FlagOnRopeSceneTest.kt`: the
      row-height alignment above; too-few-rope-segments rejected via
      `IllegalArgumentException`; the flag's own `pole-anchor` constraint
      is absent from the composed scenario while the rope's anchor
      constraint is still present; the flag's bottom-row pole-edge
      particle actually moves over 2 simulated seconds (the discriminating
      check that the attachment is dynamic, not still effectively frozen);
      and a 4-second stability smoke test (max particle speed stays under
      50 m/s) for the full pole+rope+flag assembly, mirroring
      `FlagStabilityTest`'s own approach.
      **Verified live in Chrome**: `flagOnRope` scene loads with 133
      particles (7 pole + 14 rope + 112 flag = 8 rows x 14 cols), renders
      the pole/rope as thin nearly-overlapping vertical lines (the small
      sideways gap isn't visually distinguishable at this scale, as
      expected) with the flag's cloth mesh swaying naturally under wind
      from its attached edge; watched continuously from t=28s to t=93s
      (65+ more simulated seconds) with the flag's shape visibly changing
      frame to frame as wind direction varies - confirming the edge is
      genuinely dynamic, not a frozen copy of the old rigid pin - and no
      NaN/blow-up. No console errors. The scene picker dropdown correctly
      lists `flagOnRope` alongside every other scene.
- [x] Rope/pole vs. flag surface collision: neither the rope nor the pole
      can pass through the flag's surface (requirements.md §12.4's
      "Particle vs. triangulated surface" bullet). Confirmed wiring, not
      new physics, per this item's own prior note - `SurfaceCollisionSystem`/
      `SurfaceCollisionRule` (proven by the trampoline worked example) are
      reused unchanged; the only new code is getting the pole/rope
      particles into a collidable group with a radius and pointing a rule
      at the flag's `Surface`.
      **Two real gaps closed to make that possible, not just parameter
      wiring**: (1) `buildRope`/`buildFlagpole` never gave their particles
      a `radius` at all (`ParticleStore.create`'s default `null`, §12.1's
      "no radius set -> skip"), so neither shape could ever be a collision
      participant - both gained an optional `particleRadius: Double? =
      null` parameter (mirroring `Tire.kt`'s existing `particleRadius`
      pattern), defaulting to the prior not-collidable behavior so
      `PoleRopeScene`/`MultiShapeScene`/every existing test is unaffected;
      only `buildFlagOnRopeScenario` now passes an explicit radius
      (`poleCollisionRadius = 0.03`, `ropeCollisionRadius = 0.015`).
      (2) `SurfaceCollisionRule` targets one *group name*, but neither
      `FlagpoleScenario` nor `RopeScenario` exposes the group name its own
      particles were added to (only their id lists) - rather than
      reconstructing that internal literal a second time in
      `buildFlagOnRopeScenario` (a real duplication risk), both shapes'
      pole/rope ids are added to one new scenario-local group,
      `poleRopeCollidable`, reusing §4.2's "groups as the universal
      selector" the way every other cross-cutting selector in this
      codebase already does, rather than inventing a second targeting
      path.
      **`FlagOnRopeScenario` gains a `collisions: SurfaceCollisionSystem`**
      field (one rule: `poleRopeCollidable` vs. `flag.surface`, low
      restitution 0.1 / compressionDamping 2.0 / extensionDamping 0.5 -
      deliberately damped-settle, not bouncy, since this pairing exists to
      stop visible interpenetration, not to make the pole/rope bounce off
      the cloth like a dropped ball). `FlagOnRopeScene.step()` now calls
      `scenario.collisions.resolve(...)` right after `integrator.step(...)`,
      the same two-call pattern every other collision-bearing scene
      (trampoline, ball-bounce, particle-collision) already uses.
      New test (`FlagOnRopeSceneTest`): a rope particle forced to sit
      exactly on a flag surface triangle is measurably pushed back out on
      the very next `resolve()` call - the discriminating proof that the
      rule is actually wired to the right group/surface pair, not just
      constructed and never resolved. The existing 4-second stability test
      now calls `collisions.resolve()` every step too (previously it only
      exercised forces/constraints), matching `TrampolineStabilityTest`'s
      own pattern, and still passes.
      **Verified live in Chrome**: loaded `flagOnRope`, confirmed the new
      `poleRopeCollidable` group lists in the outliner alongside the
      existing groups. Used the now-live-editable `flag.wind` panel
      (§10.4) to drive the flag hard toward the pole/rope from both sides
      (`velocity` set to `[-12, 0, 0]` then snapped to `[20, 0, 0]` to
      sweep it back through at speed, the strongest test available without
      pausing to script a synthetic contact) - in every frame captured,
      the cloth's leading edge stayed clear of the pole/rope line instead
      of crossing to its far side, then settled back to a normal billow
      with no visible penetration, no NaN/blow-up, and no console errors
      (aside from the same unrelated Chrome-extension "disconnected port"
      noise this project has already flagged as unaffiliated elsewhere).
- [x] A surface's spring/damper **breaking limits**
      (`breakThreshold`/`extensionBreakThreshold`/
      `compressionBreakThreshold`) exposed as editable alongside
      stiffness/damping in the group's springs/dampers tab
      (requirements.md §10.4/§5.4). `extensionBreakThreshold`/
      `compressionBreakThreshold` promoted from `private val` to
      `private var` on `Spring`, `Damper`, and `MeshSprings`, and added to
      each class's existing `editableFields()`/`setField()` map alongside
      stiffness/damping - **zero frontend/wire-protocol changes needed**:
      `BinaryFrame.collectEditableFields` already flattens *every*
      `EditableFields` field a named force exposes into the generic
      `fields` list every frame, and `viewer.html`'s `renderEditableFields`
      already renders whatever it finds there for a group's qualifying
      spring/damper/`MeshSprings` tab - confirmed by reading that whole
      chain before writing any code, not assumed. `setField` on all three
      classes now rejects `NaN` (`value.value.isNaN()) return false`),
      the same "a live edit is user input arriving over the wire and must
      never silently corrupt state" stance `ParticleStore.setMass` already
      documents - without this, an emptied number input sending
      `parseFloat("") === NaN` would have set a break threshold to `NaN`,
      breaking every edge on the very next `accumulate` call (`NaN`
      comparisons are always false, so `shouldBreak`'s displacement check
      would misbehave rather than throw, a silent corruption rather than
      a crash).
      **A real UX wrinkle caught before it shipped, not after**: every
      threshold defaults to `Double.POSITIVE_INFINITY` ("never breaks") -
      the first editable field in this codebase with a non-finite default.
      A `type="number"` input can't hold `Infinity` as an actual value
      (browsers silently coerce it to an empty box), which would have
      looked indistinguishable from "value 0" or a rendering bug with zero
      indication of what it actually means. Fixed generically in
      `viewer.html`'s shared `renderEditableFields`/`updateEditableFieldsLive`
      (not special-cased to break thresholds) - a non-finite scalar value
      now renders as an empty input with a `"∞ (no limit)"` (or `"-∞"`)
      placeholder instead, and an untouched-and-still-empty input no
      longer sends an edit at all (guards the same accidental-NaN path
      from the client side too, belt-and-suspenders with the server-side
      `setField` guard above).
      **Breaking is still permanent, editing the threshold doesn't undo
      it**: lowering a threshold below an edge's current displacement/
      relative-velocity breaks that edge on its very next physics step,
      same as reaching it through normal simulation; raising the
      threshold back up afterward does not restore an already-broken edge
      (§5.4's "breaking is permanent" applies exactly the same to a
      live-edited threshold as to one reached by simulation alone) - noted
      here explicitly so this isn't later mistaken for a bug.
      8 new/updated tests in `ForceComponentTest.kt`: `Spring`/`Damper`/
      `MeshSprings`' existing `editableFields()` tests updated to expect
      the two new infinite-default entries; three new tests (one per
      class) confirming the break-threshold pair is readable, settable,
      and that `setField` rejects `FieldValue.Scalar(Double.NaN)` (returns
      `false`, leaves the previous value in place).
      **Verified live in Chrome** against the `flag` scene: selected the
      `cloth` group, opened its `structural` spring tab, confirmed
      `extensionBreakThreshold`/`compressionBreakThreshold` both render as
      empty inputs with the `∞ (no limit)` placeholder at their infinite
      defaults (not blank-looking-like-zero); typed `0.01` into
      `extensionBreakThreshold` and tabbed out - the input kept showing
      `0.01` on every subsequent live-refresh (confirming the edit stuck,
      not reverted), and the flag's cloth mesh visibly changed shape
      within a few seconds (structural edges breaking under normal
      wind-driven flapping displacement, well within a threshold that
      tight). No console errors.

## Universal entity outliner reachability, viewer precision sweep, and flag camera default (§9.6/§10.3/§10.4, follow-up) — not yet phased
- [x] Every force/constraint/surface/collider across every scene-library
      scene now reaches the outliner and gets a working panel, not just a
      hand-picked subset. Root cause: `SceneRegistry.build` filters
      forces/constraints/surfaces/colliders by `it.name != null` before
      they ever become outliner-reachable, and the overwhelming majority
      of `Force`/`Constraint`/`Collider` constructions across the
      `examples`/`debug` packages left `name` at its `null` default -
      not a bug in the outliner/panel UI itself (that machinery, audited
      first, was already correct end to end), but a silent gap upstream
      of it. Named every previously-anonymous construction: `Flag.kt`'s
      `gravity`; `Trampoline.kt`'s `gravity`/`ball-gravity` and its three
      `MeshSprings` (`structural-springs`/`shear-springs`/`bend-springs`);
      `Sparks.kt`'s `gravity`/`drag`/`floor`; `DragScene.kt`'s
      `gravity`/`drag` and all 11 per-link dampers (`link-$i-damper` -
      the springs were already named, the dampers never were);
      `Tire.kt`'s `gravity` and every rim/diameter spring and damper
      (`rim-spring-$i`/`diameter-spring-$i`/`rim-damper-$i`/
      `diameter-damper-$i`); `Rope.kt`'s `gravity` and every segment
      spring/damper (`segment-spring-$i`/`segment-damper-$i`);
      `BallBounce.kt`'s `gravity`; `Flagpole.kt`'s pole-pin `FixedPosition`
      (`buildFlagpole` had no way to name it at all - now
      `placement.name("pole-anchor")`, matching `buildFlag`'s own
      pole-anchor); `FlagOnRopeScene.kt`'s per-row attachment
      springs/dampers (`attachment-spring-$row`/`attachment-damper-$row`).
      Every `placement`-aware shape uses `ShapePlacement.name(...)` so
      composed scenes (`multiShape`, `flagOnRope`, `poleRope`) get
      collision-free instance-prefixed names (`tire.gravity`,
      `flag.gravity`, ...) for free.
      **Two scenes had structural gaps beyond naming**: `BallBounceScene`
      never called `SceneRegistry.build` at all - `frame()` returned a
      bare `SceneFrame()`, so its group/force/collider were *entirely*
      absent from the outliner regardless of naming. `Sparks.kt` and
      `Tire.kt` each built their own floor `PlaneCollider` but never
      exposed it on `SparksScenario`/`TireScenario`, so even a named
      floor had no path to either the registry or the actual rendered
      wireframe (`SceneFrame.colliders`, a separate field from
      `registry` - the wire-format list `DebugRenderer` actually draws
      wireframes from) - both scenarios gained a `floor: PlaneCollider`
      field, and `SparksScene`/`BallBounceScene`/`MultiShapeScene` (which
      composes both `buildTire` and `buildBallBounce`) now pass those
      colliders into both `registry` and `colliders`. `MultiShapeScene`'s
      own doc comment previously asserted colliders "aren't wired" for
      exactly this reason - now stale and corrected alongside the fix.
      All pre-existing tests pass unchanged (naming a force doesn't
      change its physics); no new tests added - this is wiring, not new
      behavior, and every affected scene's existing physics/analytic
      tests already cover the underlying force/constraint math.
      **Verified live in Chrome** against every one of the 10 scene-library
      scenes (`flag`, `sparks`, `ballBounce`, `trampoline`, `multiShape`,
      `flagOnRope`, `drag`, plus the already-correct `particleCollision`/
      `spatialGrid`/`poleRope`): confirmed every force, constraint,
      surface, collider, and emitter now lists in the outliner with a
      working panel, including previously-invisible cases like
      `ballBounce`'s entire registry, `sparks`' `floor` collider (now
      actually rendered as a wireframe plane, not just named), and
      `multiShape`'s `tire.floor`/`ball.floor` colliders (both now
      visibly rendered where neither was before).
- [x] Viewer decimal-value precision: every numeric readout now shows
      at least three significant figures via the existing `formatSigFigs`
      helper (`x.toPrecision(3)`, trailing zeros/exponential notation
      stripped), not just particle mass/radius (already fixed in the
      prior session). Extended to `formatVec` (position/velocity
      read-only display), `formatVectorExprLiteral` (wind's editable
      velocity vector - superseding the prior session's `toFixed(2)`
      choice for it, since a small component like a `0.03` wind gust
      would round to a single significant figure under fixed-decimal
      rounding), group centroid/avg-speed and surface avg-vertex-speed
      text, every generic scalar/vector `EditableFields` input
      (`renderEditableFields`/`updateEditableFieldsLive` - spring
      stiffness, damper coefficient, break thresholds, ...), and the
      emitter panel's `rate` field, which previously displayed the raw
      evaluated double with full floating-point noise (e.g.
      `13.470015621365189`) since it read `String(entry.rate)` directly
      rather than going through any formatting helper at all.
      Deliberately left untouched: the top-bar `t=`/lag/steps-per-second
      HUD text - session/performance chrome, not simulation-entity data,
      so out of scope for this pass.
      **Verified live in Chrome**: the `flag` scene's wind velocity panel
      now shows `[6.41, -0.222, -0.646]` (3 significant figures per
      component, not fixed-decimal); the `sparks` scene's `fountain`
      emitter rate panel now shows a clean `6.09` instead of a
      17-character noisy float.
- [x] Flag demo's scripted camera off by default: `flag` is the only
      scene-library scene with an engine-scripted camera at all today
      (`FlagScene.kt`'s own `CameraFunction`, §10.1), and its orbit
      fights right-clicking a particle to inspect it - now defaults to
      manual (viewer-controlled) camera on both the very first frame the
      viewer ever receives (`applyFrame`, gated by a one-shot
      `hasSetInitialCameraMode` flag keyed off that first frame's
      `activeScene`) and every later scene switch/restart
      (`resetViewerStateForSceneChange`, now parameterized by the target
      scene name instead of hardcoding `"scripted"`), via a single new
      `defaultCameraModeForScene(sceneName)` helper. Every other scene
      keeps defaulting to scripted, unchanged. The existing "return to
      scripted camera" button still re-enables the flag's orbit on
      request, and clicking it, switching away, and switching back to
      `flag` again correctly re-defaults to manual rather than sticking
      on whatever mode was last active.
      **Verified live in Chrome**: a fresh page load lands on `flag`
      already tagged `[manual camera]` with the "return to scripted
      camera" button visible (confirmed on a hard reload, not just a
      scene-switch, proving the first-frame path works independently of
      the switch/restart path); clicking that button correctly restored
      the scripted orbit (tag and button both disappeared, camera
      resumed animating on its own); switching to another scene and back
      to `flag` re-entered manual mode as expected.

## Scene-switch reliability: crash-safe main loop, consistent library state, reconnect-safe switching, camera-target reset (bug fix, follow-up) — not yet phased
User-reported directly: "after running the flagOnRope scene for a while,
when I pick a new scene, the view resets, but the scene stays the same"
- "not very consistent," no specific reproduction steps. Investigated by
reading the actual switch path end to end (client `<select>` handler
through the server's `SceneLibrary`/main loop) rather than guessing from
the symptom alone - an initial repro attempt (heavy manual orbiting on
`flagOnRope`, then switching to `flag` via a real `change` event) did
*not* reproduce a dramatic failure, so this landed as four independent,
individually-justified fixes rather than one confirmed root cause. Only
one (the main-loop crash guard) plausibly matches "not very consistent"
on its own - the others are real, code-verified bugs regardless.
- [x] **Main loop had zero error handling.** `SceneLibraryDebugDemo.kt`'s
      `while (true)` body (drain control messages, step, build frame,
      broadcast) ran unguarded - any exception anywhere in there (a
      scene's own `step()`/`frame()` throwing, or anything else) would
      kill the single always-on loop thread outright, with nothing to
      restart it. The interactive session would go permanently inert:
      no more physics steps, no more broadcasts, every future control
      message (including "switch scenes") silently doing nothing for the
      rest of that server process's life - exactly the "sometimes I just
      can't switch scenes" shape, and "not very consistent" in the sense
      that it depends entirely on whether *some* exception has already
      fired once this session, not on which scene or button is involved.
      Fixed by wrapping the per-iteration body in a `try`/`catch
      (e: Exception)` that logs (`scene loop error on '<activeName>':
      ...` plus a stack trace) and lets the loop continue - deliberately
      catching `Exception`, not `Throwable`: a real `Error`
      (`OutOfMemoryError`, `StackOverflowError`) means the JVM itself may
      be unrecoverable, where "log and keep looping" is the wrong
      instinct. The pacing `Thread.sleep` stayed *outside* the `try` so a
      repeatedly-erroring scene still paces at ~60fps instead of spinning
      a CPU core at 100%.
- [x] **`SceneLibrary.load` could leave `activeName`/`scene` naming two
      different scenes.** `activeName = name` ran *before* `scene =
      factory()` - if the factory threw, `activeName` already pointed at
      the new scene while `scene` (and `t`/`step`) stayed on the old one,
      permanently disagreeing with each other for the rest of the
      process (nothing ever reconciles them). Fixed by building the new
      scene first and only publishing `activeName`/`scene` together once
      that succeeds - a failed `load` now leaves the library exactly as
      it was, the same guarantee the existing "unknown name" case already
      had. New regression test (`SceneLibraryTest`, a factory that always
      throws) - this **only** passes because of this fix; against the
      old ordering it fails on the `activeName` assertion, proving it's
      a real discriminating test, not just a re-statement of the change.
- [x] **A `load_scene`/`restart` sent while the socket wasn't `OPEN`
      vanished silently.** `send()` checked `readyState === OPEN` before
      writing, dropping the message with no log, no queue, no retry, no
      user-visible signal beyond the easy-to-miss "disconnected -
      retrying..." status text - and the client's own optimistic
      `resetViewerStateForSceneChange()` ran regardless in the same event
      handler, so a scene-switch click during a sub-second reconnect
      window looked like it worked (camera reset, panel cleared) while
      the server never heard about it and kept broadcasting the old
      scene forever - the literal "camera resets, but the new scene is
      not displayed" the user described. Fixed two ways: `send()` now
      `console.warn`s on every dropped message (previously fully silent),
      and specifically remembers a dropped `load_scene`/`restart` in a
      new `pendingSceneMessage`, resent once by `connect()`'s own
      `onopen` handler. Scoped to just those two message types - queuing
      drags/§10.4 field edits/time control too would mean replaying stale
      input against whatever scene/state exists by the time the
      connection actually comes back, a worse failure mode than dropping
      them.
      **Verified live in Chrome**: killed the demo server (forcing
      "disconnected - retrying..."), switched the scene picker to `flag`
      while disconnected - console showed the new warning
      (`send() dropped a message - socket not open: ...`) instead of
      silence. Restarted the server on `flagOnRope` (deliberately a
      *different* scene than what was picked, so success is unambiguous)
      - once reconnected, the client correctly showed `flag` (112
      particles, `flag`'s own force list), not the `flagOnRope` the
      server actually started on, proving the pending message was
      retried and applied. No console errors from the app itself.
- [x] **Camera reset didn't reset `OrbitControls`' own `target`.**
      `resetViewerStateForSceneChange` set `camera.position`/`up` and
      called `camera.lookAt(0,0,0)`, but `controls.target` (a separate
      Vector3 `OrbitControls` maintains itself) was untouched - the
      render loop's `if (cameraMode === "manual") controls.update()`,
      which runs every frame in manual mode, re-derives the camera's actual
      position/orientation from *its own* target and the camera's current
      position on every call, not from whatever `camera.lookAt` was last
      told. A stale target (wherever the user last orbited to, on this
      scene or an earlier one) meant the very next manual-mode
      `update()` recomputed the camera relative to that stale point
      instead of the fresh reset just made - usually just a mis-aimed
      view in casual testing, but the more the user had panned/zoomed
      away from the origin, the worse it gets, up to the new scene's
      content landing off-screen. Fixed by also calling
      `controls.target.set(0, 0, 0)` and `controls.update()` at the end
      of the reset.
      **Verified live in Chrome**: on `flagOnRope`, dragged+scrolled the
      camera aggressively (deep zoom, steep tilt, clearly off the
      flag's own default framing), then switched to `flag` - camera
      landed back at the clean default framing every time, not the
      previous scene's stale orbit point.

## Lights reach the outliner and become live-editable (§10.2/§10.3/§10.4, follow-up) — not yet phased
- [x] Named lights are now a seventh outliner kind, alongside groups/
      forces/constraints/surfaces/colliders/emitters, and are individually
      selectable/editable the same way a force or constraint is — closing
      the one gap the earlier "Lighting & materials" `[stretch]` item left
      behind: lights rendered correctly, but there was no way to *see*
      that a scene had them or change one without editing Kotlin source
      and restarting. Directly requested by the user after using the
      trampoline scene's custom lighting: "How can I tell if the lighting
      and materials are working? Can you list the lights along with all
      of the other entities... I'd also like to see and edit a light's
      properties after selecting one."
      **`Light` gained a name and became mutable/`EditableFields`** —
      `particlesim.render.Light`'s three variants (`Ambient`/
      `Directional`/`Point`) went from immutable `val`-only data classes
      to `var color`/`var intensity` (and `var position` on the two that
      have one), plus `name: String?`, following the exact same "named
      like a Force/Constraint, opt-in to the outliner by naming it"
      convention `SceneRegistry` already applies to those three kinds —
      an unnamed light still lights the scene (still reaches
      `SceneFrame.lights`/`BinaryFrame`'s unconditional render section),
      it's just not individually reachable. Data-class-ness (with `var`
      properties, which Kotlin allows) was kept specifically so
      `equals`/`hashCode`/`copy` still work for tests and logging, despite
      the mutability that makes `EditableFields.setField` possible —
      documented directly on `Light` as a "never use one as a `Map` key or
      `Set` member" caveat, since its hash code now changes on every edit.
      A new `Light.Positioned` sealed interface (implemented by
      `Directional`/`Point`, not `Ambient`) lets `editableFields()`/
      `setField()` live once on the `Light` interface itself (as default
      methods, dispatching via `is Positioned`) instead of duplicated per
      subtype, and collapses `BinaryFrame`'s existing light-encoding
      `when` (which extracted `position` per-kind) down to one branch.
      `color` deliberately reuses `FieldValue.Vector` (three doubles) —
      zero new wire shape, and the existing generic x/y/z numeric-input UI
      (`renderEditableFields` client-side) already renders it — accepting
      that the panel shows a color as three boxes labeled by position
      semantics (x/y/z), not a dedicated color picker; documented as a
      deliberate tradeoff on `Light.editableFields`'s own doc comment.
      **Wire protocol**: `SceneRegistry.build` gained a `lights: List<Light>
      = emptyList()` parameter, filtered to named entries into
      `Map<String, Light>` exactly like forces/constraints/surfaces.
      `BinaryFrame` gained a `registryLightCount` name-list section
      (same shape as the force/constraint/surface ones) right after the
      existing particle-expression-source section, and
      `collectEditableFields` now also walks `registry.lights` unconditionally
      (every `Light` is `EditableFields` — no per-entity `is EditableFields`
      check needed, unlike forces/constraints where most don't opt in),
      tagging entries `kind = "light"`. The existing `(kind, name, field)`
      field-entry wire section's `kind` byte grew a third value
      (`LIGHT_FIELD_KIND`, named to avoid confusion with the unrelated
      ambient/directional/point light-*type* byte already in this same
      file) — its decoder now matches every neighboring kind-byte decoder
      in this file's own convention of `error("unknown ... kind byte")` on
      anything unrecognized, rather than silently defaulting to "light"
      the way an earlier draft of this change did (caught by review before
      shipping, not by a test — no test exercises byte 3+ deliberately).
      This is a genuinely new wire section threaded through four parse
      sites that all have to agree byte-for-byte (Kotlin `encode`,
      Kotlin `decode`, this file's own layout doc comment, and
      `viewer.html`'s hand-written `decodeFrame`) — a mismatch anywhere
      shows up as garbage in an unrelated section (exactly the class of
      bug the flag-texture `RangeError` storm from an earlier session
      was), which is why the four were updated together in one pass and
      why the live Chrome check (below) specifically exercised the panel,
      not just the Kotlin-side round-trip test.
      **`applyEditableFieldMessage` gained a third, defaulted `lights:
      List<Light> = emptyList()` parameter** alongside `forces`/
      `constraints`, so the nine existing call sites across every
      `DemoScene` needed zero changes — only `TrampolineScene` (the one
      scene with any named lights so far) passes a real list.
      **Client side** (`viewer.html`): a new `<h4>lights</h4>`/
      `#outlinerLights` section in the page's own HTML, and a `"lights"`
      entity-kind module registered the same minimal way `"constraints"`
      already is (editable fields only, no renderer of its own) —
      `renderPanel`/`updateLive` both just call the existing
      `renderEditableFields`/`updateEditableFieldsLive("light", ...)`
      helpers, no new UI-building code needed since a light's fields are
      plain scalar/vector `EditableFields` entries like any other.
      `registrySignature` includes `registry.lights` as a **name list
      only** — explicitly not color/intensity/position, matching the
      `emitters` entry directly above it in the same function and for the
      same reason: those values change on essentially every frame once
      anything is editing a light, and putting them in the signature would
      rebuild (and clobber an in-progress edit in) the panel every single
      frame instead of only when the *set* of lights changes.
      **`TrampolineScene`'s three lights were named** (`ambient-fill`,
      `sun`, `bounce-highlight`) specifically so the scene's own point
      ("lights are actually configurable") is real from the UI, not just
      from reading the source, and threaded into both
      `SceneRegistry.build(..., lights = lights)` and
      `applyEditableFieldMessage(..., lights)` — two separate wiring
      points in the same file that a review pass had to fix once already
      earlier this session (the missing `lights = lights` argument to
      `SceneFrame(...)` itself, from the "Lighting & materials" item
      above) — a reminder that "compiles" doesn't mean "wired," so this
      time the wiring was verified by behavior (outliner listing, a live
      edit, and the edit surviving the next frame) rather than by reading
      the code.
      New tests: `LightTest` (an ambient light's field set excludes
      `position`; a directional/point light's includes it; `setField`
      mutates `color`/`intensity`/`position` in place; rejects a position
      edit on an ambient light and any unrecognized field or wrong-kind
      value). `SceneRegistryTest` (only named lights register, alongside
      the existing forces/constraints/surfaces case). `DemoSceneTest`
      (a scalar and vector field edit routes to a matching named light).
      `BinaryFrameTest` (a named light's name reaches the registry list
      and its live color/intensity/(position) round-trip as `kind="light"`
      field entries; an unnamed light reaches the unconditional render
      section but neither the outliner name list nor the field-entry
      list).
      **Verified live in Chrome** on `trampoline`: all three named lights
      appear under a new "LIGHTS" outliner section; selecting
      `ambient-fill` (no position row) vs. `bounce-highlight` (has one)
      confirmed the per-kind field set is correct; set
      `bounce-highlight`'s `intensity` to `0` and watched the mat's
      highlight visibly flatten, confirmed the panel's own field still
      read `0` several frames later (not clobbered back to `45` — proof
      the edit landed on the live object, not a copy), then restored it
      and watched the highlight return; separately set its `color` to a
      strong blue and watched the highlight visibly tint blue, then
      reverted it. Switched to `flag` (no named lights) and confirmed
      "LIGHTS (none)"; switched back to `trampoline` and confirmed all
      three lights and the custom rig recovered correctly. No console
      errors (only the known-benign Chrome-extension noise) throughout.
      Full `./gradlew test -q` suite green.
      **Follow-up, same session**: `FlagScene` gained its own named light,
      `sun` (`Light.Directional`, position `(5, 10, 7)`, white, intensity
      `0.8`) — a second real consumer of the outliner/editing work above,
      and the first to combine a named light with a *textured* mesh
      (`clothMesh` has no declared `Material`, so it resolves to
      `Material.UNTINTED` per §10.2's "don't double-tint a texture" rule,
      meaning the light's own color multiplies directly against the flag
      texture). Position/color/intensity were chosen to match the
      viewer's own hardcoded default sun (`viewer.html`'s `defaultLights`)
      as closely as possible, specifically so switching this scene from
      "no lights, default fallback" to "one named, editable light" doesn't
      change how the flag looks by default — confirmed by comparing
      before/after screenshots, not just by matching the numbers. Wired
      into `SceneRegistry.build(..., lights = lights)`,
      `applyEditableFieldMessage(..., lights)`, and `SceneFrame(...,
      lights = lights)`, the same three points `TrampolineScene` needed.
      `buildFlag`/`FlagScenario` remain untouched — the light lives on
      `FlagScene` itself, same "own it where it's actually used" reasoning
      already applied there to `clothMesh`/`windArrows`/`camera`, so
      `FlagGoldenTest`/`FlagYamlParityTest` (which call `buildFlag`
      directly, never `FlagScene`) are unaffected.
      **Verified live in Chrome**: `sun` appears in `flag`'s outliner;
      selecting it shows `color`/`intensity`/`position` all correct; the
      flag renders with the same vivid, correctly-shaded look it had
      under the default-lighting fallback (checked at the same camera
      angle/zoom as an earlier session's flag screenshot); switching to
      `trampoline` and back to `flag` recovered `sun` and the flag's
      lighting correctly. No console errors. Full test suite green.
      **Follow-up, same session**: `FlagOnRopeScene` (the flag hung from a
      rope rather than pinned to the pole — see `FlagOnRopeScenario`'s own
      doc comment) got the identical named `sun` light, same position/
      color/intensity, same reasoning (its `clothMesh` is likewise
      textured with no declared `Material`). Wired at the same three
      points, and `buildFlagOnRopeScenario` (this scene's own build
      function, consumed directly by `FlagOnRopeSceneTest`) stayed
      untouched — the light lives on `FlagOnRopeScene` itself. Verified
      live in Chrome: `sun` lists in the outliner with the correct
      color/intensity/position, the flag renders vividly under it, and it
      survives a `flag` → `flagOnRope` round-trip switch. No console
      errors. Full test suite green.
      **Follow-up, same session**: `FlagOnRopeScene` also gained a named
      `Light.Ambient` (`ambient-fill`, white, intensity `0.4`) alongside
      `sun` — the viewer's own default-lighting fallback this scene's
      lights replace was a hemisphere-plus-directional pair, not a bare
      directional; a lone `sun` (added just above) has nothing filling in
      its shadowed side/underside the way the hemisphere light's sky/
      ground split used to. A plain `Ambient` can't reproduce a
      hemisphere's sky/ground split, but a modest flat fill covers the
      same "shadows aren't pitch black" purpose without a second key
      light competing with `sun`. `flag` (the pole-pinned scene) wasn't
      given the same treatment - not an oversight, just not asked for.
      Verified live in Chrome: `ambient-fill` lists alongside `sun` in the
      outliner; its panel correctly shows only `color`/`intensity` (no
      `position` row, matching `TrampolineScene`'s own `ambient-fill`);
      the flag's shadowed folds read with visible detail rather than
      crushing to black; both lights survive a `flag` → `flagOnRope`
      round-trip switch. No console errors. Full test suite green.

## YAML front-end second pass (§4.2/§4.5, follow-up to Phase 7) — not yet phased
User asked to finish everything the Phase 7 "Second pass" note above scoped
out: every remaining field, general bulk generation, a real selector
language, ball-bounce/sparks golden parity, and the shape registry — plus a
`lights:` section (raised while presenting "what's next," not originally in
this list, folded in at the user's request). Planned in
`/Users/swampler/.claude/plans/idempotent-rolling-whistle.md` as ten
phases (0-9); each phase gets its own entry below as it lands. Explicitly
out of scope, flagged in the plan rather than silently dropped: full
`renderers:`/`material:` YAML support (no YAML renderer-declaration
mechanism exists at all today, a materially bigger undertaking than
anything else here and not needed by any target scenario),
`surface_collider`/`surface_self` collision rules (no named-surface YAML
reference exists to target), and standalone non-mesh `Spring`/`Damper`
declarations (nothing needs one).
- [x] **Phase 0 — shared loader infrastructure.** `YamlFields.kt` gained
      `optionalString`/`optionalInt` (straightforward), `optionalScalarExpr`/
      `optionalVectorExpr` (the optional counterparts to the existing
      `requireScalarExpr`/`requireVectorExpr`, for fields that fall back to
      a default rather than erroring when absent), and the pair
      `directionalTriple`/`requireDirectionalTriple` — the
      "`base`, `extension_<base>` defaults to `base`, `compression_<base>`
      defaults to `base`" shape repeated verbatim across `Spring`/`Damper`/
      `MeshSprings`' stiffness, damping, and break-threshold constructor
      parameters (confirmed by reading all three constructors directly this
      session, not assumed). Two entry points because the shape appears
      both ways on the Kotlin side: `stiffness`/`damping` are mandatory
      constructor parameters with no Kotlin-side default
      (`requireDirectionalTriple`), while `breakThreshold` has one
      (`Double.POSITIVE_INFINITY`, via `directionalTriple`'s own
      `default` param). None of these three fields are `ScalarExpr` on the
      Kotlin side (verified directly), so the triple reads plain `Double`s,
      not expressions.
      New `YamlFieldsTest.kt` (12 tests): one per helper's present/absent/
      wrong-type cases, `directionalTriple`'s three defaulting
      combinations (base only, one override, both overrides), and both
      triple functions tested against plain `Map` literals directly rather
      than through a full `YamlLoader.load()` call — these are extraction
      primitives, not scenario-level behavior.
      Full `./gradlew test -q` suite green. No scenario-visible effect yet
      (Phase 0 adds no new YAML schema surface) — verification is the unit
      tests alone.
- [x] **Phase 1 — bulk generation beyond a grid, plus tags/ids.** `particles:`
      now dispatches on whether the value is a `Map` (the original
      single-`grid:` shorthand, byte-for-byte unchanged — `loadGrid` is the
      exact same logic, just parameterized by a `context` string instead of
      the literal `"particles.grid"`) or a `List` of generator blocks
      discriminated by key, the same convention `forces:`/`constraints:`
      already use. Three new generator kinds: `random_volume`
      (`shape: {box: {center, half_extents}}` or `{sphere: {center,
      radius}}`, uniform-by-volume — sphere sampling is rejection sampling
      in the enclosing cube, not a naive random-direction-times-uniform-
      radius, which would bias samples toward the center; `seed:` is
      required, not defaulted, since an implicit system-RNG fallback would
      silently break §11's determinism the first time anyone needed to
      reproduce a run), `list` (explicit per-particle declarations, each
      with its own position/mass/radius/lifetime/tags/optional `id`), and
      `single` (one particle). Every kind accepts `tags:`, indexed into a
      new loader-local `tagIndex: Map<String, Set<Int>>`; `list`/`single`
      entries also accept an author-facing `id:` string (a duplicate is a
      load-time error), indexed into a loader-local
      `authorIds: Map<String, Int>`. Both maps are populated now but not
      yet consumed anywhere — Phase 2's `groups:` selector resolution
      (tags/ids/range) is their first real reader; **neither `ParticleStore`
      nor `Groups` gained a tags concept** — both maps live and die inside
      one `load()` call, exactly the plan's "entirely a load-time
      addressing convenience" decision.
      `list`/`single`/`random_volume` default `mass` to `1.0` (via Phase 0's
      `optionalScalarExpr`) rather than requiring it — matching
      `ParticleStore.create`'s own default — unlike `grid`, whose `mass`
      stays mandatory for backward compatibility with every existing
      `grid:` document. `velocity` on all three new kinds is a literal
      `[x,y,z]` evaluated once at load time (via `optionalVectorExpr(...).
      evaluate(0.0)`), matching `ParticleStore.create`'s own `velocity:
      Vector3` parameter, which isn't itself expression-capable on the
      Kotlin side either.
      **A real Kotlin-parser gotcha caught by the compiler, not review**:
      an early draft stored a per-shape sampling closure
      (`val positionOf: () -> Vector3 = when { ... }`) with each `when`
      branch ending in a bare `{ uniformInBox(...) }` lambda literal.
      Kotlin's trailing-lambda grammar parsed that `{ ... }` as a trailing
      lambda argument onto the *previous* line's `requireVectorLiteral(...)`
      call instead of a new expression — "too many arguments for
      requireVectorLiteral" was the actual compiler error, not an
      "unresolved reference" one, which is what made it non-obvious at
      first glance. Fixed by resolving the shape's params once into local
      `val`s outside the loop and picking box-vs-sphere per particle with a
      plain `if`, rather than storing a closure at all — simpler code, not
      just a workaround.
      New `YamlLoaderTest` cases (15): the list-form single-grid-entry
      regression check, unknown-generator-kind error, `random_volume`
      box/sphere bounds checks, seed-reproducibility (two loads → identical
      sorted positions), missing-seed error, unknown-shape error, `list`
      per-entry fields, duplicate-author-id error, mass-defaulting check,
      `single` fields, and multiple generator kinds combined in one scene.
      Full `./gradlew test -q` suite green, including the pre-existing
      `FlagYamlParityTest` (unaffected — `flag.yaml` still uses the legacy
      map shorthand, which this phase left byte-for-byte unchanged).
- [x] **Phase 2 — the tag/id/range selector language.** `groups:` entries
      are now either the original plain string (no membership of its own,
      just the zero-match-warning marker, unchanged) or
      `{name, select: {tags: [...] | ids: [...] | range: {...}}}`, which
      *does* populate real membership by resolving against Phase 1's
      `tagIndex`/`authorIds`/`grids`. Required reordering `load()` so
      `groups:` resolution now runs *after* `loadParticles` instead of
      before it — a selector needs particle data to resolve against; a
      plain string never did, so this is purely additive to the ordering,
      not a behavior change for the pre-Phase-2 form. `tags` is AND across
      every listed tag (a particle must carry all of them); an
      unrecognized tag contributes zero matches rather than erroring,
      consistent with §4.2's own "zero-match is a warning, not an error"
      framing — contrast `ids`, where an author id no `list`/`single`
      particle ever declared *is* a load-time error (the "unknown name"
      tier, not "zero match"). `range: {grid, rows?, cols?}` is an
      inclusive `[lo, hi]` block against a named Phase-1 grid, defaulting
      to the grid's full extent when `rows`/`cols` is omitted;
      out-of-bounds or an inverted `lo > hi` is a load-time error, not a
      warning — a real authoring mistake, unlike an empty selector.
      Multiple selector kinds in one `select:` block are **unioned**
      (matches any) — the simplest additive rule, revisable later if a
      scenario needs intersection instead. **Zero changes to `Groups.kt`
      or `ParticleStore.kt`** — the whole selector mechanism resolves
      entirely inside `YamlLoader.load()` and touches nothing else, exactly
      the plan's scope-reduction decision (tags/author-ids are a load-time
      addressing convenience, never a runtime concept).
      New `YamlLoaderTest` cases (11): `tags` AND semantics, `ids`
      resolution and its unknown-id error, `range` matching a grid
      sub-block and defaulting to the whole grid, out-of-bounds range
      error, zero-match selector warning (same path a stale plain-string
      entry already used), mixed plain-string + selector entries in one
      list, empty `select: {}` error, and a selector-defined group actually
      resolving correctly when referenced later by a force's `group:`.
      Full `./gradlew test -q` suite green, including `FlagYamlParityTest`
      — `flag.yaml` uses no selectors, so the reordered `groups:` pass
      resolves it identically to before.

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
- [x] Lighting & materials (§10.2, new requirement) — configurable light
      sources (ambient/directional/point) and per-object/per-group
      material properties; previously the renderer was flat/unlit with
      one fixed appearance. Requested directly by the user, alongside the
      next two items below, as "better control over the rendering of the
      scene." Built directly for §12.8's trampoline worked example -
      replacing its flat default blue-grey mat with a dark, slightly
      glossy fabric surface under a deliberately non-default lighting rig
      - the first concrete consumer, the same "wait for a real consumer"
      pattern every other stretch item here follows.
      **Model types**: new `particlesim.render.Light`, a sealed interface
      with `Ambient(color, intensity)`, `Directional(position, color,
      intensity)`, and `Point(position, color, intensity)` - the standard
      three.js light set, since §9.1/§10 already commit to that as the
      sole first-class viewer; no spot lights or shadows. New
      `particlesim.render.Material(color, roughness, opacity)`, with
      `roughness`/`opacity` validated to `[0,1]` at construction rather
      than left to silently clamp or misrender client-side.
      **Always-resolved-server-side, no absence flag**: `SurfaceRenderer`
      gained `material: Material?` plus a computed `effectiveMaterial`
      that resolves a `null` material to the historical default blue-grey
      when untextured, or to untinted white when textured (so an image
      texture isn't double-tinted by the old default blue-grey). The wire
      protocol (`BinaryFrame`) always emits a mesh's `effectiveMaterial` -
      concrete `matR/matG/matB/matRoughness/matOpacity` fields, never a
      "was material declared" flag - the same "resolve fully server-side"
      choice already used for sphere radii and line colors, so the client
      never has to know or care whether a scene author declared a material
      explicitly.
      **Lights are a new trailing wire section**, gated the same way as
      everything else optional in this protocol: `i32 lightCount` then
      `lightCount * {u8 kind, f64 r,g,b, f64 intensity, f64 px,py,pz}` (a
      zero `Vector3` for `Ambient`, which has no position) - `lightCount =
      0` for every scene that predates this feature, so old scenes are
      byte-identical modulo the new trailing zero. `SceneFrame` gained
      `lights: List<Light> = emptyList()`, threaded through
      `DebugRenderer.broadcast()` and `SceneLibraryDebugDemo`'s loop the
      same way `meshes`/`colliders`/etc. already are.
      **Client side** (`viewer.html`): the viewer's own original lighting
      (a dim hemisphere + one flat directional sun) was wrapped in a
      `defaultLights` group instead of removed, toggled `visible = frame.
      lights.length === 0` every frame - so a scene that never declares
      lights (every scene but `trampoline` today) looks exactly as it
      always has, with zero-length `frame.lights` costing nothing beyond
      the check. Custom lights live in a separate pooled `customLights`
      array/`customLightsGroup`, grown/shrunk to match `frame.lights.
      length` each frame and reusing existing `THREE.Light` objects when a
      slot's kind hasn't changed (only rebuilding the specific slot whose
      `kind` differs) - the same "grow/shrink a pool, don't rebuild it
      whole" approach the mesh pool already uses, needed here because a
      scene switch (§9.6) can go from zero lights to three and back
      repeatedly. Both material factories (`solidMeshMaterial`,
      `texturedMeshMaterial`) now take the mesh's resolved material,
      setting `roughness` directly and `transparent: true` only when
      `opacity < 1` (three.js treats `transparent` as a real cost, not a
      free flag, so it's opt-in per mesh rather than always on).
      **Real bug caught by review before this was closed out**: the
      `TrampolineScene` worked example declared its `lights` list but the
      actual `frame(t)` method never passed it into the returned
      `SceneFrame(...)` - meaning the scene would have silently rendered
      under the viewer's default lighting despite the custom rig existing
      in code, undetected by any test (the round-trip tests all pass an
      explicit `lights` argument directly to `BinaryFrame`/`Renderer`,
      never through a full scene's `frame()`). Fixed by adding the missing
      `lights = lights` argument; re-verified live afterward.
      **Point light intensity units, worth a comment in the code itself**:
      three.js r155+ dropped `useLegacyLights`, giving `PointLight.
      intensity` physically-correct candela units with inverse-square
      distance falloff, while `AmbientLight`/`DirectionalLight` stay on
      the old unitless scalar - a point light needs roughly a `4*pi x`
      multiplier over what the old scale would suggest before falloff eats
      it, which is why `TrampolineScene`'s point light is `intensity =
      45.0` next to its neighbors' `0.35`/`0.7`. Tuned empirically (started
      at `12.0`, visually too subtle in a live screenshot, raised to
      `45.0` and reconfirmed) at that light's actual ~3m distance from the
      mat - the code comments this so a future reader doesn't "fix" the
      apparent inconsistency down to match the other two and lose the
      highlight.
      New tests: `RendererTest` (`effectiveMaterial` resolves to the
      historical default when untextured with no material declared, to
      untinted white when textured with none declared, and to an explicit
      material regardless of texturing; `Material` rejects out-of-range
      roughness/opacity). `BinaryFrameTest` (a mesh's effective material
      round-trips exactly in all three resolution cases; no lights passed
      round-trips to an empty list unchanged from every scene built before
      this existed; ambient/directional/point lights round-trip kind,
      color, intensity, and position).
      **Verified live in Chrome**: on `trampoline`, confirmed the dark
      glossy mat renders with a clearly visible bright highlight from the
      point light positioned above the mat's center; switched to `flag`
      and confirmed the viewer falls back to its original default
      lighting (`defaultLights` becomes visible again since `frame.lights`
      is empty there); switched back to `trampoline` and confirmed the
      custom lighting/material are correctly restored via the pooled
      `customLights` mechanism, not left stale from the flag scene. Also
      temporarily set the mat's `opacity` to `0.4` with a bright red color
      (reverted after) to directly confirm the `transparent: opacity < 1`
      path actually blends against what's behind the mesh, rather than
      relying only on the round-trip unit test for that branch - the
      brightened color rendered as a muted, darker blend rather than a
      saturated red, as expected. No console errors in a fresh tab
      throughout. Full `./gradlew test -q` suite green.
      **Not built**: no YAML `lights:`/`material:` blocks yet (only the
      Kotlin DSL can declare either) - joins the other post-Phase-7 YAML
      gaps tracked elsewhere in this file; no per-particle/per-dot
      materials, spot lights, or shadows.
- [x] Friction — static/kinetic (§12.5) — promoted out of `[stretch]` the
      same way particle-vs-surface collision was: `ParticleCollisionDebugDemo`
      (Phase 5) turned up a concrete need. Without friction, a ball pile's
      floor/wall collisions had no way to ever stop tangential (along-
      the-surface) velocity — any sideways nudge from a many-body
      compression event persisted forever, and the demo had to be
      contained with wall colliders rather than actually settling.
      Implemented identically across all three collision systems
      (`ParticleColliderRule`/`CollisionSystem`, `SurfaceCollisionRule`/
      `SurfaceCollisionSystem`, `ParticleCollisionRule`/
      `ParticleCollisionSystem`) as two new optional fields,
      `staticFriction`/`kineticFriction`, both defaulting to `0.0`
      (frictionless, so every existing rule/demo is unaffected). Kinetic
      friction is textbook Coulomb: an impulse opposing the tangential
      relative velocity, capped at `kineticFriction * (that contact's own
      normal impulse magnitude)` and never enough to overshoot into
      reversing the slide. Static friction — for a contact already within
      the existing rest thresholds (§12.7) — is deliberately *not* a
      binary "stick": it kills a `staticFriction` *fraction* of the
      residual tangential velocity per step (`1.0` = instant stop, `0.3`
      = decays over several steps). A hard on/off stop was the first
      design and was rejected before being built — several particles
      settling within the same frame would visibly *snap* to a halt one
      by one rather than gently slowing, an artifact that would have read
      as a bug the moment it was actually watched (caught by design
      review, not after building it wrong once - see the advisor
      consultation before implementation). The same `invMassSum` scalar
      already computed for each system's normal-direction impulse is
      reused unchanged for the tangential direction, valid for any
      direction because nothing in this engine has rotational inertia.
      12 new tests across the three existing collision test files
      (`CollisionSystemTest`, `SurfaceCollisionSystemTest`,
      `ParticleCollisionSystemTest`): kinetic partial-deceleration with
      hand-derived exact values, kinetic capped-at-a-full-stop
      (never reverses), static fractional-arrest at two different
      coefficients, a zero-friction default-regression case, a
      no-tangential-motion edge case, and momentum-conservation-with-
      friction-active analytic checks for the two-body/surface systems
      (friction is an equal-and-opposite pair too, so it doesn't break
      conservation). Demo updated with real coefficients on the floor,
      walls, and ball-vs-ball rules and verified two ways: a WebSocket
      script confirmed every particle's speed settles to ~8e-10 m/s
      (effectively exact rest, down from the pre-friction 0.03-0.15 m/s
      residual drift); **and, for the first time this project has been
      able to do so, directly in a real Chrome browser** (the user
      enabled Claude in Chrome for this session) — screenshots taken 5
      seconds apart during settling were pixel-identical, and watching
      the spawn-to-settle sequence end to end showed a smooth pile
      forming with no visible snap-to-a-halt artifact.
- [ ] Continuous collision detection (§12.4)
- [x] Particle-vs-surface collision (§12.4) — promoted out of `[stretch]`
      by the trampoline below. `particlesim.surface.Triangle.closestPoint`
      (Ericson's region-test algorithm) finds the nearest point on a
      triangle, including edges/vertices, and returns barycentric weights;
      `particlesim.collision.SurfaceCollisionSystem` uses it each step
      against a `Surface`'s *current* (deformed) vertex positions —
      brute-force over every triangle (no broad-phase; one ball against a
      few hundred triangles doesn't need it), picking whichever triangle
      penetrates deepest. Reuses `ParticleColliderRule`'s exact
      restitution/asymmetric-damping/rest-clamp formulas so a surface
      contact feels like the same kind of bounce a static collider gives.
      The genuinely new part: unlike a static `Collider` (infinite mass),
      a surface's vertices are ordinary particles and must receive an
      equal-and-opposite reaction impulse (`J = deltaRelVel /
      (1/m_particle + sum(w_i^2/m_i))`, split across the three vertices by
      barycentric weight `w_i`) — verified by a dedicated momentum-
      conservation test against a free-floating, unpinned/unforced
      triangle (asserting `sum(m*v)` unchanged by the impulse to 1e-9);
      note a *pinned* vertex, e.g. the trampoline's rim, deliberately
      breaks that conservation, since `FixedPosition.applyPosition`
      discards whatever velocity the impulse just gave it every step —
      correct for an anchored frame, just not a case that should be
      asserted momentum-conserving. 16 new tests:
      `TriangleClosestPointTest` (9, vertex/edge/face regions + a
      moving-vertex case) and `SurfaceCollisionSystemTest` (7, including
      the momentum test and a "deepest-of-several-triangles wins" case).
      Surface *self*-collision (a mesh folding onto itself) is still not
      implemented — out of scope for what the trampoline actually needs.
- [x] Trampoline worked example (§12.8) — `particlesim.examples.buildTrampoline`:
      a 10x10 taut mat (`TRAMPOLINE_DT = 5e-4`, structural/shear/bend
      stiffness 2000/1000/200 — ten times the flag's — picked from
      §13.1's `dt < 2*sqrt(m/k)` budget with ~12.6x margin, slightly more
      conservative than the flag's own ~10x since a coupled mesh's true
      bound runs tighter than the single-spring estimate), rim pinned via
      `FixedPosition.atCurrentPositions` on every border row/column (not
      just one edge like the flag's pole), and a ball dropped onto it via
      `SurfaceCollisionSystem` — the surface's own per-step deformation is
      what bounces the ball, not a fixed-shape collider. `TrampolineStabilityTest`
      mirrors `FlagStabilityTest` (4 sim-seconds, no blow-up). `TrampolineBounceTest`
      is the actual end-to-end claim: drops the ball, finds its deepest
      penetration into the mat, and asserts it rebounds >0.15m above that
      depth afterward — the one thing this worked example specifically
      claims over reusing `buildBallBounce`'s static floor, and something
      a broken collision wiring could still pass every isolated component
      test while failing. Demo: `TrampolineDebugDemoKt` /
      `./gradlew runTrampolineDemo`; verified server-side via a disposable
      WebSocket script reading real engine state from a running instance —
      observed the ball's actual height dip to -0.061m (mat deflecting
      under impact) and rebound to +0.075m shortly after, a live bounce
      matching the unit test's claim, not just a passing assertion in
      isolation. Not verified in an actual browser (Chrome automation
      unavailable in this environment, as with every other viewer feature
      this phase) — the mesh should render as a shaded surface with the
      rim shown as small spheres, same `visibleIds` pattern as the flag's
      pole, but this has only been confirmed via the binary protocol, not
      looked at.
- [ ] Surface-vertex destruction / mesh repair (§14.3)
- [ ] GPU compute (§9.3)
- [x] Interactive delete (§9.4, §14.2) — §14.2's fourth destroy
      mechanism ("explicit delete via the viewer, alongside interactive
      dragging"), promoted out of `[stretch]`. `DestructionSystem.resolve`
      gained an `explicitIds: Set<Int>` parameter (default empty, so
      `SparksDebugDemo`'s existing lifetime/condition/collision-only call
      site needed zero changes) — ids already gone are silently skipped,
      same "already gone and just destroyed end up in the same place"
      stance the rest of that class already takes. `SceneControlMessage`
      gained a third case, `DeleteParticle`, alongside `RemoveCollider`/
      `Restart` (grouped there rather than with `DragMessage` since a
      delete has no per-step replay stamp and isn't part of that class's
      Start/Move/End state machine). Client-side: double-click a particle
      to delete it — a third, deliberately-hard-to-trigger-by-accident
      click gesture alongside single-click-drag and right-click-to-open.
      Wired into both `ParticleCollisionDebugDemo` (balls have no
      pairwise forces, so this mostly exercises the basic mechanic) and
      `DragDebugDemo` (the spring chain, where it actually matters:
      deleting a middle link needs its two Spring/Damper connections
      pruned from both `forces`, what physics sees, and `springs`, what
      the viewer draws — reusing `DestructionSystem`'s existing
      `danglingForces` reporting, §14.3's cleanup mechanism, rather than
      a hand-rolled one-off).
      **A real concurrency bug found and fixed via live browser
      testing, not just unit tests**: a double-click's two constituent
      clicks each start-and-immediately-end a no-op drag before the
      delete itself fires. `SceneControlMessage`s drain once per
      *frame*; `DragMessage`s drain once per physics *step* inside that
      frame's repeat loop — so a stray queued `drag_start` for the
      just-deleted id could still be sitting in the drag queue when the
      frame's delete already ran, re-arming `activeDrag` against a dead
      id and crashing the demo process (`IllegalArgumentException: no
      such particle`) the moment the matching `drag_end` tried
      `store.setVelocity` on it. Fixed by making `DragDebugDemo`'s own
      drag handling defensive — `DragMessage.Start` now checks
      `store.contains` before arming a drag, `DragMessage.End` checks it
      before touching the store - the correct general fix for "an
      interactive target can be deleted out from under an in-flight
      gesture" once any destroy mechanism coexists with drag, not a
      one-off patch for this specific race.
      6 new tests (`SceneControlMessageTest` x2, `DestructionTest` x4).
      Verified directly in a real Chrome browser: double-clicked a
      middle chain link and watched it visibly split into two
      independently-falling pieces (the severed lower segment, no
      longer connected to anything above it, fell straight down past the
      origin marker under gravity alone); the crash above was caught and
      fixed during this same live testing pass, then re-verified stable
      afterward, including that ordinary dragging still worked correctly
      post-delete.
      **Follow-up bug, from real user testing**: the restart button did
      nothing in `DragDebugDemo`. Its `sceneControlQueue` handling used a
      plain `if (message is SceneControlMessage.DeleteParticle)` rather
      than an exhaustive `when`, so `Restart` (added to `SceneControlMessage`
      earlier, for `ParticleCollisionDebugDemo`) silently fell through
      unhandled — a real instance of the same "one missed wiring step,
      no compiler error to catch it" class of bug the `ViewerInput`
      consolidation above was built to prevent, just for a `when` branch
      rather than a whole missing class. Fixed by rebuilding the chain
      from scratch on `Restart` (fresh `ParticleStore`/`Groups`, same
      pattern as `ParticleCollisionDebugDemo`'s own restart) and
      switching to an exhaustive `when` so a future unhandled case can't
      go quiet again. Verified live: clicked restart mid-run and
      confirmed `t`/`step` actually reset to ~0 with the chain rebuilt.
- [ ] Playback-fork-to-live: resume from nearest checkpoint, deterministic
      fast-forward to the exact target frame, then hand off to live input
      (§9.4, §9.5)
- [ ] Export Kotlin-authored scene to YAML (§4.3)
