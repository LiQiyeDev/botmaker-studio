# BotMaker Studio

A bot watches a game or app on your screen, decides what it sees, and clicks. **BotMaker Studio** is the
desktop IDE you build one in: assemble the logic from visual blocks, and Studio writes real Java for you — a
standard Maven project you could open in any IDE.

A bot is not a script that runs top to bottom. You break it into **activities**, wire their outcomes together
in the **Activity Flow**, and the generated driver walks that graph — so what runs next is what you drew.

What a bot can drive: a PC game from Steam, Epic, Heroic or Faugus, any executable or command line, or an app
inside an Android emulator. It can run on a **private display** so it doesn't take over your desktop, and you
can watch and drive it from your phone.

Built with **JavaFX**, editing real Java through the **Eclipse JDT** AST, and seeing the screen through the
**[BotMaker SDK](https://github.com/LiQiyeDev/BotMaker-sdk)** — OpenCV template matching, OCR, mouse, keyboard
and window control.

## Features

- 🧩 **Visual block programming** — drag and drop blocks; Studio writes and edits real Java source for you
- 🔀 **Activity flow** — break a bot into activities and wire their outcomes on a canvas; the graph is what runs
- 🎯 **Launch targets** — pick a game from your installed Steam/Epic/Heroic/Faugus libraries, an executable, a
  command line, or an app inside an emulator
- 🖥️ **Capture targets** — a window, a screen region, an emulator, or a private display the bot has to itself
- 🖼️ **Vision** — capture the screen, crop a target, name it, and reference it as a constant; OCR is built in,
  with its native library bundled rather than borrowed from the host
- ✍️ **Overlay Editor** — author blocks on top of the running game instead of beside it
- 🔍 **Type-aware autocomplete** — suggestions from your own code *and* every library on the classpath, indexed
  straight from bytecode (ClassGraph)
- ⚡ **Diagnostics and debugging** — JDT compiles in-process and surfaces errors on the blocks themselves; set
  breakpoints on blocks and step through with visual highlighting (JDI)
- 📱 **Remote Pilot** — watch and drive a run from your phone
- 🕘 **Project history** — a refactor that touches files you aren't looking at snapshots first, so it reverts
- 📦 **Library management** — add Maven dependencies from a GUI with live Maven Central autocomplete, no restart
- 🚀 **Publish and browse** — share a bot, or install one from the gallery

## Download

Grab a self-contained build from the [Releases](https://github.com/LiQiyeDev/BotMaker-Studio/releases) page. It
**bundles its own Java + JavaFX runtime**, so there's nothing else to install — unzip and run the launcher.
Builds are per-OS; pick the one matching your platform.

### Linux: install from the package repository

Fedora/RHEL and Debian/Ubuntu can take Studio from a signed repository instead, so updates arrive through the
system package manager:

```bash
curl -fsSL https://liqiyedev.github.io/botmaker-studio/install.sh | sudo bash
```

That registers the signed repository and installs from it, on either distro. The script is
[`packaging/linux/install.sh`](packaging/linux/install.sh) — committed, attached to every release, and
published byte-for-byte; read it before piping it into a root shell if you'd rather.

To do the same by hand, the copy-paste snippets are at **<https://liqiyedev.github.io/botmaker-studio>**:

```bash
# Fedora / RHEL  (dnf offers to import the signing key on the first metadata read)
sudo curl -fsSL -o /etc/yum.repos.d/botmaker-studio.repo \
  https://liqiyedev.github.io/botmaker-studio/botmaker-studio.repo
sudo dnf install botmaker-studio

# Debian / Ubuntu
sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/botmaker.asc https://liqiyedev.github.io/botmaker-studio/botmaker.asc
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/botmaker.asc] https://liqiyedev.github.io/botmaker-studio/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/botmaker-studio.list
sudo apt-get update && sudo apt-get install botmaker-studio
```

The repository carries the **latest release only** — it's an upgrade channel, not an archive; older versions
stay on the Releases page. The AppImage remains the password-free option: it needs no root and updates itself
from inside Studio.

## Quick start

1. Launch Studio and create a project — **New Project ▸ Game bot**.
2. Tell it what to launch, then what to watch.
3. Capture an image template, break the bot into activities, wire the flow, and hit **Run**.

**[WORKFLOW.md](WORKFLOW.md) is the full walkthrough** — thirteen steps from an empty project to a published
bot, plus a diagram of how a run actually loops. The same text is in the app under **Help ▸ Getting Started**;
both are rendered from one source, so they cannot say different things.

Projects are standard **Maven** projects under `~/BotMakerProjects/<ProjectName>/`:

```
~/BotMakerProjects/MyBot/
├── pom.xml                          # a normal Maven pom; pins the SDK version
├── src/main/java/com/mybot/
│   ├── MyBot.java                   # entry point — installs the popup guard, hands off to Bot.start
│   ├── FlowDriver.java              # your activity flow, as a table
│   ├── Activities.java              # your variables, read back from activities.json
│   ├── ActivityRegistry.java        # every activity, declared once
│   ├── GoHome.java                  # recovery hook: get back to a known screen
│   ├── Popups.java                  # popup guard, run before every vision step
│   ├── Templates.java               # one constant per image template
│   └── activities/                  # one file per activity — this is where your blocks live
└── src/main/resources/
    ├── activities.json              # the values behind your variables
    ├── settings.json                # project settings
    ├── botmaker-project.properties  # capture size, launch target, capture source
    └── images/                      # the image templates themselves
```

Everything above `activities/` is **generated and maintained by Studio** — read-only in the editor, because
rewriting it is how a model change reaches your code. The `pom.xml` is the single source of truth for
dependencies, and the classpath is resolved in-process with Maven Resolver: **no system `mvn` binary is
required**. Any folder with this layout shows up in the selection screen.

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

Studio resolves `botmaker-shared`, `botmaker-session` and the SDK from JitPack. Working on those alongside it?
Clone the umbrella repo and run `mvn -pl botmaker-studio -am javafx:run` from its root instead, so the sibling
modules are resolved from the reactor rather than downloaded.

## How It Works

Studio is organised around a **per-project event bus** with a service layer. Opening a project
(`BotProject.open()`) is the composition root: it builds `ProjectConfig`, `ProjectState`, the `EventBus`, the
Maven/classpath services, the type index, the analyzer and the editor/execution services, then the UI.

**Blocks** implement `CodeBlock` → `AbstractCodeBlock`, in three kinds: a `StatementBlock` (if, while, print,
declaration…), an `ExpressionBlock` (literals, identifiers, binary ops…), and the `{ … }` container,
`BodyBlock`.

**The round trip** is the heart of it. *Source → blocks:* the parser walks a JDT `CompilationUnit` and builds
`CodeBlock` instances. *Blocks → source:* `CodeEditor` applies mutations via JDT `ASTRewrite` and publishes a
`CodeUpdatedEvent` the UI re-parses from. Suggestions come from `ProjectAnalyzer`, which combines a per-jar
ClassGraph index of the classpath with live AST resolution of your own code. **Run** compiles and runs in a
separate JVM; **Debug** attaches over JDI with breakpoints set on blocks.

**The scaffold comes from the SDK.** The generated files above are rendered from templates shipped inside the
SDK jar your project pins — Studio fills in what is true about *your* project and nothing else — so they are
written in the idiom of your SDK version, not of the Studio that created the project.

For the architecture in depth see [`CLAUDE.md`](CLAUDE.md); the living backlog and changelog are in
[`ROADMAP.md`](ROADMAP.md) and [`CHANGELOG.md`](CHANGELOG.md).

## Packaging a Release

The `dist` Maven profile produces a self-contained app-image (bundled Java + JavaFX runtime) via `jpackage`:

```bash
mvn -Pdist package
# → target/dist/BotMaker Studio/   (run the launcher inside)
```

`jpackage` builds **only for the OS it runs on**, so run the profile on each platform you want to ship.
Releases are built by CI: pushing a `v*` tag runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml),
which builds the app-image across the per-OS matrix, publishes the GitHub Release, and refreshes the apt/dnf
repository behind the install one-liner above.

The build is tuned in ways that are easy to break by accident — a jlinked runtime with a hand-maintained
module list, host-platform-only native libraries, and hand-written GUI package dependencies for the `.rpm` and
`.deb`. Each is explained in a comment next to the thing it configures in [`pom.xml`](pom.xml); read those
before editing that profile.

## Project Structure

```
BotMaker-Studio/
├── pom.xml                            # Maven build (the Studio itself)
├── WORKFLOW.md                        # generated from studio/docs/Workflow.java — do not hand-edit
├── src/main/java/com/botmaker/studio/
│   ├── BotMakerStudio.java            # JavaFX Application
│   ├── Launcher.java                  # jar/app-image entry point (must not extend Application)
│   ├── blocks/                        # concrete blocks: expr/ flow/ func/ loop/ misc/ var/ vision/
│   ├── core/                          # CodeBlock hierarchy + component/ render/
│   ├── palette/                       # insertable block and expression catalogs
│   ├── parser/                        # AST ↔ block sync: factories/ guard/ handlers/ helpers/ refactor/
│   ├── project/                       # BotProject, ProjectConfig/State: activity/ capture/ launch/ scaffold/ vcs/
│   ├── services/                      # editor, libraries, execution: capture/ launch/ pilot/ platform/ record/
│   ├── runtime/                       # compile / run / debug (JDI)
│   ├── emulator/                      # Android emulator probing, ADB and scrcpy surfaces
│   ├── game/                          # Steam / Epic / Heroic / Faugus library scanners
│   ├── index/, types/, suggestions/   # type index + analyzer + suggestion pipeline
│   ├── events/                        # per-project EventBus + CoreApplicationEvents
│   ├── sharing/                       # publishing, the gallery, project archives
│   ├── docs/                          # Workflow + RuntimeDiagram: the one source WORKFLOW.md renders from
│   ├── ui/                            # app/ (shell, menus, dialogs) dnd/ render/ util/
│   └── config/, state/, util/, validation/
└── src/main/resources/                # css/ icons/ pilot/
```

## Troubleshooting

**No projects in the selection screen**
A project must live under `~/BotMakerProjects/` with a `pom.xml` and the layout
`src/main/java/com/<projectname>/<ProjectName>.java`.

**SDK types missing from autocomplete**
Open **Project ▸ Manage Libraries…** and confirm the BotMaker SDK version is set; applying refreshes the type
index. The SDK resolves from JitPack, so the first fetch of a version needs a network connection.

**A banner says the project's SDK is too old**
The project still opens, and everything in it stays editable, buildable and runnable — but the files Studio
generates are built from templates that only newer SDKs ship, so the Activity Flow cannot be saved until you
run **Project ▸ Upgrade SDK…**. The upgrade re-renders the generated files and leaves everything you wrote
untouched.

## Contributing

1. Fork and branch (`git checkout -b feature/my-feature`).
2. Keep the functional-OOP style: prefer immutable values and pure transforms; push side effects to the
   service layer (see [`CLAUDE.md`](CLAUDE.md) → Code Style).
3. Add tests (JUnit Jupiter) and run `mvn test`.
4. Open a pull request.

> **Note:** the BotMaker SDK is published to JitPack by the maintainer — don't tag or publish it yourself. A
> dev-run Studio preselects a locally installed `0.0.0-SNAPSHOT` SDK when it finds one, which is why a bot
> created from a development build is pinned to your own build rather than to a released version.

## License

Licensed under the MIT License — see [`LICENSE`](LICENSE).

## Acknowledgments

- **Eclipse JDT Core** — in-process Java parsing, AST manipulation, and compilation/diagnostics
- **ClassGraph** — fast bytecode-level type indexing
- **OpenCV** and **Tesseract** (via the BotMaker SDK) — screen vision and OCR
- **scrcpy** and **adb** — emulator and device capture and control
- **JavaFX** — desktop UI
- **Scratch / Blockly** — inspiration for block-based programming
