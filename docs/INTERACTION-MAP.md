# BotMaker Studio — UI interaction map

A catalogue of **every interaction a user can perform** in the Studio JavaFX app, with the concrete
node hooks (style classes, control/menu text, window titles) needed to locate each in an automated
test, and a note on whether it is **headless-testable** (TestFX + Monocle) or needs the native
`testing/linux` harness.

> Companion to the headless test harness under
> `src/test/java/com/botmaker/studio/ui/fx/` (`FxHeadlessTest` base class). Keep this in sync when the
> UI changes — it is the source of truth for what the `ui/fx/` tests should cover.

## How to locate nodes in a test

There is exactly **one** `setId(...)` in the whole `ui/` tree: `#status-label`
(`UIManager.java:263`). Everything else is located by:

- **style class** — `.blocks-canvas`, `.body-block-separator`, `.separator-insert-button`, `.btn-run`,
  … (full list below);
- **control / label / menu-item text** — `"Open Project"`, `"▶ Run"`, `MenuItem("Print")`;
- **prompt text** on `TextField`/`ComboBox` — `"Search blocks…"`, `"ProjectName"`;
- **window title** — each dialog is its own modal `Stage` (`"Manage Libraries"`, `"Bot Gallery"`, …).

**Style-class hooks used across the UI:**
`main-toolbar, toolbar-btn, btn-run, btn-debug, btn-stop, blocks-canvas, code-scroll-pane,
console-area, file-explorer, sidebar-header, event-log-list, body-block, body-block-separator,
empty-body-placeholder, separator-insert-button, statement-block-header, executable-picker,
steam-game-picker, point-picker, rect-picker, image-template-picker, image-template-group-picker,
image-template-group-add, enum-arg-dropdown, launch-option-picker, comment-text-field,
variable-name-field, operator-selector, keyword-label, operator-label, type-label, argument-pill,
game-picker-tile/-cover/-name, light-theme, unedited-identifier`.

## Two architectural facts that shape every test

1. **There is no draggable palette panel.** `palette/` is a *data catalog* (`BlockType`,
   `BlockCatalog`), not a view. `BlockDragAndDropManager.makeDraggable(Node, BlockType)` (the
   palette-tile drag source) has **zero callers**. Blocks are inserted via the **statement menu**
   (mouse click, not DnD) and reordered via **real platform drag-and-drop** of existing blocks.
2. **`EXISTING_BLOCK_FORMAT` moves use JavaFX platform DnD** (`startDragAndDrop`/`Dragboard`), which
   TestFX's synthesized `drag()/drop()` does **not** drive reliably — reorder is asserted at the
   **event/service layer**, not as a rendered gesture.

---

## A. Statement menu — insert a new block  *(known interaction-bug hotspot)*

`ui/render/menu/ExpressionMenuFactory.createStatementMenu(Consumer<BlockType>)` (`:540`). A
`ContextMenu` whose first item is a `CustomMenuItem` wrapping a search `TextField` (promptText
**"Search blocks…"**, `setHideOnClick(false)`). `rebuildStatementItems(...)` (`:557`) repopulates per
keystroke: empty query → `BlockCatalog.botActions()` promoted flat, then category submenus; non-empty
→ flat filtered list. Leaf = `statementItem(...)` (`:603`): `MenuItem(block.displayName())` whose
`setOnAction` calls `onSelection.accept(block)`. `menu.setOnShown` focuses the search field.

**Triggers (both mouse-driven, not DnD, not right-click):**
- **"+" separator button** — `BlockDragAndDropManager.enableSeparatorClick(separator, onInsert)`
  (`:218`). The "+" `Button` (`.separator-insert-button`) is **hidden until hover**
  (`separator.setOnMouseEntered`, `:170`); click → `menu.show(btn, Side.BOTTOM, 0, 0)`. Handler:
  `codeEditor.addStatement(targetBody, type, insertionIndex)` (`BodyBlock.createSeparatorWithHandlers`).
