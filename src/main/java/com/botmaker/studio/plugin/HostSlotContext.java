package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.SlotContext;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.TypeRef;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.util.List;

/**
 * A {@link SlotContext} over one slot of a bot's Java source — the second of the two places Studio edits a
 * value, and the one a plugin's editor could not reach until now.
 *
 * <p>{@link HostValueContext} is the other: a row of the Parameters window, with no call site behind it. The
 * pair is the whole of the host's side of the contract, and the reason there are two is that a slot has three
 * things a row does not — Java text rather than stored strings, an enclosing call, and imports.
 *
 * <p><b>The slot is asked, never captured.</b> {@link ValueSlot} resolves its expression on every call, so an
 * editor whose popup outlives the re-parse its own edit caused writes into the <em>new</em> node. Handing a
 * stale one to {@code ASTRewrite} throws {@code Node is not inside the AST}; this makes that unreachable
 * rather than guarded against, and it is why this class holds the slot rather than the expression.
 *
 * <p><b>{@link #value()} and {@link #set} are {@link #currentSource()} and {@link #replaceWith} wearing the
 * supertype's clothes.</b> The contract says so explicitly, and it is what lets an editor written for the
 * Parameters window work here without knowing it: a one-element list holding the slot's Java expression.
 */
public final class HostSlotContext implements SlotContext {

    private final CodeEditorService context;
    private final ValueSlot slot;
    private final ResolvedType paramType;
    private final String className;
    private final String methodName;
    private final int argIndex;
    private final StudioServices services;

    public HostSlotContext(CodeEditorService context, ValueSlot slot, ResolvedType paramType,
                           String className, String methodName, int argIndex, StudioServices services) {
        this.context = context;
        this.slot = slot == null ? ValueSlot.empty() : slot;
        this.paramType = paramType;
        this.className = className;
        this.methodName = methodName;
        this.argIndex = argIndex;
        this.services = services;
    }

    @Override
    public TypeRef type() {
        return typeRef(paramType);
    }

    @Override
    public String currentSource() {
        return slot.source();
    }

    @Override
    public String enclosingClass() {
        return className;
    }

    @Override
    public String enclosingMethod() {
        return methodName;
    }

    @Override
    public int argIndex() {
        return argIndex;
    }

    @Override
    public String enclosingSource() {
        MethodInvocation call = enclosingCall();
        return call == null ? null : call.toString();
    }

    @Override
    public void replaceEnclosingCall(String javaExpression, String... importsNeeded) {
        MethodInvocation call = enclosingCall();
        if (call == null || javaExpression == null || javaExpression.isBlank()) return;
        rewrite(call, javaExpression, importsNeeded);
    }

    /**
     * The call this slot is an argument of, resolved now rather than remembered — the same rule the slot
     * itself follows, and for the same reason: an editor's popup may outlive the re-parse its own first edit
     * caused, and a node from the old AST is what {@code ASTRewrite} refuses.
     *
     * <p>Only a {@link MethodInvocation}, and only when the slot is genuinely one of its arguments: a slot
     * that is the <em>receiver</em> of a call ({@code x.foo()}) has that call as its parent too, and
     * replacing it would delete the call on the strength of editing the thing it was called on.
     */
    private MethodInvocation enclosingCall() {
        return slot.node() != null && slot.node().getParent() instanceof MethodInvocation call
               && call.arguments().contains(slot.node()) ? call : null;
    }

    @Override
    public void replaceWith(String javaExpression, String... importsNeeded) {
        if (javaExpression == null || javaExpression.isBlank() || slot.node() == null) return;
        rewrite(slot.node(), javaExpression, importsNeeded);
    }

    /** One rewrite, whether the target is the slot or the call around it. */
    private void rewrite(Expression target, String javaExpression, String... importsNeeded) {
        if (importsNeeded == null || importsNeeded.length == 0) {
            context.getCodeEditor().replaceWithRawExpression(target, javaExpression);
            return;
        }
        // One import per call is what the editor's API takes; the expression is written fully-qualified by
        // every plugin anyway (the contract says that is always safe), so the imports are a tidiness pass and
        // applying them one at a time costs nothing but a loop.
        context.getCodeEditor().replaceWithRawExpression(target, javaExpression, importsNeeded[0]);
        for (int i = 1; i < importsNeeded.length; i++) {
            context.getCodeEditor().replaceWithRawExpression(target, javaExpression, importsNeeded[i]);
        }
    }

    @Override
    public List<String> value() {
        return List.of(currentSource());
    }

    @Override
    public void set(List<String> value) {
        replaceWith(value == null || value.isEmpty() ? "" : value.getFirst());
    }

    @Override
    public StudioServices services() {
        return services;
    }

    /**
     * A {@link ResolvedType} as the contract's names-only view of it.
     *
     * <p>Never a {@code Class}: the host resolves a type out of the <em>bot's</em> classpath, which may hold a
     * different version of it or not hold it at all. An unresolved type still answers a simple name, and an
     * editor keying off that still fires — refusing to edit a value because the rest of the file does not
     * compile is the worse failure.
     */
    public static TypeRef typeRef(ResolvedType type) {
        String simple = type == null ? "" : nullToEmpty(type.simpleName());
        String qualified = type == null ? "" : nullToEmpty(type.qualifiedName());
        return new TypeRef() {
            @Override public String simpleName() { return simple; }

            @Override public String qualifiedName() { return qualified; }
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
