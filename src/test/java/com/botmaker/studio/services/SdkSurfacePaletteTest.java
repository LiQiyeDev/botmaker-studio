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
 * {@code @Palette} class. Curation now comes from the plugin's per-version catalog — a real
 * {@code Class<?>} named by a method reference, resolved in this build — so what decides these answers is
 * the <em>pin</em>, and the pin is one line in a pom. The index is not consulted at all here, which is
 * itself worth pinning down: presence and curation are two questions with two sources.
 *
 * <p>Three pins, because the catalog has three states and conflating any two of them is how this breaks in
 * the field: a version released before catalogs existed (offer everything), a version this build carries a
 * catalog for (offer what it names), and a version newer than this editor (offer everything again — a bot
 * ahead of Studio must not be narrowed to a surface Studio happens to know).
 */
class SdkSurfacePaletteTest {

    /** A pin no catalog names, and never will: every release before the catalog landed. */
    private static final String UNCATALOGUED = "1.0.5";

    /** A pin this build carries a catalog for. */
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

    // --- The three states of a pin ---

    @Test
    void anSdkTooOldForACatalogOffersEverything(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, UNCATALOGUED);

        assertFalse(surface.isPaletteAware(),
                "a version with no catalog must read as uncurated, not as curated-to-nothing");
        assertFalse(surface.isCurated(MOUSE));
        assertNull(surface.offeredSignatures(MOUSE, "click"),
                "null is the 'all of them' answer and is what every SDK released before 1.1.0 gets");
        assertTrue(surface.isOffered(MOUSE, "click"));
        assertTrue(surface.isOffered(MOUSE, "scroll"), "including the members a newer catalog declines");
    }

    @Test
    void anSdkNewerThanThisEditorOffersEverythingToo(@TempDir Path tmp) throws IOException {
        SdkSurfaceService surface = serviceFor(tmp, FROM_THE_FUTURE);

        // The same fail-open, reached from the other end. Narrowing a bot to the surface this build happens
        // to know would hide members its own jar really has — the one direction the intersection must never
        // take.
        assertFalse(surface.isPaletteAware());
        assertNull(surface.offeredSignatures(MOUSE, "click"));
        assertTrue(surface.isOffered(MOUSE, "anythingAtAll"));
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
