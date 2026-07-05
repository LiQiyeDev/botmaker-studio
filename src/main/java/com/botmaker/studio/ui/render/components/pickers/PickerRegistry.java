package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.ui.render.components.ExecutablePicker;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import com.botmaker.studio.ui.render.components.LaunchOptionPicker;
import com.botmaker.studio.ui.render.components.PointPicker;
import com.botmaker.studio.ui.render.components.RectPicker;
import com.botmaker.studio.ui.render.components.SteamGamePicker;
import javafx.scene.Node;

import java.util.List;

/**
 * The single, ordered registry that maps an argument ({@link PickerContext}) to its specialized
 * bot-first editor. Replaces the former if-else chain in {@code ArgumentEditors.editorFor} and is the
 * one place to add a new special-type editor: implement a {@link SpecialTypePicker} and add it below.
 *
 * <p>The widget factories themselves live in {@code ui.render.components} (pure JavaFX builders); this
 * package owns the <em>detection + dispatch</em>. Order matters — it preserves the original precedence
 * (method-specific pickers first, then type-based, then the enum fallback).
 */
public final class PickerRegistry {

    private PickerRegistry() {}

    private static final List<SpecialTypePicker> PICKERS = List.of(
            // Method-specific (the class is a simple name on the SDK Game facade).
            SpecialTypePicker.of(ctx -> ctx.isGameMethod("launchSteam"),
                    ctx -> SteamGamePicker.create(ctx.context(), ctx.arg())),
            // Game.launch(path, args...): only the first argument is the program path; the trailing
            // varargs are optional command-line launch options, edited as plain text.
            SpecialTypePicker.of(PickerContext::isGameLaunchProgramArg,
                    ctx -> ExecutablePicker.create(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(PickerContext::isGameLaunchOptionArg,
                    ctx -> LaunchOptionPicker.create(ctx.context(), ctx.arg())),

            // Type-based.
            SpecialTypePicker.of(ctx -> ImageTemplatePicker.isImageTemplateType(ctx.paramType()),
                    ctx -> ImageTemplatePicker.create(ctx.context(), ctx.arg())),
            ImageTemplateGroupPicker.asSpecialType(),
            SpecialTypePicker.of(ctx -> ctx.isType("Rect"),
                    ctx -> RectPicker.create(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(ctx -> ctx.isType("Point"),
                    ctx -> PointPicker.create(ctx.context(), ctx.arg())),

            // Enum fallback (re-resolves name-only SDK types through the project/library index).
            EnumPicker.asSpecialType()
    );

    /** The specialized editor node for {@code ctx}, or {@code null} to use the generic pill. */
    public static Node pickerNodeFor(PickerContext ctx) {
        for (SpecialTypePicker picker : PICKERS) {
            if (picker.matches(ctx)) return picker.create(ctx);
        }
        return null;
    }

    /** Whether any picker applies to {@code ctx} (detection without building the node). */
    public static boolean hasPicker(PickerContext ctx) {
        for (SpecialTypePicker picker : PICKERS) {
            if (picker.matches(ctx)) return true;
        }
        return false;
    }
}
