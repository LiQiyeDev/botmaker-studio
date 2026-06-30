package com.botmaker.blocks.expr;

import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.services.CodeEditorService;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.math.BigDecimal;
import java.util.Set;
import java.util.function.UnaryOperator;

public class LiteralBlock<T> extends AbstractExpressionBlock {
    private final T value;

    // Java keywords blacklist
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null"
    );

    public LiteralBlock(String id, Expression astNode, T value) {
        super(id, astNode);
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        String initialText = (value instanceof String) ? (String) value : String.valueOf(value);
        TextField textField = new TextField(initialText);

        if (initialText.isEmpty() && value instanceof String) {
            textField.setPromptText("Type a value...");
        }

        // READ-ONLY LOGIC
        if (isReadOnly()) {
            textField.setEditable(false);
            textField.setStyle("-fx-background-color: transparent; -fx-text-fill: #333; -fx-border-width: 0;");
            textField.setCursor(Cursor.DEFAULT);
        } else {
            textField.setCursor(Cursor.TEXT);

            UnaryOperator<TextFormatter.Change> filter = createInputFilter();
            textField.setTextFormatter(new TextFormatter<>(filter));

            textField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    String newText = textField.getText();
                    String oldText = (value instanceof String) ? (String) value : String.valueOf(value);

                    if (isNumberType() && !isValidFinalNumber(newText)) {
                        textField.setText(oldText);
                        return;
                    }

                    // Check for keywords if it's a string
                    if (value instanceof String && JAVA_KEYWORDS.contains(newText)) {
                        // Revert to old text if keyword
                        textField.setText(oldText);
                        return;
                    }

                    String textToSave = normalizeNumberSuffix(newText);

                    if (!textToSave.equals(newText)) {
                        textField.setText(textToSave);
                    }

                    if (!textToSave.equals(oldText)) {
                        if (this.astNode instanceof MethodInvocation mi) {
                            context.getCodeEditor().addStringArgumentToMethodInvocation(mi, textToSave);
                        } else {
                            context.getCodeEditor().replaceLiteralValue((Expression) this.astNode, textToSave);
                        }
                    }
                }
            });
        }

        HBox container = new HBox(textField);
        container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return container;
    }

    private String normalizeNumberSuffix(String text) {
        if (value instanceof Float) {
            if (!text.toLowerCase().endsWith("f")) {
                return text + "f";
            }
        } else if (value instanceof Long) {
            if (!text.toLowerCase().endsWith("l")) {
                return text + "L";
            }
        }
        return text;
    }

    private UnaryOperator<TextFormatter.Change> createInputFilter() {
        return change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty()) return change;
            if (value instanceof Integer || value instanceof Short || value instanceof Byte || value instanceof Long) {
                String regex = (value instanceof Long) ? "-?[0-9]*[lL]?" : "-?[0-9]*";
                if (newText.matches(regex)) {
                    if (isWithinRange(newText, value.getClass())) return change;
                }
                return null;
            } else if (value instanceof Double || value instanceof Float) {
                if (newText.matches("-?[0-9]*\\.?[0-9]*[dDfF]?")) {
                    if (isWithinRange(newText, value.getClass())) return change;
                }
                return null;
            } else if (value instanceof String) {
                String input = change.getText();
                if (input.contains("\"") || input.contains("\n") || input.contains("\r")) return null;
                return change;
            }
            return change;
        };
    }

    private boolean isWithinRange(String text, Class<?> type) {
        if (text.equals("-") || text.equals(".") || text.equals("-.")) return true;
        String cleanText = text.replaceAll("[dDfFlL]$", "");
        if (cleanText.isEmpty()) return true;
        try {
            if (type == Byte.class) {
                long val = Long.parseLong(cleanText);
                return val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE;
            }
            if (type == Short.class) {
                long val = Long.parseLong(cleanText);
                return val >= Short.MIN_VALUE && val <= Short.MAX_VALUE;
            }
            if (type == Integer.class) {
                long val = Long.parseLong(cleanText);
                return val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE;
            }
            if (type == Long.class) {
                Long.parseLong(cleanText);
                return true;
            }
            if (type == Float.class) {
                double val = Double.parseDouble(cleanText);
                return !Double.isInfinite(val);
            }
            if (type == Double.class) {
                BigDecimal val = new BigDecimal(cleanText);
                return !Double.isInfinite(val.doubleValue());
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    private boolean isValidFinalNumber(String text) {
        if (text.equals("-") || text.equals(".") || text.equals("-.")) return false;
        if (text.endsWith(".")) return false;
        return true;
    }

    private boolean isNumberType() {
        return value instanceof Number;
    }
}