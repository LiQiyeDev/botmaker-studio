package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.game.EpicLibraryScanner;
import com.botmaker.studio.game.SteamLibraryScanner;
import com.botmaker.studio.ui.render.components.CaptureSourcePicker;
import com.botmaker.studio.ui.render.components.ClickConfigArgPicker;
import com.botmaker.studio.ui.render.components.ColorArgPicker;
import com.botmaker.studio.ui.render.components.EmulatorArgPicker;
import com.botmaker.studio.ui.render.components.ExecutablePicker;
import com.botmaker.studio.ui.render.components.GameArgPicker;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import com.botmaker.studio.ui.render.components.LaunchOptionPicker;
import com.botmaker.studio.ui.render.components.LaunchTargetArgPicker;
import com.botmaker.studio.ui.render.components.PointPicker;
import com.botmaker.studio.ui.render.components.RectPicker;
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
            // Method-specific (the class is a simple name on the SDK Game facade). The launch id of
            // launchSteam / launchEpic (+ their IfNotRunning variants) gets the cover-art game picker; the
            // program path of launch / launchIfNotRunning / launchAndWait gets the Browse picker; their
            // trailing varargs are optional command-line launch options edited as plain text. (The
            // window-detection CaptureSource args fall through to the type-based CaptureSource picker below.)
            SpecialTypePicker.of(PickerContext::isGameSteamAppIdArg,
                    ctx -> GameArgPicker.create(ctx.context(), ctx.arg(), SteamLibraryScanner::new)),
            SpecialTypePicker.of(PickerContext::isGameEpicAppIdArg,
                    ctx -> GameArgPicker.create(ctx.context(), ctx.arg(), EpicLibraryScanner::new)),
            SpecialTypePicker.of(PickerContext::isEmulatorNameArg,
                    ctx -> EmulatorArgPicker.create(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(PickerContext::isGameLaunchProgramArg,
                    ctx -> ExecutablePicker.create(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(PickerContext::isGameLaunchOptionArg,
                    ctx -> LaunchOptionPicker.create(ctx.context(), ctx.arg())),
            // ClickConfig setter args get a bounded spinner/slider/checkbox instead of a free-typed number.
            SpecialTypePicker.of(PickerContext::isClickConfigArg,
                    ctx -> ClickConfigArgPicker.create(ctx.context(), ctx.arg(), ctx.methodName())),

            // Type-based.
            // LaunchTarget slot → the Steam/Epic/Exe/Emulator target builder (replaces the plain ctor pill).
            SpecialTypePicker.of(ctx -> ctx.isType("LaunchTarget"),
                    ctx -> LaunchTargetArgPicker.create(ctx.context(), ctx.arg())),
            // A java.awt.Color arg → a colour swatch (replaces hand-writing new Color(r, g, b)).
            SpecialTypePicker.of(ctx -> ctx.isType("Color"),
                    ctx -> ColorArgPicker.create(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(ctx -> ImageTemplatePicker.isImageTemplateType(ctx.paramType()),
                    ctx -> ImageTemplatePicker.create(ctx.context(), ctx.arg())),
            ImageTemplateGroupPicker.asSpecialType(),
            // CaptureSource is an SDK interface — never a `new` ctor; always the visual chooser popup.
            SpecialTypePicker.of(ctx -> ctx.isType("CaptureSource") || ctx.isType("Window"),
                    ctx -> CaptureSourcePicker.create(ctx.context(), ctx.arg())),
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
