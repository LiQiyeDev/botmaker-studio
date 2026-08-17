package com.botmaker.studio.project;

import com.botmaker.studio.sharing.BotSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who a project belongs to, and therefore which window it opens in. The rule is deliberately conservative in
 * one direction only: a bot that might be yours opens for editing, never the reverse.
 */
class ProjectModeTest {

    @Test
    void aProjectYouMadeHereIsAlwaysYours(@TempDir Path dir) {
        // No provenance file at all: nothing to be a reader of.
        assertFalse(ProjectMode.isReader(dir, "someone"));
        assertFalse(ProjectMode.isReader(dir, ""));
    }

    @Test
    void someoneElsesInstalledBotOpensForReadingUntilYouOptIn(@TempDir Path dir) throws Exception {
        new BotSource("otherperson", "cool-bot", "v1.0.0").write(dir);

        assertTrue(ProjectMode.isReader(dir, "me"));

        ProjectMode.switchToEditor(dir);
        assertFalse(ProjectMode.isReader(dir, "me"), "the opt-in marker is permanent");
        assertTrue(Files.exists(dir.resolve(ProjectMode.MARKER)));
    }

    @Test
    void yourOwnPublishedBotComesBackAsYours(@TempDir Path dir) throws Exception {
        // Installing your own bot on a second machine used to present it as somebody else's work: it has
        // provenance like any other, and nobody had dropped the local marker there yet.
        new BotSource("MyLogin", "my-bot", "v2.0.0").write(dir);

        assertFalse(ProjectMode.isReader(dir, "mylogin"), "GitHub logins are case-insensitive");
        assertFalse(ProjectMode.isReader(dir, "  MyLogin  "));
    }

    @Test
    void withNobodySignedInTheMarkerIsStillTheAnswer(@TempDir Path dir) throws Exception {
        new BotSource("MyLogin", "my-bot", "v2.0.0").write(dir);

        // Signed out, an installed bot is just an installed bot — the owner check can't fire, so it must not
        // be what decides. A blank login has to be inert, not a match against a blank owner.
        assertTrue(ProjectMode.isReader(dir, ""));
        assertTrue(ProjectMode.isReader(dir, null));

        ProjectMode.switchToEditor(dir);
        assertFalse(ProjectMode.isReader(dir, null));
    }
}
