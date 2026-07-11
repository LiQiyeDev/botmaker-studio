import com.botmaker.shared.input.InputEvent;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.BlockType.LibraryCall;
import com.botmaker.studio.palette.Initializer;
import com.botmaker.studio.services.record.MacroTranslator;
import com.botmaker.studio.services.record.MacroTranslator.WindowRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MacroTranslator} — the pure mapping from a recorded {@link InputEvent} stream to leaf
 * blocks. All coordinates are window-relative; the window is at origin (100,100), size 800x600.
 */
public class MacroTranslatorTest {

    private static final WindowRef WINDOW = new WindowRef("Game", 100, 100, 800, 600);

    @Test
    void leftClickInsideWindowBecomesWindowRelativeClick() {
        List<InputEvent> events = List.of(
                new InputEvent.ButtonPress(1, 150, 220, 1000),
                new InputEvent.ButtonRelease(1, 150, 220, 1040));

        List<BlockType> blocks = MacroTranslator.translate(events, WINDOW);

        assertEquals(1, blocks.size());
        LibraryCall call = assertInstanceOf(LibraryCall.class, blocks.get(0));
        assertEquals("Mouse", call.className());
        assertEquals("click", call.method());
        // CaptureSource.window("Game"), 50, 120
        assertEquals(3, call.args().size());
        Initializer.StaticCall src = assertInstanceOf(Initializer.StaticCall.class, call.args().get(0));
        assertEquals("CaptureSource", src.typeName());
        assertEquals("window", src.methodName());
        assertEquals("Game", assertInstanceOf(Initializer.StrLit.class, src.args().get(0)).value());
        assertEquals("50", assertInstanceOf(Initializer.IntLit.class, call.args().get(1)).value());
        assertEquals("120", assertInstanceOf(Initializer.IntLit.class, call.args().get(2)).value());
    }

    @Test
    void printableKeyBurstCoalescesToOneTypeCall() {
        List<InputEvent> events = List.of(
                new InputEvent.KeyPress(0, 'h', 1000),
                new InputEvent.KeyPress(0, 'i', 1010),
                new InputEvent.KeyPress(0, '!', 1020));

        List<BlockType> blocks = MacroTranslator.translate(events, WINDOW);

        assertEquals(1, blocks.size());
        LibraryCall call = assertInstanceOf(LibraryCall.class, blocks.get(0));
        assertEquals("Keyboard", call.className());
        assertEquals("type", call.method());
        assertEquals("hi!", assertInstanceOf(Initializer.StrLit.class, call.args().get(0)).value());
    }

    @Test
    void backspaceEditsTheTypingBuffer() {
        List<InputEvent> events = List.of(
                new InputEvent.KeyPress(0, 'a', 1000),
                new InputEvent.KeyPress(0, 'b', 1010),
                new InputEvent.KeyPress(0, 0xFF08L, 1020), // BackSpace
                new InputEvent.KeyPress(0, 'c', 1030));

        List<BlockType> blocks = MacroTranslator.translate(events, WINDOW);

        assertEquals(1, blocks.size());
        LibraryCall call = assertInstanceOf(LibraryCall.class, blocks.get(0));
        assertEquals("ac", assertInstanceOf(Initializer.StrLit.class, call.args().get(0)).value());
    }

    @Test
    void namedKeyBecomesKeyboardTap() {
        List<InputEvent> events = List.of(new InputEvent.KeyPress(0, 0xFF0DL, 1000)); // Return

        List<BlockType> blocks = MacroTranslator.translate(events, WINDOW);

        assertEquals(1, blocks.size());
        LibraryCall call = assertInstanceOf(LibraryCall.class, blocks.get(0));
        assertEquals("Keyboard", call.className());
        assertEquals("tap", call.method());
        Initializer.EnumConst key = assertInstanceOf(Initializer.EnumConst.class, call.args().get(0));
        assertEquals("Key", key.typeName());
        assertEquals("ENTER", key.constant());
    }

    @Test
    void idleGapInsertsAWaitBetweenGestures() {
        List<InputEvent> events = List.of(
                new InputEvent.ButtonPress(1, 150, 150, 1000),
                new InputEvent.ButtonRelease(1, 150, 150, 1040),
                new InputEvent.ButtonPress(1, 160, 160, 2100),
                new InputEvent.ButtonRelease(1, 160, 160, 2140));

        List<BlockType> blocks = MacroTranslator.translate(events, WINDOW);

        assertEquals(3, blocks.size());
        assertEquals("click", ((LibraryCall) blocks.get(0)).method());
        LibraryCall wait = assertInstanceOf(LibraryCall.class, blocks.get(1));
        assertEquals("Wait", wait.className());
        assertEquals("milliseconds", wait.method());
        // gap = 2140 - 1040 = 1100, rounded to nearest 100 = 1100
        assertEquals("1100", assertInstanceOf(Initializer.IntLit.class, wait.args().get(0)).value());
        assertEquals("click", ((LibraryCall) blocks.get(2)).method());
    }

    @Test
    void clickOutsideWindowIsDropped() {
        List<InputEvent> events = List.of(
                new InputEvent.ButtonPress(1, 10, 10, 1000),   // above/left of the window origin (100,100)
                new InputEvent.ButtonRelease(1, 10, 10, 1040));

        assertTrue(MacroTranslator.translate(events, WINDOW).isEmpty());
    }

    @Test
    void leftDragIsSuppressedNotEmittedAsClick() {
        List<InputEvent> events = new ArrayList<>(List.of(
                new InputEvent.ButtonPress(1, 150, 150, 1000),
                new InputEvent.Motion(180, 190, 1020),
                new InputEvent.ButtonRelease(1, 180, 190, 1040)));

        assertTrue(MacroTranslator.translate(events, WINDOW).isEmpty());
    }
}
