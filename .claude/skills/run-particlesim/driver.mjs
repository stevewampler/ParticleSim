// Headless-Chromium driver for the ParticleSim viewer (run-particlesim skill).
// ParticleSim itself is a Kotlin/Gradle JVM process with no browser SDK of its own - it serves
// a plain HTML+WebSocket viewer (particlesim.debug.DebugRenderer, default
// http://localhost:8888, ws://localhost:8887). This driver is what lets an agent actually poke
// that page instead of just reading viewer.html and guessing. chromium-cli isn't available in
// this environment, so this is a from-scratch Playwright REPL in the same spirit (see the `run`
// skill's examples/electron.md for the pattern this borrows).
//
// Unlike an Electron app, plain `chromium.launch()` needs no xvfb/display server at all - this
// runs genuinely headless on Linux or macOS with no X server.
//
// Usage: pipe a line-delimited command script to stdin (see SKILL.md for a worked example):
//   node driver.mjs <<'EOF'
//   launch
//   ss 01-initial
//   quit
//   EOF
// Also works as an interactive REPL if you just run it with no piped input.

import { chromium } from 'playwright';
import * as readline from 'node:readline';
import * as fs from 'node:fs';
import * as path from 'node:path';

const URL = process.env.PARTICLESIM_URL || 'http://localhost:8888';
const SHOT_DIR = process.env.SCREENSHOT_DIR || '/tmp/particlesim-shots';
fs.mkdirSync(SHOT_DIR, { recursive: true });

let browser = null;
let page = null;
const consoleErrors = [];

const COMMANDS = {
  // Launches headless Chromium, navigates to the viewer, and waits for the WebSocket
  // "connected" status badge (top-left) - the real readiness signal, since the page itself
  // loads near-instantly but the first physics frame (and therefore anything in the outliner)
  // only exists once the WebSocket handshake completes.
  async launch() {
    if (browser) return console.log('already launched');
    browser = await chromium.launch();
    page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
    page.on('pageerror', (err) => consoleErrors.push('pageerror: ' + err.message));
    await page.goto(URL, { waitUntil: 'load' });
    try {
      await page.waitForFunction(
        () => document.body.innerText.includes('connected'),
        { timeout: 15_000 },
      );
    } catch {
      console.log('WARNING: never saw "connected" badge - is the server actually running?');
    }
    // One extra beat: the outliner/forces/surfaces panels populate from the first frame, which
    // can land a tick after the socket itself reports connected.
    await page.waitForTimeout(500);
    console.log('launched:', URL);
  },

  async ss(name) {
    if (!page) return console.log('ERROR: launch first');
    const f = path.join(SHOT_DIR, (name || `ss-${Date.now()}`) + '.png');
    await page.screenshot({ path: f });
    console.log('screenshot:', f);
  },

  // Switches the active demo scene via the SCENE <select> (bottom-left panel). Scene names
  // match SceneLibraryDebugDemo.kt's factories map: flag, ballBounce, trampoline, sparks,
  // fire, fluid, drag, particleCollision, spatialGrid, multiShape, poleRope, flagOnRope.
  async scene(name) {
    if (!page) return console.log('ERROR: launch first');
    await page.selectOption('select', name);
    await page.waitForTimeout(1500); // let the new scene's first frames arrive
    console.log('scene ->', name);
  },

  // Clicks an outliner entry (a group/force/constraint/surface/collider/emitter/light name,
  // listed as plain text rows on the left) OR any other exact-text element - this is also how
  // you open a per-object panel, same as right-clicking the object in the 3D view would.
  async click(text) {
    if (!page) return console.log('ERROR: launch first');
    try {
      await page.getByText(text, { exact: true }).click();
      console.log('clicked:', text);
    } catch (e) {
      console.log('ERROR clicking', JSON.stringify(text), '->', e.message);
    }
  },

  // Toggles a labeled checkbox - e.g. "show arrows" (forces panel), "show mesh" /
  // "show polylines" / "wireframe" (surfaces panel), "show particles" (groups panel),
  // "enabled" (groups panel), "show mesh edges" (top-right global toggle). Matches by the
  // checkbox's own <label> text, same text a person would read next to it.
  async toggle(label) {
    if (!page) return console.log('ERROR: launch first');
    const checkbox = page.getByRole('checkbox', { name: label, exact: true });
    const count = await checkbox.count();
    if (count === 0) return console.log('NOT_FOUND:', label);
    await checkbox.click();
    const checked = await checkbox.isChecked();
    console.log('toggled', JSON.stringify(label), '-> checked:', checked);
  },

  // Orbits the camera: left-drag by (dx, dy) pixels on the canvas. Useful because most scenes
  // (every one except "flag", which has its own scripted orbit camera) start on a fixed
  // (6,4,8)-looking-at-origin camera - geometry offset from the origin (e.g. MultiShapeScene's
  // flag, raised +3.5 in y) can start outside that initial view.
  async orbit(dxdy) {
    if (!page) return console.log('ERROR: launch first');
    const [dx, dy] = (dxdy || '').trim().split(/\s+/).map(Number);
    const box = await page.locator('canvas').first().boundingBox();
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx + (dx || 0), cy + (dy || 0), { steps: 20 });
    await page.mouse.up();
    await page.waitForTimeout(200);
    console.log('orbited', dx, dy);
  },

  // Zooms via mouse wheel, centered on the canvas (positive = out, negative = in) - matches
  // OrbitControls' own wheel handling, same as a person scrolling over the 3D view.
  async zoom(deltaY) {
    if (!page) return console.log('ERROR: launch first');
    const box = await page.locator('canvas').first().boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.wheel(0, Number(deltaY));
    await page.waitForTimeout(200);
    console.log('zoomed', deltaY);
  },

  // Prints the whole page's visible text (default) or one selector's innerText - the fastest
  // way to confirm what the outliner/panel currently shows without a screenshot.
  async text(sel) {
    if (!page) return console.log('ERROR: launch first');
    const out = await page.evaluate(
      (s) => (s ? document.querySelector(s) : document.body)?.innerText ?? '(null)',
      sel || null,
    );
    console.log(out);
  },

  async eval(expr) {
    if (!page) return console.log('ERROR: launch first');
    try { console.log(JSON.stringify(await page.evaluate(expr))); }
    catch (e) { console.log('ERROR:', e.message); }
  },

  console() {
    console.log(consoleErrors.length ? JSON.stringify(consoleErrors) : '(no console errors)');
  },

  async quit() {
    if (browser) await browser.close().catch(() => {});
    browser = null; page = null;
  },

  help() { console.log('commands:', Object.keys(COMMANDS).join(', ')); },
};

