package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
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
     * This method's signature as a draft the dialog can render, or empty for the two shapes that cannot
     * round-trip at all — a varargs or an array parameter, whose {@code …} and {@code []} the AST spells
     * outside the type node.
     *
     * <p>A type outside {@link BotType}'s catalogue is <em>not</em> one of those. It used to be: an activity's
     * {@code Outcome run(int attempts)} returned empty here, so the dialog refused to open and the name and
     * the inputs — the parts it describes perfectly well — could not be edited either, over a return type
     * nobody had asked it to change. It comes back as {@link SignatureType.Kept}, shown as a fixed chip and
     * written back verbatim, which is both more useful and strictly safer than retyping it to something the
     * catalogue does know.
     */
    public static Optional<FunctionDraft> draftOf(MethodDeclaration method) {
        SignatureType returnType = method.getReturnType2() == null
                ? SignatureType.of(BotType.NOTHING)
                : signatureTypeOf(method.getReturnType2().toString());

        List<FunctionDraft.Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < method.parameters().size(); i++) {
            SingleVariableDeclaration declaration = (SingleVariableDeclaration) method.parameters().get(i);
            // Varargs and arrays are the two that genuinely cannot round-trip: the {@code …} and the
            // {@code []} are spelled outside the type node (and `String args[]` puts them on the name), so
            // carrying the type text alone would quietly drop them.
            if (declaration.isVarargs() || declaration.getExtraDimensions() > 0
                    || declaration.getType().toString().endsWith("[]")) {
                return Optional.empty();
            }
            // Stamped with the index it holds here: this draft is what the dialog opens on, and the rows the
            // user then moves, renames or deletes carry the stamp back out — so the write knows which
            // parameter each row *is*, not merely where it ended up.
            parameters.add(new FunctionDraft.Parameter(declaration.getName().getIdentifier(),
                    signatureTypeOf(declaration.getType().toString()), i));
        }
        return Optional.of(new FunctionDraft(method.getName().getIdentifier(), returnType, parameters));
    }

    /** The curated choice for {@code sourceName}, or the text itself when the catalogue has no such type. */
    private static SignatureType signatureTypeOf(String sourceName) {
        return BotType.Choice.fromSourceName(sourceName)
                .<SignatureType>map(SignatureType::of)
                .orElseGet(() -> SignatureType.kept(sourceName));
    }

    /**
     * The part of {@code method}'s signature the dialog cannot represent, phrased for the user — or empty when
     * {@link #draftOf} would succeed.
     *
     * <p>It exists so the Edit button never has to be a dead grey square. "Why can't I edit this one?" is a
     * question with a specific answer — a type, by name — and a disabled control is the one place that answer
     * cannot be read. Naming it on click is the whole difference between a lock and a bug report.
     *
     * <p>It is now down to the two shapes {@link #draftOf} still refuses. A merely uncatalogued type is not
     * one of them: it is carried through instead, so an explanation about it would be an apology for
     * something that did not happen.
     */
    public static Optional<String> unrepresentable(MethodDeclaration method) {
        for (Object parameter : method.parameters()) {
            SingleVariableDeclaration declaration = (SingleVariableDeclaration) parameter;
            String name = declaration.getName().getIdentifier();
            if (declaration.isVarargs()) {
                return Optional.of("the input \"" + name + "\" takes any number of values, which the editor "
                        + "cannot describe");
            }
            if (declaration.getExtraDimensions() > 0 || declaration.getType().toString().endsWith("[]")) {
                return Optional.of("the input \"" + name + "\" is an array, which the editor cannot describe");
            }
        }
        return Optional.empty();
    }
}
