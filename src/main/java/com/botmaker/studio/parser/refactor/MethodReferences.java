package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every call to one function, across the whole project — the thing that had to exist before a signature could
 * be changed safely.
 *
 * <p>Editing a signature rewrote the declaration and nothing else, so renaming {@code clickAt} left four calls
 * to a name that no longer exists, in files the user was not even looking at. There was no helper anywhere
 * that answered "where is this called from"; this is it.
 *
 * <h2>Certain, other, or refuse</h2>
 *
 * <p>Bindings are not available: the point of this is to run on a project mid-edit, and half the files are not
 * on any classpath the editor owns. So every call is judged from source alone and lands in one of three
 * buckets — it <b>is</b> this method, it is <b>demonstrably not</b> (a same-named method of the calling class,
 * a receiver whose declared type is another class), or it <b>cannot be told</b>. The third one is not a
 * skipped call, it is a {@linkplain Result#refusal refusal}: silently migrating three of four call sites is
 * strictly worse than migrating none and saying which file could not be read.
 *
 * <p>A file that does not parse is the same answer for the same reason.
 *
 * <h2>Constructors count as calls</h2>
 *
 * <p>A constructor's calls are {@code new GoHome(…)}, not {@code goHome(…)}, and this used to visit
 * {@link MethodInvocation} only — so a constructor scanned as having <b>zero</b> call sites no matter how many
 * times its class was instantiated. That is worse than not scanning at all: "nothing calls this" is the answer
 * that lets a delete through, so the one guard the constructor controls rely on would have waved every
 * instantiation past. {@link ClassInstanceCreation} is now visited under the same three-way verdict.
 */
public final class MethodReferences {

    private MethodReferences() {}

    /**
     * One call to the method, and the file and parse it was found in.
     *
     * <p>{@code node} is a {@link MethodInvocation} for {@code goHome(…)} and a {@link ClassInstanceCreation}
     * for {@code new GoHome(…)}. The two are one type here because everything downstream —
     * {@link SignatureMigration}'s per-argument plan, {@link CallMigrator}'s rewrite — asks a call the same
     * three questions regardless of which it is: what are your arguments, which property holds them, and is
     * anything consuming what you produce. So they are asked *here*, once, rather than each reader
     * re-discovering that a creation has no name to rename.
     *
     * <p>Since 2026-08 a site may also be a <b>field reference</b> — {@code Key.ENTER} (a
     * {@link org.eclipse.jdt.core.dom.QualifiedName}) or the bare {@link SimpleName} of a statically-imported
     * constant or a {@code case} label. A constant is API too, so an SDK migration has to be able to rename or
     * move one, and the alternative — a second site type with its own copy of "which file, which parse" —
     * would have meant every consumer switching on which kind it held. Instead a field simply answers "no
     * arguments" to the argument questions, which is true.
     */
    public record CallSite(ProjectFile file, CompilationUnit unit, Expression node) {

        /** The arguments as written, in order — empty for a field reference, which has no argument list. */
        public List<?> arguments() {
            return switch (node) {
                case ClassInstanceCreation creation -> creation.arguments();
                case MethodInvocation call -> call.arguments();
                default -> List.of();
            };
        }

        /**
         * Which child list {@link #arguments()} is, for a {@code ListRewrite} over it — null for a field
         * reference. Never reached for one: an empty argument list is only ever rewritten to itself, which
         * {@code CallMigrator} short-circuits before asking.
         */
        public ChildListPropertyDescriptor argumentsProperty() {
            return switch (node) {
                case ClassInstanceCreation ignored -> ClassInstanceCreation.ARGUMENTS_PROPERTY;
                case MethodInvocation ignored -> MethodInvocation.ARGUMENTS_PROPERTY;
                default -> null;
            };
        }

        /**
         * The name that a rename would rewrite, or null when there is none to rewrite. A constructor's name is
         * its class's, which is not this dialog's to change — renaming the class is a different edit.
         */
        public SimpleName nameNode() {
            return switch (node) {
                case MethodInvocation call -> call.getName();
                case QualifiedName qualified -> qualified.getName();
                case SimpleName bare -> bare;
                default -> null;
            };
        }

        /**
         * The name of the type this member is reached <em>through</em> — the {@code Key} of {@code Key.ENTER},
         * the receiver of a static call, the class of a {@code new} — or null when the source names none, as
         * it does for a bare statically-imported constant, a {@code case} label, or a call on {@code this}.
         *
         * <p>Null is what makes a move refusable rather than guessable: with no type written at the site there
         * is nothing to retarget, and {@link CallMigrator} declines the whole migration instead of inventing a
         * qualifier.
         */
        public SimpleName ownerNode() {
            return switch (node) {
                case QualifiedName qualified ->
                        qualified.getQualifier() instanceof SimpleName owner ? owner : null;
                case MethodInvocation call ->
                        call.getExpression() instanceof SimpleName receiver ? receiver : null;
                case ClassInstanceCreation creation ->
                        creation.getType() instanceof SimpleType simple
                                && simple.getName() instanceof SimpleName name ? name : null;
                default -> null;
            };
        }

        public int argumentCount() {
            return arguments().size();
        }

        /** The file's class name — {@code Bot}, {@code GoHome} — which is how the preview names it. */
        public String className() {
            return file.getClassName();
        }

        /** True when the call stands as a line of its own, so nothing consumes what it gives back. */
        public boolean isStatement() {
            return node.getParent() instanceof ExpressionStatement;
        }
    }

    /**
     * What the scan found. {@code calls} is only usable when {@link #isRefusal()} is false — a result with
     * anything in {@code unreadable} or {@code uncertain} is an answer about the project, not a partial list.
     */
    public record Result(List<CallSite> calls, List<String> unreadable, List<String> uncertain) {

        public boolean isRefusal() {
            return !unreadable.isEmpty() || !uncertain.isEmpty();
        }

        /** Why the change cannot be made, naming the file — or null when it can. */
        public String refusal() {
            if (!unreadable.isEmpty()) {
                return "\"" + unreadable.getFirst() + "\" doesn't currently parse, so the calls in it can't be "
                        + "found. Fix that file first, and nothing here will have changed.";
            }
            if (!uncertain.isEmpty()) {
                return "\"" + uncertain.getFirst() + "\" has a call this editor can't be sure about, and "
                        + "changing some call sites but not others would break the project. Nothing has "
                        + "changed.";
            }
            return null;
        }

        /** The files the calls are spread across, in the order they were found. */
        public List<String> fileNames() {
            Set<String> names = new LinkedHashSet<>();
            for (CallSite site : calls) names.add(site.className());
            return List.copyOf(names);
        }
    }

    /**
     * Every call to {@code declaration} in the project.
     *
     * <p>The declaring file is read from the live AST the declaration itself belongs to, never re-parsed: the
     * caller goes on to rewrite that file through the editor's own guarded write, which holds the same tree,
     * and two parses of one file are two sets of nodes that only look alike.
     */
    public static Result find(ProjectState state, MethodDeclaration declaration) {
        List<CallSite> calls = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        if (state == null || declaration == null) return new Result(calls, unreadable, uncertain);

        String name = declaration.getName().getIdentifier();
        int arity = declaration.parameters().size();
        String owner = declaringClassOf(declaration);
        if (owner == null) return new Result(calls, unreadable, uncertain);
        Set<String> projectClasses = new LinkedHashSet<>();
        for (ProjectFile file : state.getAllFiles()) projectClasses.add(file.getClassName());

        CompilationUnit live = (CompilationUnit) declaration.getRoot();
        for (ProjectFile file : state.getAllFiles()) {
            CompilationUnit unit;
            if (file == state.getActiveFile()) {
                unit = live;
            } else {
                String source = file.getContent();
                if (source == null) continue;
                unit = SourceParser.parse(source);
                if (SourceParser.hasSyntaxErrors(unit)) {
                    unreadable.add(file.getClassName());
                    continue;
                }
            }
            scan(file, unit, name, arity, owner, declaration.isConstructor(), projectClasses, calls, uncertain);
        }
        return new Result(calls, unreadable, uncertain);
    }

    // --- one file ------------------------------------------------------------------------------------------

    private enum Verdict { MATCH, OTHER, UNCERTAIN }

    private static void scan(ProjectFile file, CompilationUnit unit, String name, int arity, String owner,
                             boolean constructor, Set<String> projectClasses, List<CallSite> calls,
                             List<String> uncertain) {
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation call) {
                if (constructor) return true;
                if (!call.getName().getIdentifier().equals(name)) return true;
                // A different number of arguments is a different method — Java's own rule, and the one that
                // keeps an overload out of a rename that was never about it.
                if (call.arguments().size() != arity) return true;
                record(call, verdictFor(call, owner, projectClasses));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation creation) {
                if (!constructor) return true;
                if (creation.arguments().size() != arity) return true;
                record(creation, verdictForCreation(creation, owner));
                return true;
            }

            private void record(Expression node, Verdict verdict) {
                switch (verdict) {
                    case MATCH -> calls.add(new CallSite(file, unit, node));
                    case UNCERTAIN -> {
                        if (!uncertain.contains(file.getClassName())) uncertain.add(file.getClassName());
                    }
                    case OTHER -> { }
                }
            }
        });
    }

    /**
     * Whether this {@code new …(…)} of the right arity is an instantiation of the class being edited.
     *
     * <p>Only a bare {@link SimpleType} naming the class is certain. Anything else that <em>ends</em> in the
     * same name — {@code new com.other.GoHome(…)}, {@code new GoHome<String>(…)} — could be that class or
     * another one entirely, and telling them apart needs the imports resolved, which is exactly what this class
     * cannot do. That is a refusal, per the rule above: an answer this cannot be sure of is not a skipped call.
     *
     * <p>{@code new GoHome(…) { … }} — an anonymous subclass — still runs the constructor and still passes it
     * these arguments, so it is a MATCH like any other.
     */
    private static Verdict verdictForCreation(ClassInstanceCreation creation, String owner) {
        Type created = creation.getType();
        if (created instanceof SimpleType simple && simple.getName() instanceof SimpleName name) {
            return owner.equals(name.getIdentifier()) ? Verdict.MATCH : Verdict.OTHER;
        }
        String text = created.toString();
        String last = text.substring(text.lastIndexOf('.') + 1).replaceAll("<.*", "").trim();
        return owner.equals(last) ? Verdict.UNCERTAIN : Verdict.OTHER;
    }

    /** Whether this same-named, same-arity call is the one being edited. */
    private static Verdict verdictFor(MethodInvocation call, String owner, Set<String> projectClasses) {
        Expression scope = call.getExpression();
        if (scope == null || scope instanceof ThisExpression) {
            String enclosing = enclosingClassOf(call);
            if (owner.equals(enclosing)) return Verdict.MATCH;
            // Some other class calls a bare name that happens to match. If that class declares one itself, it
            // is calling its own; otherwise it is inherited or statically imported, and this cannot say which.
            return declaresSameName(call, call.getName().getIdentifier(), call.arguments().size())
                    ? Verdict.OTHER : Verdict.UNCERTAIN;
        }
        if (!(scope instanceof SimpleName receiver)) {
            // `something().clickAt(…)`, `this.helper.clickAt(…)` — the receiver's type is a question only a
            // compiler can answer.
            return Verdict.UNCERTAIN;
        }
        String text = receiver.getIdentifier();
        if (owner.equals(text)) return Verdict.MATCH;
        if (projectClasses.contains(text)) return Verdict.OTHER;

        // A variable receiver: it is our method only if the variable is declared as our class.
        String declared = declaredTypeOf(call, text);
        if (declared == null) return Verdict.UNCERTAIN;
        return owner.equals(declared) ? Verdict.MATCH : Verdict.OTHER;
    }

    /** True when the type {@code node} sits in declares a method of this name and arity itself. */
    private static boolean declaresSameName(ASTNode node, String name, int arity) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (!(n instanceof TypeDeclaration type)) continue;
            for (MethodDeclaration method : type.getMethods()) {
                if (method.getName().getIdentifier().equals(name) && method.parameters().size() == arity) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The type {@code variable} is declared with, as the file writes it — searching outwards from {@code node}
     * through the enclosing method's parameters and locals and then the class's fields. Null when no
     * declaration is in sight, which is a reason to refuse rather than to assume.
     */
    private static String declaredTypeOf(ASTNode node, String variable) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) {
                for (Object parameter : method.parameters()) {
                    SingleVariableDeclaration p = (SingleVariableDeclaration) parameter;
                    if (p.getName().getIdentifier().equals(variable)) return p.getType().toString();
                }
                String local = localTypeOf(method, variable);
                if (local != null) return local;
            }
            if (n instanceof TypeDeclaration type) {
                for (FieldDeclaration field : type.getFields()) {
                    for (Object fragment : field.fragments()) {
                        if (((VariableDeclarationFragment) fragment).getName().getIdentifier().equals(variable)) {
                            return field.getType().toString();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The declared type of a local named {@code variable} anywhere in {@code method}'s body, or null. */
    private static String localTypeOf(MethodDeclaration method, String variable) {
        if (method.getBody() == null) return null;
        String[] found = new String[1];
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationStatement statement) {
                for (Object fragment : statement.fragments()) {
                    if (((VariableDeclarationFragment) fragment).getName().getIdentifier().equals(variable)
                            && found[0] == null) {
                        found[0] = typeText(statement.getType());
                    }
                }
                return true;
            }
        });
        return found[0];
    }

    private static String typeText(Type type) {
        return type == null ? null : type.toString();
    }

    /** The name of the class {@code declaration} belongs to. */
    public static String declaringClassOf(MethodDeclaration declaration) {
        return enclosingClassOf(declaration);
    }

    private static String enclosingClassOf(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof AbstractTypeDeclaration type) return type.getName().getIdentifier();
        }
        return null;
    }

    /** The statement a call stands in, for a caller that has to remove or replace the whole line. */
    public static Statement statementOf(MethodInvocation call) {
        for (ASTNode n = call; n != null; n = n.getParent()) {
            if (n instanceof Statement statement) return statement;
            if (n instanceof BodyDeclaration) return null;
        }
        return null;
    }
}
