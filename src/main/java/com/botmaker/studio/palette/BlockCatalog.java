package com.botmaker.studio.palette;

import com.botmaker.studio.palette.BlockType.ControlFlow;
import com.botmaker.studio.palette.BlockType.ControlFlow.Kind;
import com.botmaker.studio.palette.BlockType.EnumDecl;
import com.botmaker.studio.palette.BlockType.MethodMember;
import com.botmaker.studio.palette.BlockType.VarDecl;
import com.botmaker.studio.palette.Initializer.BoolLit;
import com.botmaker.studio.palette.Initializer.DoubleLit;
import com.botmaker.studio.palette.Initializer.IntLit;
import com.botmaker.studio.palette.Initializer.StrLit;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.botmaker.studio.palette.BlockCategory.*;

/**
 * The canonical set of insertable {@link BlockType}s — the single source of truth that replaces
 * {@code AddableBlock.values()}. Each {@code id()} equals the former enum constant name, so the drag-and-drop
 * serialization protocol is unchanged. The declaration order is the palette/menu display order (grouped by category
 * downstream).
 */
public final class BlockCatalog {

    private BlockCatalog() {}

    // --- Output ---
    public static final BlockType PRINT = cf("PRINT", "Print", OUTPUT, Kind.PRINT);

    // --- Flow control ---
    public static final BlockType IF = cf("IF", "If Statement", FLOW, Kind.IF);
    public static final BlockType SWITCH = cf("SWITCH", "Switch", FLOW, Kind.SWITCH);
    // MATCHES_SWITCH ("Check Image Combinations") stood here. It was never in ALL — it could only compile
    // inside a group-lambda body, so it reached the canvas by being seeded there rather than by being
    // dropped. Both the seed and the guarded switch it built are deleted (2026-09-01): branching on what was
    // found is a chain of ordinary calls now, which arrives through the member menus like any other call.

    // --- Loops ---
    public static final BlockType WHILE = cf("WHILE", "While Loop", LOOPS, Kind.WHILE);
    public static final BlockType FOR = cf("FOR", "For Each Loop", LOOPS, Kind.FOR);
    public static final BlockType DO_WHILE = cf("DO_WHILE", "Do While", LOOPS, Kind.DO_WHILE);

    // --- Control commands ---
    public static final BlockType BREAK = cf("BREAK", "Break", CONTROL, Kind.BREAK);
    public static final BlockType CONTINUE = cf("CONTINUE", "Continue", CONTROL, Kind.CONTINUE);
    public static final BlockType RETURN = cf("RETURN", "Return", CONTROL, Kind.RETURN);
    // Activity enable/disable and stop-the-bot are standard SDK facade calls now — Activity.enable/disable("X")
    // and Bot.stop() come from the Activity/Bot facade submenus and render with the normal SDK-block chrome, so
    // there are no bespoke CONTROL blocks for them (they used to be DISABLE_ACTIVITY/ENABLE_ACTIVITY/STOP_BOT).
    // "Wait" was a hand-written LibraryCall on Wait.class here until 2026-09-01, and it went with every other
    // entry in this file that named an SDK type. See the note above ALL for why they all went at once.

    // --- Variables ---
    public static final BlockType DECLARE_INT =
            new VarDecl("DECLARE_INT", "Int Variable", VARIABLES, "int", true, "number", new IntLit("0"));
    public static final BlockType DECLARE_DOUBLE =
            new VarDecl("DECLARE_DOUBLE", "Double Variable", VARIABLES, "double", true, "decimal", new DoubleLit("0.0"));
    public static final BlockType DECLARE_BOOLEAN =
            new VarDecl("DECLARE_BOOLEAN", "Bool Variable", VARIABLES, "boolean", true, "flag", new BoolLit(false));
    public static final BlockType DECLARE_STRING =
            new VarDecl("DECLARE_STRING", "String Variable", VARIABLES, "String", false, "text", new StrLit(""));
    public static final BlockType DECLARE_ARRAY = cf("DECLARE_ARRAY", "Create List", VARIABLES, Kind.ARRAY);
    public static final BlockType ASSIGNMENT = cf("ASSIGNMENT", "Set Variable", VARIABLES, Kind.ASSIGNMENT);

    // --- Input & interaction ---
    // Empty, and the INPUT category with it. CLICK, TYPE_TEXT and PRESS_KEY stood here as hand-written calls
    // on Mouse and Keyboard, and went with the rest of the SDK entries. READ_LINE, READ_INT and READ_DOUBLE
    // outlived them by a few hours on a comment that claimed they "read a Scanner, which is the JDK and
    // nobody's plugin" — which was simply false: they emitted `BotMaker.readLine()`, an SDK facade call, and
    // the editor recognised it by name to read one back. Nothing in the JDK is a one-line replacement (a
    // Scanner needs a field), so they went rather than being rewritten.

    // --- Functions ---
    public static final BlockType FUNCTION_CALL = cf("FUNCTION_CALL", "Call Function", FUNCTIONS, Kind.FUNCTION_CALL);
    public static final BlockType METHOD_DECLARATION =
            new MethodMember("METHOD_DECLARATION", "Declare Function", FUNCTIONS);
    public static final BlockType DECLARE_ENUM = new EnumDecl("DECLARE_ENUM", "Define Enum", VARIABLES);

