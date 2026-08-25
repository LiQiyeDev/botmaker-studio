package com.botmaker.studio.project.migration;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * A project data file that carries a schema version, and the two operations a version needs: reading the one
 * on disk, and stamping the current one.
 *
 * <p>Three files, and they stay three. The split follows a real boundary rather than a filing convenience:
 * {@link #PROPERTIES} is a <em>runtime contract</em> — its keys are declared in {@code botmaker-shared} and
 * the SDK parses them inside the running bot; {@link #ACTIVITIES} is the <em>model</em>, which both Studio and
 * the generated code read; {@link #SETTINGS} is <em>editor state</em> the bot never opens. Each therefore
 * changes at its own pace, and a version per file lets it: bumping the model does not oblige the properties
 * file to claim it changed.
 *
 * <p><b>Absent means 0.</b> Every project that exists today predates the marker, so a missing version is not
 * an error and not "unknown" — it is the oldest shape, and the migration steps from 0 are exactly the ones
 * written to bring that shape forward. This is the whole reason the numbering starts where it does.
 *
 * <p><b>The current version is not written down twice.</b> {@link #current()} is the number of migration steps
 * {@link SchemaMigrations} holds for this file — step <i>i</i> takes version <i>i</i> to <i>i+1</i>, so "how
 * new is this shape" and "how many steps reach it" are the same number by construction. Adding a step bumps
 * the version; there is no constant to forget to bump beside it.
 */
public enum SchemaFile {

    /** {@code activities.json} — the activity/variable model, read by Studio and by the generated code. */
    ACTIVITIES(ActivitiesConfig.FILE_NAME, Format.JSON, "activity model"),

    /** {@code settings.json} — per-project editor state (capture targets, overlay position, layout). */
    SETTINGS(StudioProjectSettings.FILE_NAME, Format.JSON, "editor settings"),

    /** {@code botmaker-project.properties} — the runtime contract the SDK reads inside the bot. */
    PROPERTIES(ProjectProperties.FILE_NAME, Format.PROPERTIES, "project properties");

    /** How the version is spelled inside the file. */
    private enum Format { JSON, PROPERTIES }

    /** The JSON member holding the version. Written first, so it is the first line a human reads. */
    public static final String JSON_FIELD = "schemaVersion";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String fileName;
    private final Format format;
    private final String description;

    SchemaFile(String fileName, Format format, String description) {
        this.fileName = fileName;
        this.format = format;
        this.description = description;
    }

    /** The file's name inside the project's {@code src/main/resources}. */
    public String fileName() {
        return fileName;
    }

    /** A short human phrase for a refusal message ("activity model", …). */
    public String description() {
        return description;
    }

    /** The version this Studio writes: one per migration step it knows. See the class note. */
    public int current() {
        return SchemaMigrations.stepsFor(this).size();
    }

    /** This file inside {@code resourcesDir}. */
    public Path in(Path resourcesDir) {
        return resourcesDir.resolve(fileName);
    }

    /**
     * The version recorded in the file, or empty when the file is not there.
     *
     * <p>Present-but-unstamped reads as {@code 0} — that is the "absent means 0" rule and it is deliberately
     * <em>not</em> the same as "no file". A file that does not exist has no shape to migrate, so the caller
     * skips it rather than stamping one it never wrote. An unparseable file also reads as empty: a migration
     * pass is not the place to discover a hand-edit is broken, and every reader here already falls back to
     * defaults on a bad parse.
     */
    public OptionalInt versionIn(Path resourcesDir) {
        Path file = in(resourcesDir);
        if (!Files.exists(file)) return OptionalInt.empty();
        try {
            return switch (format) {
                case JSON -> {
                    var node = MAPPER.readTree(file.toFile());
                    var value = node == null ? null : node.get(JSON_FIELD);
                    yield OptionalInt.of(value != null && value.isInt() ? value.asInt() : 0);
                }
                case PROPERTIES -> {
                    Properties props = new Properties();
                    try (var in = Files.newInputStream(file)) { props.load(in); }
                    yield OptionalInt.of(parse(props.getProperty(ProjectProperties.KEY_SCHEMA_VERSION)));
                }
            };
        } catch (Exception e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Records {@link #current()} in the file, leaving everything else in it alone. Does nothing when the file
     * is absent — see {@link #versionIn}; the next ordinary write stamps it, because every writer of these
     * three files goes through {@link #stamped} or {@link #stamp}.
     */
    public void stampIfPresent(Path resourcesDir) throws IOException {
        Path file = in(resourcesDir);
        if (!Files.exists(file)) return;
        switch (format) {
            case JSON -> {
                var read = MAPPER.readTree(file.toFile());
                ObjectNode body = read instanceof ObjectNode o ? o : MAPPER.createObjectNode();
                body.remove(JSON_FIELD);
                MAPPER.writeValue(file.toFile(), stamped(body));
            }
            case PROPERTIES -> {
                Properties props = new Properties();
                try (var in = Files.newInputStream(file)) { props.load(in); }
                stamp(props);
                try (var out = Files.newOutputStream(file)) {
                    props.store(out, "BotMaker project defaults");
                }
            }
        }
    }

    /**
     * {@code body} with this file's current version as its <em>first</em> member — what a JSON writer emits
     * instead of the bare object.
     *
     * <p>Every write stamps, not just a migration's. A record serializes to exactly its components, so a save
     * that did not put the number back would quietly return the file to 0 and the next open would re-run
     * every step against an already-migrated file.
     */
    public ObjectNode stamped(ObjectNode body) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put(JSON_FIELD, current());
        out.setAll(body);
        return out;
    }

    /** Sets this file's current version on {@code props} — the properties-file half of {@link #stamped}. */
    public void stamp(Properties props) {
        props.setProperty(ProjectProperties.KEY_SCHEMA_VERSION, Integer.toString(current()));
    }

    private static int parse(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
