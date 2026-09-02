package com.botmaker.studio.palette;

import com.botmaker.studio.types.PrimitiveKind;

import java.util.Optional;

/**
 * The console reads a bot can perform — one constant per {@code BotMaker.readX()} method, holding everything
 * the four places that cared about them were each spelling for themselves.
 *
 * <p>The same four-way set appeared as: the {@code type} token in the {@code BM-INPUT} marker the SDK prints
 * before blocking on stdin (switched over as a raw {@code String} in {@code UIManager.promptForInput}); the
 * method name plus declared type on {@code BlockCatalog}'s {@code ScannerRead} entries; and <b>two separate
 * switches</b> in {@code ReadInputBlock} — one mapping the method to a phrase, one to a type name — which had
 * to be edited in lockstep for a read to render correctly. Pairing them here is what the plan called for:
 * method, type and label in one constant.
 *
 * <p>The marker itself stays a {@code String} on purpose. {@link #MARKER_PREFIX} is a cross-process protocol
 * between the editor and whatever a running bot prints on it, and the editor compiles against none of that —
 * so it is hoisted to one constant here, not restructured. The parse is total: a marker naming a read this
 * Studio has never heard of yields {@link Optional#empty()} and the caller falls back to a generic prompt,
 * rather than failing the running bot's input popup.
 */
public enum InputKind {

    /** {@code String s = BotMaker.readLine();} */
    LINE("line", "readLine", "String", "read a line of text", "Enter some text:"),
    /** {@code int n = BotMaker.readInt();} */
    INT("int", "readInt", PrimitiveKind.INT.keyword(), "read a whole number", "Enter a whole number:"),
    /** {@code double d = BotMaker.readDouble();} */
    DOUBLE("double", "readDouble", PrimitiveKind.DOUBLE.keyword(), "read a decimal", "Enter a decimal number:"),
    /** {@code boolean b = BotMaker.readBoolean();} */
    BOOLEAN("boolean", "readBoolean", PrimitiveKind.BOOLEAN.keyword(), "read true/false", "Enter true or false:");

    /** What {@code BotMaker.signalInputExpected} writes between its two SOH characters, before the type token. */
    public static final String MARKER_PREFIX = "BM-INPUT:";

    /** The SOH (0x01) control character wrapping the marker, so it can be found and stripped from the console. */
    public static final char MARKER_DELIMITER = '\u0001';

    private final String markerToken;
    private final String method;
    private final String typeName;
    private final String phrase;
    private final String prompt;

    InputKind(String markerToken, String method, String typeName, String phrase, String prompt) {
        this.markerToken = markerToken;
        this.method = method;
        this.typeName = typeName;
        this.phrase = phrase;
        this.prompt = prompt;
    }

    /** The token inside the {@code BM-INPUT} marker. Part of the SDK protocol; do not change. */
    public String markerToken() {
        return markerToken;
    }

    /** The {@code BotMaker} method this read calls. */
    public String method() {
        return method;
    }

    /** The type the generated declaration is written with — {@code String}, or a primitive keyword. */
    public String typeName() {
        return typeName;
    }

    /** Whether {@link #typeName()} is a primitive, i.e. needs no import and no named type node. */
    public boolean isPrimitiveType() {
        return PrimitiveKind.isPrimitiveKeyword(typeName);
    }

    /** How the block reads in the editor, e.g. {@code "read a whole number"}. */
    public String phrase() {
        return phrase;
    }

    /** What the modal asks for when the running bot blocks on this read. */
    public String prompt() {
        return prompt;
    }

    /** The read whose marker token is {@code token}, or empty for one this Studio does not know. */
    public static Optional<InputKind> fromMarkerToken(String token) {
        return find(token, true);
    }

    /** The read whose {@code BotMaker} method is {@code method}, or empty when the call is some other one. */
    public static Optional<InputKind> fromMethod(String method) {
        return find(method, false);
    }

    private static Optional<InputKind> find(String value, boolean byMarker) {
        if (value == null) return Optional.empty();
        for (InputKind kind : values()) {
            if ((byMarker ? kind.markerToken : kind.method).equals(value)) return Optional.of(kind);
        }
        return Optional.empty();
    }
}