    // Vision stood here — FIND_IMAGE, CLICK_IMAGE, WAIT_FOR_IMAGE on ImageFinder/ImageClicker/ImageWaiter, and
    // FIND_IMAGE_ACTIONS, the body-carrying find. All four named an SDK facade and all four are gone; the
    // LambdaCallBlock that rendered the last one is untouched, because round-tripping an existing lambda is
    // read from the source, not from a palette entry.
    /**
     * The "Declare Bot Variable" submenu — generated from {@link BotType}, the same list the Add Function
     * dialog picks a return type and parameter types from. It was five hand-written entries while the dialog
     * knew a different set again, so a type you could take as a parameter was not necessarily one you could
     * declare. Each entry's seed value is the type's own default, which is why every one of them compiles the
     * moment it is dropped.
     *
     * <p><b>It is computed per call since 2026-09-01, and the {@code static final} field it replaces was a
     * latent bug the moment the list stopped being closed.</b> Most of these types now come from the loaded
     * plugins' source seeds, so the list depends on which project is open — and a field initialised when this
     * class is first touched would have frozen whatever was loaded then, which for a class the palette reaches
     * during start-up is often nothing at all.
     */
    private static List<BlockType> botVariables() {
        return BotType.declarableTypes().stream()
                .filter(t -> t.group() != BotType.Group.BASICS)
                .<BlockType>map(BlockCatalog::declareBlock)
                .toList();
    }

    // DECLARE_POINT, DECLARE_RECT, DECLARE_SIZE, DECLARE_MATCH and DECLARE_TEMPLATE stood here, each a named
    // handle onto one generated entry, and each named an SDK type. They went with the SDK half of BotType on
    // 2026-09-01. Nothing in the module read any of them — one test did, and it now builds the entry it wants
    // through declareBlockFor, which is the path the Variables screen's Add already used.

    // Game launch (Game.launch/launchSteam/launchEpic) and the two emulator blocks (Emulators.use, and the
    // Emulators.named declaration) stood here. Every one named a facade of the SDK's, and the GAME category is
    // now empty — ExpressionMenu.addCategoryMenu already skips a category with nothing in it, so the submenu
    // simply does not appear rather than appearing empty.

    // --- Utility ---
    public static final BlockType COMMENT = cf("COMMENT", "Comment", UTILITY, Kind.COMMENT);

    /**
     * All insertable blocks in palette/menu display order — <b>the language, and nothing any plugin owns.</b>
     *
     * <p>Sixteen entries were removed on 2026-09-01, each a hand-written call on an SDK facade: Wait, Mouse,
     * Keyboard, ImageFinder, ImageClicker, ImageWaiter, Game and Emulators. They went for the reason the whole
     * migration exists — a call on a plugin's type is that plugin's to offer — and it cost nothing visible,
     * because <b>they had already stopped being reachable</b>. {@code StatementMenu.languageBlocks} filters
     * every {@code LibraryCall}/{@code LambdaCall} on a catalogued facade out of this list and offers the
     * plugin's own per-class submenus instead, so the entries had been shadowed by
     * {@code PluginHost.catalogFor} since the palette became plugin-served. Grep found no production reader of
     * any of them.
     *
     * <p>What is left is control flow, variables, functions and the comment — all of it Java, none of it
     * anybody's API. The three console reads went the same day for the same reason: they emitted
     * {@code BotMaker.readLine()}.
     *
     * <p><b>The bot-variable entries are the exception, and they are not the editor's.</b> They are one
     * declare-block per type the loaded plugins seed, so this list is <em>not</em> constant and is rebuilt on
     * every call. That is why there is no {@code ALL} field any more.
     */
    private static final List<BlockType> LANGUAGE = List.of(
            PRINT,
            IF, SWITCH,
            WHILE, FOR, DO_WHILE,
            BREAK, CONTINUE, RETURN,
            DECLARE_INT, DECLARE_DOUBLE, DECLARE_BOOLEAN, DECLARE_STRING, DECLARE_ARRAY,
            ASSIGNMENT,
            FUNCTION_CALL, METHOD_DECLARATION, DECLARE_ENUM);

    /** All insertable blocks in palette/menu display order. */
    public static List<BlockType> all() {
        return Stream.of(LANGUAGE, botVariables(), List.of(COMMENT))
                .flatMap(List::stream)
                .toList();
    }

    // botActions() — the nine bot-first entries promoted to the top of the insert menu — went with them, and it
    // had no caller at all by then: the promotion it described is the plugin's facade submenus now.

    /** Resolves a block from its {@link BlockType#id()} (used to deserialize a dragboard payload). */
    public static Optional<BlockType> byId(String id) {
        if (id == null) return Optional.empty();
        return all().stream().filter(b -> b.id().equals(id)).findFirst();
    }

    private static ControlFlow cf(String id, String displayName, BlockCategory category, Kind kind) {
        return new ControlFlow(id, displayName, category, kind);
    }

    /**
     * The declare-a-variable block for one curated type — the very entry the palette lists, so anything that
     * wants to insert a declaration builds the same statement a drop would. The Variables screen's Add is the
     * second caller, which is why this is no longer private.
     */
    public static VarDecl declareBlockFor(BotType type) {
        return declareBlock(type);
    }

    /** The declare-a-variable block for one offered type. */
    private static VarDecl declareBlock(BotType type) {
        return new VarDecl("DECLARE_" + type.id(), type.label(), BOT_VARIABLE,
                type.typeName(), type.isPrimitive(), type.suggestedName(),
                type.defaultValue().orElseThrow(
                        () -> new IllegalStateException(type + " is declarable but has no default value")));
    }

}
