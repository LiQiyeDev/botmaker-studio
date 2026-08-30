package com.botmaker.studio.project.capture;

import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.studio.project.StudioProjectSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the whole-desktop target: it emits {@code CaptureSource.desktop()}, round-trips through
 * {@code capture.json} as a stored project default, and names itself consistently.
 */
public class CaptureTargetDesktopTest {

    @Test
    void desktopTarget_emitsDesktopExpression() {
        assertEquals("com.botmaker.sdk.api.capture.CaptureSource.desktop()",
                CaptureExpr.of(CaptureTargetModel.desktop()));
        // A null default must keep emitting desktop() too (back-compat).
        assertEquals("com.botmaker.sdk.api.capture.CaptureSource.desktop()",
                CaptureExpr.of((CaptureTargetModel) null));
    }

    @Test
    void desktopTarget_roundTripsAsDefault(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        StudioProjectSettings settings =
                new StudioProjectSettings(java.util.List.of(CaptureTargetModel.desktop()), 0);
        settings.write(dir);

        StudioProjectSettings loaded = StudioProjectSettings.read(dir);
        assertTrue(loaded.defaultTarget().isDesktop());
    }

    @Test
    void shortLabel_namesDesktopAndNull() {
        assertEquals("Whole desktop", CaptureTargetModel.desktop().shortLabel());
        assertEquals("Whole desktop", CaptureTargetModel.shortLabelOf(null));
    }
}
