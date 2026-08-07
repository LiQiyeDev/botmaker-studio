package com.botmaker.studio.parser.guard;

import com.botmaker.studio.config.AppVersion;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.project.LockResolver.EditKind;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One refused edit, as everything a future fix would need to reconstruct it — the payload of a line in
 * {@link RefusalJournal}.
 *
 * <p>The guard refuses a rewrite that would emit unparseable Java; the refusal is the symptom, and the rewrite
 * that produced it is the bug. Which rewrite, over which block, in which file, against which JDT problem, is
 * exactly what a stderr line loses the moment Studio closes — and what a packaged build never shows at all. So
 * a refusal is recorded rather than printed.
 *
 * <p><b>Every field is best-effort and may be null.</b> No project open, no block registered for the target
 * node, a rewrite with no target at all ({@code normalizeSwitches}) — all normal. A missing field must cost the
 * field, never the record: a diagnostic that throws would turn a refused edit into a lost one.
 *
 * <p>Absolute paths are deliberately not stored. The file is recorded relative to the project root, because a
 * journal is meant to be handed over and a home directory is not diagnostic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefusedEdit(
        // identity
        int schema,
        String at,
        String fingerprint,
        // build
        String studioVersion,
        boolean devBuild,
        String os,
        String javaVersion,
        // project
        String projectName,
        String file,
        String template,
        // the rewrite that emitted the broken source
        String refusedBy,
        String editKind,
        // the block it was editing
        String nodeType,
        Integer nodeLine,
        String nodeSnippet,
        String blockClass,
        String blockId,
        // what JDT said about the result
        int problemId,
        String message,
        int line,
        int startPosition,
        int endPosition,
        List<String> arguments,
        // the sources, written beside the journal
        String newSourceFile,
        String previousSourceFile) {

    /** Bumped when a field changes meaning, so an old line is still readable for what it is. */
    public static final int SCHEMA = 1;

    /** How much of the edited node's source is kept inline — enough to recognise it, not enough to be a dump. */
    private static final int SNIPPET_LIMIT = 120;

    /**
     * The record for a refusal, filled in as far as the available context allows.
     *
     * @param problem      the first syntax error in the source that was refused
     * @param refusedBy    the {@code CodeEditor} method that emitted it, as {@code method:line}
     * @param kind         which half of the target's method the edit touched, or null
     * @param target       the node being edited, or null for a whole-file rewrite
     * @param previousCode the source as it stands — the text {@code target}'s offsets index into
     */
    public static RefusedEdit of(IProblem problem, String refusedBy, EditKind kind, ASTNode target,
                                 String previousCode, ProjectConfig config, ProjectState state) {
        CodeBlock block = target != null && state != null ? state.getBlockForNode(target).orElse(null) : null;
        String nodeType = target != null ? target.getClass().getSimpleName() : null;
        String blockClass = block != null ? block.getClass().getSimpleName() : null;
        return new RefusedEdit(
                SCHEMA,
                Instant.now().toString(),
                fingerprint(refusedBy, problem.getID(), blockClass),
                AppVersion.get(),
                AppVersion.isDevBuild(),
                System.getProperty("os.name"),
                System.getProperty("java.version"),
                config != null ? config.projectName() : null,
                relativeFile(config, state),
                state != null && state.getTemplate() != null ? state.getTemplate().name() : null,
                refusedBy,
                kind != null ? kind.name() : null,
                nodeType,
                nodeLine(target, state),
                snippet(target, previousCode),
                blockClass,
                block != null ? block.getId() : null,
                problem.getID(),
                problem.getMessage(),
                problem.getSourceLineNumber(),
                problem.getSourceStart(),
                problem.getSourceEnd(),
                problem.getArguments() != null ? List.of(problem.getArguments()) : null,
                null, null);
    }

    /** This record with the dump filenames the journal ended up writing. */
    public RefusedEdit withSources(String newSourceFile, String previousSourceFile) {
        return new RefusedEdit(schema, at, fingerprint, studioVersion, devBuild, os, javaVersion,
                projectName, file, template, refusedBy, editKind, nodeType, nodeLine, nodeSnippet,
                blockClass, blockId, problemId, message, line, startPosition, endPosition, arguments,
                newSourceFile, previousSourceFile);
    }

    /**
     * A stable short id for "this bug", so repeated occurrences group without anything having to count them.
     * Deliberately over the <em>cause</em> (which rewrite, which problem, which block) and not over the
     * message or the offsets, which vary with the user's file.
     */
    private static String fingerprint(String refusedBy, int problemId, String blockClass) {
        return String.format("%08x", Objects.hash(refusedBy, problemId, blockClass));
    }

    /** The active file relative to the project root — never the absolute path. */
    private static String relativeFile(ProjectConfig config, ProjectState state) {
        if (state == null || state.getActiveFile() == null) return null;
        Path path = state.getActiveFile().getPath();
        if (path == null) return null;
        try {
            return config != null ? config.projectPath().relativize(path).toString() : path.getFileName().toString();
        } catch (IllegalArgumentException e) {
            // A file outside the project root can't be relativized; its name still says which one it was.
            return path.getFileName().toString();
        }
    }

    /** The line {@code target} starts on, in the source as it stands. */
    private static Integer nodeLine(ASTNode target, ProjectState state) {
        if (target == null || state == null) return null;
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        if (cu == null) return null;
        int lineNumber = cu.getLineNumber(target.getStartPosition());
        return lineNumber > 0 ? lineNumber : null;
    }

    /** {@code target}'s own source, truncated and flattened to one line. */
    private static String snippet(ASTNode target, String previousCode) {
        if (target == null || previousCode == null) return null;
        int start = target.getStartPosition();
        if (start < 0 || start >= previousCode.length()) return null;
        int end = Math.min(previousCode.length(), start + Math.max(0, target.getLength()));
        String text = previousCode.substring(start, end).replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return null;
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
    }

    /** The one-line summary the console still prints, so a dev run says the same thing the journal stores. */
    public String summary() {
        return "(" + refusedBy + (blockClass != null ? " on " + blockClass : "")
                + ") " + message + " at line " + line
                + " [" + fingerprint + "]";
    }
}
