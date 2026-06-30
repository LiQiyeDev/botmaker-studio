package com.botmaker.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application-wide constants
 */
public class Constants {

    // Debugger Configuration
    public static final int DEBUGGER_MAX_CONNECT_RETRIES = 10;
    public static final int DEBUGGER_RETRY_DELAY_MS = 250;
    public static final Path PROJECTS_ROOT = Paths.get(System.getProperty("user.home"), "BotMakerProjects").toAbsolutePath();

    /** Archived (soft-deleted) projects live here. A dot-dir, so the top-level project scan skips it. */
    public static final Path ARCHIVE_ROOT = PROJECTS_ROOT.resolve(".archive");

    private Constants() {} // Prevent instantiation
}