package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectRepair;
import com.botmaker.studio.project.ProjectSpecs;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <b>Project ▸ Recover Project Files</b> — puts back the scaffolding BotMaker owns.
 *
 * <p>Two kinds of breakage, because there are two ways to break it (see {@link ProjectRepair}):
 * <b>missing files</b>, deleted outside the Studio or from the explorer, which are recreated but never
 * overwritten; and <b>damaged locked methods</b>, where the file is present but something BotMaker calls has
 * been renamed or rewritten. The second used to be invisible here — the file existed, so recovery declared the
 * project healthy while the bot didn't compile. The user's own methods, and their own method bodies, are never
 * touched by either.
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
     * Runs the whole action: find what is missing or damaged, confirm, restore, and tell the user.
     *
     * @param refreshTree re-reads the project tree once files have been written back
     */
    static void recover(StudioContext ctx, Runnable refreshTree) {
        ProjectConfig config = ctx.config();
        ProjectState state = ctx.state();
        ActivityService activityService = ctx.activityService();

        // Worked out once, up front, and reused below: it is the same answer both times. It can be empty —
        // see canonicalScaffold — and an empty answer simply narrows what recovery attempts.
        Map<Path, String> canonical = canonicalScaffold(ctx);

        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, state.getTemplate(), activityService.current());
        List<ProjectRepair.Damage> damaged =
                ProjectRepair.findDamaged(config, state.getTemplate(), canonical);

        if (missing.isEmpty() && damaged.isEmpty()) {
            Alert ok = ThemedWindows.alert(Alert.AlertType.INFORMATION);
            ok.setTitle("Recover Project Files");
            ok.setHeaderText("Nothing to recover.");
            ok.setContentText("Every file this project needs is present, and nothing BotMaker generates has "
                    + "been changed.");
            ok.showAndWait();
            return;
        }

        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Recover Project Files");
        confirm.setHeaderText(headerFor(missing, damaged));
        confirm.setContentText(detailOf(missing, damaged)
                + "\n\nExisting files are never overwritten, and your own methods — and the bodies of the "
                + "methods you write — are never touched.");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        try {
            ProjectRepair.recover(config, missing);
            List<Path> repaired =
                    ProjectRepair.repairDamaged(config, state.getTemplate(), canonical, damaged);

            // Activity stubs, activities.json and the generated Activities/ActivityRegistry are
            // ActivityService's to write: re-running update() with the current config restores them all. It
            // writes from the config the Studio holds, read out of activities.json at open, so a regenerated
            // file comes back with its values rather than with the type defaults.
            // It writes off-thread, so refresh the tree once it's done rather than racing it.
            if (ProjectRepair.needsActivityRegeneration(missing)) {
                activityService.update(activityService.current())
                        .thenRun(() -> Platform.runLater(refreshTree));
            }

            ctx.eventBus().publish(
                    new CoreApplicationEvents.StatusMessageEvent(summaryOf(missing, repaired)));
            refreshTree.run();

            // A repaired file's blocks on screen are now stale — reload the one being looked at.
            if (state.getActiveFile() != null && repaired.contains(state.getActiveFile().getPath())) {
                ctx.codeEditorService().switchToFile(state.getActiveFile().getPath());
            }
        } catch (IOException ex) {
            Alert err = ThemedWindows.alert(Alert.AlertType.ERROR);
            err.setTitle("Recover Project Files");
            err.setHeaderText("Could not recover the project files.");
            err.setContentText(ex.getMessage());
            err.showAndWait();
        }
    }

    /**
     * What the generators would produce for this project's scaffold today, keyed by path.
     *
     * <p><b>Empty for a game bot, and temporarily.</b> A game bot's generated files are a function of its
     * Activity Flow, and Studio does not yet build the SDK's {@code ProjectModel} to render them from
     * (inversion phase 4) — nor is there a per-activity stub generator here any more. So recovery narrows to
     * what it can still do honestly: an empty project's entry point, which says nothing about a flow, plus
     * the {@link ProjectRepair} passes that need no canonical text. A damaged file whose repair needs one is
     * reported as unrecoverable rather than rewritten from a guess.
     */
    private static Map<Path, String> canonicalScaffold(StudioContext ctx) {
        ProjectConfig config = ctx.config();
        ProjectState state = ctx.state();

        Map<Path, String> byPath = new LinkedHashMap<>();
        Path mainDir = config.mainSourceFile().getParent();
        if (mainDir == null) return byPath;

        ProjectTemplate template = state.getTemplate() != null ? state.getTemplate() : ProjectTemplate.EMPTY;
        if (template == ProjectTemplate.GAME_BOT) return byPath;

        String entryFile = config.className() + ".java";
        String source = ProjectSpecs.generatedSource(config, template,
                MavenService.readSdkVersion(config.projectPath()), entryFile);
        if (source != null) byPath.put(mainDir.resolve(entryFile), source);
        return byPath;
    }

    /** The confirmation's body: what is missing, grouped by reason, then what will be restored. */
    static String detailOf(List<ProjectRepair.Missing> missing, List<ProjectRepair.Damage> damaged) {
        StringBuilder detail = new StringBuilder();
        ProjectRepair.summarise(missing).forEach((reason, names) ->
                detail.append(reason).append(":\n  ").append(String.join("\n  ", names)).append("\n\n"));
        if (!damaged.isEmpty()) {
            detail.append("methods BotMaker needs (will be restored):\n  ");
            detail.append(damaged.stream().map(ProjectRepair.Damage::describe)
                    .collect(Collectors.joining("\n  ")));
            detail.append("\n\n");
        }
        return detail.toString().trim();
    }

    /** The confirmation's headline — one sentence per kind of breakage actually found. */
    static String headerFor(List<ProjectRepair.Missing> missing, List<ProjectRepair.Damage> damaged) {
        if (damaged.isEmpty()) return missing.size() + " file(s) are missing and will be regenerated.";
        if (missing.isEmpty()) return damaged.size() + " method(s) BotMaker needs will be restored.";
        return missing.size() + " file(s) are missing and " + damaged.size()
                + " method(s) BotMaker needs have been changed.";
    }

    /** The status-bar line afterwards. Only mentions repairs when something was actually repaired. */
    static String summaryOf(List<ProjectRepair.Missing> missing, List<Path> repaired) {
        StringBuilder sb = new StringBuilder("Recovered ");
        sb.append(missing.size()).append(" file(s)");
        if (!repaired.isEmpty()) sb.append(" and repaired ").append(repaired.size()).append(" file(s)");
        return sb.append('.').toString();
    }
}
