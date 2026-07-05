# BotMaker Studio

A visual, block-based programming environment for building **PC automation bots** in Java — no syntax to memorize.
Drag and drop blocks to assemble real Java, get IDE-grade autocomplete and error checking, then run or debug your
bot in one click.

BotMaker Studio is built with **JavaFX**, generates and edits real Java via the **Eclipse JDT** AST, and ships
with the **[BotMaker SDK](https://github.com/LiQiyeDev/BotMaker-sdk)** for screen vision (OpenCV template
matching), mouse, and window control.

## Features

- 🧩 **Visual block programming** — drag and drop blocks; the Studio writes and edits real Java source for you
- 🔍 **Type-aware autocomplete** — method/constructor/variable/enum suggestions from your code *and* every library
  on the classpath, indexed straight from bytecode (ClassGraph)
- 🤖 **Bot SDK built in** — screen capture, image-template matching, click/wait/find helpers from the BotMaker SDK
- 🖼️ **Image-template tooling** — capture the screen, crop a target, and save it as a named template asset, all
  in-app
- 🐛 **Built-in debugger** — set breakpoints on blocks and step through with visual highlighting (JDI)
- ⚡ **Real-time diagnostics** — Eclipse JDT compiles your code in-process and surfaces errors/warnings on the
  blocks themselves
- 📦 **Library management** — add Maven dependencies from a GUI with live Maven Central autocomplete; no restart
- 📁 **Multi-project support** — each project is a standard Maven project, openable in any IDE

## Download

