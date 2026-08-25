package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the scaffold out of the SDK jar, and the two directions a token can be unknown.
 *
 * <p>The templates themselves are the SDK's to check — its build compiles them and its
 * {@code ScaffoldTemplatesTest} asserts every declared token is one matched pair of fences. What is Studio's
 * is this: that it finds them, that the manifest it reads agrees with the files that came with it, and that
 * filling a token replaces exactly the fenced region and nothing else.
 */
class TemplateStoreTest {

    private static final Set<String> EXPECTED_TEMPLATES = Set.of(
            "ENTRY_POINT", "GO_HOME", "POPUPS", "ACTIVITY_STUB",
            "ACTIVITIES", "ACTIVITY_REGISTRY", "FLOW_DRIVER");

    @Test
    void theBundledSdkShipsEveryTemplateStudioWrites() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        assertFalse(store.isEmpty(), "the SDK Studio compiles against must carry its own scaffold templates");
        for (String id : EXPECTED_TEMPLATES) {
            assertTrue(store.require(id).source().startsWith("package com.botmaker.sdk.templates"),
                    id + " should be a template in the SDK's own package");
        }
    }

    @Test
    void aTemplateThisSdkDoesNotShipIsRefusedByName() {
        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> TemplateStore.bundled().require("NO_SUCH_TEMPLATE"));
        assertTrue(refusal.getMessage().contains("NO_SUCH_TEMPLATE"), refusal.getMessage());
    }

    /**
     * The forward-compatibility rule, from Studio's side: a token it does not fill keeps its default, and the
     * file still compiles. That is what lets a <em>newer</em> SDK add tokens this Studio has never heard of.
     */
    @Test
    void aTokenLeftAloneKeepsItsDefault() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        TemplateStore.Template driver = store.require("FLOW_DRIVER");

        String rendered = store.render(driver, "mybot", null, Map.of(ScaffoldToken.MAX_STEPS, "42"));

        assertTrue(rendered.contains("MAX_STEPS = 42;"), rendered);
        assertTrue(rendered.contains("STEP_DELAY_MS = 0;"),
                "an unfilled token keeps the SDK's own default:\n" + rendered);
        assertTrue(rendered.contains("FlowGraph.node(\"Example\""),
                "and so does an unfilled multi-line one:\n" + rendered);
        assertTrue(TemplateStore.unfilledTokens(rendered).isEmpty(),
                "the fences themselves never survive into a bot's source");
    }

    /**
     * And the other direction, which is <b>not</b> ignorable: Studio holding a fragment the template has
     * nowhere to put means part of the user's project would be silently dropped.
     */
    @Test
    void aTokenTheTemplateDoesNotDeclareIsRefusedByName() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        TemplateStore.Template goHome = store.require("GO_HOME");

        // GO_HOME is a seed file with no holes at all, so any fragment offered to it has nowhere to go.
        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> store.render(goHome, "mybot", null, Map.of(ScaffoldToken.FLOW, "x")));
        assertTrue(refusal.getMessage().contains("FLOW"), refusal.getMessage());
    }

    /**
     * The third direction, and the one this whole generation business exists for: the hole is <em>there</em>,
     * spelled the way Studio spells it, and its shape has moved on.
     *
     * <p>Every name-based check passes here — {@code FLOW} is declared, {@code FLOW} is fenced, and the text
     * Studio holds names only members that still resolve. Written in, it would compile and route the bot
     * somewhere else. So the generation is what makes it a refusal, and the refusal has to name the hole and
     * the shape rather than talk about tokens.
     */
    @Test
    void aHoleWhoseShapeMovedOnIsRefusedByName(@TempDir Path dir) throws Exception {
        TemplateStore store = TemplateStore.forJar(jarWithFlowAtGeneration(dir, 99));
        TemplateStore.Template driver = store.require("FLOW_DRIVER");

        assertEquals(OptionalInt.of(99), driver.generationOf(ScaffoldToken.FLOW));
        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> store.render(driver, "mybot", null, Map.of(ScaffoldToken.FLOW, "\"Start\"")));
        assertTrue(refusal.getMessage().contains("FLOW:99"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Update Studio"),
                "and the way out: " + refusal.getMessage());
    }

    /** A hole Studio never offers a fragment for is not affected — its default stands, whatever generation. */
    @Test
    void aMovedHoleNobodyFillsStillRendersItsDefault(@TempDir Path dir) throws Exception {
        TemplateStore store = TemplateStore.forJar(jarWithFlowAtGeneration(dir, 99));
        TemplateStore.Template driver = store.require("FLOW_DRIVER");

        String rendered = store.render(driver, "mybot", null, Map.of(ScaffoldToken.MAX_STEPS, "42"));

        assertTrue(rendered.contains("MAX_STEPS = 42;"), rendered);
        assertTrue(TemplateStore.unfilledTokens(rendered).isEmpty(),
                "the fences go either way — an unfilled hole is not a marker left in a bot's source");
    }

    /**
     * An SDK jar carrying the real templates, with {@code FLOW}'s generation moved to {@code generation} in
     * both the manifest and the fences — the future SDK that does not exist yet, built on the spot.
     */
    private static Path jarWithFlowAtGeneration(Path dir, int generation) throws Exception {
        TemplateStore bundled = TemplateStore.bundled();
        Path jar = dir.resolve("sdk-future.jar");
        StringBuilder manifest = new StringBuilder("format 2\npackage com.botmaker.sdk.templates\n");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (TemplateStore.Template t : bundled.templates()) {
                String path = "com/botmaker/sdk/templates/"
                        + (t.id().equals("ACTIVITY_STUB") ? "activities/" : "") + t.className() + ".java";
                write(out, TemplateStore.ROOT + "/" + path,
                        t.source().replace("STUDIO:FLOW:1>", "STUDIO:FLOW:" + generation + ">"));
                String holes = t.holes().isEmpty() ? "-"
                        : String.join(",", new TreeSet<>(t.holes())).replace("FLOW:1", "FLOW:" + generation);
                manifest.append("template ").append(t.id()).append(' ').append(t.kind()).append(' ')
                        .append(path).append(' ').append(t.target()).append(' ').append(holes).append('\n');
            }
            write(out, TemplateStore.ROOT + "/manifest.txt", manifest.toString());
        }
        return jar;
    }

    private static void write(JarOutputStream out, String path, String content) throws IOException {
        out.putNextEntry(new ZipEntry(path));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    @Test
    void renderingRewritesThePackageAndTheClassName() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        Map<ScaffoldToken, String> tokens = new LinkedHashMap<>();
        tokens.put(ScaffoldToken.OUTCOMES, "NEXT, BAG_FULL");
        tokens.put(ScaffoldToken.ENABLED, "Activities.Mining");

        String rendered = store.render(store.require("ACTIVITY_STUB"), "mybot", "Mining", tokens);

        assertTrue(rendered.startsWith("package com.mybot.activities;"), rendered);
        assertTrue(rendered.contains("public class Mining extends Activity<Mining.Outcome>"), rendered);
        assertTrue(rendered.contains("import com.mybot.Activities;"), rendered);
        assertTrue(rendered.contains("public enum Outcome { NEXT, BAG_FULL }"), rendered);
        assertTrue(rendered.contains("return Activities.Mining;"), rendered);
        assertFalse(rendered.contains("ActivityStub"), "nothing may still name the template:\n" + rendered);
        assertFalse(rendered.contains("com.botmaker.sdk.templates"), rendered);
    }

    /**
     * A jar with no {@code botmaker-templates/} in it is an SDK from before they existed. It falls back to the
     * ones Studio ships rather than failing — the same fail-open rule {@code ScaffoldCheck} follows, and the
     * reason a project pinned to an old SDK still opens.
     */
    @Test
    void aJarWithNoTemplatesFallsBackToTheBundledOnes() throws Exception {
        TemplateStore store = TemplateStore.forJar(java.nio.file.Path.of("no", "such.jar"));
        assertFalse(store.isEmpty());
        assertEquals(TemplateStore.bundled().require("GO_HOME").source(), store.require("GO_HOME").source());
    }

    /**
     * …and the floor is what keeps that fallback honest. Falling open lands on templates that call
     * {@code FlowGraph} and {@code Wire}; an SDK from before those existed cannot compile them, so the version
     * — not the jar's contents — is what refuses first.
     */
    @Test
    void anSdkOlderThanTheScaffoldIsRefusedByVersion() {
        ScaffoldUnsupported refusal =
                assertThrows(ScaffoldUnsupported.class, () -> TemplateStore.requireFloor("1.0.26"));
        assertTrue(refusal.getMessage().contains("1.0.26"),
                "the sentence has to name the version the user actually pins: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains(MavenService.MIN_SDK_VERSION), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Upgrade SDK"),
                "and the way out: " + refusal.getMessage());
    }

    /** The three things the floor must let through, each for a different reason. */
    @Test
    void theFloorPassesTheFloorItself_aNewerSdk_andADevBuild() {
        assertDoesNotThrow(() -> TemplateStore.requireFloor(MavenService.MIN_SDK_VERSION));
        assertDoesNotThrow(() -> TemplateStore.requireFloor("2.0.0"));
        // 0.0.0-SNAPSHOT is what `mvn -pl botmaker-sdk -am install` produces and what a dev-run pins on
        // purpose. SemVer sorts it below everything, so a naive comparison would refuse every dev-run.
        assertDoesNotThrow(() -> TemplateStore.requireFloor("0.0.0-SNAPSHOT"));
        // No pom, or a pom naming no SDK: MavenService answers the fallback, which is at the floor.
        assertDoesNotThrow(() -> TemplateStore.requireFloor(null));
        assertDoesNotThrow(() -> TemplateStore.requireFloor("  "));
    }
}
