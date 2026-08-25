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
 * <p>They are the whole reason the action is trustworthy — a recovery that says "3 files are missing" and then
 * silently repairs a method, or one that offers to regenerate nothing at all, is worse than no recovery. Both
 * are {@code static} and JavaFX-free, so the counting is checkable without a toolkit.
 */
class ProjectRecoveryTest {

    private static ProjectRepair.Missing missing(String name) {
        return ProjectRepair.Missing.ofSource(Path.of("/p/src", name), "// source",
                "files BotMaker generates");
    }

    private static ProjectRepair.Damage damage(String method) {
        return new ProjectRepair.Damage(Path.of("/p/src/Main.java"), method,
                ProjectRepair.Damage.Kind.SIGNATURE_CHANGED);
    }

    @Test
    void missingFilesAloneAreReportedAsRegeneration() {
        assertEquals("2 file(s) are missing and will be regenerated.",
                ProjectRecoveryAction.headerFor(List.of(missing("A.java"), missing("B.java")), List.of()));
    }

    @Test
    void damagedMethodsAloneAreReportedAsRestoration() {
        assertEquals("1 method(s) BotMaker needs will be restored.",
                ProjectRecoveryAction.headerFor(List.of(), List.of(damage("run"))));
    }

    /** Both kinds present: the header must name both, since the dialog is the only place the user sees them. */
    @Test
    void bothKindsAreNamedWhenBothWereFound() {
        String header = ProjectRecoveryAction.headerFor(List.of(missing("A.java")),
                List.of(damage("run"), damage("stop")));
        assertTrue(header.contains("1 file(s)"), header);
        assertTrue(header.contains("2 method(s)"), header);
    }

    /** Nothing repaired means nothing claimed repaired — the clause is dropped, not written as "0 file(s)". */
    @Test
    void theSummaryMentionsRepairsOnlyWhenSomethingWasRepaired() {
        assertEquals("Recovered 2 file(s).",
                ProjectRecoveryAction.summaryOf(List.of(missing("A.java"), missing("B.java")), List.of()));
        assertEquals("Recovered 1 file(s) and repaired 1 file(s).",
                ProjectRecoveryAction.summaryOf(List.of(missing("A.java")), List.of(Path.of("/p/src/Main.java"))));
    }

    /** The body groups the missing files under their reason and lists every damaged method by name. */
    @Test
    void theDetailListsEveryFileAndEveryDamagedMethod() {
        String detail = ProjectRecoveryAction.detailOf(List.of(missing("A.java"), missing("B.java")),
                List.of(damage("run")));
        assertTrue(detail.contains("files BotMaker generates:"), detail);
        assertTrue(detail.contains("A.java"), detail);
        assertTrue(detail.contains("B.java"), detail);
        assertTrue(detail.contains("Main.java.run — signature changed"), detail);
    }

    @Test
    void anEmptyDetailIsEmptyRatherThanBlankLines() {
        assertEquals("", ProjectRecoveryAction.detailOf(List.of(), List.of()));
    }
}
