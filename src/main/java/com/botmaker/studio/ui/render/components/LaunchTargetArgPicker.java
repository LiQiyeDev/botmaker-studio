package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.game.EpicLibraryScanner;
import com.botmaker.studio.game.GameLibraryProvider;
import com.botmaker.studio.game.SteamLibraryScanner;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.io.File;
import java.util.List;

/**
 * Editor for a {@code LaunchTarget} argument (what the bot automates): a menu button offering the four kinds a
 * launch target can be — a Steam game, an Epic game, a plain executable, or an app inside an Android emulator —
 * each via the reusable picker dialog ({@link GameLibraryPickerDialog} / OS file chooser /
 * {@link EmulatorPickerDialog}). The chosen kind is committed as
 * {@code LaunchTarget.parse("<spec>")} via {@link com.botmaker.studio.parser.CodeEditor#replaceWithRawExpression}
 * (fully qualified so no import is needed), matching the {@code launch.target} spec grammar
 * ({@code steam:} / {@code epic:} / {@code exe:} / {@code emu-app:<pkg>@<instance>}).
 *
 * <p>Replaces the plain {@code new …}/constructor pill the user otherwise gets for a {@code LaunchTarget} slot.
 * Selected by {@link com.botmaker.studio.ui.render.components.pickers.PickerRegistry} for any
 * {@code LaunchTarget} parameter.
 */
public final class LaunchTargetArgPicker {

    private static final String PARSE = "com.botmaker.sdk.api.launch.LaunchTarget.parse(\"%s\")";

    private LaunchTargetArgPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        MenuButton button = new MenuButton(label(arg));
        button.getStyleClass().add("launch-target-picker");

        MenuItem steam = new MenuItem("Steam game…");
        steam.setOnAction(e -> pickGame(context, arg, button, new SteamLibraryScanner(), "steam"));
        MenuItem epic = new MenuItem("Epic game…");
        epic.setOnAction(e -> pickGame(context, arg, button, new EpicLibraryScanner(), "epic"));
        MenuItem exe = new MenuItem("Executable…");
        exe.setOnAction(e -> pickExecutable(context, arg, button));
        MenuItem emu = new MenuItem("Emulator app…");
        emu.setOnAction(e -> pickEmulatorApp(context, arg, button));

        button.getItems().addAll(steam, epic, new SeparatorMenuItem(), exe, emu);
        return button;
    }

    private static void pickGame(CodeEditorService context, ExpressionBlock arg, MenuButton button,
                                 GameLibraryProvider provider, String kind) {
        Window owner = owner(button);
        GameLibraryPickerDialog.show(owner, provider).ifPresent(game -> {
            if (game.id() == null || game.id().isBlank()) return;
            apply(context, arg, button, kind + ":" + game.id());
        });
    }

    private static void pickExecutable(CodeEditorService context, ExpressionBlock arg, MenuButton button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a program to launch");
        File chosen = chooser.showOpenDialog(owner(button));
        if (chosen != null) {
            apply(context, arg, button, "exe:" + chosen.getAbsolutePath());
        }
    }

    private static void pickEmulatorApp(CodeEditorService context, ExpressionBlock arg, MenuButton button) {
        EmulatorPickerDialog.show(owner(button)).ifPresent(sel -> {
            if (!sel.hasApp()) return; // a LaunchTarget needs the app package, not just the instance
            apply(context, arg, button, "emu-app:" + sel.appPackage() + "@" + sel.instance().name());
        });
    }

    /** Commits {@code spec} as {@code LaunchTarget.parse("spec")} and refreshes the button label. */
    private static void apply(CodeEditorService context, ExpressionBlock arg, MenuButton button, String spec) {
        context.getCodeEditor().replaceWithRawExpression(exprNode(arg), String.format(PARSE, escape(spec)));
        button.setText(labelFor(spec));
    }

    private static Window owner(MenuButton button) {
        return button.getScene() != null ? button.getScene().getWindow() : null;
    }

    private static String label(ExpressionBlock arg) {
        String spec = currentSpec(arg);
        return spec == null ? "Choose target…" : labelFor(spec);
    }

    private static String labelFor(String spec) {
        int colon = spec.indexOf(':');
        if (colon <= 0) return spec;
        String kind = spec.substring(0, colon);
        String rest = spec.substring(colon + 1);
        return switch (kind) {
            case "steam" -> "Steam: " + rest;
            case "epic" -> "Epic: " + rest;
            case "exe" -> "Exe: " + fileName(rest);
            case "emu-app" -> "Emulator: " + rest;
            default -> spec;
        };
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    /** The spec inside a {@code LaunchTarget.parse("…")} call, else null. */
    private static String currentSpec(ExpressionBlock arg) {
        if (exprNode(arg) instanceof MethodInvocation mi
                && "parse".equals(mi.getName().getIdentifier())) {
            List<?> args = mi.arguments();
            if (!args.isEmpty() && args.get(0) instanceof StringLiteral lit) {
                return lit.getLiteralValue();
            }
        }
        return null;
    }

    /** Escapes a spec for embedding in a Java string literal (Windows exe paths carry backslashes). */
    private static String escape(String spec) {
        return spec.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Expression exprNode(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
