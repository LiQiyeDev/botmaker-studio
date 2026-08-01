package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What may be written over a generated file: everything except the parts BotMaker owns.
 *
 * <p>In {@code FlowDriver.java} that is <em>everything</em>: it is the generated walk over the drawn flow, and
 * {@link MethodLock#NONE} defers to a file that is {@link FileRole#GENERATED}, so no edit to it may reach disk.
 * The "an editable body inside a locked file must be flushable" mechanism these tests once exercised through
 * {@code GameLoop.run} survives via {@link MethodLock#SIGNATURE} generally — see {@code lockedPartsMatch}'s
 * skeleton, which blanks any body-editable method.
 */
class LockedRegionsTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));
    private static final Path FLOW_DRIVER = CONFIG.flowDriverSourceFile();

    private static final String ORIGINAL = """
            package com.mybot;
            public class FlowDriver {
                private static int ticks = 0;
                public static void run() {
                    ActivityRegistry.ALL.forEach(Activity::execute);
                }
                public static void helper() {
                    ticks++;
                }
            }
            """;

    private static boolean matches(String a, String b) {
        return LockedRegions.lockedPartsMatch(CONFIG, ProjectTemplate.GAME_BOT, FLOW_DRIVER, a, b);
    }

    @Test
    void aFlowDriverRunBodyEditIsNotPersistable() {
        // run() is the generated flow walk — the file is generated, so an edit to it is damage.
        String edited = ORIGINAL.replace(
                "ActivityRegistry.ALL.forEach(Activity::execute);",
                "BotMaker.print(\"mine\");\n        ActivityRegistry.ALL.forEach(Activity::execute);");
        assertFalse(matches(ORIGINAL, edited), "the flow walk is BotMaker's — it must not be overwritten");
        assertFalse(matches(ORIGINAL,
                ORIGINAL.replace("ActivityRegistry.ALL.forEach(Activity::execute);", "")));
    }

    @Test
    void aChangeToTheRunSignatureIsNotPersistable() {
        assertFalse(matches(ORIGINAL, ORIGINAL.replace("void run()", "void tick()")));
        assertFalse(matches(ORIGINAL, ORIGINAL.replace("void run()", "void run(int n)")));
    }

    @Test
    void aChangeToAnotherMethodsBodyIsNotPersistable() {
        // helper() is MethodLock.NONE, which defers to the file — and the file is generated.
        assertFalse(matches(ORIGINAL, ORIGINAL.replace("ticks++;", "ticks--;")));
    }

    @Test
    void aChangeToTheClassOrItsFieldsIsNotPersistable() {
        assertFalse(matches(ORIGINAL, ORIGINAL.replace("private static int ticks = 0;", "")));
        assertFalse(matches(ORIGINAL, ORIGINAL.replace("class FlowDriver", "class FlowDriver2")));
        assertFalse(matches(ORIGINAL, ORIGINAL.replace("public static void helper() {", "public static void sneaky() {")));
    }

    @Test
    void reformattingIsNotAChange() {
        // The comparison is on JDT's printed form, so indentation and line endings never read as edits.
        String reformatted = ORIGINAL
                .replace("    private static", "\tprivate static")
                .replace("\n", "\r\n")
                .replace("public class FlowDriver {", "public class FlowDriver\n{");
        assertTrue(matches(ORIGINAL, reformatted));
    }

    @Test
    void anEditableFilesSkeletonIgnoresEveryBody() {
        // In a file the user owns, every method body is theirs, so nothing here constrains them. (The caller
        // doesn't even ask in that case — but the rule should hold on its own.)
        Path helper = CONFIG.mainSourceFile().getParent().resolve("MyHelper.java");
        String a = "package com.mybot;\npublic class MyHelper { void run() { int x = 1; } }\n";
        String b = "package com.mybot;\npublic class MyHelper { void run() { int y = 2; } }\n";
        assertTrue(LockedRegions.lockedPartsMatch(CONFIG, ProjectTemplate.GAME_BOT, helper, a, b));
    }

    @Test
    void unparseableSourceFailsClosed() {
        // An edit that can't be shown to be safe isn't safe.
        assertFalse(matches(ORIGINAL, "this is not java {{{"));
        assertFalse(matches(ORIGINAL, null));
    }
}
