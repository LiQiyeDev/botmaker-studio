# BotMaker Roadmap

Living backlog + changelog for the **Studio** (this repo). The **SDK** (`../botmaker-sdk`) and **shared**
(`../botmaker-shared`) modules each keep their own `ROADMAP.md`. Claude updates the **Completed** section
whenever work lands here (see CLAUDE.md → Roadmap).

## Completed

- **2026-08-08 — the pilot's frame loop stops paying for what it throws away (`services/pilot/TargetCapture`,
  `PilotServer`).** A session frame cost three codec passes (see `botmaker-session/ROADMAP.md`); it now
  arrives already encoded from the display agent and goes to the wire untouched, so `Capture.img()` is null on
  that route by design — decoding it here purely to fill the field would reinstate the pass. `Resolved` carries
  the optional bytes and `bytes()` encodes on demand for the `:0`/emulator routes. `jpegBytes` delegates to
  `session.Preview` (cached writer, 1280-px long edge, quality 0.6) instead of a per-frame `ImageIO.write` at
  full size; `captureBounds` caches its `Robot` per thread instead of building one every frame. The loop is now
  self-rescheduling at `clamp(period − work, 5 ms, period)` targeting **24 fps** — it keeps the fixed-delay
  no-backlog property (a tick is only scheduled once the last returned) without charging the full period on top
  of the work. Downscaling is safe because the client fits and maps touches through the header's `sw`/`sh`
  surface rect, never the bitmap's pixel size.
- **2026-08-08 — the pilot follows the pixels, not the session (`services/pilot/PilotRoutes`,
  `TargetCapture`, `PilotServer`, `TelemetrySerializer`).** A live nested session won rung 1 unconditionally
  *and released the ADB surface while doing so*, so a gamescope session hosting Waydroid — a Wayland-only
  client whose surface never reaches the embedded Xwayland — suppressed the one route that could see those
  pixels in favour of an X11 grab of an empty root. Rung 1 is now conditional on the new
  `DesktopSession.x11Capturable()`; losing it is a **demotion, not a skip** — a new rung 4 hands the session
  back ahead of the `:0` desktop, because streaming the user's real screen to a possibly-public Funnel URL and
  replaying taps on it is a worse answer than a black frame. Two backstops behind the flag: `captureSession`
  reads an entirely black root as *no capture* (a coarse 16-px grid, not a full scan — this runs every frame),
  and `pushFrame` counts consecutive frameless ticks and, after two seconds, broadcasts the `state` message
  with a `reason` so the client says why instead of showing nothing. `reason` is **omitted** when absent,
  which keeps the three existing `wire-golden.json` cases byte-identical against the digest-locked copy in the
  pilot repo; a corpus entry belongs with the client change that renders it.
- **2026-08-08 — an emulator's templates are cropped from the frame the bot matches against
  (`services/ScreenCaptureService`, `ui/app/capture/CaptureSurface`, `OverlayTemplateCapture`).** An
  `EmulatorTarget` had no branch in `grabOffThread`, so it fell through to "grab the virtual desktop": the
  template was cut out of the **host window** the emulator is drawn in, while the bot matches it against the
  frame it pulls over ADB. On Waydroid those were a scale factor apart and nothing matched — see shared's
  same-day entry for the other half. `captureDefaultTargetAsync` now takes one ADB `screencap` for an emulator
  target, and `TargetShot` carries `onScreen`: false means those pixels are nowhere on the desktop, so
  `CaptureSurface` paints the frame as a backdrop instead of staying transparent over the user's own screen.
  Region-select and point-pick are deliberately untouched — they answer in desktop coordinates.
- **2026-08-08 — SDK-call and method-call blocks are readable in all four themes (`css/blocks.css`,
  `BlockUIComponents.createArgumentPill`).** `.sdk-call-block` was the literal `#f3ecfb` and never redefined
  `-bm-text-on-color`, so its labels took the `.root` default `white` — white on near-white, and outside the
  token system, so no theme could reach it. It now has a measured fill/on-fill pair (`-bm-fill-sdk` /
  `-bm-on-fill-sdk`, plus `-bm-badge-sdk` for `.return-type-badge`) overridden per theme, and the on-colour
  cascades to everything inside the call. The argument pill's light/dark wash was a boolean passed at
  construction (`--on-dark`, always true from `MethodInvocationBlock`); it is now one `ladder()` on the
  surface's on-colour, so it follows the fill it lands on and the flag is gone from the Java API.
- **2026-08-08 — the theme reaches every window, not just the main one
  (`ui/render/theme/ThemedWindows`, ~40 dialogs, `css/blocks.css`).** Dark and Black stopped at the editor:
  Flow, Pilot, Resources, the setup wizards and every `Alert` opened as a white Modena pane. A themed window
  needs *two* things — `blocks.css` **and** the theme's style class (`.dark-theme` redefines Modena's own
  `-fx-base`/`-fx-background`) — and each JavaFX window owns a separate `Scene` that inherits neither from its
  owner, so there was no styling them by parenting. `ThemedWindows` is now the single place that hands out
  both: `apply(Scene/DialogPane/Dialog/Stage)`, plus `scene(...)` and `alert(...)` constructors so a new
  dialog is themed by default rather than by remembering. It also subscribes the window to `BlockTheme` while
  it is showing and unsubscribes on hide, so a theme switched with dialogs open reaches them without leaking
  listeners. `UIManager`'s private copy is gone; `OverlayStyles.applyThemeClass` delegates. New dialog-chrome
  classes (`.dialog-heading/-hint/-status/--ok/--error`, `.transparent-scroll`) replace the inline literals in
  `LaunchTargetDialog` and the loading screen — a literal `gray` or `#b00020` beats the stylesheet in every
  theme, which is exactly how those lines stayed unreadable. `-bm-severity-ok-text` joins the severity tokens
  in all four themes. Deliberately untouched: the transparent capture overlays, which are chrome-less by
  design and load `blocks.css` themselves.

- **2026-08-08 — Interact can no longer wedge the host pointer
  (`services/pilot/PilotInputService`, `PilotServer`, `TargetCapture`).** With the Firestone *window* picked as
  the capture source, a few taps and the whole desktop stopped responding until Studio was killed. Two causes,
  both fixed. (1) **A held button was never guaranteed to come back up.** `apply` dropped *any* gesture outside
  the frame bounds — including the `UP` ending a drag — so `BTN_LEFT` stayed down on the virtual uinput device,
  which on X is an implicit pointer grab on the window that got the press: every later click anywhere goes
  there, and only Studio exiting (destroying the device) ends it. A mid-drag `MOVE`/`UP` is now **clamped** into
  the frame rather than dropped (`DOWN`/`TAP`/`SCROLL` keep the hard reject), the held button and the route it
  was pressed on are remembered, and `releaseHeld()` — idempotent — runs on every other exit a drag can take:
  a throw, a route change mid-drag, `ws.onClose`/`onError` (the phone that vanishes), and `close()`.
  (2) **`:0` input landed in gamescope's own container.** gamescope renames its output window after the app it
  hosts, so on `:0` the best title match for "Firestone" *is* the session's container; `captureWindowTarget`
  now refuses a window whose id is the live session's `hostWindowId()`. Also: the AWT screen-rect helpers
  answer `null` instead of throwing `HeadlessException` into the frame loop.

- **2026-08-08 — the pilot asks which session is live instead of waiting to be told
  (`services/pilot/PilotSession`, `PilotServer`, `NestedSessionLauncher`, `services/launch/BackgroundLauncher`).**
  Launch a game from the ▶ Launch toolbar and the pilot went on streaming — and, with Interact armed,
  *clicking* — the real `:0` desktop while the game ran on `:N`. There is only ever one session per project
  (`BackgroundLauncher.forProject`), but the pilot learned about it by **push**, and the push was registered in
  `NestedSessionLauncher`'s constructor, which the pilot dialog creates lazily on first use of its
  Background-mode box: never opened, never subscribed. `PilotSession` is now a `Supplier` asked on every read
  (`PilotSession.forProject` → `BackgroundLauncher.session()`), so the answer cannot be missed by a consumer
  that didn't exist yet. `setActiveSession`/`clearActiveSession` and the holder's four listener methods are
  gone with the mechanism; `BackgroundLauncher.hostWindowId()` is added (non-mutating twin of
  `revealHostWindow`) for the next step, which stops `:0` input landing in a session's gamescope window.

- **2026-08-08 — the block fill, corrected; and every inline colour style swept into the stylesheet
  (`resources/css/blocks.css`, ~15 files under `blocks/` + `ui/render/`, `suggestions/ProjectAnalyzer`).**
  Three things, one cause between the first two. (1) **The fill itself.** `-fx-border-radius: -bm-block-radius`
  threw `ClassCastException: Double cannot be cast to Size` on every restyle of every block — **a JavaFX
  looked-up value is colour-only**; a size token resolves to a `Double` and the rule is silently dropped, so
  the radius never applied either. That token is gone, with a note where it was, and sizes are literals.
  The border drops 2px-on-colour → **1px accent**, and the fill splits from the accent: `-bm-cat-*` stays
  bright (border, badge, header) while the new `-bm-fill-*` is the surface — the same hue at 22%/16%
  lightness in Dark/Black, which is the "dark theme isn't applied to the blocks" report. `-bm-on-cat-*` →
  `-bm-on-fill-*`, re-measured by `BlockPaletteContrastTest` through one level of token indirection.
  (2) **~25 inline `setStyle` strings** carrying hardcoded whites, blacks and hexes — immune to every token
  and every theme, because **an inline style beats an author stylesheet outright**. They were the black-on-black
  class dropdown, the white-on-white enum field, the `#555` menu glyphs and "the text style is not uniform",
  all at once. They collapse into ~10 shared classes (`.block-chip`, `.block-inset-field`, `.block-selector`,
  `.block-icon-button`, `.block-action-button`, `.block-caption`, `.block-nested-wash`,
  `.block-section-header`, `.ui-caption`, plus per-block cards) over the tokens, with washes as neutral
  `rgba(127,127,127,…)` so one value works on all ten fills in all four themes. **`NoInlineColourStyleTest`**
  fails the build on a colour in a `setStyle` under `blocks/`, `core/` or `ui/render/` — no allowlist, since
  nothing needed one. `ui/render/components/StyleComponents` deleted (unused). (3) **A method call dropped in
  a Print block listed no methods**: `inferExpectedType` resolved the slot to the declared parameter type, so
  `populateMethodList` filtered to that return type. A parameter declared `Object` now reads as
  unconstrained (`isPrintSink`), which covers `BotMaker.print` and every future sink; `System.out.print*`
  keeps a spelling clause because `PrintStream` overloads per type. `PrintSinkInferenceTest`.
- **2026-08-07 — blocks are filled with their category colour, and the on-colour is measured
  (`resources/css/blocks.css`).** Blocks had a neutral surface with a 3px left accent bar, but every label
  colour in the file was a hardcoded white — a palette written for coloured fills that were never applied, so
  in the default (light) theme the editor was white-on-white in places. Each `-bm-cat-*` token now has an
  `-bm-on-cat-*` beside it, per theme, chosen by WCAG contrast rather than by eye: **only the two purples take
  white.** `#3498db` scores 3.15 against white and 5.52 against `#1a1a1a`, so "dark-looking fill ⇒ white text"
  was wrong for eight of the ten categories. Each `.category-*` rule fills, outlines in the on-colour, and
  redefines `-bm-text-on-color` locally — JavaFX looked-up colours cascade, so that one line re-points every
  descendant label and a nested block picks up its own category's on-colour. Dark theme's `-bm-cat-input` was
  nudged `#a569bd` → `#9d5cb5`; it cleared AA against neither on-colour. `-bm-text-dim` is **gone** (it was
  unused, and a dim colour has to move away from the on-colour in a direction that flips with it, which one
  `derive()` can't express) — dim labels are the on-colour plus `-fx-opacity`. Also added `.matches-case-mode`
  and `.switch-case-body`, which `MatchesSwitchBlock` has always tagged and this file had never styled.
  `ui/render/theme/ColorPalette` was **left alone**: it mirrors editor text/accent colours, not the category
  palette, and is live behind `BlockDragAndDropManager` + `StyleBuilder`, so there was nothing duplicated to
  delete. Guarded by `ui/render/BlockPaletteContrastTest` — every fill/on-colour pair in all four themes is
  re-measured on each build, plus a JavaFX `CssParser` syntax check on the file.

- **2026-08-07 — `break` is no longer offered inside a matchswitch (`parser/StatementPlacement`).** The
  placement rule whitelisted `break` in any `SwitchStatement`, but `MatchesSwitchBlock` emits Java 21 **arrow**
  rules, where an unlabelled `break` is a compile error (JLS 14.15) — so the insert menu offered a block that
  broke the build on drop. An arrow switch is now a hard boundary for both jumps: the walk stops there instead
  of falling through to an enclosing loop, since `continue` can't escape a switch rule either. All four
  enforcement points (insert menu, drag-over, palette drop, block move) already delegate here, so the one edit
  covers them. `parser/StatementPlacementTest` pins both forms and both nesting directions.

- **2026-08-07 — a new project's `Popups.run()` ships the loop, not a TODO (`project/ProjectCreator`).** The
  scaffold documented `whileFindAny` in its javadoc and then emitted an empty body, so the popup guard a new
  bot installs had nothing in it and the editor had no block to drop templates into — the user had to author
  the loop from the palette before the feature existed. It now generates `private static final
  ImageTemplateGroup POPUPS = ImageTemplateGroup.of();` and an `ImageFinder.whileFindAny(POPUPS, found -> …)`
  call, which `MatchesGroupScope` already recognises as a constant group, so the editor renders a real
  `while any of […]` block. Runtime behaviour is unchanged until a template is added — but only because the
  SDK now allows an empty group and short-circuits it without a capture; `ImageTemplateGroup.of()` used to
  throw, which would have made every new bot die in `Popups`' class initialiser (see
  `../botmaker-sdk/ROADMAP.md`, same date). `ScaffoldMigration` only creates missing files, so existing
  projects keep the `Popups.java` they have.
  **Ship order matters here:** a generated project pins `MavenService.SDK_FALLBACK_VERSION` (1.0.21 at time
  of writing), and that released SDK still throws on `ImageTemplateGroup.of()` — so this scaffold must not
  reach a packaged Studio before an SDK carrying the empty-group change is released *and*
  `SDK_FALLBACK_VERSION` bumped to it. `release.sh` does both in one run (sdk → studio, bumping the fallback);
  releasing Studio alone would ship a scaffold that dies in `Popups`' class initialiser. Dev runs are
  unaffected — Studio preselects the local `0.0.0-SNAPSHOT` build.
- **2026-08-07 — the pilot's route and its frame become one value (`services/pilot/`).** `TargetCapture.resolve`
  returns `Resolved(PilotRoute route, Capture cap)` — the route the frame was *actually* taken on — and
  `PilotServer.pushFrame` publishes `lastRoute`/`lastBounds` from that single value. They used to be computed
  apart: a session grab that failed fell through to a `:0` desktop capture while the published route still said
  "session", so a tap was clamped to host multi-monitor coordinates and replayed through the `:N` controller.
  That is the reported "Interact teleports the cursor to the other screen", and it was also streaming the user's
  desktop over a link opened to watch a bot. A `Session`/`Emulator` route that cannot grab now resolves to
  `null` (the client keeps its last frame) and never to the desktop.
- **2026-08-07 — session capture follows the `:N` screen, not the attached window.** `captureSession` grabs
  `DesktopSession.captureScreen()` tagged with `screen()`, falling back to the attached window only if a
  backend can't produce a root frame. The window grab returned `null` for the whole Heroic→Firestone swap —
  the "cannot capture the session" report — and under gamescope the client is fullscreen, so it is the same
  pixels anyway. `BackgroundModeBox` preselects gamescope (now the default for every launch kind) and offers a
  **Close &lt;launcher&gt;** button when a launch is refused because the launcher is open on `:0`.
- **2026-08-07 — refused edits are journalled, not printed (`parser/guard/`).** The edit guard's refusal used
  to leave two `System.err` lines (gone when Studio closes, never seen in a packaged build) and an unindexed
  `refused-<ts>.java` dump that couldn't be matched to the problem, the rewrite or the block. Now every refusal
  appends one JSON line to `<cacheDir>/refused-edits/refusals.jsonl` — `RefusedEdit`: the JDT problem (id,
  message, line, offsets, arguments), the rewrite that emitted it (`refusedBy`), the **block** being edited
  (node type, line, snippet, block class + id, `EditKind`), the project/file/template, the Studio build, and a
  `fingerprint` that groups repeats — with both full sources dumped beside it. `RefusalJournal` owns the
  writing, the rotation (4 MB) and the pruning (200 refusals of dumps); nothing in it can throw, since the edit
  is already refused by the time it runs. The block context required threading a nullable `target`/`kind`
  through `CodeEditor.triggerUpdate` — the guard previously saw only two strings. **Help ▸ Open Diagnostics
  Folder** opens the directory.

- **2026-08-07 — the Studio-only closed sets that had no type at all (`VcsFileStatus`, `DiagnosticLevel`,
  `InputKind`, `PilotCommand`).** Six small sets, each spelled as strings in two to four files. VCS file
  status was produced as `"new"/"added"/"modified"/"deleted"` and consumed by a colour `switch` that only
  listed three of them (a staged addition rendered grey through `default`) and a `"new".equals(...)` test
  choosing between *delete the file* and *restore it from the last commit* — now `VcsFileStatus`, whose
  `uncommitted()` documents why the staged bucket deliberately does **not** qualify (it also holds staged
  *modifications*). The Problems panel converted lsp4j's already-typed `DiagnosticSeverity` down to a string,
  switched on it again for a glyph and concatenated it a third time for the CSS modifier — now
  `validation/DiagnosticLevel` with `glyph()`/`styleClass(base)`, so the icon and the colour come off one
  constant. `palette/InputKind` pairs each `BotMaker.readX()` with its `BM-INPUT` marker token, its declared
  type and both labels, replacing two lockstep switches in `ReadInputBlock`, a string switch in
  `UIManager.promptForInput` and three loose fields on `BlockType.ScannerRead` (nothing had stopped an entry
  pairing `readInt` with `String`); the marker itself stays a `String` — it is the SDK's wire protocol — but is
  now one constant both readers share. `PilotCommand.from` makes the phone's `cmd` tokens a total parse.
  Also: `FileTypeDetector` owns `MAIN_METHOD` and `BlockConverter`'s byte-identical `isMainMethod` copy is
  gone, `BotMakerApi.PRINT` is one name instead of two, and `MatchesSwitchBlock` builds `"otherwise"` once.
  Carried forward from earlier phases: Studio's fourth copy of the Linux input-backend set folded onto
  shared's `LinuxInputBackendId` (Studio's was missing `xsendevent` and mis-described `AUTO`), and the three
  literal `"emulator:" + instance` writers now go through `CaptureSourceKind.EMULATOR.spec(...)`. **823 tests,
  was 817.**
- **2026-08-07 — the Java and JDK type names are typed too (`types/PrimitiveKind`, `types/JdkType`).** The
  language's own closed set was spelled as strings: `ResolvedType.primitive("boolean")` at 19 block call
  sites, two `Set<String>` constants of the primitive names that nothing tied to the parse, and a
  `StatementFactory` switch mapping each name to its JDT `PrimitiveType.Code` that *threw* on anything
  unlisted — including `void`, which it omitted. `PrimitiveKind` owns the keyword, `isNumeric()`, the JDT
  `code()` and `boxed()`; `ResolvedType.Primitive` now holds the kind, so the variant can't exist for a
  non-primitive, and `ResolvedType.BOOLEAN/INT/DOUBLE/VOID` serve the call sites. `JdkType` holds a real
  `Class<?>` per JDK type the editor names — the `SdkType` trick — collapsing four re-listings
  (`ProjectAnalyzer`'s `java.util` fallback, which built its answer as `"java.util." + simpleName`;
  `StatementFactory`'s `ITERABLE_TYPES`/`SWITCHABLE_TYPES`; `DefaultValueHelper`'s wrappers; `ResolvedType`'s
  `NUMERIC_WRAPPERS`). `isString`/`isBoolean`/`isVoid`/`isNumeric` are now single defaults on the interface
  instead of three parallel per-variant copies, and `is(JdkType)` trusts a bare simple name only for
  `java.lang`, the one package the language auto-imports. 817 tests, was 811.

- **2026-08-07 — the editor reaches the SDK surface through `palette/SdkType`, not around it.** The enum exists
  so facade and value-type names are compiler-checked, yet ~40 sites re-spelled them: `"ImageTemplate"` alone
  was written 13× across 8 files, and `InitializerFactory`, `ClickBlock`, `CaptureExpr`, `PrecisionArgPicker`,
  `LaunchTargetArgPicker` and `ScaffoldMigration` hand-wrote *fully-qualified* names the enum computes from a
  class literal. `BlockType.LibraryCall`/`LambdaCall` now hold an `SdkType` rather than a `String className` —
  every one of them was already a facade call, and both synthetic builders (`StatementMenu.sdkCall`, the
  overlay palette) started from an `SdkType` and threw it away to pass the name back as a string. New
  `parser/helpers/SdkNodes` builds the JDT nodes (`type`/`name`/`qualifiedName`/`intCtor`) and answers
  `isCallOn`/`isInstantiationOf`, so no rewrite spells an SDK name; `ResolvedType.of(SdkType)` replaces
  `ResolvedType.named("ImageTemplate")` and carries the qualified name the simple-name spelling never could;
  `ResolvedType.is(SdkType)` is the single owner of a "is this slot type T?" test four pickers each wrote out.
  A renamed SDK class is now a Studio build failure instead of generated source that no longer compiles.
  811 tests, was 807.
- **2026-08-06 — `palette/VisionLoop`: the nine `ImageFinder` lambda helpers are a set, not six lists.**
  `LambdaCallBlock`'s private `Variant` record + `VARIANTS` list owned the authoritative table while
  `MatchesGroupScope` kept a second, hand-written `Set.of("ifFindAny","whileFindAny","ifFindAll","whileFindAll")`
  — not an independent fact but exactly "the group forms that pass a parameter" — and `BlockCatalog` seeded the
  palette entry with `"ifFind"`/`"match"` literals. `VisionLoop` (public enum, `methodName()`/`group()`/
  `defaultParamName()`/`hasParam()`/`handsOverMatches()`/`returnsBoolean()`, total `fromMethodName` → `Optional`)
  is now the single owner: the dropdown lists `values()`, `MatchesGroupScope` derives its set with a filter, and
  the `→ boolean` badge asks the enum instead of `method.startsWith("if")`.
- **2026-08-06 — `palette/MatchesCheck`: any/all stops travelling as a boolean.** `hasAll`/`hasAny` was
  re-derived by ternary at four write sites and `"all of"`/`"any of"` at three more, with `boolean all` threaded
  through `Guard`, three `MatchesSwitchHandler` signatures and `CodeEditor.setMatchesCaseMode` — so
  `addCase(cu, code, stmt, false, paths)` read as a flag rather than as the branch it adds. New `VisionLoopTest`
  (6 cases) pins both sets, including the literal `MatchesGroupScope` used to carry. 807 tests, was 801.
- **2026-08-06 — `project/StudioContext`: the shell stops re-listing the project.** `UIManager` took **11**
  constructor parameters and `StudioActions` **13**, largely the same run of project services listed again at
  each layer — and four of `UIManager`'s (`projectAnalyzer`, `libraryService`, `activityService`,
  `codeExecutionService`) never became fields at all, existing only to be forwarded one level down.
  `StudioContext` is the immutable read-only view of what `BotProject` composes, built by
  `BotProject.context()`: `UIManager(ctx, primaryStage)` (11 → 2), `StudioActions` (13 → 7),
  `FileExplorerManager(ctx)` (5 → 1). It deliberately excludes the `Stage` — a window is not a project
  service, and leaving it out keeps the record and its package free of JavaFX. Two things fell out: `UIManager`
  was building a **second** `ProjectSettingsService` over the same `(config, state, eventBus)` as the one
  `BotProject` already owned, and now uses the project's; and `ProjectRecoveryAction` — six fields for an
  object built once and only ever `run()` — became `static recover(ctx, refreshTree)`, wired as a method
  reference. `WorkspaceLayoutStore` was left alone on purpose: its single field in `UIManager` is the
  save-once latch `dispose()` nulls, so making it static would trade one field for two plus a boolean.

