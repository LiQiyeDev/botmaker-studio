package com.botmaker.studio.project;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.validation.DiagnosticsManager;

/**
 * The open project's services, as one immutable value — what the window is built <em>against</em>.
 *
 * <p>{@link BotProject} is the composition root and the owner: it constructs these in order, fills its lazy
 * ones, and {@link BotProject#close() closes} what outlives the JVM. This is the read-only view of the same
 * set, taken once the wiring is finished and handed to the UI, which needs to <em>use</em> the services and
 * must never be able to end them. That is the whole difference between the two types, and the reason this one
 * is a {@code record} with no behaviour.
 *
 * <p>It exists because the shell's constructors had grown to 11 and 13 parameters — the same run of services,
 * re-listed at each layer, four of them only ever forwarded. A record of this width edges toward a service
 * locator, which cuts against the module's "pass dependencies in via constructors" style; it is accepted here
 * because the alternative in practice was those parameter lists. Keep it immutable, give it no behaviour, and
 * let nothing reach it statically.
 *
 * <p>The {@code Stage} is deliberately <b>not</b> in here: a window is not a project service, and keeping it
 * out is what lets this record — and the package it lives in — stay free of JavaFX.
 */
public record StudioContext(ProjectConfig config,
                            ProjectState state,
                            EventBus eventBus,
                            DiagnosticsManager diagnosticsManager,
                            BlockDragAndDropManager dragAndDropManager,
                            ProjectAnalyzer projectAnalyzer,
                            LibraryService libraryService,
                            ActivityService activityService,
                            ProjectSettingsService projectSettingsService,
                            SdkSurfaceService sdkSurfaceService,
                            CodeEditorService codeEditorService,
                            CodeExecutionService codeExecutionService) {
}
