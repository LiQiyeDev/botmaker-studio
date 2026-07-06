# BotMaker Roadmap

Living backlog + changelog for the **Studio** (this repo). The **SDK** (`../botmaker-sdk`) and **shared**
(`../botmaker-shared`) modules each keep their own `ROADMAP.md`. Claude updates the **Completed** section
whenever work lands here (see CLAUDE.md → Roadmap).

## Completed

- **2026-07-06 — Launch Program block: split path vs. launch-option args + native file browser.**
  `Game.launch(path, args...)` no longer renders duplicate program pickers: `PickerContext` now carries the
  `argIndex`, so `ExecutablePicker` matches only argument 0 (the program path) and trailing varargs use a new
  `LaunchOptionPicker` (labeled text field for command-line flags). "Browse for program…" now opens the OS-native
  dialog via `util/NativeFileDialog` (Windows PowerShell `OpenFileDialog`, Linux `kdialog`/`zenity`/`yad`, macOS
  `osascript`) — which shows hidden dotfiles and allows typing a path — falling back to the JavaFX `FileChooser`
  when no native tool is present.
- **2026-07-05 — Standardized special-type picker registry** (`ui/render/components/pickers/`). New
  `SpecialTypePicker` interface + `PickerContext` + `PickerRegistry` replace the if-else chain in
  `ArgumentEditors.editorFor` (now a thin facade). Enum dropdown extracted to `EnumPicker`. The header
  slot dispatch (`SentenceLayoutBuilder.addExpressionSlot`) now routes through the registry too, so any
  special type (not just `ImageTemplate`) is fillable in a while/if slot. Adding a new special-type
  editor = one `SpecialTypePicker` registered in `PickerRegistry`.
- **2026-07-05 — `ImageTemplateGroupPicker`** for the new SDK `ImageTemplateGroup` type: a row of
  template chips (change/remove) + "add" button, backed by `CodeEditor.setImageTemplateGroup(...)`
  which (re)writes `ImageTemplateGroup.of(new ImageTemplate("…"), …)`.
- **2026-07-05 — Fixed maximized-startup black border.** `configureWindow` no longer maximizes a
  scene-less stage; the restored/default maximized state is applied in `applyMaximizedState(...)` after
  each real scene is shown (`BotMakerStudio`), forcing a layout pass so content fills the window.
- **2026-07-05 — Renamed vision block headers** "while/if/repeat until image …" → drop "image"
  (`LambdaCallBlock.prefixFor`). Palette labels unchanged (the "Image" there aids discovery).

## Current state (2026-06-27)

- **The SDK engine is strong; its exposed surface is thin.** Present: OpenCV template matching; rich
  `ImageFinder` / `ImageClicker` / `ImageWaiter` (waitAndClick, clickUntilSuccess, clickWhileVisible, ifExists,
  region-scoped search); desktop + window + multi-monitor capture; `getForegroundWindow`; window-relative click.
  Missing from the public API: keyboard, rich mouse, window targeting.
- **SDK dependency:** the Studio depends on `com.github.LiQiyeDev:BotMaker-sdk` (JitPack). The version is
  no longer hand-bumped — `JitPackSearch` reads available versions from JitPack's `maven-metadata.xml`: new
  projects pick the latest at creation (overridable), and any project's SDK version is editable from **Manage
  Libraries** (pinned, non-removable row). `MavenService.SDK_FALLBACK_VERSION` is only used when JitPack is
  unreachable. Each generated project keeps whatever version is pinned in its own `pom.xml`.

## Refactoring backlog (Studio)

- [ ] **A5 — Refresh CLAUDE.md.** It still references the removed `BlockFactory` / `BlockParser` and the old
  `AddableBlock`; document `BlockType` / `BlockCatalog` and the event-driven drag-and-drop.

## PC-game feature backlog (SDK + Studio)

Priority: **P0** = blocks core usage, **P1** = important, **P2** = nice-to-have.

- [ ] **B1 (P0) — Keyboard input.** SDK side **done** (2026-07-03): `api.interaction.Keyboard`
  (press/release/tap/combo/type) + OS-neutral `api.interaction.Key`; Linux XTest, Windows keybd_event.
  Palette blocks **Type Text** (`Keyboard.type`) + **Press Key** (`Keyboard.tap`) landed 2026-07-03.
  **Remaining: a `Keyboard.combo(Key...)` block** (varargs key-picker UI).
- [ ] **B2 (P0) — Richer Mouse.** SDK side **done** (2026-07-03): move/moveTo, right/middle/double click,
  drag, scroll, button down/up + `MouseButton`. **Remaining: palette blocks** (awaits SDK publish).
- [x] **B3 (P0) — Image-template capture + region picker (Studio).** **Done** — image-template picker + capture
  (2026-06-30); the visual **Rect** region picker and **Point** magnifier picker for `new Rect(...)`/`new Point(...)`
  args landed 2026-07-01 (`RectPicker`, `PointPicker`, `ScreenCaptureService.selectRegion`/`pickPoint`,
  `ArgumentEditors`).
- [x] **B4 (P1) — Window targeting in the public API (SDK).** **Done** (2026-07-03): public
  `api.capture.Window` (foreground/find/all, capture, focus/move/resize) implementing a new
  `api.capture.CaptureSource` seam that every matcher (`ImageFinder`/`ImageState`/`Vision`) now accepts —
  so bots target a specific window (even off-screen / 2nd monitor) and survive moves/focus changes.
