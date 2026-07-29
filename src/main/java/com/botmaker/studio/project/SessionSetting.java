package com.botmaker.studio.project;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The private-display setting — whether the bot runs in its own display and on which backend — and the one
 * place that writes it.
 *
 * <p><b>Why it needs an owner.</b> The setting exists in two forms at once, and both are load-bearing:
 * <ul>
 *   <li>a {@code Session.disable()} / {@code Session.useBackend("…")} statement in the generated
 *       {@link BotSettings} source, so it travels with the bot and applies when it runs outside the Studio;</li>
 *   <li>the {@code session.isolated} / {@code session.backend} keys in {@code botmaker-project.properties},
 *       because <em>Studio's own</em> Launch buttons need it and Studio doesn't depend on the SDK — it can't
 *       run the generated statement to find out.</li>
 * </ul>
 * Two surfaces edit it (the Input &amp; Clicks dialog's Session section and the Launch Target dialog's "Run in
 * background" toggle), so without a single writer it takes one save in the wrong dialog to leave the two forms
 * disagreeing — and disagreeing badly, because the SDK ranks an explicit {@code Session} call <em>above</em> the
 * project key: a stale {@code Session.disable()} in the source would silently beat the checkbox the user just
 * ticked. {@link #write} always writes both.
 *
 * <p><b>Reading is from the properties file</b>, which is the form Studio itself consumes and the one both
 * surfaces write; an absent key means the default (isolated, automatic backend). A hand-edited statement in the
 * generated source is still honoured at <em>run</em> time by the SDK's ladder — it just isn't what seeds these
 * dialogs.
 */
public record SessionSetting(boolean isolated, BotSettings.SessionBackend backend) {

    /** The default: a private display, backend chosen by launch kind — matching the SDK's own defaults. */
    public static final SessionSetting DEFAULT = new SessionSetting(true, BotSettings.SessionBackend.AUTO);

    /** The project's current setting, from {@code botmaker-project.properties}; defaults when unset. */
    public static SessionSetting read(Path resourcesDir) {
        if (resourcesDir == null) {
            return DEFAULT;
        }
        return new SessionSetting(
                ProjectCreator.readSessionIsolated(resourcesDir),
                BotSettings.SessionBackend.fromId(ProjectCreator.readSessionBackend(resourcesDir)));
    }

    /**
     * Writes both forms: the two project-properties keys, and the regenerated {@code BotSettings.java} carrying
     * the matching {@code Session} statements (every other setting in that file is preserved — it is read back
     * first). Returns the regenerated source so a caller holding the file in an editor can refresh it, or
     * {@code null} when there was no entry point to regenerate beside.
     */
    public static String write(ProjectConfig config, SessionSetting setting) throws IOException {
        Path resourcesDir = config.resourcesRoot();
        ProjectCreator.writeSessionIsolated(resourcesDir, setting.isolated());
        ProjectCreator.writeSessionBackend(resourcesDir, setting.backend().id());

        Path main = config.mainSourceFile();
        if (main == null) {
            return null;
        }
        BotSettings current = BotSettings.read(BotSettings.fileFor(main));
        BotSettings updated = current.withSession(setting.isolated(), setting.backend());
        BotSettings.write(main, config.packageName(), updated);
        return BotSettings.source(config.packageName(), updated);
    }
}
