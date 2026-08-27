package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.sdk.api.capture.CaptureSource;
import com.botmaker.sdk.api.capture.Window;
import com.botmaker.sdk.api.launch.LaunchTarget;
import com.botmaker.sdk.api.vision.Precision;
import com.botmaker.studio.ui.render.components.CaptureSourcePicker;
import com.botmaker.studio.ui.render.components.ColorArgPicker;
import com.botmaker.studio.ui.render.components.EmulatorArgPicker;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import com.botmaker.studio.ui.render.components.LaunchTargetArgPicker;
import com.botmaker.studio.ui.render.components.PrecisionArgPicker;
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
 *
 * <p><b>Since 2026-08-27 there are three tiers, not one</b> (plugin platform, phase 12): {@link #PICKERS},
 * then every loaded plugin's {@link com.botmaker.plugin.api.SlotEditor}s via {@code PluginPickers}, then
 * {@link #FALLBACKS}. The split is not cosmetic — it is the two ends that cannot move. {@code VariablePicker}
 * has to lead, or a slot holding a project variable is claimed by type and offered a literal instead; and the
 * enum dropdown has to trail, or it claims any enum it can resolve and a plugin never draws an editor for its
 * own type. Everything between those two is negotiable, and the SDK's editors now live there like anyone's.
 */
public final class PickerRegistry {

    private PickerRegistry() {}

    private static final List<SpecialTypePicker> PICKERS = List.of(
            // First of all, and regardless of type: a slot that already holds a project variable stays a
            // variable. Every picker below would otherwise claim it by type and offer to overwrite it with a
            // literal — the Steam picker on Activities.APP_ID, the enum dropdown on Activities.DIRECTION.
            VariablePicker.asSpecialType(),

            // The emulator instance name of Emulators.use / named / launch / stop. The last call-site-matched
            // picker Studio still owns, and it is here rather than in the SDK — where every other one went in
            // phase 12c — for one reason: it opens EmulatorPickerDialog, which reaches Studio's own emulator
            // probe, app cache and phone-pairing dialog. It moves when those do.
            SpecialTypePicker.of(PickerContext::isEmulatorNameArg,
                    ctx -> EmulatorArgPicker.create(ctx.context(), ctx.arg())),

            // The Steam/Epic launch id, the program path, the trailing launch options and the bounded
            // BotSettings setters were all here until 2026-08-28. They are the SDK's now
            // (com.botmaker.sdk.internal.plugin.editors.LaunchEditors / SettingsEditors), reached through
            // PluginPickers below like any other plugin's — they were only ever Studio's because Studio was
            // written first, which is the same reasoning that emptied the contract of the SDK's vocabulary.

            // Type-based.
            // LaunchTarget slot → the Steam/Epic/Exe/Emulator target builder (replaces the plain ctor pill).
            SpecialTypePicker.of(ctx -> ctx.isType(LaunchTarget.class),
                    ctx -> LaunchTargetArgPicker.create(ctx.context(), ctx.arg())),
            // The Pixel facade's strictness argument. It is an SDK value type rather than three bare numbers
            // precisely so this dispatch can be type-based: an arg-index table would have had to know which
            // index it sits at in each of find / findAll / coverage / matchesAt / waitFor, and would silently
            // stop firing the day the SDK gains an overload. The method name is passed on (not matched on) so
            // the editor can hide the knobs that call cannot act on.
            SpecialTypePicker.of(ctx -> ctx.isType(Precision.class),
                    ctx -> PrecisionArgPicker.create(ctx.context(), ctx.arg(), ctx.methodName())),
            SpecialTypePicker.of(ctx -> ImageTemplatePicker.isImageTemplateType(ctx.paramType()),
                    ctx -> ImageTemplatePicker.create(ctx.context(), ctx.arg())),
            ImageTemplateGroupPicker.asSpecialType(),
            // CaptureSource is an SDK interface — never a `new` ctor; always the visual chooser popup.
            SpecialTypePicker.of(ctx -> ctx.isType(CaptureSource.class) || ctx.isType(Window.class),
                    ctx -> CaptureSourcePicker.create(ctx.context(), ctx.arg()))
    );

    /**
     * The pickers consulted <b>after</b> every plugin's, and the reason the list is split in two.
     *
     * <p>These are not editors for anybody's types — they are the host's answers for the JDK
     * ({@code LocalTime}, {@code LocalDate}, {@code DayOfWeek}, {@code Month}, {@code java.awt.Color}) and the
     * generic enum dropdown, which claims <em>any</em> enum it can resolve. A plugin that ships an editor for
     * its own enum would never get to draw it if that fallback ran first, so the fallbacks run last and the
     * merge order is: the host's own specials, then plugins', then these.
     */
    private static final List<SpecialTypePicker> FALLBACKS = List.of(
            // The clock and calendar of the Time facade's daily-reset predicates. All three are type-based:
            // the facade's bare-hour isBetween(int, int) overloads — the only Time arguments that ever needed
            // a (method, argIndex) hook — are gone, and PickerContext no longer carries one.
            SpecialTypePicker.of(ctx -> ctx.isType("LocalTime"),
                    ctx -> TimeArgPicker.localTime(ctx.context(), ctx.arg())),
            // A calendar day, on the same reasoning and for the same reason it was missing: java.time is not
            // in the project type index, so without an entry here a date is a raw LocalDate.parse("…") pill.
            SpecialTypePicker.of(ctx -> ctx.isType("LocalDate"),
                    ctx -> DateArgPicker.create(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(ctx -> ctx.isType("DayOfWeek"),
                    ctx -> TimeArgPicker.dayOfWeek(ctx.context(), ctx.arg())),
            SpecialTypePicker.of(ctx -> ctx.isType("Month"),
                    ctx -> TimeArgPicker.month(ctx.context(), ctx.arg())),
            // A java.awt.Color arg → a colour swatch (replaces hand-writing new Color(r, g, b)).
            SpecialTypePicker.of(ctx -> ctx.isType("Color"),
                    ctx -> ColorArgPicker.create(ctx.context(), ctx.arg())),
            // Rect, Point and Size are gone from this list: they are the SDK's types, so they are the SDK's
            // editors now (com.botmaker.sdk.internal.plugin.editors.GeometryEditors), reached through
            // PluginPickers below exactly as any other plugin's would be.

            // Enum fallback (re-resolves name-only SDK types through the project/library index).
            EnumPicker.asSpecialType()
    );

    /**
     * The specialized editor node for {@code ctx}, or {@code null} to use the generic pill.
     *
     * <p>Three tiers, in this order and for these reasons. {@link #PICKERS} first, so a slot holding a project
     * variable stays a variable — {@code VariablePicker} leads it, and every editor below would otherwise
     * claim that slot by type and offer to overwrite it with a literal. Then plugins, which is where the SDK's
     * own editors now are. Then {@link #FALLBACKS}, because the enum dropdown claims any enum it can resolve
     * and would shut a plugin out of its own type.
     */
    public static Node pickerNodeFor(PickerContext ctx) {
        for (SpecialTypePicker picker : PICKERS) {
            if (picker.matches(ctx)) return picker.create(ctx);
        }
        Node fromPlugin = PluginPickers.nodeFor(ctx);
        if (fromPlugin != null) return fromPlugin;
        for (SpecialTypePicker picker : FALLBACKS) {
            if (picker.matches(ctx)) return picker.create(ctx);
        }
        return null;
    }

    /** Whether any picker applies to {@code ctx} (detection without building the node). */
    public static boolean hasPicker(PickerContext ctx) {
        for (SpecialTypePicker picker : PICKERS) {
            if (picker.matches(ctx)) return true;
        }
        if (PluginPickers.hasPicker(ctx)) return true;
        for (SpecialTypePicker picker : FALLBACKS) {
            if (picker.matches(ctx)) return true;
        }
        return false;
    }
}