- [ ] **B5 (P1) — Run scaffold + global stop hotkey (Studio).** Generated projects are a hello-world `main`; bots
  are loops. Provide a run-loop scaffold and a global panic/stop hotkey (the game holds focus;
  `StopRunRequestedEvent` only fires from the toolbar today). Needs a global hook (e.g. jnativehook) or SDK-level
  hook.
- [ ] **B6 (P1) — Surface the rich vision/input API as blocks (Studio).** waitAndClick, clickUntilSuccess,
  exists → if, region-scoped find, key-press — buildable from the palette, not only via type-menus.
- [ ] **B7 (P2) — Replace the `BotMaker.DefaultMethod()` stub** that the "Call Function" block inserts.
- [ ] **B8 (P1) — Bump the Studio's SDK dependency** to the current SDK version and rebuild the type index so new
  APIs appear in the palette / type-menus.ic

## Completed

Most recent first. Claude appends here when work lands (date — what changed — where).

- **2026-07-03 — Image picker on lambda/sentence slots + boolean-toggle fix (Studio).** Any `ImageTemplate`
  expression slot rendered via `SentenceLayoutBuilder.addExpressionSlot` (e.g. the whileExists/ifExists image
  slot) now shows the `ImageTemplatePicker` (the slot previously ignored its expected type). Also fixed the
  true/false pill not flipping on click: `GutterDecorator` used `setOnMouseClicked`, clobbering
  `BooleanLiteralBlock`'s own toggle handler — switched to `addEventHandler(MOUSE_CLICKED, …)`.
- **2026-07-03 — Fix `whileExists` method-switch crash + Keyboard palette blocks (Studio).** Switching a
  method-call block's method to a facade method with a trailing functional-interface param (e.g. `whileExists`'s
  `Consumer<MatchResult>`) threw `Invalid identifier` — `ProjectAnalyzer.createTypeNode` fed a generic name to
  `ast.newName`. Now `createTypeNode` and `resolveLibraryType` strip generics, and `InitializerFactory` defaults a
  functional-interface arg to a block-bodied lambda (`emptyBlockLambda` in `LambdaCallHandler`) so it round-trips
  into an editable `LambdaCallBlock`. Added **Type Text** (`Keyboard.type`) and **Press Key** (`Keyboard.tap`)
  palette blocks under Input.
- **2026-07-03 — Fix SDK version not applying in Manage Libraries (Studio).** The inline version editor only
  committed on dropdown-select or Enter; typing a version then clicking Apply silently cancelled the edit, so the
  pom was rewritten with the old version. `VersionCell` now also commits on editor focus-loss (guarded on the
  popup being closed) — `ui/app/ManageLibrariesDialog`.
- **2026-07-03 — Lambda vision blocks (Studio).** Surfaced the SDK's lambda control-flow helpers as first-class
  body-carrying palette blocks: **While Image Exists** / **If Image Exists** / **Repeat Until Image Appears**
  (`palette/BlockCatalog`), each a droppable-body block whose dropped statements become the lambda body and inside
  which the matched `match` (`MatchResult`) is in scope. New reusable machinery, method-agnostic so any future
  "static call with a trailing body lambda" reuses it: `parser/handlers/LambdaCallHandler` (sole
  `LambdaExpression` build + parse site), a `BlockType.LambdaCall` sealed variant, codegen in
  `StatementFactory`, a round-trip parse branch in `BlockConverter` (detects `Class.method(img, m -> {…})` and
  recurses the lambda body via `parseBodyBlock`), and the `blocks/vision/LambdaCallBlock` UI block (modeled on
  `WhileBlock`). Emits e.g. `ImageFinder.whileExists(img, match -> { … })`; `untilExists` uses a no-arg
  `() -> {}` (Runnable).
- **2026-07-03 — Simplified SDK vision API (SDK + Studio).** Collapsed the 9-class `api.vision` package to
  three action classes — `ImageFinder` (find/findAll/findAny + `exists` + lambda control-flow
  `whileExists`/`ifExists` taking `Consumer<MatchResult>`, `untilExists` taking `Runnable`), `ImageClicker`
  (click/clickAny/clickAll), `ImageWaiter` (waitFor/waitUntilGone/waitAndClick) — plus the unchanged
  `MatchResult`/`ImageTemplate`/`ClickConfig`. Deleted `Vision`, `ImageState` (+ `ScreenState`), `ImageMatcher`
  and the `…then…`/long-tail variants. Studio: dropped `ImageMatcher`/`ImageState` from
  `palette/SdkApi.FACADE_CLASSES`. CLAUDE.md: documented that all SDK changes go through the `./BotMaker-sdk`
  submodule.
