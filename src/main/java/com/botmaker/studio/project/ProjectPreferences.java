package com.botmaker.studio.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.botmaker.studio.BotMakerStudio.PROJECTS_ROOT;

/**
 * User preferences for project management (last opened, recent projects).
 * Persisted as JSON in the projects root directory.
 * Renamed from the old ProjectConfig to avoid clash with the new ProjectConfig.
 */
public class ProjectPreferences {

    private static final Path CONFIG_FILE = PROJECTS_ROOT.resolve("botmaker-config.json");
    /** Same depth as the project MRU — long enough to cover a working set, short enough to stay scannable. */
    private static final int MAX_RECENT_LAUNCH_TARGETS = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private String lastOpenedProject;
    private List<ProjectEntry> recentProjects = new ArrayList<>();
    /**
     * Launch-target specs the user has picked before, newest first — the "Recently used" list in
     * {@code LaunchTargetDialog}. Global rather than per-project on purpose: the whole point is that a game
     * chosen once is re-selectable from the <em>next</em> project without walking the library picker again.
     */
    private List<String> recentLaunchTargets = new ArrayList<>();
    private Integer captureScreenIndex;
    private WindowState windowState;
    /** Remote-Pilot pairing token (global to Studio) → stable across restarts so paired phones don't rescan. */
    private String pilotToken;
    /** Last Remote-Pilot local bind port (global to Studio) → reused when free so the tailnet-direct URL is
     *  stable across restarts, completing the "don't rescan" story alongside {@link #pilotToken}. 0 = unset. */
    private int pilotPort;
    /** True once the user ticked "don't show again" on the Wayland → X11 notice. */
    private boolean hideWaylandNotice;
    /** How the project list is sorted, by {@code ProjectSelectionScreen.SortMode} name. Null = the default. */
    private String projectSortMode;

    public ProjectPreferences() {}

    // --- Accessors ---

    public String getLastOpenedProject() { return lastOpenedProject; }
    public void setLastOpenedProject(String name) { this.lastOpenedProject = name; }
    public List<ProjectEntry> getRecentProjects() { return recentProjects; }
    public List<String> getRecentLaunchTargets() { return recentLaunchTargets; }
    public void setRecentLaunchTargets(List<String> specs) {
        this.recentLaunchTargets = specs == null ? new ArrayList<>() : new ArrayList<>(specs);
    }
    public Integer getCaptureScreenIndex() { return captureScreenIndex; }
    public void setCaptureScreenIndex(Integer index) { this.captureScreenIndex = index; }
    public WindowState getWindowState() { return windowState; }
    public void setWindowState(WindowState windowState) { this.windowState = windowState; }
    public String getPilotToken() { return pilotToken; }
    public void setPilotToken(String token) { this.pilotToken = token; }
    public int getPilotPort() { return pilotPort; }
    public void setPilotPort(int port) { this.pilotPort = port; }
    public boolean isHideWaylandNotice() { return hideWaylandNotice; }
    public void setHideWaylandNotice(boolean hide) { this.hideWaylandNotice = hide; }
    public String getProjectSortMode() { return projectSortMode; }
    public void setProjectSortMode(String mode) { this.projectSortMode = mode; }

    public void addRecentProject(String projectName) {
        recentProjects.removeIf(p -> p.getName().equals(projectName));
        recentProjects.addFirst(new ProjectEntry(projectName));
        if (recentProjects.size() > 10) {
            recentProjects = recentProjects.subList(0, 10);
        }
    }

    /**
     * Moves {@code spec} to the front of the launch-target MRU, capped at {@link #MAX_RECENT_LAUNCH_TARGETS}.
     * Mirrors {@link #addRecentProject}: remove-then-{@code addFirst}, so re-picking an old target promotes it
     * rather than duplicating it. A null/blank spec (the "Clear target" path) is not recorded — clearing is not
     * a choice worth offering back.
     */
    public void addRecentLaunchTarget(String spec) {
        if (spec == null || spec.isBlank()) return;
        String trimmed = spec.trim();
        recentLaunchTargets.removeIf(trimmed::equals);
        recentLaunchTargets.addFirst(trimmed);
        if (recentLaunchTargets.size() > MAX_RECENT_LAUNCH_TARGETS) {
            recentLaunchTargets = new ArrayList<>(recentLaunchTargets.subList(0, MAX_RECENT_LAUNCH_TARGETS));
        }
    }

