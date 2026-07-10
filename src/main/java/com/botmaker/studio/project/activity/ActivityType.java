package com.botmaker.studio.project.activity;

import com.botmaker.studio.types.ResolvedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * The fixed, curated set of types an {@link ActivityVariable} can have. Each constant maps to a
 * concrete Java type and knows how to: present itself in pickers ({@link #displayName()}), declare a
 * field of its Java type ({@link #javaType()}), produce the runtime expression that parses its value
 * out of a Jackson {@link JsonNode} ({@link #loadExpression(String)}), and supply a sensible default
 * JSON value ({@link #defaultValue()}). It also exposes a {@link ResolvedType} so the expression menu
 * can type-filter activities against an expected slot type.
 */
public enum ActivityType {
    BOOL("Yes / No", "boolean", ResolvedType.primitive("boolean")) {
        public String loadExpression(String node) { return node + ".asBoolean(false)"; }
        public JsonNode defaultValue() { return FACTORY.booleanNode(false); }
    },
    INT("Whole number", "int", ResolvedType.primitive("int")) {
        public String loadExpression(String node) { return node + ".asInt(0)"; }
        public JsonNode defaultValue() { return FACTORY.numberNode(0); }
    },
    DOUBLE("Decimal number", "double", ResolvedType.primitive("double")) {
        public String loadExpression(String node) { return node + ".asDouble(0.0)"; }
        public JsonNode defaultValue() { return FACTORY.numberNode(0.0); }
    },
    TEXT("Text", "String", ResolvedType.named("java.lang.String")) {
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
}
