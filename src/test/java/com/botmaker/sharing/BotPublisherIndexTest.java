package com.botmaker.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotPublisherIndexTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> entry(String owner, String repo, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", repo);
        m.put("owner", owner);
        m.put("repo", repo);
        m.put("description", desc);
        m.put("tags", List.of());
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendsNewEntryToEmptyIndex() throws Exception {
        byte[] out = BotPublisher.mergeEntry(mapper, "[]".getBytes(),
                entry("alice", "Koala", "Test"), "alice/Koala");
        List<Map<String, Object>> entries = List.of(mapper.readValue(out, Map[].class));
        assertEquals(1, entries.size());
        assertEquals("alice/Koala", entries.get(0).get("owner") + "/" + entries.get(0).get("repo"));
    }

    @Test
    void dedupesBySlugSoRepublishIsIdempotent() throws Exception {
        String existing = """
                [ {"name":"Koala","owner":"alice","repo":"Koala","description":"old","tags":[]} ]
                """;
        byte[] out = BotPublisher.mergeEntry(mapper, existing.getBytes(),
                entry("alice", "Koala", "new description"), "alice/Koala");

        Map<String, Object>[] entries = mapper.readValue(out, Map[].class);
        assertEquals(1, entries.length, "the duplicate Koala entry should be replaced, not appended");
        assertEquals("new description", entries[0].get("description"));
    }

    @Test
    void keepsOtherEntriesWhenAddingANewOne() throws Exception {
        String existing = """
                [ {"name":"Farmer","owner":"bob","repo":"Farmer","description":"f","tags":[]} ]
                """;
        byte[] out = BotPublisher.mergeEntry(mapper, existing.getBytes(),
                entry("alice", "Koala", "k"), "alice/Koala");

        Map<String, Object>[] entries = mapper.readValue(out, Map[].class);
        assertEquals(2, entries.length);
    }
}
