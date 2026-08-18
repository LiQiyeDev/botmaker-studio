package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The bridge between a method as JDT holds it and a method as the Add Function dialog describes it.
 *
 * <p>Two directions, and both exist because the dialog is now the <em>only</em> way a signature is written:
 * {@link #declaredIn} says what the class already has, so a duplicate is refused and an overload is not; and
 * {@link #draftOf} reads a signature back out so the dialog can be opened on one that exists.
 *
 * <p>Kept out of {@link FunctionDraft} deliberately — the rules there are pure data with no parser in them,
 * which is what makes them testable without a JDT parse. This is where the JDT lives.
 */
public final class MethodSignatures {

    private MethodSignatures() {}

    /**
     * Every signature the class declares, drawn or not. Read from the AST rather than from the rendered
     * blocks: a member the editor hides (an activity's {@code isEnabled()}, see {@code MemberVisibility})
     * has no block, and would otherwise look free.
     */
    public static Set<String> declaredIn(TypeDeclaration typeDecl) {
        Set<String> keys = new LinkedHashSet<>();
        for (MethodDeclaration method : typeDecl.getMethods()) keys.add(keyOf(method));
        return keys;
    }

    /** This method's {@link FunctionDraft#signatureKey() signature key}. */
    public static String keyOf(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            types.add(((SingleVariableDeclaration) parameter).getType().toString());
        }
        return FunctionDraft.signatureKey(method.getName().getIdentifier(), types);
    }

    /**
     * This method's signature as a draft the dialog can render, or empty when it names a type outside
     * {@link BotType}'s catalogue ({@code String[] args}, an SDK class nobody declares a variable of).
     *
     * <p>Empty is the honest answer rather than a guess: the dialog can only offer the curated types, so a
     * signature it cannot represent is one it must not be allowed to rewrite — silently retyping
     * {@code String[]} to {@code String} on the way through would be worse than refusing to open.
     */
    public static Optional<FunctionDraft> draftOf(MethodDeclaration method) {
        BotType.Choice returnType = method.getReturnType2() == null
                ? BotType.Choice.of(BotType.NOTHING)
                : BotType.Choice.fromSourceName(method.getReturnType2().toString()).orElse(null);
        if (returnType == null) return Optional.empty();

        List<FunctionDraft.Parameter> parameters = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            SingleVariableDeclaration declaration = (SingleVariableDeclaration) parameter;
            // A varargs or array parameter is spelled by its element type in the AST, so ask the flag too.
            if (declaration.isVarargs() || declaration.getExtraDimensions() > 0) return Optional.empty();
            BotType.Choice type = BotType.Choice.fromSourceName(declaration.getType().toString()).orElse(null);
            if (type == null) return Optional.empty();
            parameters.add(new FunctionDraft.Parameter(declaration.getName().getIdentifier(), type));
        }
        return Optional.of(new FunctionDraft(method.getName().getIdentifier(), returnType, parameters));
    }
}
