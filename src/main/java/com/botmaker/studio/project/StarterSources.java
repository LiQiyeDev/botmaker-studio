package com.botmaker.studio.project;

import java.util.Map;

/**
 * The one {@code .java} a <b>blank</b> project starts with, composed by Studio.
 *
 * <h2>Why there is only one, and why it is this small</h2>
 *
 * <p>The SDK wrote a project's Java until 2026-08-29 — an entry point, {@code GoHome}, {@code Popups}, and
 * one class per activity — and then kept editing them. That made files inside a user's own source tree ones
 * they could not freely rename, move or delete, so all of it went and Studio composed the starting file
 * instead: a blank one, and a game bot.
 *
 * <p>The game bot went too, on 2026-08-30. It was not Studio's to write: <b>it is a bot project that calls
 * the SDK's static API</b>, which is exactly what the gallery already publishes, browses and installs. So a
 * richer starting point is now a published bot carrying the {@code template} tag — see
 * {@link TemplateProject} — and anybody can write one without a Studio release.
 *
 * <p>What is left here is the starting point that must work with <b>no network and no gallery</b>, which is
 * the reason Studio composes any at all. It is deliberately the smallest honest thing: a class, a
 * {@code main}, and a line of output. It prints with {@code System.out.println} and imports nothing —
 * teaching {@code BotMaker.print} for what the JDK already does would be spending a user's first impression
 * on a BotMaker spelling of a Java call.
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
     * <p>Only {@link ProjectTemplate#EMPTY} reaches here. A project made from a gallery template gets its
     * files by unpacking that template, and a caller asking for one of those by name is a bug rather than a
     * case to handle.
     *
     * @param cfg the project being created, for its package and entry class name
     */
    public static Map<String, String> of(ProjectConfig cfg) {
        String pkg = "com." + cfg.packageName();
        String name = cfg.className();
        String path = "src/main/java/" + pkg.replace('.', '/') + "/" + name + ".java";
        return Map.of(path, blank(pkg, name));
    }

    /** A bare {@code main} that prints a line. Everything after it is the user's to invent. */
    private static String blank(String pkg, String name) {
        return """
                package %1$s;

                /**
                 * Your bot.
                 *
                 * <p>BotMaker wrote this file once, when the project was created, and will never touch it
                 * again. Every line of it is yours — rename it, split it up, throw it away.
                 *
                 * <p>The BotMaker API is static methods you call: {@code ImageFinder}, {@code Mouse},
                 * {@code Wait}, {@code Bot.start(…)} to run supervised, {@code FlowGraph.run(…)} to walk the
                 * Activity Flow you draw in the editor. Start typing in main() and the palette will offer
                 * them.
                 */
                public class %2$s {

                    public static void main(String[] args) {
                        System.out.println("Hello from %2$s!");
                    }
                }
                """.formatted(pkg, name);
    }
}
