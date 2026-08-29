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

- **Studio compiles against the SDK for _type identity only_, and since 2026-08-26 it asks the SDK rather
  than mirroring it.** `palette/SdkType` — an enum over every class under `com.botmaker.sdk.api`, each
  constant holding a real `Class<?>` literal — is **deleted** (plugin platform, phase 7), and so are
  `SdkTypeTest` and `SdkTypeUseTest`. What it was *right* about survives whole: the facade set, the menu
  order, the icons and the **fully-qualified names** are still compiler-checked real `Class<?>` identities,
  because the SDK's own per-version catalog (`internal/plugin/catalog`) names its members with **method
  references**. What it was wrong about is that Studio was the author: a class renamed in the SDK broke a
  menu at runtime, and a class *added* to the SDK was invisible until somebody typed a constant here.
  It replaced `palette/SdkApi`, a hand-maintained `List<String>` nothing verified, plus a second
  hand-maintained icon map in `MenuIcons`; the catalog replaced it in turn.

  **`plugin/PluginHost` is where a name is now resolved, and it serves two different catalogs on purpose.**
  `bundled()` is "which names does a plugin own?" — the newest surface this build knows — and backs
  `ownerOf` / `qualifiedName` / `isFacadeClass` / `menuFacades` / `facadeNames`, every one of which is a
  question asked with **no project in hand**. `catalogFor(pin)` is "what should we offer *this* bot?" and is
  the only one curation may read. Using the first for curation would offer a bot on an older SDK members its
  jar has never had — the bug the pinned catalog exists to prevent.

  **What loads the plugins is no longer here.** `plugin/PluginLoader` moved to the `botmaker-plugin-host`
  submodule on 2026-08-28 (`com.botmaker.plugin.host.PluginLoader`, an ordinary `compile` dependency), so
  the `botmaker` CLI and the plugin registry's CI load a plugin exactly as Studio does — the
  parent-first/child-first split is the last code in this project that should exist in two copies.
  `PluginHost` itself stayed: it is the bundled fallback, the per-project bind and the two catalogs above,
  all of which are about *Studio's* open project. **`botmaker-plugin-host` is not `botmaker-plugin-toolkit`,
  and Studio must still never list the second** — the toolkit is a *plugin's* dependency, resolved onto the
  plugin's own classloader, and the moment Studio resolves one version of it two plugins can no longer hold
  two.

  **The catalog mirrors the SDK's `api`/`internal` boundary; it does not draw a second one.** The SDK's rule
  is *"can a bot write the name down?"* — a type it can only ever *receive* lives in `internal`. When 1.1.0
  applied that rule, the `CaptureSource` implementations (`Desktop`, `Monitor`, `NamedWindow`,
  `SessionSource`) and the observation stack (`Bots`, `BotObserver`, `Surface`, `ClickEvent`, `MatchEvent`,
  `SwipeEvent`) left `api`, and `Screen` was deleted. A type only a factory returns is not catalogued.
  The same release sub-packaged the whole surface — `api.geometry.Point`, `api.util.Debug`,
  `api.bot.Session`, `api.meta.ReplacedBy` — which is why **`ImportManager.repairSdkImports` exists**: an
  existing bot's `import com.botmaker.sdk.api.Point;` no longer resolves, so it is repointed on open by
  asking `PluginHost.qualifiedName` for the simple name's current FQN, guarded by the
  `com.botmaker.sdk.api.` prefix. A name no plugin owns is left untouched, never guessed — a wrong import
  compiles into a different type, an untouched one fails where the user can read why.
- **Method knowledge does _not_ come from that jar, on purpose.** A generated bot compiles against the SDK
  version *it* pins, which may be older than Studio's. So methods still come from `ProjectAnalyzer` scanning
  the bot's **resolved** SDK jar with ClassGraph, and Javadoc from `SdkDocsService` parsing the bot's
  `botmaker-sdk:<version>:sources` jar. Adding a *method* to an existing facade needs no Studio change.
