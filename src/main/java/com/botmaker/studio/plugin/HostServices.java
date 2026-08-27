package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Capture;
import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.Region;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureRegion;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.app.capture.ColorSampler;
import com.botmaker.studio.ui.app.capture.GameFrame;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.NativeFileDialog;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Studio's own side of {@link StudioServices} — the host capabilities a plugin's editor may reach.
 *
 * <p><b>Everything here already existed; none of it is new capability.</b> The theme is
 * {@link ThemedWindows}, the region and colour pickers are {@link ScreenCaptureService}, the paths are the
 * open project's {@link ProjectConfig}. What the class adds is the *shape* — a set of interfaces a plugin
 * can compile against without seeing a Studio type, which is the only way an editor written outside this
 * repository can look and behave like one written inside it.
 *
 * <p><b>The window owner is asked for, never held.</b> A plugin's editor is built inside some dialog and may
 * outlive it; a captured {@code Window} would be a stale parent the moment that dialog closes, which is how
 * a modal ends up behind the window it is supposed to block. So the owner arrives as a {@link Supplier}
 * evaluated at the moment a dialog is actually shown.
 *
 * <p><b>It fails soft, on purpose.</b> A plugin has no way to know whether a project is open or whether this
 * platform has a native file dialog, and an editor is drawn while the user is looking at it. So a missing
 * project answers with the working directory rather than null, and every callback that cannot be satisfied is
 * simply not invoked — which the contract documents as the cancel case, and which is what a plugin already
 * has to handle.
 */
public final class HostServices implements StudioServices {

    private final ProjectConfig project;
    private final ScreenCaptureService capture;
    private final Supplier<Window> owner;

    public HostServices(ProjectConfig project, ScreenCaptureService capture, Supplier<Window> owner) {
        this.project = project;
        this.capture = capture;
        this.owner = owner == null ? () -> null : owner;
    }

    /** The services for the project {@code config} describes, with {@code owner} as the dialog parent. */
    public static HostServices forProject(ProjectConfig config, Supplier<Window> owner) {
        return new HostServices(config, config == null ? null : ScreenCaptureService.forProjectFiles(config),
                owner);
    }

    /** The same, parented on whichever window has focus when a dialog is actually opened. */
    public static HostServices forProject(ProjectConfig config) {
        return forProject(config, HostServices::focusedWindow);
    }

    /**
     * The focused window, or null.
     *
     * <p>The right default owner for an editor that does not know which dialog it was built into: it is
     * evaluated at the moment a dialog is shown, so it names the window the user is actually looking at
     * rather than one that may since have closed.
     */
    public static Window focusedWindow() {
        return Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
    }

    @Override
    public Path projectDir() {
        return project == null ? Path.of("") : project.projectPath();
    }

    @Override
    public Path resourcesDir() {
        return project == null ? Path.of("") : project.resourcesRoot();
    }

    @Override
    public Theme theme() {
        return THEME;
    }

    @Override
    public Capture capture() {
        return new CaptureAdapter();
    }

    @Override
    public Dialogs dialogs() {
        return new DialogsAdapter();
    }

    /**
     * Stateless, so one instance serves every plugin: {@link ThemedWindows} is entirely static, and the
     * current theme is a property of the application rather than of whoever is asking.
     */
    private static final Theme THEME = new Theme() {
        @Override public void apply(Scene scene) { ThemedWindows.apply(scene); }

        @Override public void apply(Stage stage) { ThemedWindows.apply(stage); }

        @Override public void apply(Dialog<?> dialog) { ThemedWindows.apply(dialog); }

        @Override public void apply(DialogPane pane) { ThemedWindows.apply(pane); }

        @Override public Scene scene(Parent root) { return ThemedWindows.scene(root); }

        @Override public Scene scene(Parent root, double width, double height) {
            return ThemedWindows.scene(root, width, height);
        }

        @Override public Alert alert(Alert.AlertType type) { return ThemedWindows.alert(type); }

        @Override public Alert alert(Alert.AlertType type, String message, ButtonType... buttons) {
            return ThemedWindows.alert(type, message, buttons);
        }

        @Override public void applyThemeClass(Parent root) { ThemedWindows.applyThemeClass(root); }
    };

    private final class CaptureAdapter implements Capture {

        @Override
        public void selectRegion(Consumer<Region> onSelected) {
            if (capture == null || onSelected == null) return;
            // int[] {x, y, w, h} is the host's own wire for a drag; the contract's Region is the same four
            // numbers with names on them, which is the whole of the adaptation.
            capture.selectRegion(owner.get(), r -> onSelected.accept(new Region(r[0], r[1], r[2], r[3])));
        }

        @Override
        public void pickPoint(Consumer<Region> onPicked) {
            if (capture == null || onPicked == null) return;
            // A Region with no size: the contract has one coordinate type, and a point is a region whose
            // width and height are nobody's business. Cheaper than a second record for two ints.
            capture.pickPoint(owner.get(), p -> onPicked.accept(new Region(p[0], p[1], 0, 0)));
        }

        @Override
        public void sampleColor(Consumer<Color> onSampled) {
            if (capture == null || onSampled == null) return;
            capture.pickColor(owner.get(), pick -> {
                java.awt.Color c = pick.color();
                onSampled.accept(Color.rgb(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() / 255.0));
            });
        }

        @Override
        public void grabFrame(Consumer<Image> onGrabbed) {
            if (capture == null || onGrabbed == null) return;
            capture.captureDefaultTargetAsync(owner.get(), shot -> {
                if (shot != null && shot.image() != null) onGrabbed.accept(toFxImage(shot.image()));
            });
        }

