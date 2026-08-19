package com.botmaker.studio.palette;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curated type list: what it contains, and — more usefully — what it leaves out.
 *
 * <p>The list is an allow-list over {@link SdkType} rather than a filter of it, so this test's job is to fail
 * when someone widens it by accident. The absences are the whole design: a bot author choosing a variable type
 * should not be offered {@code BotStuckException}, and should not be offered the six {@code CaptureSource}
 * implementations when the capture-target dialog is what picks between those.
 */
class BotTypeTest {

    @Test
    void everySdkEntryNamesARealSdkType() {
        // The compiler already guarantees this — the point of asserting it is the reverse direction below.
        for (BotType type : BotType.values()) {
            type.sdkType().ifPresent(sdk -> assertEquals(sdk.simpleName(), type.typeName()));
        }
    }

    @Test
    void theSdkTypesDeliberatelyLeftOutStayOut() {
        Set<SdkType> offered = BotType.declarableTypes().stream()
                .flatMap(t -> t.sdkType().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // Plumbing, the target dialogs' business, and the observation callbacks. If one of these ever belongs
        // in a variable declaration, that is a decision to take deliberately — not by widening a filter.
        for (SdkType excluded : List.of(SdkType.BOT_MAKER, SdkType.BOT_STUCK_EXCEPTION, SdkType.START_MODE,
                SdkType.DESKTOP, SdkType.MONITOR, SdkType.NAMED_WINDOW, SdkType.SCREEN, SdkType.SESSION_SOURCE,
                SdkType.EMULATOR_SOURCE, SdkType.EMULATOR, SdkType.EMULATOR_REF, SdkType.LAUNCH_TARGET,
                SdkType.SURFACE, SdkType.CLICK_EVENT, SdkType.MATCH_EVENT, SdkType.BOT_OBSERVER,
                SdkType.SESSION, SdkType.TIME)) {
            assertFalse(offered.contains(excluded), excluded + " should not be offered as a bot type");
        }
        // The interface is offered; its implementations are not.
        assertTrue(offered.contains(SdkType.CAPTURE_SOURCE));
    }

    @Test
    void everyDeclarableTypeSeedsAValueThatCompiles() {
        // The reason the declare menu can offer eighteen types: each one knows what a fresh value of it looks
        // like, so dropping the block never produces a null the user has to notice.
        for (BotType type : BotType.declarableTypes()) {
            assertTrue(type.defaultValue().isPresent(), type + " needs a default value");
            assertNotNull(type.suggestedName(), type + " needs a variable name");
        }
        assertTrue(BotType.NOTHING.defaultValue().isEmpty(), "void is a return type, not a variable");
        assertFalse(BotType.NOTHING.declarable());
    }

    @Test
    void aListOfAPrimitiveIsWrittenWithTheBox() {
        assertEquals("List<Integer>", BotType.Choice.listOf(BotType.WHOLE_NUMBER).sourceName());
        assertEquals("int", BotType.Choice.of(BotType.WHOLE_NUMBER).sourceName());
        assertEquals("List<Point>", BotType.Choice.listOf(BotType.POINT).sourceName());
        assertEquals("List of Point", BotType.Choice.listOf(BotType.POINT).label());
    }

    @Test
    void thereIsNoListOfNothing() {
        assertThrows(IllegalArgumentException.class, () -> BotType.Choice.listOf(BotType.NOTHING));
        assertTrue(BotType.Choice.of(BotType.NOTHING).isVoid());
    }

    /**
     * The two set-shapes are not the same question. {@code List<T>} is a type a signature can name, so it
     * needs only a box; "one of a declared set" is a restriction on a value somebody configures, so it needs
     * a type somebody can configure. Conflating them refused {@code List<MatchResult>} as a return type.
     */
    @Test
    void aListOfAResultIsATypeButAChoiceOfOneIsNotASentence() {
        assertEquals("List<MatchResult>", BotType.Choice.listOf(BotType.MATCH_RESULT).sourceName());
        assertThrows(IllegalArgumentException.class,
                () -> new BotType.Choice(BotType.MATCH_RESULT, BotType.Shape.ONE_OF));
    }

    /** ONE_OF is a restriction the editor keeps to itself: the bot sees the bare type. */
    @Test
    void restrictingWhichValuesAreOfferedChangesNothingInTheSource() {
        BotType.Choice free = BotType.Choice.of(BotType.WHOLE_NUMBER);
        BotType.Choice restricted = new BotType.Choice(BotType.WHOLE_NUMBER, BotType.Shape.ONE_OF);

        assertEquals(free.sourceName(), restricted.sourceName());
        assertFalse(free.hasOptions());
        assertTrue(restricted.hasOptions());
        assertEquals("One of Whole number", restricted.label());
    }

    @Test
    void theDeclareMenuOffersTheCuratedTypesAndNotFive() {
        // What "Declare Bot Variable" used to hold: Point, Rect, Size, MatchResult, ImageTemplate. The menu is
        // generated from this list now, so the two features cannot know different sets of types.
        List<BlockType> declares = BlockCatalog.all().stream()
                .filter(b -> b.category() == BlockCategory.BOT_VARIABLE)
                .toList();

        long expected = BotType.declarableTypes().stream()
                .filter(t -> t.group() != BotType.Group.BASICS).count();
        assertEquals(expected, declares.size());
        assertTrue(declares.size() > 5, "the point of the change was that it is no longer five");
        assertTrue(declares.contains(BlockCatalog.DECLARE_POINT));
    }
}