Grab a self-contained build from the [Releases](https://github.com/LiQiyeDev/BotMaker-Studio/releases) page. It
**bundles its own Java + JavaFX runtime**, so there's nothing else to install — unzip and run the launcher. Builds
are per-OS; pick the one matching your platform.

To build a release yourself, see [Packaging a Release](#packaging-a-release).

## Building from Source

### Requirements

- **JDK 21** or newer (only to *build* — released app-images bundle their own runtime)
- **Maven 3.9+** (or an IDE that bundles Maven)
- **Linux, macOS, or Windows** (Linux is the primary development platform)

JavaFX is pulled in as a Maven dependency — no separate JavaFX SDK and no JavaFX-bundled JDK required.

### Clone & run

```bash
git clone https://github.com/LiQiyeDev/BotMaker-Studio.git
cd BotMaker-Studio
mvn javafx:run
```

Common Maven tasks:

```bash
mvn compile     # build
mvn test        # run the test suite (JUnit Jupiter)
mvn package     # build the shaded (fat) jar under target/
```

## Creating a Bot

1. Launch the Studio and create a new project from the selection screen (e.g. `MyBot`).
2. Assemble logic by dragging blocks from the palette: variables, `if`, loops, prints, and SDK calls.
3. Use the **BotMaker SDK** blocks/menus to find images on screen, click, and wait.
4. Hit **Run** to execute, or set breakpoints and **Debug** to step through.

Projects are scaffolded as standard **Maven** projects under `~/BotMakerProjects/<ProjectName>/`:

```
~/BotMakerProjects/MyBot/
├── src/main/java/com/mybot/MyBot.java
└── pom.xml
```

The `pom.xml` is generated programmatically (Maven Model API) and the classpath is resolved in-process with Maven
Resolver (Aether) — **no system `mvn` binary is required**. Any folder with that layout shows up in the selection
screen.

## Managing Libraries

Open **Project → Manage Libraries…** to add or remove dependencies:

- Type a coordinate to get **live Maven Central suggestions** (group / artifact), then pick a version.
- **Apply** rewrites the project `pom.xml`, re-resolves the classpath, and refreshes the type index so the new
  library's types appear in block autocomplete — no restart.

The `pom.xml` is the single source of truth: your libraries are simply the dependencies that aren't built-in
defaults. The pinned BotMaker SDK row is non-removable; its version is editable and sourced from JitPack.

## How It Works

BotMaker Studio is organized around a **per-project event bus** with a clean service layer. Opening a project
(`BotProject.open()`) is the composition root: it builds `ProjectConfig`, `ProjectState`, the `EventBus`, the
Maven/classpath services, the type index, the analyzer, and the editor/execution services, then the UI.

### Block system

Every visual block implements `CodeBlock` → `AbstractCodeBlock`:

- **`StatementBlock`** — executable statements (if, while, print, variable declaration…)
- **`ExpressionBlock`** — value-producing expressions (literals, identifiers, binary ops…)
- **`BodyBlock`** — a container of statements, i.e. a `{ … }` block

### AST ↔ block synchronization

- **Source → blocks:** the parser walks an Eclipse JDT `CompilationUnit` and builds `CodeBlock` instances.
- **Blocks → source:** `CodeEditor` applies mutations via JDT `ASTRewrite` and publishes a `CodeUpdatedEvent`.
- **Round trip:** drop a block → drag-and-drop resolves the target → `CodeEditorService` calls `CodeEditor` →
  the AST is rewritten → the event fires → the UI re-parses the source and refreshes.

### Suggestions

`ProjectAnalyzer` is the single entry point for type-aware suggestions, combining a **library index** (external
jar types read from bytecode with ClassGraph, cached per-jar) with **live AST resolution** from the user's own
source.

### Execution & debugging

- **Run** — compiles and runs the project in a separate JVM, streaming output to the event log.
- **Debug** — uses the Java Debug Interface (JDI) with breakpoints set on blocks and visual step highlighting.

For a deeper dive into the architecture, see [`CLAUDE.md`](CLAUDE.md). The living backlog and changelog are in
[`ROADMAP.md`](ROADMAP.md).

## Packaging a Release

The `dist` Maven profile produces a **self-contained app-image** (bundled Java + JavaFX runtime) via `jpackage`, so
end users need no JDK and no JavaFX install:

```bash
mvn -Pdist package
# → target/dist/BotMaker Studio/   (run the launcher inside)
```

**Releases are built automatically by CI.** Pushing a `v*` tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`)
runs `.github/workflows/release.yml`, which builds the app-image on both Linux and Windows runners and attaches the
two zips to a GitHub Release. Tags containing a hyphen (e.g. `v1.0.0-rc1`) publish as pre-releases.

Notes:

- A small `com.botmaker.studio.Launcher` (which does *not* extend `Application`) is the jar/app-image entry point —
  launching an `Application` subclass directly from a fat jar fails with "JavaFX runtime components are missing".
- The bundled runtime is the **full build JDK** (`--runtime-image ${java.home}`), not a stripped JRE — the Studio
  shells out to `javac`/`java` and uses JDI to compile, run and debug user bots, so those tools must be present.
- `jpackage` builds **only for the OS it runs on** — run the profile on each platform you want to ship.
- Pass `-Djavacpp.platform=<host>` (e.g. `linux-x86_64`, `windows-x86_64`) to ship host-only OpenCV natives
  instead of every platform's — this cuts the app-image roughly in half (~1.2 GB → ~580 MB). CI does this per leg.
- For a native installer instead of the portable directory, change `<type>` in the `dist` profile from
  `APP_IMAGE` to `DEB`/`RPM` (Linux), `MSI`/`EXE` (Windows), or `DMG`/`PKG` (macOS).

## Project Structure

```
BotMaker-Studio/
├── pom.xml                          # Maven build (the Studio itself)
├── src/main/java/com/botmaker/
│   ├── BotMakerStudio.java          # JavaFX Application entry point
│   ├── blocks/                      # Concrete blocks: expr/ flow/ func/ loop/ misc/ var/
│   ├── core/                        # CodeBlock hierarchy + render/ (decorator pipeline)
│   ├── palette/                     # Insertable block/expression catalogs
│   ├── parser/                      # AST ↔ block sync; CodeEditor + handlers/ factories/ helpers/
│   ├── project/                     # BotProject, ProjectConfig/State, activity/
│   ├── services/                    # CodeEditorService, LibraryService, Execution/Debugging…
│   ├── runtime/                     # Compile / run / debug (JDI)
│   ├── index/, types/, suggestions/ # Type index + analyzer + suggestion pipeline
│   ├── events/                      # Per-project EventBus + CoreApplicationEvents
│   ├── sharing/                     # Project/gallery sharing
│   ├── ui/                          # app/ (shell, menus, dialogs) dnd/ render/
│   ├── config/, state/, util/, validation/
└── src/main/resources/css/blocks.css  # Block state styling (highlight/error/breakpoint/read-only)

# User projects live OUTSIDE the repo, as standard Maven projects:
~/BotMakerProjects/<ProjectName>/
├── src/main/java/com/<projectname>/<ProjectName>.java
└── pom.xml
```

## Troubleshooting

**No projects in the selection screen**
A project must live under `~/BotMakerProjects/` with the layout
`src/main/java/com/<projectname>/<ProjectName>.java` and a `pom.xml`.

**SDK types missing from autocomplete**
Open **Project → Manage Libraries…** and confirm the BotMaker SDK version is set; applying refreshes the type
index. The SDK resolves from JitPack, so a network connection is needed the first time a version is fetched.

## Contributing

1. Fork and branch (`git checkout -b feature/my-feature`).
2. Keep the functional-OOP style: prefer immutable values and pure transforms; push side effects to the service
   layer (see [`CLAUDE.md`](CLAUDE.md) → Code Style).
3. Add tests (JUnit Jupiter) and run `mvn test`.
4. Open a pull request.

> **Note:** the BotMaker SDK is published to JitPack by the maintainer. Don't tag or publish the SDK yourself; the
> `0.0.0-SNAPSHOT` pin is intentional.

## License

Licensed under the MIT License — see [`LICENSE`](LICENSE).

## Acknowledgments

- **Eclipse JDT Core** — in-process Java parsing, AST manipulation, and compilation/diagnostics
- **ClassGraph** — fast bytecode-level type indexing
- **OpenCV** (via the BotMaker SDK) — screen vision / template matching
- **JavaFX** — desktop UI
- **Scratch / Blockly** — inspiration for block-based programming
