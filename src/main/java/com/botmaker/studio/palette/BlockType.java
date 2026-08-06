package com.botmaker.studio.palette;

import java.util.List;

/**
 * A block the user can add from the palette / insert menu. Replaces the old weakly-typed {@code AddableBlock} enum:
 * each variant is a record that <em>carries its own creation data</em>, so the parser dispatches by pattern-matching
 * on the sealed type (exhaustive, compiler-checked) instead of decoding an enum constant's name.
 *
 * <p>{@link #id()} is a stable token used for drag-and-drop serialization (see {@code BlockCatalog#byId}); it equals
 * the old enum constant name so the drag protocol is unchanged.
 *
 * <p>Capabilities {@link #isStatement()} / {@link #isClassMember()} say which drop targets accept the block — most
 * blocks are body statements, {@link MethodMember} is class-only, and {@link EnumDecl} is both.
 */
public sealed interface BlockType
        permits BlockType.ControlFlow, BlockType.VarDecl, BlockType.ScannerRead,
                BlockType.LibraryCall, BlockType.LambdaCall, BlockType.EnumDecl, BlockType.MethodMember {

    String id();
    String displayName();
    BlockCategory category();

    /** Can be dropped into a method/loop/if body (the parser can build a {@code Statement} for it). */
    default boolean isStatement() { return true; }

    /** Can be dropped onto a class header (becomes a method/enum member). */
    default boolean isClassMember() { return false; }

    /** One-off statements whose AST shape is bespoke; built by {@code StatementFactory} keyed on {@link Kind}. */
    record ControlFlow(String id, String displayName, BlockCategory category, Kind kind) implements BlockType {
        public enum Kind {
            PRINT, IF, WHILE, FOR, DO_WHILE, SWITCH, MATCHES_SWITCH,
            BREAK, CONTINUE, RETURN, WAIT, ASSIGNMENT, FUNCTION_CALL, COMMENT, ARRAY
        }
    }

    /** A variable declaration: {@code <typeName> <varName> = <init>}. */
    record VarDecl(String id, String displayName, BlockCategory category,
                   String typeName, boolean primitive, String varName, Initializer init) implements BlockType {}

    /**
     * A console read: {@code <type> <varName> = BotMaker.<method>()} (e.g. {@code BotMaker.readInt()}). The
     * method, the declared type and whether that type is primitive all come off the {@link InputKind} — they
     * were three independent constructor arguments, and nothing stopped an entry pairing {@code readInt} with
     * {@code String}.
     */
    record ScannerRead(String id, String displayName, BlockCategory category,
                       InputKind input, String varName) implements BlockType {}

    /**
     * A static library call statement: {@code <facade>.<method>(args...)}.
     *
     * <p>The receiver is an {@link SdkType}, not a class name: every one of these is a call on an SDK facade —
     * {@code StatementMenu} already filtered the catalog with {@code SdkType.isFacadeClass(className())}, and
     * the two synthetic builders ({@code StatementMenu.sdkCall}, the overlay palette) start from an
     * {@code SdkType} and threw it away to pass the string back. Holding the type keeps the name compiler-
     * checked and lets the write path import by identity rather than by resolving a simple name.
     */
    record LibraryCall(String id, String displayName, BlockCategory category,
                       SdkType facade, String method, List<Initializer> args) implements BlockType {
        public LibraryCall(String id, String displayName, BlockCategory category,
                           SdkType facade, String method, List<Initializer> args) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.facade = facade;
            this.method = method;
            this.args = List.copyOf(args);
        }
    }

    /**
     * A static call whose trailing argument is a body lambda:
     * {@code <className>.<method>(leadingArgs…, <lambdaParam> -> { <body> })}. The dropped statements become the
     * lambda body (a droppable {@code BodyBlock}). {@code lambdaParam} names the single lambda parameter — the
     * name the body reaches the found value under — or is {@code null} for a no-arg {@code () -> {}} (a
     * {@code Runnable} target: the {@code ImageFinder.untilFind…} forms, which loop while nothing is found).
     * Built and re-parsed by {@code parser.handlers.LambdaCallHandler}.
     */
    record LambdaCall(String id, String displayName, BlockCategory category,
                      SdkType facade, String method,
                      List<Initializer> leadingArgs, String lambdaParam) implements BlockType {
        public LambdaCall(String id, String displayName, BlockCategory category,
                          SdkType facade, String method,
                          List<Initializer> leadingArgs, String lambdaParam) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.facade = facade;
            this.method = method;
            this.leadingArgs = List.copyOf(leadingArgs);
            this.lambdaParam = lambdaParam;
        }
    }

    /** An enum declaration — valid both as a body statement and as a class member. */
    record EnumDecl(String id, String displayName, BlockCategory category) implements BlockType {
        @Override public boolean isClassMember() { return true; }
    }

    /** A method declaration — only valid as a class member. */
    record MethodMember(String id, String displayName, BlockCategory category) implements BlockType {
        @Override public boolean isStatement() { return false; }
        @Override public boolean isClassMember() { return true; }
    }
}
