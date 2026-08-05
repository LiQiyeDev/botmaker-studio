package com.botmaker.studio.ui.app.pilot;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * The small widgets the Remote Pilot dialog, the Funnel wizard and the background-mode box all build the same
 * way — a wrapped label, an external link, a copy-to-clipboard button, a checklist row.
 *
 * <p>They were private statics on {@code UIManager} and are shared by the four classes this package split it
 * into, so they live here rather than being copied three ways.
 */
final class PilotWidgets {

    /** How long a "Copied ✓" button stays flipped before it says what it does again. */
    private static final Duration COPIED_FLASH = Duration.seconds(1.6);

    private PilotWidgets() {
    }

    static Label wrapped(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(460);
        return l;
    }

    static Hyperlink linkBtn(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.setOnAction(e -> com.botmaker.studio.util.BrowserLauncher.open(url));
        return h;
    }

    static Button copyCmdBtn(String command) {
        return copyCmdBtn(command, "Copy");
    }

    /** A small button that copies {@code command} to the clipboard (for shell commands / ACL snippets). */
    static Button copyCmdBtn(String command, String label) {
        Button b = new Button(label);
        b.setTooltip(new Tooltip(command));
        b.setOnAction(e -> {
            copyToClipboard(command);
            flashCopied(b);
        });
        return b;
    }

    /**
     * Says "Copied ✓" for a moment, then puts the button's own label back.
     *
     * <p>These buttons used to keep the confirmation forever, so the second click looked like it had done
     * nothing — the label no longer described what pressing it would do.
     */
    static void flashCopied(Button button) {
        String original = button.getText();
        if ("Copied ✓".equals(original)) return; // a flash is already in flight; don't capture its label
        button.setText("Copied ✓");
        PauseTransition revert = new PauseTransition(COPIED_FLASH);
        revert.setOnFinished(e -> button.setText(original));
        revert.play();
    }

    static void copyToClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /** One wizard checklist row: a ✓/✗ status glyph + label; highlighted orange when it's the active blocker. */
    static HBox stepRow(boolean done, String text, boolean isBlocker) {
        Label glyph = new Label(done ? "✓" : "✗");
        glyph.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (done ? "#27ae60" : "#e67e22") + ";");
        Label label = new Label(text);
        label.setWrapText(true);
        if (isBlocker) label.setStyle("-fx-text-fill: #e67e22; -fx-font-weight: bold;");
        HBox row = new HBox(8, glyph, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
