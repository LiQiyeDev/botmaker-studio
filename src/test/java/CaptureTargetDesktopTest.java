import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureExpr;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Verifies the first-class {@link DesktopTarget}: it emits {@code CaptureSource.desktop()}, round-trips
 * through {@code settings.json} as a stored project default, and names itself consistently.
 */
public class CaptureTargetDesktopTest {

    @Test
    void desktopTarget_emitsDesktopExpression() {
        assertEquals("com.botmaker.sdk.api.capture.CaptureSource.desktop()",
                CaptureExpr.of(new DesktopTarget()));
        // A null default must keep emitting desktop() too (back-compat).
        assertEquals("com.botmaker.sdk.api.capture.CaptureSource.desktop()", CaptureExpr.of((CaptureTarget) null));
    }

    @Test
    void desktopTarget_roundTripsAsDefault(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        StudioProjectSettings settings =
                new StudioProjectSettings(java.util.List.of(new DesktopTarget()), 0);
        settings.write(dir);

        StudioProjectSettings loaded = StudioProjectSettings.read(dir);
        assertInstanceOf(DesktopTarget.class, loaded.defaultTarget());
    }

    @Test
    void shortLabel_namesDesktopAndNull() {
        assertEquals("Whole desktop", CaptureTargetNames.shortLabel(new DesktopTarget()));
        assertEquals("Whole desktop", CaptureTargetNames.shortLabel(null));
    }
}
