package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.refactor.MethodReferences.CallSite;
import com.botmaker.studio.project.ProjectFile;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every place one file reaches into the SDK — the single scan behind both halves of an SDK upgrade.
 *
 * <p><b>One scanner, two readers, and that is the point.</b> {@code services/SdkUpgradeService} asks it what
 * the bot calls so the report can say what breaks; {@link SdkMigrationRunner} asks it the same question so the
 * rewrite knows what to change. Two scans would eventually disagree, and the shape of that disagreement is the
 * worst one available: a dialog that lists three call sites and a button that rewrites two of them.
 *
 * <p>So a {@link Reference} carries a {@link CallSite} — file, parse and <em>node</em> — rather than a line
 * number. The report throws the node away and keeps the line; the runner keeps the node. Neither re-derives
 * what the other saw.
 *
 * <h2>No bindings, so five shapes and one refusal</h2>
 *
 * <p>Judged from source alone, for the reason {@link MethodReferences} is: a project mid-edit is on no
 * classpath Studio owns. A reference is attributed to the SDK when the source itself names the type —
 * {@code Mouse.click(…)}, {@code new ImageTemplate(…)}, {@code Key.ENTER} — which is how every generated block
 * writes them. A call through a variable is not attributed, and so is neither reported nor rewritten.
 *
 * <p>Two shapes name no type at all and are resolved from elsewhere in the file: a bare name reaching an
 * {@code import static …Key.ENTER}, and a {@code case} label, whose enum type lives on the switch expression.
 * The label is the one that can be genuinely ambiguous — {@code case UP ->} where two SDK enums declare
 * {@code UP} — and that is a {@link Scan#problems() problem}, never a guess: naming the wrong enum reports a
 * break in a class the bot never touched, and would rewrite it there too.
 */
public final class SdkReferences {

    private SdkReferences() {}

    /** {@link Reference#argCount()} for a field read: not "zero arguments", but "no argument list at all". */
    public static final int FIELD_READ = -1;

    /** A constructor has no name of its own; this is how {@code @ReplacedBy}/{@code @Replaces} spell one. */
    public static final String CTOR = "<init>";

    /**
     * One reference to something that looks like an SDK member: a method call, a constructor, or a field read.
     *
     * <p>{@code type} is the simple name as the source writes it, {@code member} the method or field name (or
     * {@link #CTOR}), and {@code argCount} is {@link #FIELD_READ} for a field — which is how a constant is told
     * apart from a no-argument call, a distinction that matters both ways round, since turning one into the
     * other is itself a break.
     */
    public record Reference(String type, String member, int argCount, CallSite site) {

        public boolean isField() {
            return argCount == FIELD_READ;
        }

        /** {@code Key#ENTER} — the same spelling the SDK's pointer annotations use, minus the package. */
        public String key() {
            return type + "#" + member;
        }
    }

    /**
     * What one file yielded. {@code problems} is what could not be <em>determined</em> — an ambiguous
     * {@code case} label — and is deliberately not an empty-handed skip: "nothing breaks" must never be the
     * answer given by a scan that could not read half the file.
     */
    public record Scan(List<Reference> references, List<String> problems) {}

    /**
     * Scans {@code unit} for references to any of {@code sdkTypes}.
     *
     * @param pathLabel how a problem line should name this file — the report wants a project-relative path,
     *                  the runner the class name
     * @param sdkTypes    every SDK class simple name worth recognising
     * @param fieldOwners constant name → the SDK types declaring it, which is the only way an unqualified
     *                    {@code case} label can be attributed at all
     */
    public static Scan in(ProjectFile file, CompilationUnit unit, String pathLabel,
                          Set<String> sdkTypes, Map<String, List<String>> fieldOwners) {
        List<Reference> references = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        // Both are per-file: a static import and a shadowing local are properties of one compilation unit, and
        // asking them of the project as a whole would answer the wrong question.
        Map<String, String> staticImports = staticFieldImports(unit, fieldOwners);
        Set<String> shadowed = declaredNames(unit);

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (node.getExpression() instanceof SimpleName receiver
                        && sdkTypes.contains(receiver.getIdentifier())) {
                    references.add(new Reference(receiver.getIdentifier(), node.getName().getIdentifier(),
                            node.arguments().size(), new CallSite(file, unit, node)));
                }
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                String type = simpleTypeName(node.getType());
                if (type != null && sdkTypes.contains(type)) {
                    references.add(new Reference(type, CTOR, node.arguments().size(),
                            new CallSite(file, unit, node)));
                }
                return true;
            }

            /** {@code Key.ENTER} — the ordinary shape, and the only certain one. */
            @Override
            public boolean visit(QualifiedName node) {
                if (withinImport(node)) return true;
                if (node.getQualifier() instanceof SimpleName owner
                        && sdkTypes.contains(owner.getIdentifier())) {
                    references.add(new Reference(owner.getIdentifier(), node.getName().getIdentifier(),
                            FIELD_READ, new CallSite(file, unit, node)));
                }
                return true;
            }

            /** A bare name that reaches an {@code import static …Key.ENTER}. */
            @Override
            public boolean visit(SimpleName node) {
                if (node.getParent() instanceof QualifiedName
                        || node.getLocationInParent() == MethodInvocation.NAME_PROPERTY
                        || withinImport(node)) {
                    return true;
                }
                String owner = staticImports.get(node.getIdentifier());
                if (owner != null && !shadowed.contains(node.getIdentifier())) {
                    references.add(new Reference(owner, node.getIdentifier(), FIELD_READ,
                            new CallSite(file, unit, node)));
                }
                return true;
            }

            /**
             * {@code case UP ->}. The label is an unqualified name whose type lives on the switch expression,
             * which without bindings is unreadable — so the owning type is inferred from the label alone, and
             * only when exactly one SDK type declares a constant of that name.
             */
            @Override
            public boolean visit(SwitchCase node) {
                for (Object expression : node.expressions()) {
                    if (!(expression instanceof SimpleName label)) continue;
                    List<String> owners = fieldOwners.getOrDefault(label.getIdentifier(), List.of());
                    if (owners.size() == 1) {
                        references.add(new Reference(owners.getFirst(), label.getIdentifier(), FIELD_READ,
                                new CallSite(file, unit, label)));
                    } else if (owners.size() > 1) {
                        problems.add(pathLabel + ":" + unit.getLineNumber(label.getStartPosition())
                                + ": the case label '" + label.getIdentifier() + "' could be a constant on "
                                + String.join(" or ", owners) + ", and which one cannot be told from the "
                                + "source, so it was not checked.");
                    }
                }
                return true;
            }
        });
        return new Scan(List.copyOf(references), List.copyOf(problems));
    }

    /** True when {@code unit} writes {@code simpleName} anywhere — the gate on a file-wide type rename. */
    public static boolean mentions(CompilationUnit unit, String simpleName) {
        boolean[] found = {false};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.getIdentifier().equals(simpleName)) found[0] = true;
                return !found[0];
            }
        });
        return found[0];
    }

    /**
     * The single-member static imports of this file that name a field on a known SDK type, as member name →
     * owning type. On-demand imports ({@code import static …Key.*}) are left out on purpose: they say nothing
     * about which names were actually meant, so treating every matching bare name as SDK would attribute the
     * bot's own constants to the SDK.
     */
    private static Map<String, String> staticFieldImports(CompilationUnit unit,
                                                          Map<String, List<String>> fieldOwners) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object each : unit.imports()) {
            if (!(each instanceof ImportDeclaration imp) || !imp.isStatic() || imp.isOnDemand()) continue;
            if (!(imp.getName() instanceof QualifiedName qualified)) continue;
            String member = qualified.getName().getIdentifier();
            String owner = lastSegment(qualified.getQualifier().getFullyQualifiedName());
            if (fieldOwners.getOrDefault(member, List.of()).contains(owner)) out.put(member, owner);
        }
        return out;
    }

    /**
     * Every name this file declares as a variable, parameter or field. A static import is shadowed by any of
     * them, and a shadowed name is a use of the bot's own code — reporting it as an SDK break would name a line
     * that has nothing to do with the SDK.
     */
    private static Set<String> declaredNames(CompilationUnit unit) {
        Set<String> out = new LinkedHashSet<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                out.add(node.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                out.add(node.getName().getIdentifier());
                return true;
            }
        });
        return out;
    }

    /**
     * True when {@code node} sits inside an {@code import} declaration. An import's name is a
     * {@link QualifiedName} of exactly the shape a field read has, so without this every
     * {@code import static …Key.ENTER} would be scanned as a use of {@code Key.ENTER} — a reference on a line
     * the user never wrote a call on, and one the rewriter would then try to edit twice.
     */
    private static boolean withinImport(org.eclipse.jdt.core.dom.ASTNode node) {
        for (org.eclipse.jdt.core.dom.ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof ImportDeclaration) return true;
        }
        return false;
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String simpleTypeName(Type type) {
        if (type instanceof SimpleType simple) {
            return lastSegment(simple.getName().getFullyQualifiedName());
        }
        return null;
    }
}