const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });

// readline emits 'line' synchronously for every buffered line as soon as it's read - when a
// whole script arrives at once (a piped heredoc, the normal agent path), that means every
// line's 'line' event fires before the first command's async work finishes. Without this
// chain, two commands would run concurrently against the same `page` (e.g. a `scene` switch
// racing a `click`), which is exactly the kind of flaky, order-dependent failure a driver
// should not have. Chaining onto one promise forces strict in-order execution regardless of
// how fast lines arrive.
let chain = Promise.resolve();

rl.on('line', (line) => {
  chain = chain.then(async () => {
    const trimmed = line.trim();
    if (!trimmed) return;
    const spaceIdx = trimmed.indexOf(' ');
    const cmd = spaceIdx === -1 ? trimmed : trimmed.slice(0, spaceIdx);
    const arg = spaceIdx === -1 ? '' : trimmed.slice(spaceIdx + 1).trim();
    const fn = COMMANDS[cmd];
    if (!fn) { console.log('unknown command:', cmd, '- try: help'); return; }
    // Every command takes at most one free-text argument (the rest of the line, not re-split
    // on whitespace) - "toggle show arrows" must reach toggle() as one "show arrows" string,
    // not get truncated to its first word. orbit() is the one exception with two numbers; it
    // splits that single string itself.
    try { await fn(arg); } catch (e) { console.log('ERROR:', e.message); }
    if (cmd === 'quit') process.exit(0);
  });
});

rl.on('close', () => { chain = chain.then(async () => { await COMMANDS.quit(); process.exit(0); }); });
