package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.refactor.MethodReferences.CallSite;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.types.SlotFit;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a signature change does to each call — worked out completely before anything is written.
 *
 * <p>Nothing here rewrites: it turns "the signature was {@code boolean clickAt(Point, int)} and is about to be
 * {@code void tapAt(Rect)}" into a list of per-call edits and a handful of sentences describing them. That
 * split is the point. The user is shown the sentences and gets to say no, and the edits are applied to several
 * files at once — both of which need the whole answer in hand before the first character changes.
 *
 * <h2>The rules</h2>
 *
 * <table>
 *   <tr><td>name</td><td>rename the invocation</td></tr>
 *   <tr><td>parameter added</td><td>append that type's default</td></tr>
 *   <tr><td>parameter removed</td><td>drop that argument</td></tr>
 *   <tr><td>parameters reordered</td><td>permute the arguments by origin</td></tr>
 *   <tr><td>parameter retyped</td><td>keep the argument if it still fits, else the new type's default</td></tr>
 *   <tr><td>return type changed</td><td>replace the <em>use</em> with a default, unless the call is a line of
 *       its own — then nothing consumed it and there is nothing to fix</td></tr>
 * </table>
 *
 * <p>The last one is the maintainer's call and worth stating plainly: a call whose value is used is replaced by
 * a default of the type the surrounding code was expecting — the <em>old</em> return type, because that is what
 * the slot was written for. The variable it was assigned to is never retyped. Changing a function's output must
 * not silently change the type of things elsewhere in the file.
 *
 * <p>"Does it still fit" is judged from source alone ({@link #syntacticTypeOf}), because a project mid-edit has
 * no bindings. Unknown fits — see {@link SlotFit#refusal} — so the doubt falls towards keeping what the user
 * wrote rather than replacing it with a zero.
 */
public final class SignatureMigration {

    private SignatureMigration() {}

    /** Where one argument of the new call comes from. */
    public sealed interface ArgumentEdit {

        /** The argument at index {@code from} of the call as it stands. */
        record Keep(int from) implements ArgumentEdit {}

        /** A value that does not exist at this call yet: write {@code type}'s default. */
        record Fresh(SignatureType type) implements ArgumentEdit {}
    }

    /** What happens at one call site. */
    public sealed interface CallChange {

        CallSite site();

        /** The call survives, with this name and these arguments. */
        record Rewrite(CallSite site, String newName, List<ArgumentEdit> arguments) implements CallChange {}

        /**
         * The call is consumed as a value and no longer gives back what the code around it expects, so the
         * call goes and {@code expected}'s default stands in its place.
         */
        record ValueReplaced(CallSite site, SignatureType expected) implements CallChange {}
    }

    /** A parameter the user removed that the body still refers to: it becomes a local of the same name. */
    public record RescuedParameter(String name, SignatureType type) {}

    /**
     * The whole migration. {@link #isEmpty()} is the "just save it" case — no call anywhere is touched and no
     * preview is worth showing.
     */
    public record Plan(List<CallChange> calls, List<RescuedParameter> rescued, List<String> changes) {

        public boolean isEmpty() {
            return calls.isEmpty() && rescued.isEmpty();
        }

        /** One line per file: {@code Bot — 3 calls}, in the order the files were scanned. */
        public List<String> perFileLines() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (CallChange change : calls) {
                counts.merge(change.site().className(), 1, Integer::sum);
            }
            List<String> lines = new ArrayList<>();
            counts.forEach((className, count) ->
                    lines.add(className + " — " + count + (count == 1 ? " call" : " calls")));
            return lines;
        }
    }

    /**
     * The plan for changing {@code before} into {@code after}.
     *
     * @param declaration the method being edited, read only to see whether a removed parameter is still used
     *                    in its body; may be null when that question does not arise
     * @param sites       every call found by {@link MethodReferences}, already known to be certain
     */
    public static Plan of(FunctionDraft before, FunctionDraft after, MethodDeclaration declaration,
                          List<CallSite> sites) {
        List<CallChange> calls = new ArrayList<>();
        boolean returnChanged = !typeText(before.returnType()).equals(typeText(after.returnType()));
        String newName = after.name().trim();

        for (CallSite site : sites) {
            if (returnChanged && !site.isStatement()) {
                calls.add(new CallChange.ValueReplaced(site, before.returnType()));
            } else {
                calls.add(new CallChange.Rewrite(site, newName, argumentsFor(before, after, site)));
            }
        }
        return new Plan(List.copyOf(calls), rescued(before, after, declaration),
                changes(before, after, returnChanged));
    }

    /** The new argument list at one call, in the order the new parameters are in. */
    private static List<ArgumentEdit> argumentsFor(FunctionDraft before, FunctionDraft after, CallSite site) {
        List<ArgumentEdit> edits = new ArrayList<>();
        for (FunctionDraft.Parameter target : after.parameters()) {
            int origin = target.origin();
            if (target.isNew() || origin >= before.parameters().size() || origin >= site.argumentCount()) {
                edits.add(new ArgumentEdit.Fresh(target.type()));
                continue;
            }
            FunctionDraft.Parameter was = before.parameters().get(origin);
            if (target.type().isKept() || typeText(was.type()).equals(typeText(target.type()))) {
                edits.add(new ArgumentEdit.Keep(origin));
                continue;
            }
            Expression argument = (Expression) site.call().arguments().get(origin);
            edits.add(stillFits(target.type(), argument)
                    ? new ArgumentEdit.Keep(origin) : new ArgumentEdit.Fresh(target.type()));
        }
        return List.copyOf(edits);
    }

    /** Removed parameters the body still refers to — each one owed a local so the method keeps compiling. */
    private static List<RescuedParameter> rescued(FunctionDraft before, FunctionDraft after,
                                                  MethodDeclaration declaration) {
        if (declaration == null) return List.of();
        List<RescuedParameter> rescued = new ArrayList<>();
        for (int i = 0; i < before.parameters().size(); i++) {
            if (isKept(after, i) || i >= declaration.parameters().size()) continue;
            SingleVariableDeclaration removed =
                    (SingleVariableDeclaration) declaration.parameters().get(i);
            if (AstRewriteHelper.referencesWithin(declaration, removed.getName()).isEmpty()) continue;
            rescued.add(new RescuedParameter(removed.getName().getIdentifier(),
                    before.parameters().get(i).type()));
        }
        return List.copyOf(rescued);
    }

    private static boolean isKept(FunctionDraft after, int origin) {
        return after.parameters().stream().anyMatch(p -> !p.isNew() && p.origin() == origin);
    }

    /** The change itself, in words — what the preview says above the file list. */
    private static List<String> changes(FunctionDraft before, FunctionDraft after, boolean returnChanged) {
        List<String> lines = new ArrayList<>();
        if (!before.name().trim().equals(after.name().trim())) {
            lines.add("renamed to \"" + after.name().trim() + "\"");
        }
        if (returnChanged) {
            lines.add("gives back " + describe(after.returnType()) + " instead of "
                    + describe(before.returnType()));
        }
        for (int i = 0; i < before.parameters().size(); i++) {
            if (!isKept(after, i)) {
                lines.add("input \"" + before.parameters().get(i).name() + "\" removed");
            }
        }
        for (FunctionDraft.Parameter target : after.parameters()) {
            if (target.isNew() || target.origin() >= before.parameters().size()) {
                lines.add("new input \"" + target.name().trim() + "\" (" + typeText(target.type())
                        + "), filled in with its default at every call");
                continue;
            }
            FunctionDraft.Parameter was = before.parameters().get(target.origin());
            if (!target.type().isKept() && !typeText(was.type()).equals(typeText(target.type()))) {
                lines.add("input \"" + was.name() + "\" is now " + typeText(target.type()));
            }
        }
        if (reordered(before, after)) lines.add("inputs reordered");
        return List.copyOf(lines);
    }

    private static boolean reordered(FunctionDraft before, FunctionDraft after) {
        int last = -1;
        for (FunctionDraft.Parameter target : after.parameters()) {
            if (target.isNew() || target.origin() >= before.parameters().size()) continue;
            if (target.origin() <= last) return true;
            last = target.origin();
        }
        return false;
    }

    private static String describe(SignatureType type) {
        return type.isVoid() ? "nothing" : typeText(type);
    }

    private static String typeText(SignatureType type) {
        return type.sourceName();
    }

    // --- does this argument still fit? ---------------------------------------------------------------------

    /** True when {@code argument} may stay where it is now that the parameter is a {@code type}. */
    private static boolean stillFits(SignatureType type, Expression argument) {
        return SlotFit.refusal(ResolvedType.named(type.sourceName()), syntacticTypeOf(argument)) == null;
    }

    /**
     * What an expression is worth, read off the source alone — {@link ResolvedType#UNKNOWN} for anything that
     * needs a compiler.
     *
     * <p>{@code ProjectAnalyzer.valueTypeOf} answers the same question far better, and cannot be used here: it
     * starts from a binding, and the files this walks are parsed without one. So this covers what a literal
     * says about itself, which is exactly the case that matters — {@code clickAt(3)} where {@code 3} has just
     * become a {@code Point} is the argument nobody wants silently kept.
     */
    private static ResolvedType syntacticTypeOf(Expression argument) {
        return switch (argument) {
            case StringLiteral ignored -> ResolvedType.named("java.lang.String");
            case BooleanLiteral ignored -> ResolvedType.BOOLEAN;
            case CharacterLiteral ignored -> ResolvedType.named("char");
            case NumberLiteral literal -> numberType(literal);
            case ClassInstanceCreation creation -> ResolvedType.named(creation.getType().toString());
            case CastExpression cast -> ResolvedType.named(cast.getType().toString());
            case ParenthesizedExpression parens -> syntacticTypeOf(parens.getExpression());
            case SimpleName ignored -> ResolvedType.UNKNOWN;
            case null, default -> ResolvedType.UNKNOWN;
        };
    }

    /** {@code 1} is an int, {@code 1.5} a double — the suffix decides the rest. */
    private static ResolvedType numberType(NumberLiteral literal) {
        String token = literal.getToken().toLowerCase(Locale.ROOT);
        if (token.endsWith("l")) return ResolvedType.named("long");
        if (token.endsWith("f")) return ResolvedType.named("float");
        if (token.endsWith("d") || token.contains(".") || token.contains("e")) {
            return ResolvedType.named("double");
        }
        return ResolvedType.named("int");
    }
}