- **The served catalog is the superset; `services/SdkSurfaceService` is what the bot actually has, and the
  palette is the intersection.** Those two drifting apart is the normal case, not the exception — a bot pins one SDK,
  Studio ships on its own train — and until 2026-08 nothing noticed: an older bot was offered blocks its jar
  could not compile, and the only feedback was a javac line *after* the block was built. The service parses
  nothing; it reads the `ClassInfo` `TypeSummaryManager` already holds, including `@Deprecated` (bytecode, a
  `RUNTIME` annotation — **not** parsed Javadoc; the Javadoc `@deprecated` *text* naming the replacement is
  `SdkDocs.Overload.deprecated()`, a separate thing that can disagree). **It fails open**: with no SDK
  indexed, every presence query answers yes and every deprecation query answers no — a degraded probe must
  never hide a block the user legitimately has, nor strike one through.
  **No menu needs an explicit *presence* gate and none must grow one**: `StatementMenu` and `MenuBuilders`
  enumerate members through `ProjectAnalyzer` first and drop a facade that resolves none, which is the same jar
  answering the same question. The service's presence queries exist for the surfaces where nothing enumerates
  first — the `OverlayPalette` chips, the class dropdowns on `MethodInvocationBlock`/`LambdaCallBlock`, and
  `ProjectSettingsDialog`'s favourites.
  **That rule is about *presence*. Curation is a second question and does need an explicit filter** (2026-08-23):
  "is this method here?" and "should we lead with it?" are different, and nothing enumerable answers the second.
  **Since 2026-08-26 the answer comes from the SDK's per-version catalog, served through
  `PluginHost.catalogFor(the project's pin)`** — the third mechanism to hold this job, after an `@Palette`
  annotation probed out of the bot's jar (2026-08-23 → 2026-08-25) and nothing at all for the day between.
  Read `isCurated` as *"a plugin catalogues this type"* and `isPaletteAware` as *"a catalog was served"*.
  **The fail-open is at the version level and only there:** a pin with no catalog — released before catalogs
  existed, *or newer than this editor* — is uncurated, so the menus widen rather than empty. Curation touches
  the **index** not at all; presence and curation are two questions with two sources, which is why
  `SdkSurfacePaletteTest` builds no fixture jar and drives everything from one line in a pom.
  The invariant that keeps it safe: **filter what is *offered*, never what is *resolved*.** `MethodInvocationBlock.findSignatures` stays unfiltered (it backs argument typing and the
  current-overload lookup), and the picker shows *offered ∪ the overload this call is already on*, so a block
  sitting on a hidden overload keeps rendering, keeps compiling and can still see where it is.
  **Every surface that *proposes* a member consults it, and getting that list wrong is the mistake this
  feature has already made once.** The 2026-08-23 rollout filtered `StatementMenu.facadeMethodNames`, the ⚙
  picker, the ★ favourite submenu and `StatementFactory`'s default-overload pick — and stopped there, leaving
  the **whole expression menu** offering everything for a year of curation, because the standing "don't gate
  presence here" comment on `MenuBuilders` read as a general prohibition. 2026-08-24 closed it:
  **`MenuBuilders.buildScopeMenu`** is the single member-listing routine behind the expression menu *and* its
  search view *and* every variable/`this`/library scope, so one filter there covers six call sites, and
  **`MethodInvocationBlock.populateMethodList`** filters the method-name dropdown. The name-level twin of
  `retainOffered` is `SdkSurfaceService.retainOfferedNames`, which deliberately has **no** never-hand-back-
  nothing guard: an empty ⚙ picker on a block that plainly has overloads reads as breakage, an absent submenu
  does not.
  **Curation is about members; `FacadeRole` is about types, and the two are separate on purpose.** Whether a
  type gets a submenu at all (`MENU`), is recognised for chrome but never offered (`HIDDEN` — `Window`,
  `Debug`, `Watchdog`, `PopupGuard`, `Session`) or is an import target only (`VALUE` — `Rect`, `Point`, the
  result types) travels with an icon and a display order, and since phase 7 all three are the **catalog's**
  to declare rather than Studio's — which is what retired `SdkType.Role`. The corollary that reads as a
  contradiction from the wrong end: a `HIDDEN` or `VALUE` type is still worth curating, because its members
  are reached through a variable's member submenu and a placed block's ⚙ picker.
  **Constants are never curated** — the fields half of a member submenu is always offered whole (a catalog
  names methods and constructors; enum constants reach the activity pickers through `VariableWire`'s own
  `enumConstantNames`, which never consults the index or the catalog).
  One collision to know about: the index is keyed by **simple name**, so a *qualified* name reaches
  `SdkSurfaceService` only as an exact match against a catalogued class — a user's own `com.mybot.Mouse` is
  nobody's to curate (`PaletteKeyResolutionTest`), and since the catalog holds the real `Class<?>` that is
  now an identity check rather than a package-prefix guess. A **bare** simple name is still trusted, so a
  user class named exactly `Window`
  reaching `MethodInvocationBlock`'s class scope would be curated by the SDK's answer; that hole predates the
  change, costs a couple of missing dropdown entries and never a wrong edit, and is left open rather than
  guessed at. A pin no catalog names leaves every menu byte-for-byte unchanged; so does a class no catalog
  names inside a catalogued pin — which is what lets a catalog be written one facade at a time. One trap
  worth knowing: the signature key is derived in **three** vocabularies — `MethodSignature.signatureKey()`
  from the analyzer's signatures, `signatureKeyOf(MethodInfo)` from the raw index, and
  `signatureKeyOf(MemberId)` from the descriptor a catalog's method reference carried — and a mismatch has
  **no symptom**, just a catalogued overload that never appears. `SignatureKeyAgreementTest` holds all three
  against the real SDK jar and is the reason to keep the derivation in one place. Varargs is the case that
  bites: a descriptor cannot say varargs, so the third derivation reads the flag back off the declaring
  `Class`, because the key spells a varargs tail as its **element** type.
  **There is no version floor — `MIN_SDK_VERSION`, the amber banner, `SdkSurfaceService.isBelowMinimum`,
  `EditorCanvas.sdkFloorBanner` and `TemplateStore.requireFloor` were all deleted on 2026-08-25**, reversing
  the `1.1.0` floor set the day before. The floor existed to stop Studio emitting `FlowGraph.of(…)` into a
  project whose jar had never heard of it; Studio now emits **no generated Java at all** (see the demolition
  below), so the floor guarded nothing while still costing every pre-1.1.0 project a banner. Any pinned SDK
  opens, reads, builds and runs, and an incompatibility surfaces at compile time. `SDK_FALLBACK_VERSION`
  stays — what a *newly created* pom pins is a separate question. When the SDK's own generator lands
  (inversion phase 2), whether it can serve a given pinned version is the **generator's** answer to give,
  from its per-version catalog, not a constant here.
