package com.botmaker.studio.palette;

import java.util.List;

/**
 * Declarative description of a default initializer expression for a {@link BlockType.VarDecl} or the arguments of a
 * {@link BlockType.LibraryCall}. Pure data (no JDT): the parser turns it into an AST {@code Expression} via a single
 * recursive builder in {@code StatementFactory}, so adding a new default value never needs new dispatch code.
 */
public sealed interface Initializer
        permits Initializer.IntLit, Initializer.DoubleLit, Initializer.BoolLit, Initializer.CharLit,
                Initializer.StrLit, Initializer.NullLit, Initializer.NewInstance, Initializer.EnumConst,
                Initializer.StaticCall, Initializer.Raw {

    /** Numeric literal rendered as an integer, e.g. {@code 0}. */
    record IntLit(String value) implements Initializer {}

    /** Numeric literal rendered as a double, e.g. {@code 0.0}. */
    record DoubleLit(String value) implements Initializer {}

    record BoolLit(boolean value) implements Initializer {}

    /** Character literal, e.g. {@code 'a'}. The AST escapes it, so {@code value} is the character itself. */
    record CharLit(char value) implements Initializer {}

    /** String literal; {@code value} is the unescaped content (e.g. {@code "image.png"} or empty). */
    record StrLit(String value) implements Initializer {}

    record NullLit() implements Initializer {}

    /** {@code new TypeName(args...)}. */
    record NewInstance(String typeName, List<Initializer> args) implements Initializer {
        public NewInstance(String typeName, List<Initializer> args) {
            this.typeName = typeName;
            this.args = List.copyOf(args);
        }
    }

    /** Qualified enum reference, e.g. {@code Direction.NORTH}. */
    record EnumConst(String typeName, String constant) implements Initializer {}

    /** Static method invocation, e.g. {@code Vision.lastMatch()}. */
    record StaticCall(String typeName, String methodName, List<Initializer> args) implements Initializer {
        public StaticCall(String typeName, String methodName, List<Initializer> args) {
            this.typeName = typeName;
            this.methodName = methodName;
            this.args = List.copyOf(args);
        }
    }

    /**
     * A Java expression somebody else wrote, carried through as text.
     *
     * <p>The other nine variants describe a shape this editor knows how to build; this one describes nothing
     * at all, and it exists because a plugin's {@code SourceSeed} is exactly that — the plugin's own sentence
     * about what a fresh value of its type looks like. Studio must not take it apart: {@code Precision.DEFAULT}
     * and {@code new ImageTemplate("")} have nothing structurally in common, and a variant per shape here
     * would be this file guessing at a vocabulary it does not own.
     *
     * <p>It is the one variant that can fail to build: the text may not parse, and {@code InitializerFactory}
     * falls back rather than writing something uncompilable. The contract's {@code SourceSeed} says so too.
     */
    record Raw(String source) implements Initializer {}

    /**
     * This default as the source text {@code StatementFactory} would produce for it — {@code ""},
     * {@code false}, {@code java.time.LocalDate.now()}.
     *
     * <p>Two callers, and they are why this is here rather than in the parser: a preview sentence that has to
     * <em>name</em> the value a body is about to be given ("the value it gives back becomes false"), and the
     * comparison that asks whether a {@code return} still holds the untouched default it was seeded with. Both
     * want the text and neither has an {@code AST} to build a node with, so the text comes from the data.
     *
     * <p>Compare it through {@link #normalised}, never with {@code equals}: an AST printed back out spaces its
     * arguments differently from this, and {@code new java.awt.Color(255,255,255)} is the same default as
     * {@code new java.awt.Color(255, 255, 255)}.
     */
    default String sourceText() {
        return switch (this) {
            case IntLit(String value) -> value;
            case DoubleLit(String value) -> value;
            case BoolLit(boolean value) -> String.valueOf(value);
            case CharLit(char value) -> "'" + value + "'";
            case StrLit(String value) -> "\"" + value + "\"";
            case NullLit ignored -> "null";
            case NewInstance(String typeName, List<Initializer> args) ->
                    "new " + typeName + "(" + argText(args) + ")";
            case EnumConst(String typeName, String constant) -> typeName + "." + constant;
            case StaticCall(String typeName, String methodName, List<Initializer> args) ->
                    typeName + "." + methodName + "(" + argText(args) + ")";
            case Raw(String source) -> source;
        };
    }

    /** Everything two spellings of the same expression may differ by: whitespace. */
    static String normalised(String sourceText) {
        return sourceText == null ? "" : sourceText.replaceAll("\\s+", "");
    }

    private static String argText(List<Initializer> args) {
        return args.stream().map(Initializer::sourceText).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
