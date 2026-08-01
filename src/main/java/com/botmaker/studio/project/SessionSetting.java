package com.botmaker.studio.project;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The private-display setting — whether the bot runs in its own display and on which backend — as the
 * {@code session.isolated} / {@code session.backend} keys of {@code botmaker-project.properties}.
 *
 * <p>Two surfaces edit it: the Input &amp; Clicks dialog's Session section and the Launch Target dialog's "Run
 * in background" toggle. Both go through here, and both the SDK (at run time) and Studio's own Launch buttons
 * (which cannot ask the SDK — Studio does not depend on it) read the same two keys.
 *
 * <p>It used to be stored <em>twice</em>, as these keys and as a {@code Session.disable()} statement in the
 * generated {@code BotSettings.java}, which is what made a single writer necessary: the SDK ranks an explicit
 * {@code Session} call above the project key, so a stale statement would silently beat the checkbox the user
 * had just ticked. With the generated file gone there is one form and that hazard with it — this type stays
 * because the pairing of the two keys, and their defaults, is still worth naming once.
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

    /** Writes the two keys, leaving the project's other settings alone. */
    public static void write(ProjectConfig config, SessionSetting setting) throws IOException {
        ProjectCreator.writeSessionIsolated(config.resourcesRoot(), setting.isolated());
        ProjectCreator.writeSessionBackend(config.resourcesRoot(), setting.backend().id());
    }
}