- **Changing the SDK version is a report, not a cell edit — `services/SdkUpgradeService`** (*Project ▸ Upgrade
  SDK…*, and where the floor banner's button goes). It resolves the **target** version's jar
  (`MavenService.resolveSdkJar`, any version — the project pom's JitPack repo means it need never have been on
  this machine), ClassGraph-scans it beside the pinned one, and intersects the difference with the bot's own
  call sites: what's new, what the bot calls that is now deprecated, what the bot calls that is **gone**
  (file + line), and where each break went — read from the **pointer pair the two jars carry**
  (`@ReplacedBy`/`@Replaces`, `docs/refactor/21-api-compat.md` §4). Four things about it are load-bearing:
  - **A redirect where the jars confirm it, a default where they do not.** The SDK once shipped a repair per
    break (a `fix` in `migrations.json`) and it was guessing — nothing checked that two members shared a
    return type, an arity or any semantics. What replaced it is not the absence of a redirect but a
    **checked** one, and the call's position decides what has to be checked: a call standing as a
    **statement** discards its value, so the target's return type cannot make the redirect wrong and it is
    always taken; a call whose value is **used** is redirected only when what comes back still fits where the
    old value sat (same type, a subtype in the target jar, or a widening primitive). Arity never refuses —
    the arguments already passed are kept in order and the difference filled or dropped, which is
    `SignatureMigration`'s machinery. Everything the check refuses falls back to a **literal default of the
    type the old jar said it returned** (`CallMigrator.literalDefaultFor`: `false`, `0`, `""`, `null` — never
    `new Point()`, since the type is often the one just removed — and **cast where the site gives the value
    no type of its own**: `((ImageTemplate) null).width()`, since `null.width()` is not Java and a bare
    `null` argument can make an overload ambiguous; an assignment or a `return` keeps the plain literal), a
    **deleted statement** for a `void`
    (`CallChange.CallDeleted`, because `0;` is not a statement), and `@NeedsReview` on the enclosing function
    **in the same rewrite** — see the review-marks bullet below. *The repair makes the bot compile; the user
    makes it correct.*
  - **`services/SdkPairing` follows edges, and pairs members independently of types.** One edge map:
    the **old** jar's `@ReplacedBy` forwards (the author of the element the bot actually calls saying where it
    went) plus the **new** jar's `@Replaces` backwards, **filtered by era** — an entry is consulted only for a
    bot pinned at or below the version it records. The walk follows edges until it reaches a spelling the
    target jar actually has, which is what resolves a **chain** with no intermediate jar fetched; a visited
    set bounds it, and a cycle reaching nothing live is simply unpaired. Three deliberate refusals: a
    spelling the target **still has** is answered by the live element (so an accumulated entry can never go
    stale into a wrong answer), an ambiguous claim is left unpaired with a `problems()` line, and a pairing is
    never invented. `memberName` and `targetOf` are the two readers — the first answers "what is this called
    on the type this one paired with", the second hands back an endpoint that crossed types, and only
    `redirectsFor` (which is about to move the receiver too) is entitled to that.
  - **The graph is multi-valued, because a member can become two.** `forwardEdges`/`backwardEdges` are
    `Map<String, List<String>>`, `follow` returns a *list* expanded in declared order and depth-first through
    chains, and `SdkRedirects.redirectsFor` returns `List<Candidate>` in preference order — `redirectFor`
    survives as the one-line "first candidate, or null", which is the whole answer for every reader that does
    not ask the user. Today's `null` is an empty list, so **every one-target pointer is
    the degenerate case** and the almost-always path is byte-for-byte what it was. A split composes with a
    chain for free (`a`→`{b,c}`, `b`→`d` lands on `{d, c}`). Two halves of compile-safety that the split
    forces apart: *shape reconcilable* belongs to the **candidate** and is decided once; *fits where the value
    is used* belongs to the **site**, since a call standing as a statement discards its result and any
    candidate fits there. Zero survivors at a site is not a new outcome — it is the default value plus
    `@NeedsReview`.
  - **Which candidate a call meant is a property of the call, so the user is asked per call site.**
    `scroll(3)` and `scroll(-3)` in one bot want different answers; a project-wide pick would be wrong in half
    of them by construction. `Report.Choice` sits **beside** `breaks`/`deprecated` (so `canMigrate()` and
    `canModernise()` keep their meanings), `CallSite` carries the call's **source text** because `(file, line)`
    cannot tell those two calls apart, and every combo arrives **already answered** — nothing is required of
    the user. The decision reaches the rewriter as a `Choices` map, so Modernise and every headless path work
    unchanged by passing nothing. **The site key is positional** — *(project-relative path, character start
    offset)* — never node identity: the report pass and the apply pass parse the sources twice, so the AST
    node in the report is not the node the rewriter holds. Nothing edits the files between the passes, and a
    key that misses falls back to that site's default.
  - **The dialog opens with what the release *gives* you.** `Report.added` is a diff-derived list of API
    names, which is not a reason to upgrade. The SDK ships its whole `CHANGELOG.md` inside its jar as
    `META-INF/botmaker/whats-new.md`; `Report.highlights()` holds the sections in `(from, to]`, newest first,
    rendered above every cost section, with the exhaustive API diff still below it. Absent file → exactly the
    old dialog, which is the standing rule that every new reader degrades.
  - **The author's own sentence reaches the user verbatim.** `@ReplacedBy(note=…)` / `@Replaces(note=…)` are
    preferred over Studio's generated sentence and never rewritten; when both jars carry one the **old** jar's
    wins (the author speaking at the moment of the change, on the element the bot actually calls) and the new
    jar's is the fallback for a bot that skipped that release. `behaviourChanged` is a logical OR across the
    two ends and forces the review mark **even where the shape did not move** — `shapeChanged ||
    behaviourChanged` — which is the one gap the model cannot detect by construction. `@Since` groups the
    additions by the version that introduced them.
  - **A removed type with no pairing is the one break that refuses the upgrade**
    (`BreakKind.TYPE_REMOVED`, `Break.isRepairable()` false): a default has nowhere to go in
    `ImageTemplate t = …;`. It disables the whole span (`Report.canMigrate()`), because rewriting some call
    sites and leaving the rest is the half-migration `CallMigrator.rewriteOthers` returns `null` to prevent.
  - **Modernise is the same machinery one hop further.** *Project ▸ Modernise…* touches no pom: it walks the
    pointers the project's own jar's **deprecated** elements carry (`throughDeprecations`, which also folds in
    the target jar's forward edges — same shape of edge, only the stopping rule differs) and rewrites the bot
    off them in place. It has its own verdict, `Report.canModernise()`, rather than borrowing `canMigrate()`:
    moving off a deprecation is still possible on a project where an unpaired removed type blocks the upgrade.
    It is also the upgrade dialog's checkbox (`compare(target, alsoModernise)`).
  - **Studio is the version that lags**, so it degrades rather than guessing — and the pointer model makes
    that free: an annotation a newer SDK invents is simply invisible to a ClassGraph scan asking for the two
    Studio knows, so an older Studio falls back to exactly its behaviour against a jar with no pointers at
    all. There is no schema to refuse any more.
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
  - **The edit primitives live in `parser/refactor`, and each can refuse.** `CallChange.ValueDefaulted` and
    `CallChange.CallDeleted` are the two an SDK upgrade produces and a signature edit never does;
    `CallMigrator.renameTypeIn` is deliberately **file-level, not per-site** — a type is also written in
    `Precision p;`, a cast and a type argument, none of which any call scan records, so renaming only the
    found sites leaves a file naming a class that is gone. `MethodReferences.CallSite` widened to hold a
    field reference (`arguments()` is simply empty), which is what lets one scan feed both the report and the
    rewrite. **Every primitive that cannot express its edit returns false and takes the whole migration down
    with it** — a removed `void` member in a one-line lambda body, or a constant used as a `case` label whose
    enum cannot be told. That is the same all-or-nothing `rewriteOthers` already enforced for a rewrite that
    will not parse.
  - **Applying is `parser/refactor/SdkMigrationRunner`: one pass over resolved endpoints, two sweeps per
    file.** There is no replay — `Pairing` has already walked the edges to an endpoint the target jar has, so
    `foo`→`bar` (2.0) and `bar`→`baz` (3.0) reach the runner as the single fact `foo`→`baz`, which is what a
    bot that has run neither pass actually needs, and `a`→`b` + `b`→`a` reaches nothing live and is dropped
    where a fixpoint loop would run forever. Each file is then swept **members first, types second, with a
    re-parse between**: a removed member of a renamed type is otherwise two `ASTRewrite` edits on one node,
    which it cannot express.
  - **The record of an incomplete repair is `@NeedsReview` in the bot's own source
    (`parser/refactor/ReviewMarks`).** Not a sidecar under `.botmaker/`: the diff cannot answer this later —
    once the pom is bumped the old jar is gone and re-diffing the project finds nothing — and an annotation
    cannot drift from the code it describes, reverts with it through Project History, and needs no schema or
    cleanup pass. It is **generated into the bot's package on demand** (`ReviewMarks.ensureFile`,
    `ProjectConfig.needsReviewSourceFile`) rather than shipped by the SDK, because every bot an upgrade is
    about to touch is pinned to the *older* SDK — an annotation arriving with the version the user is trying
    to leave would help nobody. `@Retention(SOURCE)`, so a marked bot ships the same bytes as an unmarked
    one. It used to be the one file `FileRole` classed `GENERATED` outside the game-bot template, which kept
    it out of the explorer; since the lock sweep it is an ordinary listed file, written when missing and
    never rewritten — nothing in it is about this bot, but hiding a file nobody rewrites bought nothing.
    Writing **merges** into the mark already there
    (Java allows one per method) and dedupes; `strip` removes one entry, and the last one takes the
    annotation — and, once the file holds no marks at all, the import. **A rename is not marked**: the bot
    does afterwards exactly what it did before, and burying the sites that changed meaning under the ones
    that did not is how a review list stops being read.
  - **The marker is not an SDK-upgrade feature — every refactor that rewrites a file uses it
    (`parser/refactor/ReviewMarker`).** `ReviewMarks` edits one tree through one `EditContext` and knows
    nothing of a project; `ReviewMarker` is the project-level half: `prepare` (write the annotation, answer
    the package to import it from), `snapshot` (a Project History commit), `marksSurvive` (never mark a file
    Studio regenerates), and `markLines` (mark the functions a set of changed *line numbers* falls inside —
    the way in for a rewrite done in text rather than in an AST). Four refactors now go through it:
    - **A signature edit** — `CallMigrator.applyIn` / `rewriteOthers` mark at each call site, and
      `MethodHandler.applyFunctionSignature` marks the edited function itself for a rescued parameter or a
      replaced return value. `CallMigrator.reviewEntries` is where the *complete vs. lossy* line is drawn: a
      rename, a reorder or a dropped **literal** leaves the call doing exactly what it did, so it is not
      recorded; a new input filled in with a default, a used result that no longer fits, and a dropped
      argument that **called or constructed something** all are (`droppedWork`).
    - **Deleting a variable** — marked when the uses become defaults, not when they are pointed at another
      variable, which is a complete repair.
    - **Repointing a template** (`TemplateReferences.retarget`, `ResourceManagerDialog.repointEntry`) —
      marked when blocks end up looking for a *different* picture (a delete, a missing-file repair), not on a
      rename, where every block still watches for the same thing under a new name.
    - `SdkMigrationRunner`, as above.
  - **`prepare` may answer null, and that is not a failure.** A mark is a reference to a generated annotation,
    so if the annotation cannot be written the choice is between refusing the refactor and doing it unmarked
    — and unmarked is plainly better: the user asked for the rename, not the bookkeeping. It has to be called
    **before** anything is written, so the answer is known while refusing is still cheap. `snapshot` is
    best-effort for the same reason: a project whose history was never initialised must not lose the ability
    to rename a function.
  - **The snapshot rule is "does this touch a file the user is not looking at".** `CodeEditor.touchesOtherFiles`
    asks exactly that of the plan. The editor's own ↶ already puts the active file back and dies with the
    session; a file the user never opened has no other way home, and a template repoint happens outside the
    editor entirely. Active-file-only edits take no snapshot — a commit per keystroke is not a history.
  - **The Review tab is a scan, never a list something kept (`services/ReviewService`, `ui/app/ReviewPanel`).**
    Marks live in the source precisely so that nothing has to hold a copy of them: an edit that moves a
    function moves its mark, and a revert through Project History takes the marks out with the change. The
    price is that the list is *derived* — `ReviewService.scan` re-reads the bot's sources every time the tab
    is opened, `markReviewed` strips one entry and the panel re-scans rather than removing the row. That is
    cheap (a bot is tens of files, and a file with no `NeedsReview` in its text is never parsed) and it
    cannot go stale, which a cached list demonstrably would: four refactors write marks and two of them never
    touch the editor. The **entry text**, not the function name, identifies a row — two overloads can both be
    marked. `services/BotSources` is the one walk over the bot's `.java` files, shared with
    `TemplateReferences`: buffer before disk, and a rewrite written to both.
  - **The badge is the only annotation the block editor renders** (`MethodDeclarationBlock.reviewBadge`). It
    reads `@NeedsReview` off the very `MethodDeclaration` the block was built from, so the count cannot drift
    and a mark stripped in the tab is gone from the header on the next render with nothing kept in step. The
    entries are its tooltip: the header has room for a count, not for three sentences.
  - **Generated files are rewritten but never marked.** A call in the activity registry has to be renamed with
    everything else or the bot stops compiling, but the file is regenerated on the next save, which would
    silently erase the mark. A review row that disappears on its own is worse than no row: the user is never
    told the thing they were meant to look at has stopped being listed.
  - **One scanner, two readers: `parser/refactor/SdkReferences`.** The report asks it what the bot calls; the
    runner asks it the same question to know what to rewrite. Two scans would eventually disagree, and the
    disagreement's shape is the worst available — a dialog listing three call sites beside a button that
    repairs two. A `Reference` therefore carries a `MethodReferences.CallSite` (file, parse, **node**); the
    report keeps the line and drops the node, the runner keeps the node.
  - **A member is not the only thing a bot can lose: `SdkReferences.typeUses` is the other half.** It yields
    every place the source *writes* an SDK type without calling it — a field, a parameter, a return type, a
    local, a cast, a type argument, an `instanceof`, a catch clause — and `breaks()` reads it, so a removed
    unpaired type is a `TYPE_REMOVED` break **even in a bot with zero calls**, and a `TYPE_RENAMED` one lists
    those places too. Until 2026-08-23 such a bot got *no finding at all* and was upgraded into something
    that did not compile; the file-wide rename was always right, only the report and the gate that decides a
    file is worth renaming were blind to it. `mentions` is now the same walk asking a narrower question —
    the three positions `renameTypeIn` actually rewrites, plus a static import's qualifier — rather than
    "any name anywhere", which matched a local variable that happened to share a class's name.
  - **Every file in the project is migrated, since 2026-08-30.** The split survives in the runner's two
    lists — only `FileRole.EDITABLE` files are rewritten — but the second list now holds bundled library
    source alone and is empty in an ordinary project. What follows described the arrangement it replaced, in
    which a generated file was re-rendered rather than rewritten: `SdkUpgradeService.regenerateScaffolding` called
    `Regeneration.write` **after the pom has moved** — the render has to see the SDK the project actually
    pins now — falling back to `Regeneration.writeTemplatesClass` for an empty project, which has no
    model-derived file but does have a `Templates` class. For one day (phase 0b) it re-rendered only that
    last file, because Studio had no generator at all and **an upgrade left the generated Java pinned to the
    old SDK's spelling**; that cost is paid off. An `IOException` here is a printed sentence and not a failed
    upgrade: the sources are repaired and the pom is moved, so undoing it is the destructive answer and the
    pre-upgrade snapshot is the way back. `SdkMigrationRunner.scaffoldingInTheWay` deliberately stays
    conservative even so — relaxing it wants the per-version catalog, not just a working re-render.
  - **`apply(target, repairSources)` is snapshot → migrate → bump, and `repairSources` gates only the middle.**
    A span carrying a removed type nothing pairs with must still be *switchable* — the user reads which type
    it is and where they use it, makes those edits, moves — because the target jar goes on lacking that type
    forever, so refusing the button outright is a trap with no way out. What it must never do is repair half a
    span, which is why the flag is per-upgrade, not per break. `migrateSources` re-derives everything from the
    two jars rather than trusting the `Report` the dialog holds: a value that crossed a dialog and an FX
    thread is not evidence about the files on disk right now.
