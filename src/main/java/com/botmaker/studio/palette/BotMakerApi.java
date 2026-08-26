package com.botmaker.studio.palette;

/**
 * Method names on the SDK's {@code BotMaker} facade that Studio both <em>writes</em> into a bot and
 * <em>recognises</em> when reading one back — the pairs that must agree or a block stops round-tripping.
 *
 * <p>{@code BotMaker.class} makes the class name compiler-checked wherever it is written; the method
 * names cannot be, because Studio deliberately does not read methods off the SDK jar (a bot compiles against
 * the SDK version <em>it</em> pins, which may be older). One constant per name is the next best thing: a
 * renamed method then fails in one edit, not in whichever half of the pair someone forgot.
 *
 * <p>The reads ({@code readLine} and friends) are not here — they carry a marker token, a declared type and
 * two labels alongside the method name, so they get a type of their own: {@link InputKind}.
 */
public final class BotMakerApi {

    /**
     * {@code BotMaker.print(...)} — emitted by the Print block and by the Call Function fallback, and matched
     * by {@code BlockConverter.isPrintStatement} when parsing a bot back into blocks.
     */
    public static final String PRINT = "print";

    private BotMakerApi() {}
}
