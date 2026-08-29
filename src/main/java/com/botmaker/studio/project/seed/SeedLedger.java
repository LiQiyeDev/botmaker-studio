package com.botmaker.studio.project.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which file was written for which seed instance, remembered across sessions.
 *
 * <p>One line of state, and it exists for one moment: <b>the user renames the thing a seed stands for.</b>
 * A plugin hands over a stable {@code key} with every instance, so a host that remembers the file it wrote
 * for that key last time can tell a rename from a deletion plus a creation. Without it the two are
 * indistinguishable — an activity called {@code Mining} disappears and one called {@code Smelting} appears —
 * and the only safe response to that is to orphan the file the user wrote their {@code run()} body into and
 * give them an empty one.
 *
 * <p><b>Rows are nested under the plugin, not joined into one key.</b> A key is only unique within the plugin
 * that minted it, and two plugins may both call something {@code "activity:1"} without either being wrong. A
 * joined key needs a separator neither a plugin id nor a key can contain — and any character a key could
 * contain would let one plugin read another's rows, while one that certainly cannot (a control character)
 * makes the file unreadable, which defeats keeping it as text. A map of maps has no separator to get wrong.
 *
 * <h2>Where it lives, and why not in activities.json</h2>
 *
 * <p>{@code .botmaker/seeds.json}, beside the other things Studio knows about a project and the user does
 * not. It is emphatically not part of any plugin's own file: {@code activities.json} belongs to the SDK,
 * which cannot be asked to store a fact about how a <em>host</em> laid out files — and a second plugin's rows
 * would have nowhere to go.
 *
 * <h2>Losing it is recoverable</h2>
 *
 * <p>A missing or unreadable ledger reads as empty, and an empty ledger makes every seed look new. That is
 * the same outcome as the behaviour before it existed and destroys nothing: a file already sitting where a
 * seed wants to land is never overwritten. So this is an optimisation on top of a correct-if-blunt default,
 * which is why no read here throws.
 */
public final class SeedLedger {

    /** Project-relative, under the directory Studio already keeps its own per-project state in. */
    public static final String PATH = ".botmaker/seeds.json";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Plugin id to (key to project-relative path). Sorted, so a diff of this file reads as a diff. */
    private final Map<String, Map<String, String>> byPlugin;

    private SeedLedger(Map<String, Map<String, String>> byPlugin) {
        this.byPlugin = byPlugin;
    }

    /** An empty ledger — what a project that has never had one reads as. */
    public static SeedLedger empty() {
        return new SeedLedger(new TreeMap<>());
    }

    /**
     * The ledger under {@code projectDir}, or an empty one.
     *
     * <p>Never throws and never reports: a project whose ledger is gone is a project whose seeds all look
     * new, which is correct if blunt and is exactly what every project had before this file existed.
     */
    public static SeedLedger read(Path projectDir) {
        Path file = projectDir.resolve(PATH);
        if (!Files.isRegularFile(file)) return empty();
        try {
            Map<?, ?> raw = MAPPER.readValue(Files.readString(file), Map.class);
            Map<String, Map<String, String>> byPlugin = new TreeMap<>();
            raw.forEach((plugin, rows) -> {
                if (!(plugin instanceof String id) || !(rows instanceof Map<?, ?> map)) return;
                Map<String, String> paths = new TreeMap<>();
                map.forEach((key, path) -> {
                    if (key instanceof String k && path instanceof String p) paths.put(k, p);
                });
                if (!paths.isEmpty()) byPlugin.put(id, paths);
            });
            return new SeedLedger(byPlugin);
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    /** Writes it back, creating {@code .botmaker/} if this is the first time. */
    public void write(Path projectDir) throws IOException {
        Path file = projectDir.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, MAPPER.writeValueAsString(byPlugin));
    }

    /** The file written for this instance last time, or {@code null} — which means it is new. */
    public String pathFor(String pluginId, String key) {
        Map<String, String> paths = byPlugin.get(pluginId);
        return paths == null ? null : paths.get(key);
    }

    /** Records that {@code path} is this instance's file. */
    public void put(String pluginId, String key, String path) {
        byPlugin.computeIfAbsent(pluginId, id -> new TreeMap<>()).put(key, path);
    }

    /**
     * Forgets one instance.
     *
     * <p>The file itself is not deleted and never will be by this class: a seed is written once and is the
     * user's from that moment, so what is left behind when an activity is deleted is their source, not
     * BotMaker's litter. All that goes is the claim that BotMaker put it there.
     */
    public void remove(String pluginId, String key) {
        Map<String, String> paths = byPlugin.get(pluginId);
        if (paths == null) return;
        paths.remove(key);
        if (paths.isEmpty()) byPlugin.remove(pluginId);
    }

    /** Whether anything has been recorded at all. */
    public boolean isEmpty() {
        return byPlugin.isEmpty();
    }
}
