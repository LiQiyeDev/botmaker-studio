package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditorFixture;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second half of the combination-switch seed: {@link LambdaCallHandler#seedIfReady}, run when the group
 * argument is filled in.
 *
 * <p>{@code switchVariant} covers the other order (pick the images, then choose the group form) and is tested
 * in {@code LambdaVariantSwitchTest}. It could never cover this one: a freshly dropped find block's image slot
 * is a {@code null} literal, so choosing {@code ifFindAny} first has no template to seed a guard from and
 * correctly declines — and until this hook existed, picking the images afterwards triggered nothing at all.
 * That was the empty body the feature was reported as producing.
 */
class GroupSlotSeedTest {

    /** A find call with an unfilled image slot — the shape a freshly dropped block has. */
    private static String freshCall(String method, String param, String body) {
        return """
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageFinder.%s(null, %s -> {%s});
                    }
                }
                """.formatted(method, param, body);
    }

    /** The call's leading image argument — the slot the group picker replaces. */
    private static Expression imageSlot(EditorFixture fixture, String method) {
        List<MethodInvocation> found = new ArrayList<>();
        fixture.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) {
                if (node.getName().getIdentifier().equals(method)) found.add(node);
                return true;
            }
        });
        assertFalse(found.isEmpty(), "fixture should contain " + method);
        return (Expression) found.getFirst().arguments().getFirst();
    }

    private static String dense(String code) {
        return code.replaceAll("\\s+", "");
    }

    /**
     * The order the user actually works in: choose the group form, then pick the images. The switch appears
     * when the second half lands, seeded from the first template of what was just picked — which has to be
     * passed in, since the AST still holds the slot being replaced.
     */
    @Test
    void pickingTheGroupSeedsTheCombinationSwitch() {
        for (String method : List.of("ifFindAny", "ifFindAll", "whileFindAny", "whileFindAll")) {
            EditorFixture fixture = new EditorFixture(freshCall(method, "found", ""));

            fixture.editor.setImageTemplateGroup(imageSlot(fixture, method),
                    List.of("popups/mail.png", "popups/gift.png"));

            assertNotNull(fixture.lastCode, method + " published nothing");
            String dense = dense(fixture.lastCode);
            assertAll(
                    () -> assertTrue(dense.contains("switch(found)"),
                            () -> method + " did not seed the switch: " + fixture.lastCode),
                    () -> assertTrue(dense.contains("m.hasAny(newImageTemplate(\"popups/mail.png\"))"),
                            () -> method + " seeded the wrong template: " + fixture.lastCode),
                    () -> assertTrue(dense.contains("ImageTemplateGroup.of("),
                            () -> method + " lost the group it was picking: " + fixture.lastCode));
        }
    }

    /**
     * The seed is idempotent because "the body is empty" is one of its conditions — so a user who deletes the
     * switch and then re-picks the images is not handed it back. Nothing else stops the hook re-running: it
     * fires on every group pick, for the life of the call.
     */
    @Test
    void aBodyWithAnythingInItIsNeverSeeded() {
        EditorFixture fixture = new EditorFixture(
                freshCall("ifFindAny", "found", "\n            ImageClicker.click(10, 20);\n        "));

        fixture.editor.setImageTemplateGroup(imageSlot(fixture, "ifFindAny"), List.of("popups/mail.png"));

        assertNotNull(fixture.lastCode);
        assertAll(
                () -> assertTrue(fixture.lastCode.contains("ImageClicker.click(10, 20)"),
                        () -> "the user's body must survive: " + fixture.lastCode),
                () -> assertFalse(fixture.lastCode.contains("switch"),
                        () -> "an occupied body must not be seeded: " + fixture.lastCode));
    }

    /**
     * {@code untilFind…} loops <em>until</em> something is found and hands the body a {@link Runnable} — there
     * is no {@code Matches} to switch over, so a group argument alone is not enough to seed. This is the
     * exclusion that makes the seed set narrower than "takes a group".
     */
    @Test
    void aParameterlessGroupFormIsNotSeeded() {
        EditorFixture fixture = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageFinder.untilFindAny(null, () -> {});
                    }
                }
                """);

        fixture.editor.setImageTemplateGroup(imageSlot(fixture, "untilFindAny"), List.of("popups/mail.png"));

        assertNotNull(fixture.lastCode, "the group itself must still be written");
        assertAll(
                () -> assertTrue(dense(fixture.lastCode).contains("ImageTemplateGroup.of("),
                        () -> "the group must be written: " + fixture.lastCode),
                () -> assertFalse(fixture.lastCode.contains("switch"),
                        () -> "a Runnable body has no Matches to switch on: " + fixture.lastCode));
    }

    /** A group written anywhere else is just a group — the hook only fires on a find call's leading slot. */
    @Test
    void aGroupWrittenOutsideAFindCallSeedsNothing() {
        EditorFixture fixture = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageTemplateGroup popups = null;
                    }
                }
                """);

        List<Expression> initializer = new ArrayList<>();
        fixture.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override public boolean visit(org.eclipse.jdt.core.dom.VariableDeclarationFragment node) {
                initializer.add(node.getInitializer());
                return true;
            }
        });

        fixture.editor.setImageTemplateGroup(initializer.getFirst(), List.of("popups/mail.png"));

        assertNotNull(fixture.lastCode);
        assertFalse(fixture.lastCode.contains("switch"),
                () -> "there is no lambda body to seed here: " + fixture.lastCode);
    }
}
