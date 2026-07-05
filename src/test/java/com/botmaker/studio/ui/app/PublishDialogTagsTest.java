package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublishDialogTagsTest {

    @Test
    void emptyForNullOrBlank() {
        assertEquals(List.of(), PublishDialog.parseTags(null));
        assertEquals(List.of(), PublishDialog.parseTags("   "));
        assertEquals(List.of(), PublishDialog.parseTags(",, ,"));
    }

    @Test
    void trimsSplitsAndDropsBlanks() {
        assertEquals(List.of("clicker", "farming"), PublishDialog.parseTags(" clicker , farming "));
        assertEquals(List.of("a", "b"), PublishDialog.parseTags("a,,b,"));
    }

    @Test
    void dedupesPreservingOrder() {
        assertEquals(List.of("clicker", "bot"), PublishDialog.parseTags("clicker, bot, clicker"));
    }
}
