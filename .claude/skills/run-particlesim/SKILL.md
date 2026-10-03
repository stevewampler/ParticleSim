---
name: run-particlesim
description: Build, run, and drive the ParticleSim viewer in a browser. Use when asked to start ParticleSim, launch the viewer, run a demo scene (flag, ballBounce, trampoline, sparks, fire, fluid, drag, particleCollision, spatialGrid, multiShape, poleRope, flagOnRope), take a screenshot of it, or interact with its outliner/panels/toggles.
---

ParticleSim is a Kotlin/Gradle JVM physics engine that serves a plain
HTML+WebSocket viewer (no SPA framework, no dev server) - there is no
native GUI to launch. Drive it via the headless-Chromium Playwright REPL
at `.claude/skills/run-particlesim/driver.mjs`: pipe it a line-delimited
command script over stdin (no tmux/xvfb needed - plain
`chromium.launch()` is genuinely headless on both Linux and macOS).

All paths below are relative to the repo root.

## Prerequisites

Node.js (verified with v25.6.0; any reasonably recent Node works) and
npm. No system/display packages needed - `chromium.launch()` requires
no X server.

## Setup

One-time, inside the skill directory:

```bash
cd .claude/skills/run-particlesim
npm install                        # installs the `playwright` package only
npx playwright install chromium    # downloads the browser binary (~280MB, cached
                                    # at ~/.cache/ms-playwright or ~/Library/Caches/ms-playwright -
                                    # shared across projects, so this is a no-op after the first run
                                    # anywhere on the machine)
cd ../../..                        # back to repo root
```

## Build

No separate build step - `./gradlew runSceneLibraryDemo` (below) compiles
and launches in one command via the Gradle wrapper.

## Run (agent path)

