package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.List;

/**
 * BotMaker-owned <em>members</em> inside a file the user otherwise owns — the granularity {@link FileRole}
 * (whole files) and {@link MethodLock} (whole methods) can't express.
 *
 * <p>Both cases live in an activity — any class extending the SDK's {@code Activity}, which is the user's file:
 * <ul>
 *   <li><b>The nested {@code Outcome} enum.</b> Its constants are edited on the flow canvas and written back by
 *       {@code services/ActivityStubSync}. Editing them here would be undone on the next save, and a constant
 *       added by hand is invisible to the canvas — so the editor refuses, and says where to go instead.</li>
 *   <li><b>The last statement of {@code run()}</b>, which is always a {@code return}. The flow routes on what
 *       an activity reports, so every path out of one has to report something; the statement is pinned rather
 *       than merely generated. <em>Which</em> outcome it returns is entirely the user's choice — this only
 *       stops the statement being deleted or buried under later code.</li>
 * </ul>
 *
 * <p>Consulted through {@link LockResolver}, never directly: the reason that class exists is that the verdicts
 * used to be asked separately and contradict each other.
 */
public final class GeneratedMembers {

    /** The nested enum {@code ActivityService.generateStubSource} emits and the flow dialog owns. */
    private static final String OUTCOME_ENUM = "Outcome";

    /** The singleton the registry and the entry point bind an activity through. */
    private static final String INSTANCE_FIELD = "INSTANCE";

    private GeneratedMembers() {}

    /** The SDK base class every activity extends — the structural mark of one, wherever its file lives. */
    private static final String ACTIVITY_BASE = "Activity";

    /**
     * True when {@code node} is the generated {@code Outcome} enum of an activity stub, or anything inside it.
     *
     * <p>This one keeps the <em>directory</em> rule, unlike {@link #terminalReturn}: what locks the enum is that
     * the flow canvas owns it, and only the activities under {@code activitiesPackageDir()} are on that canvas.
     * {@code GoHome} and {@code Popups} extend {@code Activity} and carry an {@code Outcome} too, but they are
     * called directly rather than routed on — locking theirs would leave the user nowhere to edit it.
     */
    public static boolean isOutcomeEnum(ProjectConfig config, ProjectTemplate template, Path file, ASTNode node) {
        if (!appliesTo(config, template, file) || node == null) return false;
        if (!MethodLock.isActivityStub(config, file)) return false;
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof EnumDeclaration e && OUTCOME_ENUM.equals(e.getName().getIdentifier())) return true;
        }
        return false;
    }

    /**
     * True when {@code node} is the {@code INSTANCE} static of a scaffold-managed file, or anything inside it.
     *
     * <p>It is the handle BotMaker reaches the activity through — {@code ActivityRegistry} lists
     * {@code Mining.INSTANCE}, the entry point binds {@code GoHome.INSTANCE::execute} — so deleting or renaming
     * it breaks the build from a file that is otherwise entirely the user's. The audience model already hid it
     * from a reader ({@code MemberVisibility}); nothing stopped its author clicking the delete cross on it, in
     * {@code GoHome.java} most visibly, because a field sits in no method and so had no {@link MethodLock} to
     * inherit.
     *
     * <p>Only {@code INSTANCE} by name, not every static in the file: {@code Popups.POPUPS} is a static too and
     * it is the author's own template list, theirs to edit and delete.
     */
    public static boolean isBoundInstance(ProjectConfig config, ProjectTemplate template, Path file,
                                          ASTNode node) {
        if (!appliesTo(config, template, file) || node == null) return false;
        if (!MethodLock.isScaffoldManaged(config, template, file)) return false;
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof FieldDeclaration field && declaresInstance(field)) return true;
        }
        return false;
    }

    private static boolean declaresInstance(FieldDeclaration field) {
        if (!Modifier.isStatic(field.getModifiers())) return false;
        for (Object fragment : field.fragments()) {
            if (fragment instanceof VariableDeclarationFragment f
                    && INSTANCE_FIELD.equals(f.getName().getIdentifier())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pinned trailing {@code return} of {@code body} when it is an activity's {@code run()} body, else
     * null. Callers use it two ways: refuse to delete that statement, and refuse to insert after it.
     */
    public static Statement terminalReturn(ProjectConfig config, ProjectTemplate template, Path file,
                                           ASTNode body) {
        if (!appliesTo(config, template, file) || !(body instanceof Block block)) return null;
        if (!(block.getParent() instanceof MethodDeclaration method)) return null;
        if (method.getName() == null || !"run".equals(method.getName().getIdentifier())) return null;
        if (!isActivity(config, file, method)) return null;

        List<?> statements = block.statements();
        if (statements.isEmpty()) return null;
        Statement last = (Statement) statements.getLast();
        return last instanceof ReturnStatement ? last : null;
    }

    /** True when {@code node} is that pinned {@code return} — the check a delete has to make. */
    public static boolean isTerminalReturn(ProjectConfig config, ProjectTemplate template, Path file,
                                           ASTNode node) {
        if (!(node instanceof ReturnStatement)) return false;
        return node == terminalReturn(config, template, file, node.getParent());
    }

    /**
     * Only a game-bot project has activities. <em>Which</em> file is not asked here on purpose — see
     * {@link #inActivityClass}.
     */
    private static boolean appliesTo(ProjectConfig config, ProjectTemplate template, Path file) {
        return config != null && file != null && template == ProjectTemplate.GAME_BOT;
    }

    /**
     * True when {@code node} belongs to an activity: it is in a class extending the SDK's {@code Activity}, or
     * — as a fallback — in a file under the {@code activities} package.
     *
     * <p>The structural half is the fix for the return that rendered two ways. {@code GoHome.java} and
     * {@code Popups.java} are activities by every measure that matters — they extend {@code Activity} and their
     * {@code run()} ends in the return whose outcome the caller reads — but they sit beside the entry point, so
     * the old directory-only test failed them and their return fell through to the generic orange expression
     * chip while the identical statement one directory over got the outcome picker.
     *
     * <p>The directory half stays as a fallback rather than being replaced, because it answers for a stub whose
     * {@code extends} clause is momentarily not what we expect — mid-edit, or written by an older Studio. A
     * file {@code ensureStubs} created is an activity whatever its header currently says.
     */
    private static boolean isActivity(ProjectConfig config, Path file, ASTNode node) {
        return inActivityClass(node) || MethodLock.isActivityStub(config, file);
    }

    /** True when {@code node} sits inside a class extending {@code Activity} — asked of the code, not the path. */
    private static boolean inActivityClass(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof TypeDeclaration type && extendsActivity(type)) return true;
        }
        return false;
    }

    /** True when {@code type}'s superclass is {@code Activity}, raw or parameterized ({@code Activity<X>}). */
    private static boolean extendsActivity(TypeDeclaration type) {
        Type superclass = type.getSuperclassType();
        if (superclass instanceof ParameterizedType parameterized) superclass = parameterized.getType();
        if (!(superclass instanceof SimpleType simple)) return false;
        String name = simple.getName().getFullyQualifiedName();
        int lastDot = name.lastIndexOf('.');
        return ACTIVITY_BASE.equals(lastDot < 0 ? name : name.substring(lastDot + 1));
    }
}