- **Empty-body placeholder** — `BodyBlock.java:66` `placeholder.setOnMouseClicked` shows the same menu;
  handler `addStatement(this, type, 0)`. Placeholder label text: **"Click to add a block"**
  (`.empty-body-placeholder`).

**Bug hotspot:** the hover→visibility→menu z-order state machine around the "+" button (`setUserData`
stashes the open menu so `setOnMouseExited` won't hide the button; `setViewOrder(-100)` juggling;
`menu.setOnHidden` re-hide). Fragile — the most plausible home of a "menu disappears / can't click the
entry" report.

**Headless-testable:** yes — `moveTo(.body-block-separator)` → `clickOn(.separator-insert-button)` →
type in "Search blocks…" → `clickOn(MenuItem)`.

## B. Canvas — reorder an existing block (real platform DnD)

- Host: `UIManager.blocksContainer` (`VBox`, `.blocks-canvas`) inside `ScrollPane` `.code-scroll-pane`.
- Render: `core/BodyBlock.createUINode` interleaves `separator, statement, separator, …`; statement
  headers get `.statement-block-header` (`core/AbstractStatementBlock.java:44`).
- `ui/dnd/BlockDragAndDropManager`: `makeBlockMovable(Node, CodeBlock)` → `setOnDragDetected` →
  `startDragAndDrop(TransferMode.MOVE)`, block id on `Dragboard` under `EXISTING_BLOCK_FORMAT`
  (`"application/x-java-existing-block"`). `startsOnInteractiveControl(...)` (`:95`) **suppresses** the
  drag when it begins on a non-`Label` `Control` — grab the header/label, never an inner button/combo.
  Drop targets: `addSeparatorDragHandlers`, `addBlockDropHitbox` (top-half = above, bottom-half = below,
  via `isTopHalf`/`event.getY()`), `addEmptyBodyDropHandlers`, `addClassMemberDropHandlers`. Drops
  publish `BlockDropRequestedEvent`/`BlockMoveRequestedEvent`; `CodeEditorService` resolves them.
  Drag-over feedback = pseudo-classes `drag-over-copy` / `drag-over-move`.
- Unused: `ADDABLE_BLOCK_FORMAT` (`"application/x-java-addable-block"`, `makeDraggable` — not wired).

**Headless-testable:** partially — assert the move at the **event/service layer** (publish
`BlockMoveRequestedEvent` → assert generated source), not as a rendered drag.

## C. Per-block right-click menu

`core/render/InteractionDecorator.java:43` — `setOnContextMenuRequested` installs a `ContextMenu`:
**"Copy (Ctrl+C)"** (→ highlight + `CopyRequestedEvent`), **"Paste After (Ctrl+V)"** (→
`PasteRequestedEvent`), separator, bound **"Add/Remove Breakpoint"** (`block.toggleBreakpoint()`).
Breakpoints also toggle via a gutter circle (`GutterDecorator` → `BreakpointToggledEvent`).

## D. Startup / project selection

`ui/app/ProjectSelectionScreen.java` — constructor `(Stage, BiConsumer<String,Boolean>)`, no-op
callback OK. `createScene()` builds title **"Select a Project"** and buttons **"Open Project"** (default,
→ `onProjectSelected.accept(name, false)`), **"Create New Project"** (→ create dialog), **"Browse
Gallery"** (→ `GalleryDialog`), an **"Archive"/"Restore"** button, a **Sort** `ComboBox`, **"My projects
only"** / **"Show archived projects"** checkboxes, a `ListView<Row>` (double-click a project row →
open), and an embedded `GitHubAccountBar`.

⚠️ Rows and login touch **filesystem** (`~/BotMakerProjects`) and **GitHub network/auth** with no
injection seam — assert only on the static controls (as `ProjectSelectionScreenSmokeTest` does).

**Create-New-Project dialog** (inline `Dialog<CreateRequest>`, `ProjectSelectionScreen:404`), title
"Create New Project": a `TextField` (prompt **"ProjectName"**) live-validated by regex
`^[A-Z][a-zA-Z0-9]*$`, length 2–50, not-already-existing — border turns green/red and enables/disables
the **"Create"** button; an editable **"BotMaker SDK version"** `ComboBox`. Good **network-free**
validation test target.

