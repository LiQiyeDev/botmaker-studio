package com.botmaker.studio.project.seed;

import com.botmaker.plugin.api.catalog.ScaffoldEntry;
import com.botmaker.plugin.api.catalog.ScaffoldPlan;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one planned seed into the source text a project gets.
 *
 * <p>The host half of the seed surface, and the reason the contract carries no parser. A plugin's annotations
 * say <em>intent</em> — this type's name may be replaced, this enum's constants may be replaced, this body is
 * the user's — and reflection can go no further than that: it knows a member exists and nothing whatever about
 * where its text sits. Locating each mark in the file is a parse, and this is where the parse happens.
 *
 * <h2>What is rewritten, and what is deliberately not</h2>
 *
 * <p>Four things: the package declaration, the substituted type's name, each substituted enum's constants, and
 * the marks themselves — which are stripped along with their imports, so a user opening the file never meets
 * an annotation from a module their project does not depend on. Everything else survives byte for byte,
 * including every comment and every blank line, because a seed's javadoc is written for the person who will
 * open it and reformatting it would be this class having an opinion about a plugin's prose.
 *
 * <p><b>The name is rewritten by identifier, not by node kind.</b> Every {@link SimpleName} matching the seed
 * class's own simple name becomes the new one, which catches the declaration, {@code extends
 * Activity<ActivityTemplate.Outcome>}, {@code new ActivityTemplate()} and {@code ActivityTemplate.INSTANCE}
 * alike. It would also catch an unrelated type that happened to share the name — and that is a seed naming a
 * class after itself, which is a mistake in the seed rather than a case to handle.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>{@link #render} answers {@code null} when it cannot produce a file it believes in: a seed whose source
 * will not parse, or whose top-level type is not the one the catalog described. A null is one file not
 * written and a line the caller can report; the alternative is a project creation that fails halfway, or —
 * worse — a {@code .java} in somebody's project that does not compile and that they did not write.
 */
public final class SeedWriter {

    /** The marks, by simple name — stripped from the written file along with their imports. */
    private static final List<String> MARKS =
            List.of("Scaffold", "ClassName", "EnumValues", "Editable");

    /** The package the marks live in; an import starting with it goes with them. */
    private static final String MARK_PACKAGE = "com.botmaker.plugin.api.scaffold";

    private SeedWriter() {}

    /**
     * The source for {@code file}, in {@code packageName}, or {@code null} when it cannot be produced.
     *
     * <p>{@code packageName} is the package the file lands in, which is not always the project's base
     * package — an activity's seed resolves to {@code <base>.activities}, and the package declaration has to
     * agree with the directory the path puts it in or nothing compiles. It is derived from the path rather
     * than passed separately, so the two cannot disagree.
     */
    public static String render(ScaffoldPlan.PlannedFile file) {
        if (file == null) return null;
        ScaffoldEntry seed = file.seed();
        String source = seed.source();

        CompilationUnit cu = SourceParser.parse(source);
        // A seed that does not parse is a broken plugin, not a broken project. Refusing is what keeps the
        // second from following from the first.
        if (SourceParser.hasSyntaxErrors(cu)) return null;

        TypeDeclaration type = firstType(cu);
        // JDT recovers aggressively, so "it parsed" is not on its own evidence that this is the file the
        // catalog described.
        if (type == null || !seed.templateName().equals(type.getName().getIdentifier())) return null;

        AST ast = cu.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);

        setPackage(ast, rewrite, cu, packageOf(file.path()));
        if (seed.renamesType()) renameType(rewrite, cu, seed.templateName(), file.typeName());
        for (ScaffoldEntry.EnumHole hole : seed.enums()) {
            setConstants(ast, rewrite, type, hole.enumName(), file.constantsFor(hole));
        }
        stripMarks(rewrite, cu, type);

        try {
            Document document = new Document(source);
            TextEdit edits = rewrite.rewriteAST(document, null);
            edits.apply(document);
            return document.get();
        } catch (Exception e) {
            // An edit that will not apply cleanly leaves the seed unwritten rather than half written.
            return null;
        }
    }

    /**
     * The package a project-relative path implies — {@code src/main/java/com/mybot/activities/Mining.java}
     * is {@code com.mybot.activities}.
     *
     * <p>Derived rather than passed, because a package declaration disagreeing with the directory it sits in
     * is a file that does not compile, and two arguments that must agree eventually will not.
     */
    static String packageOf(String path) {
        String prefix = "src/main/java/";
        if (path == null || !path.startsWith(prefix)) return "";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < prefix.length()) return "";
        return path.substring(prefix.length(), lastSlash).replace('/', '.');
    }

    private static void setPackage(AST ast, ASTRewrite rewrite, CompilationUnit cu, String packageName) {
        if (packageName.isEmpty()) {
            if (cu.getPackage() != null) rewrite.remove(cu.getPackage(), null);
            return;
        }
        PackageDeclaration declaration = ast.newPackageDeclaration();
        declaration.setName(ast.newName(packageName));
        if (cu.getPackage() == null) {
            rewrite.set(cu, CompilationUnit.PACKAGE_PROPERTY, declaration, null);
        } else {
            rewrite.set(cu.getPackage(), PackageDeclaration.NAME_PROPERTY,
                    ast.newName(packageName), null);
        }
    }

    /** Every mention of the seed class's own simple name, rewritten — see the class note on why by name. */
    private static void renameType(ASTRewrite rewrite, CompilationUnit cu, String from, String to) {
        if (to == null || to.isEmpty() || to.equals(from)) return;
        List<SimpleName> mentions = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (from.equals(node.getIdentifier())) mentions.add(node);
                return true;
            }
        });
        for (SimpleName mention : mentions) {
            rewrite.set(mention, SimpleName.IDENTIFIER_PROPERTY, to, null);
        }
    }

    /**
     * Replaces one nested enum's constants wholesale.
     *
     * <p>As a set rather than a diff, and in the order given: whoever supplied them owns their order, and a
     * constant that is gone is one nothing offers any more. A {@code null} list means the seeding said nothing
     * about this hole, which is not the same as saying it is empty — the seed's own constants stand, which is
     * what lets a seed compile on its own.
     */
    private static void setConstants(AST ast, ASTRewrite rewrite, TypeDeclaration type, String enumName,
                                     List<String> constants) {
        if (constants == null) return;
        EnumDeclaration declaration = nestedEnum(type, enumName);
        if (declaration == null) return;
        if (constantNames(declaration).equals(constants)) return;

        ListRewrite list = rewrite.getListRewrite(declaration, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        for (Object existing : declaration.enumConstants()) list.remove((ASTNode) existing, null);
        for (String constant : constants) {
            EnumConstantDeclaration created = ast.newEnumConstantDeclaration();
            created.setName(ast.newSimpleName(constant));
            list.insertLast(created, null);
        }
    }

    /**
     * Removes the four marks and the imports that brought them in.
     *
     * <p>They are the plugin's build-time vocabulary and have no business in a user's project: the annotation
     * type is in {@code botmaker-studio-api}, which a bot does not depend on, so leaving one behind would be a
     * file that does not compile. Stripping the import as well is not tidiness for its own sake — an unused
     * import of a type nothing resolves is the same error.
     *
     * <p>Only the marks. Every other annotation the seed carries is the plugin's own statement about its code
     * — {@code @Override} above all — and survives.
     */
    private static void stripMarks(ASTRewrite rewrite, CompilationUnit cu, TypeDeclaration type) {
        for (Object imported : cu.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) imported;
            if (declaration.getName().getFullyQualifiedName().startsWith(MARK_PACKAGE)) {
                rewrite.remove(declaration, null);
            }
        }
        removeMarksOn(rewrite, type);
        type.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                removeMarksOn(rewrite, node);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                removeMarksOn(rewrite, node);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.MethodDeclaration node) {
                removeMarksOn(rewrite, node);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.FieldDeclaration node) {
                removeMarksOn(rewrite, node);
                return true;
            }
        });
    }

    private static void removeMarksOn(ASTRewrite rewrite, BodyDeclaration declaration) {
        for (Object modifier : declaration.modifiers()) {
            if (modifier instanceof Annotation annotation
                    && MARKS.contains(annotation.getTypeName().toString())) {
                rewrite.remove((ASTNode) modifier, null);
            }
        }
    }

    // ---- AST helpers ------------------------------------------------------------------------------------

    private static EnumDeclaration nestedEnum(TypeDeclaration type, String name) {
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof EnumDeclaration e && name.equals(e.getName().getIdentifier())) return e;
        }
        return null;
    }

    private static List<String> constantNames(EnumDeclaration declaration) {
        List<String> names = new ArrayList<>();
        for (Object constant : declaration.enumConstants()) {
            names.add(((EnumConstantDeclaration) constant).getName().getIdentifier());
        }
        return names;
    }

    private static TypeDeclaration firstType(CompilationUnit cu) {
        for (Object type : cu.types()) {
            if (type instanceof TypeDeclaration declaration) return declaration;
        }
        return null;
    }
}