- **Studio writes a project's Java once, at creation, and never touches it again (2026-08-29).** Nothing
  generates, reconciles, restores or re-renders source. **A project's structure belongs to the user**, and a
  plugin — the SDK included — contributes methods a user calls rather than files a user inherits.
  - **`project/StarterSources` is the whole of it**: one file, composed by Studio, handed to
    `Authoring.createProject` as a caller file beside `MavenService.pomXml`. It is Studio's for the reason
    the pom already was — the entry point is where the plugins get *installed*, and only the thing that knows
    the whole plugin set can compose it. A game bot's carries `goHome` and `dismissPopups` as plain methods:
    one file is what "written once, never touched again" can honestly promise, where three were three things
    a user could delete and be quietly given back.
  - **What is deleted, and it is a long list.** `project/Regeneration` (with `ensureStubs`, `write`,
    `writeTemplatesClass`, `restore`, `renderEverything`), `project/seed/` (`SeedWriter`, `SeedReconciler`,
    `SeedLedger`, `SeedSync`), `project/ScaffoldMigration`, `ProjectSpecs.generatedFileNames`/
    `generatedSource`, `PluginHost.seedPlan`/`seedFiles`, `ImageTemplateLibrary.regenerateTemplatesClass`
    and its six call sites, `SdkUpgradeService.regenerateScaffolding`, and `ProjectRepair`'s whole
    damaged-locked-method half. `project/scaffold/` and `TemplateStore` had already gone on 2026-08-26.
  - **The lineage, because each step was defended and each was superseded within days.** Studio owned the
    generators; then the SDK did and Studio spliced fences into its templates; then the templates went and
    the SDK emitted from `ProjectModel` while `Regeneration` was Studio's one door to it; then the SDK
    shipped *seeds* — real compiling classes written into a project once and maintained at marked regions,
    with a key ledger, a reconciler and a rename engine here to keep owning them. Every step improved on the
    last. What was wrong was one level up: making *files in a user's project* a plugin surface at all.
  - **Two capabilities went with the generator and neither is coming back.** *Restoring a missing `.java`*
    needed something that knew what a project must contain. *Repairing a damaged locked method* went further
    — it rendered the project's whole scaffold from its own SDK and diffed each locked method against it, so
    that a `GoHome.run` renamed to `goHome` did not leave the bot silently uncompilable. Both needed a
    canonical text, and the premise underneath them — that a file can be partly BotMaker's — is exactly what
    was given up.
  - **`ProjectRepair` keeps everything that is not source**: `pom.xml`, `botmaker-project.properties`,
    `settings.json`, `activities.json` and the placeholder image. `looksLikeGameBot` is now the entry point's
    own text alone (`Bot.start` / `Bot.supervise`), the file-presence fallback having had no list to check.
  - **`ActivityService.update` is `activities.json` and nothing else.** Adding an activity creates no file,
    renaming one moves nothing, deleting one leaves whatever the user wrote where it is — an activity's
    behaviour is an `Activities.define("Mining", ctx -> …)` call in a file BotMaker has never known the
    location of.
  - **The lock machinery went on 2026-08-30, and what replaced it is one rule.** `FileRole.GENERATED`,
    `MethodLock`, `GeneratedMembers`, `LockedRegions` and `core/component/MemberVisibility` are deleted;
    `FileRole` is `EDITABLE | LIBRARY` over a path alone, and `LockResolver` refuses exactly three things:
    a bot open for **reading** (`ProjectMode`), **bundled library source**, and **`main`'s signature**.
    - **`main` is the one member-level lock left, and only its signature.** It is matched on the method's
      *shape* — `public static void main(String[])`, wherever the user has put it — rather than on
      `config.mainSourceFile()`, because the entry point is theirs to rename, move or split and a path-keyed
      rule would stop holding the moment they did. The signature is not a BotMaker convention but the one
      the JVM looks for, so renaming it, deleting it or retyping its parameter is the single edit whose
      consequence the user cannot read off the screen. **Its body is the user's**, and that is the point of
      the file: it is where the bot is put together, one static call at a time. Nothing is *installed* there
      and no plugin is registered — `PopupGuard.install`, `Bot.start` and `FlowGraph.run` are ordinary static
      API methods a user calls or does not. Deleting the file it lives in is not offered anywhere (the
      explorer has had no context menu since well before this).
    - What went with the machinery, deliberately: the **method lock badge** ("Generated - Read Only", "Your
      code goes here") and its `.method-block--yours` accent — every sentence it could say was about a method
      BotMaker wrote; the **pinned trailing `return`** in an activity's `run()`, which nothing may be
      inserted after; the explorer's **Generated by BotMaker** group and its hidden *derived* files, so every
      file the walk finds is now listed under **My code** or **Library**; and `CodeExecutionService`'s
      **locked-parts diff** before writing to disk, since a project file has no locked parts.
    - `Audience` and `ComponentResolver` stay: audience still decides which *components* of a block are worth
      showing. What it no longer does is drop whole members from the tree.
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

### Plugins — the registry answers "where is it", and installing is an ordinary dependency

**Project ▸ Manage Plugins…** (`ui/app/ManagePluginsDialog` over `sharing/PluginRegistry`) browses the
generated `index.json` in `botmaker-plugin-registry` and installs through **`LibraryService`**, the same path
Manage Libraries uses. That is the whole design and it is deliberate: `META-INF/services` says how the host
*instantiates* a plugin already on the classpath, so what is missing is only the **coordinate**, and once you
have one a plugin is a normal Maven dependency. A bespoke install path would be a privilege the bundled SDK
plugin has and a third party's plugin does not — the back door the platform exists to close.

- **Studio only ever reads the registry.** A plugin is submitted with `botmaker publish`, whose validator is
  the same code the registry's CI runs; those checks need the plugin's build, which a bot's editor has not
  got. There is no publish path here and there should not be one.
- **The version installed is the entry's `verifiedVersion`, not the newest tag** — the version the gate
  actually loaded and checked. Only an entry carrying none falls back to JitPack's newest.
- **Installed is decided by coordinate, never by version**, so a plugin pinned to an older version reads as
  installed; changing that version is Manage Libraries' job, and this dialog does not duplicate it.
- **Everything degrades to a sentence.** An unreachable registry is an empty catalog with a message in the
  list's placeholder, matching `JitPackSearch`; a catalog nobody can fetch must never block the editor.
- `PluginRegistry.Plugin` is pure and its rules (parse, `matches`, `isInstalledIn`, `isInstallable`) are
  tested headlessly — the same split as `BlockTree`. It ignores unknown JSON properties because **this Studio
  is the reader that lags**: a field a newer `botmaker publish` writes must not lose the whole catalog.

**The plugin author's loop is *Reload Plugins* plus `~/.m2`, and neither needs a release (2026-08-28).**
The SDK has always been testable without pushing a tag — `mvn install`, and Maven checks `~/.m2` before
JitPack — and a plugin now is too:

- **`LibraryService.reloadPlugins()` writes no pom.** The coordinate resolves to the same jar *path* before
  and after a rebuild, so there is nothing to write; what changed is the jar's **bytes**, and
  `PluginHost.bind` opening a fresh `URLClassLoader` over the same paths is the whole of what it takes to
  see them. It is deliberately not `updateLibraries(currentLibraries(), currentSdkVersion())`, which would
  rewrite the pom to say what it already says. *Project ▸ Reload Plugins* reports the plugins it found,
  because a reload that found nothing new looks exactly like one that did nothing.
- **`MavenService.localPluginBuilds()` finds them by the service file, not by a convention.** A candidate is
  a `*SNAPSHOT` directory in `~/.m2` whose jar carries
  `META-INF/services/com.botmaker.plugin.api.StudioPlugin` — the entry `ServiceLoader` itself reads — so
  nothing here keeps a list, a naming rule or a registry in step with anything. Gated on
  `AppVersion.isDevBuild()`, exactly like `localSdkVersions()`.
- **In Manage Plugins a local build *replaces* the registry's version for that coordinate**, rather than
  adding a second row: two rows for one artifact would offer two versions of it, and a developer who just
  built one wants the one they built. A local build nobody has published becomes a row of its own, at the
  top.

**Studio carries `botmaker-plugin-toolkit` at `runtime` scope, and the reason is a defect worth
remembering (2026-08-28).** Studio's own plugin #1 is the SDK, whose `SdkPlugin` extends the toolkit's
`AbstractStudioPlugin`; the SDK declares the toolkit `optional`, so it is **not transitive**, so Studio's
classpath had no toolkit at all. `ServiceLoader` threw `NoClassDefFoundError` while constructing it,
`PluginHost.discover` caught it — correctly; a classpath with no plugin on it is an ordinary state — and
Studio ran with an **empty palette, no name recognition and no SDK slot editors**, having printed one
line to stderr. Nothing failed to compile at any point.

- **`PluginHostLoadTest` is the guard**, and every assertion in it is "not empty", because empty is exactly
  what this break looks like. It reads the unbound statics, which answer from `BUNDLED`.
- **The scope is `runtime` so javac never sees the toolkit**: no Studio source may name a
  `com.botmaker.plugin.toolkit` type, and `StudioSourcesTest` refuses a widening to `compile`. The toolkit is
  a *plugin's* widget kit; Studio having a version of its own to keep in step is the thing to avoid.
- **It does not lock a plugin to Studio's version.** `PluginLoader` is parent-first only for
  `com.botmaker.plugin.api.**` and the platform namespaces, so the toolkit is child-first: a plugin carrying
  its own copy resolves its own, and Studio's is the fallback for one that brings none.

### Sharing — the gallery is read whole and written one file at a time

`sharing/GitHubGallery` **reads** `index.json` from the gallery's raw-CDN URL; `sharing/BotPublisher`
**writes** `bots/<owner>-<repo>.json` and nothing else. Since 2026-08-28 `index.json` is *generated* by the
gallery's own CI from those entry files, and a pull request that edits it is refused — so the read path and
the write path no longer touch the same file, and that asymmetry is the design rather than an accident:

- **The read URL is a compatibility promise.** Every Studio already installed has it compiled in, so the
  generated array stays byte-compatible with `GalleryEntry` and the path never moves.
- **The write path was the problem.** Appending to a shared array made every concurrent submission a merge
  conflict and each publish a read-modify-write against a base SHA somebody else may have moved; unpublishing
  rewrote the whole file for a one-line removal. `GitHubConfig.entryPath` is where a bot's identity becomes a
  path, and re-publishing is idempotent because that path is the identity.
- **Publishing from a Studio older than that release breaks, deliberately** — the gate refuses the pull
  request with a message naming the update. The alternative (a CI job converting an index-only PR) means
  maintaining both shapes indefinitely.
- `GitHubClient.delete(url, body, token)` exists for this: GitHub's Contents API needs a body to delete a
  file and `HttpRequest.DELETE()` sends none. The bodyless `delete(url, token)` stays for the endpoints that
  reject one.

### Validation

`DiagnosticsManager` holds the current set of compiler diagnostics. `ErrorTranslator` maps Eclipse JDT error codes to user-friendly messages. Diagnostics are surfaced to blocks via `CodeBlock.setError()` / `clearError()`.