- **2026-07-03 — SDK Vision/Input/Window overhaul, Phases 1–3 (SDK).** Foundation for window-aware,
  game-driving bots. **(Ph1) Window targeting** — new `api.capture.CaptureSource` seam (`capture()` +
  `origin()`), implemented by `Screen` (`Screen.asSource()`) and a new public `api.capture.Window`
  (`foreground`/`find`/`all`, `capture`, `focus`/`move`/`resize`, window-relative `click`). `ImageFinder`
  /`ImageState` gained `CaptureSource` overloads (all legacy signatures preserved) so matching + absolute
  click coords work against a specific window. Extended internal `NativeController` + Linux (X11
  `XMoveResizeWindow`/`XRaiseWindow`/`XSetInputFocus`) and Windows (`SetWindowPos`/`ShowWindow`/
  `SetForegroundWindow`) impls. **(Ph2) Lambda decision-tree** — new `api.vision.Vision.evaluate(source,
  callback, templates…)` captures once and hands a `ScreenState` to a callback (also fixed `ImageState.
  checkState`'s prior double-capture via a single `computeState`). **(Ph3) Keyboard + richer Mouse** —
  new `api.interaction.Keyboard` (press/release/tap/combo/type) + OS-neutral `Key` enum; `Mouse` extended
  with move/right/middle/double-click/drag/scroll/down-up + `MouseButton`; backed by new
  `NativeController` input methods (Linux XTest `XTestFakeKeyEvent`/keysym→keycode, Windows
  `keybd_event`/`mouse_event`). Tests: `WindowApiTest`, `InputApiTest`, `VisionEvaluateTest` (+ injectable
  `NativeControllerFactory.setForTesting` / `RecordingNativeController`), 14 green. **SDK-only** (no palette
  blocks yet — awaits the maintainer's JitPack publish). Staged next: humanization (Bezier, `Wait.random`),
  color/mask/OCR vision, debug overlays. Linux-first; Windows impls best-effort.
- 2026-07-02 — Added Botmaker-sdk as submodule to studio

- **2026-07-02 — Game-launch picker UX (Studio).** **Launch Program** now opens a native `FileChooser`
  ("Browse for program…" / "Enter path…" fallback) instead of a bare string — new
  `ui/render/components/ExecutablePicker`. **Launch Steam Game** now opens a reusable cover-art grid popup
  (`ui/render/components/GameLibraryPickerDialog`: searchable tiles from local `library_600x900.jpg` /
  `header.jpg`, initials placeholder when none, plus a manual-id fallback field) instead of a text combo —
  `SteamGamePicker` rewritten to launch it. Discovery generalised behind `game.GameLibraryProvider` +
  `game.InstalledGame(platform, id, name, artwork)`, with `game.SteamLibraryScanner` refactored to
  implement it and resolve local cover art — so Epic/GOG plug in later by adding a provider (dialog + block
  wiring reused). Wired via `ArgumentEditors` `Game.launch`/`launchSteam` branches; tile CSS in `blocks.css`.
- **2026-07-02 — Game launch blocks (SDK + Studio).** New SDK facade `api.launch.Game`
  (`launch(path, args…)` via `ProcessBuilder`; `launchSteam(appId)` via cross-platform
  `steam://rungameid/<id>` with `steam -applaunch` fallback; internal `launch.UriLauncher`). Studio exposes
  two blocks in a new **Game** category — **Launch Program** / **Launch Steam Game** (`BlockCatalog`,
  `BlockCategory.GAME`, `ColorPalette`, `SdkApi` facade so they round-trip). The Steam block's appId arg gets
  a dropdown of locally-installed games via `game.SteamLibraryScanner` (parses `libraryfolders.vdf` +
  `appmanifest_*.acf`, no login/API key) wired through `ArgumentEditors`/new `SteamGamePicker` (editable,
  free-text appId still allowed). Steam launching needs no auth of ours — the signed-in Steam client owns the
  session. Note: scanner lists Steam "tools" too (Proton, Linux Runtime) — filtering them out is a future refinement.
- 2026-07-02 — App-update flow fixed: download now runs behind a modal progress bar and the installer launch moved off the FX thread (AWT `Desktop` on the FX thread was freezing the window to a white screen); manual-restart messaging (`MenuBarManager.downloadAndInstall`, `UpdateService.downloadInstaller(update, progress)`).
- 2026-07-02 — Version reporting fixed: dev-fallback bumped to 1.0.5 (`AppVersion.FALLBACK`, pom `app.version`/`version`); release workflow already bakes the tag's numeric version into the manifest so installed builds report their true version. Correctly-stamped builds require a new tagged release.
- 2026-07-02 — Main window stays maximized: geometry persisted via `ProjectPreferences.WindowState`, restored large and maximized (`BotMakerStudio.configureWindow`) so opening a popup no longer shrinks the window to a quarter-screen.
- 2026-07-02 — App icon wired: Stage window/taskbar icon from `icons/icon-*.png` rasters (`BotMakerStudio.applyAppIcons`) + jpackage `<icon>` per OS profile via `${installer.icon}` (png default, `.ico` on Windows). Source SVG lives at `src/main/resources/icons/icon.svg`; rasters must be generated/committed.

- **2026-07-02 — Method blocks, unified type menu & list-UI fixes.** (a) Return types now offer the full
  primitive+SDK+project type list (was primitives-only) via a clickable chip; (b) one searchable type picker
  (`ExpressionMenuFactory.showTypeMenu`) replaces the return ComboBox, the add-param menu and the type-change
  submenu — `MenuComponents.populateGroupedTypeMenu` retired; (c) new non-void methods get a default `return`
  (primitive literal / `null` for objects), kept in sync on return-type change only when still an untouched default,
  removed on switch to `void` (`MethodHandler`); (d) class parameter/return types are now imported
  (`MethodHandler` add/change/return → `ImportManager.addImportForSimpleName`); (e) fixed list **move-down** no-op
  (`ListHandler.moveElement` off-by-one); (f) small glyph action buttons share a fixed footprint
  (`BlockUIComponents.createMove{Up,Down}Button` + `.icon-button` CSS), unifying `ListBlock` and `SwitchBlock`.
- **2026-07-01 — List block fixes + for-each body accent + version/auto-update/installers.** Two clusters:
  - **Lists** — element-type inference extracted to a pure, unit-tested `ListElementType` (fixes multi-dim
    arrays: outer `String[][]` now yields `String[]`, and adds generic `List<T>` support); the "+" add menu now
    reuses the type-aware `ExpressionMenuFactory` (variable/method/constructor submenus) via new
    `CodeEditor.insertIntoList` / `ListHandler.insertChoiceIntoList` instead of a bare placeholder; the "+" moved
    beneath the last element; per-row ▲/▼ reorder buttons (`CodeEditor.moveListElement` /
    `ListHandler.moveElement`); list button/label styling moved from compounding inline styles to absolute-size
    CSS so nested lists no longer shrink (`blocks.css`). For-each/if/while body now shows a left accent bar
    (`BodyLayoutBuilder` `block-body` class + `blocks.css`).
  - **Distribution** — runtime version via `Implementation-Version` in the shade manifest + `config/AppVersion`,
    shown in About + the landing screen; in-app updater (`services/UpdateService`, Help → **Check for Updates…**)
    that compares GitHub Releases via `SemVer` and downloads/launches the OS installer; `-Pdist` now builds native
    `.deb`/`.rpm` (Linux) and `.msi` (Windows) installers (OS-activated pom profiles) and `release.yml` publishes
    them alongside the portable zip.
- **2026-07-01 — Non-blocking project open with progress screen.** `BotProject.open()` now runs on a
  background `Task` (`BotMakerStudio.openProject`); the window shows a loading scene (indeterminate
  `ProgressBar` + live status label) immediately instead of freezing while Maven downloads jars. Progress
  is driven by a new `Consumer<String>` threaded through `BotProject.open` → `MavenService.resolveClasspath`,
  which wires an Aether `TransferListener` to report per-jar downloads plus coarse stage messages.
- **2026-07-01 — Specialized SDK call block + bot-first argument editors + breakpoint restore.** A large
  authoring upgrade across the block system:
  - **Breakpoints re-addable** — `toggleBreakpoint()` was orphaned; re-wired via an "Add/Remove Breakpoint"
    context-menu item (`InteractionDecorator`), a clickable gutter strip and a double-click handler
    (`GutterDecorator`).
  - **SDK call block** — `LibraryCallBlock`/`MethodInvocationBlock` now render a distinct `sdk-call-block`
    (purple accent + "🤖 SDK" badge, `blocks.css`) with an **inline class dropdown** to switch between the SDK
    facades; SDK calls are recognized in expression context too. Canonical facade list in new `palette/SdkApi`
    (fixes `BlockConverter.isLibraryClass` missing `ImageWaiter`/`ClickConfig`).
  - **Typed argument editors** — new `ui/render/components/ArgumentEditors` dispatches per-arg widgets, shared by
    `MethodInvocationBlock` + `ListBlock`: image picker for **every** `ImageTemplate` (varargs now stretched via
    `MethodSignature.varargs`/`paramTypeAt`, so all of `findAny(...)` get it), new **RectPicker** (drag a region,
    reusing `ScreenCaptureService.selectRegion`) and **PointPicker** (magnifier overlay, `pickPoint`), a manual
    `NumberFieldsDialog`, and a **Direction/enum dropdown** (SDK library params are now index-backed/enum-aware via
    `ProjectAnalyzer.resolveLibraryType`). New `CodeEditor.setRect`/`setPoint`.
  - **Expression menu** — reworked to mirror the statement menu: a search box + flat quick-picks
    (`ExpressionMenuFactory`), plus a generic **"New &lt;Type&gt; variable…"** entry in the Variables submenu
    (`ExpressionChoice.NewVariable` → `CodeEditor.declareVariableBeforeAndReference`) — so a `Direction` (or any
    typed) variable can be created inline. Completes backlog **B3**'s pending Rect picker.

- **2026-06-30 — Bot runtime: OpenCV classpath fixed + SDK loads the native; Wayland-capture limitation noted.**
  A generated vision bot failed at runtime with `NoClassDefFoundError: org/opencv/core/Mat`. Root cause was in
  the Studio's in-process Aether resolver (`MavenService.resolveClasspath`): `MavenRepositorySystemUtils.newSession()`
  didn't expose the JVM system properties, so bytedeco's `javacpp-presets` parent POM failed model-building on a
  JDK-activated profile (`Failed to determine Java version for profile doclint-java8-disable`); the descriptor
  read was silently ignored, collapsing the whole `opencv-platform` subtree (so the opencv main jar + natives
  never reached the run classpath). Fix: `session.setSystemProperties(System.getProperties())` — the `-platform`
  aggregators now expand exactly like `mvn` (23 → 173 jars; `opencv-…​.jar` + host natives present). On the SDK
  side, the production path (`ImageFinder.find → Template → OpencvManager`) never loaded the OpenCV native — added
  `internal/opencv/OpenCvNative.ensureLoaded()` (`Loader.load(opencv_java.class)`, idempotent) called from the
  static initializers of `OpencvManager`, `Template`, and `internal/capture/ScreenCapture`. Runtime system
  dependency: bytedeco's `opencv_java` links highgui → **GTK2** (`libgtk-x11-2.0.so.0`); on Fedora install
  `gtk2`. Screen capture (`ScreenCapture.captureDesktop`) already unions all monitors and is silent on **X11 /
  Windows**; on a **Wayland** session AWT Robot is forced through the desktop portal (per-call prompt) — for
  silent all-monitor capture, run the bot in a Plasma **X11** session. SDK changes await the user's JitPack
  publish.
- **2026-06-30 — Palette/menu blocks now emit imports for SDK/library types + image picker works in lists.**
  Fixed the compile bug where dropping vision blocks produced unimported bare names
  (`ImageFinder.find(new ImageTemplate(...))` → `cannot find symbol`). Added
  `ImportManager.addImportForSimpleName(...)` (resolves a simple name to its FQN via `ProjectAnalyzer` and
  imports it) and threaded the `ProjectAnalyzer` into the write path (`CodeEditor` ctor) and the node
  builders (`StatementFactory`, `ExpressionFactory`, `MethodHandler.createMethodInvocation`, `NodeCreator`),
  so static-call scopes, `new T(...)`, var-decl types and enum constants all import. Extracted the inline
  image-template picker out of `MethodInvocationBlock` into a reusable
  `ui/render/components/ImageTemplatePicker` and used it in `ListBlock`: `ImageTemplate` list elements now
  render the picker, and the list "+" adds a `new ImageTemplate("")` element directly
  (`ListHandler.addImageTemplateElement` / `CodeEditor.addImageTemplateToList`).
- **2026-06-30 — Cross-platform GitHub Release (CI) + dist profile actually landed.** Implemented the `dist`
  Maven profile that was previously only documented: stages the shaded jar and runs `jpackage` into a portable
  app-image, bundling the **full build JDK** (`--runtime-image ${java.home}`) so the Studio's `javac`/`java`/JDI
  subprocesses for compiling, running and debugging user bots work (`pom.xml`). Added
  `.github/workflows/release.yml` — an ubuntu+windows matrix that builds on a `v*` tag push and publishes both
  app-image zips to a GitHub Release. Per-leg `-Djavacpp.platform=<host>` ships host-only OpenCV natives
  (~1.2 GB → ~580 MB). Added a shade signature-file filter (fixes "Invalid signature file digest") and pointed the
  shade manifest at `com.botmaker.studio.Launcher` so the bare fat jar runs too. Fixed a stray-char typo in `pom.xml`.
- **2026-06-30 — Self-contained release groundwork + README rework.** Added `com.botmaker.studio.Launcher`
  (non-`Application` entry point) so the fat jar / app-image launches without the "JavaFX runtime components are
  missing" error. Reworked `README.md`: corrected stale Gradle→Maven, removed the dead JDT-language-server setup
  step (the LSP server is never launched; diagnostics come from in-process JDT Core) and the nonexistent
  light/dark theme claim, added Download + Packaging sections.

- **2026-06-30 — Resource Manager shortcut + richer screen chooser w/ remembered default.** Image-template
  picker gains an "Open Resource Manager…" item (`MethodInvocationBlock` → new `OpenResourceManagerEvent`,
  handled in `UIManager`). The multi-monitor capture chooser (`ScreenCaptureService`) now grabs the desktop
  first and shows per-screen preview thumbnails + details (resolution/position/primary/scale); the last pick
  is remembered and preselected via `ProjectPreferences.captureScreenIndex`.
- **2026-06-30 — Project/SDK/gallery links + bigger template previews + screen picker.** Help menu now
  links to the Studio and SDK GitHub repos (`MenuBarManager`); Project menu links to the published
  project repo (`UIManager`/`MenuBarManager`); gallery dialog links to the gallery repo (`GalleryDialog`).
  Inline image-picker thumbnails (`MethodInvocationBlock`) and the Resource Manager preview
  (`ResourceManagerDialog`) are larger and window-scaled. Screen capture adds a multi-monitor chooser
  (`ScreenCaptureService`).
- **2026-06-30 — Wayland screen capture + hide/lock `Activities.java`.** `ScreenCaptureService` now
  shells out to an installed screenshot CLI (`grim`/`gnome-screenshot`/`spectacle`) under Wayland (where
  `Robot` returns black), keeping `Robot` on X11/Windows. The generated `Activities.java` is hidden from
  the file tree (`FileExplorerManager.buildFileTree`) and forced read-only in the editor
  (`CodeEditorService.refreshUI`, "[Generated - Read Only]").
- **2026-06-30 — Activities (global config variables).** Editor defines named, typed globals
  (`ActivityType`: Bool/Int/Double/Text/Time/Date → Java types); the user fills values. Schema + values
  persist to `src/main/resources/activities.json` (`ActivitiesConfig`, modeled on `BotSource`); a
  generated `Activities.java` exposes `public static final` fields loaded from that JSON at startup, so
  blocks reference `Activities.<name>`. New `ActivityService`, `ProjectState.activities`, wired in
  `BotProject.open`. Expression menu gains an **Activities** submenu (`ExpressionCatalog.ACTIVITY` +
  `ExpressionMenuFactory.activitiesSubmenu` + `ProjectAnalyzer.getActivityVariables`), type-filtered,
  emitting an `ExpressionChoice.Field("Activities", …)`. UI: **Project → Manage Activities** /
  **Set Activity Values**. `src/main/resources` added to the run/debug classpath so the JSON resolves.
- **2026-06-30 — Image-template capture + in-block picker (B3, partial).** `find`/`click`/`waitFor`
  blocks now render an inline image-template picker for their `ImageTemplate` argument
  (`MethodInvocationBlock.renderArguments` → thumbnail `MenuButton`): pick a saved template or
  **Capture new…** (crop the screen). New `ScreenCaptureService` (pure-Java AWT `Robot`, full virtual
  desktop, rubber-band crop; Wayland caveat noted), `ImageTemplateLibrary` (lists PNGs under
  `src/main/resources/images`, maps to project-relative paths), `CodeEditor.setImageTemplate`, and a
  **Project → Resource Manager** dialog (preview / rename / delete / capture). Rect region picker still
  pending.
- **2026-06-29 — Gallery/selection refinements (round 2).** (1) **Publish version** is now a structured,
  monotonic picker: `PublishDialog` uses an editable `ComboBox` seeded with the patch/minor/major bumps
  after the repo's latest release, and Publish is disabled (with a reason) unless the value is valid
  semver AND strictly greater than the last published tag (`SemVer.compare`/`isGreater`/`nextMinor`/
  `nextMajor`). (2) **Project selection**: sort dropdown (Name/Date asc-desc), always-on **Local** /
  **Imported** group headers (new `Row`/`ProjectRow`/`HeaderRow` model), and a **My projects only**
  filter (local + published-by-you) gated behind GitHub sign-in. Removed the "Clear Language Server
  Cache" checkbox (open now always passes `clearCache=false`). (3) **Linux browser fix**: new
  `util/BrowserLauncher` falls back from `Desktop.browse` to `xdg-open`/`open`/`rundll32`, fixing the
  OAuth page not opening; reused by `PublishDialog` + `GitHubAccountBar`. Tests: extended `SemVerTest`.

- **2026-06-29 — Gallery publish UX + project archive + GitHub account management.** (1) **Publish
  dialog**: version (tag) now auto-proposes the next patch after the repo's latest release (new pure
  `sharing/SemVer.next`, falls back to local provenance then `1.0.0`); added a **Tags** field threaded
  through `BotPublisher.publish`/`submitToGallery` (was hardcoded `[]`). (2) **Account management**: new
  reusable `ui/app/GitHubAccountBar` (extracts the device-flow sign-in from `PublishDialog`) adds
  **Sign out** / **Switch account** and shows the signed-in login; used by both the publish dialog and
  the project-selection screen. `GitHubAuth` gained a cached `login(client)` (cleared on sign-out / new
  token). (3) **Project selection**: **Archive** (soft-delete → `~/BotMakerProjects/.archive/`, new
  `Constants.ARCHIVE_ROOT` + `ProjectManager.archiveProject/restoreProject/listArchivedProjects`) with a
  **Show archived** toggle to restore; per-project ownership badges (Local / Published by you / Imported
  from {owner}). Cleanup: removed `ProjectManager.isValidProject` debug prints. Tests: `SemVerTest`,
  `PublishDialogTagsTest`.

- **2026-06-29 — Gallery publish fixes (post first-publish).** (1) `BotPublisher.submitToGallery`: when the
  publisher owns the index repo (maintainer case), commit the `index.json` entry directly instead of
  trying to fork-your-own-repo + self-PR (which 422'd `"No commits between main and main"`). Factored the
  read/append/commit into `editIndex` + a pure, tested `mergeEntry` helper that **dedupes by `owner/repo`**
  so re-publishing is idempotent; non-owners still fork → `awaitFork` → edit → PR. (2)
  `ProjectSelectionScreen`: the create-project SDK version combo is pre-seeded with `SDK_FALLBACK_VERSION`
  so it's never empty/offline-blank; the JitPack fetch then refines it (and no longer wipes the seed on an
  empty result). Test: `BotPublisherIndexTest`.

- **2026-06-29 — Federated bot gallery: publisher side (Track B, part 2).** New in `com.botmaker.studio.sharing`:
  `GitHubAuth` ("Sign in with GitHub" OAuth device flow — no token pasting; token stored 0600 under the
  cache dir, never in `~/BotMakerProjects/`), `ProjectArchive` (collect publishable files, excluding
  `target`/`.git`/provenance), `BotPublisher` (ensure repo → push the project tree via the Git Data API
  → cut a release tag → best-effort fork+PR to the curated index repo → write local provenance), and
  `GitHubClient.patch`. UI `ui/app/PublishDialog` (device-flow sign-in panel, repo/description/version form,
  result with repo + PR links), reachable from **Project → Publish to Gallery…**. Degrades gracefully when
  `OAUTH_CLIENT_ID` is unset ("publishing not configured"). Tests: `ProjectArchiveTest`. **Maintainer
  setup still required to go live:** register a device-flow GitHub OAuth App and paste its client id into
  `GitHubConfig.OAUTH_CLIENT_ID`; create the index repo (`INDEX_OWNER/INDEX_REPO`) with a seed `index.json`.

- **2026-06-29 — Federated bot gallery: consumer side (Track B, part 1).** New `com.botmaker.studio.sharing`
  package: `GitHubConfig` (stubbed maintainer values — OAuth `client_id` + index-repo coords — with
  graceful degradation), `GitHubClient` (JDK `HttpClient` + Jackson REST/raw helper, no GitHub SDK),
  `GalleryEntry` + `GitHubGallery` (browse the curated `index.json` via raw CDN URL; live `latestReleaseTag`
  per author repo), `BotSource` (provenance `botmaker-source.json`), `BotInstaller` (download release zip →
  unzip into `~/BotMakerProjects/` with zip-slip guard → record provenance; `checkForUpdate`/`update`).
  UI `ui/app/GalleryDialog` (Browse + Installed tabs, trust-warning gated install, per-row update badge),
  reachable from **Project → Browse Gallery…** and a **Browse Gallery** button on the project-selection
  screen. Account-free (browse/install). Tests: `GalleryAndInstallerTest`. Remaining: Track B part 2 —
  publisher side (OAuth device flow, repo create + Git Data API push, release, index fork+PR).

- **2026-06-29 — SDK version is JitPack-driven & user-editable (Track A of SDK/sharing work).** New
  `services/JitPackSearch` fetches versions/latest from the SDK's JitPack `maven-metadata.xml` (no more
  hand-bumping). `MavenService` exposes `SDK_GROUP_ID/ARTIFACT_ID/FALLBACK_VERSION`, a
  `writePom(..., sdkVersion)` overload, `readSdkVersion`, and `writeUserLibraries(..., sdkVersion)`;
  `LibraryService.updateLibraries(userLibs, sdkVersion)` replaces `updateUserLibraries`. The create-project
  dialog (`ProjectSelectionScreen`) now has an SDK-version picker defaulting to the latest JitPack release,
  threaded through `ProjectCreator.createProject(name, sdkVersion)`. **Manage Libraries** shows the SDK as a
  pinned, non-removable row and gives every row an editable version dropdown (JitPack for `com.github.*`,
  else Maven Central). Tests: `JitPackSearchTest`, `MavenServiceSdkTest`. Remaining: Track B — federated
  GitHub bot-sharing gallery (publish/browse/install/update) per the plan.

