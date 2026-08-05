package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.services.CursorNavigator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The overlay editor's <b>tree model</b>: every question {@code ProgramShapeOverlay} asks about the shape of the
 * block tree, answered without a single JavaFX type. Pure and static, so the placement rules it encodes — which
 * fail <em>silently</em> when they are wrong, since a misplaced caret just drops the insert — are testable
 * headlessly (see {@code ProgramShapeOverlayCursorTest}).
 *
 * <p><b>Why an {@link Index}.</b> Blocks carry no parent pointers, so every lookup used to re-walk the whole
 * tree: "is this body nested inside another" alone was a walk per body (quadratic), and the ordinal / locate /
 * statement-at helpers each walked again, several times per re-parse. {@link #index} does one DFS — the same
 * order as {@link CursorNavigator#collectAll} — and answers all of them from it.
 *
 * <p><b>Why {@link Position} rather than block references.</b> Every edit goes through {@code CodeEditor}, which
 * re-parses and republishes the whole tree, so a {@code StatementBlock} held across an edit is dead. A body's
 * DFS ordinal plus a slot index survives a re-parse that only adds or removes a bodiless statement, which is
 * what the insert → re-parse → re-home-the-caret handoff needs.
 */
public final class BlockTree {

    private BlockTree() {}

    /**
     * Tree coordinates that survive a re-parse: the DFS ordinal of a body among all bodies, and a slot index
     * within it. Index {@code -1} means "above the first statement", matching {@link InsertionCursor}.
     */
    public record Position(int bodyOrdinal, int index) {}

    /** One DFS walk of a published tree, answering every structural question the overlay asks. */
    public static final class Index {
        private final CodeBlock root;
        private final List<CodeBlock> all = new ArrayList<>();
        private final List<BodyBlock> bodies = new ArrayList<>();
        /** Bodies reached from inside another body — i.e. control-flow bodies, not method bodies. */
        private final Set<BodyBlock> nested = Collections.newSetFromMap(new IdentityHashMap<>());

        private Index(CodeBlock root) {
            this.root = root;
            walk(root, false);
        }

        private void walk(CodeBlock block, boolean insideBody) {
            if (block == null) return;
            all.add(block);
            boolean inside = insideBody;
            if (block instanceof BodyBlock body) {
                bodies.add(body);
                if (insideBody) nested.add(body);
                inside = true;
            }
            if (block instanceof BlockWithChildren bwc) {
                for (CodeBlock child : bwc.getChildren()) walk(child, inside);
            }
        }

        /** The tree this index was built from, or {@code null} for an index over no tree. */
        public CodeBlock root() {
            return root;
        }

        /** Every block, depth-first (same order as {@link CursorNavigator#collectAll}). */
        public List<CodeBlock> all() {
            return List.copyOf(all);
        }

        /** Every body, depth-first — the coordinate space {@link Position#bodyOrdinal()} indexes into. */
        public List<BodyBlock> bodies() {
            return List.copyOf(bodies);
        }

        /**
         * The bodies that are <em>not</em> inside another body: the method bodies. These are the render roots
         * when no single method is selected — rendering every body would draw each control-flow body twice,
         * once standalone and once under its owner.
         */
        public List<BodyBlock> topLevelBodies() {
            return bodies.stream().filter(b -> !nested.contains(b)).toList();
        }

        /** True when {@code body} belongs to this tree (used to spot a caret left dangling by a re-parse). */
        public boolean contains(BodyBlock body) {
            for (BodyBlock b : bodies) {
                if (b == body) return true;
            }
            return false;
        }

        /** {@code body}'s DFS ordinal, or {@code -1} when it is not part of this tree. */
        public int ordinalOf(BodyBlock body) {
            for (int i = 0; i < bodies.size(); i++) {
                if (bodies.get(i) == body) return i;
            }
            return -1;
        }

        /** The body at {@code ordinal}, or {@code null} when the ordinal is out of range. */
        public BodyBlock bodyAt(int ordinal) {
            return (ordinal >= 0 && ordinal < bodies.size()) ? bodies.get(ordinal) : null;
        }

        /** The statement {@code p} points at, or {@code null} when the position no longer resolves. */
        public StatementBlock statementAt(Position p) {
            if (p == null) return null;
            BodyBlock body = bodyAt(p.bodyOrdinal());
            if (body == null) return null;
            List<StatementBlock> statements = body.getStatements();
            return (p.index() >= 0 && p.index() < statements.size()) ? statements.get(p.index()) : null;
        }

        /** Where {@code stmt} sits, in coordinates a re-parse survives, or {@code null} when it isn't in the tree. */
        public Position locate(StatementBlock stmt) {
            if (stmt == null) return null;
            for (int b = 0; b < bodies.size(); b++) {
                int index = bodies.get(b).getStatements().indexOf(stmt);
                if (index >= 0) return new Position(b, index);
            }
            return null;
        }

        /**
         * Every method declared in the tree, in DFS order, labelled with its parameter list — {@code run()},
         * {@code aim(Rect area, int tries)}. The bare name is not an identity: two overloads collapsed to one
         * picker entry, and every lookup below then silently answered with whichever came first.
         */
        public List<String> methodLabels() {
            List<String> labels = new ArrayList<>();
            for (CodeBlock b : all) {
                if (b instanceof MethodDeclarationBlock m) labels.add(methodLabel(m));
            }
            return labels;
        }

        /** The body of the method labelled {@code label}, or {@code null} if there is no such method. */
        public BodyBlock methodBody(String label) {
            if (label == null) return null;
            for (CodeBlock b : all) {
                if (!(b instanceof MethodDeclarationBlock m) || !label.equals(methodLabel(m))) continue;
                for (CodeBlock child : m.getChildren()) {
                    if (child instanceof BodyBlock body) return body;
                }
            }
            return null;
        }

        /**
         * The caret inside {@code methodName}'s body: the slot just <em>before</em> a trailing {@code return},
         * so an inserted block lands where it will actually execute — an activity stub's body is only
         * {@code return Outcome.NEXT;}, and inserting below that produces unreachable code, which the user
         * experiences as the insert having done nothing. Falls back to {@link CursorNavigator#defaultCursor}
         * for a file whose method was renamed or removed by hand, or whose body is read-only scaffolding.
         */
        public InsertionCursor methodCursor(String label) {
            for (CodeBlock b : all) {
                if (!(b instanceof MethodDeclarationBlock m)
                        || !Objects.equals(label, methodLabel(m))) continue;
                for (CodeBlock child : m.getChildren()) {
                    if (!(child instanceof BodyBlock body) || body.isReadOnly()) continue;
                    List<StatementBlock> statements = body.getStatements();
                    boolean endsWithReturn = !statements.isEmpty()
                            && statements.get(statements.size() - 1).getAstNode()
                                    instanceof org.eclipse.jdt.core.dom.ReturnStatement;
                    return new InsertionCursor(body, statements.size() - (endsWithReturn ? 2 : 1));
                }
            }
            return CursorNavigator.defaultCursor(root);
        }
    }

    /**
     * How the method picker names a method: {@code run()}, {@code aim(Rect area, int tries)}. Built from the
     * declaration's own AST node rather than from {@link com.botmaker.studio.util.MethodSignature}, which models
     * a <em>resolved SDK</em> method — these are the user's own, and may not compile at all while being edited.
     */
    static String methodLabel(MethodDeclarationBlock m) {
        StringBuilder sb = new StringBuilder(m.getMethodName()).append('(');
        if (m.getAstNode() instanceof org.eclipse.jdt.core.dom.MethodDeclaration decl) {
            List<?> params = decl.parameters();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(params.get(i));
            }
        }
        return sb.append(')').toString();
    }

    /**
     * Whether {@code body} lies inside {@code scope} — the test behind "has the caret escaped the method the HUD
     * is scoped to". It walks {@code scope} rather than the whole tree, so it costs the subtree, not the file.
     */
    public static boolean containsDescendant(CodeBlock scope, BodyBlock body) {
        if (scope == null || body == null) return false;
        for (CodeBlock b : CursorNavigator.collectAll(scope)) {
            if (b == body) return true;
        }
        return false;
    }

    // ── the flattened row model ──────────────────────────────────────────────────────────────────────────

    /** What a {@link Row} stands for. */
    public enum Kind {
        /** A statement of {@link Row#body()}, at {@link Row#index()}. */
        STATEMENT,
        /** A branch label ({@code else}, {@code case A:}) introducing the body it precedes. */
        CAPTION,
        /** The placeholder for a body with no statements at all. */
        EMPTY
    }

    /**
     * One line of the compact tree.
     *
     * @param body    the body this row belongs to — for a {@link Kind#CAPTION}, the body it introduces, so
     *                clicking the caption parks the caret at the top of that branch
     * @param index   the slot in {@code body}; {@code -1} on a caption or empty row, which address the slot
     *                above the body's first statement
     * @param depth   indentation level, 0 at the rendered body's own statements
     * @param caption the branch label, non-null only for {@link Kind#CAPTION}
     */
    public record Row(Kind kind, StatementBlock stmt, BodyBlock body, int index, int depth, String caption) {}

    /**
     * {@code body}'s statements as a flat, indented, <b>fully branched</b> row list.
     *
     * <p>This is the fix for the overlay's worst class of bug: it used to walk only the direct
     * {@link BodyBlock} children of each statement, which meant an {@code else} body drew identically to the
     * {@code then} above it, and an {@code else if} chain, a {@code switch}'s cases and a matches-switch's
     * branches <em>never drew at all</em> — their bodies are not direct children. A program the HUD showed as
     * complete could have most of its logic invisible. Branch structure now comes from
     * {@link BranchingBlock#branches()}, so each owner names its own branches and this stays type-agnostic;
     * anything that is not a {@code BranchingBlock} still contributes its direct bodies uncaptioned, which is
     * what a loop or a lambda wants.
     *
     * <p>Captions sit at their owner's depth and the body they introduce one level deeper — so {@code else}
     * lines up under its {@code if} rather than drifting right down a chain.
     */
    public static List<Row> flatten(BodyBlock body, int depth) {
        List<Row> out = new ArrayList<>();
        flattenBody(body, depth, out);
        return out;
    }

    private static void flattenBody(BodyBlock body, int depth, List<Row> out) {
        if (body == null) return;
        List<StatementBlock> statements = body.getStatements();
        if (statements.isEmpty()) {
            out.add(new Row(Kind.EMPTY, null, body, -1, depth, null));
            return;
        }
        for (int i = 0; i < statements.size(); i++) {
            StatementBlock stmt = statements.get(i);
            out.add(new Row(Kind.STATEMENT, stmt, body, i, depth, null));
            flattenBranches(stmt, depth, out);
        }
    }

    /** The sub-structure {@code owner} contains, drawn beneath its own row. */
    private static void flattenBranches(CodeBlock owner, int depth, List<Row> out) {
        if (owner instanceof BranchingBlock branching) {
            for (BranchingBlock.Branch branch : branching.branches()) {
                if (branch.caption() != null) {
                    // The caption addresses the top of the branch it introduces. For a chained `else if` the
                    // target is the nested block, so borrow its first body — the caret has to land in a body.
                    BodyBlock target = branch.target() instanceof BodyBlock b ? b : firstBody(branch.target());
                    out.add(new Row(Kind.CAPTION, null, target, -1, depth, branch.caption()));
                }
                if (branch.target() instanceof BodyBlock b) {
                    flattenBody(b, depth + 1, out);
                } else {
                    // An `else if`: continue the chain at this level so it reads flat, as the editor draws it.
                    flattenBranches(branch.target(), depth, out);
                }
            }
            return;
        }
        if (owner instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) {
                if (child instanceof BodyBlock childBody) flattenBody(childBody, depth + 1, out);
            }
        }
    }

    /** The first body reachable from {@code block}, for a caption that has to point the caret somewhere. */
    private static BodyBlock firstBody(CodeBlock block) {
        if (block instanceof BodyBlock b) return b;
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) {
                BodyBlock found = firstBody(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Builds the one-walk index for {@code root}. Cheap enough to rebuild on every render. */
    public static Index index(CodeBlock root) {
        return new Index(root);
    }

    /** {@link Index#methodCursor} for callers that hold only a root — chiefly the headless placement tests. */
    public static InsertionCursor methodCursor(CodeBlock root, String label) {
        return index(root).methodCursor(label);
    }

    /** The caret inside an activity's no-arg {@code run()}. See {@link Index#methodCursor}. */
    public static InsertionCursor runCursor(CodeBlock root) {
        return methodCursor(root, "run()");
    }
}
