package com.botmaker.studio.services.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * The single source of truth for the graphics session BotMaker is running under. Studio's screen capture
 * and input control rely on X11 (Xorg); under Wayland {@code Robot} capture returns black and native
 * window control is blocked, so several call sites need to know the session type and, on Wayland, how to
 * install the distro's X11 session packages so the user can switch.
 *
 * <p>Switching Wayland → Xorg cannot be done live: the user must log out and pick the "Xorg" / X11 session
 * at the login screen. This service only detects the session and builds a best-effort package-install
 * command; it never claims X11 is "enabled".
 */
public final class SessionEnvironment {

    private SessionEnvironment() {}

    /** True if we appear to be running under a Wayland session (the strongest of the env signals). */
    public static boolean isWayland() {
        return "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))
                || System.getenv("WAYLAND_DISPLAY") != null;
    }

    /** True on Linux (where the X11-vs-Wayland distinction — and package install — applies). */
    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** The current desktop environment string ({@code XDG_CURRENT_DESKTOP}), lower-cased; never null. */
    public static String currentDesktop() {
        String d = System.getenv("XDG_CURRENT_DESKTOP");
        return d == null ? "" : d.toLowerCase();
    }

    /**
     * A best-effort {@code pkexec} command that installs the X11/Xorg session packages for the detected
     * package manager and desktop, or {@code null} if the package manager can't be detected. The command
     * is always surfaced to the user so they can run it manually if {@code pkexec}/detection fails.
     */
    public static List<String> x11InstallCommand() {
        String pm = detectPackageManager();
        if (pm == null) return null;
        String desk = currentDesktop();
        boolean kde = desk.contains("kde") || desk.contains("plasma");

        List<String> cmd = new ArrayList<>();
        cmd.add("pkexec");
        List<String> pkgs = new ArrayList<>();
        switch (pm) {
            case "dnf" -> {
                cmd.add("dnf"); cmd.add("install"); cmd.add("-y");
                pkgs.add(kde ? "plasma-workspace-x11" : "gnome-session-xsession");
            }
            case "apt" -> {
                cmd.add("apt-get"); cmd.add("install"); cmd.add("-y");
                pkgs.add("xorg");
                pkgs.add(kde ? "plasma-workspace" : "gnome-session");
            }
            case "pacman" -> {
                cmd.add("pacman"); cmd.add("-S"); cmd.add("--noconfirm");
                pkgs.add("xorg-server");
                pkgs.add(kde ? "plasma-desktop" : "gnome-session");
            }
            case "zypper" -> {
                cmd.add("zypper"); cmd.add("--non-interactive"); cmd.add("install");
                pkgs.add("xorg-x11-server");
            }
            default -> {
                return null;
            }
        }
        cmd.addAll(pkgs);
        return cmd;
    }

    /** The first supported package manager found on PATH ({@code dnf}/{@code apt}/{@code pacman}/{@code zypper}), or null. */
    private static String detectPackageManager() {
        for (String pm : new String[]{"dnf", "apt-get", "pacman", "zypper"}) {
            if (onPath(pm)) return pm.equals("apt-get") ? "apt" : pm;
        }
        return null;
    }

    private static boolean onPath(String name) {
        try {
            return new ProcessBuilder("which", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