- **2026-06-28 — Bot-builder surfacing: SDK-backed Print/Read, whitelisted type menus, imports UI.**
  (1) Print/Read blocks now compile to the SDK instead of raw Java: added `BotMaker.print(...)` and
  `readLine/readInt/readDouble/readBoolean` (`BotMaker-sdk` `api/BotMaker.java`, lazy private `Scanner`);
  `StatementFactory` emits `BotMaker.print(...)` / `BotMaker.readX()` (and adds the import), `BlockConverter`
  recognizes them, `BlockCatalog`/`ReadInputBlock` use the `readX` names, and `scanner` left `HIDDEN_VARIABLES`.
  (2) Whitelisted the type/expression menus to `com.botmaker.sdk.api.*` via a configurable
  `allowedPackagePrefixes` filter in `TypeSummaryManager.ensureCaches()` — transitive deps (opencv/jackson/
  eclipse/ddmlib) and the SDK `internal` package are still indexed for resolution but hidden from the user.
  (3) **Project → Manage Imports…** (`ui/app/ManageImportsDialog`) lists/adds/removes the current file's
  imports via new `CodeEditor.addImport/removeImport/getImports` + `ImportManager.removeImport/listImports`.
  Bumped `MavenService.DEFAULT_DEPENDENCIES` to the republished `com.botmaker.sdk:botmaker-sdk:1.0.1` so the
  new Print/Read blocks compile in generated user projects. (B7 `DefaultMethod` stub still pending.)

