# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Planning

At the end of the planning stage, write the plan to a dedicated plan file before starting implementation,
so work can be resumed if a session is interrupted.

## Roadmap

`ROADMAP.md` (repo root) is the living backlog + changelog for the **Studio** (this repo only — the SDK and
shared modules each own their own `ROADMAP.md`). **After completing a meaningful change, update it:** add a
dated entry to the top of the **Completed** section (date — what changed — where), and check off / remove the
corresponding backlog item if it's now done. Keep entries to 1–3 lines. New backlog ideas that surface during
work go under the relevant backlog section.

## Commands

This is a **Maven** project (`pom.xml`) — there is no Gradle build.

```bash
# Build
mvn compile

# Run the application
mvn javafx:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=TypeAwareSuggestionTest

# Run a single test method
mvn test -Dtest=TypeAwareSuggestionTest#methodName

# Build a distributable (native app-image + installer)
mvn -Pdist package
```

From the **umbrella** root you can also run the Studio via the reactor: `mvn -pl botmaker-studio javafx:run`.
Tests run with JUnit Jupiter (Surefire).

## Code Style

Prefer minimizing mutable state — favor a functional OOP style. Use immutable values (`record`s like
`ProjectConfig`, `UserLibrary`) and pure transformations; pass dependencies in via constructors rather
than holding mutable fields or reaching for static/singleton state. Keep side effects (file I/O, process
launching, event publishing) at the edges in the service layer.

**Don't re-derive what shared already models.** Studio consumes shared's types, so a label, key or probe that
shared can answer belongs there, not in a dialog. Concretely: use `EmulatorInstance.brand()` /
`PlatformId.displayName()` rather than a local id→name switch (Studio's own `brandOf` had silently drifted
from shared's naming), `EmulatorInstance.identity()` for any cache or de-dup key (never the display name —
instances routinely share one), and `Platforms.PlatformStatus.statusLine()` for the per-product summary. The
editor-side counterpart is `emulator/EmulatorProbe` (liveness, `screencap`, `installedApps`), shared by both
pickers so they can't drift on timeouts or failure handling.

