package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.Initializer;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.refactor.MethodReferences.CallSite;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.types.SlotFit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

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
 *   <tr><td>return type changed</td><td>replace the <em>use</em> with a default — but only where the slot
 *       around it refuses the new type. A call standing as a line of its own is consumed by nothing, and a
 *       slot that would still accept the new value is left exactly as written</td></tr>
 *   <tr><td>return type changed</td><td>and, in the body itself, make the trailing {@code return} agree —
 *       see {@link #returnFate}</td></tr>
 * </table>
 *
 * <p>The last one is the maintainer's call and worth stating plainly, because it was decided twice. A call
 * whose value is used <em>and no longer fits where it sits</em> is replaced by a default of the type the
 * surrounding code was expecting — the <em>old</em> return type, because that is what the slot was written for.
 * The variable it was assigned to is never retyped: changing a function's output must not silently change the
 * type of things elsewhere in the file.
 *
 * <p>The fitting question is the part that was missing. Replacing every used call regardless meant that
 * retyping a function called inside {@code print(…)} — a slot that accepts anything — threw away a perfectly
 * good call and wrote a default in its place. See {@link #slotAccepts}.
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

        /**
         * The same, for a type named only as text: write a <em>literal</em> default of it — {@code false},
         * {@code 0}, {@code ""} or {@code null}, and nothing that needs an import.
         *
         * <p>The argument-level twin of {@link CallChange.ValueDefaulted}, and here for the same reason. An
         * SDK upgrade redirecting a call to an overload that takes one more input knows that input's type
         * only as the target jar spells it, and {@link Fresh} would ask the palette — which answers
         * {@code new Point()} for a type it can construct, and {@code Direction.UP} for an enum. Both name
         * something this file may not import and the editor may not have. A literal always compiles.
         */
        record Literal(String typeName) implements ArgumentEdit {}
    }

    /** What happens at one call site. */
    public sealed interface CallChange {

        CallSite site();

        /** The call survives, with this name and these arguments. */
        record Rewrite(CallSite site, String newName, List<ArgumentEdit> arguments) implements CallChange {}

        /**
         * The same, and on another type: the receiver written at the call site becomes {@code newTypeFqn},
         * which is imported with it.
         *
         * <p>Distinct from {@link Rewrite} because it is the one call change that can be <b>refused</b> at a
         * site the scan found: a bare statically-imported constant and a {@code case} label name no type at
         * all, so there is nothing there to retarget and nothing may be invented. A signature edit never
         * produces one — a function does not move to another class — so this, like {@link ValueDefaulted},
         * exists for an SDK upgrade, where a member the release moved is the whole point.
         */
        record Retargeted(CallSite site, String newTypeFqn, String newName, List<ArgumentEdit> arguments)
                implements CallChange {}

        /**
         * The call is consumed as a value and no longer gives back what the code around it expects, so the
         * call goes and {@code expected}'s default stands in its place.
         */
        record ValueReplaced(CallSite site, SignatureType expected) implements CallChange {}

        /**
         * The member is gone, and a <em>literal</em> default of the type it used to give back stands in:
         * {@code false}, {@code 0}, {@code ""} or {@code null}, and nothing else.
         *
         * <p>Deliberately not {@link ValueReplaced}, which asks the palette what a type's default is — and the
         * palette answers {@code new Point()} for a type it can construct. That is right for a signature edit,
         * where the type is one the editor offers and certainly exists, and wrong here, where the type is
         * frequently the one that was just <em>removed</em>: {@code new Key()} names a class the target jar
         * does not have, trading a missing method for a missing class. A literal always compiles.
         *
         * <p>{@code typeFqn} is that type's fully-qualified name <b>in the jar being upgraded to</b>, or null
         * when the target has no such type (or the default is not a {@code null} at all). It is what lets a
         * site with no type of its own — a receiver, an overloaded argument — be written
         * {@code ((ImageTemplate) null)} instead of a bare {@code null} that would not compile or would not
         * pick an overload. See {@link CallMigrator#literalDefaultFor}.
         */
        record ValueDefaulted(CallSite site, String typeName, String typeFqn) implements CallChange {}

        /**
         * The call stood as a line of its own and is removed outright, statement and all.
         *
         * <p>{@link ValueReplaced} cannot cover this and is not a near miss: it replaces the <em>expression</em>,
         * which for a call made purely for its effect yields {@code 0;} — and for a {@code void} member there
         * is no value to write at all. This is the one shape a signature edit never produces; it exists for an
         * SDK upgrade, where a removed member is repaired by making the bot compile rather than by guessing at
         * a replacement.
         */
        record CallDeleted(CallSite site) implements CallChange {}
    }

    /** A parameter the user removed that the body still refers to: it becomes a local of the same name. */
    public record RescuedParameter(String name, SignatureType type) {}

    /**
     * What a changed result type does to the {@code return} at the end of the body — which has to agree with
     * the signature or the file stops compiling.
     *
     * <p>It is decided here, next to the call rules, because both halves of the change are one question and the
     * user is shown the answer before either is written. {@code MethodHandler} then does what this says rather
     * than working it out a second time: a preview that promised the returned value would become {@code false}
     * and a write that left a {@code LocalDate} there would be two different edits wearing one dialog.
     */
    public enum ReturnFate {
        /** The body already agrees: no {@code return} to touch, or the one there still fits. */
        UNCHANGED,
        /** The function now gives nothing back, so the trailing {@code return} goes. */
        REMOVED,
        /** It gives something back and the body said nothing, so a {@code return} is appended. */
        ADDED,
        /** The value it gives back no longer fits the new type and becomes that type's default. */
        REPLACED
    }

    /**
     * The whole migration. {@link #isEmpty()} is the "just save it" case — no call anywhere is touched, the
     * body needs nothing, and no preview is worth showing.
     */
    public record Plan(List<CallChange> calls, List<RescuedParameter> rescued, List<String> changes,
                       ReturnFate returnFate) {

        public boolean isEmpty() {
            // A replaced return value is a body change the user has to have read: a hand-written
            // `return someDate;` becoming `return false;` is exactly the kind of thing that must not happen
            // behind a dialog that never opened, even when nothing in the project calls the function.
            return calls.isEmpty() && rescued.isEmpty() && returnFate != ReturnFate.REPLACED;
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

        int leftAlone = 0;
        for (CallSite site : sites) {
            if (returnChanged && !site.isStatement() && !slotAccepts(site, after.returnType())) {
                calls.add(new CallChange.ValueReplaced(site, before.returnType()));
            } else {
                if (returnChanged && !site.isStatement()) leftAlone++;
                calls.add(new CallChange.Rewrite(site, newName, argumentsFor(before, after, site)));
            }
        }
        List<String> changes = new ArrayList<>(changes(before, after, returnChanged));
        ReturnFate fate = returnFate(declaration, before.returnType(), after.returnType());
        switch (fate) {
            case REPLACED -> changes.add("the value it gives back becomes "
                    + after.returnType().defaultText());
            case REMOVED -> changes.add("the \"return\" at the end of it is removed");
            case ADDED -> changes.add("a \"return\" is added at the end, giving back "
                    + after.returnType().defaultText());
            case UNCHANGED -> { }
        }
        int replaced = (int) calls.stream().filter(CallChange.ValueReplaced.class::isInstance).count();
        if (replaced > 0) changes.add(countOf(replaced) + " using its result " + (replaced == 1 ? "is" : "are")
                + " replaced by a default value");
        if (leftAlone > 0) changes.add(countOf(leftAlone) + " using its result still "
                + (leftAlone == 1 ? "accepts" : "accept") + " the new type and " + (leftAlone == 1 ? "is" : "are")
                + " left as written");
        return new Plan(List.copyOf(calls), rescued(before, after, declaration), List.copyOf(changes), fate);
    }

    // --- the return at the end of the body -----------------------------------------------------------------

    /**
     * What has to happen to {@code method}'s trailing {@code return} for it to agree with a result type
     * changing from {@code before} to {@code after}.
     *
     * <p>{@code MethodHandler} used to answer half of this on its own: it replaced a return value only when the
     * value was still the <em>untouched default</em> of the old type, and left anything hand-written alone. As
     * a rule about ownership that is right — a value the user typed is theirs — and as a rule about compiling
     * it is not: after {@code Date} → {@code Yes/No} the body still hands back a {@code LocalDate}, and the
     * file the user is looking at no longer builds. So a hand-written value that no longer fits is replaced
     * <em>and named in the preview</em>, which is the part that keeps it from being a silent discard. One that
     * still fits stays.
     *
     * <p>"Fits" is judged the way everything else here is judged — from source, without bindings, unknown
     * fitting. A {@code return findTarget();} whose type nothing here can work out is kept.
     */
    public static ReturnFate returnFate(MethodDeclaration method, SignatureType before, SignatureType after) {
        if (method == null || method.isConstructor() || method.getBody() == null) return ReturnFate.UNCHANGED;
        if (typeText(before).equals(typeText(after))) return ReturnFate.UNCHANGED;

        ReturnStatement trailing = trailingReturnOf(method);
        if (after.isVoid()) return trailing == null ? ReturnFate.UNCHANGED : ReturnFate.REMOVED;
        if (trailing == null) return ReturnFate.ADDED;

        Expression value = trailing.getExpression();
        if (value == null) return ReturnFate.REPLACED;
        if (isDefaultOf(value, before)) return ReturnFate.REPLACED;
        return SlotFit.refusal(ResolvedType.named(after.sourceName()), syntacticTypeOf(value)) == null
                ? ReturnFate.UNCHANGED : ReturnFate.REPLACED;
    }

    /**
     * The {@code return} the signature depends on: the last statement of the body, when that is one.
     *
     * <p>Only the last. An early {@code return} inside an {@code if} is a decision the user made about the
     * flow, and no signature change has anything to say about it.
     */
    public static ReturnStatement trailingReturnOf(MethodDeclaration method) {
        if (method == null || method.getBody() == null) return null;
        List<?> statements = method.getBody().statements();
        if (statements.isEmpty()) return null;
        return statements.get(statements.size() - 1) instanceof ReturnStatement trailing ? trailing : null;
    }

    /**
     * Whether {@code value} is a value the editor put there rather than one the user wrote — the old type's own
     * default, or the bare {@code null} that earlier versions seeded every object-typed return with.
     */
    private static boolean isDefaultOf(Expression value, SignatureType type) {
        String written = Initializer.normalised(value.toString());
        return written.equals(Initializer.normalised(type.defaultText())) || written.equals("null");
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
            Expression argument = (Expression) site.arguments().get(origin);
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

    /** {@code 1 call} / {@code 3 calls}, for the preview's own sentences. */
    private static String countOf(int calls) {
        return calls + (calls == 1 ? " call" : " calls");
    }

    // --- does the slot this call sits in still accept it? --------------------------------------------------

    /**
     * Whether whatever consumes this call's value will still take it once the function returns {@code now}.
     *
     * <p>This is the question the rule was missing. It used to replace <em>every</em> call whose value was
     * used, so changing a function's result type blew away {@code print(readCount())} — a slot that takes
     * anything at all, and the maintainer's report that overturned the earlier rule. Now the slot is asked, and
     * only a slot that refuses the new type gets a default written into it.
     *
     * <p>An unreadable slot accepts, deliberately: this runs without bindings, so "I cannot tell what this
     * position expects" must not become "so I rewrote it". The doubt falls the same way it does for arguments —
     * towards keeping what the user wrote. What it <em>can</em> read from source alone is the handful of
     * positions that name their own expectation, which is where the damage was.
     */
    private static boolean slotAccepts(CallSite site, SignatureType now) {
        ResolvedType expected = slotTypeOf(site.node());
        if (expected == null) return true;
        return SlotFit.refusal(expected, ResolvedType.named(now.sourceName())) == null;
    }

    /**
     * What the position around {@code call} expects, read off the source — null when nothing here can say.
     *
     * <p>The conditions are the boolean-or-nothing positions Java itself fixes; a declaration's initialiser and
     * a {@code return} both have their type written a few nodes away. An argument to another call is
     * deliberately <em>not</em> in this list: its parameter type lives in a declaration that may be in another
     * file or in a jar, so it is exactly the "cannot tell" that has to accept.
     */
    private static ResolvedType slotTypeOf(Expression call) {
        StructuralPropertyDescriptor location = call.getLocationInParent();
        ASTNode parent = call.getParent();
        if (location == IfStatement.EXPRESSION_PROPERTY
                || location == WhileStatement.EXPRESSION_PROPERTY
                || location == DoStatement.EXPRESSION_PROPERTY
                || location == ConditionalExpression.EXPRESSION_PROPERTY) {
            return ResolvedType.BOOLEAN;
        }
        if (location == VariableDeclarationFragment.INITIALIZER_PROPERTY
                && parent.getParent() instanceof VariableDeclarationStatement declaration) {
            return ResolvedType.named(declaration.getType().toString());
        }
        if (location == ReturnStatement.EXPRESSION_PROPERTY) {
            MethodDeclaration enclosing = enclosingMethodOf(parent);
            if (enclosing != null && enclosing.getReturnType2() != null) {
                return ResolvedType.named(enclosing.getReturnType2().toString());
            }
        }
        return null;
    }

    private static MethodDeclaration enclosingMethodOf(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method;
        }
        return null;
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
