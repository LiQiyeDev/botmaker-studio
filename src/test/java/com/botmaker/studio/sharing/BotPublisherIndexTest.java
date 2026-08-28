package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What publishing writes into the gallery, now that it writes one file.
 *
 * <p>The tests this replaced covered {@code mergeEntry} and {@code removeEntry} — the read-modify-write of
 * the whole {@code index.json} array, deleted with them. Appending to a shared array made every concurrent
 * submission a merge conflict and every delisting a whole-file diff; an entry's path is its identity now, so
 * there is no merge left to test. What is left is the path itself and the bytes of one file.
 */
class BotPublisherIndexTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void the_entry_path_is_the_bot_identity() {
        assertEquals("bots/alice-Koala.json", GitHubConfig.entryPath("alice", "Koala"));
    }

    /** Two bots, two paths: the property the layout exists for, stated where it is decided. */
    @Test
    void two_bots_never_share_a_path() {
        assertTrue(!GitHubConfig.entryPath("alice", "Koala")
                .equals(GitHubConfig.entryPath("bob", "Koala")));
    }

    @Test
    void an_entry_file_is_pretty_printed_and_newline_terminated() throws Exception {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "Koala");
        entry.put("owner", "alice");
        entry.put("repo", "Koala");
        entry.put("description", "Test");
        entry.put("tags", List.of("mining"));

        String json = new String(BotPublisher.entryJson(mapper, entry), StandardCharsets.UTF_8);

        assertTrue(json.endsWith("}\n"), json);
        assertTrue(json.contains("\n  "), "an entry is reviewed in a pull request, so it is indented:\n" + json);
        // Read back rather than string-matched: the file has one reader that matters, and it is a parser.
        Map<?, ?> parsed = mapper.readValue(json, Map.class);
        assertEquals("alice", parsed.get("owner"));
        assertEquals(List.of("mining"), parsed.get("tags"));
    }

    /** Field order is the insertion order, so a re-publish diffs as a change and not as a reshuffle. */
    @Test
    void field_order_is_stable() throws Exception {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "Koala");
        entry.put("owner", "alice");
        entry.put("repo", "Koala");

        String json = new String(BotPublisher.entryJson(mapper, entry), StandardCharsets.UTF_8);

        assertTrue(json.indexOf("\"name\"") < json.indexOf("\"owner\""), json);
        assertTrue(json.indexOf("\"owner\"") < json.indexOf("\"repo\""), json);
    }
}
