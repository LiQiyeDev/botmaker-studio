package com.botmaker.studio.services;

import com.botmaker.sdk.api.interaction.Mouse;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Curation reached the whole menu system on 2026-08-24, and with it a vocabulary problem
 * {@link SdkSurfacePaletteTest} never had to face: that test asks about {@code "Mouse"}, but the surfaces
 * added here ask about whatever the analyzer called the scope — {@code com.botmaker.sdk.api.interaction.Mouse}
 * for a variable, an in-scope type or a library class, and a bare simple name only for a facade.
 *
 * <p>Both spellings must reach the same verdict, and — the half that actually protects someone — a class of
 * the user's own that merely <em>shares</em> an SDK simple name must reach none at all. A bot with its own
 * {@code Mouse} is not far-fetched; silently filtering its methods by the SDK's opinion of a different class
 * would be a menu that lies with no symptom to report.
 *
 * <p><b>Since phase 7 the qualified half is matched exactly rather than by package prefix</b>, because the
 * catalog holds the real {@code Class<?>} and can simply be asked. The collision is therefore no longer
 * staged with a fixture jar carrying two same-named classes — it is asked of the catalog directly, which is
 * the thing that now answers.
 *
 * <p>The name-level filter is tested here too, because it is the one place that deliberately does <em>not</em>
 * inherit {@code retainOffered}'s never-hand-back-nothing guard, and the reason is a judgement rather than a
 * mechanism (see {@link SdkSurfaceService#retainOfferedNames}).
 */
class PaletteKeyResolutionTest {

    /** A pin this build carries a catalog for. */
    private static final String CATALOGUED = "1.1.0";

    private static final String SIMPLE = Mouse.class.getSimpleName();
    private static final String QUALIFIED = Mouse.class.getName();
    /** The same simple name, in a package of the user's own. */
    private static final String USERS_OWN = "com.mybot." + SIMPLE;

    /** A service over a project pinned to {@link #CATALOGUED}; the index is never consulted for curation. */
    private static SdkSurfaceService serviceFor(Path tmp) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("fixture", tmp);
        Files.createDirectories(config.projectPath());
        Files.writeString(config.projectPath().resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.fixture</groupId>
                  <artifactId>fixture</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, CATALOGUED));
        TypeSummaryManager index = new TypeSummaryManager(Set.of("com.botmaker.sdk.api"));
        return new SdkSurfaceService(config, index, new EventBus(false));
    }

    // --- The two spellings agree ---

    @Test
    void aQualifiedSdkNameAnswersAsItsSimpleNameDoes(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);

        assertTrue(surface.isCurated(SIMPLE), "the facade menus' spelling");
        assertTrue(surface.isCurated(QUALIFIED), "a variable scope's spelling");
        assertEquals(surface.offeredSignatures(SIMPLE, "click"),
                surface.offeredSignatures(QUALIFIED, "click"));
        assertFalse(surface.isOffered(QUALIFIED, "scroll"),
                "a member no catalog names is hidden however the caller spells its type");
    }

    // --- A user's class of the same name is not the SDK's ---

    @Test
    void aUserClassSharingAnSdkSimpleNameIsNotCurated(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);

        assertFalse(surface.isCurated(USERS_OWN));
        assertNull(surface.offeredSignatures(USERS_OWN, "scroll"),
                "null is the 'all of them' answer — the user's own class is nobody's to curate");
        assertTrue(surface.isOffered(USERS_OWN, "scroll"));
    }

    @Test
    void anUnrelatedTypeIsUntouched(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);

        assertFalse(surface.isCurated("java.util.List"));
        assertFalse(surface.isCurated("SomethingNobodyHasHeardOf"));
        assertNull(surface.offeredSignatures("java.util.List", "add"));
    }

    // --- The name-level filter ---

    @Test
    void hiddenNamesDropOutAndTheCurrentOneStays(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);
        List<String> names = List.of("scroll", "click");

        assertEquals(List.of("click"), surface.retainOfferedNames(QUALIFIED, names, null));
        assertEquals(names, surface.retainOfferedNames(QUALIFIED, names, "scroll"),
                "a block already on a hidden member must still see its own name in the dropdown");
    }

    @Test
    void anEmptyResultIsAnAnswerNotAFallback(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);

        // Deliberately unlike retainOffered: the caller drops the whole submenu, which is the same thing it
        // already does for a type with nothing compatible with the slot. An empty ⚙ picker reads as breakage;
        // an absent submenu does not.
        assertEquals(List.of(), surface.retainOfferedNames(QUALIFIED, List.of("scroll"), null));
    }

    @Test
    void anUncuratedTypeIsTheIdentityFunction(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);
        List<String> names = List.of("scroll", "click");

        assertEquals(names, surface.retainOfferedNames(USERS_OWN, names, null));
        assertEquals(names, surface.retainOfferedNames("java.util.List", names, null));
    }

    @Test
    void overloadFilteringAcceptsAQualifiedNameToo(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp);
        List<MethodSignature> all = List.of(sig("click", "Point"), sig("click", "String"));

        assertEquals(List.of(all.getFirst()), surface.retainOffered(QUALIFIED, "click", all, null));
        assertEquals(all, surface.retainOffered(USERS_OWN, "click", all, null));
    }

    private static MethodSignature sig(String name, String... paramTypes) {
        List<ResolvedType> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < paramTypes.length; i++) {
            types.add(ResolvedType.named(paramTypes[i]));
            names.add("arg" + i);
        }
        return new MethodSignature(name, types, names, ResolvedType.named("void"));
    }
}
