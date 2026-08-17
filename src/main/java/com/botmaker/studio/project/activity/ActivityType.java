package com.botmaker.studio.project.activity;

import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.ResolvedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

/**
 * The fixed, curated set of types an {@link ActivityVariable} can have. Each constant maps to a
 * concrete Java type and knows how to: present itself in pickers ({@link #displayName()}), declare a
 * field of its Java type ({@link #javaType()}), produce the runtime expression that parses its value
 * out of a Jackson {@link JsonNode} ({@link #loadExpression(String)}), and supply a sensible default
 * JSON value ({@link #defaultValue()}). It also exposes a {@link ResolvedType} so the expression menu
 * can type-filter activities against an expected slot type.
 */
public enum ActivityType {
    BOOL("Yes / No", "boolean", ResolvedType.BOOLEAN) {
        public String loadExpression(String node) { return node + ".asBoolean(false)"; }
        public JsonNode defaultValue() { return FACTORY.booleanNode(false); }
    },
    INT("Whole number", "int", ResolvedType.INT) {
        public String loadExpression(String node) { return node + ".asInt(0)"; }
        public JsonNode defaultValue() { return FACTORY.numberNode(0); }
    },
    DOUBLE("Decimal number", "double", ResolvedType.DOUBLE) {
        public String loadExpression(String node) { return node + ".asDouble(0.0)"; }
        public JsonNode defaultValue() { return FACTORY.numberNode(0.0); }
    },
    TEXT("Text", "String", ResolvedType.of(JdkType.STRING)) {
        public String loadExpression(String node) { return node + ".asText(\"\")"; }
        public JsonNode defaultValue() { return FACTORY.textNode(""); }
    },
    TIME("Time of day", "java.time.LocalTime", ResolvedType.named("java.time.LocalTime")) {
        // Defensive: parse via a generated helper so a present-but-invalid/wrong-type node can't
        // throw at bot startup (see ActivityService.generateSource → parseTime).
        public String loadExpression(String node) { return "parseTime(" + node + ")"; }
        public JsonNode defaultValue() { return FACTORY.textNode("00:00"); }
    },
    DATE("Date", "java.time.LocalDate", ResolvedType.named("java.time.LocalDate")) {
        public String loadExpression(String node) { return "parseDate(" + node + ")"; }
        public JsonNode defaultValue() { return FACTORY.textNode("2000-01-01"); }
    },
    /**
     * One of a declared list of choices, generated as the chosen {@code String}. The list is
     * {@link ActivityVariable#options()}; the generated field is a plain {@code String}, so a bot compares it
     * with {@code equals} and nothing about the option set leaks into the generated code.
     */
    CHOICE("Choice", "String", ResolvedType.of(JdkType.STRING)) {
        public String loadExpression(String node) { return node + ".asText(\"\")"; }
        public JsonNode defaultValue() { return FACTORY.textNode(""); }
    },
    /**
     * Any number of a declared list of choices, generated as an immutable {@code List<String>}. Fully
     * qualified in {@link #javaType()} for the same reason {@link #TIME} is — the generated {@code Activities}
     * class has a fixed import block, and a type that needs no import cannot be forgotten from it.
     */
    MULTI_CHOICE("Multiple choice", "java.util.List<String>", ResolvedType.named("java.util.List")) {
        // Via a generated helper, like TIME/DATE: a present-but-wrong-shaped node (a string where an array
        // belongs) must degrade to "nothing selected", not throw while the bot is starting up.
        public String loadExpression(String node) { return "parseChoices(" + node + ")"; }
        public JsonNode defaultValue() { return FACTORY.arrayNode(); }
    };

    static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private final String displayName;
    private final String javaType;
    private final ResolvedType resolvedType;

    ActivityType(String displayName, String javaType, ResolvedType resolvedType) {
        this.displayName = displayName;
        this.javaType = javaType;
        this.resolvedType = resolvedType;
    }

    /** Human label for type pickers (e.g. "Whole number"). */
    public String displayName() { return displayName; }

    /** The Java type used when declaring the generated static field (e.g. {@code int}). */
    public String javaType() { return javaType; }

    /** The resolved type used for expression-menu type filtering. */
    public ResolvedType resolvedType() { return resolvedType; }

    /**
     * The Java expression (as source text) that parses this type's value from {@code node}, a
     * {@code JsonNode} reference. Used by the generated {@code Activities} class.
     */
    public abstract String loadExpression(String node);

    /** A sensible default JSON value for a freshly created activity of this type. */
    public abstract JsonNode defaultValue();

    /** True for the types whose value is picked from {@link ActivityVariable#options() a declared list}. */
    public boolean hasOptions() {
        return this == CHOICE || this == MULTI_CHOICE;
    }

    /**
     * {@code value} with anything that is not in {@code options} removed — the identity for every type that
     * has no options. Called when the editor edits the option list, so deleting a choice also unsets it
     * wherever it was chosen rather than leaving the bot running on a setting the UI no longer shows.
     */
    public JsonNode pruneValue(JsonNode value, List<String> options) {
        if (!hasOptions()) return value;
        if (this == CHOICE) {
            String chosen = value == null ? "" : value.asText("");
            return options.contains(chosen) ? value : FACTORY.textNode(options.isEmpty() ? "" : options.getFirst());
        }
        ArrayNode kept = FACTORY.arrayNode();
        if (value != null && value.isArray()) {
            for (JsonNode n : value) {
                if (options.contains(n.asText(""))) kept.add(n.asText(""));
            }
        }
        return kept;
    }
}