- **2026-06-28 — Fixed three bugs (undo/redo, function-call dropdowns, type-menu lag).**
  (1) Undo/Redo was a no-op: `CodeEditorService.applyHistoryState` published a `CodeUpdatedEvent` while
  `isRestoringHistory` was set, which suppressed the UI refresh — switched it to `UIRefreshRequestedEvent`
  (refreshes without re-recording history) and deleted the now-dead `isRestoringHistory` flag.
  (2) `MethodInvocationBlock` dropdowns now mirror `ExpressionMenuFactory`: the class selector adds a
  `--- LIBRARIES ---` section from `getStaticUtilityTypes()`, and the method selector filters by the slot's
  expected return type (via new `MethodSignature.returnsCompatibleWith` / `typeSatisfies`, also reused by the
  menu). (3) First-menu lag: warm `TypeSummaryManager`'s derived caches + `ProjectAnalyzer`'s library
  `ResolvedType`s on a background daemon thread at `BotProject.open` (new `warmCaches` / `warmLibraryTypes`;
  `ensureCaches` / `libraryTypes` made `synchronized`).
- **2026-06-28 — Removed `CompletionContext`.** The record (in the vestigial `lsp/` package) was just a partial
  copy of the `CodeEditorService` that built it, plus three dead fields (`docUri`/`sourceCode`/`docVersion`), a
  dead `getConfig()`, and an unused `LanguageServer` import. Now the owning `CodeEditorService` is threaded
  directly through `getUINode` / `createUINode`; added `getState` / `getEventBus` / `getDragAndDropManager`
  getters and rewrote call sites (`context.codeEditor()` → `getCodeEditor()`, `context.codeEditorService()` →
  `context`, etc.). Deleted the record, `createCompletionContext()`, and the empty `lsp/` package; updated
  CLAUDE.md.