Start the server in the background and wait for it to actually serve
(Gradle's first-run compile can take a while):

```bash
lsof -ti:8888 -sTCP:LISTEN | xargs -r kill 2>/dev/null   # free the port from any previous run
lsof -ti:8887 -sTCP:LISTEN | xargs -r kill 2>/dev/null
nohup ./gradlew runSceneLibraryDemo > /tmp/particlesim-server.log 2>&1 &
i=0; while [ $i -lt 90 ]; do curl -sf http://localhost:8888 >/dev/null 2>&1 && break; sleep 1; i=$((i+1)); done
```

Then drive it by piping commands to the driver:

```bash
SCREENSHOT_DIR=/tmp/particlesim-shots node .claude/skills/run-particlesim/driver.mjs <<'EOF'
launch
ss 01-initial
click gravity
ss 02-gravity-panel
toggle show arrows
ss 03-gravity-arrows-off
console
quit
EOF
```

Screenshots land in `/tmp/particlesim-shots/` (override with
`SCREENSHOT_DIR`). Stop the server the same way you started: kill the
port 8888/8887 listeners (same two `lsof` lines as above).

### Commands

| command | what it does |
|---|---|
| `launch` | launch headless Chromium, open the viewer, wait for the "connected" badge |
| `ss [name]` | screenshot -> `$SCREENSHOT_DIR/<name-or-timestamp>.png` |
| `scene <name>` | switch the active demo via the SCENE dropdown - one of `flag`, `ballBounce`, `trampoline`, `sparks`, `fire`, `fluid`, `drag`, `particleCollision`, `spatialGrid`, `multiShape`, `poleRope`, `flagOnRope` |
| `click <exact text>` | click an outliner entry (group/force/constraint/surface/collider/emitter/light name) or any other exact-text element - opens its per-object panel |
| `toggle <label text>` | click the checkbox next to that label, e.g. `toggle show arrows`, `toggle show mesh`, `toggle show polylines`, `toggle wireframe`, `toggle show particles`, `toggle show mesh edges` |
| `orbit <dx> <dy>` | left-drag the 3D view by (dx, dy) pixels - most scenes start framed near the origin, so geometry offset from it (e.g. multiShape's flag) may need this to come into view |
| `zoom <deltaY>` | mouse-wheel zoom, centered on the canvas (positive = out, negative = in) |
| `text [css-sel]` | print innerText of the whole page, or one selector |
| `eval <js>` | evaluate an expression in the page, print it as JSON |
| `console` | print any browser console errors seen so far |
| `quit` | close the browser |

Plain `launch`/`click <text>`/`toggle <label>` cover almost everything -
every outliner entry opens the same kind of panel (editable fields plus
whatever visibility toggles apply to that object's type: field forces
get "show arrows", surfaces get "show mesh"/"show polylines"/"wireframe",
groups get "show particles" and "enabled").

## Run (human path)

```bash
./gradlew runSceneLibraryDemo               # default scene: flag
./gradlew runSceneLibraryDemo --args="trampoline"   # or any other scene name
```

Then open `http://localhost:8888` in a real browser. Ctrl-C to stop (and
still free the ports with the `lsof`/`kill` lines above - Gradle doesn't
always clean up the JVM it spawned).

## Test

```bash
./gradlew test
```

## Gotchas

- **`toggle`/`click`/`eval` need their whole argument as one string, not
  re-split on spaces** - `toggle show arrows` must reach the `toggle`
  command as the single string `"show arrows"`, since Playwright matches
  the checkbox by its full label text. The driver only splits off the
  first word (the command itself); everything after that is passed
  through verbatim. `orbit` is the one exception with two numeric
  arguments, and splits its own string internally.
- **"connected" badge, not page load, is the real readiness signal.**
  The HTML loads almost instantly, but the outliner (GROUPS/FORCES/
  SURFACES/...) and every panel only populate once the WebSocket
  handshake completes and the first physics frame arrives - `launch`
  waits for that text, not just `page.goto`'s own load event.
- **Most scenes start framed near the origin, not around their actual
  geometry.** The default camera is a fixed `(6,4,8)` looking at the
  origin; only the `flag` scene has its own scripted orbit camera. In
  `multiShape`, for example, the flag sits offset `+3.5` in y (on top of
  its flagpole) and starts outside that initial view - `zoom`/`orbit`
  (see the worked example above) are how you bring it into frame.
- **A selected surface's vertices always draw as dots, even with its
  mesh hidden** - the per-object panel says so directly ("this surface's
  vertices are shown as dots while selected, even if normally
  mesh-only"). Don't mistake those dots for the mesh renderer itself
  when checking whether `toggle show mesh` actually did anything - compare
  the shaded/faceted surface *between* the dots, not the dots.
- **Playwright's browser cache is shared across projects** (`~/.cache/
  ms-playwright` on Linux, `~/Library/Caches/ms-playwright` on macOS) -
  `npx playwright install chromium` is a fast no-op if any other project
  on the machine already pulled that exact Playwright version's browser
  build.
- **`readline`'s `'line'` event fires synchronously for every buffered
  line as soon as it's read** - a whole piped heredoc arrives at once, so
  without explicit chaining every command's async Playwright work would
  race the next line's instead of running in order. The driver chains
  each command onto one promise for this reason; don't remove that if
  you extend it.

## Troubleshooting

- **`WARNING: never saw "connected" badge`**: the Gradle server isn't
  actually up yet (first-run compile can take 60s+) or died - check
  `/tmp/particlesim-server.log`, and confirm `curl -sf
  http://localhost:8888` succeeds before launching the driver.
- **`Address already in use` / server won't start**: a previous run's
  JVM is still holding the port - `lsof -ti:8888 -sTCP:LISTEN | xargs -r
  kill` (and the same for 8887) before retrying.
- **`toggle`/`click` report `NOT_FOUND`**: the exact text/label doesn't
  match what's on screen right now - run `text` (no selector) first to
  see the full current page text, including the outliner and whatever
  panel is open.
