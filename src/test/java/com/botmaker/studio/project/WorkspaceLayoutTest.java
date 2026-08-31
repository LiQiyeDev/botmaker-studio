package com.botmaker.studio.project;

import com.botmaker.studio.project.StudioProjectSettings.WorkspaceLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The remembered window layout: what survives a round trip through {@code settings.json}, and what is
 * refused.
 *
 * <p>A divider is the one persisted value that can make the window unusable — restore 1.0 and the canvas is
 * gone, with no visible control left to get it back. So the sanitising is where the test is.
 */
class WorkspaceLayoutTest {

    @Test
    void aDividerThatWouldHideAPaneIsTreatedAsUnset() {
        assertNull(new WorkspaceLayout(0.0, 0.5, null).explorerDivider());
        assertNull(new WorkspaceLayout(1.0, 0.5, null).explorerDivider());
        assertNull(new WorkspaceLayout(0.5, -0.2, null).bottomDivider());
        assertNull(new WorkspaceLayout(0.5, Double.NaN, null).bottomDivider());
    }

    @Test
    void anUnsetDividerFallsBackToTheCallersDefault() {
        WorkspaceLayout none = new WorkspaceLayout(null, null, null);
        assertEquals(0.25, none.explorerDividerOr(0.25));
        assertEquals(0.82, none.bottomDividerOr(0.82));

        WorkspaceLayout saved = new WorkspaceLayout(0.31, 0.7, null);
        assertEquals(0.31, saved.explorerDividerOr(0.25));
        assertEquals(0.7, saved.bottomDividerOr(0.82));
    }

    /** The layout must not be collateral damage of an unrelated settings edit. */
    @Test
    void anUnrelatedSettingsChangeKeepsTheLayout() {
        StudioProjectSettings settings = StudioProjectSettings.empty()
                .withWorkspaceLayout(new WorkspaceLayout(0.3, 0.6, "VCS"));

        StudioProjectSettings after = settings
                .withTemplate(null)
                .withLastRecordedActivity("Mining");

        assertNotNull(after.workspaceLayout());
        assertEquals(0.3, after.workspaceLayout().explorerDivider());
        assertEquals("VCS", after.workspaceLayout().bottomTab());
    }

    /** Settings written by an older Studio have no layout at all, and must still load. */
    @Test
    void settingsWithoutALayoutStillLoad(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME),
                "{\"captureTargets\":[],\"defaultTargetIndex\":null}");
        StudioProjectSettings read = StudioProjectSettings.read(dir);
        assertNull(read.workspaceLayout());
    }

    @Test
    void theLayoutSurvivesARoundTripThroughDisk(@TempDir Path dir) throws Exception {
        StudioProjectSettings.empty()
                .withWorkspaceLayout(new WorkspaceLayout(0.28, 0.75, "ERRORS"))
                .write(dir);

        WorkspaceLayout read = StudioProjectSettings.read(dir).workspaceLayout();
        assertNotNull(read);
        assertEquals(0.28, read.explorerDivider());
        assertEquals(0.75, read.bottomDivider());
        assertEquals("ERRORS", read.bottomTab());
    }
}
