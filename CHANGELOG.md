# Changelog

What each released version of BotMaker Studio gives you, in a few bullets. `ROADMAP.md` stays the detailed
engineering log — why a thing was built, what was rejected, what it cost; this is the short answer to
*"should I update, and what changes for me?"*, and it is prepended to the GitHub Release notes for the tag.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

- **The Resource Manager is the SDK's 🖼 Manage Pictures now.** The window that renames, retags, replaces,
  deletes, imports and exports your bot's pictures moved out of the editor and onto the toolbar, beside ✂
  Capture Templates. It does everything it did, including rewriting the blocks that name a picture you rename
  or delete — that half never left. What left is the half that knew what a picture is called.
- **A plugin can rename something it owns and carry your code with it.** Whatever a plugin names in your bot's
  source, it can now find and repoint through the editor, in your open buffers as well as on disk, with the
  project snapshotted to Project History first and the changed functions marked for review when the meaning
  moved. It reaches files that do not currently compile, which is exactly the file a half-finished rename
  leaves behind.

## [1.0.31] — 2026-08-24

- **The files BotMaker generates for you come from the SDK your project pins, not from Studio.** Studio fills
  in what is true about your project — your activities, your flow, your stored parameters — and the SDK owns
  everything else. Two things follow for you: a generated file is written in the idiom of *your* SDK rather
  than of the Studio that happened to create the project, and a Studio older than your SDK still produces
  files that compile, because anything it does not recognise stays at the SDK's own default.
- **`FlowDriver` and `Activities` hold your project's data and nothing else.** The walk loop, the step budget
  and ~150 lines of parameter-parsing code are the SDK's now. Your two knobs, `MAX_STEPS` and
  `STEP_DELAY_MS`, are still in `FlowDriver` where you left them.
- **A duration reads the same in the editor and in the bot.** `1h30m` was parsed by two separate
  implementations — one in Studio, one written into the generated code — that nothing could compare. There is
  one now, and it is the SDK's.
- **Studio requires SDK 1.1.0 or newer to write a generated file.** An older bot still opens, and every file
  in it stays editable, buildable and runnable — but saving the Activity Flow, and *Recover Project Files*,
  ask you to run *Project ▸ Upgrade SDK…* first, by name and with nothing changed on disk. The upgrade
  re-renders `FlowDriver` and `Activities` in the new shape and does not touch your stubs, `GoHome` or
  `Popups`. New projects pin 1.1.0.
- ***Project ▸ Upgrade SDK…* opens with what the release gives you, not with what it costs.** The SDK ships
  its own `CHANGELOG.md` inside its jar, so the dialog shows every release you are moving through — newest
  first, in the author's words — above the cost sections. The exhaustive API diff is still there, below it.
- ***Project ▸ Upgrade SDK…* leads with what the release costs *your* bot, with file and line** — and now
  also with what the SDK's author said about each move, word for word, out of the `@ReplacedBy` / `@Replaces`
  pointers the SDK carries in its jar. A redirect that keeps its shape but changes what it does is marked for
  review instead of shipping silently, and additions are grouped by the version that introduced them.
- **A member that became two now asks you which one you meant, per call site.** `scroll(3)` and `scroll(-3)`
  in one bot want different answers, so the dialog lists every call with its own combo — already answered, so
  you can accept the whole thing untouched.
- **The palette is curated.** Studio offers the members the SDK's `@Palette` names, in the statement menu, the
  expression menu, both search views, the ⚙ overload picker and the method-name dropdown. A bot already using
  a member that is no longer proposed still renders, still resolves and still compiles — filtering applies to
  what is *offered*, never to what is *resolved*. A bot pinned to an SDK that predates `@Palette` sees exactly
  the menus it saw before.
- **Existing projects survive the SDK's package reorganisation**: `api.*` imports are repointed on open rather
  than opening as a wall of red.
- **Upgrading no longer stops half-way because of a file Studio wrote.** The generated files — `Activities`,
  `ActivityRegistry`, `FlowDriver`, `Templates` — are re-rendered against the new SDK after the version moves,
  using the same pointers that repair your own code, so a release that renames something they use goes
  through instead of refusing. Where the move genuinely cannot be expressed, the report says so **at the top,
  before you start**, rather than failing part-way through.
- **A new project is never created against an SDK it cannot compile against.** Studio checks the version you
  picked before it writes a single file, and says which member is missing so you can choose another version —
  instead of leaving a broken project behind.
- **Saving the Activity Flow is all-or-nothing.** Every generated file is produced and verified in memory
  before any of them is written, so a flow edit can no longer leave three files updated and a fourth stale.
  If it cannot be done at all, nothing on disk changes and the editor tells you which SDK member is the
  reason. (Reachable only on an SDK newer than your Studio; the fix is to update Studio.)
- A type the bot only *writes* (a field, a parameter, a cast, a type argument) is now seen by the upgrade
  report, and a defaulted value says what type it is.

## [1.0.30] — 2026-08-22

- Re-tagged against a new SDK. No source change.

## [1.0.29] — 2026-08-22

- Documentation only.

## [1.0.28] — 2026-08-22

- **The Linux packages install cleanly and are less than half the size**: the bundled runtime is jlinked
  rather than copied whole (rpm 241 → 126 MB, deb similarly), other platforms' natives are gone, and rpm/deb
  declare the GUI stack they actually need.

## [1.0.27] — 2026-08-21

- **One-command install** on Linux (`packaging/linux/install.sh`) instead of registering the repository by
  hand.

## [1.0.26] — 2026-08-21

- Re-tagged against a new SDK. No source change.

## [1.0.25] — 2026-08-21

- **Studio installs and updates from a signed dnf/apt repository**, so updates arrive through
  `dnf upgrade` / `apt upgrade` rather than a visit to the Releases page. An RPM upgrade no longer deletes the
  application menu entry.
- **Refactoring across files**: a signature you cannot change behind the project's back, a result type the
  body agrees with, every picker on one screen, and undo that spans files.
- The release is built from source at pinned upstream refs rather than from JitPack.

## [1.0.24] — 2026-08-04

- **A selectable UI theme** — Default, Dark, Black, High Contrast — from the View menu.

## [1.0.23] — 2026-08-02

- **The Activity Flow arranges itself**, and a new activity gets a dialog instead of a blank file.
- **The overlay draws over a live private session** and names the activity it records into; object capture
  zooms.
- **An eyedropper**, and one `Precision` editor that hides the knobs a given call cannot use.
- The bot's tuning became project settings rather than a generated `BotSettings.java`.

## Earlier

v1.0.22 and below predate this file. `ROADMAP.md` has the dated log.
