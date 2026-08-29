package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectRepair;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.io.IOException;
import java.util.List;

/**
 * <b>Project ▸ Recover Project Files</b> — puts back the files a project needs and BotMaker owns.
 *
 * <p>Which, since 2026-08-29, means the files that are <b>not</b> the user's source: {@code pom.xml},
 * {@code botmaker-project.properties}, {@code settings.json}, {@code activities.json} and the placeholder
 * image template. Missing ones are recreated; a file that exists is never overwritten.
 *
 * <p><b>Two capabilities went with the generator, and the second is the interesting one.</b> Restoring a
 * missing {@code .java} needed something that knew what a project must contain, and nothing does. <b>Damaged
 * locked methods</b> went further: the file was present, so the never-overwrite rule declared the project
 * healthy while a {@code GoHome.run} renamed to {@code goHome} left the bot uncompilable — so recovery
 * rendered the project's whole scaffold from its own SDK and diffed each locked method against it. That
 * needed a canonical text, which no longer exists, and the premise underneath it — that a file can be partly
 * BotMaker's and partly the user's — is precisely what was given up. A project's structure belongs to the
 * user, including the parts of it that used to be load-bearing.
 *
 * <p>The two strings the user reads back — {@link #headerFor} and {@link #summaryOf} — are {@code static} and
 * JavaFX-free so the counting they do is testable without a toolkit.
 *
 * <p>Nothing here is worth an instance: this was a six-field object built once and never looked at again
 * except through {@code Runnable.run()}. It is one menu action, so it is one static call, wired as a method
 * reference from the shell.
 */
final class ProjectRecoveryAction {

    private ProjectRecoveryAction() {
    }

    /**
     * Runs the whole action: find what is missing, confirm, restore, and tell the user.
     *
     * @param refreshTree re-reads the project tree once files have been written back
     */
    static void recover(StudioContext ctx, Runnable refreshTree) {
        ProjectConfig config = ctx.config();
        ProjectState state = ctx.state();
        ActivityService activityService = ctx.activityService();

        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, state.getTemplate(), activityService.current());

        if (missing.isEmpty()) {
            Alert ok = ThemedWindows.alert(Alert.AlertType.INFORMATION);
            ok.setTitle("Recover Project Files");
            ok.setHeaderText("Nothing to recover.");
            ok.setContentText("Every file this project needs is present.");
            ok.showAndWait();
            return;
        }

        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Recover Project Files");
        confirm.setHeaderText(headerFor(missing));
        confirm.setContentText(detailOf(missing)
                + "\n\nExisting files are never overwritten, and none of your own code is touched.");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        try {
            ProjectRepair.recover(config, missing);
            ctx.eventBus().publish(new CoreApplicationEvents.StatusMessageEvent(summaryOf(missing)));
            refreshTree.run();
        } catch (IOException ex) {
            Alert err = ThemedWindows.alert(Alert.AlertType.ERROR);
            err.setTitle("Recover Project Files");
            err.setHeaderText("Could not recover the project files.");
            err.setContentText(ex.getMessage());
            err.showAndWait();
        }
    }

    /** The confirmation's body: what is missing, grouped by reason. */
    static String detailOf(List<ProjectRepair.Missing> missing) {
        StringBuilder detail = new StringBuilder();
        ProjectRepair.summarise(missing).forEach((reason, names) ->
                detail.append(reason).append(":\n  ").append(String.join("\n  ", names)).append("\n\n"));
        return detail.toString().trim();
    }

    /** The confirmation's headline. */
    static String headerFor(List<ProjectRepair.Missing> missing) {
        return missing.size() + " file(s) are missing and will be restored.";
    }

    /** The status-bar line afterwards. */
    static String summaryOf(List<ProjectRepair.Missing> missing) {
        return "Recovered " + missing.size() + " file(s).";
    }
}
