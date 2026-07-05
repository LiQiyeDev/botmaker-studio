package com.botmaker.studio.sharing;

import com.botmaker.studio.config.Constants;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs and updates bots from their GitHub repos (no account needed). A bot is a standard Maven project,
 * so installing = download the repo's release zip → unzip into {@code ~/BotMakerProjects/} → record
 * provenance ({@link BotSource}) for later update checks.
 *
 * <p>The blocking methods here are intended to run off the FX thread (the dialog wraps them in a
 * background task).
 */
public final class BotInstaller {

    private final GitHubClient client;
    private final GitHubGallery gallery;

    public BotInstaller(GitHubClient client, GitHubGallery gallery) {
        this.client = client;
        this.gallery = gallery;
    }

    /** The directory a bot named {@code name} would install into (sanitised to a valid project name). */
    public Path installDir(String name) {
        return Constants.PROJECTS_ROOT.resolve(sanitizeName(name));
    }

    public boolean isInstalled(String name) {
        return Files.exists(installDir(name));
    }

    /**
     * Downloads {@code entry} at release {@code tag} and unpacks it into a new project directory.
     *
     * @return the installed project directory
     * @throws IOException if the project already exists or download/unpack fails
     */
    public Path install(GalleryEntry entry, String tag) throws IOException {
        Path dest = installDir(entry.name());
        if (Files.exists(dest)) {
            throw new IOException("A project named '" + dest.getFileName() + "' already exists.");
        }
        downloadInto(entry.owner(), entry.repo(), tag, dest);
        new BotSource(entry.owner(), entry.repo(), tag).write(dest);
        return dest;
    }

    /**
     * Returns the latest release tag for an installed bot if it is newer than what's installed, else empty.
     */
    public Optional<String> checkForUpdate(Path projectDir) {
        Optional<BotSource> src = BotSource.read(projectDir);
        if (src.isEmpty()) return Optional.empty();
        String latest = gallery.latestReleaseTag(src.get().owner(), src.get().repo()).join();
        if (latest.isBlank() || latest.equals(src.get().tag())) return Optional.empty();
        return Optional.of(latest);
    }

    /**
     * Re-downloads the latest release of an installed bot and replaces the project in place. Overwrites
     * local edits (the caller warns the user first).
     *
     * @return the tag updated to, or empty if there was nothing newer / no provenance
     */
    public Optional<String> update(Path projectDir) throws IOException {
        Optional<BotSource> src = BotSource.read(projectDir);
        if (src.isEmpty()) return Optional.empty();
        String latest = gallery.latestReleaseTag(src.get().owner(), src.get().repo()).join();
        if (latest.isBlank() || latest.equals(src.get().tag())) return Optional.empty();

        Path tmp = projectDir.resolveSibling(projectDir.getFileName() + ".update-tmp");
        deleteRecursively(tmp);
        downloadInto(src.get().owner(), src.get().repo(), latest, tmp);
        new BotSource(src.get().owner(), src.get().repo(), latest).write(tmp);

        deleteRecursively(projectDir);
        Files.move(tmp, projectDir);
        return Optional.of(latest);
    }

    // -------------------------------------------------------------------------
    // Download + unzip
    // -------------------------------------------------------------------------

    private void downloadInto(String owner, String repo, String tag, Path dest) throws IOException {
        byte[] zip;
        try {
            zip = client.getBytes(GitHubConfig.archiveUrl(owner, repo, tag)).join();
        } catch (Exception e) {
            throw new IOException("Failed to download " + owner + "/" + repo + "@" + tag + ": "
                    + rootMessage(e), e);
        }
        Files.createDirectories(dest);
        unzipStrippingTopDir(zip, dest);
    }

    /**
     * Unzips a GitHub archive into {@code dest}, stripping the single top-level {@code repo-tag/} directory
     * that GitHub wraps every entry in. Guards against zip-slip.
     */
    private static void unzipStrippingTopDir(byte[] zipBytes, Path dest) throws IOException {
        Path destRoot = dest.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String stripped = stripFirstSegment(entry.getName());
                if (stripped.isEmpty()) continue; // the top-level dir entry itself

                Path target = destRoot.resolve(stripped).normalize();
                if (!target.startsWith(destRoot)) {
                    throw new IOException("Blocked unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static String stripFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(slash + 1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Best-effort coercion of an arbitrary bot/repo name to a valid project name ({@code ^[A-Z][a-zA-Z0-9]*$}),
     * camel-casing across separators (e.g. {@code clicker-bot} → {@code ClickerBot}).
     */
    static String sanitizeName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upNext = true; // capitalise the first kept letter and any letter after a separator
        for (char c : (name == null ? "" : name).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upNext ? Character.toUpperCase(c) : c);
                upNext = false;
            } else {
                upNext = true;
            }
        }
        String cleaned = sb.toString();
        if (cleaned.isEmpty()) return "ImportedBot";
        if (!Character.isLetter(cleaned.charAt(0))) cleaned = "Bot" + cleaned;
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
