# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The **BotMaker SDK** is the runtime library that user bots compile against. The sibling
**BotMaker-Studio** app (`../BotMaker-Studio`) generates user projects that depend on this SDK and
call its public `com.botmaker.sdk.api.*` facades.

## Planning

For large changes, write the plan to a dedicated plan file before starting implementation, so work
can be resumed if a session is interrupted.

**Always update `ROADMAP.md` whenever you add a feature or refactor code** — append a dated entry
under "Done" (and add/adjust "Deferred / next" items as needed). It is the running history future
sessions rely on to understand what changed and what's intentionally left for later.

## Commands

```bash
mvn compile        # Build
mvn test           # Run tests (JUnit Jupiter)
mvn install        # Install to the local Maven repo so a local Studio checkout resolves this build
```

Demo / manual entry points (run from the IDE): `com.botmaker.sdk.Main` (smoke-tests the public find
path) and `com.botmaker.sdk.internal.Main` (internal capture harness). The `internal/**/*Test.java`
classes are manual `main`-method harnesses, not JUnit tests.

## Publishing

The SDK is consumed by Studio via JitPack as `com.github.LiQiyeDev:BotMaker-sdk:0.0.0-SNAPSHOT`.
**The maintainer owns the SDK → JitPack publish — don't push or publish the SDK yourself.** For
testing local SDK changes against a local Studio checkout, run `mvn install` here (the local Maven
repo is checked before JitPack).

## Code Style

Prefer **functional OOP**: minimize mutable class fields to avoid state-related bugs. Favor immutable
values (`record`s like `MatchResult`, `RawMatch`, `Point`/`Rect`/`Size`) and pure transformations;
pass dependencies in via parameters rather than holding mutable fields or static/singleton state.
Keep side effects (screen capture, native library loading, process launching) at the edges. The
static facades (`ImageFinder`, `ImageClicker`, `ScreenCapture`, …) are stateless dispatchers.

## Architecture

### Public API vs internal plumbing

- **`com.botmaker.sdk.api.*`** is the stable contract Studio compiles user bots against — **do not
  change existing public method signatures.** It contains:
  - `api.vision` — `ImageFinder` (find + `exists` + the lambda control-flow `whileExists`/`ifExists`
    /`untilExists`), `ImageClicker`, `ImageWaiter`, `MatchResult`, `ImageTemplate`, `ClickConfig`.
  - `api.capture.Screen` (`capture()`), `api.interaction.Mouse`/`Wait`, `api.core.Direction`,
    geometry `api.Point`/`Rect`/`Size`.
  - `api.BotMaker` — console IO. `readX()` prints a SOH-wrapped `BM-INPUT:<type>` marker to stdout
    before blocking on stdin; Studio detects/strips it to show a modal input prompt. Changing that
    marker on one side without the other breaks input prompts.
- **`com.botmaker.sdk.internal.*`** is plumbing, free to rework: `opencv`, `capture`, `emulator`,
  `inspector`, `interaction`.

### OpenCV / native loading

The native library is `org.openpnp:opencv` (self-contained — bundles the OS native and loads it via
`nu.pattern.OpenCV.loadLocally()`). **All loading goes through the single idempotent loader
`internal/opencv/OpenCvNative.ensureLoaded()`** (volatile + synchronized, runs once). It is invoked
from a `static {}` block on the classes that first touch an `org.opencv` type — `ImageTemplate` (which
owns the image `Mat`) and `OpencvManager` — so every find/match path loads the native before any Mat
is created, independent of JVM class-link order. Do not rely on scattered per-class blocks elsewhere;
a class that links an OpenCV type without a guaranteed-loaded path is how "opencv not loaded" errors
return. `ImageFinder.find` deliberately does **not** catch `Error`s (e.g. `UnsatisfiedLinkError`), so a
genuine load failure surfaces instead of masquerading as "not found".

`OpencvManager` is the matching engine: it works directly on `org.opencv.core.Mat` and returns the
internal `RawMatch` record (plain ints + score, no OpenCV types), which `ImageFinder`
maps onto the public `MatchResult`. The OpenCV `Mat` lives in `ImageTemplate` — the old `Template` /
`InternalMatch` / `MatType` wrapper layer has been collapsed.

### Screen capture

`internal/capture/ScreenCapture` is the **single** desktop-capture facade. Both `api.capture.Screen`
and every `NativeController.captureDesktop()` (Windows/Linux) route through it; there is one
`getVirtualScreenBounds()` (the AWT all-monitor union). Full-desktop capture is delegated to a
`CaptureBackend` (sealed: `RobotCapture`, `SpectacleCapture`) chosen by `CaptureBackend.select()`:

- **`RobotCapture`** — AWT `Robot` over the virtual bounds. Windows, X11, and XWayland.
- **`SpectacleCapture`** — KDE Wayland (AWT `Robot` returns black under native Wayland). Runs
  `spectacle -b -n -f -o <tmp>`; `-f` captures the **entire desktop / all monitors with no picker**.
  Falls back to `RobotCapture` on failure.

To add cross-compositor Wayland support (GNOME/sway), add a portal/PipeWire `CaptureBackend` and wire
it into `select()` — no caller changes needed. Per-window capture (Windows GDI `PrintWindow` / Robot)
also lives in `ScreenCapture` and is unchanged.

### Mouse clicks & the Wayland input limitation

`api.interaction.Mouse.click` routes through `NativeControllerFactory.get()` (Windows → `Clicker`/
`User32 PostMessage`; Linux → `LinuxController` XTest, with an AWT `Robot` fallback). Match
coordinates are absolute: `ImageFinder` adds `Screen.captureOrigin()` (the virtual-screen
origin) so clicks are correct even when a monitor sits left/above the primary.

On Linux the click warps the real cursor, then restores it. **Restore is X11-only:** under native
Wayland the JVM is an **XWayland** client that can *write* the pointer (warp + click work) but
**cannot read the global cursor position** (XQueryPointer / AWT `MouseInfo` return a stale constant
when the cursor isn't over our surface — and the bot has no window). `LinuxController` therefore
skips the restore when `WAYLAND_DISPLAY` is set, leaving the cursor on the target. The Wayland-correct
"click without disturbing the cursor" path is the xdg-desktop-portal **RemoteDesktop** (libei/
PipeWire) interface — deferred; see `ROADMAP.md`.

## Out of scope (for now)

ADB / emulator control (`internal/emulator/*`, the `ddmlib` dependency) is present but not a current
focus.
