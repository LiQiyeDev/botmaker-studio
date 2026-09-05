package com.botmaker.studio.services;

import com.botmaker.shared.Executables;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.github.SemVer;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the {@code botmaker} command-line tool current, on Linux, through the package manager.
 *
 * <p>Studio and the CLI are two hosts of one platform, not a library and its consumer: what they share lives
 * in {@code botmaker-studio-api} and {@code botmaker-plugin-host}, and <b>Studio has no Maven dependency on
 * {@code botmaker-cli} — there is deliberately none, and none is coming.</b> So there is no version to skew:
 * this class knows the CLI only as a package name, a {@code --version} line and a GitHub tag. That is also why
 * the check reaches {@code LiQiyeDev/botmaker-cli}'s releases rather than reading a constant baked in here —
 * a Studio that has not been re-released still learns about a newer CLI.
 *
 * <p><b>Linux only.</b> The CLI is packaged for the same signed dnf/apt repository Studio itself updates from
 * ({@code packaging/linux/install.sh} registers it), and the Studio packages declare {@code botmaker} as a
 * dependency, so on a supported install the tool is already present and this class only ever upgrades it.
 * There is no Windows or macOS package to upgrade, so {@link #isSupported()} is false there and the menu item
 * is not created at all.
 *
 * <p>Nothing here runs a privileged command silently: {@link #upgradeCommand()} is what a dialog shows the
 * user before {@link #runUpgrade()} hands it to {@code pkexec}, exactly as {@link UpdateService} hands an
 * installer to the OS rather than writing system files itself. Every network and process call is best-effort:
 * a failure resolves to "no update" rather than throwing, because a broken check must never be why the editor
 * shows an error.
 */
public final class CliUpdateService {

    /** A newer CLI than the one installed. {@code installed} is blank when the tool is absent entirely. */
    public record AvailableCliUpdate(String tag, String installed) {

        /** Whether this is a first install rather than an upgrade — the dialog asks a different question. */
        public boolean isFirstInstall() {
            return installed == null || installed.isBlank();
        }
    }

    /** {@code botmaker 0.0.10}, and tolerant of anything else the tool may print around the number. */
    private static final Pattern VERSION_IN_OUTPUT = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private final GitHubClient client;

    public CliUpdateService() {
        this(new GitHubClient());
    }

    public CliUpdateService(GitHubClient client) {
        this.client = client;
    }

    /**
     * Whether this machine can be offered a CLI upgrade at all: Linux, with a package manager we know how to
     * drive and {@code pkexec} to authorise it. Everything else — Windows, macOS, a Linux without polkit — is
     * a machine where the honest answer is to say nothing, since no command offered would work.
     */
    public static boolean isSupported() {
        return isLinux() && packageManager() != null && Executables.onPath("pkexec");
    }

    /**
     * Resolves to the newer CLI release if one exists, else empty. Empty also covers "up to date" and any
     * network or parse failure. An <em>absent</em> {@code botmaker} is not empty: it resolves to an update
     * whose {@code installed} is blank, because "you do not have it" and "yours is old" are the same offer
     * with a different sentence.
     */
    public CompletableFuture<Optional<AvailableCliUpdate>> checkForUpdate() {
        if (!isSupported()) return CompletableFuture.completedFuture(Optional.empty());
        String url = GitHubConfig.API_BASE + "/repos/"
                + GitHubConfig.CLI_OWNER + "/" + GitHubConfig.CLI_REPO + "/releases/latest";
        String installed = installedVersion();
        return client.get(url, null).thenApply(node -> {
            if (node == null) return Optional.<AvailableCliUpdate>empty();
            String tag = node.path("tag_name").asText("");
            // isGreater treats a blank/unparseable baseline as no lower bound, which is precisely the
            // "botmaker is not installed" case: any published tag is newer than nothing.
            if (!SemVer.isGreater(tag, installed)) return Optional.<AvailableCliUpdate>empty();
            return Optional.of(new AvailableCliUpdate(tag, installed));
        }).exceptionally(e -> Optional.empty());
    }

    /**
     * The version {@code botmaker --version} reports, or {@code ""} when the tool is absent, fails, or prints
     * something with no version in it. Blocking, but bounded: the CLI is a local process and a hung one must
     * not hold the check open.
     */
    public String installedVersion() {
        if (!Executables.onPath("botmaker")) return "";
        try {
            Process p = new ProcessBuilder("botmaker", "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            Matcher m = VERSION_IN_OUTPUT.matcher(output);
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * The shell command the upgrade will run, so a dialog can show it before anything is authorised. Null on a
     * machine {@link #isSupported()} rejects.
     */
    public static String upgradeCommand() {
        String manager = packageManager();
        if (manager == null) return null;
        return switch (manager) {
            case "dnf" -> "dnf upgrade -y botmaker || dnf install -y botmaker";
            case "apt-get" -> "apt-get install -y --only-upgrade botmaker || apt-get install -y botmaker";
            default -> null;
        };
    }

    /**
     * Runs {@link #upgradeCommand()} under one {@code pkexec} prompt and blocks until it finishes. Call it off
     * the FX thread. Throws when the command is unavailable or exits non-zero — the caller reports that; there
     * is nothing to roll back, since the package manager is transactional on its own.
     */
    public void runUpgrade() throws IOException {
        String cmd = upgradeCommand();
        if (cmd == null) {
            throw new IOException("No supported package manager on this machine");
        }
        try {
            Process p = new ProcessBuilder("pkexec", "sh", "-c", cmd).inheritIO().start();
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("The package manager exited with code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The upgrade was interrupted", e);
        }
    }

    /** {@code dnf} or {@code apt-get}, whichever this machine has; null when it has neither. */
    private static String packageManager() {
        if (Executables.onPath("dnf")) return "dnf";
        if (Executables.onPath("apt-get")) return "apt-get";
        return null;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
