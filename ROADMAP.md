# BotMaker Roadmap

Living backlog + changelog for the **Studio** (this repo). The **SDK** (`../botmaker-sdk`) and **shared**
(`../botmaker-shared`) modules each keep their own `ROADMAP.md`. Claude updates the **Completed** section
whenever work lands here (see CLAUDE.md → Roadmap).

## Completed

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
  Google — an honest stub) sit far-right, BotMaker-wide. *Deliberate deviation:* no "Push" button — a BotMaker
  project has no git remote (sharing is the GitHub Data API), so a push would have nowhere to go; offering one
  would be the "looks like it works, silently doesn't" trap the codebase already rejects for read-only edits.
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

## Overlay Editor backlog

- [ ] **Edit-in-place in the tree** — move-up/move-down and delete for the focused block (currently add-only).
- [ ] **Run / run-to-cursor from the overlay** so a bot can be tested without switching back to Studio.
- [ ] **Live match preview** — draw the last vision match rect over the target window.
- [ ] **Richer recorded gestures** — right/middle/double-click and drag (deferred in `MacroTranslator` v1).
- [ ] **Collapse/expand** control-flow bodies in the tree for long programs.
- [ ] **Global hotkey** to toggle record without reaching for the overlay.

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
