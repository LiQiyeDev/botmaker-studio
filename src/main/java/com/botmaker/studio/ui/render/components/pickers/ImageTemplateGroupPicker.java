package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A row of template chips standing in for an {@code ImageTemplateGroup.of(new ImageTemplate("…"), …)}
 * expression — the multi-template counterpart of {@link ImageTemplatePicker}. Each chip is a thumbnail
 * menu (change / remove); a trailing "+ image" button adds another template. Every edit rewrites the
 * whole group via {@link com.botmaker.studio.parser.CodeEditor#setImageTemplateGroup}.
 */
public final class ImageTemplateGroupPicker {

    private ImageTemplateGroupPicker() {}

    /** True when {@code type} is the SDK {@code ImageTemplateGroup} (by simple or qualified name). */
    public static boolean isImageTemplateGroupType(com.botmaker.studio.types.ResolvedType type) {
        return type != null
                && (type.simpleName().equals("ImageTemplateGroup")
                    || type.qualifiedName().endsWith(".ImageTemplateGroup"));
    }

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        ProjectConfig config = context.getConfig();
        Expression node = (Expression) ((AbstractCodeBlock) arg).getAstNode();
        List<String> paths = currentPaths(node);

        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("image-template-group-picker");

        for (int i = 0; i < paths.size(); i++) {
            row.getChildren().add(chip(context, config, node, paths, i));
        }
        row.getChildren().add(addButton(context, config, node, paths));
        return row;
    }

    /** One template chip: thumbnail + name, with a menu to change (from the library) or remove it. */
    private static MenuButton chip(CodeEditorService context, ProjectConfig config,
                                   Expression node, List<String> paths, int index) {
        MenuButton button = new MenuButton();
        button.getStyleClass().add("image-template-picker");
        String path = paths.get(index);
        Path file = config.projectPath().resolve(path);
        button.setText(ImageTemplateLibrary.baseName(file));
        button.setGraphic(ImageTemplatePicker.thumbnail(file, 24));

        button.setOnShowing(e -> {
            button.getItems().clear();
            for (Path lib : ImageTemplateLibrary.list(config)) {
                MenuItem item = new MenuItem(ImageTemplateLibrary.baseName(lib), ImageTemplatePicker.thumbnail(lib, 18));
                item.setOnAction(a -> apply(context, node, replace(paths, index, ImageTemplateLibrary.pathFor(config, lib))));
                button.getItems().add(item);
            }
            if (!button.getItems().isEmpty()) button.getItems().add(new SeparatorMenuItem());
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(a -> apply(context, node, without(paths, index)));
            MenuItem openManager = new MenuItem("Open Resource Manager…");
            openManager.setOnAction(a ->
                    context.getEventBus().publish(new CoreApplicationEvents.OpenResourceManagerEvent()));
            button.getItems().addAll(remove, openManager);
        });
        return button;
    }

    /** The trailing "add another template" button, populated from the library. */
    private static MenuButton addButton(CodeEditorService context, ProjectConfig config,
                                        Expression node, List<String> paths) {
        MenuButton add = new MenuButton(paths.isEmpty() ? "Choose images…" : "＋");
        add.getStyleClass().add("image-template-group-add");
        add.setOnShowing(e -> {
            add.getItems().clear();
            for (Path lib : ImageTemplateLibrary.list(config)) {
                MenuItem item = new MenuItem(ImageTemplateLibrary.baseName(lib), ImageTemplatePicker.thumbnail(lib, 18));
                item.setOnAction(a -> apply(context, node, append(paths, ImageTemplateLibrary.pathFor(config, lib))));
                add.getItems().add(item);
            }
            if (!add.getItems().isEmpty()) add.getItems().add(new SeparatorMenuItem());
            MenuItem openManager = new MenuItem("Open Resource Manager…");
            openManager.setOnAction(a ->
                    context.getEventBus().publish(new CoreApplicationEvents.OpenResourceManagerEvent()));
            add.getItems().add(openManager);
        });
        return add;
    }

    private static void apply(CodeEditorService context, Expression node, List<String> newPaths) {
        context.getCodeEditor().setImageTemplateGroup(node, newPaths);
    }

    /** Reads the template paths from an {@code ImageTemplateGroup.of("…", …)} call, or empty if not one yet. */
    private static List<String> currentPaths(Expression node) {
        List<String> out = new ArrayList<>();
        if (node instanceof MethodInvocation mi
                && "of".equals(mi.getName().getIdentifier())
                && mi.getExpression() != null
                && mi.getExpression().toString().endsWith("ImageTemplateGroup")) {
            for (Object a : mi.arguments()) {
                if (a instanceof ClassInstanceCreation cic && !cic.arguments().isEmpty()
                        && cic.arguments().get(0) instanceof StringLiteral sl) {
                    out.add(sl.getLiteralValue());
                }
            }
        }
        return out;
    }

    private static List<String> append(List<String> base, String path) {
        List<String> copy = new ArrayList<>(base);
        copy.add(path);
        return copy;
    }

    private static List<String> replace(List<String> base, int index, String path) {
        List<String> copy = new ArrayList<>(base);
        copy.set(index, path);
        return copy;
    }

    private static List<String> without(List<String> base, int index) {
        List<String> copy = new ArrayList<>(base);
        copy.remove(index);
        return copy;
    }

    /** The {@link SpecialTypePicker} entry for {@code ImageTemplateGroup} params. */
    public static SpecialTypePicker asSpecialType() {
        return SpecialTypePicker.of(
                ctx -> isImageTemplateGroupType(ctx.paramType()),
                ctx -> create(ctx.context(), ctx.arg()));
    }
}
