package com.botmaker.studio.project.capture;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A saved destination for editor-time screen interaction (image capture, region/point picking): a whole
 * monitor ({@link ScreenTarget}), a specific application window ({@link WindowTarget}), or the whole
 * virtual desktop across every monitor ({@link DesktopTarget}). The project remembers a list of these
 * plus which one is the default, so pickers stop re-asking which screen to use and can target a window.
 *
 * <p>Persisted polymorphically inside {@link com.botmaker.studio.project.StudioProjectSettings} via a
 * {@code "type"} discriminator. A screen is identified by its index into {@code Screen.getScreens()};
 * a window by a case-insensitive title substring, matching the SDK's runtime {@code Window.find(...)};
 * the desktop carries no fields. A {@code null} target is treated the same as a {@code DesktopTarget}
 * (whole desktop) throughout, but {@code DesktopTarget} lets "the whole desktop" be an explicit, storable,
 * selectable choice.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CaptureTarget.ScreenTarget.class, name = "screen"),
        @JsonSubTypes.Type(value = CaptureTarget.WindowTarget.class, name = "window"),
        @JsonSubTypes.Type(value = CaptureTarget.DesktopTarget.class, name = "desktop")
})
public sealed interface CaptureTarget
        permits CaptureTarget.ScreenTarget, CaptureTarget.WindowTarget, CaptureTarget.DesktopTarget {

    /** A short human-readable label for menus / settings rows. */
    String label();

    /** A whole monitor, by its index into {@code javafx.stage.Screen.getScreens()}. */
    record ScreenTarget(int index) implements CaptureTarget {
        @Override
        public String label() {
            return "Screen " + (index + 1);
        }
    }

    /** A specific application window, matched at capture time by a case-insensitive title substring. */
    record WindowTarget(String titleSubstring) implements CaptureTarget {
        @Override
        public String label() {
            return "Window: " + (titleSubstring == null || titleSubstring.isBlank() ? "(any)" : titleSubstring);
        }
    }

    /** The whole virtual desktop — every monitor combined ({@code CaptureSource.desktop()}). */
    record DesktopTarget() implements CaptureTarget {
        @Override
        public String label() {
            return "Whole desktop (all monitors)";
        }
    }
}