- **2026-06-28 — Blocks-package duplication sweep (round 2).** Extracted four more duplicated UI patterns:
  `ExpressionMenuFactory.installTypeSelector` (cursor + tooltip + type-menu wiring, used by the variable /
  field / parameter / enum blocks); a new `ui/render/menu/MenuComponents` with `populate` / `showListMenu`
  (flat list→menu with empty fallback, used by `IdentifierBlock` / `ListBlock` / `MethodInvocationBlock`) and
  `populateGroupedTypeMenu` (PRIMITIVES/CLASSES grouped type picker, shared by `MethodDeclarationBlock` /
  `ConstructorBlock` — the constructor add-param menu is now grouped/sorted/deduped like the method one); and
  `AbstractExpressionBlock.createArgumentPill` (change-button + pill wiring shared by `InstantiationBlock` /
  `MethodInvocationBlock`).
- **2026-06-28 — `InstantiationBlock` cleanup.** Dropped the private `determineExpectedType()` (a worse,
  two-case duplicate of `ProjectAnalyzer.inferExpectedType`) in favour of the shared analyzer, and moved all
  inline `setStyle(...)` strings into `blocks.css` style classes (`.instantiation-block` and children).
- **2026-06-28 — Expression-menu follow-ups: context node, empty-slot picks, Call-Function, perf.** Local
  `VariableDeclarationBlock` / `ReturnBlock` / `SwitchBlock` were calling menu overloads that hardcoded
  `contextNode = null` (no Variables/Call-Function suggestions); removed those overloads and pass
  `this.astNode`. Added a single `NodeCreator.createExpression(selection)` that builds an AST node from either
  an `ExpressionType` or an `ExpressionChoice`, and routed the empty-slot setters (`setVariableInitializer` /
  `setFieldInitializer` / `setReturnExpression`) through it so empty slots now accept variable/method/
  constructor/enum picks (not just literals). `ExpressionMenuFactory.functionCallSubmenu` now offers the
  enclosing class's own methods ("This (Class)", local call) and a lazily-built "Library (static)" group over
  all external-jar classes with static returning methods (`TypeSummaryManager.getStaticUtilityTypes`).
  Performance: `TypeSummaryManager` lazily caches the flattened type list + simple/qualified-name maps +
  static-utility list; `ProjectAnalyzer.getAvailableTypes` memoizes the library `ResolvedType` list
  (invalidated on index-size change), removing the per-open full library rescan. Covered by extended
  `ScopeAtLiteralNodeTest`. Follow-up: Call-Function now hides non-user variables (`args`/`this`/… via
  `isUserVariable`) and array-typed targets, and filters method suggestions to those whose return type is
  assignable to the slot's expected type. QOL: scope/jar/package submenus with no type-compatible members are
  dropped instead of shown empty (`buildScopeMenu` returns null); and readable **fields** are now suggested
  alongside methods — static constants for class scopes, instance members for variable scopes — via a new
  `ExpressionChoice.Field` + `ProjectAnalyzer.getFields`, inserted as `scope.field`.
