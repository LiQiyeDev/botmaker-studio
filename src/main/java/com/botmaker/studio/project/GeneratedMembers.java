package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;

import java.nio.file.Path;
import java.util.List;

/**
 * BotMaker-owned <em>members</em> inside a file the user otherwise owns — the granularity {@link FileRole}
 * (whole files) and {@link MethodLock} (whole methods) can't express.
 *
 * <p>Both cases live in an activity stub, which is the user's file:
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

    private GeneratedMembers() {}

    /**
     * True when {@code node} is the generated {@code Outcome} enum of an activity stub, or anything inside it.
     */
    public static boolean isOutcomeEnum(ProjectConfig config, ProjectTemplate template, Path file, ASTNode node) {
        if (!appliesTo(config, template, file) || node == null) return false;
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof EnumDeclaration e && OUTCOME_ENUM.equals(e.getName().getIdentifier())) return true;
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

    /** Only a game-bot project has activity stubs, and only their files carry generated members. */
    private static boolean appliesTo(ProjectConfig config, ProjectTemplate template, Path file) {
        return config != null && file != null && template == ProjectTemplate.GAME_BOT
                && MethodLock.isActivityStub(config, file);
    }
}
