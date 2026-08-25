package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the templates out of the SDK jar, and the two directions a hole can be unknown.
 *
 * <p>The templates themselves are the SDK's to check — its build compiles them and its
 * {@code ScaffoldTemplatesTest} asserts every fence is one matched pair. What is Studio's is this: that it
 * finds them, that the manifest it reads agrees with the files that came with it, and that filling a hole
 * replaces exactly the fenced region and nothing else.
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
        IOException refusal = assertThrows(IOException.class,
                () -> TemplateStore.bundled().require("NO_SUCH_TEMPLATE"));
        assertTrue(refusal.getMessage().contains("NO_SUCH_TEMPLATE"), refusal.getMessage());
    }

    /**
     * The forward-compatibility rule, from Studio's side: a hole it does not fill keeps its default, and the
     * file still compiles. That is what lets a <em>newer</em> SDK add holes this Studio has never heard of.
     */
    @Test
    void aHoleLeftAloneKeepsItsDefault() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        TemplateStore.Template driver = store.require("FLOW_DRIVER");

        String rendered = store.render(driver, "mybot", null, Map.of("MAX_STEPS", "42"));

        assertTrue(rendered.contains("MAX_STEPS = 42;"), rendered);
        assertTrue(rendered.contains("STEP_DELAY_MS = 0;"),
                "an unfilled hole keeps the SDK's own default:\n" + rendered);
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
    void aHoleTheTemplateDoesNotDeclareIsRefusedByName() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        TemplateStore.Template goHome = store.require("GO_HOME");

        // GO_HOME is a seed file with no holes at all, so any fragment offered to it has nowhere to go.
        IOException refusal = assertThrows(IOException.class,
                () -> store.render(goHome, "mybot", null, Map.of("FLOW", "x")));
        assertTrue(refusal.getMessage().contains("FLOW"), refusal.getMessage());
    }

    @Test
    void renderingRewritesThePackageAndTheClassName() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        Map<String, String> fills = new LinkedHashMap<>();
        fills.put("OUTCOMES", "NEXT, BAG_FULL");
        fills.put("ENABLED", "Activities.Mining");

        String rendered = store.render(store.require("ACTIVITY_STUB"), "mybot", "Mining", fills);

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
     * ones Studio ships rather than failing, which is the reason a project pinned to an old SDK still opens.
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
        IOException refusal =
                assertThrows(IOException.class, () -> TemplateStore.requireFloor("1.0.26"));
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