- **2026-06-28 — Fix empty Variables/Methods menus + auto method signatures.** Root cause: the editor AST was
  parsed without `setUnitName`, so source bindings came back null, and `VariableScopeVisitor.getScopeAt` only
  answered for "trigger" nodes — so the menu's literal/placeholder `contextNode` always yielded an empty
  scope (enums still showed because they come from the ClassGraph index, not the scope visitor). Added
  `setUnitName` in `ProjectAnalyzer.createCompilationUnit` (threaded from the active file) and made
  `getScopeAt` capture the live scope at `preVisit(node)` for any node (excluding the node's own
  declaration). Refactored the 165-line `ExpressionMenuFactory.createExpressionTypeMenu` into per-concern
  helpers and drove category order off the enums. `MethodInvocationBlock` now resolves methods/signatures via
  `ProjectAnalyzer.getMethods` (project **and** external-library types), auto-picks the overload matching the
  current arg count (smart-merge handles args), and dropped the manual ⟳ sync button (kept the ⚙ overload
  picker). New `ScopeAtLiteralNodeTest` covers the fix.
- **2026-06-28 — A2: `AstRewriter` merged into `CodeEditor`.** Deleted the ~40-method pass-through façade; pure
  `(cu, code) → code` rewrites now go straight to `parser/handlers/*` or to `private static` transforms folded
  into `CodeEditor`, and the per-method `canModify()` / `triggerUpdate()` boilerplate is factored into one
  `edit(markUnedited, op)` helper. Pipeline is now `CodeEditor → handlers/* + NodeCreator → AstRewriteHelper`.
