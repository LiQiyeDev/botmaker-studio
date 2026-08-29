package com.botmaker.studio.project;

import java.util.Map;

/**
 * The one {@code .java} a new project starts with, composed by Studio.
 *
 * <h2>Why this is here and not in the SDK</h2>
 *
 * <p>The SDK wrote a project's Java until 2026-08-29 — an entry point, {@code GoHome}, {@code Popups}, and
 * one class per activity — and then kept editing them. That made files inside a user's own source tree ones
 * they could not freely rename, move or delete. <b>A project's structure belongs to the user</b>, so the SDK
 * writes none of it, and what is left is the smallest honest thing: <b>one file, written once, never read or
 * rewritten by anything again.</b>
 *
 * <p>It is Studio's for the reason {@code pom.xml} already was. The entry point is where the plugins get
 * <em>installed</em> — the popup guard hooked up, the flow started — and the SDK is one plugin among however
 * many the editor has loaded, so it cannot compose the file that wires them all together. Only the thing
 * that knows the whole plugin set can.
 *
 * <p><b>Nothing here is a template in the old sense.</b> There are no holes, no fences and no manifest;
 * nothing parses this text back, nothing reconciles it, and no repair puts it back if it is deleted. It is
 * the text a project happens to begin with, and every line of it is the user's from the moment it lands.
 */
public final class StarterSources {

    private StarterSources() {}

    /**
     * The project's starting files, keyed by path relative to the project root — what
     * {@code Authoring.createProject} is handed as {@code callerFiles} beside the pom.
     *
     * @param cfg      the project being created, for its package and entry class name
     * @param template which starting shape the user chose
     */
    public static Map<String, String> of(ProjectConfig cfg, ProjectTemplate template) {
        String pkg = "com." + cfg.packageName();
        String name = cfg.className();
        String path = "src/main/java/" + pkg.replace('.', '/') + "/" + name + ".java";
        return Map.of(path, template == ProjectTemplate.GAME_BOT
                ? gameBot(pkg, name)
                : empty(pkg, name));
    }

    /** A bare {@code main} that prints a greeting. Everything after it is the user's to invent. */
    private static String empty(String pkg, String name) {
        return """
                package %1$s;

                import com.botmaker.sdk.api.util.BotMaker;

                public class %2$s {

                    public static void main(String[] args) {
                        BotMaker.print("Hello from %2$s!");
                    }
                }
                """.formatted(pkg, name);
    }

    /**
     * A game bot: the popup guard, the activity bodies, and the supervised walk over the drawn flow.
     *
     * <p>{@code goHome} and {@code dismissPopups} are plain methods here rather than the {@code GoHome.java}
     * and {@code Popups.java} classes BotMaker used to write. One file is what "written once and never
     * touched again" can honestly promise; three files were three things a user could delete and then be
     * quietly given back.
     *
     * <p>{@code %2$s.class} is passed to {@code FlowGraph.run} because it is the one class that can name the
     * project's own package without spelling it — so renaming this class, or the package, or both, changes
     * nothing.
     */
    private static String gameBot(String pkg, String name) {
        return """
                package %1$s;

                import com.botmaker.sdk.api.bot.Activities;
                import com.botmaker.sdk.api.bot.Bot;
                import com.botmaker.sdk.api.bot.PopupGuard;
                import com.botmaker.sdk.api.flow.FlowGraph;

                /**
                 * The bot's entry point.
                 *
                 * <p>BotMaker wrote this file once, when the project was created, and will never touch it
                 * again. Every line of it is yours — rename it, split it up, throw parts of it away.
                 */
                public class %2$s {

                    public static void main(String[] args) {
                        // Runs before every vision step, so a daily reward or a mail popup gets dismissed
                        // instead of hiding whatever the next find was looking for.
                        PopupGuard.install(%2$s::dismissPopups);

                        // What each activity on your Activity Flow canvas actually does. Add one call per
                        // activity — the editor offers a dropdown of the activities you have drawn, and
                        // another of the outcomes each one declares:
                        //
                        //   Activities.define("Mining", ctx -> {
                        //       if (bagIsFull()) return ctx.outcome("BAG_FULL");
                        //       mineOnce();
                        //       return ctx.done();
                        //   });
                        //
                        // An activity with no define call yet is not a problem: the flow passes through it
                        // and takes its "disabled" wire, so you can draw the whole thing first.

                        // Walks the flow you drew, read from activities.json — which activities run, where
                        // each outcome leads, and the two limits that stop a loop running away. Nothing about
                        // it is written into this project, so redrawing the canvas changes no Java.
                        Bot.start(() -> FlowGraph.run(%2$s.class, %2$s::goHome), %2$s::goHome);
                    }

                    /**
                     * Navigate back to a known-good "home" screen. Called before the supervisor relaunches
                     * the game during recovery, and before any activity whose "go home first" tick is on.
                     */
                    static void goHome() {
                        // TODO: get back to your game's home screen, e.g.
                        //   while (!ImageFinder.exists(home)) {
                        //       ImageClicker.click(back);
                        //       Wait.seconds(1);
                        //   }
                    }

                    /** Dismiss whatever the game has interrupted us with. */
                    static void dismissPopups() {
                        // TODO: close the popups your game shows, e.g.
                        //   ImageClicker.click(ImageTemplateGroup.of(closeButton, okButton));
                    }
                }
                """.formatted(pkg, name);
    }
}
