package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.PickStep;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.MethodInvocation;

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
 */
public final class PickAllSession {

    private PickAllSession() {}

    /** True when {@code type} is one this session can pick on screen ({@code ImageTemplate}/{@code Rect}/{@code Point}). */
    private static boolean isPickable(ResolvedType type) {
        return ImageTemplatePicker.isImageTemplateType(type) || isType(type, "Rect") || isType(type, "Point");
    }

    /** Matches {@code type} against a simple type name (by simple or qualified name); mirrors {@code PickerContext.isType}. */
    private static boolean isType(ResolvedType type, String simpleName) {
        return type != null
                && (type.simpleName().equals(simpleName) || type.qualifiedName().endsWith("." + simpleName));
    }

    /** Whether at least one of {@code argCount} arguments (typed via {@code signature}) can be picked on screen. */
    public static boolean hasPickableArgs(MethodSignature signature, int argCount) {
        if (signature == null) return false;
        for (int i = 0; i < argCount; i++) {
            if (isPickable(signature.paramTypeAt(i))) return true;
        }
        return false;
    }

    /**
     * Captures the target once and walks every pickable argument of {@code mi} through one overlay, then
     * applies all picks in a single rewrite. No-op if nothing is pickable or the user quits before any pick.
     */
    public static void run(CodeEditorService context, MethodInvocation mi, List<ExpressionBlock> args,
                           MethodSignature signature, Window owner) {
        if (signature == null) return;
        String methodName = mi.getName().getIdentifier();
        Map<Integer, CodeEditor.ArgValue> values = new LinkedHashMap<>();
        List<PickStep> steps = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            ResolvedType type = signature.paramTypeAt(i);
            if (type == null) continue;
            final int idx = i;
            String label = methodName + " · " + type.simpleName() + " (arg " + (i + 1) + ")";

            if (ImageTemplatePicker.isImageTemplateType(type)) {
                steps.add(new PickStep.ImageStep(label, img -> {
                    try {
                        String rel = ImageTemplatePicker.saveTemplate(context.getConfig(), img, autoName(methodName, idx));
                        values.put(idx, new CodeEditor.ArgValue.ImageVal(rel));
                    } catch (IOException e) {
                        System.err.println("Pick-all: failed to save template for arg " + idx + ": " + e.getMessage());
                    }
                }));
            } else if (isType(type, "Rect")) {
                steps.add(new PickStep.RegionStep(label,
                        r -> values.put(idx, new CodeEditor.ArgValue.RectVal(r[0], r[1], r[2], r[3]))));
            } else if (isType(type, "Point")) {
                steps.add(new PickStep.PointStep(label,
                        p -> values.put(idx, new CodeEditor.ArgValue.PointVal(p[0], p[1]))));
            }
        }

        if (steps.isEmpty()) return;
        ScreenCaptureService.forProject(context).runSession(owner, steps, () -> {
            if (!values.isEmpty()) context.getCodeEditor().setCallArguments(mi, values);
        });
    }

    /**
     * A unique, filesystem-safe template name for a batch-captured image (the "Pick all" flow does not
     * prompt per image — a modal name dialog over the full-screen capture overlay is fragile — so it
     * auto-names; the author can rename later in the Resource Manager).
     */
    private static String autoName(String methodName, int argIndex) {
        String safe = methodName.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe + "_arg" + (argIndex + 1) + "_" + Long.toString(System.currentTimeMillis(), 36);
    }
}
