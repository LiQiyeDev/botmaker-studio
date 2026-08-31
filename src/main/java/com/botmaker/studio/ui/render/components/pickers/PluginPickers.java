package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.plugin.api.SlotEditor;
import com.botmaker.plugin.api.SlotRun;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.HostSlotContext;
import com.botmaker.studio.plugin.PluginHost;
import javafx.scene.Node;

import java.util.List;

/**
 * The plugin tier of {@link PickerRegistry} — every loaded plugin's {@link SlotEditor}s, offered a slot of a
 * bot's source.
 *
 * <p>The Parameters window has had this since phase 11 ({@code ValueEditors.fromPlugin}); this is the same
 * thing for the canvas, and the pair is what makes the contract's promise true rather than aspirational: one
 * editor, written once against {@code ValueContext}, drawn in both places. The only difference between the two
 * call sites is which context is built — {@code HostValueContext} there, {@link HostSlotContext} here — and
 * that difference is exactly the call site a slot has and a row does not.
 *
 * <p><b>A plugin's editor is third-party code drawn inside our window.</b> One that throws costs the user that
 * slot's widget and nothing more: the next editor is offered the value, and the generic expression pill is
 * still behind them all. The same rule, and the same reasoning, as the Parameters window's.
 */
final class PluginPickers {

    private PluginPickers() {}

    /** The first plugin editor that claims {@code ctx}, built; or {@code null} when none does. */
    static Node nodeFor(PickerContext ctx) {
        return dispatch(ctx, true, null);
    }

    /** As above, for a slot the host knows to be one of a run of sibling arguments. */
    static Node nodeFor(PickerContext ctx, SlotRun run) {
        return dispatch(ctx, true, run);
    }

    /** Whether any plugin editor claims {@code ctx}, without building anything. */
    static boolean hasPicker(PickerContext ctx) {
        return dispatch(ctx, false, null) != null;
    }

    /**
     * One walk of the plugin list, either asking or asking-and-building.
     *
     * <p>Written once because the two must agree: a {@code hasPicker} that said yes where {@code pickerNodeFor}
     * returns null leaves a slot advertised as editable and drawn as a bare pill. When {@code build} is false
     * the answer is a sentinel rather than a node, so a matching editor is never constructed just to be
     * discarded — {@code matches} is documented as cheap, {@code create} is not.
     */
    private static Node dispatch(PickerContext ctx, boolean build, SlotRun run) {
        List<SlotEditor> editors = PluginHost.slotEditors();
        if (editors.isEmpty() || ctx == null) return null;

        HostSlotContext context = new HostSlotContext(ctx.context(), ctx.arg(), ctx.paramType(),
                ctx.className(), ctx.methodName(), ctx.argIndex(),
                HostServices.forProject(ctx.context() == null ? null : ctx.context().getConfig()), run);

        for (SlotEditor editor : editors) {
            try {
                if (!editor.matches(context)) continue;
                if (!build) return MATCHED;
                Node node = editor.create(context);
                if (node != null) return node;
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Plugin slot editor failed for "
                                   + (ctx.paramType() == null ? "?" : ctx.paramType().simpleName()) + ": " + e);
            }
        }
        return null;
    }

    /** Stands for "some editor claims this" in the detection-only walk; never attached to a scene. */
    private static final Node MATCHED = new javafx.scene.Group();
}
