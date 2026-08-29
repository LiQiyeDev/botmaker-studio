package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectRepair;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two sentences Recover Project Files shows the user.
 *
 * <p>They are the whole reason the action is trustworthy — a recovery that offers to regenerate nothing at
 * all is worse than no recovery. Both are {@code static} and JavaFX-free, so the counting is checkable
 * without a toolkit.
 *
 * <p>The damaged-method half of this test went on 2026-08-29 with the machinery behind it: a "damaged" method
 * was one that no longer matched what the generator would write, and nothing generates a project's Java.
 */
class ProjectRecoveryTest {

    private static ProjectRepair.Missing missing(String name) {
        return ProjectRepair.Missing.ofSource(Path.of("/p", name), "// content",
                "files BotMaker needs");
    }

    @Test
    void missingFilesAreReportedByCount() {
        assertEquals("2 file(s) are missing and will be restored.",
                ProjectRecoveryAction.headerFor(List.of(missing("pom.xml"), missing("settings.json"))));
    }

    @Test
    void theSummaryCountsWhatWasRecovered() {
        assertEquals("Recovered 2 file(s).",
                ProjectRecoveryAction.summaryOf(List.of(missing("pom.xml"), missing("settings.json"))));
    }

    /** The body groups the missing files under their reason. */
    @Test
    void theDetailListsEveryFileUnderItsReason() {
        String detail = ProjectRecoveryAction.detailOf(List.of(missing("pom.xml"), missing("settings.json")));
        assertTrue(detail.contains("files BotMaker needs:"), detail);
        assertTrue(detail.contains("pom.xml"), detail);
        assertTrue(detail.contains("settings.json"), detail);
    }

    @Test
    void anEmptyDetailIsEmptyRatherThanBlankLines() {
        assertEquals("", ProjectRecoveryAction.detailOf(List.of()));
    }
}