    // --- Persistence ---

    public static ProjectPreferences load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                return MAPPER.readValue(CONFIG_FILE.toFile(), ProjectPreferences.class);
            }
        } catch (Exception e) {
            System.err.println("Failed to load project preferences: " + e.getMessage());
        }
        return new ProjectPreferences();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            MAPPER.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("Failed to save project preferences: " + e.getMessage());
        }
    }

    // --- Static Convenience ---

    public static void updateLastOpened(String projectName) {
        ProjectPreferences prefs = load();
        prefs.setLastOpenedProject(projectName);
        prefs.addRecentProject(projectName);
        prefs.save();
    }

    public static String getLastOpened() {
        return load().getLastOpenedProject();
    }

    /** Records a launch-target spec in the global MRU. Called from every path that writes {@code launch.target}. */
    public static void recordLaunchTarget(String spec) {
        ProjectPreferences prefs = load();
        prefs.addRecentLaunchTarget(spec);
        prefs.save();
    }

    /** The launch-target specs picked before, newest first; empty when none have been. */
    public static List<String> recentLaunchTargets() {
        return load().getRecentLaunchTargets();
    }

    /** Index (into {@code Screen.getScreens()}) of the screen last chosen for capture, or {@code null}. */
    public static Integer getCaptureScreen() {
        return load().getCaptureScreenIndex();
    }

    public static void updateCaptureScreen(int index) {
        ProjectPreferences prefs = load();
        prefs.setCaptureScreenIndex(index);
        prefs.save();
    }

    /** The persisted Remote-Pilot pairing token (global), or {@code null} if never generated. */
    public static String loadPilotToken() {
        return load().getPilotToken();
    }

    /** Persists a new Remote-Pilot pairing token (pass {@code null} to clear/revoke). */
    public static void updatePilotToken(String token) {
        ProjectPreferences prefs = load();
        prefs.setPilotToken(token);
        prefs.save();
    }

    /** The persisted Remote-Pilot local bind port, or {@code 0} if never bound. */
    public static int loadPilotPort() {
        return load().getPilotPort();
    }

    /** Persists the Remote-Pilot local bind port so the next start reuses it when it's free. */
    public static void updatePilotPort(int port) {
        ProjectPreferences prefs = load();
        prefs.setPilotPort(port);
        prefs.save();
    }

    /** True if the user asked not to see the Wayland → X11 notice again. */
    public static boolean isWaylandNoticeHidden() {
        return load().isHideWaylandNotice();
    }

    public static void setWaylandNoticeHidden(boolean hide) {
        ProjectPreferences prefs = load();
        prefs.setHideWaylandNotice(hide);
        prefs.save();
    }

    /** The project list's sort order, or {@code null} to use the default. */
    public static String getSortMode() {
        return load().getProjectSortMode();
    }

    public static void updateSortMode(String mode) {
        ProjectPreferences prefs = load();
        prefs.setProjectSortMode(mode);
        prefs.save();
    }

    /** The persisted main-window geometry, or {@code null} if never saved. */
    public static WindowState loadWindowState() {
        return load().getWindowState();
    }

    public static void saveWindowState(WindowState state) {
        ProjectPreferences prefs = load();
        prefs.setWindowState(state);
        prefs.save();
    }

    // --- Inner Record ---

    /** Main-window geometry + maximized flag, so the app reopens where the user left it. */
    public static class WindowState {
        private double x;
        private double y;
        private double width;
        private double height;
        private boolean maximized;

        public WindowState() {}

        public WindowState(double x, double y, double width, double height, boolean maximized) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.maximized = maximized;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }
        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
        public boolean isMaximized() { return maximized; }
        public void setMaximized(boolean maximized) { this.maximized = maximized; }

        @JsonIgnore
        public boolean isUsable() { return width >= 400 && height >= 300; }
    }

    public static class ProjectEntry {
        private String name;
        private String lastOpened;

        public ProjectEntry() {}

        public ProjectEntry(String name) {
            this.name = name;
            this.lastOpened = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLastOpened() { return lastOpened; }
        public void setLastOpened(String lastOpened) { this.lastOpened = lastOpened; }
    }
}
