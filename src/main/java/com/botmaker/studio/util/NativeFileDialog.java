package com.botmaker.studio.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Opens the operating system's <em>native</em> "open file" dialog to pick a program, instead of the
 * JavaFX {@link javafx.stage.FileChooser}. The JavaFX chooser can neither show hidden dotfiles nor let
 * the user type/paste a path, which makes programs under hidden directories ({@code ~/.local/share},
 * {@code ~/.steam}, …) unreachable — the native dialogs used here support both.
 *
 * <p>Strategy per OS (best-effort, failures logged not thrown — same spirit as {@link BrowserLauncher}):
 * <ul>
 *   <li><b>Windows</b> — PowerShell {@code System.Windows.Forms.OpenFileDialog} (the native Explorer dialog).</li>
 *   <li><b>macOS</b> — {@code osascript}'s {@code choose file}.</li>
 *   <li><b>Linux</b> — the first of {@code kdialog} / {@code zenity} / {@code yad} on {@code PATH}
 *       (preferring {@code kdialog} on KDE); all give a native dialog with a hidden-file toggle and a
 *       location bar.</li>
 * </ul>
 *
 * <p>When no native tool is available, {@link #chooseProgram} reports {@link Choice#nativeDialogShown()}
 * {@code false} so the caller can fall back to the JavaFX chooser. The dialog blocks until the user acts,
 * so callers must invoke this off the JavaFX Application Thread.
 */
public final class NativeFileDialog {

    private NativeFileDialog() {}

    /**
     * The result of an attempted native pick: whether a native dialog was actually shown, and the chosen
     * absolute path (empty when the user cancelled or no dialog was shown).
     */
    public record Choice(boolean nativeDialogShown, Optional<String> path) {
        static final Choice UNAVAILABLE = new Choice(false, Optional.empty());
        static Choice cancelled() { return new Choice(true, Optional.empty()); }
        static Choice picked(String path) { return new Choice(true, Optional.of(path)); }
    }

    /**
     * Shows the native "open file" dialog starting at {@code initialDir} (may be {@code null}). Blocks
     * until the user picks a file or cancels. Never throws.
     */
    public static Choice chooseProgram(String initialDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) return runWindows(initialDir);
            if (os.contains("mac")) return runMac(initialDir);
            return runLinux(initialDir); // Linux / other Unix
        } catch (Exception e) {
            System.err.println("Native file dialog failed: " + e.getMessage());
            return Choice.UNAVAILABLE;
        }
    }

    // --- Windows -----------------------------------------------------------------------------------

    private static Choice runWindows(String initialDir) throws Exception {
        String initDir = (initialDir == null || initialDir.isBlank()) ? "" : initialDir.replace("'", "''");
        String script =
                "Add-Type -AssemblyName System.Windows.Forms;" +
                "$f = New-Object System.Windows.Forms.OpenFileDialog;" +
                "$f.Title = 'Choose a program to launch';" +
                (initDir.isEmpty() ? "" : "$f.InitialDirectory = '" + initDir + "';") +
                "if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($f.FileName) }";
        return run(List.of("powershell", "-NoProfile", "-STA", "-Command", script));
    }

    // --- macOS -------------------------------------------------------------------------------------

    private static Choice runMac(String initialDir) throws Exception {
        // `choose file` errors (exit 1) on cancel — run() treats a non-zero exit with no output as cancel.
        String script = "POSIX path of (choose file with prompt \"Choose a program to launch\")";
        return run(List.of("osascript", "-e", script));
    }

    // --- Linux -------------------------------------------------------------------------------------

    private static Choice runLinux(String initialDir) throws Exception {
        boolean kde = System.getenv().getOrDefault("XDG_CURRENT_DESKTOP", "").toUpperCase().contains("KDE");
        List<String> order = kde ? List.of("kdialog", "zenity", "yad") : List.of("zenity", "kdialog", "yad");
        for (String tool : order) {
            if (!onPath(tool)) continue;
            return switch (tool) {
                case "kdialog" -> run(List.of("kdialog", "--getopenfilename",
                        initialDir == null ? "." : initialDir));
                case "zenity" -> run(List.of("zenity", "--file-selection",
                        "--title=Choose a program to launch",
                        "--filename=" + (initialDir == null ? "" : ensureTrailingSlash(initialDir))));
                case "yad" -> run(List.of("yad", "--file",
                        "--title=Choose a program to launch",
                        "--filename=" + (initialDir == null ? "" : ensureTrailingSlash(initialDir))));
                default -> Choice.UNAVAILABLE;
            };
        }
        return Choice.UNAVAILABLE;
    }

    private static String ensureTrailingSlash(String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /** Whether {@code tool} is an executable on the {@code PATH}. */
    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, tool);
            if (f.canExecute()) return true;
        }
        return false;
    }

    // --- shared ------------------------------------------------------------------------------------

    /** Runs {@code command}, returning the first non-blank stdout line as the chosen path (blocks). */
    private static Choice run(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() == 0 && !line.isBlank()) out.append(line);
            }
        }
        process.waitFor();

        String picked = out.toString().trim();
        return picked.isEmpty() ? Choice.cancelled() : Choice.picked(picked);
    }
}
