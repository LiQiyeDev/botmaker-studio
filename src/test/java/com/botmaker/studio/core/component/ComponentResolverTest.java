package com.botmaker.studio.core.component;

import com.botmaker.studio.core.component.ComponentResolver.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict table is the whole contract of the component schema, so it is asserted directly rather than
 * through a rendered block: the resolver is pure and JavaFX-free precisely so these cases can be checked
 * without a toolkit.
 *
 * <p>The case that matters most is the first one — an {@code EDITOR_ONLY} component must stay hidden from a
 * user even when it is perfectly editable, because audience is not a permission level and an unlocked
 * generated member must not leak through.
 */
class ComponentResolverTest {

    /** Suppliers are never invoked by the resolver, so a marker that would fail loudly if one were. */
    private static BlockComponent component(BlockComponent.Visibility visibility,
                                            BlockComponent.WhenLocked whenLocked) {
        return new BlockComponent("field", BlockComponent.Kind.PICKER, visibility, whenLocked,
                () -> { throw new AssertionError("the resolver must not build a node"); });
    }

    private static BlockComponent everyone() {
        return component(BlockComponent.Visibility.EVERYONE, BlockComponent.WhenLocked.SHOW);
    }

    private static BlockComponent editorOnly() {
        return component(BlockComponent.Visibility.EDITOR_ONLY, BlockComponent.WhenLocked.SHOW);
    }

    @Test
    void editorOnlyComponentIsHiddenFromTheUserEvenWhenEditable() {
        assertEquals(Verdict.HIDDEN, ComponentResolver.resolve(editorOnly(), Audience.USER, false));
        assertEquals(Verdict.HIDDEN, ComponentResolver.resolve(editorOnly(), Audience.USER, true));
    }

    @Test
    void editorSeesEditorOnlyComponents() {
        assertEquals(Verdict.SHOWN_EDITABLE, ComponentResolver.resolve(editorOnly(), Audience.EDITOR, false));
        assertEquals(Verdict.SHOWN_LOCKED, ComponentResolver.resolve(editorOnly(), Audience.EDITOR, true));
    }

    @Test
    void lockedIsShownNotHidden() {
        // An activity's pinned return is locked in place yet must stay on screen: locked never implies hidden
        // unless the component asked for it.
        assertEquals(Verdict.SHOWN_LOCKED, ComponentResolver.resolve(everyone(), Audience.USER, true));
        assertEquals(Verdict.SHOWN_EDITABLE, ComponentResolver.resolve(everyone(), Audience.USER, false));
    }

    @Test
    void hiddenWhenLockedDropsTheComponentInsteadOfDimmingIt() {
        BlockComponent c = everyone().hiddenWhenLocked();
        assertEquals(Verdict.HIDDEN, ComponentResolver.resolve(c, Audience.EDITOR, true));
        assertEquals(Verdict.SHOWN_EDITABLE, ComponentResolver.resolve(c, Audience.EDITOR, false));
    }

    @Test
    void nullAudienceIsTreatedAsEditorAndNullComponentIsHidden() {
        assertEquals(Verdict.SHOWN_EDITABLE, ComponentResolver.resolve(editorOnly(), null, false));
        assertEquals(Verdict.HIDDEN, ComponentResolver.resolve(null, Audience.EDITOR, false));
    }

    @Test
    void noResolverOrNoNodeMeansNotLocked() {
        // LockResolver's own convention: "we don't know what project this is" permits everything.
        assertFalse(ComponentResolver.isLocked(null, null, null));
    }

    @Test
    void verdictsExposeTheTwoQuestionsTheRenderPathAsks() {
        assertTrue(Verdict.SHOWN_LOCKED.isVisible());
        assertTrue(Verdict.SHOWN_LOCKED.isReadOnly());
        assertFalse(Verdict.SHOWN_EDITABLE.isReadOnly());
        assertFalse(Verdict.HIDDEN.isVisible());
    }

    @Test
    void aSpecRejectsDuplicateIdsSoAComponentCanAlwaysBeFoundByName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentSpec(java.util.List.of(everyone(), everyone())));

        ComponentSpec spec = ComponentSpec.builder().add(everyone()).build();
        assertTrue(spec.find("field").isPresent());
        assertTrue(spec.find("absent").isEmpty());
        assertTrue(ComponentSpec.empty().components().isEmpty());
    }

    @Test
    void aComponentMustDeclareEveryField() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockComponent(" ", BlockComponent.Kind.LABEL, BlockComponent.Visibility.EVERYONE,
                        BlockComponent.WhenLocked.SHOW, () -> null));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockComponent("id", BlockComponent.Kind.LABEL, BlockComponent.Visibility.EVERYONE,
                        BlockComponent.WhenLocked.SHOW, null));
    }
}
