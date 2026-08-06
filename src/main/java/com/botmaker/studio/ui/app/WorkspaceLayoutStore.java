package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.StudioProjectSettings.WorkspaceLayout;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.Map;

/**
 * Remembers how the main window was arranged: the two split-pane dividers and the open bottom tab.
 *
 * <p>The window's own geometry already survives a restart ({@code ProjectPreferences.WindowState}); this is
 * the missing half. It is saved <b>per project</b> and read back at open, because how much of the window the
 * file tree or the console deserves follows from the bot: one being read wants a wide tree, one being
 * debugged wants a tall terminal.
 *
 * <p>Written once, at teardown, rather than on every drag. A divider's position changes continuously while
 * it is dragged and again whenever the window is resized, so listening would mean rewriting a file in the
 * user's <em>versioned</em> project on every frame of a drag; {@link #save()} on the way out costs one write
 * per session and picks up exactly the same final positions.
 */
final class WorkspaceLayoutStore {

    private final ProjectSettingsService settings;
    private final SplitPane explorerSplit;
    private final SplitPane bottomSplit;
    private final TabPane bottomTabPane;
    private final Map<BottomTab, Tab> tabs;

    WorkspaceLayoutStore(ProjectSettingsService settings, SplitPane explorerSplit, SplitPane bottomSplit,
                         TabPane bottomTabPane, Map<BottomTab, Tab> tabs) {
        this.settings = settings;
        this.explorerSplit = explorerSplit;
        this.bottomSplit = bottomSplit;
        this.bottomTabPane = bottomTabPane;
        this.tabs = tabs;
    }

    /**
     * Applies the remembered layout over the defaults each pane was built with. A project that has never
     * been arranged, an unusable divider and a tab this Studio doesn't have all leave the default in place.
     */
    void restore() {
        WorkspaceLayout saved = settings.current().workspaceLayout();
        if (saved == null) return;

        explorerSplit.setDividerPositions(saved.explorerDividerOr(explorerSplit.getDividerPositions()[0]));
        bottomSplit.setDividerPositions(saved.bottomDividerOr(bottomSplit.getDividerPositions()[0]));

        Tab tab = tabs.get(BottomTab.named(saved.bottomTab()));
        if (tab != null) bottomTabPane.getSelectionModel().select(tab);
    }

    /** Writes the layout as it stands. Called from {@code UIManager.dispose()}; idempotent and best-effort. */
    void save() {
        settings.saveNow(settings.current().withWorkspaceLayout(snapshot()));
    }

    /** The current arrangement. Divider positions are sanitised by {@link WorkspaceLayout} itself. */
    private WorkspaceLayout snapshot() {
        Tab selected = bottomTabPane.getSelectionModel().getSelectedItem();
        String tabKey = tabs.entrySet().stream()
                .filter(e -> e.getValue() == selected)
                .map(e -> e.getKey().key())
                .findFirst().orElse(null);
        return new WorkspaceLayout(dividerOf(explorerSplit), dividerOf(bottomSplit), tabKey);
    }

    /** A split's first divider, or {@code null} if it has none (nothing was ever laid out). */
    private static Double dividerOf(SplitPane split) {
        double[] positions = split.getDividerPositions();
        return positions.length == 0 ? null : positions[0];
    }
}