        /**
         * Through {@link ScreenCaptureService#toFxImage}, which is the one conversion in the application and
         * is null-tolerant — a plugin passing a capture that did not happen gets null back rather than an
         * exception, which is the same contract every host caller already relies on.
         */
        @Override
        public Image toFxImage(BufferedImage image) {
            return ScreenCaptureService.toFxImage(image);
        }

        /**
         * The same grab {@link #grabFrame} makes, kept as AWT pixels and carrying the target's label.
         *
         * <p>Silent on failure by design: {@link ScreenCaptureService#captureDefaultTargetAsync} hands back
         * null when there is no target or the grab came back blank, and the contract says a callback that
         * cannot be satisfied is simply not invoked. What it must never do is fall back to the desktop — a
         * frame of the wrong thing answers a question the editor did not ask.
         */
        @Override
        public void grabTargetFrame(Consumer<Frame> onGrabbed) {
            if (capture == null || onGrabbed == null) return;
            capture.captureDefaultTargetAsync(owner.get(), shot -> {
                if (shot != null && shot.image() != null) {
                    onGrabbed.accept(new Frame(shot.image(), shot.label()));
                }
            });
        }

        /**
         * Grabs a frame, then opens the eyedropper on it — {@link ColorSampler#openOn} rather than
         * {@link ColorSampler#open}, because that overload wants a {@code CodeEditorService} and this class
         * deliberately holds only a project.
         */
        @Override
        public void sampleFromTarget(Consumer<Sample> onSampled) {
            if (onSampled == null) return;
            grabTargetFrame(frame -> ColorSampler.openOn(new GameFrame(frame.image(), frame.label()),
                    owner.get(),
                    picked -> onSampled.accept(new Sample(frame, picked.color(), picked.spread()))));
        }

        @Override
        public void chooseSource(Consumer<SourceChoice> onChosen) {
            if (onChosen == null) return;
            new com.botmaker.studio.ui.app.capture.CaptureSourcePicker(owner.get(), true).showAndWait()
                    .ifPresent(selection -> onChosen.accept(switch (selection) {
                        case com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection.ProjectDefault
                                ignored -> SourceChoice.projectDefault();
                        case com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection.Concrete c ->
                                describe(c.target(), c.region());
                    }));
        }

        @Override
        public SourceChoice defaultSource() {
            if (project == null) return SourceChoice.desktop(null);
            return describe(StudioProjectSettings.read(project.resourcesRoot()).defaultTarget(), null);
        }

        /**
         * A {@link CaptureTarget} as the contract's structural description of it.
         *
         * <p>This is the whole of the translation the plugin platform needs here: Studio knows what a source
         * <em>is</em>, the plugin knows what to <em>write</em>, and neither has to learn the other's
         * vocabulary. A null target is the whole desktop, exactly as it is everywhere else in Studio.
         */
        private static SourceChoice describe(CaptureTarget target, CaptureRegion region) {
            Region narrowed = region != null && region.isValid()
                    ? new Region(region.x(), region.y(), region.width(), region.height())
                    : null;
            if (target instanceof CaptureTarget.ScreenTarget screen) {
                return SourceChoice.monitor(screen.index(), narrowed);
            }
            if (target instanceof CaptureTarget.WindowTarget window) {
                return SourceChoice.window(window.titleSubstring(), narrowed);
            }
            if (target instanceof CaptureTarget.EmulatorTarget emulator) {
                return SourceChoice.emulator(emulator.instanceName(), narrowed);
            }
            return SourceChoice.desktop(narrowed);
        }
    }

    private final class DialogsAdapter implements Dialogs {

        @Override
        public Window owner() {
            return owner.get();
        }

        @Override
        public Choice chooseProgram(Path initialDir) {
            NativeFileDialog.Choice choice =
                    NativeFileDialog.chooseProgram(initialDir == null ? null : initialDir.toString());
            // nativeDialogShown is passed through rather than recomputed: a plugin uses it to decide whether
            // to offer its own fallback, and only the dialog itself knows whether one appeared.
            return new Choice(choice.nativeDialogShown(), choice.path().map(Path::of));
        }

        @Override
        public Choice chooseFile(String title, Path initialDir, String... extensions) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title == null || title.isBlank() ? "Choose a file" : title);
            initialDirectory(initialDir, chooser::setInitialDirectory);
            if (extensions != null && extensions.length > 0) {
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                        String.join(", ", extensions), globs(extensions)));
            }
            return chosen(chooser.showOpenDialog(owner.get()));
        }

        @Override
        public Choice chooseDirectory(String title, Path initialDir) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(title == null || title.isBlank() ? "Choose a folder" : title);
            initialDirectory(initialDir, chooser::setInitialDirectory);
            return chosen(chooser.showDialog(owner.get()));
        }

        private static String[] globs(String[] extensions) {
            String[] globs = new String[extensions.length];
            for (int i = 0; i < extensions.length; i++) {
                String ext = extensions[i] == null ? "" : extensions[i].trim();
                globs[i] = ext.startsWith("*.") ? ext : ext.startsWith(".") ? "*" + ext : "*." + ext;
            }
            return globs;
        }

        /** A directory that does not exist is no directory at all — JavaFX throws rather than ignoring it. */
        private static void initialDirectory(Path dir, Consumer<File> set) {
            if (dir == null) return;
            File file = dir.toFile();
            if (file.isDirectory()) set.accept(file);
        }

        private static Choice chosen(File file) {
            return new Choice(true, file == null ? Optional.empty() : Optional.of(file.toPath()));
        }
    }
}
