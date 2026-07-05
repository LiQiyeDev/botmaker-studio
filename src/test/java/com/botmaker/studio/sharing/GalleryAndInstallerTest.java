package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryAndInstallerTest {

    @Test
    void indexJsonDeserializesToEntriesIgnoringUnknownFields() throws Exception {
        String json = """
                [
                  {"name":"ClickerBot","owner":"alice","repo":"clicker-bot",
                   "description":"auto clicker","tags":["clicker","afk"],"extraneous":true},
                  {"name":"Farmer","owner":"bob","repo":"farmer"}
                ]
                """;
        List<GalleryEntry> entries = List.of(new ObjectMapper().readValue(json, GalleryEntry[].class));

        assertEquals(2, entries.size());
        assertEquals("alice/clicker-bot", entries.get(0).slug());
        assertEquals("https://github.com/alice/clicker-bot", entries.get(0).htmlUrl());
        assertEquals(List.of("clicker", "afk"), entries.get(0).tags());
        assertTrue(entries.get(1).tags().isEmpty());
    }

    @Test
    void entryMatchesQueryAcrossFields() {
        GalleryEntry e = new GalleryEntry("ClickerBot", "alice", "clicker-bot", "auto clicker", List.of("afk"));
        assertTrue(e.matches(""));
        assertTrue(e.matches("clicker"));
        assertTrue(e.matches("ALICE"));
        assertTrue(e.matches("afk"));
        assertTrue(e.matches("auto"));
        assertTrue(!e.matches("zzz"));
    }

    @Test
    void sanitizeNameProducesValidProjectName() {
        assertEquals("ClickerBot", BotInstaller.sanitizeName("ClickerBot"));
        assertEquals("ClickerBot", BotInstaller.sanitizeName("clicker-bot"));
        assertEquals("Bot123", BotInstaller.sanitizeName("123"));
        assertEquals("ImportedBot", BotInstaller.sanitizeName("---"));
    }

    @Test
    void botSourceRoundTrips(@TempDir Path dir) throws Exception {
        new BotSource("alice", "clicker-bot", "1.2.0").write(dir);
        Optional<BotSource> read = BotSource.read(dir);
        assertTrue(read.isPresent());
        assertEquals("alice/clicker-bot", read.get().slug());
        assertEquals("1.2.0", read.get().tag());
    }
}