**GitHubAccountBar** (`ui/app/GitHubAccountBar.java`, shared with `PublishDialog`): "Sign in with
GitHub" (OAuth device flow), "Sign out", "Switch account"; status label cycles auth states. **Network.**

## E. Main editor window: menus, toolbar, panels, shortcuts

**Scene** `ui/app/UIManager.java`: root `VBox`(menuBar, toolbar, mainSplit, `#status-label`), root style
`light-theme`, `/css/blocks.css`, 1000×700.

**Menu bar** (`MenuBarManager`):
- **File** — "Select Project…" (`Ctrl+Shift+O`), "Exit" (`Ctrl+Q` → `Platform.exit()`).
- **Edit** — "Undo" (`Ctrl+Z` → `UndoRequestedEvent`), "Redo" (`Ctrl+Y` → `RedoRequestedEvent`);
  Cut/Copy/Paste are **disabled placeholders**.
- **View** — Zoom In/Out/Reset = **disabled placeholders**.
- **Project** — Manage Libraries…, Manage Imports…, Manage Activities…, Set Activity Values…,
  Resource Manager…, Browse Gallery…, Publish to Gallery…, Project Repository on GitHub (disabled until
  published).
- **Help** — Studio/SDK on GitHub, "Check for Updates…" (`UpdateService`, modal progress `Stage`),
  "About BotMaker".

**Toolbar** (`ToolbarManager`, `.main-toolbar`) — all publish on the `EventBus`:
- Undo "↶", Redo "↷", **"⚙ Compile"** (`CompilationRequestedEvent`).
- **"🎯 Capture Targets"** → `ManageCaptureTargetsDialog`.
- **"▶ Run"** (`.btn-run` → `ExecutionRequestedEvent`), **"🐞 Debug"** (`.btn-debug` →
  `DebugStartRequestedEvent`), **"⏹ Stop"** (`.btn-stop`, disabled idle → `Stop/DebugStopRequestedEvent`),
  **"⤵ Step"**, **"⏩ Cont"** (both debug-paused only). Enable/disable is **IDLE/RUNNING/DEBUGGING
  state-driven** via `ProgramStarted/Stopped`, `DebugSession*`, `HistoryStateChanged` events.

**Left panel** (`FileExplorerManager`, `.file-explorer`): header "Project Files" (`.sidebar-header`);
**"New Function Library"** button → `TextInputDialog` → `codeEditorService.createFile`; `TreeView<Path>`
— select file → `switchToFile`; right-click a non-library file → context menu **"Delete File"**.

**Bottom `TabPane`** (3 non-closable tabs): **"Terminal"** (`.console-area`, read-only, right-click
Copy/Clear), **"Errors"** (filter `ToggleButton`s "Errors/Warnings/Infos (n)"; click a `Diagnostic` row
→ focuses its block), **"Event Log"** (`.event-log-list`, multi-select read-only). Auto-selects Terminal
on `ProgramStartedEvent`/`DebugSessionStartedEvent`, Errors on any error diagnostic.

**Global shortcuts** (scene `KEY_PRESSED` filter, suppressed while focus is in a `TextInputControl`):
`Ctrl/Cmd+C` → `CopyRequestedEvent`, `Ctrl/Cmd+V` → `PasteRequestedEvent`.

**Bot input prompt:** `InputRequestedEvent` → modal `TextInputDialog` "Bot needs input" →
`SendInputEvent`.

## F. In-block argument editors / pickers

Dispatched by `ArgumentEditors.editorFor` → `PickerRegistry`. Each is a direct-interaction control,
located by style class:

