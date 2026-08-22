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
      (§10.2) — `src/main/resources/particlesim/debug-viewer.html`
      (three.js via CDN import map), served over `http://localhost` by
      `ViewerHttpServer` (JDK's built-in `HttpServer`, no dependency) —
      not `file://`, which blocks ES module `<script>` tags in Chrome.
      `particlesim.debug.DebugRenderer` ties both servers together;
      `DebugRendererDemo` (`./gradlew run`) is a runnable example — a
      pinned chain of spring-connected particles swinging under gravity.
      **Colliders and surfaces aren't drawn** — neither exists yet
      (Phase 4/5); those branches of `--render-all` land with the things
      they draw, not stubbed out now.
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
- [ ] Shared spatial partitioning (sized to collision granularity) — its
      first real consumer, not the general execution engine (§9.3).
      Deferred: a single ball against one plane collider needs no broad
      phase at all; building one before a scenario has enough
      particles/colliders to need it would be optimizing blind. Second
      pass, alongside particle-vs-particle collision below.
- [ ] Particle-vs-particle collision (§12.4/§12.5, the two-body case) —
      deferred to the second pass along with the spatial index above (an
      all-pairs particle-vs-particle check has the same "needs enough
      particles to matter" problem broad phase does). This is also where
      "constrained particles behave as infinite mass in collision
      response" (§12.5) actually needs implementing — the two-body impulse
      formula has to substitute infinite mass for a `FixedPosition`/
      `FixedVelocity` particle's real mass, which particle-vs-collider
      response never has to do.
- [ ] Collision groups & filtering beyond a single rule's group/collider
      pairing (§12.3) — deferred alongside particle-vs-particle collision,
      since group-vs-group filtering is primarily about *that* case
      (which pairs of groups collide with each other), not particle-vs-
      collider (already filtered by which rule references which collider).

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
- [ ] Real-time interactive loop: full state stream contract (camera pose,
      events), bidirectional input, wall-clock pacing policy (drop frames,
      never coarsen physics) — upgrades Phase 3's bare-bones stream (§9.1)
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
      doesn't), checkpointing, resume.
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
      target) are added to `debug-viewer.html`, resolved entirely
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
      drag) needed zero changes. `debug-viewer.html` applies the camera
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
      **Client (`debug-viewer.html`)**: `ws.binaryType = "arraybuffer"`
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
      **Client**: `debug-viewer.html`'s line material switched to
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
      (`pickParticle` in `debug-viewer.html`, using the raycast hit's own
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