**One conversion, one place.** `ScreenCaptureService.toFxImage` is the single `BufferedImage` → FX `Image`
path and is null-tolerant, so best-effort callers (a window that wouldn't capture, a stopped emulator) pass
their result straight through instead of keeping a private null-returning copy.

## Setup

User projects live in `~/BotMakerProjects/` (not inside this repo). Each project is a standard **Maven** project with the layout `src/main/java/com/<projectnamelowercase>/<ProjectName>.java`. The BotMaker-Studio app itself is also a Maven project (`pom.xml`): build with `mvn compile`, run with `mvn javafx:run`, test with `mvn test`.

### Relationship to the SDK and shared

Studio's BotMaker Maven dependencies are **`botmaker-shared`** (editor-time native window capture),
**`botmaker-session`** (private displays) and **`botmaker-sdk`**. The SDK dep is narrow and deliberate, and
there are three distinct relationships to keep straight:

- **Studio compiles against the SDK for _type identity only_.** `palette/SdkType` is an enum over every class
  under `com.botmaker.sdk.api`, each constant holding a real `Class<?>` literal. That makes the facade set,
  the menu order, the menu icons and — crucially — the **fully-qualified names** compiler-checked. The FQNs
  matter because facades live in *sub-packages* (`api.vision.ImageFinder`, `api.interaction.Mouse`,
  `api.capture.Window`), so no import path can derive them from the simple name. It replaced `palette/SdkApi`,
  a hand-maintained `List<String>` that nothing verified, plus a second hand-maintained icon map in
  `MenuIcons`. **A new SDK class now needs a constant here or the surrounding code won't see it — but the
  compiler tells you when an existing one moves or is renamed.**
- **Method knowledge does _not_ come from that jar, on purpose.** A generated bot compiles against the SDK
  version *it* pins, which may be older than Studio's. So methods still come from `ProjectAnalyzer` scanning
  the bot's **resolved** SDK jar with ClassGraph, and Javadoc from `SdkDocsService` parsing the bot's
  `botmaker-sdk:<version>:sources` jar. Adding a *method* to an existing facade needs no Studio change.
- **`SdkType` is the superset; `services/SdkSurfaceService` is what the bot actually has, and the palette is
  the intersection.** Those two drifting apart is the normal case, not the exception — a bot pins one SDK,
  Studio ships on its own train — and until 2026-08 nothing noticed: an older bot was offered blocks its jar
  could not compile, and the only feedback was a javac line *after* the block was built. The service parses
  nothing; it reads the `ClassInfo` `TypeSummaryManager` already holds, including `@Deprecated` (bytecode, a
  `RUNTIME` annotation — **not** parsed Javadoc; the Javadoc `@deprecated` *text* naming the replacement is
  `SdkDocs.Overload.deprecated()`, a separate thing that can disagree). **It fails open**: with no SDK
  indexed, every presence query answers yes and every deprecation query answers no — a degraded probe must
  never hide a block the user legitimately has, nor strike one through.
  **Most menus need no explicit gate and must not grow one**: `StatementMenu` and `MenuBuilders` enumerate
  members through `ProjectAnalyzer` first and drop a facade that resolves none, which is the same jar
  answering the same question. The service exists for the surfaces where nothing enumerates first — the
  `OverlayPalette` chips, the class dropdowns on `MethodInvocationBlock`/`LambdaCallBlock`, and
  `ProjectSettingsDialog`'s favourites.
  `MavenService.MIN_SDK_VERSION` is the floor: below it the project **still opens**, under one amber banner
  offering *Upgrade SDK…*. An old bot that runs is not a broken bot.
- **Changing the SDK version is a report, not a cell edit — `services/SdkUpgradeService`** (*Project ▸ Upgrade
  SDK…*, and where the floor banner's button goes). It resolves the **target** version's jar
  (`MavenService.resolveSdkJar`, any version — the project pom's JitPack repo means it need never have been on
  this machine), ClassGraph-scans it beside the pinned one, and intersects the difference with the bot's own
  call sites: what's new, what the bot calls that is now deprecated, what the bot calls that is **gone**
  (file + line), and what the SDK declares about each break (`META-INF/botmaker/migrations.json`; the
  `summary` is the SDK author's sentence and is never paraphrased). Three things about it are load-bearing:
  - **Every break the SDK ships declares its own repair, or says why it has none.** Each entry in
    `migrations.json` carries exactly one of a `fix` (a `kind` from `SdkUpgradeService.KNOWN_FIX_KINDS`,
    applied by `parser/refactor`) or a `manual` sentence. `Report.automatic()` / `Report.manual()` are the
    two lists the dialog shows separately, because they ask different things of the user — one is a button,
    the other is reading. **One manual entry disables the whole span** (`Report.canMigrate()`): rewriting
    some call sites and leaving the rest is the half-migration `CallMigrator.rewriteOthers` returns `null`
    to prevent.
  - **Studio is the version that lags**, so it degrades rather than guessing. A `schema` above
    `MIGRATIONS_MAX_SCHEMA` is refused **whole** — one `problems()` line, no entries — since a grammar we do
    not know is one we may *misread*; breaks are still reported, coming from the jar scan and needing no
    file. An unknown `fix.kind` degrades that **one** entry to manual (summary still shown, `degraded()`
    true so the dialog can say *"needs a newer Studio"* rather than *"no rewrite can express this"*) and
    takes the span's auto-apply down with it. **Adding a `fix.kind` therefore does not bump `schema`** —
    that graceful path is the entire point of having the rule.
  - This replaced `mvn rewrite:run` against OpenRewrite recipes in 2026-08. OpenRewrite existed for one
    requirement — migrating with no Studio at all — and once that was withdrawn it bought nothing
    `CallMigrator` could not do. Note which way that cut: OpenRewrite type-attributes against the **old**
    SDK, so the rewrite had to run *before* the pom was bumped and the dialog had to teach that ordering.
    Our rewriter never resolves the SDK, so **snapshot → migrate → bump is one operation**, not two steps a
    user can get wrong.
  - **Breaks are judged by arity, not by argument types, and only for members the old jar had.** There are no
    bindings (same constraint as `parser/refactor/MethodReferences`), so a call through a variable is not
    attributed to the SDK at all and is not reported. A file that does not parse goes in `Report.problems()`
    rather than being skipped: `nothingBreaks()` is false whenever anything could not be read, because
    "nothing breaks" from a scan that read half the project is the one answer worse than no answer.
  - **Fields and constants are API too, and the scan reads all three shapes of use.** `Key.ENTER`,
    `Precision.TIGHT`, `Direction.UP` break a bot exactly as hard as a method, so public fields are scanned
    out of both jars into the same `byName` map (an enum constant *is* a static field — the five public enums
    come for free) and marked `ApiMember.field`, which is what stops a constant answering for a no-argument
    call. On the source side that means a qualified `QualifiedName` read, a bare name reaching an
    `import static`, and a **`case` label** — whose enum type lives on the switch expression and so is not
    written at the label at all. The unqualified two therefore take `MethodReferences`' three-way verdict:
    exactly one SDK type declaring that constant is a match, several is a `problems()` line, none is not SDK.
    Until 2026-08 none of this was read at all, so a release deleting `Key.ENTER` reported *"nothing breaks"*
    while the SDK's own `ApiRulesCheck` (whose member tags always included `field`) demanded a migration for
    it — CI and Studio disagreeing about what an API is.
  - **What a `fix` is actually made of lives in `parser/refactor`, and each primitive can refuse.**
    `ArgumentEdit.Literal(source, importFqn)` writes a value the SDK author chose (`Fresh` cannot — it
    synthesises the default of a *palette* type); `CallChange.MemberMoved(site, toType, newName)` retargets
    the type written at a call site, the one shape Studio's own refactorings never produce; and
    `CallMigrator.renameTypeIn` is deliberately **file-level, not per-site** — a type is also written in
    `Precision p;`, a cast and a type argument, none of which any call scan records, so renaming only the
    found sites leaves a file naming a class that is gone. `MethodReferences.CallSite` widened to hold a
    field reference (`arguments()` is simply empty), which is what lets one scan feed both the report and the
    rewrite. **Every primitive that cannot express its edit returns false and takes the whole migration down
    with it** — a constant moved out from under a `case` label, or a call on `this` with no type to retarget.
    That is the same all-or-nothing `rewriteOthers` already enforced for a rewrite that will not parse.
- **Studio generates bot projects that depend on the SDK.** `services/MavenService` writes each user
  project's `pom.xml` pinning `com.github.LiQiyeDev:botmaker-sdk` (default `SDK_FALLBACK_VERSION`;
  user-selectable in the project screen from JitPack's version list). That pin is independent of the version
  Studio itself compiles against.

This Studio repo is a submodule of the **`botmaker` umbrella repo** (sibling submodules `botmaker-shared/`,
`botmaker-sdk/`, `botmaker-studio/` + an aggregator `pom.xml`; see `../CLAUDE.md`). From the umbrella root
`mvn install` builds shared → sdk → studio in one reactor. **All SDK/shared changes go through their umbrella
submodules** — edit there, commit inside that submodule, bump its pointer in the umbrella. Don't vendor either
inside this repo. To try local SDK changes in a generated bot without pushing a tag, use
`botmaker-sdk/dev-install.sh` and pin the bot to `local-SNAPSHOT` (see `../botmaker-sdk/CLAUDE.md`). Releases
are cut with the umbrella's `../release.sh`; **the maintainer owns the SDK/shared → JitPack publish.**

The read-input blocks depend on a small SDK protocol: `BotMaker.readX()` prints a `BM-INPUT:<type>` marker (SOH-wrapped) to stdout before blocking on stdin. The Studio detects and strips that marker (`CodeExecutionService` for run, `DebuggingService` for debug), shows the modal input prompt, and writes the entered line back to the process's stdin via `SendInputEvent` → `sendInput(...)`. Changing the marker on either side without the other breaks input prompts.

## Architecture

### Project Lifecycle

`BotMakerStudio` (JavaFX `Application`) is the entry point. On launch it either re-opens the last project via `ProjectPreferences` or shows `ProjectSelectionScreen`. Opening a project goes through `BotProject.open()`, which is the composition root — it constructs all services and wires them together in order:

1. `ProjectConfig` — immutable record with all paths and JVM info for the project
2. `ProjectState` — mutable runtime state (current AST, classpath, highlighted block, etc.). **Confined to the
   FX thread.** Anything that runs off it — a run, a compile, a debug session — takes a `state.snapshot()`
   *before* spawning its thread and reads that record instead; the getters are not for background callers.
   The block registry is likewise built into a fresh map and published with `setNodeToBlockMap` in one
   assignment, never filled in place.
3. `EventBus` — per-project, not global; all inter-service communication goes through it
4. `MavenService` — generates and edits the project `pom.xml` (Maven Model API) and resolves the classpath in-process via Maven Resolver (Aether); no system `mvn` binary required. The `pom.xml` is the single source of truth for dependencies (see **Library Management**)
5. `TypeSummaryManager` — builds/loads a serialised cache of external library types per-jar
6. `ProjectAnalyzer` — unified type/suggestion provider backed by both the live JDT AST and the library index
7. `LibraryService` — add/remove user libraries: rewrites the pom, re-resolves the classpath, refreshes the type index, and publishes `LibrariesChangedEvent`
8. `CodeEditorService` — subscribes to `EventBus` and orchestrates all code editing operations
9. `ExecutionService` / `DebuggingService` — compile, run, and debug the user's project via JDI
10. `UIManager` — builds the JavaFX scene and connects UI events to services

`BotProject` owns all services; `BotMakerStudio` holds only one `BotProject` at a time.

### Block System

All visual blocks implement `CodeBlock` (interface) → `AbstractCodeBlock` (abstract, in `core/`). The two branch types:

- `StatementBlock` — executable statements (if, while, print, variable declaration, etc.)
- `ExpressionBlock` / `AbstractExpressionBlock` — value-producing expressions (literals, identifiers, binary ops, etc.)
- `BodyBlock` — a container of `StatementBlock`s, corresponds to a `{ ... }` AST block
- `BlockWithChildren` — interface for any block that contains child blocks (used for traversal)

Concrete blocks live in `blocks/` under sub-packages: `expr/`, `flow/`, `func/`, `loop/`, `misc/`, `var/`.

Each `AbstractCodeBlock` holds an `ASTNode` and a stable `id` (generated by `BlockId`). A concrete block only
implements `createUINode(CodeEditorService)` to return its **raw** content; `getUINode(CodeEditorService)`
lazy-creates that node once, then runs an ordered list of `core/render/BlockDecorator`s
(`GutterDecorator` → `ReadOnlyDecorator` → `InteractionDecorator`) that layer on the cross-cutting concerns
(left gutter + breakpoint circle, read-only marking, right-click menu, tooltip). Per-block visual *state*
(highlight / error / breakpoint / read-only) is driven by JavaFX **pseudo-classes** toggled on the root node,
styled by `src/main/resources/css/blocks.css` — not by inline-style string mutation.

### AST ↔ Block Synchronisation

The round-trip between Java source and visual blocks:

1. **Source → Blocks**: `BlockFactory` + `BlockParser` walk an Eclipse JDT `CompilationUnit` and create `CodeBlock` instances. `BlockFactory.parseBodyBlock()` is the recursion entry point. A `Map<ASTNode, CodeBlock>` is maintained as the canonical registry.
2. **Blocks → Source**: `CodeEditor` applies mutations (backed by Eclipse JDT `ASTRewrite`), delegating to the `parser/handlers/*` and its own pure transforms; `NodeCreator` creates new AST nodes. `CodeEditor` drives the overall write operation and publishes `CodeUpdatedEvent`.
3. **Sync flow**: User drops a block → `BlockDragAndDropManager` resolves the drop target → `CodeEditorService` calls `CodeEditor` → `CodeEditor` rewrites the AST → `CodeUpdatedEvent` published → `CodeEditorService` refreshes the UI by re-parsing the source file.

The `parser/handlers/` classes handle specialised AST mutations (method signatures, type replacements, enum manipulation, etc.).

### Rewriting pipeline (parser package)

The write path is `CodeEditor` (public, per-edit API) → `parser/handlers/*` + `CodeEditor`'s own `private static`
transforms (bespoke AST shapes) + `NodeCreator` (which owns the `parser/factories/*`) → `AstRewriteHelper.applyRewrite`.
Every rewrite is a pure transform: it takes `(CompilationUnit cu, String originalCode, …)` and returns the new source
string. `CodeEditor` is the only stateful layer — its `edit(markUnedited, op)` helper wraps each call with
`canModify()` and `triggerUpdate()` (publishes `CodeUpdatedEvent`), so individual methods are one-liners.

Shared low-level rewrite primitives live in `AstRewriteHelper` (`applyRewrite`, `removeNode`, `renameSimpleName`,
`getListRewriteForBody`) — reuse these rather than re-implementing `ASTRewrite` boilerplate. The stateless handlers
`OperatorReplacementHandler` and `EnumManipulationHandler` expose only static methods.

**Remaining cleanup opportunities** (favor the functional-OOP guideline above):

- **`BlockFactory` scratch fields (reentrancy smell):** it keeps per-`convert()` state in mutable fields (`ast`,
  `currentSourceCode`, `allComments`, `blockParser`, `isReadOnlyMode`, `markNewIdentifiersAsUnedited`).
  `markNewIdentifiersAsUnedited` is toggled by `CodeEditor` via a setter before each edit and reset in a `finally`
  (temporal coupling). Prefer threading a per-call context object instead of holding these as fields.
- **`StatementFactory` library imports:** the vision-type creators no longer emit imports for `Point`/`Rect`/etc.
  (the old `resolveLibraryFQN` was a `""` stub). If those types ever live outside the default package, add real
  FQN resolution (e.g. via `ProjectAnalyzer`/`TypeSummary`) at the creation sites.
### Suggestion / Autocomplete Pipeline

`ProjectAnalyzer` is the single entry point for all type-aware suggestions. It combines:

- **Library index** (`TypeSummaryManager` / `TypeSummary`) — lightweight summaries of external jar types read directly from bytecode via **ClassGraph** (no decompilation), serialised and cached per-jar under the BotMaker cache dir
- **Project AST** (`CompilationUnitAnalyzer`) — live `ITypeBinding` resolution from the user's own source

The owning `CodeEditorService` is passed directly into every block's `getUINode()` call, giving blocks access to the `CodeEditor`, event bus, drag-and-drop manager, project state, and `ProjectAnalyzer` without requiring service locators. (It earlier threaded a dedicated `CompletionContext` record, which was just a partial copy of the service and has been removed.)

The type-aware context menus (insert/replace an expression, pick a method/constructor/enum/variable, choose a
type) are built by `ui/render/menu/ExpressionMenu`, which reads `ProjectAnalyzer`. A menu pick is emitted
either as a sealed `palette/ExpressionType` (a plain palette entry, from `ExpressionCatalog`) or as a sealed `parser/ExpressionChoice`
(`Method` / `Constructor` / `EnumConstant` / `Variable`); `AbstractCodeBlock.applyExpressionSelection` dispatches it
to the matching `CodeEditor.replaceWith…` call with an exhaustive `switch`.

**Planned: drop `TypeSummary`, consume ClassGraph directly.** The `TypeSummary`/`MethodSummary`/`FieldSummary`
records are a hand-rolled DTO mirroring what ClassGraph already models, plus a hand-rolled per-jar Java-serialized
(`.ser`) cache (`saveJar`/`loadFromFile` via `ObjectOutputStream`). The intended direction is to remove this
duplication and let ClassGraph own both the model and the persistence:
- Cache the scan with ClassGraph's built-in `ScanResult.toJSON()` / `ScanResult.fromJSON(String)` instead of the
  custom `.ser` files — deletes `saveJar`, `saveAll`, `loadFromFile`, `getCacheFileForJar`'s `.ser` logic, and the
  `Serializable` requirement.
- Have `ProjectAnalyzer` consume `ClassInfo`/`MethodInfo`/`FieldInfo` directly; the sealed
  `ProjectAnalyzer.ResolvedMethod`/`ResolvedField` `FromIndex(...)` variants would wrap ClassGraph types instead of
  `TypeSummary.MethodSummary`/`FieldSummary`.
- Delete the `TypeSummary` record and the `toSummary`/`toMethodSummary` mappers in `TypeSummaryManager`.

Accepted tradeoff: this couples `ProjectAnalyzer` to ClassGraph (today `TypeSummary` is an anti-corruption boundary
that kept the CFR→ClassGraph swap isolated to `TypeSummaryManager`). The win is much less of our own code — no DTO,
no mapper, no bespoke serialization. Note ClassGraph still leaves cache *policy* (where/when/invalidate) to us; only
the serialize/deserialize step is outsourced.

### Event Bus

`EventBus` is instantiated per project (not a singleton). Events are defined in `CoreApplicationEvents` as nested classes. Subscribe with `eventBus.subscribe(EventClass.class, handler)`. The optional `runOnFxThread` flag wraps delivery in `Platform.runLater()`. A handler that throws is logged at `SEVERE` with the event name and cause and the publish continues — on **both** branches; the guard lives in the delivery, not around the call, because on the `runLater` branch `publish` has already returned by the time the handler runs. `subscribeAll()` receives every event (used by the event log panel).

### UI Structure

The `ui/` package is split by concern:

- **`ui/app/`** — the application shell. `UIManager` is the *coordinator*: it assembles the main window out
  of the collaborators below and releases what that window acquired (`dispose()`) — which matters because a
  new one is built on every project open **and every reload**, so anything it holds and doesn't release is
  leaked per reload. It builds and hands callbacks to `EditorCanvas` (the block canvas, its scroll position
  and the Reader banner), `DiagnosticsPanel` (the Errors tab), `IdentityCluster` (accounts + the theme
  dropdown; owns one of the window's two `BlockTheme` listeners), `StudioActions` (every menu/toolbar action
  in one wiring table, plus the GitHub/sharing services that back them), `ProjectRecoveryAction`
  (**Project ▸ Recover Project Files**) and `WorkspaceLayoutStore` (the persisted dividers + open bottom tab);
  none of them holds a reference back. `BottomTab` is the closed set of bottom tabs. Alongside those: the
  panel/screen managers `FileExplorerManager` (project file tree), `MenuBarManager` / `ToolbarManager` (menus
  and toolbar; the **Project → Manage Libraries…** entry lives here), `EventLogManager` (runtime event/output
  log), `ProjectSelectionScreen`, `VcsPanel` / `GitHubAccountBar` / `GoogleAccountBar`, and ~15 dialogs
  (`ProjectSetupDialog`, `LaunchTargetDialog`, `ManageCaptureTargetsDialog`, `ManageLibrariesDialog`,
  `ResourceManagerDialog`, `PublishDialog`, `GalleryDialog`, …). The open-time source migrations are **not**
  here — they are `project/ProjectOpenMigrations`, run from the shell's constructor before
  `FileExplorerManager` exists, since a migration can delete a file the tree would otherwise go on listing.
  There is **no `PaletteManager`** — this entry named one for a long time and no such file has ever existed;
  the insertable catalogs are `palette/` below, and the overlay's own palette bar is
  `ui/app/overlay/OverlayPalette`.
- **`ui/app/pilot/`** — **Remote Pilot**: driving the bot from a phone. `RemotePilotUi` is the bring-up state
  machine and the owner of the two OS resources (`PilotServer`'s bound port, `NestedSessionLauncher`'s nested
  X display) — it is `AutoCloseable`, and `UIManager.dispose()` is what closes it. `RemotePilotDialog` (the
  pairing dialog: URL, QR, token reset), `FunnelSetupWizard` (the Tailscale Funnel steps, rendered from a
  `FunnelDiag`; makes no CLI calls itself) and `BackgroundModeBox` (the private-display controls) are pure
  rendering over its records.
- **`ui/app/capture/`** — the screen-capture feature: `OverlayTemplateCapture` (the on-screen capture toolbar)
  over `CaptureSurface` / `ObjectCaptureSurface` (rect and contour selection), `MagicWand`, `ColorSampler`,
  `ZoomPan`, `CaptureSourcePicker`, `TargetThumbnail`, `GameFrame`, `BatchTemplateNamingDialog`.
- **`ui/app/flow/`** — the activity-flow graph editor: `FlowCanvas` (nodes, ports, edges, auto-arrange),
  `FlowRules`, `FlowNames`, `ActivityDraft`, `ActivityValueWidgets`, `NewActivityDialog`.
- **`ui/app/overlay/`** — the **Overlay Editor**: the always-on-top HUD that mirrors the program as one-line
  rows over the running game, and the only place a bot can be authored or recorded without leaving it.
  `ProgramShapeOverlay` is the *coordinator* — the stage, the event subscriptions, and the FX-thread-confined
  state that sequences an edit against the re-parse it causes. Everything else is a collaborator it constructs
  and hands callbacks to; none of them holds a reference back to it:
  `BlockTree` (**pure, no JavaFX** — the tree model and the flattened row list, so the placement rules that
  fail silently are testable headlessly), `OverlayTreeView` (the rows), `OverlayTargetPicker` (which activity
  and method blocks land in), `OverlayPalette` (the SDK facade chips + ＋ Add block),
  `ArgumentConfigPopover`, `OverlayRecorder` (Record/Pause/Stop over `services/record/RecordingSession`),
  `RecordedBatchInserter` (inserts a recorded batch one block per re-parse), `OverlayHeader`,
  `OverlayHotkey` (the global `F9`), `OverlayStyles` and `OverlayToolbars` (shared with `capture/`).
- **`ui/dnd/`** — drag-and-drop and block input events: `BlockDragAndDropManager`, `DropInfo`, `MoveBlockInfo`,
  `BlockEvent`, `DropZoneFactory`.
- **`palette/`** (top-level, dependency-light) — the insertable catalogs: `BlockType`/`BlockCatalog`/`BlockCategory`
  and `Initializer` for statements, `ExpressionType`/`ExpressionCatalog`/`ExpressionCategory` for expressions.
- **`ui/render/`** — block rendering: `layout/` (the fluent `BlockLayout` DSL — only `header()`/`sentence()`,
  with `HeaderLayoutBuilder.andBody()`, are live), `components/` (pure JavaFX widget factories, e.g.
  `BlockUIComponents`), `menu/` (`ExpressionMenu` fills an expression slot, `StatementMenu` inserts a block,
  `MenuBuilders` is their shared plumbing and `MenuIcons` the single glyph lookup), and `theme/` (theming constants;
  `Spacing.gutter()` is the single source of the block gutter width).

Cross-cutting block decoration lives in `core/render/` (the `BlockDecorator` pipeline, see **Block System**), and
block state styling lives in `src/main/resources/css/blocks.css`. That file also carries the **window
chrome** — the toolbar's hairline, the status line, the Errors filter bar and the diagnostic rows — as classes
over the `-bm-*` design tokens each theme redefines. Style the shell there, never with `setStyle`: an inline
style beats the stylesheet in *every* theme, which is exactly how the toolbar border came to override its own
token-driven rule and the Errors bar came to stay light grey in Dark.

### Library Management

The user can add/remove third-party dependencies from the GUI (**Project → Manage Libraries…**,
`ui/app/ManageLibrariesDialog`). The design keeps Maven simple:

- **The `pom.xml` is the single source of truth.** A "user library" is just any dependency in the pom that isn't
  one of `MavenService.DEFAULT_DEPENDENCIES`. There is no separate store file. `UserLibrary` is an immutable
  `record(groupId, artifactId, version)`; `MavenService.readUserLibraries` / `writeUserLibraries` read and rewrite
  the non-default dependencies in place (defaults, repositories and properties are preserved).
- **`LibraryService.updateUserLibraries`** runs the slow work off the FX thread: write pom → `resolveClasspath` →
  `ProjectState.setResolvedClasspath` → `TypeSummaryManager.refresh` (incrementally indexes the new jars) →
  publish `LibrariesChangedEvent`.
- **`MavenCentralSearch`** provides IntelliJ-style autocomplete in the dialog via the Maven Central Solr API,
  using the JDK's built-in `java.net.http.HttpClient` + Jackson (no new dependencies). All calls are async and
  best-effort — network failures resolve to empty results.

### Validation

`DiagnosticsManager` holds the current set of compiler diagnostics. `ErrorTranslator` maps Eclipse JDT error codes to user-friendly messages. Diagnostics are surfaced to blocks via `CodeBlock.setError()` / `clearError()`.
