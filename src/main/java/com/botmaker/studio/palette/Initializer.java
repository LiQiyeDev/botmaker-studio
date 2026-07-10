package com.botmaker.studio.palette;

import java.util.List;

/**
 * Declarative description of a default initializer expression for a {@link BlockType.VarDecl} or the arguments of a
 * {@link BlockType.LibraryCall}. Pure data (no JDT): the parser turns it into an AST {@code Expression} via a single
 * recursive builder in {@code StatementFactory}, so adding a new default value never needs new dispatch code.
 */
public sealed interface Initializer
        permits Initializer.IntLit, Initializer.DoubleLit, Initializer.BoolLit, Initializer.StrLit,
                Initializer.NullLit, Initializer.NewInstance, Initializer.EnumConst, Initializer.StaticCall {

    /** Numeric literal rendered as an integer, e.g. {@code 0}. */
    record IntLit(String value) implements Initializer {}

    /** Numeric literal rendered as a double, e.g. {@code 0.0}. */
    record DoubleLit(String value) implements Initializer {}

    record BoolLit(boolean value) implements Initializer {}

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

    /** Static method invocation, e.g. {@code VisionContext.getLastMatch()}. */
    record StaticCall(String typeName, String methodName, List<Initializer> args) implements Initializer {
        public StaticCall(String typeName, String methodName, List<Initializer> args) {
            this.typeName = typeName;
            this.methodName = methodName;
            this.args = List.copyOf(args);
        }
    }
}
