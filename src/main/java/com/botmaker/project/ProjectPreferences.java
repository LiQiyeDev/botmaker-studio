package com.botmaker.project;

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

import static com.botmaker.BotMakerStudio.PROJECTS_ROOT;

/**
 * User preferences for project management (last opened, recent projects).
 * Persisted as JSON in the projects root directory.
 * Renamed from the old ProjectConfig to avoid clash with the new ProjectConfig.
 */
public class ProjectPreferences {

    private static final Path CONFIG_FILE = PROJECTS_ROOT.resolve("botmaker-config.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private String lastOpenedProject;
    private List<ProjectEntry> recentProjects = new ArrayList<>();
    private Integer captureScreenIndex;
    private WindowState windowState;

    public ProjectPreferences() {}

    // --- Accessors ---

    public String getLastOpenedProject() { return lastOpenedProject; }
    public void setLastOpenedProject(String name) { this.lastOpenedProject = name; }
    public List<ProjectEntry> getRecentProjects() { return recentProjects; }
    public Integer getCaptureScreenIndex() { return captureScreenIndex; }
    public void setCaptureScreenIndex(Integer index) { this.captureScreenIndex = index; }
    public WindowState getWindowState() { return windowState; }
    public void setWindowState(WindowState windowState) { this.windowState = windowState; }

    public void addRecentProject(String projectName) {
        recentProjects.removeIf(p -> p.getName().equals(projectName));
        recentProjects.addFirst(new ProjectEntry(projectName));
        if (recentProjects.size() > 10) {
            recentProjects = recentProjects.subList(0, 10);
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

    /** Index (into {@code Screen.getScreens()}) of the screen last chosen for capture, or {@code null}. */
    public static Integer getCaptureScreen() {
        return load().getCaptureScreenIndex();
    }

    public static void updateCaptureScreen(int index) {
        ProjectPreferences prefs = load();
        prefs.setCaptureScreenIndex(index);
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