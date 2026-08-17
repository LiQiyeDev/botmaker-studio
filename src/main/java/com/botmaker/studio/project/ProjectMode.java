package com.botmaker.studio.project;

import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubAuth;

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
 *   <li>A bot whose provenance names the <b>signed-in account</b> is also always editable: you published it,
 *       so re-installing your own bot on a second machine must not present it as somebody else's.</li>
 *   <li>Any other installed bot defaults to <b>reading</b>, until the user chooses "Improve this bot", which
 *       drops the {@link #MARKER} file and flips it to editing for good.</li>
 * </ul>
 *
 * <p>Nothing here decides how a project is <em>drawn</em> any more — an installed bot opens in the Runner
 * window, not in a stripped-down editor — and the editor can preview that window at will. That preview is
 * session-only and deliberately does not route through here: previewing what a user sees must never leave a
 * marker behind that changes what the project is.
 *
 * <p>Enforcement is not here — it is in {@link LockResolver}, the one authority on whether a node may change.
 * This class only decides the initial/effective mode; {@code LockResolver} turns "reader" into a denial that
 * outranks every {@link FileRole}/{@link MethodLock} verdict.
 */
public final class ProjectMode {

    /** Local opt-in marker: its presence means the user switched an installed bot to Editor mode. */
    public static final String MARKER = ".botmaker-editing";

    private ProjectMode() {}

    /** True when {@code projectDir} should open read-only, judged against the account signed in on this machine. */
    public static boolean isReader(Path projectDir) {
        return isReader(projectDir, GitHubAuth.rememberedLogin());
    }

    /**
     * The rule itself, with the signed-in login passed in so it can be tested without credentials on disk.
     *
     * @param signedInLogin the GitHub login signed in here, or blank/null when nobody is
     */
    public static boolean isReader(Path projectDir, String signedInLogin) {
        if (projectDir == null) return false;
        BotSource source = BotSource.read(projectDir).orElse(null);
        if (source == null) return false;                 // locally created → always yours to edit
        if (isOwnedBy(source, signedInLogin)) return false;  // your own published bot, come home
        return !Files.exists(projectDir.resolve(MARKER));
    }

    /** True when {@code source}'s owner is {@code login} — GitHub logins are case-insensitive. */
    private static boolean isOwnedBy(BotSource source, String login) {
        return login != null && !login.isBlank()
                && source.owner() != null && source.owner().equalsIgnoreCase(login.trim());
    }

    /** Marks {@code projectDir} as opted into Editor mode (idempotent). */
    public static void switchToEditor(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(MARKER), "editor" + System.lineSeparator());
    }
}
