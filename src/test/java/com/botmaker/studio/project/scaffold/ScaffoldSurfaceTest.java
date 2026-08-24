package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.TemplateConstants;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.FlowNode;
import com.botmaker.studio.project.scaffold.ScaffoldSurface.Element;
import com.botmaker.studio.project.scaffold.ScaffoldSurface.Origin;
import com.botmaker.studio.services.ActivityService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The scaffold's backward guarantee: what the generators emit, what {@link ScaffoldSurface} declares, and what
 * the SDK actually has must be the same three things.
 *
 * <h2>The three assertions, and why each is needed on its own</h2>
 *
 * <ol>
 *   <li><b>Emitted equals declared</b> ({@link #theDeclarationIsWhatTheGeneratorsEmit()}). A declaration
 *       nobody checks is a second copy that drifts, which is worse than no copy at all — so the text blocks
 *       are parsed and the symbols they name compared with the list, in <em>both</em> directions. An
 *       undeclared call fails; so does a declaration no generator emits any more.</li>
 *   <li><b>Declared resolves</b> ({@link #everyDeclaredElementExistsInTheSdk()}). Studio compiles against one
 *       SDK, so every element in the surface can be looked up in it right here. Rename
 *       {@code Watchdog.checkpoint} in the SDK, reinstall, and <em>Studio's</em> build fails naming that
 *       member — instead of the rename surfacing as a broken file in somebody's project.</li>
 *   <li><b>The file is current</b> ({@link #theSurfaceFileMatchesTheDeclaration()}). The SDK's own gate has
 *       to compare its {@code @Scaffolding} annotations against something, and it cannot read Studio — the
 *       dependency runs the other way, and comparing the annotations with themselves proves nothing. So this
 *       side, which holds the truth, writes {@code botmaker-sdk/scaffolding-surface.txt} and the SDK's
 *       {@code ApiPointersTest} reads it as its expectation.</li>
 * </ol>
 *
 * <h2>Resolution is reflection, not a jar scan</h2>
 *
 * <p>The plan called for ClassGraph over the SDK jar. It is on Studio's own test classpath — {@code SdkType}
 * holds real {@code Class<?>} literals for exactly this reason — so {@code Class.forName} plus
 * {@code getMethods()} answers the same question with no scan to configure and no jar to locate. A member is
 * matched on <em>name and parameter count</em>, which is what the surface records; the SDK's own gate matches
 * the same way, and the two agreeing is the point.
 */
class ScaffoldSurfaceTest {

    /**
     * The committed expectation the SDK's gate reads. Relative to this module, so it is found from the
     * umbrella checkout and absent from a standalone {@code botmaker-studio} clone — see
     * {@link #theSurfaceFileMatchesTheDeclaration()}.
     */
    private static final Path SURFACE = Path.of("..", "botmaker-sdk", "scaffolding-surface.txt");

    /** Regenerate with {@code -Dbotmaker.scaffold.writeSurface=true} rather than editing the file by hand. */
    private static final String WRITE = "botmaker.scaffold.writeSurface";

    // ------------------------------------------------------------------
    // 1 — emitted equals declared
    // ------------------------------------------------------------------

    @Test
    void theDeclarationIsWhatTheGeneratorsEmit() {
        List<Element> emitted = ScaffoldScan.collect(corpus());
        List<Element> declared = ScaffoldSurface.all().stream().sorted().toList();

        List<String> undeclared = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (Element e : emitted) if (!declared.contains(e)) undeclared.add(describe(e));
        for (Element d : declared) if (!emitted.contains(d)) stale.add(describe(d));

        if (undeclared.isEmpty() && stale.isEmpty()) return;
        StringBuilder message = new StringBuilder("ScaffoldSurface disagrees with what the generators emit:");
        if (!undeclared.isEmpty()) {
            message.append(" emitted but not declared: ").append(String.join(", ", undeclared)).append('.');
        }
        if (!stale.isEmpty()) {
            message.append(" declared but no generator emits it: ").append(String.join(", ", stale)).append('.');
        }
        message.append(" Fix whichever is wrong — a call added to a text block needs a line in ScaffoldSurface,"
                + " and a line no generator backs is a claim Phase 10's check would then enforce against"
                + " nothing. The origins are part of the comparison: a member the seed and the regenerated"
                + " files both write is one element carrying both.");
        fail(message.toString());
    }

    /** {@code ref(arity) [SEED, REGENERATED]} — enough to act on without opening the file. */
    private static String describe(Element e) {
        return e.line() + " " + e.origins().stream().sorted().map(Enum::name).toList();
    }

    // ------------------------------------------------------------------
    // 2 — declared resolves against the SDK Studio builds on
    // ------------------------------------------------------------------

    @Test
    void everyDeclaredElementExistsInTheSdk() {
        List<String> missing = new ArrayList<>();
        for (Element e : ScaffoldSurface.all()) {
            Class<?> type;
            try {
                type = Class.forName(e.type());
            } catch (ClassNotFoundException absent) {
                missing.add(e.type() + " (the type itself)");
                continue;
            }
            if (e.isType()) continue;
            if (!hasMember(type, e.member(), e.arity())) {
                missing.add(e.line());
            }
        }
        if (missing.isEmpty()) return;
        fail("The SDK Studio compiles against no longer has: " + String.join(", ", missing)
                + ". Studio's generators write these into every project they create, so a project made by this"
                + " build would not compile. Either the SDK moved them — follow its @ReplacedBy pointer and"
                + " update the generator and ScaffoldSurface together — or the declaration was wrong.");
    }

    private static boolean hasMember(Class<?> type, String member, int arity) {
        if (ScaffoldSurface.CTOR.equals(member)) {
            for (Constructor<?> c : type.getConstructors()) {
                if (c.getParameterCount() == arity) return true;
            }
            return false;
        }
        for (Method m : type.getMethods()) {
            if (m.getName().equals(member) && m.getParameterCount() == arity) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 3 — the file the SDK's gate reads
    // ------------------------------------------------------------------

    @Test
    void theSurfaceFileMatchesTheDeclaration() throws IOException {
        String expected = ScaffoldSurface.surfaceFile();
        if (Boolean.getBoolean(WRITE)) {
            Files.createDirectories(SURFACE.getParent());
            Files.writeString(SURFACE, expected);
            System.out.println("Wrote " + SURFACE.toAbsolutePath().normalize() + " — commit it with this change.");
            return;
        }
        if (!Files.exists(SURFACE)) {
            // botmaker-studio is its own git repo with its own CI, where there is no sibling SDK checkout to
            // compare against. The two assertions above still run there; only the cross-repo half is skipped.
            System.out.println("No " + SURFACE + " beside this module — skipping the cross-repo comparison. "
                    + "Run this from the umbrella checkout to check it.");
            return;
        }
        String actual = Files.readString(SURFACE);
        assertTrue(expected.equals(actual),
                "botmaker-sdk/scaffolding-surface.txt is out of date — the SDK's ApiPointersTest reads it as "
                        + "the expected @Scaffolding set, so it must be regenerated in the same commit that "
                        + "changes the surface. Run: mvn -pl botmaker-studio test "
                        + "-Dtest=ScaffoldSurfaceTest -D" + WRITE + "=true");
    }

    // ------------------------------------------------------------------
    // the corpus
    // ------------------------------------------------------------------

    /**
     * Every file Studio generates, rendered for a model chosen so that every shape actually appears: one
     * variable per {@link BotType#storableTypes()} (so a newly storable type shows up here on its own rather
     * than waiting for someone to remember), and one activity that is wired, goes home first and checks for
     * popups — which is what makes the driver emit {@code GoHome.INSTANCE.execute()},
     * {@code PopupGuard.enabled(…)} and a switch over {@code ActivityRegistry.MINING.execute()}.
     */
    private static List<ScaffoldScan.Source> corpus() {
        List<ScaffoldScan.Source> sources = new ArrayList<>();

        for (Map.Entry<String, String> file
                : ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "Actbot", "actbot").entrySet()) {
            sources.add(new ScaffoldScan.Source("seed/" + file.getKey(), file.getValue(), Origin.SEED));
        }
        for (Map.Entry<String, String> file
                : ProjectCreator.sourcesFor(ProjectTemplate.EMPTY, "Emptybot", "actbot").entrySet()) {
            sources.add(new ScaffoldScan.Source("empty/" + file.getKey(), file.getValue(), Origin.SEED));
        }

        ActivityDefinition mining = ActivityDefinition.create("Mining", "Dig.")
                .withGoHome(true).withPopupCheck(true);
        List<ActivityVariable> variables = new ArrayList<>();
        for (BotType type : BotType.storableTypes()) {
            variables.add(ActivityVariable.create("each" + type.name(), BotType.Choice.of(type)));
        }
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(mining), variables)
                .withFlow(new ActivityFlow(List.of(new FlowNode("Mining", 0, 0)),
                        List.of(new FlowEdge("Mining", "Mining", FlowEdge.NEXT_OUTCOME))));

        ActivityService service = new ActivityService(
                ProjectConfig.forProject("Actbot", Path.of("target", "scaffold-surface")), null, null);
        sources.add(new ScaffoldScan.Source("seed/activities/Mining.java",
                service.generateStubSource(mining), Origin.SEED));
        sources.add(new ScaffoldScan.Source("Activities.java",
                service.generateSource(cfg), Origin.REGENERATED));
        sources.add(new ScaffoldScan.Source("ActivityRegistry.java",
                service.generateRegistrySource(cfg), Origin.REGENERATED));
        sources.add(new ScaffoldScan.Source("FlowDriver.java",
                service.generateDriverSource(cfg), Origin.REGENERATED));
        // Names no SDK element at all — its constants are plain strings. In the corpus so that the day it
        // starts naming one, this test says so rather than the project failing to compile.
        sources.add(new ScaffoldScan.Source("Templates.java",
                TemplateConstants.generateSource("actbot", List.of("home", "mail")), Origin.REGENERATED));

        return sources;
    }
}
