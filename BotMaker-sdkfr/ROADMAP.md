# ROADMAP

A running history of features and refactors for future Claude Code sessions. **Append here whenever
you add a feature or refactor** (this is required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list (what shipped) and, when relevant, updates
to **Deferred / next** (intentionally left for later, with enough context to pick up cold).

---

## 2026-07-03 — Vision API simplification

**Done**
- **Collapsed `api.vision` from 9 classes to 3 action classes** (+ the unchanged value/config types
  `MatchResult`/`ImageTemplate`/`ClickConfig`):
  - **`ImageFinder`** — now owns single-frame lookup *and* existence: `find`/`findAll`/`findAny`, the boolean
    `exists`/`notExists`/`existsAny` (moved from `ImageMatcher`), and lambda control-flow `whileExists` /
    `ifExists` (take `Consumer<MatchResult>` — one capture per check, hands the action the live match) and
    `untilExists` (takes `Runnable`, since no match exists while the template is absent).
  - **`ImageClicker`** — trimmed to `click`/`clickAny`/`clickAll`.
  - **`ImageWaiter`** — unchanged: `waitFor`/`waitUntilGone`/`waitAndClick`.
- **Deleted** `Vision` (+ `evaluate`/`snapshot`), `ImageState` (+ `ScreenState`), `ImageMatcher`, and the
  `…then…`/long-tail variants (`clickBest`, `clickFirst`, `clickUntilSuccess`, `clickWhileVisible`, `findBest`,
  `retryUntilFound`, `clickAndThen`, `clickThenWaitFor`, `waitForGoneThenClick`, `clickOrWaitAndClick`). The
  multi-template single-capture branch (`Vision.evaluate`/`ScreenState`) is replaced by the `whileExists`-style
  lambdas; deleted `VisionEvaluateTest`.
- **Callers updated:** `capture/CaptureSource` + `capture/Window` javadocs no longer reference
  `Vision`/`ImageState`; `Main` unaffected. Studio drops `ImageMatcher`/`ImageState` from its SDK-facade list.

## 2026-07-02 — Game launch API

**Done**
- **`api.launch.Game` facade.** New public static entry point exposed as Studio blocks:
  `Game.launch(String executablePath, String... args)` starts any executable via `ProcessBuilder`
  (detached), and `Game.launchSteam(String appId)` / `launchSteam(int)` hands a Steam appId to the
  local Steam client via the cross-platform `steam://rungameid/<appId>` URL, falling back to
  `steam -applaunch <appId>`. Launching a Steam game needs no login of ours — the installed, signed-in
  Steam client owns the session; we never touch credentials.
- **`internal/launch/UriLauncher`.** Small OS URL-handler opener (Desktop.browse → `xdg-open`/`open`/
  `rundll32`), mirroring the Studio's `util.BrowserLauncher` (duplicated because the SDK can't depend
  on the Studio). Used by `launchSteam` to invoke `steam://` URLs.

**Deferred / next**
- No "wait for game window" helper yet — the vision blocks (`ImageWaiter`) already poll for on-screen
  templates, which is how a test bot detects the game is up.

## 2026-06-30 — Linux click support

**Done**
- **Linux click works.** `api.interaction.Mouse.click` now routes through
  `internal/capture/core/NativeControllerFactory.get()` instead of calling the Windows-only
  `internal/capture/Clicker` (JNA `User32`) directly. This fixes
  `UnsatisfiedLinkError: Unable to load library 'user32'` on Linux, which surfaced whenever a click
  actually fired (i.e. when `ImageFinder` found the template on screen). Windows path is unchanged
  (factory → `WindowsController` → `Clicker` → `User32 PostMessage`).
- **Multi-monitor coordinates fixed.** Added `api.capture.Screen.captureOrigin()` (the virtual-screen
  origin from `ScreenCapture.getVirtualScreenBounds()`). `ImageFinder.find/findAll` and
  `ImageState.findWhichAreVisibleDetailed` now add this origin to match coordinates, so reported
  points are **absolute** screen coordinates. Previously they were image-local, so clicks landed off
  by the virtual origin whenever a monitor was placed left/above the primary (negative origin).
  Note: `ImageFinder.find/findAll` now return absolute coords (corrected contract).
- **Cursor save/restore on Linux click (X11 only).** `LinuxController.postLeftClickScreen` reads the
  pre-click pointer via X11 `XQueryPointer` (added the binding in `linux/X11.java`) against the
  default root window — same coordinate space as `XTestFakeMotionEvent` — and warps back after the
  click. Gated on `WAYLAND_DISPLAY == null`; skipped under Wayland (see below).

**Deferred / next**
- **Click without disturbing the cursor on native Wayland.** Under native Wayland the JVM is an
  XWayland client: it can *write* the pointer (warp/click via XTest) but **cannot read** the global
  cursor position, so save/restore is impossible there (it would teleport the cursor to a stale
  constant). Current interim: on Wayland we skip restore and leave the cursor on the click target.
  Proper fix: implement input injection via the **xdg-desktop-portal RemoteDesktop** interface
  (libei / PipeWire) — this is also what raises the one-time "allow control of pointer/keyboard"
  prompt. Alternative (weaker): window-relative motionless click via `XSendEvent`, but many apps
  ignore synthetic events and it can't reach native Wayland windows. Likely belongs alongside the
  capture backends as a new injection strategy.
- **GNOME/sway capture.** Add a portal/PipeWire `CaptureBackend` and wire into
  `CaptureBackend.select()` (noted in `CLAUDE.md` › Screen capture).
