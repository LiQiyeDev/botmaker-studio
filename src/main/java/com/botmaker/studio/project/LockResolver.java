package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.nio.file.Path;

/**
 * The one answer to "may the user change <em>this</em> node, and how?" — {@link FileRole} and {@link MethodLock}
 * combined, for one file.
 *
 * <p><b>Why this exists.</b> The two verdicts used to be consulted separately and contradicted each other:
 * {@code MethodLock} documented {@code GameLoop.run}'s body as the user's (it has since been reclassified as
 * fully generated) while {@code FileRole} locked the file around it, and whichever caller asked last won. The
 * render path asked {@code FileRole}; nothing asked on the write path at all. Both now ask here, so they
 * cannot drift apart again — if
 * you find yourself calling {@code FileRole.of} or {@code MethodLock.of} outside this class to decide whether an
 * edit is allowed, that is the bug this class was written to end.
 *
 * <p><b>The rule.</b> {@link MethodLock} outranks {@link FileRole} at method granularity, and may unlock as well
 * as lock:
 * <pre>
 * signatureEditable = !role.isReadOnly() &amp;&amp; !lock.locksSignature()
 * bodyEditable      = SIGNATURE -> true                 // granted, however locked the file is
 *                     FULL      -> false
 *                     NONE      -> !role.isReadOnly()   // no opinion: the file decides
 * </pre>
 * A node with no enclosing method (a field, the class header, an import) is judged by the file alone.
 *
 * <p>Pure and cheap: no I/O, no state, constructed per call. A null {@code config} or {@code file} means "we
 * don't know what project this is" — used by tests and by editor paths with no project open — and permits
 * everything. A null <em>node</em>, by contrast, is a caller that forgot to say what it was editing, and is
 * always denied.
 */
public record LockResolver(ProjectConfig config, ProjectTemplate template, Path file) {

    /** Which half of a method an edit touches. {@link #SIGNATURE} also covers class-level structure. */
    public enum EditKind {
        /** Statements inside a method body — the user's, unless the method is {@link MethodLock#FULL}. */
        BODY,
        /** A method's name/params/return type/existence, a field, an import, the class itself. */
        SIGNATURE
    }

    /** Whether an edit is allowed, and — when it isn't — what to tell the user. */
    public record Verdict(boolean allowed, String reason) {
        private static final Verdict OK = new Verdict(true, null);

        static Verdict ok() {
            return OK;
        }

        static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** The resolver for whatever file is being edited right now, or a permissive one if there is no project. */
    public static LockResolver forActiveFile(ProjectConfig config, ProjectState state) {
        if (config == null || state == null) return new LockResolver(null, null, null);
        ProjectFile active = state.getActiveFile();
        return new LockResolver(config, state.getTemplate(), active == null ? null : active.getPath());
    }

    /** This file's role. {@link FileRole#EDITABLE} when we don't know the project. */
    public FileRole role() {
        return FileRole.of(config, template, file);
    }

    /**
     * The lock on the method enclosing {@code node} — the node itself if it is a {@link MethodDeclaration} —
     * or {@link MethodLock#NONE} when it sits outside any method (a field, the class header, an import).
     */
    public MethodLock lockAt(ASTNode node) {
        MethodDeclaration method = enclosingMethod(node);
        return method == null ? MethodLock.NONE : MethodLock.of(config, template, file, method);
    }

    /** True when {@code node}'s method may be renamed/retyped/deleted, or its class-level structure changed. */
    public boolean signatureEditable(ASTNode node) {
        return !role().isReadOnly() && !lockAt(node).locksSignature();
    }

    /** True when statements inside {@code node}'s method may be changed. */
    public boolean bodyEditable(ASTNode node) {
        return switch (lockAt(node)) {
            case SIGNATURE -> true;                 // the body is the user's, whatever the file says
            case FULL -> false;
            case NONE -> !role().isReadOnly();      // no opinion: defer to the file
        };
    }

    /** True when blocks in this file should default to refusing interaction. Per-method locks refine it. */
    public boolean suppressesInteraction() {
        return role().suppressesInteraction();
    }

    /** Whether {@code kind} of edit is permitted at {@code node}. */
    public boolean permits(ASTNode node, EditKind kind) {
        return check(node, kind).allowed();
    }

    /**
     * Whether {@code kind} of edit is permitted at {@code node}, with a reason to show the user when it isn't.
     * A null {@code node} is denied: the escape hatch for "no project" belongs on {@code config}, so a caller
     * that forgot to name its target fails loudly rather than silently editing locked code.
     */
    public Verdict check(ASTNode node, EditKind kind) {
        if (config == null || file == null) return Verdict.ok();
        if (node == null) return Verdict.no("Nothing to edit.");

        boolean allowed = kind == EditKind.SIGNATURE ? signatureEditable(node) : bodyEditable(node);
        return allowed ? Verdict.ok() : Verdict.no(reasonFor(node, kind));
    }

    private String reasonFor(ASTNode node, EditKind kind) {
        FileRole role = role();
        if (role == FileRole.LIBRARY) return "This is bundled library code — it can't be edited.";

        MethodLock lock = lockAt(node);
        if (lock.locksBody()) {
            // Two FULL locks, two right places to send the user: from an activity's isEnabled() to the run()
            // beside it; from the game loop to the activities it dispatches.
            return "run()".equals(methodName(node))
                    ? "The game loop is generated by BotMaker — your code goes in your activities' run()."
                    : methodName(node) + " is generated by BotMaker — edit the run() method instead.";
        }
        if (kind == EditKind.SIGNATURE && lock.locksSignature()) {
            return "BotMaker calls " + methodName(node) + ", so its name and parameters are fixed."
                    + " Its body is yours to change.";
        }
        return "This file is generated by BotMaker and can't be edited.";
    }

    private String methodName(ASTNode node) {
        MethodDeclaration method = enclosingMethod(node);
        return method == null || method.getName() == null ? "This code" : method.getName().getIdentifier() + "()";
    }

    /** The nearest {@link MethodDeclaration} at or above {@code node}, or null if there is none. */
    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method;
        }
        // JDT keeps comments out of the parent chain — a Comment's getParent() is always null, and it is
        // reachable only from CompilationUnit.getCommentList(). Walking up therefore finds nothing, so a
        // comment inside a body-editable method (an activity's run()) would be judged by its file instead of
        // its method. Fall back to the source range that actually contains it.
        if (node instanceof Comment comment) return methodContaining(comment);
        return null;
    }

    /** The method whose source range contains {@code comment}, or null if it sits outside every method. */
    private static MethodDeclaration methodContaining(Comment comment) {
        if (!(comment.getAlternateRoot() instanceof CompilationUnit cu)) return null;

        int position = comment.getStartPosition();
        MethodDeclaration[] found = new MethodDeclaration[1];
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                int start = method.getStartPosition();
                if (position >= start && position < start + method.getLength()) found[0] = method;
                return true;   // keep descending: an inner type's method is the closer answer
            }
        });
        return found[0];
    }
}
