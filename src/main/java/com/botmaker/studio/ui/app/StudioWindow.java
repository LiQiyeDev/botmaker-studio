package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * The one way Studio opens a secondary window.
 *
 * <p>Twenty-three files built a {@link Stage} by hand and each answered the same five questions its own way:
 * who owns it, whether it is modal, whether it is themed, how small it may be dragged, and where it comes up.
 * The answers had drifted — some dialogs set no owner (so the window manager could put them behind the shell
 * and lose them), several passed a size to {@code new Scene(root, w, h)} and none remembered the size the user
 * dragged it to. Every one of those is a property of *being a Studio window*, not of being the Resource
 * Manager, so they live here.
 *
 * <p>The size a caller passes is a <em>default</em>, used the first time and never again: once the window has
 * been resized, {@code ProjectPreferences.loadDialogState(key)} answers instead. That is what the {@code key}
 * is for, and it is why it must stay stable across releases — rename one and users silently get the default
 * back.
 *
 * <p><b>The owner does not move.</b> A dialog opening or closing has repeatedly nudged the main window: the
 * shell records its own geometry only while focused and un-maximized ({@code BotMakerStudio.configureWindow}),
 * which stops the drift being *written*, but not the window manager reporting it in the first place. So the
 * owner's geometry is read before the show and put back at both ends — a pulse after the dialog appears and
 * again when it goes away — the maximize included, since that is the form the drift takes on a maximized shell.
 * It is a small, verifiable guard at the two moments the drift happens, rather than a theory about which of
 * X11, GTK and JavaFX moved it.
 *
 * <p>Typical use — note that the stage exists before the content, because content routinely closes it:
 * <pre>{@code
 * StudioWindow window = StudioWindow.modal("resource-manager", "Resource Manager", owner)
 *         .size(1100, 720).minSize(760, 480);
 * this.stage = window.stage();
 * ... build root, whose buttons call stage.close() ...
 * window.show(root);
 * }</pre>
 */
public final class StudioWindow {

    /** How long after the last move/resize the geometry is written. A drag is hundreds of events. */
    private static final Duration WRITE_DELAY = Duration.millis(600);
    /** Below this, a "remembered" position is one the user cannot reach — an unplugged second screen. */
    private static final double ON_SCREEN_MARGIN = 60;

    private final String key;
    private final Stage stage = new Stage();
    private final Window owner;
    private double width = 900;
    private double height = 640;
    private double minWidth;
    private double minHeight;

    private StudioWindow(String key, String title, Window owner, Modality modality) {
        this.key = key;
        this.owner = owner;
        if (owner != null) stage.initOwner(owner);
        stage.initModality(modality);
        stage.setTitle(title);
    }

    /** A window the user must deal with before returning to the one that opened it — most of Studio's. */
    public static StudioWindow modal(String key, String title, Window owner) {
        return new StudioWindow(key, title, owner, Modality.APPLICATION_MODAL);
    }

    /** A window the user can leave open while working — a palette, a log, a live view. */
    public static StudioWindow plain(String key, String title, Window owner) {
        return new StudioWindow(key, title, owner, Modality.NONE);
    }

