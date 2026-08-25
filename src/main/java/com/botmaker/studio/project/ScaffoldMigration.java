package com.botmaker.studio.project;

import com.botmaker.studio.palette.SdkType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings a game-bot project created before the current scaffold onto it. Two migrations so far, both keyed off
 * the generated entry point:
 * <ul>
 *   <li><b>The retired files.</b> The entry point binds {@code FlowDriver::run} directly and the launch step
 *       comes from the SDK, so {@code GameLoop.java} and {@code Startup.java} are no longer generated — and a
 *       project still carrying them keeps compiling against a 3-arg {@code Bot.start} that no longer exists.</li>
 *   <li><b>The popup guard.</b> {@link #installPopupGuard} adds the {@code PopupGuard.install(...)} line, so an
 *       older project gets the before-every-vision-step popup check instead of being the one project where the
 *       feature quietly doesn't exist. It used to restore a missing {@code Popups.java} as well; since
 *       2026-08-25 it cannot (the scaffold's text left with the SDK's templates), so a project without that
 *       file is skipped whole and picks the guard up at the first open after inversion phase 2.</li>
 * </ul>
 *
 * <p>Run at project open, next to {@link BotSettings#migrate}, for the same reason: it is the one moment we
 * know the project and haven't yet built the file explorer that would go on listing files we are about to
 * delete.
 *
 * <p><b>Nothing is deleted unless the entry point was ours to begin with.</b> The whole migration is gated on
 * finding the generated 3-arg call in {@code main}: that call is the proof this is a BotMaker game bot and
 * these two files are BotMaker's copies, not a {@code GameLoop.java} someone wrote themselves in an empty
 * project. Inside that gate a delete is unconditional on content — both files were {@code MethodLock.FULL} in
 * every generation of the scaffold, so the Studio never let anyone put work in them, and leaving a legacy one
 * behind (the pre-flow inline dispatch loop, say) means a project that still carries dead wiring it cannot
 * edit. The rewrite still runs first, so a failure halfway leaves a project that compiles.
 */
public final class ScaffoldMigration {

    private ScaffoldMigration() {}

    private static final String GAME_LOOP_FILE = "GameLoop.java";
    private static final String STARTUP_FILE = "Startup.java";

    /**
     * The legacy 3-arg entry-point call. {@code supervise} is the pre-rename spelling; the middle argument is
     * captured rather than assumed because it is the user's — {@code GoHome.INSTANCE::execute} today, but an
     * older project may bind {@code GoHome::run}, and either way it is not ours to rewrite.
     */
    private static final Pattern LEGACY_START = Pattern.compile(
            "Bot\\.(?:start|supervise)\\s*\\(\\s*([^,]+?)\\s*,\\s*([^,]+?)\\s*,\\s*Startup::run\\s*\\)");

    /**
     * Migrates {@code config}'s project if it still carries the retired files or the 3-arg call. Returns the
     * rewritten entry-point source when {@code main} changed (so the caller can refresh its cached copy), or
     * {@code null} when nothing needed doing.
     */
    public static String migrate(ProjectConfig config) throws IOException {
        Path main = config.mainSourceFile();
        Path dir = main == null ? null : main.getParent();
        if (dir == null || !Files.exists(main)) return null;

        String source = Files.readString(main);
        String updated = rewriteEntryPoint(source);
        if (!updated.equals(source)) {
            Files.writeString(main, updated);
            deleteIfRetired(dir.resolve(GAME_LOOP_FILE), updated, "GameLoop");
            deleteIfRetired(dir.resolve(STARTUP_FILE), updated, "Startup");
        }

        // The guard names Popups.INSTANCE, so it can only be installed where that file exists or can be
        // written — and since 2026-08-25 nothing here can write it (its text left with the SDK's scaffold
        // templates; inversion phase 2 restores the generator). Skipping is right rather than merely
        // tolerable: this whole step is content-gated, not schema-stamped, so a project that misses the guard
        // today simply gets it at the first open after phase 2. The retired-files half above still runs.
        String guarded = Files.exists(dir.resolve(POPUPS_FILE)) ? installPopupGuard(updated) : updated;
        if (!guarded.equals(updated)) {
            Files.writeString(main, guarded);
        }
        return guarded.equals(source) ? null : guarded;   // null: nothing needed doing
    }

    /**
     * Adds the popup guard to an entry point generated before it existed:
     * {@code PopupGuard.install(Popups.INSTANCE::execute);} above the {@code Bot.start} call, plus its import.
     *
     * <p>Gated on both the import and the call being the ones we generate — a user who rewrote their own entry
     * point owns it, and the import anchor is also what tells a game bot from an empty project. Idempotent: a
     * source that already names {@code PopupGuard} is returned untouched, whether we put it there or the user
     * did.
     */
    static String installPopupGuard(String source) {
        if (source.contains(POPUP_GUARD_TYPE) || !source.contains(BOT_IMPORT)) return source;

        int call = source.indexOf("Bot.start(");
        if (call < 0) return source;
        int lineStart = source.lastIndexOf('\n', call) + 1;
        String indent = source.substring(lineStart, call);
        if (!indent.isBlank()) return source;   // not a statement of its own: leave it alone

        String withImport = source.replace(BOT_IMPORT, BOT_IMPORT + "\nimport " + POPUP_GUARD_FQN + ";");
        int at = withImport.indexOf("Bot.start(");
        int insertAt = withImport.lastIndexOf('\n', at) + 1;
        return withImport.substring(0, insertAt)
                + indent + "// Dismiss popups before every vision step; Popups.java is yours to fill in.\n"
                + indent + "PopupGuard.install(Popups.INSTANCE::execute);\n\n"
                + withImport.substring(insertAt);
    }

    private static final String POPUPS_FILE = "Popups.java";
    private static final String POPUP_GUARD_TYPE = SdkType.POPUP_GUARD.simpleName();
    private static final String POPUP_GUARD_FQN = SdkType.POPUP_GUARD.qualifiedName();
    private static final String BOT_IMPORT = "import " + SdkType.BOT.qualifiedName() + ";";

    /**
     * {@code Bot.start(GameLoop::run, goHome, Startup::run)} → {@code Bot.start(FlowDriver::run, goHome)}.
     * Returns {@code source} unchanged when the call isn't the shape we generated — a user who rewrote their own
     * entry point owns it.
     *
     * <p>The body argument is only retargeted when it is the {@code GameLoop::run} hop we are deleting; anything
     * else the user bound there stays bound.
     */
    static String rewriteEntryPoint(String source) {
        Matcher m = LEGACY_START.matcher(source);
        if (!m.find()) return source;

        String body = m.group(1);
        String goHome = m.group(2);
        if ("GameLoop::run".equals(body)) body = "FlowDriver::run";
        String rewritten = new StringBuilder(source)
                .replace(m.start(), m.end(), "Bot.start(" + body + ", " + goHome + ")")
                .toString();
        // The line above the call described the two files by name. Left behind it would document a shape the
        // project no longer has — and, since the delete below refuses to remove a type the entry point still
        // names, a stale comment would also be enough to keep Startup.java on disk forever.
        return rewritten.replace(LEGACY_COMMENT, CURRENT_COMMENT);
    }

    private static final String LEGACY_COMMENT =
            "// Runs GameLoop forever; on a crash or a stuck screen it runs GoHome then Startup and restarts.";
    private static final String CURRENT_COMMENT =
            "// Walks the Activity Flow forever; on a crash or a stuck screen it runs GoHome and\n"
            + "            // restarts the game you picked in the Studio.";

    /**
     * Deletes {@code file} once the rewritten entry point has stopped naming {@code typeName} — if the user put
     * the reference back by hand, the file is still load-bearing and stays.
     *
     * <p>"Names it" is judged with comments stripped. A comment mentioning {@code Startup} is not a call to it,
     * and a project whose entry point carries an older phrasing of ours must not keep a file forever because of
     * prose.
     */
    private static void deleteIfRetired(Path file, String entryPoint, String typeName) throws IOException {
        if (Files.exists(file) && !stripComments(entryPoint).contains(typeName)) {
            Files.delete(file);
        }
    }

    /** {@code source} with its line and block comments blanked out. Not a parser — a filter. */
    static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
