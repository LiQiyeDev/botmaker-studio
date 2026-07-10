package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitPackSearchTest {

    private static final String METADATA = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <metadata modelVersion="1.0.0">
                <groupId>com.github.LiQiyeDev</groupId>
                <artifactId>BotMaker-sdk</artifactId>
                <versioning>
                    <release>1.0.6</release>
                    <versions>
                        <version>0.1.0</version>
                        <version>0.1.1</version>
                        <version>1.0.2</version>
                        <version>1.0.6</version>
                    </versions>
                </versioning>
            </metadata>
            """;

    @Test
    void parsesVersionsNewestFirst() {
        List<String> versions = JitPackSearch.parseVersions(METADATA);
        assertEquals(List.of("1.0.6", "1.0.2", "0.1.1", "0.1.0"), versions);
    }

    @Test
    void parsesLatestFromReleaseTag() {
        assertEquals("1.0.6", JitPackSearch.parseLatest(METADATA));
    }

    @Test
    void latestFallsBackToNewestVersionWhenNoRelease() {
        String noRelease = METADATA.replaceAll("<release>.*?</release>", "");
        assertEquals("1.0.6", JitPackSearch.parseLatest(noRelease));
    }

    @Test
    void collapsesBareAndVPrefixedTagsOfTheSameVersion() {
        String mixed = """
                <metadata><versioning><versions>
                    <version>1.0.6</version>
                    <version>1.0.7</version>
                    <version>v1.0.7</version>
                    <version>v1.0.8</version>
                </versions></versioning></metadata>
                """;
        // 1.0.7 / v1.0.7 collapse to one (v-prefixed preferred); newest-first order preserved.
        assertEquals(List.of("v1.0.8", "v1.0.7", "1.0.6"), JitPackSearch.parseVersions(mixed));
    }

    @Test
    void emptyOrNullInputsAreSafe() {
        assertTrue(JitPackSearch.parseVersions(null).isEmpty());
        assertEquals("", JitPackSearch.parseLatest(null));
        assertTrue(JitPackSearch.parseVersions("<metadata/>").isEmpty());
    }
}
