package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.ActivityVariable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the value-entry widget for one {@link ActivityVariable}, seeded from its current value, and hands
 * back a reader turning the widget's live state into a {@link JsonNode}. Reading is total: an unparseable
 * entry falls back to the type's default rather than failing, matching the generated {@code Activities}
 * loader (which also degrades to defaults instead of crashing the bot).
 *
 * <p>Shared by every value editor so a type is rendered the same way everywhere.
 */
public final class ActivityValueWidgets {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ActivityValueWidgets() {}

    /** A value widget's variable plus a reader turning its UI state back into a {@link JsonNode}. */
    public record ValueEditor(ActivityVariable variable, Supplier<JsonNode> read) {

        /** The variable with the widget's current value applied — what gets persisted. */
        public ActivityVariable readVariable() {
            return variable.withValue(read.get());
        }
    }

    /** Builds the widget for {@code a}, seeded from its current value, registering its reader in {@code sink}. */
    public static Node build(ActivityVariable a, List<ValueEditor> sink) {
        JsonNode current = a.value();
        Node widget;
        Supplier<JsonNode> reader;
        switch (a.type()) {
            case BOOL -> {
                CheckBox cb = new CheckBox();
                cb.setSelected(current != null && current.asBoolean(false));
                widget = cb;
                reader = () -> NODES.booleanNode(cb.isSelected());
            }
            case INT -> {
                TextField tf = new TextField(current != null ? String.valueOf(current.asInt(0)) : "0");
                widget = tf;
                reader = () -> NODES.numberNode(parseIntOr(tf.getText(), 0));
            }
            case DOUBLE -> {
                TextField tf = new TextField(current != null ? String.valueOf(current.asDouble(0.0)) : "0.0");
                widget = tf;
                reader = () -> NODES.numberNode(parseDoubleOr(tf.getText(), 0.0));
            }
            case TIME -> {
                TextField tf = new TextField(current != null ? current.asText("00:00") : "00:00");
                tf.setPromptText("HH:mm");
                widget = tf;
                reader = () -> NODES.textNode(normalizeTime(tf.getText()));
            }
            case DATE -> {
                DatePicker dp = new DatePicker();
                dp.setValue(parseDateOr(current != null ? current.asText(null) : null, LocalDate.now()));
                widget = dp;
                reader = () -> NODES.textNode(
                        dp.getValue() != null ? dp.getValue().toString() : LocalDate.now().toString());
            }
            default -> { // TEXT
                TextField tf = new TextField(current != null ? current.asText("") : "");
                widget = tf;
                reader = () -> NODES.textNode(tf.getText() == null ? "" : tf.getText());
            }
        }
        if (widget instanceof TextField || widget instanceof DatePicker) {
            ((Control) widget).setMaxWidth(Double.MAX_VALUE);
        }
        sink.add(new ValueEditor(a, reader));
        return widget;
    }

    /** A one-line "param = value, param = value" summary, for the node card's collapsed view. */
    public static String summarize(List<ActivityVariable> params, int max) {
        if (params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(max, params.size());
        for (int i = 0; i < shown; i++) {
            ActivityVariable p = params.get(i);
            if (i > 0) sb.append(", ");
            sb.append(p.name()).append(" = ").append(p.value() == null ? "" : p.value().asText(""));
        }
        if (params.size() > shown) sb.append(", +").append(params.size() - shown).append(" more");
        return sb.toString();
    }

    // --- value parsing helpers (best-effort; fall back to defaults) ---

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDoubleOr(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static String normalizeTime(String s) {
        try { return LocalTime.parse(s.trim()).toString(); }
        catch (DateTimeParseException e) { return "00:00"; }
    }

    private static LocalDate parseDateOr(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return def; }
    }
}
