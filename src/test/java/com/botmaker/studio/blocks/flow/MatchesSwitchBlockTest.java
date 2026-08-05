package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.parser.EditorFixture;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which block a {@code switch} becomes, and what the {@code Matches} one narrows its image menus to.
 *
 * <p>The dispatch matters more than it looks: {@code BlockConverter} claims a guarded arrow switch <em>before</em>
 * the ordinary one, and getting that order or that test wrong is silent — an ordinary switch rendered by the
 * Matches block loses its case labels, and a Matches switch rendered by the ordinary block shows its guard as
 * an unreadable expression. Neither throws.
 *
 * <p>Rendering itself is out of scope (it needs the FX toolkit); this is the construction and narrowing half.
 */
class MatchesSwitchBlockTest {

    private static String inLambda(String switchBody) {
        return """
                package com.mybot;
                public class Subject {
                    static final ImageTemplateGroup POPUPS = ImageTemplateGroup.of(
                            new ImageTemplate("popups/mail.png"),
                            new ImageTemplate("popups/gift.png"),
                            new ImageTemplate("popups/chest.png"));
                    public void run() {
                        ImageFinder.whileFindAny(POPUPS, found -> {
                %s
                        });
                    }
                }
                """.formatted(switchBody.indent(12));
    }

    private static final String GUARDED_SWITCH = """
            switch (found) {
                case Matches m when m.hasAny(new ImageTemplate("popups/mail.png")) -> {
                    ImageClicker.click(m.best());
                }
                default -> {
                }
            }
            """;

    private static List<CodeBlock> flatten(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren p) {
            for (CodeBlock c : p.getChildren()) out.addAll(flatten(c));
        }
        return out;
    }

    private static List<String> blockKinds(EditorFixture fixture) {
        return flatten(fixture.root).stream().map(b -> b.getClass().getSimpleName()).toList();
    }

    private static SwitchStatement switchIn(EditorFixture fixture) {
        List<SwitchStatement> found = new ArrayList<>();
        fixture.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override public boolean visit(SwitchStatement node) { found.add(node); return true; }
        });
        return found.isEmpty() ? null : found.getFirst();
    }

    // ---- Which block ----

    @Test
    void aGuardedMatchesSwitchBecomesTheMatchesBlock() {
        List<String> kinds = blockKinds(new EditorFixture(inLambda(GUARDED_SWITCH)));

        assertAll(
                () -> assertTrue(kinds.contains("MatchesSwitchBlock"), () -> "produced " + kinds),
                () -> assertFalse(kinds.contains("SwitchBlock"),
                        "the ordinary switch block must not also claim it"));
    }

    @Test
    void anOrdinarySwitchIsUntouched() {
        List<String> kinds = blockKinds(new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        String s = "a";
                        switch (s) {
                            case "a":
                                break;
                        }
                    }
                }
                """));

        assertAll(
                () -> assertTrue(kinds.contains("SwitchBlock"), () -> "produced " + kinds),
                () -> assertFalse(kinds.contains("MatchesSwitchBlock")));
    }

    /**
     * Each branch's body is a real {@link BodyBlock}, which is what gives it the same drop zones as an
     * {@code if}. Without it the branch would render but nothing could be dropped into it.
     */
    @Test
    void everyBranchBodyIsADropTargetIncludingTheDefault() {
        EditorFixture fixture = new EditorFixture(inLambda(GUARDED_SWITCH));

        CodeBlock block = flatten(fixture.root).stream()
                .filter(b -> b instanceof MatchesSwitchBlock).findFirst().orElse(null);
        assertNotNull(block, "the switch should have become a MatchesSwitchBlock");

        long bodies = ((BlockWithChildren) block).getChildren().stream()
                .filter(c -> c instanceof BodyBlock).count();
        assertEquals(2, bodies, "one body for the branch, one for `otherwise`");
    }

    // ---- The narrowing ----

    @Test
    void theBranchMenusAreNarrowedToTheEnclosingGroup() {
        EditorFixture fixture = new EditorFixture(inLambda(GUARDED_SWITCH));

        List<String> allowed = MatchesGroupScope.allowedPaths(switchIn(fixture));

        assertEquals(List.of("popups/mail.png", "popups/gift.png", "popups/chest.png"), allowed,
                "a branch can only ever test images the enclosing group can produce");
    }

    @Test
    void anInlineGroupNarrowsJustAsAConstantDoes() {
        EditorFixture fixture = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageFinder.whileFindAny(ImageTemplateGroup.of(new ImageTemplate("a.png")), found -> {
                            switch (found) {
                                case Matches m when m.hasAny(new ImageTemplate("a.png")) -> {
                                }
                                default -> {
                                }
                            }
                        });
                    }
                }
                """);

        assertEquals(List.of("a.png"), MatchesGroupScope.allowedPaths(switchIn(fixture)));
    }

    /**
     * Unresolvable is unrestricted, never empty. A group assembled at runtime is a legitimate thing to write,
     * and a chip menu offering nothing would make the block unusable rather than merely permissive.
     */
    @Test
    void aGroupBuiltAtRuntimeLeavesTheMenusWide() {
        EditorFixture fixture = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageFinder.whileFindAny(buildGroup(), found -> {
                            switch (found) {
                                case Matches m when m.hasAny(new ImageTemplate("a.png")) -> {
                                }
                                default -> {
                                }
                            }
                        });
                    }
                    ImageTemplateGroup buildGroup() { return null; }
                }
                """);

        assertNull(MatchesGroupScope.allowedPaths(switchIn(fixture)),
                "null is 'no restriction' — an empty list would offer the user nothing");
    }

    @Test
    void aSwitchOutsideAnyFindCallIsUnrestricted() {
        EditorFixture fixture = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run(Matches found) {
                        switch (found) {
                            case Matches m when m.hasAny(new ImageTemplate("a.png")) -> {
                            }
                            default -> {
                            }
                        }
                    }
                }
                """);

        assertNull(MatchesGroupScope.allowedPaths(switchIn(fixture)));
    }

    /**
     * The walk is anchored on the lambda, not on any enclosing invocation — a find call nested <em>inside</em>
     * the body did not produce this {@code Matches}, and narrowing to its group would offer the wrong images.
     */
    @Test
    void anInnerFindCallDoesNotHijackTheNarrowing() {
        EditorFixture fixture = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    static final ImageTemplateGroup OUTER = ImageTemplateGroup.of(new ImageTemplate("outer.png"));
                    static final ImageTemplateGroup INNER = ImageTemplateGroup.of(new ImageTemplate("inner.png"));
                    public void run() {
                        ImageFinder.whileFindAny(OUTER, found -> {
                            ImageFinder.ifFindAny(INNER, other -> {
                                switch (other) {
                                    case Matches m when m.hasAny(new ImageTemplate("inner.png")) -> {
                                    }
                                    default -> {
                                    }
                                }
                            });
                        });
                    }
                }
                """);

        assertEquals(List.of("inner.png"), MatchesGroupScope.allowedPaths(switchIn(fixture)),
                "the nearest enclosing lambda is the one that produced this Matches");
    }
}
