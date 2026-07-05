# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Planning

At the end of the planning stage, write the plan to a dedicated plan file before starting implementation,
so work can be resumed if a session is interrupted.

## Roadmap

`ROADMAP.md` (repo root) is the living backlog + changelog for both the Studio and the SDK. **After completing a
meaningful change, update it:** add a dated entry to the top of the **Completed** section (date — what changed —
where), and check off / remove the corresponding backlog item if it's now done. Keep entries to 1–3 lines. New
backlog ideas that surface during work go under the relevant backlog section.

## Commands

```bash
# Build
./gradlew build

# Run the application
./gradlew run

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "TypeAwareSuggestionTest"

# Run a single test method
./gradlew test --tests "TypeAwareSuggestionTest#methodName"

# Build a distributable
./gradlew installDist
```

Tests run with JUnit Jupiter. The test task uses `useJUnitPlatform()`.

## Code Style

Prefer minimizing mutable state — favor a functional OOP style. Use immutable values (`record`s like
`ProjectConfig`, `UserLibrary`) and pure transformations; pass dependencies in via constructors rather
than holding mutable fields or reaching for static/singleton state. Keep side effects (file I/O, process
launching, event publishing) at the edges in the service layer.

## Setup

The JDT Language Server must be installed at `tools/jdt-language-server/`:

```bash
mkdir -p tools
wget https://download.eclipse.org/jdtls/milestones/1.40.0/jdt-language-server-1.40.0-202410171240.tar.gz
mkdir tools/jdt-language-server
tar -xzf jdt-language-server-*.tar.gz -C tools/jdt-language-server
```

User projects live in `~/BotMakerProjects/` (not inside this repo). Each project is a standard **Maven** project with the layout `src/main/java/com/<projectnamelowercase>/<ProjectName>.java`. The BotMaker-Studio app itself is also a Maven project (`pom.xml`): build with `mvn compile`, run with `mvn javafx:run`, test with `mvn test`.

### BotMaker SDK dependency

The Studio depends on the **BotMaker SDK** (`com.github.LiQiyeDev:BotMaker-sdk`) pinned to `0.0.0-SNAPSHOT`. That SNAPSHOT is intentional — it's published to JitPack (via the SDK repo's GitHub Action) so it resolves on a clean checkout. **The maintainer owns the SDK→JitPack publish; don't push or publish the SDK yourself.**

This Studio repo is a submodule of the **`botmaker` umbrella repo**, which holds `botmaker-studio/` and `botmaker-sdk/` as sibling submodules plus an aggregator `pom.xml`. From the umbrella root, `mvn install` builds the SDK then the Studio in one reactor. **All SDK changes must be made through the umbrella's `botmaker-sdk/` submodule** — edit the sources there, commit inside the SDK submodule, then bump the SDK submodule pointer in the umbrella repo. Do not edit an external sibling checkout or vendor the SDK inside this Studio repo. For testing local SDK changes, run `mvn install` in the umbrella's `botmaker-sdk/` (checked before JitPack) or `mvn install` at the umbrella root.

The read-input blocks depend on a small SDK protocol: `BotMaker.readX()` prints a `BM-INPUT:<type>` marker (SOH-wrapped) to stdout before blocking on stdin. The Studio detects and strips that marker (`CodeExecutionService` for run, `DebuggingService` for debug), shows the modal input prompt, and writes the entered line back to the process's stdin via `SendInputEvent` → `sendInput(...)`. Changing the marker on either side without the other breaks input prompts.

## Architecture

### Project Lifecycle

`BotMakerStudio` (JavaFX `Application`) is the entry point. On launch it either re-opens the last project via `ProjectPreferences` or shows `ProjectSelectionScreen`. Opening a project goes through `BotProject.open()`, which is the composition root — it constructs all services and wires them together in order:

1. `ProjectConfig` — immutable record with all paths and JVM info for the project
2. `ProjectState` — mutable runtime state (current AST, classpath, highlighted block, etc.)
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
type) are built by `ui/render/menu/ExpressionMenuFactory`, which reads `ProjectAnalyzer`. A menu pick is emitted
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

`EventBus` is instantiated per project (not a singleton). Events are defined in `CoreApplicationEvents` as nested classes. Subscribe with `eventBus.subscribe(EventClass.class, handler)`. The optional `runOnFxThread` flag wraps delivery in `Platform.runLater()`. `subscribeAll()` receives every event (used by the event log panel).

### UI Structure

The `ui/` package is split by concern:

- **`ui/app/`** — the application shell: `UIManager` (builds the main scene) plus the panel/screen managers
  `PaletteManager` (block palette / drag sources), `FileExplorerManager` (project file tree),
  `MenuBarManager` / `ToolbarManager` (menus and toolbar; the **Project → Manage Libraries…** entry lives here),
  `EventLogManager` (runtime event/output log), `ProjectSelectionScreen`, and `ManageLibrariesDialog`.
- **`ui/dnd/`** — drag-and-drop and block input events: `BlockDragAndDropManager`, `DropInfo`, `MoveBlockInfo`,
  `BlockEvent`, `DropZoneFactory`.
- **`palette/`** (top-level, dependency-light) — the insertable catalogs: `BlockType`/`BlockCatalog`/`BlockCategory`
  and `Initializer` for statements, `ExpressionType`/`ExpressionCatalog`/`ExpressionCategory` for expressions.
- **`ui/render/`** — block rendering: `layout/` (the fluent `BlockLayout` DSL — only `header()`/`sentence()`,
  with `HeaderLayoutBuilder.andBody()`, are live), `components/` (pure JavaFX widget factories, e.g.
  `BlockUIComponents`), `menu/` (`ExpressionMenuFactory`, the type-aware menus), and `theme/` (theming constants;
  `Spacing.gutter()` is the single source of the block gutter width).

Cross-cutting block decoration lives in `core/render/` (the `BlockDecorator` pipeline, see **Block System**), and
block state styling lives in `src/main/resources/css/blocks.css`.

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
