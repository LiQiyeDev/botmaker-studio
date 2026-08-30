package com.botmaker.studio.ui.render.components;

import com.botmaker.sdk.api.geometry.Point;
import com.botmaker.sdk.api.geometry.Rect;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.events.CoreApplicationEvents.ResourcesChangedEvent;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.capture.ScreenOverlay.CapturedCrop;
import com.botmaker.studio.services.capture.ScreenOverlay.PickStep;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.capture.BatchTemplateNamingDialog;
import com.botmaker.studio.ui.app.capture.BatchTemplateNamingDialog.NamedTemplate;
import com.botmaker.studio.util.MethodSignature;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Pick all on screen…" orchestrator for a {@link com.botmaker.studio.blocks.func.MethodInvocationBlock}:
 * drives a single capture overlay (one frame, one pass) through every on-screen-pickable argument of one
 * call — {@code ImageTemplate}, {@code Rect} and {@code Point} params — instead of one overlay per argument.
 *
 * <p>The overlay engine ({@link ScreenCaptureService#runSession}) and the atomic multi-slot rewrite
 * ({@link CodeEditor#setCallArguments}) already exist; this class only detects the pickable arguments,
 * assembles the {@link PickStep}s (collecting their results into a slot→value map), and applies them in one
 * rewrite when the pass finishes. Applying per-argument would invalidate the other cached argument nodes
 * after the first re-parse, hence the batch.
 *
 * <p>Image arguments finish through the same tail as {@code Capture many}
 * ({@link BatchTemplateNamingDialog}): the crops are held until the overlay is gone, then named or discarded
 * in one dialog. The naming cannot happen <em>during</em> the pass — a modal dialog over a full-screen,
 * application-modal capture overlay is fragile — but it does not have to, and auto-naming (what this used to
 * do) only moved the work to the Resource Manager.
 */
public final class PickAllSession {

    private PickAllSession() {}

    /** True when {@code type} is one this session can pick on screen ({@code ImageTemplate}/{@code Rect}/{@code Point}). */
    private static boolean isPickable(ResolvedType type) {
        return ImageTemplatePicker.isImageTemplateType(type)
                || isType(type, Rect.class) || isType(type, Point.class);
    }

    private static boolean isType(ResolvedType type, Class<?> sdkType) {
        return type != null && type.is(sdkType);
    }

    /** Whether at least one of {@code argCount} arguments (typed via {@code signature}) can be picked on screen. */
    public static boolean hasPickableArgs(MethodSignature signature, int argCount) {
        if (signature == null) return false;
        for (int i = 0; i < argCount; i++) {
            if (isPickable(signature.paramTypeAt(i))) return true;
        }
        return false;
    }

    /** A crop picked for one argument slot, waiting to be named once the overlay is out of the way. */
    private record PendingImage(int argIndex, CapturedCrop crop) {}

    /**
     * Captures the target once and walks every pickable argument of {@code mi} through one overlay, then
     * applies all picks in a single rewrite. No-op if nothing is pickable or the user quits before any pick.
     */
    public static void run(CodeEditorService context, MethodInvocation mi, List<ExpressionBlock> args,
                           MethodSignature signature, Window owner) {
        if (signature == null) return;
        String methodName = mi.getName().getIdentifier();
        Map<Integer, CodeEditor.ArgValue> values = new LinkedHashMap<>();
        List<PendingImage> pending = new ArrayList<>();
        List<PickStep> steps = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            ResolvedType type = signature.paramTypeAt(i);
            if (type == null) continue;
            final int idx = i;
            String label = methodName + " · " + type.simpleName() + " (arg " + (i + 1) + ")";

            if (ImageTemplatePicker.isImageTemplateType(type)) {
                steps.add(new PickStep.ImageStep(label, crop -> pending.add(new PendingImage(idx, crop))));
            } else if (isType(type, Rect.class)) {
                steps.add(new PickStep.RegionStep(label,
                        r -> values.put(idx, new CodeEditor.ArgValue.RectVal(r[0], r[1], r[2], r[3]))));
            } else if (isType(type, Point.class)) {
                steps.add(new PickStep.PointStep(label,
                        p -> values.put(idx, new CodeEditor.ArgValue.PointVal(p[0], p[1]))));
            }
        }

        if (steps.isEmpty()) return;
        ScreenCaptureService.forProject(context).runSession(owner, steps, () -> {
            saveNamedTemplates(context, owner, pending, values);
            if (!values.isEmpty()) context.getCodeEditor().setCallArguments(mi, values);
        });
    }

    /**
     * Names {@code pending} through the batch dialog, saves what the user kept, and records each saved path
     * against its argument slot in {@code values}. A discarded crop simply leaves that slot untouched — the
     * rewrite is a slot→value map, not a full replacement, so the argument keeps whatever it already had.
     */
    private static void saveNamedTemplates(CodeEditorService context, Window owner,
                                           List<PendingImage> pending, Map<Integer, CodeEditor.ArgValue> values) {
        if (pending.isEmpty()) return;
        List<BufferedImage> crops = new ArrayList<>();
        for (PendingImage p : pending) crops.add(p.crop().image());

        BatchTemplateNamingDialog.Batch batch = BatchTemplateNamingDialog.show(owner, context.getConfig(), crops,
                ImageTemplateLibrary.openActivityTag(context.getConfig(), context.getState()));
        List<String> saved = new ArrayList<>();
        for (NamedTemplate t : batch.templates()) {
            PendingImage p = pending.get(t.index());
            try {
                String rel = ImageTemplateLibrary.saveTemplate(context.getConfig(), t.image(), t.name(),
                        p.crop().frameWidth(), p.crop().frameHeight(), p.crop().targetTitle());
                values.put(p.argIndex(), new CodeEditor.ArgValue.ImageVal(rel));
                saved.add(t.name());
            } catch (IOException e) {
                System.err.println("Pick-all: failed to save template for arg "
                        + (p.argIndex() + 1) + ": " + e.getMessage());
            }
        }
        ImageTemplateLibrary.applyTags(context.getConfig(), batch.tagsFor(saved));
        if (!saved.isEmpty()) context.getEventBus().publish(new ResourcesChangedEvent());
    }
}