- **2026-08-06 — `parser/EditContext`: the write path gets the context the read path always had.** Every
  factory and handler under `parser.factories`/`parser.handlers` needs the same five things to build a node —
  `AST`, `CompilationUnit`, `ASTRewrite`, `ProjectAnalyzer`, `ProjectState` — and threaded them as five
  separate parameters. That is why the widest signature in the package ran to **eight**
  (`MethodHandler.updateMethodInvocation`) and why teaching `new T()` to name a real constructor meant editing
  seven call chains. `EditContext` is the write-path twin of `ParseContext`, which has done exactly this job
  for the read path since the converter was written: same package, same "immutable, threaded through, the
  layer holds no per-edit state" rationale. `of(cu, analyzer, state)` creates the rewriter, `applyTo(code)`
  ends the edit, and `addImport(SdkType)` / `addImport(ResolvedType)` / `addImportForSimpleName` /
  `addImportForType` collapse the four-argument import calls to one.

  Threaded through all six handlers, all three factories, `NodeCreator` and the `CodeEditor` entry points
  (which build one via a private `ctx(cu)` helper). **Methods taking ≥6 parameters in `parser/`: 21 → 8, and
  nothing is above 6 any more.** Deliberately *not* in the record: the `ASTNode context` drop site, which
  varies as the factories recurse and so is a real argument, and the original source text, which only the
  outermost entry point holds. `InitializerFactory` takes a context but ignores its rewriter — it has no
  imports to add, which is precisely why it seeds only literal-valued constructor parameters, and
  destructuring there keeps that limit visible. Three dead `NodeCreator` delegating overloads and an unused
  `rewriter` parameter on `createRecursiveListInitializer` fell out and were removed.

  One behaviour change, in the right direction: call sites that used to pass `state = null` to
  `addImportForSimpleName` (because state simply wasn't threaded that deep) now pass the real one, so
  tier 1 — the project's own sources — actually runs for them.

- **2026-08-06 — Imports resolve instead of guessing (`ImportManager`).** `resolveQualifiedName` is now three
  ordered tiers — **project sources → `SdkType` → a JDK package probe** — replacing a hand-written map of 14
  simple-name→FQN entries. The probe walks a fixed ordered package list (`java.util`, `java.util.function`,
  `java.util.stream`, `java.io`, `java.nio.file`, `java.time`, `java.math`, `java.util.regex`, `java.awt`,
  `java.awt.image`) with `Class.forName(…, false, getPlatformClassLoader())`, first hit wins, memoized
  **including misses** (an uncached miss costs one failed lookup per package, on every keystroke-driven edit).
  Order is the disambiguation — `java.util` before `java.awt` settles `List` — and the names that made this
  tier dangerous never reach it, because the SDK tier runs first: the old map had to *omit* `Point` and carry
  a comment explaining why. Net effect it deduces far more than the fourteen someone thought to list
  (`Optional`, `Instant`, `Files`, `Pattern`, `BigDecimal`, `Collectors`…). Loading with `initialize = false`
  and the **platform** loader is deliberate: the probe must not see Studio's own classpath, and must not run a
  static initializer because the user typed a type name.

  Alongside it, a typed `ImportManager.addImport(cu, rewriter, SdkType)`. Eleven call sites across
  `CodeEditor`, `ListHandler`, `LambdaCallHandler` and `StatementFactory` were passing a **string literal**
  they knew at compile time to `addImportForSimpleName`, which then *searched* the analyzer index for it — and
  emitted nothing when the index was cold. Those are now identity lookups that cannot fail.
  `addImportForSimpleName` stays for the cases where a name genuinely is all the caller has (the paste path,
  enum-constant scopes, an unbound `ResolvedType`'s leaf). Three `ProjectAnalyzer` parameters fell dead as a
  result and were removed (`LambdaCallHandler.switchVariant`/`seedIfReady`/`seededBody`,
  `ListHandler.addImageTemplateElement`). Four new cases in `PasteImportsTest` pin the tier order: `Point` to
  the SDK and not `java.awt`, sub-packaged `ImageFinder`/`ImageTemplate`, four JDK types the old map never
  listed, and `List` to `java.util`.

- **2026-08-06 — The SDK surface is a typed, compiler-checked set (`palette/SdkType`).** Studio mirrored the
  SDK's facades as a hand-maintained `List<String>` in `palette/SdkApi`, with a *second* hand-maintained glyph
  map keyed by the same strings in `MenuIcons`, and nothing verified either against the real SDK. Studio now
  takes a narrow **compile-scope dependency on `botmaker-sdk`** (`${botmaker.sdk.version}`, same contract as
  shared/session; `dependency:tree` confirms it adds no new transitives) and `SdkApi` is replaced by `SdkType`
  — an enum over all 53 classes under `com.botmaker.sdk.api`, each constant holding a real `Class<?>`. That
  buys three things: drift becomes a **compile error** rather than a silently broken menu; `qualifiedName()`
  is correct for free, which matters because the facades live in sub-packages (`api.vision.ImageFinder`,
  `api.capture.Window`) so no import path can derive an FQN from a simple name; and `ImportManager` gains a
  way to say "this name belongs to the SDK" — the prerequisite for deducing the JDK fallback, since `Point`,
  `Window`, `Desktop` and `Text` all collide with `java.awt`. `Role.FACADE`/`FACADE_HIDDEN`/`VALUE` replaces
  the old `MENU_HIDDEN` set, declaration order is still menu order, and the icons moved onto the constants.
  **Type identity only** — methods still come from `ProjectAnalyzer`/ClassGraph and Javadoc from
  `SdkDocsService`, both over the SDK version *the bot* pins, which may be older than Studio's; reflecting
  Studio's copy would offer methods a bot can't compile, and bytecode carries no Javadoc at all.
  `byName()` is total (the boundary with user source), and a duplicate simple name throws at class-init
  rather than resolving to whichever constant was declared last.

- **2026-08-06 — A `new T()` placeholder names a constructor that exists.** Seeded arguments emitted a bare
  `new T()` regardless of what `T` declares, so any type without a no-arg constructor produced uncompilable
  source — the SDK's `ImageTemplate` has only `(String)` and `(String, double)`, and `new ImageTemplate()`
  reached two user projects on disk. `InitializerFactory.newInstance` now asks
  `ProjectAnalyzer.getConstructors`: zero-arg wins, else the fewest-parameter constructor, seeded with
  literals. `ProjectAnalyzer` is threaded into the deepest `createDefaultInitializer` overload and passed from
  the seven write paths that already hold one (`NodeCreator`, `MethodHandler` ×4, `InstantiationHandler` ×2,
  `StatementFactory`); `ExpressionFactory.createInstantiation` shares the rule, since picking `new T()` from
  the expression menu produced the identical bad text. **Only literal-valued parameters are filled** — this
  factory has the CU but not the `ASTRewrite`, so it can add no imports, and filling `new Rect(Point, Point)`
  would trade "no such constructor" for "cannot find symbol Point"; anything else falls back to the old bare
  `new T()`. That restriction is also why there is no recursion: an argument is never itself a `new`. The five
  hand-written exemptions (`CaptureSource`, `Color`, `Precision`, `Duration`, `LocalTime`/`DayOfWeek`/`Month`)
  were **kept, not retired** — they are the behaviour when there is no analyzer (the short overloads used by
  `CodeEditor` and `TypeHandler`), and `Color` specifically would regress to `new Color(0)`, which compiles and
  then lies to `ColorArgPicker`'s RGB read-back. `parser/ConstructorPlaceholderTest` (5) pins all of it.

- **2026-08-06 — Studio formats what it writes.** Every rewrite was an `ASTRewrite` applied to the previous
  text, and `ASTRewrite` only lays out what it *inserts* — nothing ever re-formatted the file, so a generated
  bot degraded edit by edit until one user's activity had its whole lambda, `switch`, both guarded labels and
  the first `->{}` on a single line. New `parser/helpers/SourceFormatter` (JDT `ToolFactory`, settings from
  `.editorconfig`: 4 spaces, 120 columns, LF; compliance reused from `SourceParser.latestLevelOptions()` rather
  than a second copy that could silently sit at JDT's 1.3 default and mangle a `switch` rule). Applied in
  exactly one place — `CodeEditor.triggerUpdate`, before the edit guard — so no write path can skip it and the
  guard still has the last word. Comments are not reflowed (re-wrapping a user's prose is an opinion about
  their writing), and source that doesn't parse is returned untouched: JDT would otherwise format a *recovered*
  tree, which is a way to lose text from a file that was going to be refused and dumped intact. Expect one
  large diff the first time an existing project is saved. `SourceFormatterTest` covers the layout, the
  round-trip (same AST), idempotence, and that the write path actually calls it.

- **2026-08-06 — The refusal log names the rewrite, not itself.** `CodeEditor.refusedBy` excluded the guard's
  other frames (`wouldBreak`, `triggerUpdate`, `edit`) but not its own, so the first matching frame was always
  `refusedBy` and every refusal in the wild logged `(refusedBy)`. The one thing the line existed to say was the
  one thing it never said, and nothing inside the feature could notice. Now it names the calling method and its
  line, and `dumpRefused` writes the refused source to `<cache>/refused-edits/` — the emitted text is what
  makes a rewrite diagnosable when the file can't be reproduced from a retyped copy, which is exactly the case
  that prompted this. `EditGuardTest.theRefusalNamesTheRewriteThatCausedIt` asserts on the log.
  **Not fixed, and open:** a report of two branches refusing inserts in a real activity file does *not*
  reproduce — the file is now a byte-for-byte test resource (`src/test/resources/parser/packed-switch.java.txt`)
  and all three of its branches accept a block on both the current build and the pre-fix one. The next
  occurrence carries the rewrite's name and its output; that is the input to the fix.

- **2026-08-06 — A block dropped into an arrow-form `switch` branch lands inside it.** Adding anything to a
  combination block's case wrote source that didn't compile; on disk it read
  `case Matches m when … -> ImageClicker.click(…);` followed by the branch's own `{}` on the next line — the
  statement went in *front of* the body, as a bare block among arrow rules. Cause: `parseSwitch` only knew the
  colon form. It backed every case's `BodyBlock` with the `SwitchCase` label, which is right for `case X:`
  (those statements really are siblings of the label, and `insertIntoList` offsets from it) and wrong for
  `case X -> { … }`, whose body is one `Block`. `SwitchNormalizer` was the only place in the module that knew
  arrow rules existed. Now `BlockConverter.labeledRuleBody` backs an arrow rule's body with its `Block` — the
  rule `parseMatchesSwitch` already followed, generalised to every arrow switch — `SwitchNormalizer` gained a
  second pass bracing a bare `case X -> foo();` so every branch has somewhere to drop into (both passes now
  run from one `CodeEditor.normalizeSwitches`), and `insertIntoList`'s colon-form arithmetic throws on a
  labeled rule rather than corrupting a switch again. The Phase 5 edit guard was already refusing the bad
  output, which is why the symptom read as "I can't add anything" instead of a branch silently emptying.
  (`parser/BlockConverter`, `parser/handlers/SwitchNormalizer`, `parser/CodeEditor`,
  `services/CodeEditorService`, new `SwitchCaseInsertTest` covering both label forms.)

- **2026-08-06 — The edit guard: source that doesn't parse is never published.** The disappearing-method bug.
  A rewrite that *throws* was already handled — `AstRewriteHelper.applyRewrite` catches and returns the
  original code — but one that *succeeds* and emits broken Java had nothing checking it: it was published,
  `refreshUI` re-parsed it, JDT recovered a mangled tree, and the method rendered **empty**. Adding a block
  could erase the visible contents of a method, with Ctrl-Z the only way back. `CodeEditor.triggerUpdate` —
  the single point every write path funnels through, `edit(...)` and the four direct publishers alike — now
  refuses to publish source that doesn't parse: no `CodeUpdatedEvent`, so the undo stack is untouched and the
  canvas never renders a recovered tree, plus a user-facing status line and a `System.err` line naming the
  first `IProblem` and the `CodeEditor` method that produced it (via `StackWalker` — that name is the handle
  on *which* rewrite is broken, the refusal being only the symptom). `addStatement`'s `BlockAddedEvent` is
  dropped with the edit, since announcing a block that was never published scrolls the canvas to nothing.
  **The already-broken clause is the half that makes it liveable:** only a *newly introduced* error is
  refused, or a user mid-way through fixing a syntax error would have every edit rejected — including the one
  that fixes it. Syntax errors only, via `SourceParser` with bindings unresolved: a block naming a type the
  project doesn't have yet is a normal intermediate state, a broken brace is not. Costs one parse on the edit
  path and only on the path that already parses — the new code is checked first, so a clean edit never touches
  the old code, and `refreshUI` parses the same file anyway. (`parser/CodeEditor`,
  `parser/helpers/SourceParser` gains `firstSyntaxError`, `EditorFixture` now captures status messages; new
  `EditGuardTest`.) Next, and deliberately not in this change: the `parser/handlers/*` rewrites that emit
  broken source are now harmless and logged by name — fixing them individually is the follow-up.

- **2026-08-06 — "Check Image Combinations": off the menu, actually auto-created, and stripped to its
  content.** Four changes to the block that was in the wrong places and missing from the right one.
  **Off the statement menu** (`MATCHES_SWITCH` out of `BlockCatalog.ALL`, the exclusion `FIND_IMAGE_ACTIONS`
  already carries): offered there it can be dropped anywhere, and everywhere outside a group find has no
  `Matches` to switch over. **The auto-creation now happens**, which it previously never did — two gaps, not
  one. `LambdaCallHandler.seededBody` was reachable only from the method dropdown, and even there it declined,
  because a freshly dropped find block's image slot is a `null` literal and a guard needs a literal template.
  So *both* orders produced the empty body users saw. A new `seedIfReady` closes the second: the group picker
  (`CodeEditor.setImageTemplateGroup`) calls it after writing the group, passing the path it just wrote. It is
  idempotent — "the body is empty" is one of its conditions — so deleting the switch and re-picking does not
  hand it back, and it seeds only the four forms that hand over a `Matches` (`ifFindAny`/`ifFindAll`/
  `whileFindAny`/`whileFindAll`); `ifFind`/`whileFind` pass a single `MatchResult` with no combination to
  test, and `untilFind…` pass nothing at all. The variant set is read from `MatchesGroupScope`, which already
  owned it for the chip narrowing. **The lambda variable is no longer drawn** — the `found →` chip and its
  arrow are gone from `LambdaCallBlock`, along with the two CSS rules behind them (one of which, scoped to the
  whole SDK block, was narrowing every variable declared inside the lambda body to 72px). The parameter still
  exists in the source and in the body's expression menu; what it is now reads in words on the method
  dropdown. **The block shows only its branches**: the invisible `check … for` header, the `subject` field
  that fed it, and the left indent under it are gone, with `+ Add branch` and the delete control paired in one
  footer row. (`palette/BlockCatalog`, `parser/handlers/LambdaCallHandler`, `parser/CodeEditor`,
  `blocks/flow/MatchesGroupScope`, `blocks/flow/MatchesSwitchBlock`, `blocks/vision/LambdaCallBlock`,
  `parser/BlockConverter`, `css/blocks.css`; new `GroupSlotSeedTest`.)

- **2026-08-06 — The toolbar stops painting over the menu bar.** The min-height clamps in `UIManager` were
  already in place, so the crop wasn't the shrink pass — it was the *preferred* height they resolve through.
  A `FlowPane` asked for its preferred height without a width answers against its **wrap length** (400px by
  default), not the width `BorderPane` will really hand it, so the bar reserved the height of a 400px-wide
  capture group while laying out a different one; the rows that didn't fit painted upward, and JavaFX doesn't
  clip a `Region`. The centre group's `prefWrapLength` is now bound to the width actually left between the
  two edge clusters — the same fix the run cluster already had, for a sharper reason. Alongside: the bar's
  padding moved out of `setPadding` into `.main-toolbar` (an inline set marks the property author-set, which
  is what kept CSS from owning it) and grew vertically to `8 6 8 6`; and `.toolbar-resolution` — until now a
  style class with **no rule behind it** — gives the resolution readout the vertical padding its button
  siblings have, with `minHeight = USE_PREF_SIZE` so a tight bar can't clip its descenders.
  (`ui/app/UIManager`, `ui/app/ToolbarManager`, `css/blocks.css`.)

- **2026-08-06 — Launch Target dialog recaps the targets you picked before.** The dialog persisted exactly one
  value (`launch.target`, per project) and nothing else, so re-picking last week's game meant walking the
  Steam/Epic/Heroic library picker again. `ProjectPreferences` now carries a 10-entry launch-target MRU
  (`recentLaunchTargets`, the same remove-then-`addFirst` shape as `recentProjects`) — **global**, not
  per-project, since the point is re-selecting a target from the *next* project. `LaunchTargetDialog` records
  through its single `apply(...)` funnel so no kind can forget to, and shows a "Recently used" list built from
  `LaunchSpec.describe` (shared already owns the label — no second id→name switch). Re-selecting an
  `emu-app:` re-derives `capture.source` from `LaunchSpec.emulatorInstance()`, which `pickEmulatorApp` sets
  alongside the target and which a plain re-apply would have dropped. The current target is skipped in the
  list, the section hides itself when empty, and the choices now scroll so a full MRU can't push the action
  bar off a fixed-size dialog. (`project/ProjectPreferences`, `ui/app/LaunchTargetDialog`.)

- **2026-08-06 — The shell: `UIManager` split 1,740 → 501 lines, and the resources it was leaking are now
  released.** Five phases. The split: a new **`ui/app/pilot/`** package (`RemotePilotUi` — the bring-up state
  machine and the only mutable state, `RemotePilotDialog`, `FunnelSetupWizard`, `BackgroundModeBox`), plus
  `DiagnosticsPanel`, `EditorCanvas`, `IdentityCluster`, `StudioActions` (every menu/toolbar action in one
  wiring table, and the GitHub services that back the sharing ones), `ProjectRecoveryAction`, `BottomTab` and
  `WorkspaceLayoutStore` in `ui/app/`, and `project/ProjectOpenMigrations` — which is not a UI concern and
  only lived in the shell because the shell ran at the right moment (still before the file explorer is built:
  a migration can delete a file the tree would otherwise go on listing). None of the collaborators holds a
  back-reference. **The defect that made this more than a tidy-up:** a new `UIManager` is built on every
  project open *and every reload*, and nothing ever released the pilot's bound port or the nested
  Xephyr/gamescope display, so a VCS rollback left an orphaned server streaming a project that was gone with
  the game still running inside a live display — and two `BlockTheme` listeners (a **static** list with no
  callers of `removeThemeChangeListener` anywhere) pinning the dead scene graph. `RemotePilotUi` is now
  `AutoCloseable` and `UIManager.dispose()` tears down both, called from `BotMakerStudio` on open, on the
  switch back to the selector, and on shutdown. Also fixed: a double-click on Remote Pilot started two
  bring-ups (in-flight guard); `qrCell` returned `null` so an un-encodable URL gave a pairing dialog with no
  QR and no explanation; three "Copied ✓" buttons never reverted; the token reset rebuilt its URL by regex
  that only worked while `token=` was last; the canvas jumped to the top on every edit (`vvalue` is now
  restored across the re-render); `selectBottomTab(0|1)` and a computed `vcsTabIndex` became the `BottomTab`
  closed set; the console copied its whole buffer per line to measure it (`getLength()`); and
  `reader-to-editor` is a daemon thread (SU12). **Theming:** the toolbar's border, the Errors filter bar, the
  diagnostic rows and the status line were inline hex literals — the toolbar's *overrode* `blocks.css`'s
  token-driven rule in every theme, and the rest stayed light grey in Dark/Black/High Contrast. All four are
  now classes over new `-bm-divider` / `-bm-band` / `-bm-severity-*` tokens defined per theme. **Feature:**
  the explorer/canvas divider, the canvas/bottom divider and the open bottom tab now persist per project
  (`StudioProjectSettings.WorkspaceLayout`), written once at teardown rather than on every drag — a divider
  moves continuously while dragged, and `settings.json` is in the user's versioned project. +26 tests
  (`RemotePilotFunnelTest`, `DiagnosticsPanelFilterTest`, `ProjectRecoveryTest`, `WorkspaceLayoutTest`,
  `BottomTabTest`, and `UIManagerSceneTest`'s dispose assertions); 768 green. Closes **SU7** in
  `docs/refactor/14-studio-ui.md`, wider than that item scoped.

- **2026-08-06 — Overlay editor: split into a coordinator + collaborators, and the four features it was
  missing.** `ProgramShapeOverlay` had reached 1,483 lines carrying every concern at once. It is now a
  coordinator (877 lines, ~37% of them rationale comments) that owns the stage, the subscriptions and the
  FX-thread-confined pending state, beside nine single-purpose collaborators in `ui/app/overlay/`:
  `BlockTree` (the pure, headless row model), `OverlayTreeView`, `OverlayTargetPicker`, `OverlayPalette`,
  `ArgumentConfigPopover`, `OverlayRecorder`, `RecordedBatchInserter`, `OverlayHeader`, `OverlayHotkey`,
  `OverlayStyles`. None holds a back-reference to the coordinator; each takes callbacks. This **supersedes**
  the "do not split" verdict at `docs/refactor/14-studio-ui.md` §10 (written at 894 lines) — the
  thread-confinement argument behind it survived the split rather than being traded for a lock. Features
  added in the same pass: **move up/down** (`▲▼` on the focused row, `Alt+↑/↓`) through
  `CodeEditor.moveStatement`, so the drag-and-drop path's read-only and pinned-return guards apply rather
  than a second set of rules; **collapse/expand** of control-flow bodies, keyed by `BlockTree.Position` so a
  fold survives the re-parse every edit causes; **persisted HUD state** (position + Show-lines, new
  `StudioProjectSettings.OverlayState`, position discarded on restore if the monitor it named is gone); and a
  **global `F9` record hotkey** plus the `⏺ Record` toolbar button that revives the overlay's long-dead
  `startRecording` flag. The hotkey runs on its own XRecord connection, and `RecordingSession.ignoreKeysym`
  keeps the key that *stops* a recording from becoming that recording's last action. Also: `CodeEditor`'s
  refusals (`StatusMessageEvent`) now reach the HUD's status line — the main editor's status bar isn't on
  screen while the overlay is, so a blocked edit simply looked like nothing happened. +9 tests
  (`BlockTreeFlattenTest` folding, `RecordedBatchInserterTest`); 737 green.

- **2026-08-05 — A `switch` over `Matches`: branch on image combinations, with the chips narrowed to the
  group.** Multi-template conditions were expressible but not organisable, and the chip menus offered every
  template in the project — including ones the enclosing `whileFindAny` group can never produce, so a branch
  could be written that was dead by construction. New `blocks/flow/MatchesSwitchBlock` renders a real Java 21
  guarded switch (`case Matches m when m.hasAny(new ImageTemplate("…"), …) -> { … }`) as one row per branch:
  an any/all toggle plus the existing `ImageTemplateGroupPicker` chip row. New
  `parser/handlers/MatchesSwitchHandler` owns the four writes; `BlockConverter` claims the guarded arrow form
  ahead of the ordinary `SwitchStatement` branch, purely on label *shape* — Studio doesn't compile against the
  SDK, so `Matches` routinely has no binding and a type-based test would never fire. New
  `blocks/flow/MatchesGroupScope` walks out to the enclosing find call (inline group or constant) for the
  narrowing, returning `null` — unrestricted — when it can't resolve one, because an empty menu is worse than
  a wide one. `ImageTemplateGroupPicker.chipRow` gained a `Restrictions(allowed, minimum)` overload (the
  3-arg form delegates, so the image-varargs caller is unchanged); "Capture new…" is hidden on a narrowed row
  since a fresh image is by definition not in the group. **Two rules are enforced where they're edited, not
  validated after, because both are compile errors:** the `default` rule is undeletable chrome (a pattern
  switch must be exhaustive) and a branch can't drop to zero templates (an unguarded `case Matches m` is
  unconditional and collides with `default`). Both verified with `javac --release 21`. Deliberately **no SDK
  change** — the planned `has(String)`/`hasAny(String…)` overloads would have been keyed on `templateId` (the
  basename, `"mail"`) while every caller passes a path, so they'd have silently matched nothing, and they'd
  have lost the `new ImageTemplate("…")` chip rendering that already works end to end. +19 tests
  (`MatchesSwitchHandlerTest`, `MatchesSwitchBlockTest`).

- **2026-08-06 — The `Matches` switch takes its subject from the lambda, and a group form is born holding
  one.** Two follow-ups to the above, both found by using it. (1) Dropping the block into a `whileFindAny`
  body inserted `switch (null)`: the subject came from asking `ProjectAnalyzer` for a visible variable of type
  `Matches`, but a lambda parameter's type is inferred and Studio doesn't compile against the SDK, so that
  lookup resolves to nothing in precisely the place the answer is certain — the parameter of a
  `whileFindAny`-shaped call *is* the `Matches`, by the signature. New `MatchesGroupScope.matchesVariable`
  reads it off the enclosing call using the same walk that does the chip narrowing, falling back to the type
  lookup only when there is no such call. Same reasoning as `isMatchesSwitch` testing label shape rather than
  a binding. (2) `LambdaCallHandler.switchVariant` now **seeds** a group form's body with the switch, one
  branch on the group's first template plus `otherwise` — so picking `whileFindAny` from the method dropdown
  lands on the question that variant exists to ask instead of an empty block. Guarded three ways: only a form
  that actually hands over a `Matches` (`untilFind…` is a `Runnable`), only an **empty** body (a non-empty one
  is never displaced), and only when a template is readable. `MatchesGroupScope.groupPaths` became the single
  owner of "what images can this call produce?" — inline group, constant, or the single template being
  converted — because the seed and the chip narrowing ask the same question and two readers would drift.
  +6 tests.

- **2026-08-05 — The pilot can stream and touch an emulator, so BotPilot works against Waydroid.**
  BotPilot was unusable against an emulator, and it had never been wired for one: `TargetCapture` handled
  window/screen/desktop targets and simply fell through for an `EmulatorTarget`, so the phone was shown the
  user's *real desktop* while the bot looked at Android; `PilotInputService` routed on one nullable
  `DesktopSession`, so an Interact tap at emulator coordinates was synthesized onto `:0` — wrong pixel, wrong
  screen, and it hijacked the cursor to get there. That question is now a closed set: new sealed `PilotRoute`
  (`Desktop` | `Session` | `Emulator`) resolved by new `PilotRoutes` in one documented order — a live nested
  session, then `capture.source = emulator:<name>` (what the Launch Target dialog already writes for every
  `emu-app:` target, and what the running bot reads), then a default `EmulatorTarget`, else `:0`. An
  unreachable instance degrades to the desktop rather than to a blank stream. New
  `emulator/EmulatorSurface` + `AdbEmulatorSurface` holds **one** ADB connection (reconnecting on failure)
  for `screencap` frames and `input tap`/`swipe` gestures. Android has no pointer, so a drag is one swipe
  emitted on `UP` rather than a stream of moves, and the route reports `backgroundInput = true` — ADB has no
  host cursor to touch — which correctly removes the pilot's "moves your real cursor" warning. `apply` now
  takes the route that produced the frame (recorded beside `lastBounds`) so a gesture can't land on a route
  that changed under it, and the frame loop moved to `scheduleWithFixedDelay`: a full-frame PNG slower than
  the period was queueing back-to-back, which is a backlog, not a frame rate. The Background-mode box stops
  advising an `emu-app:` target into a session it will refuse and reads green "already isolated" (amber, from
  an off-thread probe, when the instance isn't up). Also `ProjectCreator.readCaptureSource` — with the four
  copies of the properties-load boilerplate collapsed onto one `readKey` — and `TargetThumbnail` onto shared's
  `EmulatorInstances.byName` + `EmulatorProbe`, deleting a fourth copy of the ADB TCP probe.
  `PilotRoutesTest`, `PilotInputServiceTest`, `TargetCaptureTest`, `LaunchTargetRoundTripTest`.

- **2026-08-05 — The emulator picker shows apps by name, and asks Waydroid rather than ADB.**
  `EmulatorProbe.installedAppsDetailed` returns `(package, label)` pairs and sources them from
  `WaydroidApps.list()` for a Waydroid instance — the host CLI answers when ADB is refused by the in-guest
  trust prompt, and it knows the app is called "Firestone" rather than `com.HolydayStudios.Firestone`. The
  picker labels each row with that name and keeps the package in its tooltip (it is what the launch target
  stores, and what the icon loader now identifies rows by). `EmulatorAppCache` lines grow an optional
  tab-separated label; the older package-only files still read, because a cache isn't worth a migration but
  silently forgetting everything on upgrade would defeat it.

- **2026-08-05 — The emulator's app list survives a restart, and a start waits for Android, not for a port.**
  `EmulatorPickerDialog`'s `APP_CACHE`/`ICON_CACHE` were `static` maps, so the promise that a stopped instance
  still shows its last-known apps held only within one run — after restarting Studio a stopped Waydroid went
  back to "start it to list apps". New `emulator/EmulatorAppCache` writes both through to
  `BotMakerDirs.getCacheDir()/emulators` (one text file per instance, icons as PNGs); the maps stay as the
  in-process layer. Remembered icon *failures* stay in memory — a permanent negative on disk would outlive
  its reason. Separately the picker now polls shared's `EmulatorReadiness.isReady` (port **and**
  `sys.boot_completed`) when starting, and takes its ceiling from `PlatformId.bootTimeout()` instead of two
  local constants — querying `pm list packages` at port-open is why a freshly started row could come back
  empty. `QuickLaunch` narrates the emulator boot through the launcher's new progress consumer and ends on
  what actually happened. `EmulatorAppCacheTest`.

- **2026-08-05 — An `emu-app:` launch target can now actually be launched.** "▶ Launch now" never started an
  app inside an emulator: `session.isolated` defaults to **on**, so every launch went through
  `BackgroundLauncher` → `LaunchIsolation.check`, which refuses `emu-app:` (empty child ladder — there is no
  process of ours to hand a private `DISPLAY` to). The refusal was the button's only outcome. `QuickLaunch`
  now routes on shared's new `LaunchKind.runsOffDesktop()` via the testable
  `usesBackgroundSession(spec, isolated)`: an emulator app takes the direct `Launcher.start` path whatever
  the toggle says, and says why on success. The Launch Target dialog greys "Run in background" for such a
  target (persisted key untouched), and the pilot's background box refuses it up front with shared's wording
  instead of a generic isolation failure. `QuickLaunchRoutingTest`.

- **2026-08-05 — Phase 6: a variable's *members* reach an expression slot, and any varargs call can grow.**
  The Variables menu listed only variables assignable to the slot, so a `Matches found` never appeared under
  a `boolean` condition and the combination logic `Matches` exists for was reachable only through the
  type-unfiltered "Call Function" escape hatch. `ExpressionMenu.memberSubmenus` now fans a non-fitting
  variable out into its type-compatible members via the same `MenuBuilders.buildScopeMenu` the SDK facades
  use — `found ▸ has(…)`, `hasAny(…)`, `isEmpty()` — and the search view qualifies those leaves by receiver
  (`found.hasAny`). Separately, `MethodInvocationBlock` renders a `✕` on each varargs argument and a trailing
  `＋`, backed by the new `CodeEditor.addVarargsArgument` / `deleteArgumentFromMethodInvocation`:
  `MethodSignature` always modelled the trailing parameter correctly, but `addArgument` had no UI caller, so
  a call was frozen at the arguments it was created with. Image varargs keep the chip row instead.
  `VarargsArgumentEditTest`.

- **2026-08-05 — An `ImageTemplate...` varargs slot gets the group picker's chip row, so a call can hold more
  than one template.** A varargs image slot rendered one single-image picker per argument *that already
  existed*, so `found.hasAny(coin)` had no affordance that could ever produce `found.hasAny(coin, gem)` — the
  picker was never the problem, there was simply no way to add a slot. `ImageTemplateGroupPicker.chipRow` is
  now the shared control (paths in, whole new list out); the group expression and the varargs tail are just
  two writers of it, the second being the new `CodeEditor.setImageTemplateArgs`.
  `MethodInvocationBlock.imageVarargsStart` claims the tail **only** when every argument in it is a plain
  `new ImageTemplate("…")` — a variable or call in a varargs slot has no path to show and would be
  overwritten by the next chip edit, so those calls keep the per-argument pickers. An empty tail
  (`hasAny()`) still renders the row, or the slot would be unfillable. `ImageTemplateVarargsTest`.

- **2026-08-04 — The vision loop's variant switch actually rewrites, and its facade is re-pointable.**
  Improvements round 2 phase 5. **The variant switch was throwing, not no-op'ing:** a parameter-count change
  (`untilFindAll → ifFindAll`) paired a `ListRewrite` on `PARAMETERS_PROPERTY` with a `PARENTHESES_PROPERTY`
  flip, which JDT cannot do at once — it scanned for the parameter list at offsets the parenthesis change had
  invalidated and threw `"Document does not match the AST"` off the end of the file. `AstRewriteHelper`
  catches that and keeps the original source, which is exactly why the dropdown looked inert. Diagnosis was
  written as a failing test **before** the fix (`LambdaVariantSwitchTest`). `adjustLambdaParam` now builds a
  fresh `LambdaExpression` with the parameters and parentheses already right and `rewriter.replace`s the whole
  node, carrying the body over with `createCopyTarget` so the user's statements, comments and indentation are
  moved verbatim rather than re-printed; the in-place path stays for a pure rename, where it works.
  **`LambdaCallBlock`'s facade is now a `ComboBox`** over `SdkApi.FACADE_CLASSES` instead of a `Label` — the
  block was a one-way door, since nothing else on it names the class. Picking another facade goes through the
  new `CodeEditor.replaceLambdaCallWithFacadeCall`, which *replaces* the call rather than reusing
  `updateMethodInvocation` (that syncs arguments positionally and would try to fit the old image and lambda
  into the new signature). A body with statements in it is confirmed away first; an empty one is not, so the
  prompt that matters isn't trained away.

- **2026-08-04 — Every `Time` argument is dispatched by type; the `(method, argIndex)` hook is gone.**
  Improvements round 2 phase 4. Added a `Month` picker beside the `DayOfWeek` one (`TimeArgPicker.month`,
  registered in `PickerRegistry`, seeded `java.time.Month.JANUARY` by `InitializerFactory`), and **deleted
  `PickerContext.isTimeHourArg` plus `TimeArgPicker.hourOfDay`** — the SDK's bare-hour `isBetween(int, int)` /
  `isBetweenUtc(int, int)` overloads they served no longer exist. That hook was the last per-method picker
  entry for `Time` and the exact failure mode this codebase avoids: an argument-index table stops firing the
  day a facade gains an overload, and a picker that merely fails to appear breaks no test. `TimeArgPickerTest`
  now asserts the *absence* of a picker on an `int` argument of `Time.isBetween`, so reintroducing a hook is a
  red test rather than a silent regression. The two enum dropdowns share one generic `constants(…)` builder;
  they exist at all because `EnumPicker` resolves constants through the project type index, which covers the
  SDK jar and the user's sources but not the JDK, so a `java.time` slot would otherwise fall through to a text
  pill.

- **2026-08-04 — The duration picker edits `java.time.Duration`, and the range restructures the call.**
  Improvements round 2 phase 3, following the SDK dropping its own `Duration` record. `DurationPicker` now
  reads/writes `Duration.ofMillis/ofSeconds/ofMinutes`, and `InitializerFactory` + `BlockCatalog.WAIT` seed
  `Duration.ofSeconds(1)`. Two consequences worth remembering. **Fractions change unit rather than truncate:**
  `java.time`'s factories take `long`, so 1.5 seconds is committed as `ofMillis(1500)` and 1.5 minutes as
  `ofSeconds(90)` — the largest unit that still expresses the value exactly, which also keeps a whole number in
  the unit it was typed in. **The random range is now a different call, not a nested expression:** ticking the
  toggle rewrites the enclosing statement `Wait.time(x)` → `Wait.between(x, y)` and back, so the picker edits
  the `MethodInvocation`, not just its own slot. Each end of a `between` then gets its own button labelled with
  its own length (the range is already legible from the method name) while opening either edits both — and a
  call with an end this control can't show (a variable) is left whole, since restructuring would discard it.
  Outside a `Wait` call the toggle is hidden: there is no statement to restructure. This also retires a live
  hazard — `ImportManager` already mapped the bare name `Duration` to `java.time.Duration`, contradicting the
  SDK type the picker used to insert.

- **2026-08-04 — App icons in the emulator picker, and an honest empty-list note.** A package list is a list
  of reverse-DNS strings; `com.supercell.clashofclans` only reads as a game if you already know it. Each app
  row now carries its launcher icon, read out of the installed APK by shared's new `ApkIcon` (four bounded
  byte ranges, no file pull) via `EmulatorProbe.appIcon`. One background thread walks a row's packages in
  order rather than one per app — each icon is several ADB round-trips — and results are cached per
  `identity/package` **including the failures**, since a row re-renders three times (initial, post-probe,
  post-start). The write-back checks the button still under that index is the one it fetched for, because a
  start/stop can rebuild the list under a fetch in flight. Separately, `EmulatorProbe.installedApps` now
  returns **null** for "couldn't talk to it" as distinct from an empty list: a *declined* ADB authorization
  prompt looks exactly like a healthy instance from outside (the TCP probe answers, the dot goes green) while
  every query fails, and reporting that as "no third-party apps found" blamed the device for the user's own
  dismissed dialog. The note now names the prompt and what to do about it.

- **2026-08-04 — Start and stop an emulator from the picker.** Improvements round 2 phase 2. Every discovered
  instance has carried its host `launchCommand`/`stopCommand` since the emulator work began and **Studio had
  never called `EmulatorLauncher`** — so a stopped instance showed "start it to list apps" and offered no way
  to, which on Waydroid (no other route to bring it up) meant no launch target could be chosen at all. Each row
  in `EmulatorPickerDialog` now carries a **Start**/**Stop** button, hidden when the product ships no console
  tool for that direction (`canLaunch`/`canStop`). Because `EmulatorLauncher` is fire-and-forget by contract —
  `true` means *dispatched*, not *up* — readiness is established here: `waitFor` polls the ADB port to a
  bounded ceiling behind a `starting…` state, then the row re-probes itself, so the app list fills in without
  reopening the picker. Waydroid gets a 4-minute ceiling against 90s for the rest (container + session +
  Android boot, not a process start), and a poll that expires there opens `WaydroidDiagnosticsDialog` — the
  precise on-failure trigger the diagnostics were built for. *Not done:* applying a framebuffer resolution
  before the spawn, see backlog.

- **2026-08-04 — Overlay editor: delete, a live argument popup, and the scaffold hooks.** Improvements round 2
  phase 1. **Delete worked on nothing that mattered**: `ProgramShapeOverlay.delete` tested the block's *own*
  node for `instanceof Statement`, which is false for every method-call row — `MethodInvocationBlock` holds the
  `MethodInvocation`, not its `ExpressionStatement` — so both ✕ and Del returned silently. The target is now
  resolved by `CodeBlock.enclosingStatement()` (new default method), and the two other spellings that had grown
  around the same confusion (`AbstractStatementBlock`'s `(Statement) astNode` cast, `MethodInvocationBlock`'s
  `astNode.getParent()` one) go through it too. **The argument popup no longer goes stale**: it was built once
  from block/AST nodes that every picker write replaces, so it showed old values and dropped every edit after
  the first — `configContent` is now rebuilt in place (same `Stage`, same position) on each re-parse via
  `refreshConfig`, located by body-ordinal coordinates, and it closes only when its call is gone. Plus an
  explicit **Done** button, since `setAlwaysOnTop` + `promoteAboveFullscreen` can leave no title bar to close
  it with. **`GoHome`/`Popups` are selectable**: they have no `ActivityDefinition` and live beside the main
  source rather than under `activities/`, so a picker built from the flow could never reach them —
  `targetNames()` appends the on-disk hooks under a disabled `— scaffolds —` caption
  (`MethodLock.superviseHookFiles()`), and `selectActivity` resolves either location.

- **2026-08-04 — Waydroid troubleshooting in the emulator picker.** Improvements plan phase 6, Studio half.
  `ui/render/components/WaydroidDiagnosticsDialog` renders shared's `WaydroidDiagnostics` findings — symptom,
  remedy, and the commands in a read-only monospace area with a **Copy** button and *no Run button*, because
  every fix needs `sudo` and reaches outside anything BotMaker owns (the host packet filter, the Android
  system image). Reached two ways, offered rather than forced: a `Diagnose…` button on the Waydroid row in
  `EmulatorPickerDialog` (with a click filter, so asking why it is broken doesn't also select it), and
  automatically from the no-instances summary when Waydroid is installed but discovery found nothing — the
  moment the user has a symptom and no explanation. The empty result is worded as a conclusion ("no problems
  found, the cause is somewhere else"), since ruling the setup out is what makes running the check worthwhile.
  The picker itself needed no other change: Waydroid arrives through `Platforms.discoverAll()` like any
  product.

- **2026-08-04 — pickers for durations and the clock.** Improvements plan phase 5, the Studio half of the
  SDK's new `Duration`. `ui/render/components/pickers/DurationPicker` is registered **by type** in
  `PickerRegistry` beside `Precision`: a value, a unit dropdown (ms/s/min) and a *random range* toggle that
  commits `Duration.between(…)`. The unit shown is the one the source names — `Duration.seconds(1.5)` and
  `Duration.ms(1500)` are the same value, so reading the number back would silently rewrite what the user
  typed. `palette/BlockCatalog.WAIT` now inserts `Wait.time(Duration.seconds(1))` rather than
  `Wait.milliseconds(1000)`, so the block the menu adds is the one the picker can edit.
  `ui/render/components/pickers/TimeArgPicker` covers the `Time` facade: a 24-hour clock for a `LocalTime`
  slot (`LocalTime.of(5, 30)` vs `LocalTime.of(5, 3)` is one keystroke and both compile), a day dropdown for
  `DayOfWeek` — `EnumPicker` can't resolve `java.time` constants, so it would otherwise be a text pill — and,
  as the one `(method, argIndex)` hook here, an 0–23 dropdown for the bare hours of `Time.isBetween(int, int)`.
  `InitializerFactory` seeds all three types (each is constructor-less, so the generic `new T()` would be
  uncompilable in the user's project); the `java.time` seeds are fully qualified because those types aren't in
  the SDK jar the analyzer indexes. +12 tests (663 total).

- **2026-08-04 — a popup detector the user configures by editing it.** Improvements plan phase 4, the Studio
  half of the SDK's `PopupGuard`. The game-bot scaffold now generates an editable `Popups.java` beside
  `GoHome.java` (`project/ProjectCreator.gameBotSources`), and the entry point installs it with
  `PopupGuard.install(Popups.INSTANCE::execute)` — the SDK then runs it before every vision step.
  **(a) It ships empty.** A scaffold can't know this game's popups, and `ImageTemplateGroup.of()` throws at
  class-init, so declaring a placeholder `POPUPS` constant would break the project before the user ever opened
  it; the body is a TODO with the working shape in its javadoc, exactly the GoHome model. The user builds the
  real `whileFindAny(POPUPS, found -> …)` branching in the block editor, with the group edited by the existing
  `ImageTemplateGroupPicker` — no new picker.
  **(b) It is protected like GoHome.** `project/MethodLock.SUPERVISED_HOOKS` gains `Popups.java`, so `run()` is
  `SIGNATURE`-locked (the entry point binds it by method reference) and `isEnabled()` is `FULL` — which gets
  missing-file recovery and damaged-method repair from `ProjectRepair` for free, carrying the user's body over.
  **(c) Older projects are migrated.** `project/ScaffoldMigration.installPopupGuard` adds the install line and
  its import to an entry point generated before the guard, writing `Popups.java` first so a failure between the
  two leaves an unused file rather than a project that doesn't compile. Gated on our own generated import and
  call, and idempotent.
  **(d) A per-activity opt-out.** `ActivityDefinition` gains `popupCheck` (boxed `Boolean`, absent ⇒ true, in
  `activities.json`), with a tick on the flow card and in the new-activity dialog; `ActivityService.driverCase`
  emits `PopupGuard.enabled(<bool>);` for **every** activity, not just the ones opting out — the flag is
  process-global, so an activity that said nothing would inherit the previous one's setting. +5 tests
  (651 total).

- **2026-08-04 — the vision loop's found value is now reachable in the block editor.** Improvements plan
  phase 3, the Studio half of the SDK's `Matches`. The value a `whileFindAny(group, found -> …)` hands its body
  was invisible: the header rendered the call, the body rendered underneath, and nothing named what crossed
  between them — so a bot author could only reach it out-of-band via `VisionContext.getLastMatch()`.
  **(a) A parameter chip.** `blocks/vision/LambdaCallBlock` renders the lambda parameter as an editable name
  field plus the `→` it stands for, reading the name off the AST; committing one goes through the new
  `CodeEditor.renameLambdaParameter` → `AstRewriteHelper.renameLambdaParameter`, which carries the body's
  references along (identifier-scoped, not binding-keyed — see (c) — skipping method/field names and stopping
  at a shadowing nested lambda).
  **(b) The group variants lost their `Runnable` shape.** `Variant` now carries the parameter *name* rather
  than a boolean; all four `…Any`/`…All` forms take a `Consumer<Matches>` to match the SDK, leaving only
  `untilFind…` parameterless (it loops while nothing is found — there is nothing to hand over).
  `LambdaCallHandler.switchVariant` takes a name and renames in place, keeping a user's own name across a
  same-shape switch and resetting it when the value's type changes (`MatchResult` ↔ `Matches`).
  **(c) Scope registration — the change that actually makes it reachable.** `ProjectAnalyzer` now adds
  enclosing lambda parameters to `getVisibleVariables` from the AST, typed from the binding when there is one
  and otherwise by reading the functional interface's type argument (`Consumer<Matches>` → `Matches`) out of
  the library index. This is deliberately not binding-backed: an inferred lambda parameter only gets an
  `IVariableBinding` once JDT resolves the target type from the SDK jar, which in the editor is routinely
  absent, and the binding-only scope walker then reports no such variable at all. `ExpressionMenu`'s "Call
  Function" section reads that list instead of `scope.variables()`, so `found.has(…)`/`.get(…)`/`.best()` are
  offered inside the body. +8 tests (646 total).

- **2026-08-04 — the overlay editor's five broken affordances.** Improvements plan phase 1; five defects that
  between them made the HUD unusable for the job it exists for.
  **(a) "Fill arguments after adding" never fired for most of the palette.** Applying a palette-picked overload
  is itself an edit, so the block resolved from `pendingInsert` was replaced again before the popover could
  open — every method with more than one overload silently skipped the popover. A new `pendingConfig` defers
  the open to the next re-parse.
  **(b) The popover opened behind the HUD.** Not a positioning bug: `promoteAboveFullscreen` re-raises the HUD
  every 750 ms, so any window opened *from* it was shoved back under within the second. `OverlayToolbars`
  gained a gated overload and the HUD now stands down while the popover is up; the popover is promoted
  instead, and `placeBesideHud` puts it to the HUD's right, screen-clamped, flipping left when there is no
  room. (`initOwner` was the obvious fix and is wrong here — JavaFX hides owned windows with their owner, and
  the HUD is deliberately hidden while a capture surface is up with the popover kept alive to host it.)
  **(c) Blocks could not be deleted at all** — a mis-recorded block meant leaving the overlay for the main
  editor. Added a per-row `✕` and Delete/Backspace, both routed through `CodeEditor.deleteStatement` so the
  read-only/pinned-return guards apply rather than being re-implemented; the caret steps back first.
  **(d) Macro recording worked about half the time.** `RecordingSession.start` armed the listener *before*
  clearing the buffer and setting `recording`, so the first click after pressing Record was dropped and the
  previous run's events could leak in; `stop()` copied the `synchronizedList` without holding its monitor
  while the native thread was still appending; `actionCount++` on a `volatile int` lost presses; and one
  `Platform.runLater` per event flooded the FX queue that the insert handoff runs on. Also, the exclusion
  region was applied at `stop()` against the HUD's *final* position, so a drag mid-recording kept the wrong
  events and dropped the right ones — it is now applied per event against an FX-thread-published snapshot.
  **(e) The Overlay Editor button no longer pulled up the launch target.** `liveSessionWindow` returns 0
  whenever `nestedLauncher` is null, and it is created lazily by the Remote Pilot dialog, so on a fresh run
  the session path always missed and the user hit a dead-end warning. It now opens `LaunchTargetDialog`
  (which gained the `show(Runnable onClosed)` overload `ManageCaptureTargetsDialog` already had) and retries
  once, falling back to the warning rather than looping.
  Also: `EventBus.subscribe` returns a closeable `Subscription`, and the overlay drops its two subscriptions
  on close — the acknowledged leak that had every reopen stacking another handler re-rendering a dead HUD.

- **2026-08-04 — B19: the wire corpus was shadowing the BotPilot dist it was filed next to.**
  `PilotServerTest` 404'd on every static path — red on CI and locally, since 2026-07-31. Two directories
  claimed the classpath name `pilot/`: `src/main/resources/pilot/` (the committed BotPilot dist that
  `PilotServer` serves as `directory = "/pilot", location = CLASSPATH`) and `src/test/resources/pilot/`
  (`wire-golden.json`, added the same day B19 was noted). Surefire puts `target/test-classes` ahead of
  `target/classes`, so `/pilot` resolved to the test copy — which has no `index.html`. So neither the server
  nor the test was wrong; the corpus was in the wrong place. Moved to `src/test/resources/pilot-wire/`,
  bytes untouched, so the asserted SHA-256 and the pilot repo's byte-identical copy are unaffected and the
  served path is unchanged. A real app run has no `target/test-classes`, which is why the UI always worked.
  Also: `FileExplorerManager.collectByRole` walks a directory that need not exist yet (`refreshTree` falls
  back to the main source file's parent exactly when `src/main/java` is missing, and that parent is missing
  too), so it now returns early instead of printing a `NoSuchFileException` trace four times a run; the
  genuinely-exceptional path left behind logs at `WARNING` rather than `printStackTrace`.

- **2026-08-02 — an eyedropper, and one `Precision` editor that only shows the knobs the call can use.**
  There was no way to pick a colour *from the game*: `ColorArgPicker` opened the OS palette, which answers
  "which colour do I want" when a bot author already has a pixel on screen and needs the value that matches
  it — and game art is shaded and compressed, so a health bar's red is never `Color.RED`. New
  `ui/app/capture/ColorSampler` is an eyedropper over a frozen frame with an 8× loupe on the exact pixel, and
  it reports the **ΔE spread of the 5×5 neighbourhood** — the honest suggested tolerance, and the number the
  ΔE slider never had any way to justify. `ColorArgPicker` gained the button; both paths commit the same
  fully-qualified `new java.awt.Color(r, g, b)`.
  `ToleranceArgPicker` + `MinPixelsArgPicker` collapse into one **`PrecisionArgPicker`**, following the SDK's
  collapse of `Tolerance` + `MinPixels` into `Precision(deltaE, minArea, minCount)`. It keeps the anchor
  slider with its swatch strip and the to-scale blob-on-a-grid, adds the `minCount` spinner, and — with a
  frame in hand — reports what the current settings actually do to it ("3 blobs match, largest 812 px²"),
  naming *which* gate rejected a miss. That search is debounced 150 ms and run off the FX thread; it is real
  OpenCV work over a full frame. It **reads `PickerContext.methodName()` to hide the knobs the call cannot
  act on**, so a `Precision` on `matchesAt`/`coverage` offers the tolerance alone and one on `findInRange`
  only the quantity gates — the SDK's "some fields are ignored here" javadoc turned into something the UI
  enforces. Committed source is the shortest exact form (`Precision.TIGHT.minArea(400)`), and every form it
  writes reads back as the values it was given, wither chains included.
  New `ui/app/capture/GameFrame` is the single way to get a frozen frame, so the no-frame path is written
  once: it distinguishes **no capture target** from **a target that grabbed blank** (a real Wayland case that
  a message blaming the configuration sends the user to fix the wrong thing), offers *Choose target…* into
  `ManageCaptureTargetsDialog`, and retries on close — never a silent desktop fallback.
  New `ui/app/capture/ZoomPan` extracts the Ctrl+scroll/middle-drag gesture out of `ObjectCaptureSurface`;
  installed as event *filters*, it consumes what it uses, so that surface lost its `panning` bookkeeping
  entirely rather than gaining a second caller's worth of guards. 635 → 637 tests.

- **2026-08-02 — improvements Phase 9: two generated files that held no project data are gone.**
  `GameLoop.java` was a one-line `FlowDriver.run()` hop and `Startup.java` a two-branch switch over `Target`;
  neither said anything about *this* project, and both were `MethodLock.FULL`, so the Studio never let anyone
  put work in them. The entry point now binds `FlowDriver::run` directly and the SDK's 2-arg `Bot.start`
  supplies the launch step. `ProjectCreator` stops writing both, `FileRole`/`MethodLock` stop claiming them
  (`GoHome.java` is the only supervise hook left), and `ProjectRepair.looksLikeGameBot` keys its file-presence
  fallback on `FlowDriver.java` + `ActivityRegistry.java`. New `ScaffoldMigration` runs at project open beside
  `BotSettings.migrate`: it rewrites the legacy 3-arg call (carrying the user's own goHome argument over) and
  then deletes the two files — all of it gated on finding *our* generated call, so a `GameLoop.java` someone
  wrote themselves in an empty project is never touched.
  **Two read-only leaks fell out of it**, both found the moment the whole-file render test started rendering
  `FlowDriver` instead of the retired `GameLoop`: `ConstructorBlock` built its own header and never asked
  whether the file was locked, so every generated utility class shipped a live delete and add-parameter button
  on its private constructor (`canEditSignature()` is now `protected` and both ask it); and a `null` literal
  rendered as the red "Select Expression..." slot, which is right for an unfilled argument and wrong for
  `String node = null;`, the flow's own "nowhere to go". 628 → 635 tests.

- **2026-08-01 — improvements Phase 8: real editors for `Pixel`'s tolerance and minPixels.**
  `ToleranceArgPicker` is a slider laid out against the SDK's named anchors (EXACT/TIGHT/DEFAULT/LOOSE) that
  names the reading rather than showing a bare ΔE, and — when the sibling `Color` argument of the same call is
  a literal — previews a strip of shades at increasing distance from it, marking which the current tolerance
  accepts. The distances are measured with shared's `ColorMatcher.deltaE`, the same function the bot runs, so
  the preview cannot drift from the matcher. `MinPixelsArgPicker` is a spinner over a 1:1 preview that draws
  the *area* (a blob and its equivalent square on a 10px grid) with the readout "400 px² — about 20×20, or a
  circle 23 across", because drawing a radius would teach the misreading the picker exists to correct.
  Both dispatch on `ctx.isType(...)` — the SDK made these value types in the same phase, so no `(method,
  argIndex)` table duplicating `Pixel`'s overloads exists to go stale. `InitializerFactory` seeds a fresh slot
  with `Tolerance.DEFAULT`/`MinPixels.DEFAULT` (a record has no no-arg ctor, so the generic `new T()` would
  have shipped uncompilable Java, exactly the `new Color()` bug at 2c), and `replaceWithRawExpression` gained
  an import-aware overload so a picker can commit the readable `Tolerance.TIGHT` instead of the FQN.

- **2026-08-01 — improvements Phase 7: no generated `BotSettings.java`; the tuning is project settings.**
  `project/BotSettings` keeps its record shape but loses the generator and the regex reader entirely — it now
  reads and writes shared's eight new `botmaker-project.properties` keys, and `ProjectCreator` seeds them at
  creation (`GAME_DEFAULTS` for a game bot, which is where real-input-on came from). `BotSettings.java` is
  gone from both templates and so is the `BotSettings.apply();` at the top of `main`; `ProjectRepair` stops
  regenerating it by virtue of `sourcesFor` no longer listing it. `migrate` inverted: it now reads a legacy
  generated file (or the older inline `ClickConfig.useRealInput` call), writes the values into the properties,
  deletes the file and strips the `apply()` call — falling back per setting to *what the project already has*
  rather than to `DEFAULTS`, because the session keys were always written to the properties file too and a
  generated file that never mentioned `Session` must not reset them. Renames: `ClickConfigDialog` →
  `BotSettingsDialog` (writes one properties file instead of source + two keys), `ClickConfigArgPicker` →
  `BotSettingsArgPicker` (and it picks up the new `setCompareMargin`), `PickerContext.isClickConfigArg` →
  `isBotSettingsArg`, plus the facade name in `palette/SdkApi` and `MenuIcons`. `SessionSetting` shrank to
  the two keys — the hazard it existed for (the SDK ranking a stale generated `Session.disable()` above the
  key the user just ticked) cannot happen with no generated file.

- **2026-08-01 — improvements Phase 5: the overlay works over a private session.**
  When a nested session is live, `ProgramShapeOverlay` now targets *its* host window instead of the project's
  configured desktop window (`UIManager.liveSessionWindow` → `NestedSessionLauncher.revealHostWindow`, revealing
  it first since bring-up minimizes it). Two things the phase turned up: a gamescope host window **cannot be
  named by title** — gamescope renames its output window after the app inside it, and a second window of its own
  shares its `WM_CLASS` — so `CaptureTarget.WindowTarget` gained an optional live `windowId` and
  `ScreenCaptureService.resolveWindow` prefers it, falling back to the title when the id is stale. And a session
  window is **never resized**: gamescope's output (`-W/-H`) and internal (`-w/-h`) sizes are launched equal on
  purpose, and resizing is what would break the 1:1 mapping recorded coordinates depend on. Input needed no work
  — gamescope forwards host input into its Xwayland. The premise (a host-side X capture of the host window reads
  real pixels, rather than black) was **verified before building**, and is now pinned by
  `SessionHostWindowLiveTest.theHostWindowReadsRealPixelsFromTheHostSide` on both backends.

- **2026-08-01 — improvements Phase 4: the overlay names the activity it records into.**
  `ui/app/overlay/ProgramShapeOverlay` gains an activity picker (constructor-injected `ActivityService` from
  `UIManager`): choosing one switches the editor to `activities/<Name>.java` and parks the caret above that
  activity's trailing `return`, so recorded and hand-added blocks actually land somewhere that runs. Previously
  the target was implicit — whatever file was last rendered — which in a GAME_BOT project is always generated
  scaffolding, so `insertBelowCursor` returned silently and recording appeared to do nothing. The choice is
  remembered per project (`StudioProjectSettings.lastRecordedActivity`); a project with no activities disables
  recording with a reason instead. `InsertionCursor` index `-1` is now documented as a real position.

- **2026-08-01 — improvements Phase 3: object capture zooms.** `ui/app/capture/ObjectCaptureSurface` puts its
  four layers (frozen frame, mask preview, stroke trail, rubber band) in a `Group` carrying a `Scale`+`Translate`
  pair: Ctrl+scroll zooms about the cursor (0.4×–8×, step 1.1), middle-drag pans, Ctrl+0 or a click on the new
  `%` readout in the control bar resets. Only `toContent(MouseEvent)` knows about the zoom, so `solve`,
  `paintAt`, `showPreview` and `finalizeSelection` keep receiving unscaled surface-logical coordinates and the
  extracted crop is unaffected. The band's stroke is divided by the scale to stay a hairline, and image
  smoothing is switched off above 1× so magnified pixels look like pixels. A press now only starts a gesture
  when it lands on the image, which also stops a miss on the control bar from opening a rubber band behind it.
  The sibling `CaptureSurface` (capture one/many) is deliberately untouched — it is a transparent window onto
  the *live* desktop, where a zoomed view would no longer line up with what is underneath it.
- **2026-08-01 — improvements Phase 2: the Activity Flow arranges itself, and new activities get a real
  dialog.** Three changes to `ActivityFlowDialog` / `FlowCanvas`. (1) A flow whose saved layout carries *no*
  card positions is auto-arranged on open, in the same post-layout `Platform.runLater` that redraws the wires
  (so cards stack by their real heights, not the fallback). One saved position is enough to mean somebody laid
  this out by hand, and then nothing moves — positions are persisted and stomping them would be unrecoverable.
  (2) New `ui/app/flow/NewActivityDialog`: name, description, go-home tick and an editable outcome list, with
  the implicit `NEXT` shown as a fixed, non-removable first row (it isn't part of `ActivityDraft.outcomes()`,
  so an ordinary row would either lose it on save or duplicate it — but hiding it makes the card grow a port
  the dialog never mentioned). It replaces the top bar's bare name field, which could only produce an activity
  with no outcomes at all. (3) Both entry points go through `createActivityAt(Point2D)`: the "Add activity"
  button at `nextFreeSpot()`, and a double-click on empty canvas under the cursor — `beginCanvasGesture` now
  guards `getClickCount() == 2` on the primary button and returns before starting a rubber-band. The point is
  `content`-local, which is already pre-`Scale` canvas space, so the card lands under the cursor at any zoom.
  The naming rules moved to `ui/app/flow/FlowNames` (identifier, outcome normalisation, name-collision
  messages) now that two dialogs in two packages apply them; a lenient second copy would admit a name that
  only breaks later, in the generator.

- **2026-08-01 — improvements Phase 1: the launch-target button shows its cover art after Project Setup.**
  `ProjectSetupDialog` opened `LaunchTargetDialog` with `spec -> refresh()`, a callback that only re-ticked
  its own checklist rows. `ToolbarManager.setLaunchTarget` is the sole path that re-labels the toolbar button
  and kicks off `resolveLaunchArtwork`, so a target picked through the Setup hub ticked green while the button
  behind it still read "🚀 Launch Target" with no thumbnail until the project was reopened. The dialog now
  takes a `Consumer<String> onLaunchTargetChanged` and `UIManager.openProjectSetup` passes
  `toolbarManager::setLaunchTarget` — the same wiring the toolbar button (`UIManager:172`) and Getting Started
  (`UIManager:1007`) already used; the checklist still re-ticks alongside it.

- **2026-08-01 — refactor Phase 4 · SV4: Studio no longer spawns `which` to ask what is installed (closes
  B7's Studio shape).** `DesktopGrab.toolExists`, `SessionEnvironment.onPath` and
  `UpdateService.commandExists` were three private copies of `ProcessBuilder("which", name).waitFor()`, and
  the last redirected neither stream — an undrained child that blocks in `write()` once the pipe buffer fills.
  All three are deleted; the six call sites call shared's `Executables.onPath`, which walks `PATH` in pure
  Java, so there is no child and no pipe. Behaviour-preserving: `OnPathParityTest` (written in Phase 3 for
  exactly this swap) asserts shared agrees with `which` on every name these sites ask, and now also that no
  Studio source spawns `which` at all.

- **2026-08-01 — refactor Phase 4 · SC4: `EventBus` logs a handler that throws on *both* branches.** The
  `catch (Exception) → SEVERE` guard — the whole of Studio's error logging — only covered the inline
  delivery; the `runOnFxThread` branch hands the call to `Platform.runLater` and returns, so the throw landed
  on the FX thread after `publish` had gone, unlogged. The guard now lives in the delivery itself and travels
  onto whichever thread runs the handler, so the UI subscriptions — the half most likely to throw — are
  covered. `publish`'s own catch stays for a failure to *queue* the delivery. Prerequisite for SU13, which
  removes the pre-marshalling that was masking this. Held by `EventBusErrorHandlingTest`.

- **2026-08-01 — refactor Phase 4 · SV5: `ProjectState` is confined to the FX thread (fixes B10).** Background
  work now reads a `ProjectState.Snapshot` — code, AST, block registry, classpath, file contents and template
  as one value — taken on the FX thread *before* the thread is spawned; `DebuggingService.startDebugging`,
  `CodeExecutionService.runCode`/`compileCode`/`compileAndWait` and `buildRuntimeClasspath` all take it
  instead of reading the live state. The block registry is published complete
  (`CodeEditorService.refreshUI` fills a fresh map and assigns it once) and `getMutableNodeToBlockMap()` is
  deleted, so a debug session no longer dies on a `ConcurrentModificationException` when an edit lands, and a
  run can no longer compile the code from one revision against the classpath from the next. Held by
  `ProjectStateConcurrencyTest`; fixtures build registries through `TestSupport.convertAndPublish`.

- **2026-07-31 — refactor Phase 3: the test floor for `ui/` and the remainder (SU4, SC3, SU18).** The last
  block of the phase and its largest coverage move: +160 tests (454 → 614), module line coverage
  **29.3 → 35.5%**, and `ui.app` — the package that runs on every launch — **5.9 → 20.0%** (4,703 → 4,000
  missed lines). `UIManagerSceneTest` is the first test ever to build the main window: the TestFX + Monocle
  harness existed all along and had only ever been pointed at block rendering. Also new:
  `MagicWandSessionLifecycleTest` (every native `Mat` released on close, the bounded history's evictions
  included), `CoordinatePickerLabelTest` (both pickers through one scenario each — the net SU8's merge
  needs), `ThemeAndLayoutSmokeTest` (what the five live DSL entry points do, before SU5 deletes 55 members),
  `ErrorTranslatorTest`, `ResolvedTypeTest`, `VariableScopeVisitorTest`,
  `ProjectAnalyzerStaticSectionsTest`, `GameLibraryScannerTest` and `TypeSummaryManagerCacheTest`.
  **Found: B20** — `ErrorTranslator`'s 26-entry problem-id table is unreachable, because the only
  `Diagnostic` Studio ever constructs is the empty-slot message and javac's output goes to the run pane as
  raw text; **B21** (the enricher double-quotes half its templates); **B22** — `for`, enhanced-`for` and
  `try`-with-resources push no scope, so their variables are still offered after the statement ends and the
  generated source will not compile (red-by-design, `@Disabled` with its condition); **B23** (same-named
  jars share one index cache entry); **B24** (painting over the whole selection throws out of
  `MagicWand.refine` and is silently swallowed). Three production changes, all additive: the Steam/Epic/
  Faugus scanners each grew a package-private overload taking their root so a fixture tree can stand in for
  an install, `EditorFixture` grew a lazily-built `context()`, and Surefire now redirects the BotMaker cache
  dir into `target/` so no test can write into the developer's real cache or credentials file. **SU18 done:**
  `UserLibraryTest`'s hand-edit-the-path `@Disabled` is parameterised to the sibling SDK submodule and now
  runs; every `@Disabled` in the suite carries a bug id and a re-enable condition.

- **2026-07-31 — refactor Phase 3: the BotPilot wire contract, asserted from both repos (P3).**
  `TelemetryWireContractTest` (12 tests) serializes real `TelemetryEvent`s against
  `src/test/resources/pilot/wire-golden.json` — a corpus of every message Studio sends, committed
  **byte-identically** in `botmaker-pilot` (`web/src/wire-golden.json`), where `wire.test.ts` feeds the same
  file through the real client. Neither CI job can see the other repo, so **both sides assert the corpus'
  SHA-256**: change the wire on one side and the other goes red. `TelemetrySerializer` gained
  `telemetryJson`/`stateJson` so the class whose javadoc claims to own the wire schema actually owns all
  three messages on it (`PilotServer` delegates). **Found: B18** — a window title containing a control
  character, or a `NaN` confidence, emits JSON no parser accepts and the client drops the message silently;
  P10's real JSON writer closes both halves. **Also found: `PilotServerTest` is red at `HEAD`** (B19) —
  every static path 404s under Surefire though the resources are on the classpath; needs one manual launch
  to decide whether the server or the test is wrong. **(B19 fixed 2026-08-04 — neither: this very corpus,
  filed under `pilot/`, was shadowing the served dist. See the entry at the top.)**

- **2026-07-31 — refactor Phase 3: the test floor for `services/` + `runtime/` (SV3, MISSING 1–8).** Eight
  new test classes: +60 tests, `runtime` 0.0 → 67.9% (it had never been executed at all, and it is the
  feature — `CodeExecutionServiceTest` now compiles and runs a real bot JVM end to end from a `@TempDir`),
  `services.platform` 0.0 → 60.0%, `services.capture` 0.0 → 20.0%, `services` 21.7 → 27.5%, module
  27.6 → 29.3%. Four tests are `@Disabled` and were each confirmed red first — they gate SV5 (B10, both the
  CME and the torn read), SV11 (the `EventBus` FX branch) and a new bug. **Found: B17** — a window capture
  that comes back blank with no working desktop fallback is returned as a *successful* `WindowShot`, and
  `grabOffThread` hard-codes `blank=false` for window targets, so the "capture came back blank, switch to
  Xorg" warning cannot fire for the default capture target of a game bot. **Also found:**
  `ScreenCaptureService` keeps a private copy of `DesktopGrab.cropToBounds` (delete it in SV15, don't move
  it), and `Executables.onPath` diverges from `which` on a path-shaped argument — so the parity test also
  asserts every call site passes a bare name, which is SV4's real precondition.

- **2026-07-31 — refactor Phase 3: the test floor for `blocks/` + `parser/` (SP4, SP7).** Seven new test
  classes over the write path, the block converter, the statement factory and the signature handlers: +40
  tests, `parser.factories` 23.0 → 57.2%, `parser.handlers` 25.8 → 39.9%, `blocks.vision` 0.0% → covered,
  module 25.7 → 27.6%. Six tests are `@Disabled` and were each confirmed red first — they gate SP5/SP6 and a
  new bug. **Found: B16** — `renameMethod` and `renameMethodParameter` rewrite only the declaration, so a
  rename from the editor leaves call sites and body references dangling and the file stops compiling; the fix
  pattern is already in the same file (`renameForEachVariable`). **Also found:** B12 drops seven statement
  kinds, not five (a plain `try/catch` and a local `class` are dropped *inside* branches that look like they
  handle them); B11 has a third site that bypasses `CodeEditor.edit`; SP8's target (`Kind.WAIT`, the only
  `printStackTrace` the factory emits into bot source) is unreachable dead code, so that item is a deletion
  rather than a coordinated SDK release; and `blocks/misc/ClickBlock` has zero construction sites. SP7 turned
  `TypeAwareSuggestionTest`'s five `assumeTrue` into assertions — one was skipping on a broken test helper,
  not a missing capability.
- **2026-07-30 — Pilot gestures take shared's cursor policy (Phase 12).** `PilotInputService`'s private
  `sessionOwnsPointer()` moved to shared as `PointerPolicy`, because the SDK's own click path had never implemented
  the same rule and was warping the pointer off the target after every in-session click (the game showed a hover
  instead). `TAP` now calls `PointerPolicy.click` and `UP` calls `PointerPolicy.restoreTo` — which also fixes the
  deferred bug on this side: the drag `UP` used to restore `dragOrigin` even on `:N`, ending a session drag with the
  pointer somewhere else. Two new tests cover the drag in both directions.
- **2026-07-30 — Run the bot into the session you already launched (Phase 11 step 3).** `CodeExecutionService` now
  prefixes the bot's argv with `BackgroundLauncher.handoffArguments()` — the live session's display, backend and
  attached window — so the bot joins it instead of bringing up a second private display, which a single-instance
  launcher would have redirected into the first one. The hand-off's shape belongs to shared's `AdoptedSession`
  (which also reads it); Studio only passes it along, and a failure to compose it never blocks a run.
- **2026-07-30 — A dead background session is let go of (Phase 11 step 2).** `BackgroundLauncher` now watches its
  held session (2 s poll on `NestedSession.closeIfDead()`) and drops it when the private display dies, firing the
  stopped listeners. Before, `isRunning()` kept saying yes, the Launch button kept refusing a second bring-up, and
  the dead session's slice kept a private `dbus-daemon` alive that the launch probes read as an open launcher — so
  the next launch was refused too. Studio also sweeps orphaned session slices on boot, off the FX thread
  (`BotMakerStudio.start`).
- **2026-07-30 — Refreshed the committed pilot `dist` (`src/main/resources/pilot/`)** so the browser/PWA client
  Studio serves carries the BotPilot tap fix (a tap is sent at the pointer-**down** coordinate — see
  `botmaker-pilot`). The APK bundles its own copy and still needs `npm run dist` + reinstall.

- **2026-07-30 — "▶ Launch now" creates the session at the project's resolution, not a hardcoded 1280×720.**
  `QuickLaunch.launchInBackground` passed `BackgroundLauncher.DEFAULT_WIDTH/HEIGHT` unconditionally, so the
  Launch button ignored the project's standard resolution while the Remote Pilot path (`UIManager.referenceSize`)
  honoured it. That is not cosmetic: gamescope's `-w/-h` *is* the screen the game inside sees, so the cap became
  the game's own maximum resolution option, and the capture was a scaled copy of what the templates were
  authored from. New `ProjectCreator.readCaptureSize(resourcesDir)` reads `capture.width`/`capture.height` from
  `botmaker-project.properties` — the same file, and the same shape, as the `launch.target` and
  `session.isolated` readers this call site already uses — with the old constants as the fallback when unset.

- **2026-07-30 — An Interact tap in a session no longer takes the pointer with it (isolated-launch fixes,
  Phase 10 / A3).** `PilotInputService` TAP used `clickRestoringCursor` unconditionally, which warps the
  pointer back to where it was immediately after the release. On the host `:0` that courtesy is the whole
  reason the gesture is tolerable; on a session's `:N` there is no user cursor to hand back, and warping away
  is a good way to leave the game rendering a hover highlight where a click should have registered — the
  pointer is elsewhere by the time the next frame samples it. The path is now chosen by
  `Capability.BACKGROUND_CLICK` (`sessionOwnsPointer()`): a session tap takes shared's new
  `NativeController.click(x, y, button)`, which also holds the button down for the session's ~40 ms instead of
  releasing in the same instant. Tests: `aSessionTapDoesNotWarpThePointerBack`,
  `aHostTapPutsTheUsersCursorBack`. Not the root cause of the reported unreliability — the same symptom
  appears with a real mouse in the session, which never touches this code — but a real defect either way.

- **2026-07-29 — A background launch that can't be isolated now says exactly why, immediately (isolated-launch
  fixes, Phase 7).** `BackgroundLauncher.start`'s up-front guard was only about an open host launcher; it now
  asks shared's `LaunchIsolation.check(spec)`, which also names a kind with no child-launchable command, a
  Flatpak-only target with no `dbus-daemon` to own its portal (the game would land on your real desktop), and
  "nothing that starts it is installed" — each as its own sentence rather than one two-minute timeout.
  The failure message after a launch that *was* allowed now reports what the process table says happened
  (escaped to the desktop vs never started) instead of offering both as a guess.

- **2026-07-29 — The private display becomes a bot setting, and a running launcher is refused up front
  (isolated-launch fixes, Phase 4).** Isolation was a properties key with a checkbox buried in the Launch Target
  dialog; it is now a first-class setting in the same place as every other runtime knob, and it travels with the
  bot.
  - **`BotSettings` gains `isolatedSession` + a `SessionBackend {AUTO, GAMESCOPE, XEPHYR}` enum** (shaped like
    the existing `LinuxInput`: stable `id()`, total `fromId`). `source(...)` emits `Session.disable();` /
    `Session.useBackend("…");` **only when they differ from the SDK's defaults**, and the `Session` import is
    conditional on the same test — so a default project's generated file mentions `Session` nowhere and
    regenerating an existing one is byte-identical. `read(Path)` accepts all three spellings a hand-editor might
    reach for (`disable()`, `enable()`, `set(bool)`).
  - **"Session" section in the Input &amp; Clicks dialog**: the checkbox and a backend combo, worded in terms of
    what the setting actually buys (keep using the machine while the bot runs) rather than nested X servers. The
    backend combo disables when isolation is off.
  - **New `SessionSetting` — one writer for a setting that exists in two forms.** The generated statement
    travels with the bot; the `session.isolated`/`session.backend` keys are what *Studio's own* Launch buttons
    read (Studio doesn't depend on the SDK, so it can't run the statement). The SDK ranks the statement **above**
    the keys, so a properties-only write would let a stale `Session.disable()` silently beat the box the user
    just ticked — which is exactly what the Launch Target dialog's toggle used to do. Both surfaces now go
    through `SessionSetting.write`, which moves both forms; reading is from the properties file, the form both
    write. `LaunchTargetDialog` takes a `ProjectConfig` instead of a bare resources dir to make that possible.
    `ProjectCreator` gains `writeSessionBackend`/`readSessionBackend` over a factored `writeProjectKey` (the
    load-modify-store dance had been copied per key), and *removes* the backend key for `auto` rather than
    writing the string — absent is what the SDK reads as "choose by kind", so "never chose" and "chose
    automatic" stay the same bytes.
  - **A running host launcher is now refused before anything is spent.** `BackgroundLauncher.start` probes with
    `HostLauncherProbe` and reports the shared refusal message immediately: a second Heroic/Steam invocation is
    forwarded to the instance already on `:0`, which maps the game on the real desktop, so the private display
    sat empty for the whole window timeout and the forwarded launcher was then SIGKILLed mid-boot — the Electron
    SIGTRAP coredump that started this work. The post-timeout message no longer *asserts* a launcher grabbed it;
    it says so only when the probe agrees, and otherwise offers the likelier "a first Proton/Wine run takes
    minutes".
  - `SdkApi` lists `Session` so the palette recognises and offers it. Tests: the new statements' emit/parse/
    absent cases in `BotSettingsTest`, and a `SessionSettingTest` covering both-forms-move, back-to-default
    removing the key *and* the statement, and the session write preserving every other tuning value.

- **2026-07-29 — Background the Launch buttons, add the opt-out toggle, share the setting (bot-owned-display
  plan, Phase J).** Isolation was reachable only from the Remote Pilot dialog; every other Launch button ran on
  the cursor-moving `:0`. Now the toolbar **▶ Launch** and the Launch Target dialog's **▶ Launch now**
  (`QuickLaunch`) bring the game up in a private nested display **by default**, choosing the backend by kind
  (`SessionBackends` — gamescope for Steam/Epic/Heroic/Faugus/exe games, Xephyr for a `cli:` command) and
  failing **loudly** with the install hint when a game's backend isn't installed, rather than dropping to a
  Xephyr that crashes it. The bring-up + held session moved out of the pilot into a new PilotServer-free
  `services/launch/BackgroundLauncher` (one holder per project keyed by resources dir, started/stopped
  listeners), so the Launch buttons and the pilot drive the **same** session and can't disagree;
  `NestedSessionLauncher` is now a thin pilot adapter over it that routes the session to `PilotServer`. The
  **Launch Target** dialog gained a **"Run in background (private display)"** checkbox persisted to
  `session.isolated` (`ProjectCreator.readSessionIsolated`/`writeSessionIsolated`); new projects default it on.
  The pilot box now preselects the backend from `SessionBackends.preferredBackend(target)` and takes
  availability/install-hint from shared `SessionBackends` (the old `NestedSessionLauncher.backendAvailable` PATH
  probe is gone). Bot Run/Debug already isolates by default via the SDK (Phase H reads the same key) — a
  Studio-side "gamescope missing" note on Run/Debug is deferred (cosmetic; the bot logs the hint itself).

- **2026-07-28 — Surface & default background mode in the pilot (bot-owned-display plan, Phase F).** The
  isolated-`:N` control was buried at the very bottom of the Remote Pilot dialog where users never found it, so
  every click went through the cursor-moving `:0` path. Now the **Remote Pilot** dialog leads with a
  **"Background mode — run the game in a private display (recommended)"** box: the backend picker + Start/Stop,
  a Start gated on a configured launch target **and** the backend binary on `PATH`
  (`NestedSessionLauncher.backendAvailable`, off shared's new `Backend.binaryName()`), and one persistent,
  colour-coded status line driven off live session state — green *"● Isolated on `:3` — Firestone attached.
  Interact drives it; your real cursor stays free."* vs amber *"● Mirroring your real desktop `:0` — Interact
  moves your real cursor…"* (also carries the loud Phase-E failure text). Adds a Xephyr-only **"Show display
  window"** button that raises the "Xephyr on `:N`" host window on the real desktop (gamescope has no host
  window — the pilot preview is its view). New `NestedSessionLauncher` accessors: `activeDisplay()`,
  `attachedTitle()`, `configuredTarget()`, `backendAvailable(...)`.

- **2026-07-28 — Nested launcher fails loudly instead of silently dropping to `:0` (bot-owned-display plan,
  Phase E).** With shared now able to launch store targets (Heroic/Steam/Faugus) into `:N` via their
  child-launchable CLI ladders, `NestedSessionLauncher.runStart`'s no-window path now reports an explicit,
  actionable error: it names the private display, explains a host launcher (Heroic/Steam) likely grabbed the
  game on the real desktop, and warns that the pilot stayed on `:0` so Interact would move the real cursor —
  "close the launcher and try again". No more silent `:0` fallback the user mistakes for success. (The
  shared-side launch mechanics — `LaunchCommands`, `NestedSession.commandFor` ladder — are in
  `../botmaker-shared/ROADMAP.md`.)

- **2026-07-28 — Pilot capture picks the right window (bot-owned-display plan, Phase D).**
  `TargetCapture.resolveWindow` took the first window whose title *contained* the needle, so streaming/Interact
  on a "Firestone" target could bind a wiki tab or launcher entry instead of the game (and the wrong rect made
  `:0` clicks miss). It now delegates to shared `WindowMatch.best(...)` — the same ranked matcher the SDK
  runtime uses — so an exact/prefix/whole-word match beats an incidental substring and the shortest/largest
  window wins ties.

- **2026-07-28 — Nested-session launcher: the producer that connects launch target → `:N` → pilot
  (bot-owned-display plan, Phase A).** New `services/pilot/NestedSessionLauncher` is the missing producer —
  the `setActiveSession` hook from Phase 5 finally has a caller. It reads the project's configured
  `launch.target` (`QuickLaunch.specOf` → `LaunchSpec`), brings up a `NestedSession` on the selected backend
  (Xephyr 2D default; gamescope 3D opt-in), `launch()`es the game into `:N`, and hands the live session to
  `PilotServer.setActiveSession` so the pilot previews and drives that window while the real `:0` desktop stays
  the user's. Stopping reaps the whole tree and clears the session back to `:0`. The nested display is sized to
  the project's reference resolution (`StudioProjectSettings.referenceResolution()`, fallback 1280×720) so
  captures line up with the templates. Because a nested session **owns the single window it launched**, there
  is no capture *target* to pick — the launched game is the target and `capture.source`'s title selector is
  irrelevant while a session is active. UI: an "Isolated display (:N)" section in the Remote Pilot dialog
  (`UIManager.isolatedSessionBox`) with a backend choice + Launch/Stop. Bring-up runs off the FX thread.
  Backend selection unit-tested (`NestedSessionLauncherTest`); live launch is manual (needs a real X server).
- **2026-07-23 — Pilot capture + Interact can be pointed at a bot-owned `:N` session (bot-owned-display plan,
  Phase 5).** New `services/pilot/PilotSession` is the one switch between the user's `:0` desktop (default,
  unchanged) and a nested `DesktopSession`: `TargetCapture` now previews the session's `:N` window (its own
  `capture()` frame, tagged with the window's `:N` rect) and `PilotInputService` drives the session's
  `:N`-bound controller — so capture and Interact share one coordinate space and every gesture is a background
  click by construction (no `:0` cursor to hijack, no `useReliableInput()` escalation, `backgroundInput`
  honestly `true` via `Capability.BACKGROUND_CLICK`). `PilotServer` owns the holder and exposes
  `setActiveSession(...)`/`clearActiveSession()` as the integration point; with no session it behaves exactly
  as before. Tests: `PilotInputServiceTest` (4) + `TargetCaptureTest` (1) via new `PilotFakes`; full suite 339
  green. **Deferred** (needs a live Xephyr `:N` + a phone): the Studio UI that actually launches a nested
  session and calls `setActiveSession`, and the plan's Phase 5 exit — a Tailscale pilot preview of a nested
  session with stable RSS over an hour. Net-new streaming (`XShmGetImage`+`XDamage`) stays out of scope here.
- **2026-07-23 — The Game toggle became an Input & Clicks dialog over a generated `BotSettings.java`.** New
  `project/BotSettings` (record + generator + regex reader) owns all of `ClickConfig` — real input, both delays,
  confidence, compare margin, random clicks, watchdog retries — plus the Linux input backend, written as a
  generated file both templates carry and `main` calls first (`ProjectCreator.sourcesFor`, so `ProjectRepair`
  regenerates it). `ui/app/ClickConfigDialog` edits it; the toolbar's `🖱 Game ●/○` ToggleButton is now a plain
  `🖱 Input` button, and `writeRealInput`'s regex-in-main is gone. Projects predating the file are migrated on
  open: the inline `ClickConfig.useRealInput(…)` seeds the new file and is replaced with `BotSettings.apply();`.
- **2026-07-23 — Project Setup can launch the target it just ticked.** The launch-target row in
  `ProjectSetupDialog` gained `QuickLaunch.button(...)` beside its Set… button, so the checklist can prove the
  target works — and so the game's window exists before the user reaches the capture-target row below it. The
  row builder took an optional trailing `Node` rather than growing a second copy, and the outcome reports to its
  own status label: the summary line is rewritten by the focus-return `refresh()`, which a launch always
  triggers, so a failure parked there would erase itself.
- **2026-07-23 — The run cluster wraps, and Compile moved into it.** `ToolbarManager.createExecutionGroup()`
  returns a `FlowPane` instead of an `HBox`, with Compile leading it (out of `createEditGroup()`, which is now
  just Undo/Redo). Because a `BorderPane` gives its right child that child's *preferred* width, the FlowPane
  also needs a wrap length to be squeezable at all — `UIManager.createScene()` binds `prefWrapLength` to 42% of
  the toolbar width, so the cluster holds one row on a normal window and starts sharing the shortfall with the
  centre group instead of letting it wrap alone onto three or four rows.
- **2026-07-23 — `HeroicLibraryScanner` reads Heroic's config through shared.** The launch stack needed the
  same records to tell whether a `heroic:` target is already running (its app name appears nowhere in a running
  game's command line — the executable and title do), so the parsing moved to
  `shared.launch.HeroicLibrary` and this class kept only what a picker cares about: the `InstalledGame`
  mapping and the `icons/<appName>.<ext>` artwork probe, which now searches every config root instead of the
  first. Drops the Jackson parse here; behaviour is unchanged.

- **2026-07-23 — Quitting the Studio now stops the bot.** `BotProject.close()` was an empty stub whose comment
  said "Clean shutdown of any running processes" — so the bot, which runs as its own OS process, outlived the
  window and kept clicking with no UI left to stop it from. It now stops the debug session (first: a debuggee
  suspended at a breakpoint won't exit until the JDI connection is disposed) and closes
  `CodeExecutionService`. Two more holes closed alongside it: the `File ▸ Exit` item called
  `Platform.exit()`/`System.exit(0)` *directly*, never reaching that close path at all, and now fires the
  stage's `WINDOW_CLOSE_REQUEST` like the window's X does; and a `bot-process-reaper` JVM shutdown hook covers
  the paths with no orderly close (a crash, a signal). `stopRunningProgram` also collects descendants
  **before** killing the parent — afterwards they're reparented to init and no longer reachable, which is how
  a bot-launched game survived Stop. Same fix in `DebuggingService.stopDebugging`.

- **2026-07-23 — Project files: a flat "My activities" replaces the package tree.** `Your files → com → <bot> →
  activities → Mining.java` was four rows of Java ceremony in front of the only file a user ever opens.
  `FileExplorerManager.buildUserTree` — the recursive, empty-directory-pruning walk that *was* that
  arborescence — is deleted; both groups now come from one `collectByRole(dir, role, out)` sweep that is
  recursive but emits **files only**, so an editable file living outside the activities package still shows (at
  the top level) rather than silently vanishing. `Generated by BotMaker` is unchanged and still visible.
  `expandPathTo` went with it: nothing is more than one level deep now.
- **2026-07-23 — "▶ Launch now" in three places; Launch moved left of Capture on the toolbar.** A user can now
  start the project's configured `launch.target` without compiling and running the bot — from the Launch Target
  dialog (verify what you just picked), the Capture Targets dialog (a game's window can't be picked as a capture
  source until the game is up), and the toolbar. One helper, `project/launch/QuickLaunch`, backs all three: it
  runs `shared.launch.Launcher.start` **off the FX thread** (a protocol hand-off spawns processes, and an
  `emu-app:` target polls to its boot timeout) and yields a *disabled* button with an explanatory tooltip when
  no target is configured, rather than an enabled one that does nothing. The whole thing is a one-liner because
  shared owns the launch stack — the entire point of the preceding move. `ManageCaptureTargetsDialog` gained a
  `resourcesDir` constructor argument to read the target. Toolbar order is now
  `Setup │ 🚀 Launch Target │ ▶ Launch │ 🎯 Capture │ …`: you choose what opens before where to look.

- **2026-07-23 — Studio's duplicate OpenCV loader deleted; project-property keys single-sourced.** Studio had
  its own `ui/app/capture/OpenCvNative`, whose javadoc admitted it mirrored the SDK's — two of the three
  independent `loadLocally()` calls that could run in one JVM. `MagicWand` now calls
  `com.botmaker.shared.opencv.OpenCvNative` and the copy is gone. Separately, `ProjectCreator` wrote
  `capture.source` / `capture.width` / `capture.height` / `launch.target` / `debug` and the file name as
  string literals, while the SDK read the same keys from its own literals; both sides now use
  `shared.config.ProjectProperties`' constants, so a renamed key can't silently write to one side only.

- **2026-07-23 — Launch logic moved to shared; `LaunchTargetNames` deleted.** Studio can't depend on the SDK,
  so the launch stack was being copied instead: `util/BrowserLauncher` duplicated the SDK's `UriLauncher`, and
  `project/launch/LaunchTargetNames` re-derived the `launch.target` grammar `LaunchTarget.parse` already had.
  Both now come from `com.botmaker.shared.launch` — `BrowserLauncher` is a four-line wrapper adding only
  Studio's "log it, never throw" contract, and `LaunchSpec` replaces `LaunchTargetNames` in `LaunchTargetDialog`,
  `ProjectSetupDialog`, `LaunchTargetArgPicker` and `ToolbarManager` (which now resolves cover artwork from
  `LaunchKind.id()`/`token()` instead of hand-splitting the spec). This unblocks a quick-launch button, which
  is what motivated the move. (shared/ROADMAP.md has the full picture.)

- **2026-07-23 — A pause between activities, so a looping flow can be stopped.** A flow may cycle on purpose,
  but an activity that finishes in milliseconds and wires back to itself never lets go of the mouse — the
  step budget eventually stops the run, yet the *user* got no moment to intervene. `ActivityFlow` gains
  `stepDelayMs` (default 1000, `0` = no pause), edited beside Max steps in Activity Flow ▸ Loop safety, and
  the generated `FlowDriver` waits after each hand-off (`ActivityFlow`, `ActivityFlowDialog`,
  `ActivityService`). Absent-vs-zero matters, so deserialization goes through a `@JsonCreator` with a boxed
  `Integer` — a missing key would otherwise bind to 0 and silently turn every existing flow zero-delay.
- **2026-07-23 — `Pixel.find` blocks compile again; the colour swatch stops lying.** The default for an object
  argument was a bare `new Color()` — uncompilable twice over (nothing imports it; `java.awt.Color` has no
  no-arg constructor), which is the recurring `cannot find symbol: class Color`. Worse, `ColorArgPicker` reads
  RGB back only from a `new Color(r, g, b)` literal, so its swatch fell back to the JavaFX default (white)
  while the code said otherwise. `Color` slots are now seeded `new java.awt.Color(255, 255, 255)`, fully
  qualified so no import is needed (`InitializerFactory`, pinned by `ColorArgumentSeedTest`).
- **2026-07-22 — The toolbar stops collapsing over the menu bar.** A regression from the min-size clamps added
  with the centered toolbar: `topBar`/`toolbarColumn` had `setMinHeight(0)`, and the root `VBox`'s shrink pass
  treats *every* child as a candidate regardless of `Vgrow`, so on any real bot (the canvas `ScrollPane`'s
  preferred height tracks the block list, leaving the root permanently over-subscribed) the bar was squeezed to
  nothing and — JavaFX not clipping a `Region` — painted `⚙ Compile` up over the menu. Both now pin
  `minHeight = USE_PREF_SIZE`; `root.setMinHeight(0)` is the only clamp the Stage ever reads, so the
  click-resizes-the-window fix is unaffected (`UIManager.createScene`).
- **2026-07-22 — Imports follow the arguments, not just the scope.** Inserting `Pixel.find(Color)` left
  `Color` unimportable, and switching a call through the SDK dropdown or the ⚙ overload picker imported
  nothing at all. Two gaps: `MethodHandler.createMethodInvocation` imported `choice.scope()` but never
  iterated `choice.paramTypes()` — even though it builds a default initializer for each, and those
  initializers reference their type by *simple* name (`Color.RED`, `new Color()`); and
  `updateMethodInvocation` → `syncArguments` (plus its twin in `InstantiationHandler`) added no import for
  either the new scope or the new argument types. Both now import every parameter type through a new
  `ImportManager.addImportForType`, which uses the strongest resolution available — the FQN a
  `Bound`/`FromIndex` already carries, else an analyzer lookup by simple name — and imports an array's
  *leaf*. The overload path threads the `ProjectAnalyzer` (it previously had none) and imports the new
  scope, guarded on capitalisation since a scope may be an instance receiver rather than a type.
  `ImportManager`'s `COMMON_JAVA_UTIL_CLASSES` became a well-known-JDK map so a name-only `Color` resolves
  at all; `Point` is deliberately excluded — the SDK ships its own `com.botmaker.sdk.api.Point` and bots use
  it constantly, so the bare name must not silently resolve to `java.awt.Point`. `addImportForSimpleName`
  now falls through to that name-based path instead of giving up when the analyzer resolves nothing, which
  is what makes the JDK fallback reachable. Covered by `ArgumentImportsTest` (insert + overload switch).

- **2026-07-22 — Pilot Interact taps land, and the generated bot declares its input mode.**
  `PilotInputService` had the same bug as the SDK's `Mouse.click` for exactly one gesture:
  `case TAP -> postLeftClickScreen(...)`, a synthetic event games drop, while `DOWN`/`MOVE`/`UP` already
  drove the real pointer — which is precisely why drags worked and taps did nothing. `TAP` now uses shared's
  `clickRestoringCursor`; drags record the pointer's origin on `DOWN` and restore it on `UP` (never
  mid-drag). Corrected the class javadoc, which asserted Windows needed no escalation because `PostMessage`
  is "already both reliable and cursor-safe" — that claim is what let the bug stand. `ProjectCreator`'s
  `Game bot` template now opens `main` with `ClickConfig.useRealInput(true)`, so a new bot is set up for a
  game by default and the choice is a visible, editable statement rather than a hidden setting. The toolbar's
  **🖱 Game** toggle reads and rewrites *that statement* (`ProjectCreator.readRealInput`/`writeRealInput` on
  `ProjectConfig.mainSourceFile()`) rather than a side-car key — one source of truth, and the bot behaves the
  way its code reads when run outside the Studio. Inserts the call and its import when absent (an `EMPTY`
  bot, or the user deleted it), and updates the editor's cached `ProjectFile` so a disk-only write can't be
  clobbered by the next edit.

- **2026-07-22 — Gallery: installs find the release, stars stop falling back to 0.** One root cause, two
  symptoms. `GitHubClient.ensureRepo` returns an *existing* repo untouched, and the VCS Push button creates
  that repo **private** — so publishing afterwards cut a release into a private repo. `BotPublisher.publish`
  now PATCHes it public (never the reverse) with a clear error if the token's scope refuses. Both read paths
  passed `null` for the token: `GitHubGallery` now takes `GitHubAuth` and sends the signed-in token on
  `latestReleaseTag`/`repoMeta`/the archive download — which also lifts the anonymous 60-req/hour cap that
  silently zeroed a whole browse page (one API call per listed bot). `repoMeta` resolves to `null` rather than
  `RepoMeta.UNKNOWN` on failure so `GalleryDialog.refreshBrowse` can't clobber a known star count with 0.
  `GitHubConfig.archiveUrl` is now the API `zipball` endpoint (honours auth, 302s to a signed codeload URL) and
  `GitHubClient` follows redirects.

- **2026-07-22 — Toolbar: centered, one row, and it stops resizing the window.** The outer wrapping `FlowPane`
  in `UIManager.createScene()` is now a `BorderPane` (left = edit group, center = project actions, right = run
  + identity): a FlowPane packs all three units from the leading edge and cannot centre its middle child,
  which is why the project buttons sat left-aligned and the run cluster drifted. The run cluster also gets
  right padding so it no longer touches the window edge. **The window-resize bug** was the min-size chain:
  JavaFX derives a Stage's minimum from the scene root's *computed* minimum, so a wrapped extra row (min
  height) or a widening label (min width) propagated up and grew the window — only `setMinWidth(0)` was set,
  leaving the height half live. `topBar`, `toolbarColumn`, `mainSplit` and the root `VBox` are now clamped on
  both axes. Labels shortened to icon + one word (`🧭 Setup`, `🔀 Flow`, `🎮 Pilot`, `✂ Templates`,
  `⧉ Overlay`) with the full text kept in the tooltips, the debug toggle's two states made equal-width
  (`🐞 Debug ●`/`○` instead of `on`/`off`), and the capture/launch-target buttons pinned to a fixed width so a
  target switch — or `resolveLaunchArtwork`'s background scan landing with the real game title — ellipsizes
  instead of re-wrapping the bar.

- **2026-07-22 — Pilot Interact actually clicks.** `PilotInputService.controller()` now calls shared's new
  `NativeController.useReliableInput()` once, lazily, on first Interact use. On Linux that escalates the
  input backend from the cursor-preserving `XSendEvent` (whose `send_event=True` events every Wine/Proton
  game ignores — taps landed nowhere) to uinput, else XTest; on Windows it's a no-op. `supportsBackgroundInput()`
  then honestly reports `false`, which `PilotServer` forwards as the state message's `backgroundInput` flag and
  the pilot renders as its existing "moves the computer's real cursor" warning — accurate instead of decorative.
  Escalating only on Interact keeps bot runs on the cursor-safe default. No pilot/TS change; `botmaker-pilot/README.md`'s
  Interact section documents the trade.

- **2026-07-22 — "Project default" capture no longer freezes on an overload switch.** Switching a call onto a
  `(CaptureSource, …)` overload re-seeds the new slot through `InitializerFactory.createDefaultInitializer`,
  which wrote `CaptureExpr.of(<today's default target>)` — a `CaptureSource.window("Firestone")` snapshot that
  stopped following the project's source. It now emits the live `Source.current()` call, hoisted into
  `CaptureExpr.projectDefault()` so the three sites that mean "project default" (`InitializerFactory`, the
  in-block `CaptureSourcePicker`, and the expression menu's capture entry — the last still snapshotting too)
  share one string. New `parser/CaptureSourceOverloadTest` pins the overload switch.
- **2026-07-22 — `ExpressionMenuFactory` split, and every menu entry gets an icon.** The 944-line class held two
  unrelated menus; it is now `ui/render/menu/ExpressionMenu` (fill an expression slot + the type picker),
  `StatementMenu` (insert a block) and package-private `MenuBuilders` (the search box wiring, section headers,
  leaf collection, `buildScopeMenu`). Deleted outright — no shim — and all ~14 call sites updated. New
  `MenuIcons` is the one glyph lookup for both menus: it re-exports the category icons and adds the SDK-facade
  map (🔍 ImageFinder, 🖱 Mouse, ⌨ Keyboard, 🎮 Game, 📱 Emulators, 🐞 Debug, …) plus the structural submenus
  ("Variables", "Call Function", "Library (static)"), which previously rendered bare.

- **2026-07-22 — VCS can push without publishing.** `project/vcs/ProjectVcs` gains a remote:
  `remoteUrl()` / `setRemote(url)` / `push(token)` (JGit, non-forcing, current branch + tags; a
  non-fast-forward is reported as "the remote has commits this project doesn't" rather than clobbered). A
  **Push** button in `ui/app/VcsPanel` signs in if needed (the shared `GitHubAccountBar` popup), and on the
  first push offers to create a **private** repo and set it as `origin` — no release, no gallery entry, no
  provenance. Repo creation moved into `GitHubClient.ensureRepo(...)`, now shared with `BotPublisher`
  (public + `auto_init` for publish; private + no `auto_init` for backup, since an auto-created initial commit
  would make the first push a non-fast-forward). `GitHubConfig.SCOPE` `public_repo` → `repo` (private repos
  need it); tokens predating the change get a "sign out and back in" message instead of a raw 403/404. This
  reverses the earlier "deliberately no Push button" decision recorded in the 2026-06 VCS entry.
- **2026-07-22 — Google sign-in stops pretending.** The identity cluster's round **G** button is now disabled
  with "Google sign-in isn't available yet — reserved for future Tailscale/Drive features." as its tooltip
  (installed on a wrapper, since a disabled JavaFX control gets no mouse events); the alert that only
  apologised is gone. `ProjectSelectionScreen` no longer builds a `GoogleAccountBar` — with a blank
  `GoogleConfig.OAUTH_CLIENT_ID` it could only render itself invisible. The device-flow plumbing
  (`sharing/GoogleAuth`, `ui/app/GoogleAccountBar`) is kept and `GoogleConfig`'s javadoc now records exactly
  what wiring it up would take.
- **2026-07-22 — Faugus Launcher is a pickable launch target.** New `game/FaugusLibraryScanner` reads
  `~/.local/share/faugus-launcher/games.json` (or the Flatpak data root), skipping `hidden` entries and taking
  `artwork()` from each entry's `cover` else `icon` — both absolute paths already on disk, so the picker gets
  previews for free. Registered in `game/GameLibraries`, offered by `ui/app/LaunchTargetDialog` and
  `ui/render/components/LaunchTargetArgPicker`, and named by `LaunchTargetNames` (`faugus:<gameid>` →
  "Faugus game …"). The arg picker's private kind→label switch — a third copy that had drifted to
  "Steam: 570" against the dialog's "Steam game 570" — was deleted in favour of `LaunchTargetNames.describe`.
- **2026-07-22 — Launch target shows its game: Heroic cover art + a named toolbar button.**
  `game/HeroicLibraryScanner` now reads `<configRoot>/icons/<appName>.{jpg,jpeg,png,webp,ico}` for
  `InstalledGame.artwork()` (its javadoc previously claimed Heroic keeps no local art path — it does, which is
  why every Heroic tile was a placeholder). New `project/launch/LaunchTargetNames` owns the `launch.target`
  spec's labels (`describe`/`shortLabel`/`kindOf`/`tokenOf`) for the dialog, the Project Setup checklist and the
  toolbar; new `game/GameLibraries` resolves a `<platform>:<id>` spec back to its `InstalledGame`. The toolbar's
  Launch Target button now reads `🚀 <game title>` with a 20px cover as its graphic, resolved off the FX thread
  and refreshed from `LaunchTargetDialog`'s change callback (`ui/app/ToolbarManager.setLaunchTarget`).

- **2026-07-22 — Toolbar wraps instead of hiding, and no longer resizes the window.**
  `ui/app/ToolbarManager.createCaptureGroup()` returns a `FlowPane` and the three actions that lived in the
  `⋯ More` `MenuButton` (Capture Templates, Overlay Editor, Resources) are plain buttons again — the overflow
  hid them even at full width. `ui/app/UIManager.createScene()` drops the hand-rolled reflow
  (`centerWrap`/`secondRow`/`TWO_ROW_THRESHOLD = 1080` + a width listener) for one outer `FlowPane` holding
  the edit / capture / execution+identity groups as indivisible units; wrapping is the layout's job. The bar's
  fixed 50px height is gone (a wrapped row needs to grow) and `minWidth = 0` on the toolbar containers stops a
  growing button label (`🐞 Debug: on` → `off`, a longer capture target) from driving the stage wider on click.

- **2026-07-22 — Login polish, activity comment/return blocks, Heroic/CLI launch, capture-source default.**
  Six parts. (1) **Comment blocks** (`blocks/misc/CommentBlock`, `TextFieldComponents`, `blocks.css`) are now a
  read-only wrapping amber note with a small ✎ edit button (a locked file gets no button); long notes wrap
  instead of scrolling a one-line field. (2) **Pinned activity return** (`blocks/flow/ReturnBlock`): the
  trailing `return Outcome.X;` of an activity's `run()` shows an outcome-only picker (the nested `Outcome`
  enum's constants) instead of the generic expression menu, and no delete button — you pick which outcome, the
  flow canvas routes it. (3) **Insert-between-comment-and-return bug** (`parser/CodeEditor.canInsertAt`): the
  guard compared a BodyBlock child index (comments included) against the pinned return's statements() index
  (comments excluded), refusing a drop between the generated comment and the return; both it and `insertIntoList`
  now share `toStatementIndex`. Covered by `PinnedReturnInsertTest`. (4) **GitHub login** (`GitHubAccountBar`):
  the device-code dialog now auto-closes on success/failure (it used to stay open), and no-connection errors read
  as "No internet connection…". (5) **Google sign-in** (`sharing/GoogleAuth`+`GoogleConfig`, `ui/app/GoogleAccountBar`):
  OAuth device-flow plumbing + a signed-in email label, hidden until a client id is configured — no backend
  wired yet. Both auth classes now merge into the shared `credentials.json` by key instead of overwriting it.
  (6) **Capture-source picker** (`ui/render/components/CaptureSourcePicker`): "Project default" now emits the live
  `Source.current()` SDK call (survives later default changes) and is labelled "Project Default". Heroic/CLI
  launch targets are the Studio half of the SDK launch work (see `../botmaker-sdk/ROADMAP.md`): new
  `game/HeroicLibraryScanner` (Linux Epic/GOG discovery) + "Heroic game…" and "CLI command…" choices in
  `LaunchTargetDialog` and `LaunchTargetArgPicker`.

- **2026-07-22 — VCS/publish made discoverable and trustworthy, Reader/Editor mode, GitHub fork/PR/sync + stars.**
  Six parts. (1) **Restore fix (the trust bug)**: `ProjectVcs.restoreTo` was correct git, but nothing reloaded
  the project — the in-memory ASTs were written back over the restored files on the next save. A rollback (and
  a per-file discard) now publishes `CoreApplicationEvents.ProjectReloadRequestedEvent`, which `BotMakerStudio`
  handles by re-running its open path from disk. (2) **VCS tool window**: extracted `VcsDialog`'s body into a
  reusable `ui/app/VcsPanel` (IntelliJ Commit layout — message + Commit / Publish… / Propose / Sync on the left;
  a changed-files tree grouped by directory over a Diff/History tab pane on the right), hosted both as a fourth
  **VCS** bottom tab beside Terminal and by the (now thin) `VcsDialog`. New `ProjectVcs.status()` buckets JGit's
  status; `diff()` (JGit `DiffFormatter`) and `discard()` back the diff view and per-file discard. A **⑂ VCS**
  toolbar button plus two round account buttons (GitHub — reuses `GitHubAccountBar`'s device flow in a popup;
  Google — an honest stub) sit far-right, BotMaker-wide. *Deliberate deviation (since reversed — see the
  2026-07-22 Push entry): no "Push" button, on the grounds that a BotMaker project has no git remote.*
  (3) **Reader/Editor mode** via `LockResolver`, the one authority on "may this change": a new `readerMode`
  input makes every verdict a denial that outranks `FileRole`/`MethodLock`. Mode is derived, local-only
  (`project/ProjectMode`): installed bots (have `BotSource` provenance) open read-only until "Switch to Editor
  mode" drops a `.botmaker-editing` marker (excluded from publish + gitignored) and reloads; local projects are
  always editable. Reader blocks render full-colour and control-free — a canvas-level `.reader-mode` CSS class
  undoes the generated-scaffold dimming (kept for its own per-file case) — under a "Reading — switch to Editor"
  banner. (4) **Fork/PR/sync**: `BotPublisher.submitPatch` now pushes one reused `editor-<login>` branch
  (force-updating it, and returning the existing open PR's URL instead of opening a second), and new `syncFork`
  calls GitHub's native `merge-upstream`, surfacing a 409 as a "your fork diverged — open on GitHub" message.
  (5) **Starring + gallery sort**: `GitHubClient.delete`/`isNoContent`, `GitHubGallery.repoMeta`/`isStarred`/
  `setStarred`; `GalleryDialog` gained a Sort (Stars / Recently updated / Name) control and a per-card ★ count +
  star toggle (counts stay GitHub's, so github.com stars count too). (6) **Author identity** — commits carry the
  signed-in login (noreply email); **provenance** shown as "Based on owner/repo @ tag" in the panel.

- **2026-07-21 — blocks that compile the moment they're dropped, and jumps that can only land where they're legal.**
  Three parts. (1) **Scope-aware creation defaults** (`parser/factories/StatementFactory`): `switch`, `Set
  Variable`, `for-each` and `Call Function` were seeded with invented identifiers (`switch (variable)`,
  `variable = 0`, `for (String item : array)`, `BotMaker.DefaultMethod()` — **closes B7**, that method never
  existed), so every drop produced an unresolvable symbol. Each now names something real at the drop site via
  `ProjectAnalyzer` (the drop target's `ASTNode` is threaded through `NodeCreator.createDefaultStatement`), or
  leaves an empty "+" slot when nothing qualifies — never an invented name. `VariableOption` carries a
  `ResolvedType` rather than a type *name*, since "is this switchable / iterable" can't be answered from a
  simple name. The fixed-name declare blocks (`VarDecl`, `ScannerRead`, `ARRAY`) now uniquify (`myList2`, …)
  instead of redeclaring on a second drop. (2) **Switch QOL**: a case's trailing `break` is kept out of the
  `BodyBlock` by `BlockConverter` and drawn as fixed case chrome — nothing to drag, nothing to delete, and
  appends land before it for free (`insertIntoList` offsets from the case label). New `parser/handlers/
  SwitchNormalizer` adds the missing `break` to any falling-through case when a file is opened (skipping the
  arrow form and the multi-label idiom, which don't fall through); a new switch ships as one `case` + `default`;
  `+ Add Case` inserts before `default:`; and an enum switch gets a dedicated case-value menu listing only the
  constants no sibling has claimed, plus "add all remaining cases" as one undo step. (3) **Jump placement**:
  the loop/switch-ancestry rule moved out of `CodeEditorService` into `parser/StatementPlacement`, the single
  implementation now enforced at all four points — drag-over (illegal slots show a red `:drag-over-illegal` bar
  and refuse the transfer mode, carried on the dragboard as a new `JUMP_KIND_FORMAT`), the `+` insert menu
  (illegal blocks aren't listed), and the existing drop and move paths.

- **2026-07-21 — one live view: the debug dashboard is gone and BotPilot can drive the game.** Two halves.
  (1) **Removed `services/debug/TelemetryDashboardServer`** and every wiring point (`UIManager`,
  `ToolbarManager`, `MenuBarManager`, `GettingStartedDialog`). It was the older of two servers rendering the
  same frames and the same `TelemetrySerializer` schema, over SSE + base64 to a loopback browser tab; with
  Studio's in-app preview panel already gone, keeping two half-answers to "what does the bot see?" only split
  the work. `PilotServer` is now the single answer, so **🎮 Remote Pilot moved out of the `⋯ More` overflow
  into the inline toolbar group**, taking the dashboard button's slot. `TargetCapture.base64Jpeg` went with it
  (the SSE data-URL encoder had no other caller). `TargetCapture` / `TelemetrySerializer` stay — they were
  extracted to share one schema between the two servers, and that schema is now the pilot web app's contract.
  (2) **Interact mode in BotPilot**: tapping the video reveals an **✋ Interact** toggle; armed, a tap/drag/
  scroll on the stream reaches the real game. It rides the existing WebSocket rather than a new channel —
  `{"cmd":"interact","on":…}` arms **per connection** (disarmed on connect, and re-sent on every reconnect),
  then `{"cmd":"input","kind":"tap|down|move|up|scroll","x":…,"y":…}` carries absolute screen coordinates,
  which the client derives by inverting the renderer's live letterbox transform (`ViewTransform`, published
  from the draw loop — a re-derived fit would land clicks in the wrong place). Studio replays them through
  `services/pilot/PilotInputService` → `NativeController`. Three deliberate details: a **plain tap is sent as
  `tap`**, not down+up, so it takes the cursor-preserving path (`PostMessage` on Windows, `XSendEvent` on X11)
  while only real drags fall back to `mouseMove`/`mouseButton`; every gesture is **clamped to the rect the
  client was actually shown** (`lastBounds`, published from `pushFrame`) because a pilot session can be
  reachable over a public Funnel URL and must not become a remote desktop; and the state message now carries
  `backgroundInput` (`NativeController.supportsBackgroundInput()`) so the phone warns when the host's Linux
  backend will visibly hijack the real cursor.

- **2026-07-20 — `GoHome` is a project activity, and auto-arrange no longer drifts.** Two fixes.
  (1) The scaffolded `GoHome.java` is generated as a real `Activity` subclass
  (`extends Activity<GoHome.Outcome>` with a self-held `INSTANCE`) instead of a bare `public static void run()`,
  so it gets the same base, `before()/after()/onStuck()` hooks and name-registration as every other activity. It
  stays *standalone* — not in `activities.json`, not a canvas node — because its two call sites are special: the
  supervisor recovery hook and the per-activity "⌂ go home first" pre-step, now
  `Bot.start(GameLoop::run, GoHome.INSTANCE::execute, Startup::run)` and `GoHome.INSTANCE.execute();`
  (`ProjectCreator.gameBotSources`, `ActivityService.driverCase`). No SDK change was needed — a value-returning
  method reference is `Runnable`-compatible. `MethodLock` now treats GoHome like an activity stub (`run()` →
  SIGNATURE, `isEnabled()` → FULL). Two known edges: `ProjectRepair` restores only *methods*, so a hand-deleted
  `extends`/`Outcome`/`INSTANCE` is a plain compile error (same as a mangled activity stub); and projects
  scaffolded before this keep `GoHome::run` in their entry point and won't auto-migrate.
  (2) **Auto-arrange was pushing unlinked activities further apart on every click.** With no edges the layer walk
  positioned only the start card and the orphan pass placed nothing (`FlowRules.orphans` is empty when there are
  no edges), so `centreOnCanvas` translated everything by a delta derived from stale coordinates, widening the
  bounding box each run. `FlowCanvas.autoArrange` now gives *every* placed card a fresh position each run —
  unwired flows grid uniformly, and cards the layer walk didn't reach grid below it — making the layout a fixed
  point. Covered by the new headless `ui/fx/FlowCanvasAutoArrangeTest` (verified to fail before the fix).

- **2026-07-20 — conditional Activity Flow: outcome-routed edges + a generated `FlowDriver`.** An activity's
  `run()` now returns its own nested `Outcome` enum and the canvas maps each outcome to a target, so the flow
  branches and loops. The old generated `GameLoop` iterated `ActivityRegistry.ALL` and disabled each activity
  after running it — the drawn flow only decided a *list order* and there was no current node to branch — so it
  is replaced by a generated `FlowDriver` state machine with an explicit start node and a step budget
  (`ActivityFlow.start`/`maxSteps`; a cycle means no root can be inferred). Editing an activity's outcomes
  reconciles the stub's enum, superclass and `run()` signature through the new `services/ActivityStubSync`,
  which also carries an old `void run()` across. Found and fixed on the way: `ASTParser` defaults to **source
  level 1.3**, so `ProjectRepair` and `LockedRegions` had been reading recovered garbage trees for any file
  containing `@Override` — all whole-file parsing now goes through `parser/helpers/SourceParser`.

- **2026-07-20 — flow round 2: no Stop card, `NEXT`, GoHome, and canvas usability.** From using the above.
  (1) The **Stop card is gone** — an outcome with no wire already ended the run, so the terminal node was a
  second way to say the same thing; the implicit outcome is renamed `DEFAULT` → **`NEXT`** (edges store it as
  blank, so no JSON migration) and `ActivityStubSync` rewrites stale `Outcome.DEFAULT` references.
  (2) **GoHome is a per-activity tick**, on by default with a project-level default for new activities; the
  driver calls `GoHome.run()` after the `active()` check. (3) `run()` always ends in a `return`, and that
  statement is **pinned**: new `project/GeneratedMembers` (consulted through `LockResolver`) refuses to delete
  it or insert after it, and locks the generated `Outcome` enum outright while leaving *which* outcome it
  returns to the user. (4) Dialog: Enter in any field no longer reaches the default Save button and closes the
  dialog — which is also what made outcome edits look like they never reached the `.java` — and outcome names
  are normalised (`bag full` → `BAG_FULL`) instead of rejected. (5) Canvas: `recenter` did its arithmetic
  against the content rather than the *scrollable extent* and ignored the zoom; auto-arrange layered by
  breadth-first depth (so forward wires drew backwards) with a fixed row pitch — now longest-path layering over
  the flow with back-edges removed, barycenter ordering and real card heights; self-wires loop over the card
  instead of hiding behind it; left-drag rubber-band selects and moves a group, right/middle-drag pans, and
  there is a minimap.

- **2026-07-20 — flow/editor bug fixes + canvas and menu polish.** Five fixes from using the new canvas.
  (1) `ActivityFlow.linearize` took the *first* node with no incoming wire as the chain root; placement is
  canvas insertion order, so one un-wired card placed early became the whole "chain" and every wired activity
  was reported an orphan **and dropped from the generated `ActivityRegistry.ALL`** — the longest walk now
  wins. (2) Inserting after a comment landed before it (a `Comment` holds no `Block.statements()` slot and JDT
  folds it into the *extended* range of the next statement, so no index means "after the comment"): that case
  now defers to a text edit tracked by a `RangeMarker` (`AstRewriteHelper.applyRewriteAndInsertAt`), fixing
  paste, palette-insert and drag-drop together. (3) Paste now brings the snippet's imports via
  `ImportManager.addImportForSimpleName`. (4) `ExpressionCatalog` stopped offering maths/logic in reference-typed
  slots — `TypeExpectation.of` folds every object type into `ANY`, which was read as "no constraint", so a
  `Point` slot offered `Addition`; `Object` and unresolved types stay permissive. (5) Activities are now
  **archived, never deleted** (`ActivityDefinition.archived`): deleting stopped `Activities.<Name>` being
  generated while the hand-written `activities/<Name>.java` survived referring to it, so the project no longer
  compiled. Plus: directional arrowheads on wires, Recenter and Auto-arrange buttons, and glyph icons on the
  expression/statement menu entries (set as the item *graphic*, since the menus search on the text).

- **2026-07-20 — warnings triage.** The IDE warnings came from an "enable all 467 inspections" IntelliJ
  profile, not javac (no pom sets `-Xlint`/`-Werror`). Curated to ~222 on / ~245 off — style dogma, complexity
  caps, mutually contradictory qualification/import rules, exception-style rules that fight the deliberate
  best-effort `catch (Exception)` in discovery paths, and unused domains (JDBC/J2EE/serialization) are off;
  defect-finding ones (unused symbols, nullability/DFA, resource leaks, equality, fall-through, concurrency,
  JavaFX, `JavadocHtmlLint`) stay on. `.idea/` is gitignored so the profile isn't version controlled — the
  policy is recorded in the umbrella `CLAUDE.md` § Code style. Real findings that survived were fixed:
  `EmulatorProbe.withDevice` now uses try-with-resources (`AdbDevice` is `AutoCloseable`),
  `ActivityValueWidgets` declares `Control` instead of casting `Node`, and `FlowCanvas` uses Java 21's
  `Math.clamp` (deleting a hand-rolled helper) and drops an unused field.

- **2026-07-20 — code quality: typed platform id + de-duplicated emulator/picker code.** Follows shared's
  `String platformId` → `PlatformId` enum. **Deleted** `EmulatorPickerDialog.brandOf` (a second id→name
  switch that had already drifted from shared's — it said "MuMu" where the platform said "MuMu Player") in
  favour of `EmulatorInstance.brand()`; both pickers' hand-rolled cache/dedup keys now use
  `EmulatorInstance.identity()`, and their byte-identical `statusLine` copies use
  `Platforms.PlatformStatus.statusLine()`. New `emulator/EmulatorProbe` holds the TCP liveness probe,
  `screencap` and `installedApps` that `EmulatorPickerDialog` and `CaptureSourcePicker` each carried their
  own copies of. `CaptureSourcePicker.toFxImage` is gone: `ScreenCaptureService.toFxImage` is now
  null-tolerant (returns null for a null image) so the one implementation serves best-effort callers too.

- **2026-07-20 — Activity Flow canvas (replaces the two activity dialogs).** `ui/app/ActivityFlowDialog` is
  now the single place activities are defined, configured, ordered and switched on — **Manage Activities…**
  and **Set Activity Values…** are gone (both classes deleted), replaced by one **🔀 Activity Flow** toolbar
  button + Project ▸ Activity Flow…. Three panes: the free-form `ui/app/flow/FlowCanvas` (draggable cards,
  drag-from-▶-port wiring, click-a-wire to delete, Ctrl+scroll zoom, dot grid), a side panel editing the
  selected card's name/description/params (or the project globals when nothing is selected), and a preset bar
  (built-in Everything/Nothing + user-saved) that flips enable ticks without touching the wiring. A live
  footer previews the run order and names any card the chain never reaches ("won't run"). `ui/app/flow/
  ChainRules` keeps the flow a single linear chain — self-wires, forks, joins and loops are refused with an
  inline reason — and delegates linearization to `ActivityFlow.linearize`, the same walk the generator uses,
  so the previewed order is the generated order. Value widgets were lifted out of the retired dialog into
  `ui/app/flow/ActivityValueWidgets`. Note a *second* disconnected chain reads as orphaned, by design: only
  one chain runs.

- **2026-07-20 — activity flow: one-shot activities + chain data model + flow-ordered registry.** Groundwork
  for the Activity Flow canvas (the visual editor itself lands next). **Execution model:** an activity's
  `execute()` is its whole job and runs **once** — the generated `GameLoop` (`ProjectCreator.gameBotSources`)
  now calls `activity.disable()` right after it, so a disabled activity is skipped and the flow moves on; when
  all have run, `!anyActive` stops the bot. Existing projects pick the new template up via **Project ▸ Recover
  Project Files**. **Data model:** new `project/activity/` records `FlowNode` (canvas placement), `FlowEdge`
  (a wire), `ActivityFlow` (topology + `order(...)` linearization from the chain root) and `ActivityPreset`
  (a named on/off selection, wiring untouched); `ActivitiesConfig` grew `flow` + `presets` — a
  back-compatible JSON addition — plus `orderedActivities()`/`applyPreset()`/`withFlow()`/`withPresets()`.
  **Generation:** `ActivityService.generateRegistrySource` emits `ActivityRegistry.ALL` in flow order,
  excluding orphans (unwired nodes still get a stub and their `Activities.<field>` flags, so the project
  compiles — they just don't run); an un-wired flow falls back to plain definition order so legacy projects
  are unchanged. New `applyPreset`/`updateFlow` service entry points route through the normal `update` write
  path. Known v1 limitation: with no loops, a flow runs once top-to-bottom then the bot stops.

- **2026-07-20 — batch: toolbar declutter, activity-name picker, emulator chooser fixes, startup lifecycle.**
  Five improvements. **(1)** `ToolbarManager.createCaptureGroup` moves secondary capture actions (Capture
  Templates, Overlay Editor, Resources, Remote Pilot) into a "⋯ More" overflow `MenuButton` so the inline bar
  stays compact. **(2)** `ExpressionMenuFactory` offers an "Activity name" dropdown (from
  `ProjectAnalyzer.getActivityNames`) when editing the string arg of `Activity.enable/disable`, inserting the
  name as a literal instead of free-typing. **(3)** `LaunchTargetDialog` Clear/Close buttons pinned to
  `USE_PREF_SIZE` (were ellipsizing to "…"); status label now the flexible element. **(4)**
  `EmulatorInstanceScanner` de-dups by identity `(platformId,host,adbPort)` not display name — fixes instances
  collapsing and a MuMu instance rendering with the BlueStacks brand; both pickers (`EmulatorPickerDialog`,
  `CaptureSourcePicker`) now show a per-product detection summary (via shared `Platforms.discoverDetailed`) and
  the emulator chooser gained clearer app-state messaging + a manual package-name fallback. **(5)** Generated
  `Startup` template is now `run(StartMode)` — `COLD → Target.startIfNotRunning()`, `RESTART → Target.restart()`
  (matches the SDK `StartMode` change; skip-relaunch-if-open on cold start, force-stop-then-relaunch on recovery).

- **2026-07-19 — fix: smoke test 3-arg `OpenHandler`, unblocks release.** `ProjectSelectionScreenSmokeTest`
  still built the screen with the old 2-arg callback lambda; the Project Setup work made it 3-arg
  (`open(name, clearCache, freshlyCreated)`). Release runs `-Pdist package -DskipTests`, which still compiles
  test sources, so `testCompile` failed and aborted the last two tags. Fixed the lambda in `ui/fx/`.

- **2026-07-19 — Project Setup hub + Getting Started guide.** New onboarding for a fresh project.
  `ui/app/ProjectSetupDialog` is a checklist hub that reads each setup step's status live and opens the
  existing editor for it: **Launch target** (`ProjectCreator.readLaunchTarget`), **Capture target** (non-seed
  default in `StudioProjectSettings`), **Reference resolution**, and an optional **Image templates** row
  (informational only — pixel/OCR/coords bots need none). It re-ticks on `SettingsChangedEvent` and on regaining
  focus after a child dialog closes. `ui/app/GettingStartedDialog` (Help ▸ Getting Started) explains each
  feature area with an "Open ▸" jump button reusing the toolbar/menu actions. Entry points: a 🧭 Project Setup
  toolbar button (`ToolbarManager.createCaptureGroup`) + Project ▸ Project Setup…, wired in `UIManager`; the hub
  **auto-opens once on project creation** via a `freshlyCreated` flag threaded from `ProjectSelectionScreen`
  (new `OpenHandler` interface) through `BotMakerStudio.finishOpen`.
- **2026-07-19 — Capture-object polish (Phase 7).** Four fixes to the GrabCut "Capture object" flow.
  **Perf:** `MagicWand.Session.refine` now solves on a **cropped ROI** around a working box (an OpenCV
  sub-`Mat` shares the parent's pixels, so the solve writes back into the full mask while only paying for the
  pixels that can change) instead of re-segmenting the whole frame each stroke. **Undo/redo:** a bounded
  mask-snapshot history in `Session` (`undo`/`redo`/`canUndo`/`canRedo`, pushed before every solve, released
  on `close`) wired to **Ctrl+Z / Ctrl+Y** (and Ctrl+Shift+Z) in `ObjectCaptureSurface` — previously
  Esc-only. **No crop on refine:** `paint` grows the working box to cover every stroke, so foreground painted
  outside the original box isn't dropped by the ROI (with the coordinate audit confirming `scaleX/scaleY` and
  the physical-frame `objectFrameW/H` sidecar reference are already correct). **Naming preview:** a new
  `ImageTemplatePicker.promptTemplateName(…, BufferedImage preview)` overload shows a thumbnail of the crop
  above the name field (ARGB transparency and all), now used by the single, object, and picker capture flows
  (batch already had per-row thumbnails). Stale "scroll to resize" object tooltip corrected.
- **2026-07-19 — Ellipse (circle/oval) template capture.** Capture Templates gained a **▢/⬭ shape toggle**
  (`OverlayTemplateCapture.buildShapeToggle`) that applies to both *Capture one* and *Capture many*. In
  ellipse mode the rubber-band draws an inscribed oval (hold **Shift** for a perfect circle) and the saved
  crop is masked to that oval with a **transparent background** — `cropToImage` clips the bounding-box
  subimage to an `Ellipse2D` into an ARGB image, reusing the object-cut PNG/preview path (no library/batch
  change). `CaptureSurface` now carries a `Shape {RECT, ELLIPSE}` on its `Region` and factory methods and
  renders the band/marks accordingly; Capture object is unchanged.
- **2026-07-19 — Block rows wrap instead of ellipsizing (Phase 6).** New `WrappingSentencePane` (an `HBox`
  subclass overriding only the layout math) flows a block's children like words: an overflowing pill falls
  onto a hang-indented continuation line rather than being squeezed, so nothing is ever clipped or shown as
  "…" at any nesting depth. `SentenceLayoutBuilder.build()` and the nested `BlockUIComponents.createArgumentPill`
  now build these panes (returned as `HBox`, so every caller/CSS is unchanged); a lone token wider than a whole
  line is clamped so it wraps internally, an Hgrow spacer still pins a trailing delete button to the right, and
  builder labels use `OverrunStyle.CLIP`. Wrap width comes from the existing `fitToWidth` canvas — no
  `UIManager` change needed.
- **2026-07-19 — Unified debug-output toggle (Phase 5).** Added `"Debug"` to `SdkApi.FACADE_CLASSES` so the
  SDK's new `api.Debug` switch surfaces as a block submenu. New `ProjectCreator.writeDebug`/`readDebug`
  persist the `debug` key in `botmaker-project.properties` (default on, mirroring the SDK's semantics). A new
  **🐞 Debug: on/off** `ToggleButton` in the toolbar capture group (`ToolbarManager.setOnToggleDebugOutput`,
  wired in `UIManager` from `config.resourcesRoot()`) reads the persisted state and writes each change — one
  switch governs the bot's `[Bot]/[Game]/[Target]/[Activity]` + vision traces at runtime.
- **2026-07-19 — Emulator capture category + live previews + Launch Target toolbar button (Phase 4).**
  Added a fourth `Emulators` category to `CaptureSourcePicker` (a tile per configured Android instance, with a
  live ADB `screencap` thumbnail when running) backed by a new `CaptureTarget.EmulatorTarget(instanceName)`
  sealed variant that `CaptureExpr` maps to `new EmulatorSource("<name>")` and `CaptureTargetNames`/
  `TargetThumbnail` now handle (so an emulator target previews in the Capture Targets manager too). The
  `EmulatorPickerDialog` rows gained the same live screencap preview. New **🚀 Launch Target** toolbar button
  (sibling of 🎯 Capture Targets) opens `LaunchTargetDialog` — Steam/Epic/Exe/Emulator-app builder that bakes
  the choice into `botmaker-project.properties` (`launch.target`, via `ProjectCreator.writeLaunchTarget`, plus
  `capture.source=emulator:<instance>` for an emulator app), seeded from the new `ProjectCreator.readLaunchTarget`.
- **2026-07-19 — Custom argument pickers for Color / ClickConfig / LaunchTarget / emulator launch-stop (Phase 3).**
  New `SpecialTypePicker`s registered in `PickerRegistry`, each replacing a plain constructor/number pill:
  `ColorArgPicker` (a `java.awt.Color` slot → JavaFX colour swatch → `new java.awt.Color(r,g,b)`),
  `ClickConfigArgPicker` (each bounded `ClickConfig` setter arg → a range-limited spinner dialog for delays/
  retries/confidence and an inline checkbox for `enableRandomClicks`/`enableDebugMode`), and
  `LaunchTargetArgPicker` (a `LaunchTarget` slot → Steam/Epic/Exe/Emulator-app builder emitting
  `LaunchTarget.parse("<spec>")`). `PickerContext.isEmulatorNameArg` now also covers `Emulators.launch(name)`/
  `stop(name)` (not just `use`/`named`), and gained `isClickConfigArg`. All commit via
  `CodeEditor.replaceWithRawExpression`/`replaceLiteralValue` so the picker re-matches on the round-tripped value.
- **2026-07-19 — Activity/stop blocks standardized as SDK facade calls (Phase 2).** Removed the bespoke
  `ActivityToggleBlock`/`StopBotBlock` (and the `DISABLE_ACTIVITY`/`ENABLE_ACTIVITY`/`STOP_BOT` catalog +
  `Kind` entries + `StatementFactory` creators + `BlockConverter` interceptions). `Activity.disable/enable("X")`
  and `Bot.stop()` now render through the standard `LibraryCallBlock`/`MethodInvocationBlock` chrome like every
  other SDK call, reached via the `Activity`/`Bot` facade submenus; the `Control` group keeps only
  break/continue/return. Round-trip covered by `BlockDragDropEditTest`. (The activity-name combo will return as
  a `SpecialTypePicker` on the `Activity.disable/enable` string arg in Phase 3.)
- **2026-07-19 — Menu cleanup + expression-menu parity (Phase 1).** `SdkApi` now distinguishes recognition
  (`FACADE_CLASSES`, unchanged) from menu visibility (`MENU_FACADE_CLASSES` = full set minus `Bots`/`Window`/
  `Watchdog`), so those three internal-wiring facades no longer appear as insert-menu submenus while still
  rendering with standard SDK-block chrome where already used. The **expression menu**
  (`ExpressionMenuFactory.createExpressionTypeMenu`) now mirrors the statement menu: a submenu per SDK facade
  listing its static members whose return type fits the slot (void-only methods drop out), plus "Facade.member"
  leaves in the flat search view. `Bot.supervise` is gone from the palette (made package-private in the SDK);
  the generated `main` calls `Bot.start`, and `ProjectRepair.looksLikeGameBot` recognises both `Bot.start` and
  legacy `Bot.supervise`.
- **2026-07-19 — Statement menu rebuilt from the SDK API + full emulator picker dialog (Phase 4).** The
  statement insert menu (`ExpressionMenuFactory.createStatementMenu`, now taking a `ProjectAnalyzer`) is
  generated from `palette/SdkApi.FACADE_CLASSES`: one submenu per facade class in that order, enumerating each
  class's static methods at runtime (`ProjectAnalyzer.getMethods`) as `LibraryCall` inserts (args seeded from the
  resolved overload by `StatementFactory`). The flat promoted "bot actions" row is gone; the hand-authored
  SDK-facade blocks are excluded from the language grouping (reached via the generated submenus), and the bot
  Control statements (enable/disable activity, stop bot) are relocated into a `Control` group placed last. Search
  stays flat across language blocks **and** every SDK facade method. `SdkApi.FACADE_CLASSES` completed + reordered
  (added Keyboard, Text, Activity, Source, Window, Bots, and the new `Target`). Full **emulator picker dialog**
  (`components/EmulatorPickerDialog`): every configured instance with its brand (BlueStacks/LDPlayer/MEmu/MuMu/
  Gameloop), a running dot (TCP probe), and — for a running instance — its installed apps (via shared `AdbDevice`,
  cached per instance so a stopped one still lists its last scan). `EmulatorInstanceScanner.instances()` now
  returns full `EmulatorInstance`s. `EmulatorArgPicker` opens the dialog (button, replacing the combo); picking an
  app also writes `launch.target = emu-app:<pkg>@<instance>` + `capture.source = emulator:<instance>`
  (`ProjectCreator.writeCaptureSource`, sibling to Phase 3's `writeLaunchTarget`). Updated `StatementMenuTest`.
- **2026-07-19 — Read-only generated `Startup.java` + launch-target plumbing (Phase 3).** The game-bot
  `Startup.run()` scaffold is no longer a TODO stub — it is generated wiring, `Target.start()`, which launches
  the project's configured launch target (`ProjectCreator.gameBotSources`). Consequently `Startup.java` is now
  `FileRole.GENERATED` (locked, "Generated by BotMaker" badge, undeletable → recoverable) and its `run()` is
  `MethodLock.FULL` — joining `GameLoop.java`; only `GoHome.run` stays a `SIGNATURE` stub. Added
  `ProjectCreator.writeLaunchTarget(resourcesDir, spec)` to persist the `launch.target` key into
  `botmaker-project.properties` (the picker that calls it — brand + running dot + installed apps — is Phase 4).
  Updated `FileRoleTest`/`MethodLockTest`/`ProjectCreatorTest`. Pairs with the SDK's new `api.launch.Target` /
  `LaunchTarget` holder + `emulator:<name>` capture source (see `../botmaker-sdk`).
- **2026-07-18 — Android emulator blocks + instance picker (Phase 3, Slice B).** New palette blocks
  `USE_EMULATOR` (`Emulators.use("<instance>")` — connects **and** points the whole bot at the emulator via
  `Source.set`, so every no-source vision/click/OCR call then targets it; promoted into `BOT_ACTIONS`) and
  `CONNECT_EMULATOR` (`Emulator emulator = Emulators.named("<instance>")` — keeps a handle for native
  `tap`/`swipe`/`startApp`). Registered the new `Emulators` facade in `palette/SdkApi`. The instance-name arg
  gets a new `components/EmulatorArgPicker` (editable combo of discovered instances + free-text), wired via
  `PickerContext.isEmulatorNameArg()` + a `PickerRegistry` entry — same shape as the Steam/Epic `GameArgPicker`.
  Discovery reuses **shared**'s `com.botmaker.shared.emulator.Platforms` (no Studio-side config parsing): a thin
  `emulator/EmulatorInstanceScanner` projects `discoverAll()` → distinct names off the FX thread. Pairs with the
  SDK's `api.emulator` facade + the shared emulator capability (see `../botmaker-sdk`, `../botmaker-shared`).
  Note: shared now pulls kotlin-stdlib transitively (dadb), so it rides into the Studio app-image.
- **2026-07-18 — Epic Games launch integration (mirrors Steam).** New `game/EpicLibraryScanner implements
  GameLibraryProvider` discovers installed Epic games from `%ProgramData%\Epic\EpicGamesLauncher\Data\
  Manifests\*.item` (JSON via Jackson; `AppName`→id, `DisplayName`→name; no local cover art → placeholder
  tiles). Generalized the Steam-only `SteamGamePicker` into a provider-parameterized
  `components/GameArgPicker` (takes a `Supplier<GameLibraryProvider>`; labels come from `displayName()`),
  so one widget serves both stores — `PickerRegistry` maps `isGameSteamAppIdArg`→Steam and the new
  `isGameEpicAppIdArg`→Epic through it. Added `GameLibraryProvider.findById` default (used by the picker to
  resolve a saved id → name+art; removed Steam's duplicate). New palette block `LAUNCH_EPIC_GAME`
  (`Game.launchEpic`) alongside `LAUNCH_STEAM_GAME`. Pairs with the SDK's `Game.launchEpic` (see
  `../botmaker-sdk`).
- **2026-07-17 — Manage Activities: reorderable activity list (= run/priority order).** `ActivityRegistry.ALL`
  is generated in `activities.json` list order and `GameLoop` runs that order, so an activity's list position
  is its priority — but the dialog had no way to change it. Added **Move up / Move down** buttons to
  `ui/app/ManageActivitiesDialog` (`buildActivitiesSection`) that swap the selected `ActRow` in `activityRows`
  (boundary-aware disable, selection follows the moved row); Apply persists the new order through the existing
  `ActivityService` path, so the regenerated `ActivityRegistry.ALL` — and thus the macro loop's run order —
  reflects it. Hint updated to note "top runs first". No SDK/model change.
- **2026-07-16 — Activity toggle is now a name picker (`Activity.disable("X")`); keyword blocks restyled.**
  The old "disable/enable this activity" emitted a bare `disable()` self-call — only valid inside an Activity
  and only self-targeting, and it compile-broke if dropped elsewhere. Reworked `blocks/flow/ActivityToggleBlock`
  to render an **activity-name ComboBox** (populated from `ProjectState.getActivities()`) and emit the SDK's
  static `Activity.disable("Name")` / `Activity.enable("Name")` (valid anywhere; one activity can toggle
  another). `StatementFactory` seeds the name to the first activity + adds the `Activity` import;
  `BlockConverter` recognises `Activity`-receiver `disable`/`enable` with a `StringLiteral` arg back to the
  picker block (the old implicit-`this` recognition removed); picking rewrites the literal via
  `CodeEditor.replaceLiteralValue`. Fixed the "keyword blocks render as plain text" bug — `withKeyword` tags
  `keyword-label` but `blocks.css` only styled `.header-keyword-label`; added `.keyword-label` to that rule so
  break/continue/return/wait/stop/toggle keywords are styled. Verified the activity **config wiring**
  end-to-end (params + globals → generated `Activities.<field>` → ExpressionMenu "Activities" submenu; values
  via *Set Activity Values*) — sound, no change needed. Tests: `BlockDragDropEditTest` (picker round-trip).
- **2026-07-16 — GameLoop auto-ends when all activities are disabled; new "Stop This Bot" palette block.**
  Paired with the SDK's `Bot.stop()` (see `../botmaker-sdk`). The generated `GameLoop.java` now tracks whether
  any activity ran this pass and calls `Bot.stop()` when the registry is non-empty and none is active — so
  disabling the last activity actually ends the bot instead of spinning `supervise`'s `while (true)` forever
  (the bug: "the bot can't end even when all activities are false"). The `!ALL.isEmpty()` guard keeps a
  not-yet-configured bot behaving as before. `GameLoop.run` is `MethodLock.FULL`, so existing projects pick it
  up via *Recover Project Files* (`BODY_CHANGED` → restored). Added a fixed-label "Stop This Bot" **Control**
  block → `Bot.stop();`: `Kind.STOP_BOT`, `StatementFactory.createStopBotStatement` (a static-qualified call
  that also adds the `Bot` import via `ImportManager`, unlike the bare inherited toggle self-calls),
  `BlockConverter` matches `Bot.stop()` back to the new `blocks/flow/StopBotBlock`. Tests: `ProjectCreatorTest`
  (loop carries `Bot.stop()`/`!anyActive`/the import), `ProjectRepairAstTest` (old loop without the guard is
  restored), `BlockDragDropEditTest` (drop → `Bot.stop();` + import → round-trips to `StopBotBlock`).
- **2026-07-16 — GameLoop checks `activity.active()`; new "Disable/Enable This Activity" palette blocks.**
  Paired with the SDK's runtime enable/disable (`Activity.active()`/`setEnabled()`, see `../botmaker-sdk`).
  The generated `GameLoop.java` template guards each activity with `activity.active()` instead of
  `activity.isEnabled()`, so a mid-run `disable()` actually stops it next pass (the bug: no way to turn an
  activity off → the loop ran it forever). Since `GameLoop.run` is `MethodLock.FULL`, existing projects
  self-heal via *Recover Project Files* — an old `isEnabled()` loop reports `BODY_CHANGED` and is restored to
  the `active()` form (no `ProjectRepair` code change; it diffs against `ProjectCreator.sourcesFor`). Added two
  fixed-label statement blocks in the **Control** category — "Disable This Activity" → `disable();` and
  "Enable This Activity" → `enable();` — modelled like `Kind.BREAK`/`CONTINUE` (no scope/method dropdown, so
  the implicit-`this` self-call never surfaces a scope pill). New `Kind.DISABLE_ACTIVITY`/`ENABLE_ACTIVITY`
  emit via `StatementFactory.createSelfCallStatement`; `BlockConverter` matches an implicit-`this`, no-arg
  `disable()`/`enable()` back to the new `blocks/flow/ActivityToggleBlock`. Tests: `ProjectCreatorTest`
  (`active()` in the loop), `ProjectRepairAstTest` (old `isEnabled()` upgraded), `BlockDragDropEditTest`
  (drop → `disable();` → round-trips to a toggle block). **Needs the matching SDK release** — the generated
  loop calls `active()`.

- **2026-07-16 — `GameLoop.run` reclassified as fully generated (`MethodLock.FULL`).**
  It was `SIGNATURE` ("body is the user's"), so its call blocks legitimately kept live class/method selectors
  and the ⚙ overload button, edits stuck, and Recover Project Files ignored an edited dispatch loop. But the
  generator ships `run()` complete (iterate registry → run enabled activities → `Watchdog.checkpoint()`); the
  user's workspace is the activities (plus GoHome/Startup, whose `run()` stays `SIGNATURE`). One change in
  `MethodLock.of` propagates everywhere: blocks in GameLoop are now inert (`LockResolver`), stray edits can't
  reach disk (`LockedRegions`), and an edited loop is `BODY_CHANGED` damage that Recover restores
  (`ProjectRepair`). Tests moved off GameLoop.run as the "editable body" example onto activity `run()`.
  *Follow-up sweep:* rendering the now-fully-locked GameLoop exposed blocks that never met the null-button
  contract — `IfBlock`'s add-else NPE'd the whole render pass ("no blocks visible"), `SwitchBlock`'s case
  delete would too, and `BinaryExpressionBlock` NPE'd styling its change buttons. Guarded those, and closed
  the surviving read-only leaks: else-delete, switch add-case/move-case, list element add/change/move/delete,
  and the operator ComboBoxes in `AssignmentBlock`/`BinaryExpressionBlock`/`ComparisonExpressionBlock` (now a
  plain label when locked). `CodeEditorService.getSdkDocs()` degrades to `SdkDocs.EMPTY` without a docs
  service. New tests render the *real* generated `GameLoop.java` end-to-end plus one locked method covering
  every crash-prone block shape, asserting zero editing controls.

- **2026-07-16 — Follow-up fixes to the lock refactor: render crash, comment-only bodies, foreach rename, generated-file delete.**
  Four reported failures from the lock refactor's new null-returning button contract + a latent index bug.
  **(1)** `DeclareClassVariableBlock` styled its delete button unconditionally, but `createDeleteButton` now
  returns null for read-only blocks — so any read-only file with a field (e.g. `ActivityRegistry.java`) NPE'd in
  `UIManager.handleBlocksUpdate` and rendered *no blocks at all*. Null-guarded.
  **(2)** Adding a statement to a body whose only child is a comment threw `IndexOutOfBounds` — `CommentBlock`
  is a `StatementBlock` so it counted toward the drop index, but a `Comment` isn't in JDT `Block.statements()`.
  `CodeEditor.insertIntoList` now translates the body-child index to a `statements()` index (comments excluded).
  This is why statements couldn't be added to generated `run()` bodies that ship with a comment placeholder.
  **(3)** Renaming a `for-each` iterator renamed only the declaration, breaking compilation.
  `AstRewriteHelper.renameForEachVariable` now renames the declaration + all binding-matched references within
  the loop (falls back to the single-node rename when bindings are unresolved). `ForBlock` uses it.
  **(4)** Generated files were deletable from the explorer behind a confirm dialog; their "Delete File" action
  is now disabled with an explanatory label (recover via Project ▸ Recover Project Files).
  *Follow-up:* `MethodHandler.renameMethodParameter` has the same single-node rename flaw — not yet fixed.

- **2026-07-16 — Locks are enforced where edits happen, not where controls are drawn; project-creation UX; block visual tokens.**
  Fixes six reported bugs whose root causes were two.
  **(1) The write layer is now the enforcement point** (`parser/CodeEditor`). Its `canModify()` tested for a
  path (`com/botmaker/sdk`) that no longer existed, so it *always returned true* — read-only was enforced only
  by not rendering a control, and every path that forgot (the expression menu, the method-call dropdown, the
  separator "+") silently rewrote generated code and persisted it. `edit(...)` now takes the target node + an
  `EditKind`; ~60 call sites route through it, and a refusal is reported to the user rather than being a mystery
  no-op. Covered by `parser/CodeEditorLockTest`, which calls the editor exactly as a forgetful UI path would.
  **(2) `MethodLock` outranks `FileRole` at method granularity, and may unlock as well as lock**
  (`project/LockResolver`, the one place the two verdicts combine). They contradicted each other — `MethodLock`
  documented `GameLoop.run`'s body as the user's while `FileRole` locked the whole file, and the file won,
  which is why statements couldn't be added to the game loop. `NONE` now means "defer to the file"; `SIGNATURE`
  grants the body unconditionally. `ParseContext.readOnlySubtree()` → `withReadOnly(boolean)` (two-way).
  Also: `FileRole.of` ignored its `template` for `GameLoop`/`Activities`/`ActivityRegistry` (an independent
  cause — a user's own `GameLoop.java` in an empty project went read-only), and `looksLikeGameBot` guessed
  GAME_BOT from one stray file. `MethodLock`'s supervise hooks are now anchored to the main package: `SIGNATURE`
  grants a body, so bare-filename matching would have unlocked a vendored `library/GameLoop.java`.
  **(3) An activity's `run()` is `SIGNATURE`-locked** — it's an `@Override`, so a rename silently stops
  overriding `Activity.run`. Its body stays the user's.
  **(4) Persistence is method-aware** (`project/LockedRegions`). The whole-file skip would now discard the
  user's game-loop body on every compile; only changes to *locked parts* are refused. `FileRole.blocksPersistence`
  deleted so the concept can't drift back.
  **(5) Recovery repairs damaged locked methods** (`ProjectRepair.findDamaged`/`repairDamaged`). It was
  existence-only, so a renamed `GoHome.run` was "present, therefore fine" while the bot didn't compile. For a
  `SIGNATURE` lock the signature is restored and **the user's body is kept**; their own methods are never touched.
  **(6) A read-only block now offers no interaction at all** — absent, not disabled: no menus, dropdowns
  (`ComboBox`→`Label`), delete buttons, type selectors, name fields, empty-body invitation or separator "+".
  Driven by factories returning null + builders skipping null nodes, so blocks inherit the rule instead of each
  remembering it. `ui/fx/LockedBlockRenderingTest` asserts on node *types*, so a new block that forgets fails.
  **(7) Project creation:** a lowercase first letter is allowed — `ProjectConfig` derives `className`
  (capitalized) instead of the name doubling as the class; the directory/artifactId keep what the user typed.
  Default sort is newest-first (persisted in `ProjectPreferences`), and creating a project opens it.
  **(8) Block visuals:** design tokens (`-bm-*`) in `blocks.css` as the single source of colour;
  `BlockCategory#styleClass` drives a per-category accent; the "Your code goes here" badge is now the loudest
  thing in the header (was 10px teal-on-purple, after a spacer) plus a block-level accent; read-only reads as
  flat/desaturated. The comment block's text now fills the block — the cause was a broken hgrow chain in
  `HeaderLayoutBuilder` (its unconditional greedy spacer starved the field), latent in *every* `withCustomNode`
  + delete header. Dead theme code removed (`ColorPalette.forCategory` had no callers; `hexToRgb`/`adjustBrightness`
  were stubs returning the literal `"..."`). The explorer group and `FileRole.GENERATED`'s badge no longer say
  "read-only", which would contradict the editable `run()` body on screen.

- **2026-07-15 — Generated scaffolding is actually locked; method-level locks; new/recovered files open without a restart.**
  Follow-up to the entry below, reversing one of its decisions after using it.
  **(1) `GENERATED` is now as inert as `LIBRARY`** (`FileRole.suppressesInteraction`). Letting generated blocks
  stay interactive while silently dropping the edits at the compile-time flush reads as data loss, not as a
  lock: the edit appears to work, survives until the next reload, then vanishes. If it can't be saved, don't
  offer it. `ClassBlock` also stops rendering "+ Add Function", member drop zones and drag-to-reorder when
  read-only, and `MethodDeclarationBlock` swaps every signature control (name field, return-type selector,
  param pills, delete button) for plain labels — `ReadOnlyDecorator` only styles a node, so the header's own
  controls were leaking write access regardless. Covered by `ui/fx/LockedMethodRenderingTest` against the real
  scene graph.
  **(2) The template is persisted** (`StudioProjectSettings.template`, seeded by `ProjectCreator.seedSettings`,
  resolved once at open into `ProjectState.getTemplate()`). Needed because `FileRole` locks the entry point
  only for `GAME_BOT` — an `EMPTY` project's `main` is the user's only file. Also retires
  `ProjectRepair.looksLikeGameBot` guesswork to a legacy-only fallback. **Game bot is now the default template.**
  **(3) `project/MethodLock`** — the method-level counterpart to `FileRole`, for the case a file-level verdict
  can't express: `Bot.supervise(GameLoop::run, GoHome::run, Startup::run)` binds those as `Runnable`s, so the
  *signature* is scaffolding while the *body* is the whole point. `SIGNATURE` for those hooks, `FULL` for an
  activity's `isEnabled()`, plus a header badge naming which method is the user's ("Your code goes here").
  **(4) Activity stubs have no constructor** — the SDK's `Activity` gained a no-arg ctor naming the activity
  after its class, so the stub asks for nothing but `run()`. **Needs an SDK release before bots on a released
  SDK can use it.**
  **(5) `static { }` renders** (`blocks/misc/InitializerBlock`): JDT models it as an `Initializer`, which
  `parseRoot` handled nowhere, so `Activities.java`'s JSON loader was dropped from the tree entirely — and
  since `ClassBlock` rewrites from block state, an edit could have deleted it for real.
  **(6) `Activities.X` renders as a field access, not plain text.** The `QualifiedName` branch required
  binding resolution; unresolved bindings are routine (a sibling generated file may not be compiled yet), and
  the fallback rendered inert source text. Now falls back syntactically. `UnknownExpressionBlock` stays the
  terminal fallback.
  **(7) New/recovered files open immediately.** `CodeEditorService.switchToFile` resolved only against
  `openFiles`, populated once at project open, while `ActivityService`/`ProjectRepair` write straight to disk —
  so a new activity showed in the tree and refused to open until a restart. A map miss now means "not loaded
  yet": load from disk. Regenerated `Activities`/`ActivityRegistry` are evicted on `ActivitiesChangedEvent` so
  they don't render stale.
  **(8) Recover closes its gaps** — `Activities.java`, `ActivityRegistry.java` and `activities.json` are now
  checked (the explorer's delete dialog promised Recover could bring `Activities.java` back; it couldn't), and
  `ProjectRepair`'s private copy of the empty-project entry point — which had drifted and lost an import, so a
  recovered project didn't compile — is gone in favour of `ProjectCreator.sourcesFor`.

- **2026-07-15 — File roles (user vs generated), New Activity, project recovery, explorer layout, `extends` + method refs.**
  **(1) `project/FileRole`** is now the single source of "may the user change this?" — `EDITABLE` /
  `GENERATED` / `LIBRARY`, replacing inline path checks in `CodeEditorService.refreshUI` that the explorer
  didn't share (which is how `ActivityRegistry.java` was read-only in the editor but silently deletable from
  the tree). ~~**`GENERATED` is deliberately not `LIBRARY`:** its blocks stay interactive and visually editable,
  but the edits are in-memory only.~~ **Superseded by the entry above — generated files are now fully inert;
  interactive-but-discarded edits read as data loss.** Persistence is still enforced in
  **`CodeExecutionService.compileAndWait`**, the *only* place edited source reaches disk (the editor never
  writes as you type). `GENERATED` = entry point (game-bot only, see above) + `ActivityRegistry` +
  `Activities` + `GameLoop`; `GoHome`/`Startup`/activity stubs stay editable files.
  **(2) `MacroLoop` → `GameLoop`** throughout the template.
  **(3) Explorer reworked** (`FileExplorerManager`): `TreeView<Path>` → `TreeView<ExplorerNode>` (a path can't
  model a synthetic group header), split into **"Your files"** / **"Generated (read-only)"**, delete now
  confirms for generated files, and it subscribes to `ActivitiesChangedEvent` so new activities appear.
  **(4) "New Function Library" → "New Activity"**, delegating to the existing `ActivityService.update` (which
  regenerates the registry + stub) instead of the old hardcoded `static void action()` writer.
  **(5) Explorer drag bounded** — `UIManager.clampExplorerWidth` clamps the `mainSplit` divider to
  150–460px. The node keeps `maxWidth = MAX_VALUE` on purpose: capping *the node* (what `960d01d` removed) is
  what leaves dead space beside the tree; the divider is the thing that needed a bound.
  **(6) `Project ▸ Recover Project Files`** (`project/ProjectRepair`) regenerates scaffolding deleted outside
  the Studio — only ever creating what's absent, never overwriting. `ProjectCreator.gameBotSources` now
  returns `fileName -> source` so recovery reuses the templates rather than duplicating them. Note the chosen
  `ProjectTemplate` is **not persisted**, so game-bot-ness is *inferred* (`Bot.supervise` in main, or any
  scaffold file present).
  **(7) `ClassBlock` shows inheritance** (`Class: Mining extends Activity`) — `getSuperclassType()` was never
  read, so every generated activity stub hid its superclass.
  **(8) `supervise()` empty-parens fixed.** `MethodReferenceBlock` models `GameLoop::run`; more importantly
  `BlockConverter.dispatchExpression`'s bare `return Optional.empty()` fallback (fed through
  `.ifPresent(block::addArgument)`) **silently dropped** unmodelled arguments — a rewrite from block state
  could then delete them for real. It now emits an `UnknownExpressionBlock` rendering the source verbatim, so
  no expression is ever invisibly dropped again.

- **2026-07-15 — Object-capture wand rebuilt on OpenCV GrabCut.**
  The pure-Java flood-fill wand (`ui/app/capture/MagicWand`) is replaced by OpenCV **GrabCut**: drag a box →
  solve, then left/right-drag to paint definite foreground/background and re-solve from retained GMM models
  (`MagicWand.Session`). Studio gains its first OpenCV dependency — `org.openpnp:opencv:4.9.0-0`, the same
  artifact/version the SDK pins — plus a Studio-local `OpenCvNative.ensureLoaded()` mirroring the SDK's loader.
  The old flood had unbounded neighbour-relative colour drift, an edge gate coupled to the tolerance slider,
  and a `maxPixels` truncation bug that marked pixels outside the reported bbox. Output contract is unchanged
  (bbox-cropped ARGB whose alpha becomes the runtime `matchTemplate` mask) except the boundary is now
  **feathered** instead of forced opaque — a hard rim baked background-blended pixels into the template.
  `ObjectCaptureSurface` moves from hover+wheel to drag→refine→accept, solving off the FX thread.
  Note: the loader must sit on the nested `Session` class — instantiating a nested class does not run the
  outer class's static initializer.

- **2026-07-14 — Bot lifecycle scaffolding + two-tier Activities + "Game bot" template.**
  **(1) New "Game bot" project template** (`ProjectTemplate` enum; picker in `ProjectSelectionScreen`;
  `ProjectCreator.createGameBotFiles`): scaffolds a supervised entry point (`Bot.supervise(MacroLoop::run,
  GoHome::run, Startup::run)`), a `MacroLoop` that dispatches over `ActivityRegistry.ALL` + calls
  `Watchdog.checkpoint()`, editable `GoHome`/`Startup` recovery hooks, and an initial empty `ActivityRegistry`.
  Relies on the new SDK `com.botmaker.sdk.api.bot` (`Bot`, `Watchdog`, `Activity`, `BotStuckException`) — see
  `../botmaker-sdk/ROADMAP.md`. `SdkApi.FACADE_CLASSES` gains `Bot`, `Watchdog`.
  **(2) Activities are now two-tier.** `ActivityDefinition` (name + enable flag + description + its own
  `params`) alongside free-standing `globals`; `ActivitiesConfig` becomes `{activities, globals}` with
  back-compat read of the old flat shape (migrates to `globals`) and `allVariables()` flattening
  (enable-flag `Activities.<Name>`, params `Activities.<Name>_<param>`, then globals) consumed by thext
  generator and the expression menu (`ProjectAnalyzer.getActivityVariables`).
  **(3) `ActivityService` generates a registry + stubs.** Besides `Activities.java` it now writes a
  read-only `ActivityRegistry.java` (`List<Activity> ALL` of `new <Name>()` — replaces a hand if-chain) and
  creates a once-only editable `activities/<Name>.java` `Activity` subclass per activity (never overwritten).
  `CodeEditorService` marks `ActivityRegistry.java` generated/read-only. `ManageActivitiesDialog` (activities
  + per-activity params + globals) and `SetActivityValuesDialog` (enable toggles + values) reworked.

- **2026-07-14 — Fixes: overlays truly above fullscreen + contour-aware object capture.**
  **(1)** `OverlayToolbars.promoteAboveFullscreen(Stage)` now re-asserts on a **~750 ms `Timeline`** (stopped when
  the stage stops showing) in addition to the focus listener, so overlays stay above a fullscreen app that
  re-raises/re-fullscreens itself. Paired with the shared-side remap fix (the WM only re-reads the notification
  window type on remap), overlays now sit above fullscreen games (e.g. Firestone). **(2)** `MagicWand` rewritten
  from a plain colour-distance BFS into a **shape-aware** pipeline: precomputed Sobel edge map (built once per
  frozen frame in `ObjectCaptureSurface`) gates the flood so it stops at the object's contour instead of leaking
  across gradients; neighbour-relative colour tolerance; interior-hole fill (background flood from the box border)
  so textured objects come out solid; 1-px dilation to kill the transparent halo. New `MagicWandTest`.

- **2026-07-14 — Feature batch: overlays-above-fullscreen, Resources toolbar button, dropdown-driven favourites,
  "Capture object" transparent extraction, resolution dropdown + readouts.**
  **(1)** Overlays now stay above fullscreen games: `OverlayToolbars.promoteAboveFullscreen(Stage)` tags each
  ownerless always-on-top stage (Overlay Editor, capture toolbar, `CaptureSurface`, `ObjectCaptureSurface`) with a
  unique title and calls the new shared `NativeController.promoteOverlayAboveFullscreen` (X11 EWMH; best-effort).
  **(2)** New **🗂 Resources** toolbar button (`ToolbarManager` + `UIManager.openResourceManager`).
  **(3)** `ProjectSettingsDialog` favourite-methods/overloads are now **dropdown-driven** (no manual typing):
  class from `SdkApi.FACADE_CLASSES`, methods/overloads from `ProjectAnalyzer.getMethods` keyed by
  `MethodSignature.signatureKey()`; disabled with a hint until the SDK jar is indexed. `UIManager` now stores
  `ProjectAnalyzer` and passes it in. **(4)** New **◎ Capture object** mode: `ObjectCaptureSurface` shows a frozen
  window snapshot; hovering runs a pure-Java `MagicWand` flood-fill (bounded), the mouse wheel steers colour
  tolerance (bigger/smaller object), click extracts the region onto a transparent background and saves it as a
  template. **(5)** Standard resolution is now a **dropdown + landscape/portrait toggle** (`ResolutionChoices`) in
  Project Settings and the new-project dialog (default 1920×1080 landscape, seeded into `settings.json` +
  `botmaker-project.properties` by `ProjectCreator`/`ProjectSettingsService` so runtime scaling matches). Current
  window/screen resolution readouts added to the Overlay Editor header, capture toolbar, IDE toolbar, and the Debug
  Dashboard page.
- **2026-07-14 — Run now validates blocks (empty slots) via `BlockValidator`, surfaced in the Errors panel with
  click-to-scroll.** `CodeExecutionService.runCode` calls the new `DiagnosticsManager.validateBlocks()` (built on
  `BlockValidator.emptySlots(nodeToBlockMap)`) before compiling; unfilled slots (a `NullLiteral`/`NullBlock`) now
  publish `DiagnosticsUpdatedEvent` (previously never published — the Errors tab was dead), marking blocks red and
  aborting the run with a friendly status instead of a raw `javac` error. Clicking an error now `scrollToBlock`s
  the canvas to and highlights the offending block (`UIManager`, reusing `BlockHighlightEvent`), replacing the old
  no-op `requestFocus`. Active-file scope, matching the existing diagnostics limitation.
- **2026-07-14 — Overlay/capture/settings batch: invisible-on-capture, desktop targets, category palette,
  overload nav, inline empty-slot validator, project settings + capture previews.** Broad UX/correctness pass.
  **(A)** The Overlay Editor now hides itself (and its config popover) while any capture draw surface is up:
  `ScreenCaptureService` fires a process-wide `CaptureOverlayListener` from `overlayStage(...)`;
  `ProgramShapeOverlay` subscribes and `hide()`s the HUD (guarded so its close handler doesn't tear down) /
  dims the modal-owning config popover to opacity 0. **(B)** Removed the **⏺ Record Macro** toolbar button
  (recording still lives inside the overlay); dropped `ToolbarManager.onRecordMacro` + `UIManager.openMacroRecorder`.
  **(C)** Capture Templates + Overlay Editor now accept **desktop/monitor** targets, not just windows — new
  `ScreenCaptureService.captureDefaultTargetAsync` (target-agnostic grab); window keeps raise+resize, screen/
  desktop uses native bounds; recording stays window-only (disabled otherwise). **(D)** `ManageCaptureTargetsDialog`
  rows now show a **live thumbnail + exists/not-found badge** (new `ui/app/capture/TargetThumbnail`, off-thread,
  cached); a newly added source becomes default and **double-click** sets default; `apply()` now derives from
  `current()` so it no longer clobbers favourite overloads/methods/resolution. **(E)** `ActivityVariable` gains a
  **description** (4th record component, back-compat ctor); Manage Activities has an editable Description column
  emitted as a field Javadoc. **(F)** New **Project → Project Settings…** (`ui/app/ProjectSettingsDialog`):
  reference resolution, favourite overloads (view/remove), and a new `favoriteMethods` (class→methods) field on
  `StudioProjectSettings`. **(G)** The overlay palette is now a **hover-expanding SDK category bar**
  (`buildPaletteBar`/`facadeMenuButton`): each facade chip fans out methods → overloads (favourites first); fresh
  calls default to the **fewest-argument overload** (`MethodSignature.fewestParams`, applied in
  `StatementFactory`) or the project favourite; a picked overload is applied post-insert via `pendingOverload`.
  **(H)** The ⚙ config popover gained an **overload selector** and now edits **every** parameter (generic
  expression menu when no special picker applies) via `MethodInvocationBlock.{overloadSignatures,currentSignature,
  switchToOverload}` and the shared `ExpressionMenuFactory.applySelection`. **(I)** **Arrow-key navigation** of the
  compact rows (→ step in, ← step out, ↑/↓ move, Enter configure). **(J)** New pre-compile `validation/BlockValidator`
  flags **empty required slots** (`NullLiteral`); empty args now render **red** in the overlay rows and in
  `NullBlock` on the canvas. (`ErrorTranslator` kept — it is actively used by the Errors panel and block-error
  tooltips, not a dead relic.)
- **2026-07-12 — Overlay Editor v2: Basic/Advanced modes, translucent HUD, merged macro recorder.** Extended
  `ui/app/overlay/ProgramShapeOverlay`. **(1)** Palette now has a **Basic/Advanced** toggle — Basic keeps the six
  `BlockCatalog.botActions()` buttons; Advanced adds an "＋ Add block" button opening the full categorized
  `ExpressionMenuFactory.createStatementMenu` (control flow, variables, print, functions, comments). **(2)** The
  window is now a **translucent HUD**: `StageStyle.TRANSPARENT` stage + `Color.TRANSPARENT` scene with rounded
  semi-opaque panels (header/controls/tree) and gaps that show the app beneath; borderless, dragged by the header
  via `OverlayToolbars.installDrag`; rows restyled for the dark panel. **(3)** **Merged the macro recorder in**:
  a new headless `services/record/RecordingSession` (extracted from the retired `MacroRecorder`) drives a ●
  Record / ⏸ Pause / ■ Stop control set; Stop translates via `MacroTranslator` and inserts the blocks **at the
  cursor** progressively (reusing the `pendingInsert`/`onBlocksUpdated` re-home path), with the auto-fill popover
  suppressed for the batch. Clicks on the overlay itself are excluded from a recording (screen-bounds exclusion
  rect). Record is disabled with a tooltip off Linux/X11. Deleted the standalone `MacroRecorder` +
  `ui/app/record/MacroRecorderToolbar`; `UIManager.openMacroRecorder` now opens the overlay in record mode
  (`open(..., startRecording=true)`). `MacroTranslator` + its test unchanged.
- **2026-07-12 — Overlay Editor rework: true window overlay, non-empty program list, top action palette,
  auto-fill toggle.** Reworked `ui/app/overlay/ProgramShapeOverlay` per user feedback. **(1)** Fixed the
  always-"Program is empty" bug — `render()` filtered bodies with `!isNested()` (child of *any* block), which
  every body is; now uses `isNestedInBody()` (nested inside *another* `BodyBlock`) so method bodies become
  render roots and control-flow bodies are still drawn by the recursion. **(2)** It's now a **true overlay**:
  `open(...)` gates on a default **window** target (warns like Capture/Record otherwise) and, off the FX thread,
  raises the window + snaps it to the reference resolution (reusing `ScreenCaptureService.raiseWindow` /
  `resizeTarget`, seeding the resolution like `OverlayTemplateCapture`), then positions itself inside the
  window's top-left. **(3)** The insert palette moved to the **top** and is restricted to the six core bot
  actions (`BlockCatalog.botActions()`) as a wrapping button row — the free-text SDK-method search was removed.
  **(4)** New **"Fill arguments after adding"** checkbox (on by default): after a re-parse the just-inserted
  block is located by stable DFS body-ordinal + slot, the cursor re-homes onto it, and — when the toggle is on
  and it's a `MethodInvocationBlock` — its `openConfig` argument popover opens automatically. Wiring:
  `UIManager.openOverlayEditor` now passes `projectSettingsService` + `screenCaptureService`.

- **2026-07-12 — Overlay authoring system (Phase 2): program-shape editor + insertion cursor + method
  palette.** New **"⧉ Overlay Editor"** toolbar button opens `ui/app/overlay/ProgramShapeOverlay` — a small,
  always-on-top, independently-minimizable window that mirrors the program's shape as a compact,
  clickable/scrollable list of one-line rows built by walking the live `CodeBlock` tree (no second renderer).
  An **insertion cursor** (`project/InsertionCursor`, held on `ProjectState`) marks the focused block; the
  two-row toolbar's **step / step-into / step-out** buttons move it (`services/CursorNavigator`, pure +
  unit-tested in `CursorNavigatorTest`) and **＋ Add below** / the palette insert just beneath it. An
  always-visible **searchable method palette** lists every insertable SDK method — curated bot-actions plus all
  facade static methods (vision included, via `ProjectAnalyzer.getMethods`) — and inserts the pick as an ad-hoc
  `BlockType.LibraryCall` below the cursor. Per-row **⚙ config** button opens the existing argument pickers
  (`PickerRegistry`: draw-a-`Rect`, pick/capture `ImageTemplate`/`ImageTemplateGroup`, `CaptureSource`/`Window`
  chooser) so args are filled without leaving the overlay. Supporting changes: `IfBlock` now implements
  `BlockWithChildren` (so traversal/step-into reach its bodies — the only body-block that lacked it);
  `CodeEditorService` caches + exposes the last rendered root (`getRootBlock()`); `MethodInvocationBlock` exposes
  its arg blocks / scope / resolved param types for the config popover. **Deferred:** per-row overload toggle
  (methods are switchable via palette re-insert / inline editor); context-filtering the palette by cursor-valid
  types; config popover refresh after multi-arg edits (single-arg draw works).
- **2026-07-12 — Capture-overlay & macro-recorder fixes + vision-menu cleanup (Phase 1).** Both floating
  mini-toolbars (capture templates + record macro) are now **single-instance** (re-pressing the button focuses
  the live one), **draggable**, and **ownerless** so Studio can be minimized without the overlay vanishing —
  centralized in a new `ui/app/overlay/OverlayToolbars` helper (also drops `initOwner` on `CaptureSurface`).
  The **target window is now raised/de-iconified** before capture and before recording: `ScreenCaptureService`
  resolves minimized windows (`getAllWindows(true)`), calls `restoreWindow`, and shared `LinuxController` now
  also sends an EWMH `_NET_ACTIVE_WINDOW` request (raises on WMs that ignore bare `XRaiseWindow`). **Keyboard
  recording fixed:** keysyms are resolved on a dedicated X connection (not the one blocked inside
  `XRecordEnableContext`), which had silently dropped every keystroke while mouse worked; numpad digits mapped.
  The **"Vision" statement-menu category removed** (find/click/wait retargeted to Input as bot-actions;
  `Find Image → Do Actions` unlisted but its `LambdaCallBlock` impl kept for the Phase-2 overlay palette).
  **Lambda SDK block "englobes" its body** — the purple frame moved to the outer container so it wraps header +
  action body (`blocks.css` `.sdk-call-block`/`.sdk-lambda-body`). **Main toolbar wraps to two rows** when the
  window is narrow. **Capture resolution normalization:** a project `referenceResolution` (new
  `StudioProjectSettings` field, seeded from the window's size on first capture) snaps the target window to a
  canonical size before each capture (`ScreenCaptureService.resizeTarget`), avoiding lossy match-time scaling.
  Committed to **X11 only**: removed dead xdg-desktop-portal ScreenCast token plumbing (kept the fail-fast
  `ForceX11Notice` guard and the blank-frame CLI fallback). **Deferred → Phase 2:** the overlay program-shape
  (compact clickable blocks), insertion cursor + step/step-into/step-out, block config buttons (rect pickers),
  and the searchable context-aware SDK method palette.
- **2026-07-11 — Macro recorder v1 (Linux/X11): record real input → blocks.** New "⏺ Record Macro" toolbar
  button (next to Capture Templates; disabled off-Linux) opens a floating mini-toolbar over the project's
  default **window** target (Record / Pause / Stop & Insert + live action counter). Real clicks & keystrokes
  are observed globally and passively via the new shared X11 XRecord listener (`botmaker-shared`
  `input.InputListener` / `InputEvent`), buffered while recording, and on Stop translated by the pure,
  unit-tested **`services/record/MacroTranslator`** into leaf blocks appended to the bot's main method:
  left click → window-relative `Mouse.click(CaptureSource.window("…"), x, y)`, printable keys coalesced into
  `Keyboard.type("…")` (Backspace edits), named keys → `Keyboard.tap(Key.X)`, wheel → `Mouse.scroll(±n)`,
  idle gaps → `Wait.milliseconds(n)`. Clicks outside the window (incl. on the toolbar) are dropped; left
  drags are suppressed. Blocks are inserted one per FX pulse (`services/record/MacroRecorder`) so each
  re-parse lands before the next. **Deferred:** right/middle/double click & drag (no window-relative
  overload), if/loop-from-template + nesting/step-cursor, live insertion, and the Windows
  (`SetWindowsHookEx WH_*_LL`) listener. 7 new `MacroTranslatorTest` cases.
- **2026-07-11 — Template capture reworked into a true overlay with single & batch modes.** The "✂ Capture
  Templates" tool no longer covers the window with a mouse-grabbing pane (which blocked clicks to the app
  underneath). It now shows a small always-on-top **mini-toolbar** (`Capture one` / `Capture many` / `Close`)
  that never covers the window, so the target app stays fully clickable to navigate to the screen to capture.
  The rubber-band **`CaptureSurface`** (new, `ui/app/capture`) is shown only *during* a draw, then dismissed:
  `Capture one` → draw one region → name → save; `Capture many` → draw several (each numbered) → Done → a
  single **`BatchTemplateNamingDialog`** (thumbnail + name + Discard per row) names/discards them all, saving
  from one fresh window snapshot. Naming rule centralized as `ImageTemplateLibrary.sanitizeName`; batch
  uniqueness checks against both disk and the other names in the batch. `ScreenCaptureService.toFxImage` is now
  public for thumbnail reuse. `OverlayTemplateCapture` is now the toolbar orchestrator; still window-target only.
- **2026-07-11 — SDK-alignment pass: find-blocks get SDK chrome, menu reorg, javadoc button, Game pickers, RPM identity.**
  - **Find-lambda blocks now render like the SDK block** (`blocks/vision/LambdaCallBlock`): a `🤖 SDK` badge,
    an `ImageFinder` class chip, a **method dropdown** (ifFind/whileFind/untilFind × single/any/all — replaces
    the old ⚙ variant picker and the plain-English "while … is visible" wording), a `→ boolean` return badge
    for the `if…` forms, and the `?` help button — plus the droppable action body. Switching method still goes
    through `switchLambdaVariant` (rewrites in place, preserving the body); the generic overload path is
    deliberately not reused for lambda calls (it syncs args positionally and would clobber the trailing lambda).
  - **Statement-menu reorg** (`palette/BlockCatalog`, `BlockCategory`): Game launch blocks promoted to the
    top-level bot actions (no "Game" submenu); the three "If/While Image Exists" / "Repeat Until…" entries
    replaced by a single **"Find Image → Do Actions"** entry (its method dropdown covers the variants);
    **"Wait"** is now a standard SDK `Wait.milliseconds` block with the overload picker instead of a raw
    `Thread.sleep` (existing `Thread.sleep` bots still round-trip via `WaitBlock`).
  - **Method block layout** (`MethodInvocationBlock` + `BlockUIComponents`): the return-type badge and the
    explanation button moved to **after** the argument list (far right); the info icon changed `ⓘ` → `?`.
  - **Game parameter pickers** (`pickers/PickerContext`, `PickerRegistry`): the Browse (executable) and Steam
    cover-art (appId) pickers now also fire for `launchIfNotRunning`/`launchAndWait`/`launchSteamIfNotRunning`
    at the new first-parameter positions; the new `CaptureSource` window-detection args pick up the existing
    type-based capture-source picker automatically.
  - **RPM/DEB identity** (`pom.xml`): pinned `<linuxPackageName>botmaker-studio</linuxPackageName>` on both
    Linux packages so successive installs upgrade in place rather than co-installing. NOTE: the residual
    "app disappears from the menu after an upgrade" is jpackage's known `%postun`-after-`%post` ordering bug;
    the definitive fix needs a custom RPM spec resource + a real RPM upgrade test (CI/Linux host), not yet done.

- **2026-07-11 — Local-dev fix: editor no longer serves a stale SDK from the per-jar type cache.**
  - **Root cause:** `index/TypeSummaryManager` keyed its ClassGraph `.json` cache purely by jar file *name*
    and reused it whenever the file existed. A reused `0.0.0-SNAPSHOT.jar` (overwritten in place on every
    local SDK rebuild) kept the same name, so the cache was never regenerated — palette/autocomplete/menus
    showed a days-old SDK API even though the pom and runtime jar were correct.
  - **Fix:** new `isCacheFresh(jar, cacheFile)` mtime check gates both `buildOrLoad` and `refresh`; a cache
    older than its jar is treated as missing and re-indexed. `refresh` also drops an in-memory entry whose
    on-disk jar has changed (live-session correctness). Verified end-to-end: stale cache → re-index; fresh
    cache → reused. Released (uniquely-named) versions are unaffected.
  - **Hardening:** `services/MavenService.buildRemoteRepositories` now disables snapshot fetching on every
    remote (jitpack/central/google) so a local SNAPSHOT SDK/shared can never be shadowed by a remote fetch.
  - **Diagnostic:** `project/BotProject.open` logs the resolved SDK jar's `Build-Time`/`Implementation-Version`
    manifest stamp (see the SDK ROADMAP) so "which SDK build did the editor index?" is answerable at a glance.
- **2026-07-11 — Windows-testing fixes: progress bar, capture-source order, Report Issue, packaging.**
  - **Real-percentage dependency progress** on first project open. New `services/ProgressReporter` (fraction
    + message) replaces the `Consumer<String>` progress sink through `MavenService.resolveClasspath` →
    `BotProject.open` → the `BotMakerStudio` loading screen (bar bound to the open `Task`). `MavenService`'s
    Aether `TransferListener` now aggregates bytes across concurrent transfers (`DownloadAggregator`) into a
    real fraction; the status line shows `… — NN%`.
  - **Capture-source picker reordered** (`ui/app/capture/CaptureSourcePicker`): Desktop → Monitors → Windows,
    so the common picks aren't buried below the (long) window list.
  - **Help ▸ Report Issue…** (`ui/app/ReportIssueDialog`, wired in `MenuBarManager`): title + description +
    optional screenshots → files a GitHub issue on the umbrella repo `LiQiyeDev/botmaker`. No token stored:
    reuses the existing `GitHubAuth` sign-in to POST directly, else opens a prefilled browser New-Issue page
    (browser session authenticates). Screenshots → opens the issue in the browser to drag them in (GitHub's
    issue API has no attachment upload). New `GitHubConfig.ISSUE_OWNER/ISSUE_REPO` + `issuesApiUrl`/`newIssueBrowserUrl`.
  - **Auto-update actually installs** (`services/UpdateService`): AppImage self-update (swap `$APPIMAGE` in
    place, no root) when running as one; `.rpm`/`.deb` installed under one `pkexec` prompt via the native
    package manager (was: only downloaded, user finished manually in the store). `preferredExtensions` now
    knows `.AppImage` (preferred when running as an AppImage).
  - **Packaging** (`pom.xml`, `.github/workflows/release.yml`, `.github/scripts/*`, `flatpak/`): CI now also
    emits **AppImage** (recommended password-free channel; solves the Fedora "missing from search after
    install/update" issue), **tarball**, and a best-effort **Flatpak** (sandbox limits capture/input — see
    the manifest caveat). `.rpm`/`.deb` get **GPG signing** under the "LiQiyeDev" identity (gated on
    `GPG_KEY_ID`/`GPG_PRIVATE_KEY`/`GPG_PASSPHRASE` secrets — unsigned until configured) and a Development
    menu category. **Needs a validation release to confirm the CI wiring.**

- **2026-07-10 — Dedupe v-prefixed SDK versions in the version dropdowns.** `JitPackSearch.parseVersions`
  now collapses tags that differ only by a leading `v` (`services/JitPackSearch.dedupeVPrefix`), so the SDK
  repo's historical bare `1.0.x` tags and `release.sh`'s new `v1.0.x` tags no longer show as duplicate
  choices (v-prefixed preferred) in New Project + Manage Libraries.

- **2026-07-10 — Live overlay template capture + resolution sidecars.** New toolbar button "✂ Capture
  Templates" opens `ui/app/capture/OverlayTemplateCapture`: a transparent, always-on-top overlay over the
  default **window** target (not a screenshot) with a Draw region / Finish button; rubber-band a rect over the
  live window and it re-captures fresh window pixels (occlusion-safe), crops (HiDPI-correct by ratio), prompts
  a unique non-blank name, and saves. Multi-capture until Esc/Finish. Every capture now writes a `<name>.json`
  resolution sidecar (`ImageTemplateLibrary.TemplateMetadata` + `saveTemplate`/`exists`/`sidecarFor`), consumed
  by the SDK for per-template rescaling. Retrofitted the two existing capture paths (block `ImageTemplatePicker`
  + `ResourceManagerDialog`): empty default name, duplicate-name blocking (re-prompt via
  `ImageTemplatePicker.promptTemplateName`), sidecar written, and rename/delete keep the sidecar in sync.
  `ScreenCaptureService.captureRegion` now also reports the capture source's physical resolution.

- **2026-07-10 — Phase 5: Activity generation is startup-safe.** The generated `Activities` class no longer
  rethrows as `ExceptionInInitializerError` when `activities.json` is malformed/unreadable — it logs and keeps
  type defaults. `ActivityType.TIME`/`DATE` now emit defensive `parseTime`/`parseDate` helpers (generated only
  when used) so a present-but-invalid or wrong-type node defaults instead of throwing `DateTimeParseException`
  at bot launch. Missing file / missing key already defaulted via `MissingNode` + `asX(default)`. Tests:
  `ActivityGenerationTest` compiles the generated source in-memory and loads it against missing-file /
  missing-key / wrong-type / malformed-JSON fixtures, asserting defaults and no init crash.

- **2026-07-10 — Phase 4: Community patching (fork + PR upstream).** `BotPublisher.submitPatch(projectDir,
  origin, title, body)` forks the installed bot's origin repo (`BotSource` provenance), pushes the current
  project snapshot onto a fresh `botmaker-patch-<ts>` branch via the same Git Data API tree-push as `publish`
  (factored into a shared `buildTreeCommit`), and opens a PR against the origin's default branch — returning
  the PR URL. `VcsDialog` shows a **"Submit patch…"** button only when the project has upstream provenance;
  it prompts for a PR title, runs off the FX thread, and opens the resulting PR in the browser.

- **2026-07-10 — Phase 4: Linear VCS via JGit.** New `project/vcs/ProjectVcs` — a JGit-backed, single-branch
  (no branches) history facade per user project: `init`/`ensureInitialized` (writes `.gitignore`, initial
  commit; lazy-migrates existing projects), `commit` (stages adds + tracked deletions, no-ops when clean),
  `history` (newest-first, tags surfaced per commit), `tagPrivate`/`tagPublic`, and a **reflog-safe
  `restoreTo`** (snapshots pending work, then re-lands the target commit's content as a new commit — nothing
  lost). `ProjectCreator` inits the repo on create. UI: `ui/app/VcsDialog` (Project ▸ **Project History…**,
  wired via `MenuBarManager.setOnShowHistory` → `UIManager`) with commit-message + Commit, Tag (private/public),
  and Roll-back-to-selected, all off the FX thread. Dep: `org.eclipse.jgit:6.10`. Tests: `ProjectVcsTest`.

- **2026-07-10 — Phase 4: Gallery listing is now opt-in.** `BotPublisher.publish(...)` takes a
  `listInGallery` flag; when off it creates the repo + release but skips the discovery topic + index
  submission (`galleryStatus` = "Published privately…"). `PublishDialog` adds a **"List in the public
  gallery"** checkbox (default on) — so a private release no longer auto-lists; only an opted-in publish does.

- **2026-07-10 — Phase 4: Unpublish UI.** `PublishDialog` gained an **Unpublish** button (enabled when
  signed in; disabled while busy) that calls the already-implemented `BotPublisher.unpublish(repo)` off
  the FX thread — delist-only (repo + releases left intact): maintainer commits the `index.json` removal
  directly, others fork + PR it. Confirmation dialog + status surfaced from the returned outcome string.
  Tests: `BotPublisherIndexTest` now covers `removeEntry` (drop/keep-others/no-op/case-insensitive).

- **2026-07-10 — Phase 3: Pickers, palette/menus, block aesthetics & constraints.**
  - **Declaration blocks now use the specialized pickers.** `VariableDeclarationBlock` routes a non-list
    initializer through `PickerRegistry` keyed on the declared type — so `ImageTemplate`/`Rect`/`Point`/
    enum declarations get their thumbnail/region/dropdown editor instead of a raw expression node (fixes
    "ImageTemplate declaration missing picker"; the Rect region-select normalizes to the 4-int ctor so a
    `Rect(Point,Point)` no longer sticks).
  - **MouseButton empty-parens fix.** `EnumPicker.resolveEnum` now returns null when the resolved enum has
    **no** constants, so an unindexable SDK enum falls back to the generic pill (preserving the value)
    instead of rendering an empty dropdown that wipes the arg to `()`.
  - **Menus:** the **Vision submenu is gone** (find/click/wait stay promoted flat at top; the lambda vision
    blocks moved to Loops/Logic; `BlockCategory.VISION` retained but empty), and **Game now sits right after
    Control** (so "Launch …" follows "Wait (ms)"). **"Add Constructor"** removed from `ClassBlock`.
  - **ImageTemplateGroup picker** gained a **"Capture new…"** item (shared `ImageTemplatePicker.captureAndSave`).
  - **Default non-deletable template.** `ProjectCreator` writes a generated `default_template.png` into every
    new project's images root; `ImageTemplateLibrary.isDefaultTemplate`/`DEFAULT_TEMPLATE_PATH` guard it from
    rename/delete in `ResourceManagerDialog`, and `BlockCatalog.DECLARE_TEMPLATE` now seeds that path so a
    fresh `ImageTemplate` compiles immediately.
  - **`Direction` de-duplicated:** dropped the hardcoded `DECLARE_DIRECTION` catalog block; a Direction var is
    now declared via the generic type flow, seeded from the index-resolved first constant
    (`InitializerFactory`) and edited with the `EnumPicker`.
  - **break/continue placement validated.** `CodeEditorService` rejects dropping/moving a `break`/`continue`
    outside an enclosing loop (`break` also allowed in `switch`), surfacing a `StatusMessageEvent`.
  - **Prettier instantiation blocks** (`blocks.css`: gradient fill, soft shadow, tinted ⚙ constructor button).
  - *Deferred (ambiguous UX, needs a design call):* per-statement-menu-element method submenus, and an
    explicit `Rect(Point,Point)`↔`Rect(Point,Size)` overload-chooser widget (region-select already normalizes
    to the 4-int form). "Pick all" still lives on the whole-call button only.

- **2026-07-10 — Phase 2: Studio blocks aligned to the boolean/int + `VisionContext` SDK.** The SDK vision
  API now returns `boolean`/`int` (find/click → boolean, findAll/clickAll → int) and stores the
  `MatchResult` in `VisionContext`. Studio mirror updated:
  - `palette/SdkApi.FACADE_CLASSES`: added **`VisionContext`** (so bots can read `getLastMatch()` etc.),
    removed **`Screen`** (no longer a user-facing `CaptureSource` facade).
  - New `Initializer.StaticCall` variant (+ `StatementFactory` builder) so seeded declarations can emit a
    static call; `BlockCatalog.DECLARE_MATCH` now seeds **`VisionContext.getLastMatch()`** instead of `null`
    (a `find(...)` seed no longer type-checks against a `MatchResult` variable).
  - `MethodInvocationBlock`: a **`→ ReturnType` badge** on SDK call blocks (`return-type-badge` in
    `blocks.css`), resolved from the current overload's return type so it flips `→ boolean`↔`→ int` when the
    user switches `find`↔`findAll`. The method dropdown + class selector + ⚙ overload picker already provide
    the find-family switching (with method-name preservation in `switchSdkClass`); `LambdaCallBlock` was
    already on the `*Find*` names.

- **2026-07-09 — Remote Pilot: VPN is now the default path; Funnel demoted to Advanced; pairing-QR + camera fixes.**
  - **VPN-default (`UIManager` bring-up split):** opening Remote Pilot now binds directly to the Tailscale
    tailnet interface (phone runs Tailscale, same account) — instant, no CLI wait, more private, zero
    computer-side setup. `startRemotePilot()` split into `startRemotePilotDirect()` (default) and
    `startRemotePilotFunnel()` (opt-in). The dialog leads with the phone's 3 Tailscale steps.
  - **Funnel → Advanced (`enableFunnelExposure()`):** exposing publicly over HTTPS (Funnel) is now an explicit
    "Advanced: expose publicly…" link in the dialog; the setup wizard only appears after that opt-in fails
    (default open never shows it). Wizard "Re-check & enable" re-runs the Funnel attempt.
  - **Pairing QR now decodable (`QrCodes` + `UIManager.qrCell`):** quiet zone `MARGIN 1→4` and a crisp 1:1
    render (encode at display px, `setSmooth(false)`, white backing frame) — the old 220→190 blurred, tight-margin
    code failed to decode on phone cameras.
  - **Funnel wizard diagnostics:** `NO_HTTPS_CERT` issue highlights the HTTPS-certificates step (the common
    remaining blocker), and the raw `tailscale funnel` error is always shown.
  - **BotPilot scanner survives background/resume:** re-initializes the camera on `visibilitychange`/resume
    (was frozen/black after opening the native camera app). (See `botmaker-pilot`.)

- **2026-07-09 — Remote Pilot: idempotent re-open, stable port, Funnel link fix (real-world bugfixes).**
  - **Idempotent re-open (`UIManager.openRemotePilot`):** re-clicking the toolbar/menu button no longer tears
    the server down and rebinds a fresh ephemeral port (which dropped an already-paired phone) — when the pilot
    is running it just re-shows the same dialog. A new `openRemotePilot(true)` forces a real restart and is
    wired to the wizard's "Re-check & enable" only.
  - **Stable local port (`PilotServer.start` + `ProjectPreferences.pilotPort`):** the bound port is persisted
    and reused when free (ephemeral fallback), so the tailnet-direct `http://<ip>:<port>` URL survives a Studio
    restart — completing the "don't rescan" story alongside the stable token.
  - **Funnel admin link fix:** `TAILSCALE_FUNNEL_ADMIN_URL` pointed at `/admin/settings/funnel` (404); now
    `/admin/acls` (Access Controls, where the `funnel` node-attr lives), with the step relabeled to match.
  - **BotPilot QR scanner** swapped to ZXing (`@zxing/browser`) with continuous autofocus — robust continuous
    decode replacing the hand-rolled jsQR loop, fixing the move-toward-code lag. (See `botmaker-pilot`.)

- **2026-07-09 — Funnel setup wizard + stable pairing token; BotPilot connection history & reconnect.**
  - **Stable pairing token (`PilotServer` + `ProjectPreferences.pilotToken`):** the pilot token is now persisted
    machine-globally and reused across restarts (was re-minted every `start()`), so the Funnel URL
    `https://<machine>.ts.net/?token=…` stays valid — a paired phone reconnects without rescanning. New
    `PilotServer.resetToken()` + a **Reset pairing token** button in the dialog revokes it.
  - **Funnel setup wizard (`UIManager.showRemotePilotDialog`):** when the user wants Funnel (phone needs nothing)
    but it isn't live, the dialog now leads with a guided, **Re-check**-able checklist — Tailscale installed &
    signed in (✓/✗ from `TailscaleFunnelService.isLoggedIn()`, new), HTTPS certs, the `funnel` ACL attr (copyable
    snippet + admin link), and `--operator=$USER` (copyable command) — with the active blocker highlighted
    (`FunnelIssue` classified off the FX thread into the new `FunnelDiag` on `PilotOutcome`). The direct-bind
    connection is offered below as a fallback.
  - **QR dialog spacing:** the two QR codes are now bordered, titled cards (① Open on phone / ② Get the app) with
    a wider gap and larger codes, so they no longer read as one.
  - **BotPilot app:** connection **history** (Recent list, tap to reconnect, no rescan), a **Switch connection**
    escape when a socket is stuck reconnecting, a **faster QR scanner** (downscaled + throttled decode), a
    **landscape white-border fix** (cutout `shortEdges` + dark window bg + safe-area insets), and a visible
    **Check for updates** button with the current version. (See `botmaker-pilot`.)

- **2026-07-09 — Drop the in-Studio preview; promote Remote Pilot to the toolbar; clearer pairing dialog.**
  - **Removed the live window-preview panel** — the Debug Dashboard + BotPilot remote app supersede it. Deleted
    `ui/app/WindowPreviewManager` and the whole `services/preview/` package (`PortalScreenCast`,
    `PreviewScreenFeed`, `PipeWireVideoSource`) and its CSS; `UIManager`'s left column is now just the file
    explorer. Pilot/dashboard capture (`services/pilot/TargetCapture`) was already independent.
  - **`ToolbarManager` gains a 🎮 Remote Pilot button** (in the capture group, mirroring Debug Dashboard) wired
    to the existing idempotent `UIManager.openRemotePilot`; the View-menu item stays too.
  - **Pairing dialog UX (`showRemotePilotDialog`):** the URL is now a clickable `Hyperlink` (opens the browser);
    reworded copy states plainly the phone needs **nothing** installed and there's **no registration** — just
    scan the LEFT QR (link) / RIGHT QR (app). When Funnel isn't enabled, a clickable Tailscale admin-console
    link (`login.tailscale.com/admin/settings/funnel`) explains it's a one-time computer-side setup.
  - **Test fix:** `FunctionalInterfaceDefaultTest` now passes the new `ProjectState` arg to
    `MethodHandler.updateMethodInvocation`.
  - **BotPilot app:** in-app **QR scanner** (scan the pairing QR to auto-connect) + **GitHub-release auto-update**
    banner. (See `botmaker-pilot`.)

- **2026-07-09 — BotPilot: real HTTPS remote access via Tailscale Funnel + QR pairing + fast APK delivery.**
  - **New `services/pilot/TailscaleFunnelService`** wraps the `tailscale` CLI (best-effort, captured stderr):
    `isAvailable()`, `dnsName()` (from `status --json` → `Self.DNSName`), `enable(port)` = `funnel --bg <port>`
    (443 → loopback, returns the public `https://<machine>.ts.net` base or the CLI error), `disable()`.
  - **`PilotServer`** `Endpoint` gains `publicBaseUrl` (so `url()` emits `https://…/?token=` when funneled, else
    the old `http://host:port`); new `attachFunnel(...)` + Funnel teardown in `close()`. Token hardened for public
    exposure: 24-byte (192-bit) token + constant-time `MessageDigest.isEqual` compare in the WS handshake.
  - **`UIManager` ▸ Enable Remote Pilot** now prefers Funnel — binds loopback, runs `funnel.enable`, shows an
    **HTTPS** dialog with two **QR codes** (`ui/util/QrCodes` → ZXing `com.google.zxing:core`, rendered straight
    into a JavaFX `WritableImage`): left pairs the pilot URL (scan → phone browser, no VPN), right downloads the
    APK. Falls back to the tailnet/all-interfaces direct bind (surfacing the Funnel error) when Funnel is
    unavailable/ungranted.
  - **APK delivery:** `botmaker-pilot/.github/workflows/release-apk.yml` builds + attaches `botpilot.apk` on tag;
    the install QR points at the stable `releases/latest/download/botpilot.apk` permalink. Added `npm run dist`
    (web → sync → APK) in `botmaker-pilot/package.json`. Web client needed no change — it already derives `wss://`
    from an `https:` origin.
  - **Bring-up UX:** the Tailscale CLI runs off the FX thread behind an indeterminate progress dialog (inline
    calls previously froze/"crashed" the UI); `enable` aborts the instant Funnel reports it's not enabled
    (fail-fast markers) so the fallback is ~1s instead of a 12s timeout; Copy-URL gives "Copied ✓" feedback with
    a selectable field + resizable dialog. Bumped Javalin 6.3.0 → 6.7.0 (Jetty 11.0.25).

- **2026-07-09 — BotPilot: remote live-preview + control over WebSocket (browser + Android APK).**
  - **New `services/pilot/PilotServer`** (Javalin 6.3.0, embedded Jetty) serves the BotPilot web client and a
    `/ws` endpoint on one port: **binary** JPEG frames (16-byte `sx,sy,sw,sh` header) at ~12 FPS with per-client
    in-flight backpressure (drop frame if the previous send is pending), **text** telemetry/state out, and
    inbound `{cmd:start|stop|pause|resume}`. Token-gated handshake (`?token=`), Tailscale-iface (CGNAT
    `100.64.0.0/10`) or `0.0.0.0` binding.
  - **Capture/serialize extracted** from the loopback `TelemetryDashboardServer` into `services/pilot/TargetCapture`
    (adds raw `byte[] jpegBytes`) + `TelemetrySerializer`; the SSE dashboard now delegates to them (unchanged behavior).
  - **Controls:** start/stop via EventBus (`ExecutionRequestedEvent`/`StopRunRequestedEvent`); pause/resume via
    `SIGSTOP`/`SIGCONT` on the bot JVM (`services/pilot/PilotControlService`, Unix-only — crash-free kernel freeze),
    using new `CodeExecutionService.runningBotPid()`. Run state broadcast off `ProgramStarted/StoppedEvent`.
  - **Studio UI:** View ▸ **Enable Remote Pilot…** (`MenuBarManager`/`UIManager`) starts the server and shows a
    copyable URL + token dialog.
  - **Client** lives in the new sibling **`botmaker-pilot`** submodule (Vite + React + TS PWA in `web/`, Capacitor
    Android shell → APK). Studio serves a prebuilt `dist` committed under `src/main/resources/pilot/`; the new
    **`-Ppilot`** Maven profile rebuilds it from source via a project-local Node (frontend-maven-plugin). Added
    `io.javalin:javalin:6.3.0` dep.

- **2026-07-08 — Fix batch: overload capture default, Follow highlight, preview cadence, version indicator, favorite overload, SNAPSHOT model.**
  - **Overload switch now seeds the project-default CaptureSource** (was always "Whole Desktop"). `ProjectState`
    is threaded through the argument-sync path (`NodeCreator.createDefaultInitializer(ast,type,cu,state)` →
    `MethodHandler`/`InstantiationHandler`), so switching to a `(…, CaptureSource, …)` overload fills the slot
    from the project default via the same `InitializerFactory`/`CaptureExpr.of` path as initial creation.
  - **Debug/trace Follow now highlights the running block.** `CoreApplicationEvents.BlockHighlightEvent` (emitted
    by `DebuggingService` on trace-advance, debug-pause, and clear) had no subscriber; added one in
    `CodeEditorService` that applies it to `ProjectState` (`setHighlightedBlock`/`clearHighlight`, on the FX thread).
  - **Preview capture is now two-cadence + event-driven** (fan-noise fix). `WindowPreviewManager` captures at
    `IDLE_FPS`≈1 while idle and `RUN_FPS`≈6 while running, reschedules on run start/stop, grabs a fresh frame per
    SDK feedback event, and throttles the Wayland/PipeWire portal feed to ~1 FPS idle / ~12 FPS running.
  - **Local build version indicator** (distinct from the GitHub update check). New `config/VersionInfo` reports
    Studio / shared / project-SDK versions with `dev build` / `(local)` / `(local build)` markers; shown in the
    About dialog (`MenuBarManager`) and printed as a startup banner (`UIManager`).
  - **Favorite overload per project.** `StudioProjectSettings` gains `favoriteOverloads` (methodKey→signatureKey,
    persisted in `settings.json`); set/clear via the ⚙ picker's "★ Default overload" submenu
    (`MethodInvocationBlock`), and applied when a fresh palette block is created (`StatementFactory.buildLibraryCall`
    seeds the favorite overload's args). New `MethodSignature.signatureKey()`/`bestForKey(...)`.
  - **`botmaker.shared.version` defaults to `0.0.0-SNAPSHOT` in the committed pom**; the real shared tag is
    injected only at CI build time (`release.yml` resolves the newest shared tag and passes `-Dbotmaker.shared.version`).
    `release.sh` no longer edits/commits the property.

- **2026-07-08 — UX batch: dev-only SDK list, desktop default, minimized-window capture, telemetry stability.**
  - **SDK version dropdown shows one local build, dev-only.** `MavenService.localSdkVersions()` now early-returns
    when `AppVersion.isDevBuild()` is false (no manifest `Implementation-Version` → dev run only) and caps to the
    single newest snapshot, so a released app-image never lists `~/.m2` dev builds and the stale
    `0.0.0-SNAPSHOT`/`local-SNAPSHOT` duplication is gone. New `AppVersion.isDevBuild()`.
  - **Whole desktop is the default capture target of a fresh project.** `StudioProjectSettings.empty()` seeds a
    `DesktopTarget` at index 0 (was empty/`null`), and `CaptureSourcePicker` preselects the Desktop tile instead
    of the first monitor — so the toolbar shows "Whole desktop" immediately and pickers stop starting empty.
  - **Minimized window target: capture instead of blank/screen.** New "keep un-minimized" (▣) preview control
    (off by default) restores a minimized target via shared `restoreWindow(...)` so its real content is captured;
    when off, the preview shows a clear "window is minimized" hint (`WindowPreviewManager`) instead of silently
    falling back to full-screen. Uses shared `getAllWindows(includeMinimized)`.
  - **Telemetry "disconnected" flapping fixed + clearer errors.** `TelemetryDashboardServer` sends a 15s SSE
    keepalive comment and the page shows `reconnecting…` (not a hard `disconnected`) on a transient `EventSource`
    hiccup. When a bot's SDK speaks an incompatible telemetry wire version, Studio now appends a one-time notice
    to the output ("pick a current SDK build") via the new `TelemetryServer` `onError` hook, instead of a silent
    dead preview.
  - **Benign X errors silenced at startup.** `BotMakerStudio.main` installs a no-op Xlib error handler
    (`X11ErrorSilencer`, shared) *before* `launch()` so window-capture `BadMatch` noise stops without triggering
    GDK's "error trap pushed" warning.

- **2026-07-08 — Capture UX: unfroze capture, first-class Desktop target, preview parity, force-X11.**
  - **Fixed the machine-freezing capture.** `ScreenCaptureService.prepareScreenshot` ran the whole grab
    (native focus + `Thread.sleep` + Robot/CLI shell-out) on the FX thread *before* showing a modal
    full-screen overlay — a slow/blank grab froze the desktop. The grab now runs off-thread
    (`grabAsync` → `grabOffThread`), hops back for the FX-thread screen chooser, and a blank (Wayland) grab
    shows a dismissible warning instead of a black full-screen trap.
  - **`DesktopTarget` is now a first-class capture target** (`project/capture/CaptureTarget`), so "the whole
    desktop" is an explicit, storable project default and a selectable tile — not an implicit `null`. Wired
    through `CaptureExpr`, `ScreenCaptureService`, `WindowPreviewManager`, and the dashboard; persists as
    `{"type":"desktop"}`.
  - **Capture-source picker has three categories** — Windows / Monitors / Desktop (was Screens + Windows);
    the in-block button now labels the whole desktop "Whole desktop" (was the ambiguous "Whole screen").
  - **Toolbar Capture button shows the current default** (name only, e.g. "🎯 Screen 2" / a window title /
    "🎯 Whole desktop"), refreshed on `SettingsChangedEvent`; shared `CaptureTargetNames.shortLabel`.
  - **"📊 Debug Dashboard" toolbar button** next to Capture Targets (`ToolbarManager` + `UIManager`); the
    dashboard now previews the **project default** target (not the whole current screen) via an
    effective-target resolver mirroring the Studio preview, and gained zoom / fit / follow view controls.
  - **Force-X11 on Wayland.** New `services/platform/SessionEnvironment` (single Wayland detector +
    best-effort `pkexec` X11-session-package install command per distro/DE); a one-time-per-session
    `ForceX11Notice` modal (`BotMakerStudio.finishOpen`) explains the re-login requirement, offers the
    package install (streams output, always shows the exact command), and has a "don't show again" flag
    (`ProjectPreferences`).

- **2026-07-08 — CaptureSource picker: type-aware overloads, region-as-modifier, project-default seeding.**
  Follows the SDK's CaptureSource redesign (`../botmaker-sdk/ROADMAP.md`).
  - **Picker no longer mis-renders on same-arity overloads.** Overload selection was by argument *count* only
    (`MethodSignature.bestForArity`), so `find(t, CaptureSource)` vs `find(t, Rect)` vs `find(t, double)` (all
    arity-2) picked whichever the analyzer listed first → a CaptureSource slot could get a RectPicker. New
    `MethodSignature.bestForArgs` scores same-arity overloads against the **actual** argument types
    (`ProjectAnalyzer.resolveType(Expression)`, binding-backed) and `MethodInvocationBlock` now uses it
    (`determineCurrentSignature`), so each slot gets the right picker. Falls back to count-only when arg types
    are unresolved.
  - **Region is picked as a rect *of* the chosen source.** The capture chooser gained an optional
    x/y/w/h region row; `CaptureExpr` emits a trailing `.region(new Rect(...))` and `CaptureSource.Selection`
    carries a `CaptureRegion`. (Visual rubber-band selection deferred; numeric entry is the interim.)
  - **`CaptureExpr` retargeted to the new SDK factories** — emits `CaptureSource.desktop()` /
    `CaptureSource.monitor(i)` / `CaptureSource.window("t")` (was `screen()` / `Screen.at(i)`), so generated
    bot code compiles against the redesigned SDK. In-block picker label + expression menu updated to match.
  - **New CaptureSource blocks seed from the project default.** `InitializerFactory` hard-coded
    `CaptureSource.screen()`; it now parses `CaptureExpr.of(project default target)` (window/monitor/desktop)
    into the AST, falling back to `desktop()` when no default is set.

- **2026-07-08 — Wayland preview follow-up: window + screen capture.** The first regression batch (below)
  didn't cover the two live capture pipelines; a run on Fedora/GNOME-Wayland still failed.
  - **Window preview / bot window vision fixed in `shared`.** `LinuxController.captureWindow` used AWT
    `Robot` → portal prompt + `SecurityException` per window on Wayland. Now reads the window pixmap directly
    via `XGetImage` (prompt-free); see `botmaker-shared/ROADMAP.md`. Fixes the dashboard `WindowPreviewManager`
    window preview *and* the SDK's `Window.capture()`.
  - **ScreenCast handshake hardened + instrumented.** `PortalScreenCast.negotiate()` now logs each step
    (`[preview-screencast] step=CreateSession/SelectSources/Start/OpenPipeWireRemote`) so a live run pinpoints
    which request precedes the `FatalDBusException` EOF; guards against sending a malformed `restore_token`.
    (Root-cause fix of the EOF still needs live iteration on the box.)
  - **Portal retry spam stopped.** `PreviewScreenFeed` latched failure per-instance, but a fresh feed is built
    every capture start/stop cycle, so the portal was re-hammered on every settings/run event. Failure is now
    a process-wide latch → try once, then fall back to the hint until app restart.

- **2026-07-08 — Regression fixes for the capture-source picker / Wayland-preview batch.** Six issues found
  running the 960d01d landing on Fedora/GNOME-Wayland:
  - **Drag crash fixed.** `CodeEditor.moveStatement` cast a block's AST node straight to `Statement`, but a
    bare method-call block (find/click/print — a `MethodInvocationBlock`) is backed by a `MethodInvocation`,
    not the enclosing `ExpressionStatement` → `ClassCastException` on drag. Now resolves the nearest enclosing
    `Statement` (`enclosingStatement`). Regression test in `BlockDragDropEditTest`.
  - **Debug run now uses the project working dir.** `DebuggingService`'s bot `ProcessBuilder` lacked
    `.directory(config.projectPath())` (which `CodeExecutionService` had), so a debugged/telemetry run
    inherited Studio's CWD and OpenCV `imread` couldn't find `src/main/resources/images/*.png` (the
    follow-mode path bug). Added.
  - **Capture picker no longer prompts per monitor on Wayland.** The picker grabbed each monitor thumbnail
    with AWT `Robot`, re-triggering the portal share picker per tile. New shared `services/capture/DesktopGrab`
    (prompt-free CLI grab on Wayland — grim/gnome-screenshot/spectacle — Robot on X11) grabs the desktop once
    and crops per monitor; `ScreenCaptureService` now delegates to it too (de-dup).
  - **`BotConfig.java` sidecar dropped.** Capture-source blocks now emit inline, fully-qualified expressions
    (`CaptureSource.screen()` / `Screen.at(i)` / `CaptureSource.window("t")`, via new
    `project/capture/CaptureExpr`) — no generated sidecar is ever written. Removed
    `ProjectSettingsService.ensureBotConfig/writeBotConfig/generateBotConfig` and `ProjectConfig.botConfigSourceFile`.
    "Project default" is snapshotted to the current default target at pick time.
  - **Capture-source picker now consistent across all SDK overloads** — the SDK gained full `CaptureSource`
    coverage (see `../botmaker-sdk/ROADMAP.md`); the picker is type-driven (`PickerRegistry` on
    `CaptureSource`/`Window`) so it attaches to every new source parameter with no Studio change.
  - **Wayland preview D-Bus session lifetime fixed.** `PortalScreenCast.open()` returned only the `Stream`,
    leaving the `PortalScreenCast` (and its D-Bus connection, which backs the PipeWire session) unreferenced →
    GC closed the socket mid-use → `FatalException`/EOF, negotiation reported as failed. Now returns the live
    handle (`PreviewScreenFeed` keeps it and closes it on `close()`), uses a non-shared connection, and clears
    a stale `restore_token` on failure so the next attempt re-prompts cleanly.

- **2026-07-08 — Wayland live preview (portal + PipeWire), capture-source picker fixes, lazy `BotConfig`,
  running-block highlight.** Follow-ups after validating the 2026-07-07 landings on Fedora/Wayland:
  - **Non-disruptive Wayland capture (WS2).** `services/preview/` new: `PortalScreenCast` (xdg-desktop-portal
    ScreenCast handshake over D-Bus — `CreateSession`→`SelectSources(persist_mode=2, restore_token)`→`Start`→
    `OpenPipeWireRemote`, restore token stored globally in `ProjectPreferences` so GNOME prompts **once ever**),
    `PipeWireVideoSource` (GStreamer `pipewiresrc→videoconvert→appsink` live BGRx→ARGB), `PreviewScreenFeed`
    (negotiates off-thread, delivers frames + monitor origin). `WindowPreviewManager` no longer calls AWT
    `Robot` for screen capture on Wayland (that per-frame grab was what re-prompted endlessly) — it renders the
    live PipeWire feed via `WritableImage`/`PixelWriter`, throttled ~18 FPS, with a fallback hint if the
    portal/GStreamer stack is missing. New deps: `dbus-java-core` + `-transport-native-unixsocket` 5.1.0,
    `gst1-java-core` 1.4.0 (runtime needs `gstreamer1-plugins-good`). X11/Windows/macOS keep the Robot path.
    *Limitations:* a **specific screen index** can't be forced on Wayland (the portal picker owns monitor
    choice); window-by-title still can't be enumerated on native Wayland (X11 client list only).
  - **Capture-source picker is now a real inline picker.** `ui/render/components/CaptureSourcePicker` +
    `PickerRegistry` entry (before `Rect`): a `CaptureSource`/`Window` arg slot shows a 🎯 button opening the
    visual chooser popup (was falling through to the generic pill next to the `Rect` region picker). Emits a
    fully-qualified `BotConfig.defaultSource()/screen(i)/window("…")` snippet.
  - **`BotConfig.java` is now lazy.** `ProjectSettingsService` no longer writes it on project open or on every
    settings change; `ensureBotConfig()` materializes it only when a block first references it (then keeps it
    synced). A freshly-declared `CaptureSource` variable defaults to fully-qualified `CaptureSource.screen()`
    (no sidecar, no import). Fixes the unwanted `BotConfig` file appearing unprompted.
  - **Running-block highlight on a plain run.** Telemetry gained a source line (shared wire v2 + SDK
    `IpcObserver.botLine()`); `WindowPreviewManager` builds a line→block map on run start and highlights the
    executing block as `Match`/`Click` events arrive (debug/trace still highlights via JDI). *Requires the
    local-SNAPSHOT SDK + shared v2 — a bot pinned to an old released SDK speaks wire v1 and its frames are
    rejected; re-run `./dev-install.sh` and reselect the local SDK.*
  - Picker's Windows section now shows an explanatory placeholder on Wayland instead of a blank list.

- **2026-07-07 — Preview honors default target + zoom/follow; visual capture-source picker; valid
  `CaptureSource` codegen; explorer rework; telemetry debug dashboard.** Five related landings:
  - **Live preview (`WindowPreviewManager`)** now resolves its target from the project's default
    `CaptureTarget` (a live telemetry *window* still wins), so a window default drives the panel — and it
    previews the default even while idle. Added a hover-revealed control bar: zoom `＋/－/⤢`, a **Follow
    found object** toggle (eases a source-image viewport onto the last `Match` rect), and a **reload** button.
    Overlays render through the same viewport. Screen capture now grabs the chosen monitor's bounds, not the
    whole virtual desktop. CSS: `.preview-controls`/`.preview-ctl` in `blocks.css`.
  - **Visual capture-source picker** (`ui/app/capture/CaptureSourcePicker`): a Steam-style chooser with
    Screens + Windows categories (live thumbnails + names) and a "Project default" tile. Reused by the
    toolbar Capture Targets dialog (replaces the combo-box add-rows) and by in-block selection.
  - **Valid, default-tracking `CaptureSource` codegen (WS4).** `CaptureSource` is an SDK *interface* — the
    old default-arg path emitted `new CaptureSource()` (uncompilable). `InitializerFactory` now defaults such
    a slot to `BotConfig.defaultSource()`; `ExpressionMenuFactory` offers "🎯 Choose capture source…" for
    `CaptureSource`/`Window` slots (no constructor) → a `RawExpression` snippet inserted via
    `RawExpressionHandler`. `ProjectSettingsService` generates/regenerates `BotConfig.java`
    (`defaultSource()` tracks the project default; `window(title)` / `screen(index)` back concrete picks) on
    every settings change and at load; `ProjectConfig.botConfigSourceFile()` added.
  - **File explorer rework** (`FileExplorerManager` + `UIManager` left column): tree fills the panel
    (vgrow + max sizes), no maxWidth cap (kills the drag-into-dead-space), file/folder/lib icons, and
    active-file/dir/lib styling moved from inline `setStyle` to CSS style classes (`.file-explorer` in
    `blocks.css`).
  - **Telemetry debug dashboard** (`services/debug/TelemetryDashboardServer`, View ▸ Open Debug Dashboard):
    an opt-in local `HttpServer` (ephemeral port, auto-opens browser) that streams every `ViewFeedbackEvent`
    over SSE plus a periodically-captured live frame with overlays, on a self-contained HTML page.
  - Pairs with the SDK's new `Screen.at(index)` per-monitor `CaptureSource` (see `../botmaker-sdk/ROADMAP.md`).
  - **Deferred:** the non-intrusive Wayland *live-video* capture (persistent xdg-desktop-portal ScreenCast
    session + PipeWire) — WS2 — is not yet implemented; screen preview still uses per-frame `Robot`, which on
    GNOME/Wayland re-prompts. A **window** default already avoids the portal (native capture).

- **2026-07-07 — Auto-list local SDK dev builds in the version pickers.** `MavenService.localSdkVersions()`
  scans `~/.m2/.../botmaker-sdk/` for installed `*-SNAPSHOT` builds (jar present, newest first). Both pickers
  surface them at the top: the New Project combo (`ProjectSelectionScreen`, labeled `(local build)` via a
  cell factory, preselected when present) and the SDK row in `ManageLibrariesDialog`. Removes the old "type
  `local-SNAPSHOT` by hand" step — these versions never appear in JitPack's tag list. Pairs with the SDK
  `dev-install.sh` fix that routes a local SDK build to the local `botmaker-shared` build.

- **2026-07-07 — Deterministic startup window fill (fixes black border / jump / click-twice on GTK).**
  `BotMakerStudio.configureWindow` now fills the primary screen's visual bounds with explicit stage bounds
  instead of a post-`show()` `Platform.runLater(stage.setMaximized(true))`. On GTK/X11 the WM applied the
  maximize asynchronously, so the scene laid out at the pre-maximize size (black border), the window jumped,
  and the first maximize toggle was often dropped ("click twice to expand"). Explicit bounds paint correctly
  on the first frame. A user's saved non-maximized size still wins; the geometry listener now clears the
  maximized flag on any manual resize so a filled-then-resized window is restored at that size, not re-filled.

- **2026-07-07 — Live window-preview panel + telemetry IPC server.** A new bottom-left panel
  (`ui/app/WindowPreviewManager`, under the File Explorer in a vertical `SplitPane`) shows a live capture of
  the bot's target window/screen with overlays where the vision/interaction functions acted (green/red match
  rect, click crosshair, faint search region; overlays linger ~1.2s then fade). Fed by a new
  `ViewFeedbackEvent` republished from the `com.botmaker.shared.ipc` `TelemetryServer`, which
  `CodeExecutionService` (run) and `DebuggingService` (debug/trace) start before launch and hand to the bot
  via `BM_IPC_PORT`/`BM_IPC_TOKEN` env; closed on stop. Capture is non-intrusive — grabs the target window
  via the shared native controller *without* focusing it, on a ~6fps timer while running, so it never
  disturbs the bot. `BM-INPUT` stays on stdout unchanged. Requires the SDK observer-emit half (see the SDK
  ROADMAP). End-to-end env auto-install verified across two real JVMs; the on-screen overlays are the manual
  (display-dependent) check via `dev-install.sh` + a stub bot.

- **2026-07-07 — Follow execution (live trace highlight).** New "👁 Follow" toolbar button
  (`ToolbarManager`) + `FollowStartRequestedEvent` (in the `DebugControlRequest` family). `DebuggingService`
  gained a trace mode (`startDebugging(boolean trace)`): it attaches JDI like debug but installs breakpoints
  on *every* mapped line, ignores user breakpoints, and in `handleLocatableEvent` highlights the block and
  immediately resumes (never fires `DebugSessionPausedEvent`). Highlight repaints are coalesced to one per
  130 ms (trailing edge, `scheduleHighlight`) so a tight loop pulses instead of strobing. Note: JavaFX 21 CSS
  has no `transition`, so the throttle — not a CSS fade — is what smooths the highlight.

- **2026-07-07 — Silence the native-access startup warning.** Added
  `--enable-native-access=ALL-UNNAMED` to both launch paths in `pom.xml`: the `javafx-maven-plugin`
  `<options>` (dev `mvn javafx:run`) and every `jpackage-maven-plugin` execution's `<javaOptions>`
  (packaged app-image/deb/rpm/msi). Stops JavaFX's `System::load` (glass native libs) "restricted method"
  warning and pre-empts the future JDK release that blocks it. Backlog: the remaining two JFX-21-internal
  warnings (Marlin `sun.misc.Unsafe::allocateMemory`, classpath "unnamed module") are only cleared by a
  JavaFX 21→25 upgrade — deferred.

- **2026-07-07 — UI interaction map + interaction tests.** New `docs/INTERACTION-MAP.md` catalogues every
  user interaction with its node hooks (style classes / text / titles) and headless-vs-native testability —
  the source of truth for what `ui/fx/` should cover. Added 12 headless interaction tests: `StatementMenuTest`
  (statement-menu search/filter + item→callback wiring), `SeparatorInsertButtonTest` (the "+" hover→visibility→
  menu state machine — the fragile area behind the reported statement-menu issue), `ToolbarInteractionTest`
  (buttons publish the right `EventBus` events; Run/Stop enablement tracks run state). Deferred: block-reorder
  at the event layer, per-block context menu, create-project name validation, tab auto-switch / global shortcuts.
- **2026-07-07 — Headless automated UI tests (TestFX + Monocle).** Studio's JavaFX layer is now
  testable with no X server / display: TestFX drives the real scene graph, run on Monocle's `Headless`
  platform. New `src/test/java/com/botmaker/studio/ui/fx/`: `FxHeadlessTest` (base), `FxHarnessSmokeTest`
  (proves the headless robot end-to-end) and `ProjectSelectionScreenSmokeTest` (renders the real startup
  screen and asserts its controls). Wiring in `pom.xml`: `testfx-core`/`testfx-junit5`/`openjfx-monocle`
  test deps + Surefire `systemPropertyVariables` (Monocle headless) and the JavaFX 17+ `--add-exports`/
  `--add-opens` the robot needs; also fixed a hardcoded Windows heap-dump path in `argLine`. Complements
  the manual `testing/linux` x11docker harness, which stays the way to test the `botmaker-shared` native
  X11/Wayland window layer (not reachable headless).
- **2026-07-06 — SDK docs from the sources jar + vision-block overhaul.** Studio now reads the SDK's real
  Javadoc + parameter names at runtime by resolving `botmaker-sdk:<version>:sources` via Aether and parsing it
  with Eclipse JDT (`index/SdkDocsParser` → `palette/SdkDocs`, owned per-project by `services/SdkDocsService`,
  refreshed on `LibrariesChangedEvent`). No committed JSON — the SDK source is the single source of truth.
  - **Named argument pills + "learn about it" (ⓘ) help** on SDK calls (`MethodInvocationBlock`,
    `LambdaCallBlock`): pills read the real param name (`findCompare(good, bad)`) with the `@param` text as a
    tooltip, and a click-open popover (`BlockUIComponents.createInfoButton`) shows the method summary + per-param
    docs. Every specialized SDK arg pill now also carries the "+" change button (open the expression menu).
  - **Vision loop blocks are first-class** (`LambdaCallBlock`): a ⚙ overload picker switches
    `whileExists ↔ …Any ↔ …All` (and the `if`/`until` families), swapping the slot between a single
    `ImageTemplate` and the multi-chip `ImageTemplateGroup` picker and fixing the lambda param
    (`Consumer<MatchResult>` vs `Runnable`) — driven by `parser/handlers/LambdaCallHandler.switchVariant`
    (`CodeEditor.switchLambdaVariant`). The "+" change button now sits next to the picker.
  - **One canonical path to SDK methods:** SDK facades are filtered out of the generic "Call Function →
    Library (static)" menu / scope dropdown (`ExpressionMenuFactory`, `MethodInvocationBlock`); reach them via the
    curated Vision palette + the in-block class/method/⚙ selectors.
  - **Menu de-dup:** dropped "Click Any Image"; `BOT_ACTIONS` is now 5 promoted actions; new
    **"Declare Bot Variable"** submenu (`BlockCategory.BOT_VARIABLE`) holds the vision var-decls, rendered right
    below the promoted actions. 📸 pick-all button relabelled ("📸 Pick all" + explanatory tooltip).
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

## Activity Flow backlog

- [ ] **Conditional edges + loops (the next milestone).** `FlowEdge` gains an optional condition; a node may
  then have more than one outgoing edge, which retires the single-chain invariant `ChainRules.rejectionFor`
  enforces (the fork rejection specifically) and makes `linearize`'s single-successor map a graph traversal.
  With branches a cycle stops being illegal — it is how a bot repeats — so the guard becomes an emitted bound
  (max iterations / wall-clock) rather than an editor-time rejection, and the current "runs once top to bottom
  then stops" goes away. A branching flow can't be a flat `List<Activity> ALL`, so codegen needs a driver (a
  state machine over node ids, or emitted `if/else` dispatch). Conditions should be authored with the existing
  `ExpressionCatalog`/`ExpressionMenuFactory` against a boolean slot rather than a second expression language.

## Refactoring backlog (Studio)

- [ ] **A5 — Refresh CLAUDE.md.** It still references the removed `BlockFactory` / `BlockParser` and the old
  `AddableBlock`; document `BlockType` / `BlockCatalog` and the event-driven drag-and-drop.
- [ ] **A6 — Don't seed `new T()` for a type with no no-arg constructor.** `InitializerFactory`'s object
  branch assumes one exists; `java.awt.Color` is now special-cased but any other such type still generates
  uncompilable code. A general guard needs constructor knowledge — `ProjectAnalyzer.getConstructors` has it,
  but `createDefaultInitializer` isn't given an analyzer, so this is a signature change across its callers.
  Fall back to `null` when no no-arg constructor is visible.
- [ ] **A7 — The Errors tab steals focus on every compile.** `DiagnosticsPanel.update` raises the tab whenever
  a compile produced at least one error, so a bot being edited with a known error in it pulls the bottom pane
  away from the terminal on every keystroke-triggered rebuild. Left as-is deliberately during the `UIManager`
  split (2026-08-06, maintainer's call) — the alternative is raising it only when the *set* of errors changes,
  which needs a diff and a decision about what counts as a change.

## Overlay Editor backlog

- [x] **Edit-in-place in the tree** — move-up/move-down and delete for the focused block. *(2026-08-06)*
- [x] **Collapse/expand** control-flow bodies in the tree for long programs. *(2026-08-06)*
- [x] **Global hotkey** to toggle record without reaching for the overlay — `F9`. *(2026-08-06)*
- [ ] **Run / run-to-cursor from the overlay** so a bot can be tested without switching back to Studio.
- [ ] **Live match preview** — draw the last vision match rect over the target window.
- [ ] **Richer recorded gestures** — right/middle/double-click and drag (deferred in `MacroTranslator` v1).
- [ ] **A recording that knows it is off-resolution.** The header now *says* the target window isn't at the
  project reference resolution, but `MacroTranslator` still emits raw window-relative pixels either way. The
  honest fix is to scale the recorded coordinates by `reference / windowBounds` at translation time — which
  needs a decision about which of the two the user meant, so it is a feature, not a bug fix.

## Emulator backlog

- [ ] **A configured Waydroid framebuffer resolution.** `WaydroidResolution.apply()` exists in shared and
  **nothing calls it**, because there is nowhere to author the target: the only value available is
  `WaydroidResolution.read()`, and applying what you just read is a tautology. The Start button therefore does
  *not* apply a resolution — it would be a no-op at best, and at worst `apply()`'s deliberate session
  stop/start cycle would tear down the session the button is bringing up. What this actually needs is a
  BotMaker-owned expected size (a project or emulator setting), at which point `apply()` before the spawn is
  both meaningful and safe. Until then the gamescope sizing flags come from whatever the container was last
  configured with, read at discovery — and a mismatch is already surfaced, not silent, by
  `WaydroidDiagnostics.resolutionMismatch`.

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
- [ ] **B8 (P1) — Bump the Studio's SDK dependency** to the current SDK version and rebuild the type index so new
  APIs appear in the palette / type-menus.ic

## Completed

Most recent first. Claude appends here when work lands (date — what changed — where).

- **2026-07-06 — Capture Targets moved to the toolbar (Studio).** The `Project → Capture Targets…` menu item
  is now a **🎯 Capture Targets** button in the toolbar's (previously empty) center — `ToolbarManager.createCaptureGroup`
  + `UIManager` `topBar.setCenter(...)`; removed from `MenuBarManager`.
- **2026-07-06 — Remember window titles for capture targets (Studio).** The add-window dropdown now lists
  previously-seen/used window titles (union of live windows + a persisted list), so a game window can be picked
  as the default target without the app running. New `knownWindowTitles` on `StudioProjectSettings` (backward-
  compatible), populated in `ManageCaptureTargetsDialog`.
- **2026-07-06 — Fix SDK version un-editable + add a "latest" option (Studio).** The inline `VersionCell`
  editor collapsed the instant JitPack versions loaded: the async `combo.setValue(...)` fired the combo's
  `onAction` → `commitEdit` → editor torn down. Added a `loading` guard so programmatic value-seeding no longer
  commits. Added a **latest** version option (in Manage Libraries version combos + the create-project SDK combo)
  that resolves to the newest concrete version at apply/create time so the pom stays pinned to a real version —
  `ui/app/ManageLibrariesDialog`, `ui/app/ProjectSelectionScreen`.
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
