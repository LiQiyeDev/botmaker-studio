package com.botmaker.studio.project.launch;

import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.Launcher;
import com.botmaker.studio.project.ProjectCreator;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.nio.file.Path;

/**
 * The "▶ Launch now" button: brings the project's configured {@code launch.target} up <em>without</em>
 * compiling and running the bot, so a user can check the target is right — and, in the Capture Targets dialog,
 * so the game's window exists to be picked at all.
 *
 * <p>It is one line of real work ({@link Launcher#start}) because <b>shared</b> owns the launch stack. That is
 * the point of the move: an earlier attempt at this button copied the protocol URLs and CLI ladders into
 * Studio, which is documented as explicitly <em>not</em> their owner. Studio now knows nothing about how a
 * Steam or Heroic game starts.
 *
 * <p>Two things every call site gets for free by going through here rather than wiring its own button:
 * the launch runs <b>off the FX thread</b> (a protocol hand-off blocks on process spawns, and an
 * {@code emu-app:} target polls an emulator to its boot timeout — seconds of frozen UI otherwise), and a
 * project with no target configured yields a <b>disabled</b> button that says why in its tooltip, rather than
 * an enabled button that silently does nothing.
 */
public final class QuickLaunch {

    private QuickLaunch() {}

    /** How a call site shows the outcome — its own status label, in its own dialog's idiom. */
    @FunctionalInterface
    public interface Report {
        void accept(boolean ok, String message);
    }

    /**
     * A button that launches the target configured in {@code resourcesDir}, reporting through {@code report}
     * (always on the FX thread). Disabled with an explanatory tooltip when no target is configured.
     */
    public static Button button(Path resourcesDir, Report report) {
        Button button = new Button("▶ Launch now");
        bind(button, resourcesDir, report);
        return button;
    }

    /**
     * (Re)points an existing button at whatever {@code resourcesDir} currently configures. Call sites that can
     * <em>change</em> the launch target while the button is on screen (the Launch Target dialog itself) call
     * this after a save, so the button and the target can't disagree.
     */
    public static void bind(Button button, Path resourcesDir, Report report) {
        LaunchSpec spec = specOf(resourcesDir);
        if (spec == null) {
            button.setDisable(true);
            button.setOnAction(null);
            button.setTooltip(new Tooltip(
                    "No launch target configured yet — set one in the Launch Target dialog first."));
            return;
        }
        button.setDisable(false);
        button.setTooltip(new Tooltip("Start " + spec.describe() + " now, without running the bot"));
        button.setOnAction(e -> launch(button, spec, report));
    }

    /** The parsed {@code launch.target} of the project rooted at {@code resourcesDir}, or {@code null}. */
    public static LaunchSpec specOf(Path resourcesDir) {
        if (resourcesDir == null) {
            return null;
        }
        String spec = ProjectCreator.readLaunchTarget(resourcesDir);
        return (spec == null || spec.isBlank()) ? null : LaunchSpec.parse(spec);
    }

    private static void launch(Button button, LaunchSpec spec, Report report) {
        button.setDisable(true);
        report.accept(true, "Launching " + spec.describe() + "…");
        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                Launcher.start(spec);
            } catch (Exception ex) {
                // Launcher.start propagates the underlying failure (Steam not installed, no protocol handler)
                // precisely so it can be shown here instead of vanishing into a log.
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            String message = failure;
            Platform.runLater(() -> {
                button.setDisable(false);
                if (message == null) {
                    report.accept(true, "Launched " + spec.describe() + ".");
                } else {
                    report.accept(false, "Couldn't launch: " + message);
                }
            });
        }, "quick-launch");
        worker.setDaemon(true);
        worker.start();
    }
}
