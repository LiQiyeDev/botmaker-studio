package com.botmaker.studio.ui.app;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Help ▸ Getting Started — a plain-language tour of what BotMaker Studio's features are and how they fit
 * together, with a jump button on each section that opens the matching feature. Studio had no in-app
 * explanations before this; the sections mirror the real workflow (build blocks → tell the bot what to
 * watch/launch → capture templates → run/debug → drive from a phone), and each "Open ▸" reuses the same action
 * {@link UIManager} wires to the toolbar/menu. A footer button jumps straight to {@link ProjectSetupDialog}.
 */
public final class GettingStartedDialog {

    /** The feature-opening actions each section's "Open ▸" button triggers, supplied by {@link UIManager}. */
    public record Actions(Runnable projectSetup, Runnable captureTargets, Runnable launchTarget,
                          Runnable captureTemplates, Runnable resources,
                          Runnable remotePilot, Runnable manageLibraries) {}

    private final Stage owner;
    private final Actions actions;

    public GettingStartedDialog(Stage owner, Actions actions) {
        this.owner = owner;
        this.actions = actions;
    }

    public void show() {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Getting Started");

        Label heading = new Label("Welcome to BotMaker Studio");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        Label intro = new Label("Studio lets you build a bot out of visual blocks, then point it at a game or app "
                + "on your screen. Here's what each part does — click Open to jump straight in.");
        intro.setWrapText(true);
        intro.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        VBox sections = new VBox(14,
                section("1 · Build your bot with blocks",
                        "The center canvas is your program. Drag blocks from the palette on the left to build logic "
                                + "— loops, conditions, clicks, image searches — without writing Java by hand.",
                        null, null),
                section("2 · Tell the bot what to watch (Capture target)",
                        "The capture target is where the bot looks: a whole monitor, a specific window, the whole "
                                + "desktop, or an Android emulator. Vision, clicks and OCR all default to it, so set it "
                                + "to your game window or emulator first.",
                        "Open Capture Targets ▸", actions.captureTargets()),
                section("3 · Tell the bot what to launch (Launch target)",
                        "The launch target is what the bot starts up before it runs — a Steam or Epic game, an "
                                + "executable, or an app inside an emulator. Picking an emulator app also points the "
                                + "capture target at that emulator automatically.",
                        "Open Launch Target ▸", actions.launchTarget()),
                section("4 · Capture image templates",
                        "Draw an overlay over your game to grab little pictures (templates) of buttons, icons or "
                                + "text. Blocks like \"find image\" match against these. You can capture rectangles, "
                                + "ellipses, or cut out an object with a transparent background.",
                        "Open Capture Templates ▸", actions.captureTemplates()),
                section("   Manage your resources",
                        "The Resource Manager lists every image template in the project so you can rename, preview "
                                + "or delete them.",
                        "Open Resource Manager ▸", actions.resources()),
                section("5 · Run & debug",
                        "Use Run to execute the bot, or Debug to step through it block by block with breakpoints. "
                                + "The Terminal and Errors tabs at the bottom show what it printed and what went "
                                + "wrong; for a live view of what the bot actually sees, open Remote Pilot below.",
                        null, null),
                section("6 · Watch and drive it (Remote Pilot)",
                        "Remote Pilot streams what the bot sees to your phone or browser and lets you start, stop "
                                + "and watch it from anywhere — scan the QR code to pair, no VPN needed. Turn on "
                                + "Interact to click and drag in the game yourself, straight from the stream.",
                        "Enable Remote Pilot ▸", actions.remotePilot()),
                section("7 · Add libraries",
                        "Need something extra? Manage Libraries adds third-party Maven dependencies (and picks the "
                                + "SDK version) for the generated bot.",
                        "Open Manage Libraries ▸", actions.manageLibraries()));

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(4));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button setup = new Button("Open Project Setup ▸");
        setup.setOnAction(e -> { stage.close(); if (actions.projectSetup() != null) actions.projectSetup().run(); });
        Button close = new Button("Close");
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, setup, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, heading, intro, new Separator(), scroll, new Separator(), bar);
        root.setPadding(new Insets(18));
        stage.setScene(new Scene(root, 560, 620));
        stage.show();
    }

    /** A titled paragraph with an optional "Open ▸" jump button; {@code action == null} renders text only. */
    private static VBox section(String title, String body, String openText, Runnable action) {
        Label name = new Label(title);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label text = new Label(body);
        text.setWrapText(true);
        text.setStyle("-fx-font-size: 12px;");
        VBox box = new VBox(4, name, text);
        if (openText != null && action != null) {
            Button open = new Button(openText);
            open.setOnAction(e -> action.run());
            box.getChildren().add(open);
        }
        return box;
    }
}
