package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;

/**
 * Standalone window over the project's version control — a thin host for {@link VcsPanel}, which is the one
 * implementation shared with the "VCS" bottom tab. Kept for the menu / toolbar entry points that want VCS in a
 * dialog rather than the docked tab.
 */
public class VcsDialog {

    private final Window owner;
    private final VcsPanel panel;

    public VcsDialog(Window owner, String projectName, Path projectDir, BotPublisher publisher,
                     GitHubAuth auth, GitHubClient client, EventBus eventBus, Runnable openPublish) {
        this.owner = owner;
        this.panel = new VcsPanel(owner, projectName, projectDir, publisher, auth, client, eventBus, openPublish);
    }

    public void show() {
        StudioWindow window = StudioWindow.modal(
                        "vcs", "Version Control — " + panel.projectName(), owner)
                .size(720, 520).minSize(560, 400);

        VBox root = new VBox(panel.getView());
        root.setPadding(new Insets(4));
        VBox.setVgrow(panel.getView(), javafx.scene.layout.Priority.ALWAYS);
        window.show(root);
        panel.refresh();
    }
}
