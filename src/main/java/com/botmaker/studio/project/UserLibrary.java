package com.botmaker.studio.project;

/**
 * An immutable Maven coordinate for a user-added library dependency.
 *
 * <p>The set of user libraries is stored in the project {@code pom.xml} (see
 * {@code MavenService.readUserLibraries}/{@code writeUserLibraries}); this record is just the value.
 * Package/type discovery for suggestions is handled separately by the type index
 * ({@code TypeSummaryManager}), so no jar scanning lives here.
 */
public record UserLibrary(String groupId, String artifactId, String version) {

    public UserLibrary {
        groupId = groupId == null ? "" : groupId.trim();
        artifactId = artifactId == null ? "" : artifactId.trim();
        version = version == null ? "" : version.trim();
    }

    /** The {@code groupId:artifactId:version} coordinate string. */
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /** {@code groupId:artifactId}, ignoring version — used to match against default dependencies. */
    public String groupArtifact() {
        return groupId + ":" + artifactId;
    }

    /**
     * Parses a {@code groupId:artifactId:version} coordinate string.
     *
     * @throws IllegalArgumentException if the string does not have exactly three non-blank parts
     */
    public static UserLibrary parse(String gav) {
        if (gav == null) {
            throw new IllegalArgumentException("Coordinate must not be null");
        }
        String[] parts = gav.trim().split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected 'groupId:artifactId:version', got: '" + gav + "'");
        }
        return new UserLibrary(parts[0], parts[1], parts[2]);
    }

    @Override
    public String toString() {
        return coordinates();
    }
}
