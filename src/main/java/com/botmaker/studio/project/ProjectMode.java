package com.botmaker.studio.project;

import com.botmaker.studio.sharing.BotSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a project opens for <em>reading</em> (someone else's installed bot, shown full-colour but with every
 * edit path refused) or for <em>editing</em> (your own code). This is a per-checkout, local-only distinction —
 * it never ships with a published bot — so it is derived from provenance plus a local opt-in marker rather than
 * persisted in {@code settings.json}:
 *
 * <ul>
 *   <li>A locally-created project (no {@link BotSource} provenance) is <b>always editable</b> — it is yours.</li>
 *   <li>An installed community bot (has provenance) defaults to <b>reading</b>, until the user chooses
 *       "Improve this bot", which drops the {@link #MARKER} file and flips it to editing for good.</li>
 * </ul>
 *
 * <p>Enforcement is not here — it is in {@link LockResolver}, the one authority on whether a node may change.
 * This class only decides the initial/effective mode; {@code LockResolver} turns "reader" into a denial that
 * outranks every {@link FileRole}/{@link MethodLock} verdict.
 */
public final class ProjectMode {

    /** Local opt-in marker: its presence means the user switched an installed bot to Editor mode. */
    public static final String MARKER = ".botmaker-editing";

    private ProjectMode() {}

    /** True when {@code projectDir} should open read-only: it has provenance and hasn't been opted into editing. */
    public static boolean isReader(Path projectDir) {
        if (projectDir == null) return false;
        boolean installedFromSomeoneElse = BotSource.read(projectDir).isPresent();
        if (!installedFromSomeoneElse) return false;      // locally created → always yours to edit
        return !Files.exists(projectDir.resolve(MARKER));
    }

    /** Marks {@code projectDir} as opted into Editor mode (idempotent). */
    public static void switchToEditor(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(MARKER), "editor" + System.lineSeparator());
    }
}
