package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.SlotRun;
import com.botmaker.studio.services.CodeEditorService;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Studio's side of {@link SlotRun}: a tail of one call's arguments, edited as one list.
 *
 * <p>The host's half of the bargain is the part a plugin cannot see. That these arguments are one list comes
 * from the resolved signature (a varargs parameter) or from the shape of the guard they sit in; how few of
 * them the surrounding code still compiles with is the block's own rule; and which of them the code around
 * the run still permits is a walk out to an enclosing call. None of that is knowable from the value.
 *
 * <p><b>Everything crossing is Java source.</b> {@link #elements()} are the arguments as they stand and
 * {@link #replace} takes expressions to put back, so this class never learns what a picture is — which is
 * exactly what the old {@code ImageTemplateGroupPicker} could not avoid, since it decoded every argument to a
 * template path and rebuilt {@code new ImageTemplate(…)} on the way out. Studio spelling the SDK's API is the
 * thing this whole platform is removing, and this is where that stopped for the multi-picture row.
 *
 * <p><b>The call is resolved on every use, never captured</b> — the same rule {@link HostSlotContext} follows
 * for its slot, and for the same reason: an editor's popup outlives the re-parse its own first edit caused,
 * and a node from the old syntax tree is what {@code ASTRewrite} refuses.
 */
public final class HostSlotRun implements SlotRun {

    private final CodeEditorService context;
    private final Supplier<MethodInvocation> call;
    private final int fromIndex;
    private final int minimum;
    private final Supplier<List<String>> allowed;

    /**
     * @param call      the call whose arguments make up the run, resolved when asked
     * @param fromIndex the first argument that belongs to the run — the varargs boundary, or 0
     * @param minimum   how few elements the surrounding code still compiles with
     * @param allowed   the only element sources the surrounding code still accepts, or a supplier of
     *                  {@code null} for no narrowing
     */
    public HostSlotRun(CodeEditorService context, Supplier<MethodInvocation> call, int fromIndex,
                       int minimum, Supplier<List<String>> allowed) {
        this.context = context;
        this.call = call;
        this.fromIndex = Math.max(0, fromIndex);
        this.minimum = Math.max(0, minimum);
        this.allowed = allowed;
    }

    /** A run with no floor and no narrowing — a varargs tail anywhere the code has no further opinion. */
    public static HostSlotRun of(CodeEditorService context, Supplier<MethodInvocation> call, int fromIndex) {
        return new HostSlotRun(context, call, fromIndex, 0, () -> null);
    }

    @Override
    public List<String> elements() {
        MethodInvocation node = call == null ? null : call.get();
        if (node == null) return List.of();
        List<String> out = new ArrayList<>();
        List<?> arguments = node.arguments();
        for (int i = fromIndex; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof Expression argument) out.add(argument.toString());
        }
        return out;
    }

    @Override
    public int minimum() {
        return minimum;
    }

    @Override
    public List<String> allowed() {
        return allowed == null ? null : allowed.get();
    }

    @Override
    public void replace(List<String> javaExpressions, String... importsNeeded) {
        MethodInvocation node = call == null ? null : call.get();
        if (node == null || javaExpressions == null) return;
        // Refused rather than clamped: the contract says a short list leaves the source alone, and an editor
        // that ignores the floor must not be able to produce code that will not compile.
        if (javaExpressions.size() < minimum) return;
        context.getCodeEditor().setTrailingArguments(node, fromIndex, javaExpressions, importsNeeded);
    }
}