- **2026-06-28 — A1: `AddableExpression` enum → sealed `ExpressionType` hierarchy.** New `com.botmaker.studio.palette`
  members (`ExpressionType` with `Literal` / `Reference` / `InfixOp` / `PrefixOp`, JDT-free `Op` enum;
  `ExpressionCategory`; `ExpressionCatalog` constants + `getForType` / `isCompatibleWith`). `ExpressionFactory`
  pattern-matches the sealed type instead of two name-decoding switches; deleted the nullable
  operator/return-type fields. Covered by `BlockDragDropEditTest`.
- **2026-06-27 — `AddableBlock` enum → sealed `BlockType` hierarchy.** New dependency-free `com.botmaker.studio.palette`
  package (`BlockType`, `Initializer`, `BlockCategory`, `BlockCatalog`); `StatementFactory` now pattern-matches on
  the sealed type with data-driven `VarDecl` / `ScannerRead` / `LibraryCall` builders; deleted the dead `blockClass`
  field and the name-decoding switch / `name().startsWith` / `valueOf` coupling. Covered by `BlockDragDropEditTest`.
- **2026-06-27 — Drag-and-drop QoL.** Non-reflowing insertion indicator (pseudo-class, background-only);
  whole-block top/bottom-half drop hitbox; self-move guard; removed the forbidden-drag cursor flash; removed the
  right-click "toggle breakpoint" popup (the gutter circle still toggles breakpoints).
- **2026-06-27 — Drag-and-drop architecture rework.** Event-driven drops (`BlockDropRequestedEvent` /
  `BlockMoveRequestedEvent`) replacing never-wired callbacks; fixed the method-declaration double-drag
  registration; parent-chain control hit-test; pseudo-class drag feedback. All four flows (palette-add,
  class-member add, statement move, method reorder) now work.
