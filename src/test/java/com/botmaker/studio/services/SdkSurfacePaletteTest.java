package com.botmaker.studio.services;

import com.botmaker.sdk.api.interaction.Mouse;
import com.botmaker.sdk.api.vision.ImageFinder;
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
 * The curation half of {@link SdkSurfaceService}: which overloads this bot's SDK says are worth
 * <em>offering</em>, as opposed to which merely exist (that is {@link SdkSurfaceServiceTest}).
 *
 * <p><b>No fixture jar, and that is the change phase 7 made.</b> Until 2026-08-26 curation was an
 * annotation the service probed for in the bot's own jar, so a test had to build one carrying a
 * {@code @Palette} class. Curation now comes from the plugin's catalog — real {@code Class<?>} identities,
 * resolved in this build — and the index is not consulted at all here, which is itself worth pinning down:
 * presence and curation are two questions with two sources.
 *
 * <p><b>The pin no longer selects a catalog</b> (phase 8, later the same day). There was one catalog class
 * per released version and therefore three states to keep apart — before catalogs, catalogued, and newer
 * than this editor. The SDK now serves a single catalog generated from its own annotations and
 * {@linkplain com.botmaker.sdk.plugin.SdkPlugin#catalog(String) ignores the pin}, so every pin here gets
 * the same curation and the three constants below survive only to say so. The narrowing that used to come
 * from picking an older catalog now comes from the other half of this service — the intersection against
 * the bot's own resolved jar, which is {@link SdkSurfaceServiceTest}'s subject and still fails open.
 */
class SdkSurfacePaletteTest {

    /** A release older than the catalog itself. */
    private static final String ANCIENT = "1.0.5";

    /** An ordinary pin. Nothing distinguishes it from the two below any more. */
    private static final String CATALOGUED = "1.1.0";

    /** A bot pinned to an SDK newer than this editor. */
    private static final String FROM_THE_FUTURE = "99.0.0";

    /** The SDK's own {@code Mouse}, as a catalogued facade's two spellings. */
    private static final String MOUSE = Mouse.class.getSimpleName();

    /**
     * A service over a project whose pom pins {@code version}, and an index that was never refreshed —
     * curation reads neither.
     */
    private static SdkSurfaceService serviceFor(Path tmp, String version) throws IOException {
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
                """.formatted(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, version));
        TypeSummaryManager index = new TypeSummaryManager(Set.of("com.botmaker.sdk.api"));
        return new SdkSurfaceService(config, index, new EventBus(false));
    }

    // --- The pin is not a selector any more ---

    @Test
    void anAncientPinGetsThisBuildsCuration(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, ANCIENT);

        // A deliberate, recorded change: this pin used to read as uncurated and be offered everything. It
        // is now curated like any other, and what its own jar genuinely lacks is removed by the presence
        // half of this service rather than by pretending the curation is unknown.
        assertTrue(surface.isPaletteAware());
        assertTrue(surface.isCurated(MOUSE));
        assertEquals(Set.of("Point", "int,int", "CaptureSource,int,int"),
                surface.offeredSignatures(MOUSE, "click"));
    }

    @Test
    void aPinNewerThanThisEditorGetsTheSameAnswer(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, FROM_THE_FUTURE);

        // The direction that always mattered: a bot ahead of Studio must not be *narrowed* to a surface
        // Studio happens to know. It is not — a name this catalog never heard of stays offered, because a
        // facade nobody catalogues is uncurated and only a catalogued facade can decline anything.
        assertTrue(surface.isPaletteAware());
        assertEquals(Set.of("Point", "int,int", "CaptureSource,int,int"),
                surface.offeredSignatures(MOUSE, "click"));
        assertTrue(surface.isOffered("SomethingThisEditorHasNeverHeardOf", "anythingAtAll"));
    }

    @Test
    void aCataloguedFacadeOffersExactlyTheMembersItNames(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);

        assertTrue(surface.isPaletteAware());
        assertTrue(surface.isCurated(MOUSE));
        assertEquals(Set.of("Point", "int,int", "CaptureSource,int,int"),
                surface.offeredSignatures(MOUSE, "click"));
        assertTrue(surface.isOffered(MOUSE, "click"));
    }

    @Test
    void aVarargsOverloadIsKeyedByItsElementType(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);

        // The descriptor is ImageTemplate[]; every caller — the menu, the picker, the persisted favorite —
        // reasons about the element, so the key has to agree with them and not with the bytecode. Varargs is
        // not in a descriptor at all, so this is the catalog reading the flag back off the declaring class.
        Set<String> keys = surface.offeredSignatures(ImageFinder.class.getSimpleName(), "findAny");
        assertTrue(keys.contains("ImageTemplate"), keys.toString());
        assertFalse(keys.contains("ImageTemplate[]"), keys.toString());
    }

    @Test
    void aPublicMemberNoCatalogNamesIsNotOffered(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);

        // Mouse.scroll(int) is public, supported and compiles; scrollUp/scrollDown are what the palette
        // proposes instead. An empty set is 'none of them' — the answer for anything a catalogued facade
        // declines to name.
        assertEquals(Set.of(), surface.offeredSignatures(MOUSE, "scroll"));
        assertFalse(surface.isOffered(MOUSE, "scroll"));
        assertTrue(surface.isOffered(MOUSE, "scrollUp"));
    }

    @Test
    void aClassNoPluginCataloguesIsUnchanged(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);

        // The rule that lets a catalog be written one facade at a time, and the one that keeps a user's own
        // classes out of it: a type nobody catalogues behaves exactly as it did before catalogs existed.
        assertFalse(surface.isCurated("SomethingNobodyHasHeardOf"));
        assertNull(surface.offeredSignatures("SomethingNobodyHasHeardOf", "click"));
        assertTrue(surface.isOffered("SomethingNobodyHasHeardOf", "click"));
    }

    @Test
    void theIndexAndTheCatalogAreSeparateQuestions(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);

        // Nothing was ever indexed, yet the palette is curated: curation is the plugin's answer for the pin
        // and never a property of the jar on disk. Presence still fails open beside it, which is what makes
        // the pair safe — the two halves cannot deadlock into an empty menu.
        assertFalse(surface.isIndexed());
        assertTrue(surface.isPaletteAware());
        assertTrue(surface.hasType("AnythingAtAll"));
    }

    // --- Offered, never resolved ---

    @Test
    void theOverloadACallIsAlreadyOnSurvivesTheFilter(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);
        MethodSignature hidden = sig("click", "String");
        List<MethodSignature> all = List.of(sig("click", "Point"), hidden, sig("click", "int", "int"));

        assertEquals(2, surface.retainOffered(MOUSE, "click", all, null).size());
        List<MethodSignature> keeping = surface.retainOffered(MOUSE, "click", all, hidden);
        assertEquals(3, keeping.size(), "a block already on a hidden overload must still see where it is");
        assertTrue(keeping.contains(hidden));
    }

    @Test
    void filteringEverythingAwayFallsBackToTheWholeList(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, CATALOGUED);
        List<MethodSignature> all = List.of(sig("scroll", "int"));

        assertEquals(all, surface.retainOffered(MOUSE, "scroll", all, null),
                "an empty picker reads as a broken block; the menu already declined to offer this name");
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
