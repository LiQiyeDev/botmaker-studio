package com.botmaker.studio.project.launch;

import com.botmaker.shared.launch.LaunchKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SupportedTargets} — the launch kinds a published bot declares it works on.
 *
 * <p>The property doing the most work here is that <b>undeclared allows everything</b>. Every project and
 * every gallery entry that predates this key has no declaration, and reading "the author never said" as
 * "the author said nothing works" would make each of them un-launchable on a Studio that knows the key.
 */
class SupportedTargetsTest {

    @Test
    void anUndeclaredSetAllowsEveryKind() {
        SupportedTargets any = SupportedTargets.any();
        assertFalse(any.declared());
        for (LaunchKind kind : SupportedTargets.selectable()) {
            assertTrue(any.supports(kind), kind + " must stay available to an undeclared bot");
        }
        assertNull(any.spec(), "nothing declared must remove the key, not write an empty value");
    }

    @Test
    void aDeclaredSetAllowsOnlyWhatItNames() {
        SupportedTargets steamOnly = SupportedTargets.of(List.of(LaunchKind.STEAM));
        assertTrue(steamOnly.supports(LaunchKind.STEAM));
        assertFalse(steamOnly.supports(LaunchKind.EPIC));
        assertTrue(steamOnly.supportsSpec("steam:570"));
        assertFalse(steamOnly.supportsSpec("epic:Fortnite"));
    }

    @Test
    void anUnparseableSpecIsAllowedRatherThanRefused() {
        // A hand-edited launch.target must not become a dialog where nothing can be picked.
        SupportedTargets steamOnly = SupportedTargets.of(List.of(LaunchKind.STEAM));
        assertTrue(steamOnly.supportsSpec("nonsense"));
        assertTrue(steamOnly.supportsSpec(null));
    }

    @Test
    void thePropertiesValueRoundTrips() {
        SupportedTargets declared = SupportedTargets.of(List.of(LaunchKind.EMULATOR_APP, LaunchKind.STEAM));
        assertEquals("steam,emu-app", declared.spec(), "ids come out in enum order, not insertion order");
        assertEquals(declared, SupportedTargets.parse(declared.spec()));
        assertEquals(SupportedTargets.any(), SupportedTargets.parse("  "));
    }

    @Test
    void aKindThisBuildDoesNotKnowIsDroppedRatherThanThrowing() {
        // A bot published by a newer Studio must still open in this one.
        SupportedTargets parsed = SupportedTargets.parse("steam,quantum-console");
        assertEquals(Set.of(LaunchKind.STEAM), parsed.kinds());
    }

    @Test
    void theGalleryJsonFormIsThePlainIdList() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SupportedTargets declared = SupportedTargets.of(List.of(LaunchKind.STEAM, LaunchKind.EPIC));

        assertEquals("[\"steam\",\"epic\"]", mapper.writeValueAsString(declared));
        assertEquals(declared, mapper.readValue("[\"steam\",\"epic\"]", SupportedTargets.class));
        assertEquals(SupportedTargets.any(), mapper.readValue("[]", SupportedTargets.class));
    }

    /**
     * An index entry written before this field existed. Jackson resolves the absent value to {@code null}
     * without consulting the creator, so the normalisation that keeps such an entry usable has to live in
     * {@link com.botmaker.studio.sharing.GalleryEntry}'s own constructor — asserted here rather than assumed.
     */
    @Test
    void aGalleryEntryWithNoDeclarationReadsAsUndeclared() throws Exception {
        String json = """
                {"name":"ClickerBot","owner":"alice","repo":"clicker-bot","description":"","tags":[]}
                """;
        var entry = new ObjectMapper()
                .readValue(json, com.botmaker.studio.sharing.GalleryEntry.class);
        assertEquals(SupportedTargets.any(), entry.launchTargets());
        assertTrue(entry.launchTargets().supports(LaunchKind.STEAM));
    }

    @Test
    void describeNamesTheKindsAndSaysSoWhenNothingIsDeclared() {
        assertEquals("any launch target", SupportedTargets.any().describe());
        assertEquals("Steam game, Emulator app",
                SupportedTargets.of(List.of(LaunchKind.EMULATOR_APP, LaunchKind.STEAM)).describe());
    }
}
