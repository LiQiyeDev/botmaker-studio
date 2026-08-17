package com.botmaker.studio.docs;

import java.util.List;

/**
 * <b>The BotMaker workflow, written once.</b>
 *
 * <p>How you get from an empty project to a published bot existed only as tribal knowledge: the Getting
 * Started dialog listed features, and no file in any of the repos told the story end to end. This class is
 * that story, and it is the <em>only</em> copy of it. Two renderers read it —
 * {@link com.botmaker.studio.ui.app.GettingStartedDialog} (a walkthrough with a jump button per step) and
 * {@link WorkflowMarkdown} (the committed {@code WORKFLOW.md}) — so the doc and the dialog cannot drift.
 * {@code WorkflowDocTest} fails the build if the committed file stops matching what this class says.
 *
 * <p>What belongs here: what a step <em>is for</em> and what the person has to decide. What does not: how the
 * pixels get from a capture to a phone, which is {@code docs/display-pipeline.md}'s job and is linked from
 * {@link #furtherReading()} rather than summarised badly here — and how a bot <em>runs</em>, which is a loop
 * rather than a step and so lives in {@link RuntimeDiagram}, rendered by the same two renderers.
 */
public final class Workflow {

    private Workflow() {
    }

    public static final String TITLE = "The BotMaker workflow";

    public static final String INTRO =
            "A BotMaker bot watches a game or app on your screen, decides what it sees, and clicks. You build "
            + "it out of visual blocks in Studio, and Studio writes the Java for you — a real Maven project you "
            + "could open in any IDE. The steps below are the order things are normally done in; only the first "
            + "three are mandatory before a bot can do anything useful.";

    /** A pointer to a document that answers something this guide deliberately does not. */
    public record Reference(String title, String path, String why) {}

