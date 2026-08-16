package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.helpers.SdkNodes;
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
import java.util.function.Consumer;

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
        return type != null && type.is(SdkType.IMAGE_TEMPLATE_GROUP);
    }

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Expression node = (Expression) ((AbstractCodeBlock) arg).getAstNode();
        return chipRow(context, currentPaths(node),
                paths -> context.getCodeEditor().setImageTemplateGroup(node, paths));
    }

    /**
     * The chip row itself, over an arbitrary list of template paths: one chip per path (thumbnail + change /
     * remove menu) and a trailing add button. Every edit hands {@code apply} the <em>whole</em> new list, so
     * the writer decides what shape it lands in — an {@code ImageTemplateGroup.of(…)} expression here, a run
     * of {@code ImageTemplate...} varargs arguments in {@code MethodInvocationBlock}. Splitting it out is
     * what let an image varargs slot ({@code Matches.hasAny(a, b, c)}) offer the same multi-image editing as
     * a group: it rendered one single-image picker per argument that already existed, so a call could never
     * grow a second template.
     */
    public static Node chipRow(CodeEditorService context, List<String> paths, Consumer<List<String>> apply) {
        return chipRow(context, paths, Restrictions.NONE, apply);
    }

    /**
     * What a chip row is allowed to offer and to leave behind.
     *
     * @param allowed the only template paths the change / add menus may offer, or {@code null} for the whole
     *                project library. The {@code Matches} switch narrows to the templates its enclosing find
     *                call can actually produce, so a case cannot be written that is dead by construction —
     *                but {@code null} stays the default because an over-wide menu beats an empty one.
     * @param minimum how many chips the row refuses to go below, so Remove is disabled rather than absent at
     *                the floor. A guarded case needs it: {@code case Matches m} with no guard is unconditional
     *                and would dominate every case after it, i.e. not compile.
     */
    public record Restrictions(List<String> allowed, int minimum) {
        public static final Restrictions NONE = new Restrictions(null, 0);

        /** Narrowed to {@code allowed} (null for all), never dropping below {@code minimum} chips. */
        public static Restrictions of(List<String> allowed, int minimum) {
            return new Restrictions(allowed, minimum);
        }
    }

    public static Node chipRow(CodeEditorService context, List<String> paths, Restrictions restrictions,
                               Consumer<List<String>> apply) {
        ProjectConfig config = context.getConfig();
        Restrictions limits = restrictions == null ? Restrictions.NONE : restrictions;

        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("image-template-group-picker");

        for (int i = 0; i < paths.size(); i++) {
            row.getChildren().add(chip(context, config, apply, paths, i, limits));
        }
        row.getChildren().add(addButton(context, config, apply, paths, limits));
        return row;
    }

    /**
     * Whether {@code file} may be offered under {@code limits}. Used as the filter the shared tag-grouped
     * menu is built through, so this row's narrowing and the single picker's tag submenus are the same menu
     * with one predicate between them.
     */
    private static boolean isOfferable(ProjectConfig config, Path file, Restrictions limits) {
        return limits.allowed() == null || limits.allowed().contains(ImageTemplateLibrary.pathFor(config, file));
    }

    /** One template chip: thumbnail + name, with a menu to change (from the library) or remove it. */
    private static MenuButton chip(CodeEditorService context, ProjectConfig config,
                                   Consumer<List<String>> apply, List<String> paths, int index,
                                   Restrictions limits) {
        MenuButton button = new MenuButton();
        button.getStyleClass().add("image-template-picker");
        String path = paths.get(index);
        Path file = config.projectPath().resolve(path);
        button.setText(ImageTemplateLibrary.baseName(file));
        button.setGraphic(ImageTemplatePicker.thumbnail(file, 24));

        button.setOnShowing(e -> {
            button.getItems().clear();
            button.getItems().addAll(ImageTemplatePicker.templateMenuItems(config,
                    lib -> isOfferable(config, lib, limits),
                    lib -> apply.accept(replace(paths, index, ImageTemplateLibrary.pathFor(config, lib)))));
            if (!button.getItems().isEmpty()) button.getItems().add(new SeparatorMenuItem());
            MenuItem remove = new MenuItem("Remove");
            // Disabled rather than hidden at the floor: the row still shows removal exists, and the tooltip
            // says why this one can't go — silently omitting it reads as a missing feature.
            if (paths.size() <= limits.minimum()) {
                remove.setDisable(true);
                remove.setText("Remove (this branch needs at least "
                        + limits.minimum() + (limits.minimum() == 1 ? " image)" : " images)"));
            }
            remove.setOnAction(a -> apply.accept(without(paths, index)));
            MenuItem openManager = new MenuItem("Open Resource Manager…");
            openManager.setOnAction(a ->
                    context.getEventBus().publish(new CoreApplicationEvents.OpenResourceManagerEvent()));
            button.getItems().addAll(remove, openManager);
        });
        return button;
    }

    /** The trailing "add another template" button, populated from the library. */
    private static MenuButton addButton(CodeEditorService context, ProjectConfig config,
                                        Consumer<List<String>> apply, List<String> paths,
                                        Restrictions limits) {
        MenuButton add = new MenuButton(paths.isEmpty() ? "Choose images…" : "＋");
        add.getStyleClass().add("image-template-group-add");
        add.setOnShowing(e -> {
            add.getItems().clear();
            add.getItems().addAll(ImageTemplatePicker.templateMenuItems(config,
                    // Within a closed group, adding a template the row already holds says nothing new — so a
                    // narrowed row offers only what's left. An unrestricted row keeps allowing repeats.
                    lib -> isOfferable(config, lib, limits)
                            && !(limits.allowed() != null && paths.contains(ImageTemplateLibrary.pathFor(config, lib))),
                    lib -> apply.accept(append(paths, ImageTemplateLibrary.pathFor(config, lib)))));
            if (!add.getItems().isEmpty()) add.getItems().add(new SeparatorMenuItem());
            MenuItem openManager = new MenuItem("Open Resource Manager…");
            openManager.setOnAction(a ->
                    context.getEventBus().publish(new CoreApplicationEvents.OpenResourceManagerEvent()));
            // Capture is offered only on an unrestricted row. A freshly captured image is by definition not in
            // the enclosing group, so adding it to a narrowed row would build exactly the dead branch the
            // narrowing exists to prevent — the image has to join the group first.
            if (limits.allowed() == null) {
                MenuItem capture = new MenuItem("Capture new…");
                capture.setOnAction(a -> ImageTemplatePicker.captureAndSave(context, add,
                        path -> apply.accept(append(paths, path))));
                add.getItems().add(capture);
            }
            add.getItems().add(openManager);
            if (limits.allowed() != null && add.getItems().size() == 1) {
                MenuItem none = new MenuItem("Every image in this group is already on this branch");
                none.setDisable(true);
                add.getItems().addFirst(none);
            }
        });
        return add;
    }

    /** Reads the template paths from an {@code ImageTemplateGroup.of("…", …)} call, or empty if not one yet. */
    public static List<String> currentPaths(Expression node) {
        List<String> out = new ArrayList<>();
        if (node instanceof MethodInvocation mi
                && "of".equals(mi.getName().getIdentifier())
                && SdkNodes.isCallOn(mi, SdkType.IMAGE_TEMPLATE_GROUP)) {
            for (Object a : mi.arguments()) {
                templatePath(a).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * The path inside {@code new ImageTemplate("…")}, or empty for anything else.
     *
     * <p>"Anything else" is the important half: a varargs slot holding a variable, a field or a call is a
     * reference the chip row cannot represent and must not overwrite, so its caller keeps the ordinary
     * per-argument pickers instead.
     */
    public static java.util.Optional<String> templatePath(Object expression) {
        if (SdkNodes.isInstantiationOf(expression, SdkType.IMAGE_TEMPLATE)
                && expression instanceof ClassInstanceCreation cic
                && !cic.arguments().isEmpty()
                && cic.arguments().get(0) instanceof StringLiteral sl) {
            return java.util.Optional.of(sl.getLiteralValue());
        }
        return java.util.Optional.empty();
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