    /** The size to open at the <em>first</em> time; afterwards the remembered size wins. */
    public StudioWindow size(double width, double height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * The smallest the window may be dragged. Set it to the point below which the content stops being usable
     * rather than to the point where it stops looking tidy — the whole reason a window has a minimum is that a
     * scroll bar is a better answer than a clipped button.
     */
    public StudioWindow minSize(double width, double height) {
        this.minWidth = width;
        this.minHeight = height;
        return this;
    }

    /** The stage, for the content to close, title or listen to. Not yet shown and not yet sized. */
    public Stage stage() {
        return stage;
    }

    /** Themes {@code content}, restores the geometry, shows the window. */
    public Stage show(Parent content) {
        prepare(content);
        stage.show();
        return stage;
    }

    /** {@link #show} for a caller that blocks until the window is closed. */
    public void showAndWait(Parent content) {
        prepare(content);
        stage.showAndWait();
    }

    private void prepare(Parent content) {
        // Unsized on purpose. A Scene built with a width and height resizes the Stage it is set on, which is
        // the wrong way round here: the stage already knows where it goes, from what the user last did.
        Scene scene = ThemedWindows.scene(content);
        stage.setScene(scene);
        if (minWidth > 0) stage.setMinWidth(minWidth);
        if (minHeight > 0) stage.setMinHeight(minHeight);
        restoreGeometry();
        rememberGeometry();
        pinOwner();
    }

    private void restoreGeometry() {
        ProjectPreferences.WindowState saved = ProjectPreferences.loadDialogState(key);
        if (saved != null && onScreen(saved)) {
            stage.setX(saved.getX());
            stage.setY(saved.getY());
            stage.setWidth(saved.getWidth());
            stage.setHeight(saved.getHeight());
            if (saved.isMaximized()) stage.setMaximized(true);
            return;
        }
        // No usable memory: the caller's size, centred on the owner. Leaving the position to JavaFX centres on
        // the *screen*, which on a second monitor puts the dialog somewhere the user isn't looking.
        stage.setWidth(width);
        stage.setHeight(height);
        centreOnOwner();
    }

    private void centreOnOwner() {
        if (owner == null || Double.isNaN(owner.getX()) || owner.getWidth() <= 0) return;
        stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
        stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
    }

    /** True if enough of {@code state} lands on a screen the user actually has attached. */
    private static boolean onScreen(ProjectPreferences.WindowState state) {
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D b = screen.getVisualBounds();
            boolean overlaps = state.getX() + state.getWidth() - ON_SCREEN_MARGIN > b.getMinX()
                    && state.getX() + ON_SCREEN_MARGIN < b.getMaxX()
                    && state.getY() + state.getHeight() - ON_SCREEN_MARGIN > b.getMinY()
                    && state.getY() + ON_SCREEN_MARGIN < b.getMaxY();
            if (overlaps) return true;
        }
        return false;
    }

    private void rememberGeometry() {
        ProjectPreferences.WindowState state = new ProjectPreferences.WindowState(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
        PauseTransition write = new PauseTransition(WRITE_DELAY);
        write.setOnFinished(e -> ProjectPreferences.saveDialogState(key, state));

        ChangeListener<Number> geom = (obs, was, is) -> {
            if (!stage.isShowing() || stage.isMaximized()) return;
            state.setX(stage.getX());
            state.setY(stage.getY());
            state.setWidth(stage.getWidth());
            state.setHeight(stage.getHeight());
            write.playFromStart();
        };
        stage.xProperty().addListener(geom);
        stage.yProperty().addListener(geom);
        stage.widthProperty().addListener(geom);
        stage.heightProperty().addListener(geom);
        stage.maximizedProperty().addListener((obs, was, is) -> state.setMaximized(is));

        // On hide rather than only on the timer: closing a dialog a second after resizing it is exactly the
        // gesture the debounce would otherwise swallow.
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> {
            write.stop();
            ProjectPreferences.saveDialogState(key, state);
        });
    }

    /**
     * See the class note: the window that opened this one is where it was, afterwards.
     *
     * <p>Two things this used to miss, and both are what the user actually reported. It restored only on
     * <b>hide</b> — but the shift is visible the moment the dialog <em>opens</em>, and a shell that jumps at
     * open and is put back at close has still moved for as long as the dialog is up. And it returned early on
     * a <b>maximized</b> owner, on the reasoning that the window manager owns that geometry: true, and beside
     * the point, because what happens there is the maximize being dropped, which no amount of x/y restoring
     * would have addressed. Asking for the maximize again is the restore for that case.
     */
    private void pinOwner() {
        if (!(owner instanceof Stage ownerStage)) return;
        boolean wasMaximized = ownerStage.isMaximized();
        double x = ownerStage.getX();
        double y = ownerStage.getY();
        double w = ownerStage.getWidth();
        double h = ownerStage.getHeight();
        if (!wasMaximized && (Double.isNaN(x) || w <= 0)) return;

        Runnable restore = () -> {
            if (!ownerStage.isShowing()) return;
            if (wasMaximized) {
                if (!ownerStage.isMaximized()) ownerStage.setMaximized(true);
                return;
            }
            if (ownerStage.isMaximized()) return;   // the user maximized it themselves; that is not drift
            if (ownerStage.getWidth() != w) ownerStage.setWidth(w);
            if (ownerStage.getHeight() != h) ownerStage.setHeight(h);
            if (ownerStage.getX() != x) ownerStage.setX(x);
            if (ownerStage.getY() != y) ownerStage.setY(y);
        };

        // One pulse after the show: the move arrives with the window manager's response to the new window, not
        // synchronously with show(), so correcting it in the same frame corrects nothing.
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> Platform.runLater(restore));
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> restore.run());
    }
}