    public static List<WorkflowStep> steps() {
        return List.of(
                WorkflowStep.of("Create a project",
                        "A project is a normal Maven project under ~/BotMakerProjects/.",
                        StudioAction.PROJECT_SETUP,
                        "Studio generates the sources, the pom.xml and the scaffolding a bot needs — an entry "
                        + "point, an activity registry, a popup guard. You never have to edit those by hand; "
                        + "they are marked read-only in the editor precisely because Studio maintains them.",
                        "Project Setup is also where you come back to later: it collects the project's targets, "
                        + "its SDK version and its run settings in one place."),

                WorkflowStep.of("Tell the bot what to launch — the launch target",
                        "What gets started before the bot runs: a Steam, Epic, Heroic or Faugus game, an "
                        + "executable, a command line, or an app inside an emulator.",
                        StudioAction.LAUNCH_TARGET,
                        "This comes before the capture target for a practical reason: you cannot pick the "
                        + "window the bot watches until the game is on screen. Launch it from here first, then "
                        + "choose what to capture.",
                        "Studio scans your installed libraries so you pick a game from a list instead of typing "
                        + "an app id. Choosing an emulator app can also point the capture target at that "
                        + "emulator in the same move — a tickbox in the dialog, since it is a convenience and "
                        + "not a rule.",
                        "The launch target is yours and is stripped when you publish. What does travel is what "
                        + "you declare in the Publish dialog: the kinds of launch target your bot is known to "
                        + "work with. That is advice for whoever installs it — they still get to try anything "
                        + "on their machine — so declare what you actually tested, not what you hope works."),

                WorkflowStep.of("Tell the bot what to watch — the capture target",
                        "Where the bot looks: a monitor, a window, the whole desktop, or an Android emulator.",
                        StudioAction.CAPTURE_TARGETS,
                        "Everything visual is relative to this one choice. Image search, OCR, colour sampling "
                        + "and every click coordinate are expressed inside the capture target, so a bot written "
                        + "against a game window keeps working when that window moves.",
                        "Like the launch target, this belongs to the machine that runs the bot, not to the bot. "
                        + "It is not published: when you install someone else's bot you pick your own."),

                WorkflowStep.of("Capture image templates",
                        "Little pictures of buttons, icons and text that the bot matches against the screen.",
                        StudioAction.CAPTURE_TEMPLATES,
                        "An overlay opens over your game so you can draw a rectangle or an ellipse, or cut an "
                        + "object out with a transparent background so the match ignores whatever is behind it. "
                        + "\"Capture many\" takes a batch in one pass and names them together.",
                        "Templates are organised by tag, not by folder — a template used by two activities "
                        + "carries both tags instead of being copied. Tags render as folders in the pickers and "
                        + "in the Resource Manager, where you can also rename, re-tag or delete a template, and "
                        + "export a set as a .bmtemplates file to import into another project."),

                WorkflowStep.of("Manage your resources",
                        "The Resource Manager is the one list of every template in the project.",
                        StudioAction.RESOURCES,
                        "Rename, preview, re-tag, delete, import and export all happen here, and they go through "
                        + "the template library so the tag manifest can never drift from the files on disk."),

                WorkflowStep.of("Break the bot into activities",
                        "An activity is one thing the bot can be doing; the flow graph says what follows what.",
                        StudioAction.ACTIVITY_FLOW,
                        "Rather than one long script, a bot is a set of named activities — \"Mining\", "
                        + "\"HandleFullInventory\", \"Login\" — each returning an outcome, and the flow editor "
                        + "wires those outcomes to whatever runs next. \"" + RuntimeDiagram.TITLE + "\" above "
                        + "is what that looks like at run time; it is worth reading before you draw a graph, "
                        + "because activities do not run top to bottom, once each.",
                        "Studio generates and maintains one source file per activity plus the registry that "
                        + "knows them. Archiving an activity removes it from the graph and from the generated "
                        + "sources; un-archiving brings it back, which is what makes archiving safe to do."),

                WorkflowStep.of("Author the logic with blocks",
                        "The centre canvas is your program: drag blocks to build loops, conditions, clicks and "
                        + "image searches.",
                        null,
                        "Blocks are the Java, not a picture of it — every edit rewrites the real source through "
                        + "the AST, and every source edit comes back as blocks. Nothing is trapped in a format "
                        + "only Studio can read.",
                        "Blocks that call the SDK come from the palette on the left, grouped by facade: vision, "
                        + "interaction, capture, timing. Expression slots accept a drop from the palette or an "
                        + "existing expression, and refuse a drop whose type would not compile."),

                WorkflowStep.of("Author over the running game — the Overlay Editor",
                        "An always-on-top HUD that mirrors your program as one-line rows over the game itself.",
                        StudioAction.OVERLAY_EDITOR,
                        "The only way to write or record a bot without leaving the game. Add blocks where the "
                        + "cursor is, or hit Record and let it write the clicks and drags you perform, then "
                        + "insert the batch into an activity."),

                WorkflowStep.of("Run and debug",
                        "Run executes the bot; Debug steps through it block by block with breakpoints.",
                        null,
                        "The Terminal tab shows what the bot printed and the Errors tab what did not compile, "
                        + "with the failing block highlighted on the canvas. A breakpoint is set on the block's "
                        + "gutter, and stepping highlights the block currently executing.",
                        "If a bot needs to run without taking over your screen, it can run on a private display "
                        + "— a nested X server it owns — so you keep using your machine while it works."),

                WorkflowStep.of("Watch and drive it from your phone — Remote Pilot",
                        "Streams what the bot sees to a phone or a browser, and lets you start, stop and touch "
                        + "it from anywhere.",
                        StudioAction.REMOTE_PILOT,
                        "Scan the QR code to pair; no VPN and no port forwarding. Turning on Interact makes the "
                        + "stream two-way, so a tap on your phone lands as a click in the game at the right "
                        + "coordinate whatever the stream is scaled to."),

                WorkflowStep.of("Publish and share",
                        "Push the bot to your own GitHub repo, and optionally list it in the gallery.",
                        StudioAction.PUBLISH,
                        "Publishing declares which launch targets the bot was tested on and strips the parts of "
                        + "the project that describe your machine. Someone installing it picks their own "
                        + "capture and launch targets; your declaration is shown to them as what is known to "
                        + "work, and it recommends rather than restricts — they can still point the bot at a "
                        + "launcher you never tried.",
                        "The Gallery browses what everyone else has published; installing from it creates a "
                        + "normal project you can run, read, and — if you choose to — start editing."),

                WorkflowStep.of("Browse the gallery",
                        "Install someone else's bot, or see how one is put together.",
                        StudioAction.GALLERY,
                        "An installed bot opens read-only: the scaffolding and the generated members are hidden "
                        + "rather than merely greyed out, so you see the bot's logic and not Studio's plumbing. "
                        + "Opting into editing turns it into an ordinary project of yours."));
    }

    /**
     * Documents this guide points at instead of paraphrasing. Paths are relative to the Studio repo root,
     * where the rendered {@code WORKFLOW.md} lives; the two {@code ../} ones are in the umbrella repo that
     * has this one as a submodule.
     */
    public static List<Reference> furtherReading() {
        return List.of(
                new Reference("docs/display-pipeline.md", "../docs/display-pipeline.md",
                        "capture → encode → transport → render, and the path a touch takes back — for both the "
                        + "pilot's stream and a bot's own read of the same pixels"),
                new Reference("the umbrella CLAUDE.md", "../CLAUDE.md",
                        "how the four modules fit together, the JitPack coordinate model, and how a release is cut"),
                new Reference("ROADMAP.md", "ROADMAP.md",
                        "what landed when, and what is still on the backlog"));
    }
}