| Widget | File | Style class | Interaction |
|---|---|---|---|
| Point picker | `components/PointPicker.java` | `point-picker` | "Pick on screen…" (**native capture**) / "Edit values…" (`NumberFieldsDialog` x,y) |
| Rect picker | `components/RectPicker.java` | `rect-picker` | region drag (**native**) / "Edit values…" (x,y,w,h) |
| Image template picker | `components/ImageTemplatePicker.java` | `image-template-picker` | pick template / "Capture new…" (**native**) / Resource Manager |
| Image template group | `components/pickers/ImageTemplateGroupPicker.java` | `image-template-group-picker`, `image-template-group-add` | per-index menu + "+" add |
| Enum dropdown | `components/pickers/EnumPicker.java` | `enum-arg-dropdown` | select constant |
| Executable picker | `components/ExecutablePicker.java` | `executable-picker` | "Browse for program…" (**file dialog**) / "Enter path…" |
| Steam game picker | `components/SteamGamePicker.java` | `steam-game-picker` | opens `GameLibraryPickerDialog` (**Steam scan**) |
| Launch option | `components/LaunchOptionPicker.java` | `launch-option-picker` | `TextField`, Enter commits |
| Comment field | `components/TextFieldComponents.java` | `comment-text-field` | Enter → commit |
| Variable-name field | `components/TextFieldComponents.java` | `variable-name-field` | rename identifier |
| Operator selector | `components/SelectorComponents.java` | `operator-selector` | pick operator symbol |

Two more searchable popup menus mirror the statement menu — the **expression "change value" menu**
(`ExpressionMenuFactory.createExpressionTypeMenu`, prompt "Search…") and the **type-picker menu**
(`showTypeMenu`, prompt "Search types…", with "Add/Remove Dimension []"). Supporting modal:
`components/NumberFieldsDialog.java`.

## G. Dialogs (each a modal `Stage`, located by window title)

| Dialog | Title | Heavy dep |
|---|---|---|
| `GalleryDialog` | "Bot Gallery" (Browse/Installed tabs) | **network** |
| `ManageLibrariesDialog` | "Manage Libraries" (`TableView` Group/Artifact/Version) | **JitPack/Maven network** on version load |
| `ManageImportsDialog` | "Manage Imports" (list + autocomplete add) | network-free |
| `ManageActivitiesDialog` | "Manage Activities" (editable table) | network-free |
| `SetActivityValuesDialog` | "Set Activity Values" (per-type widgets) | network-free |
| `ManageCaptureTargetsDialog` | "Capture Targets" | **native** window enumeration |
| `ResourceManagerDialog` | "Resource Manager — Image Templates" | **native** capture |
| `PublishDialog` | "Publish to Gallery" (form + `GitHubAccountBar`) | **GitHub network** |
| `GameLibraryPickerDialog` | "Choose a … game" | **Steam library scan** |

---

## Testability tiers

- **Tier A — Studio JavaFX UI (headless-testable, TestFX + Monocle):** screens/dialogs render, controls
  respond to clicks/keystrokes, UI events wire to services. Everything above **except** the native/network
  items flagged in-line.
- **Tier B — native window plumbing (`botmaker-shared`: X11/uinput enumerate/capture/focus/input, and
  the X11-vs-Wayland gap):** needs a real display server — validated manually via `testing/linux`
  x11docker. Screen-capture pickers (point/rect/image), capture-targets window enumeration, and screen
  region drags route here.

### Reusable headless fixture recipe

Build an in-memory editable project with real AST, no JavaFX/network/native (from
`test/java/BlockDragDropEditTest`):

```
new ProjectState()
  → addFile(new ProjectFile(path, SOURCE)) → setActiveFile → setSourcePath
  → setResolvedClasspath(TestSupport.runtimeClassPath())
new EventBus(false)                          // synchronous, FX-thread-free; subscribe CodeUpdatedEvent
new BlockConverter(state).convert(SOURCE, state.getMutableNodeToBlockMap(),
                                  new BlockDragAndDropManager(bus), false, false)
  → state.setCompilationUnit(result.cu());  result.root() is the root block
new CodeEditor(state, bus, new ProjectAnalyzer(null, state))   // (null skips jar indexing, keeps bindings)
```

**Never instantiate in a headless UI test:** `BotProject.open()` (full composition root — Maven resolve,
type indexing, JDI), `ExecutionService`/`DebuggingService` (spawn JVMs/JDI), and any `botmaker-shared`
window/screen capture (Tier B).
