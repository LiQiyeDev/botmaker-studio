package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.StudioProjectSettings.Resolution;
import com.botmaker.studio.ui.app.ResolutionChoices;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolution dropdown says what the project will be saved with.
 *
 * <p>Both dropdowns (Project Settings and New Project) used to hold the landscape catalog and swap the pair
 * only when the value was read, so a portrait project offered, displayed and confirmed <b>1920 × 1080</b> for a
 * resolution stored as 1080×1920. Nothing downstream was wrong — every label the user could see was. The fix is
 * to re-orient the <em>items</em>, which is what these assertions pin: a converter reading the toggle would
 * render correctly only until the toggle next changed, since a {@code ComboBox} consults its converter when a
 * cell refreshes and not when something elsewhere flips.
 */
class ResolutionOrientationTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // No scene needed — these exercise the control's model, not its rendering. The harness is here for the
        // FX thread the ComboBox's selection model demands.
    }

    @Test
    void portraitTurnsEveryEntryAndTheSelectionOnItsSide() {
        interact(() -> {
            ComboBox<Resolution> combo = new ComboBox<>();
            combo.getItems().addAll(ResolutionChoices.LANDSCAPE);
            combo.getSelectionModel().select(ResolutionChoices.DEFAULT);

            ResolutionChoices.orient(combo, false);

            assertEquals(new Resolution(1080, 1920), combo.getSelectionModel().getSelectedItem());
            assertEquals("1080 × 1920", ResolutionChoices.label(combo.getSelectionModel().getSelectedItem()));
            combo.getItems().forEach(r -> assertTrue(r.height() > r.width(), "every entry is portrait: " + r));
        });
    }

    @Test
    void goingBackToLandscapeRestoresTheCatalogExactly() {
        interact(() -> {
            ComboBox<Resolution> combo = new ComboBox<>();
            combo.getItems().addAll(ResolutionChoices.LANDSCAPE);
            combo.getSelectionModel().select(new Resolution(1280, 720));

            ResolutionChoices.orient(combo, false);
            ResolutionChoices.orient(combo, true);

            assertEquals(ResolutionChoices.LANDSCAPE, combo.getItems(), "a round trip is the identity");
            assertEquals(new Resolution(1280, 720), combo.getSelectionModel().getSelectedItem());
        });
    }

    /** An empty selection is a real state (the Clear button), and re-orienting must not invent one. */
    @Test
    void clearingTheSelectionSurvivesAToggle() {
        interact(() -> {
            ComboBox<Resolution> combo = new ComboBox<>();
            combo.getItems().addAll(ResolutionChoices.LANDSCAPE);
            combo.getSelectionModel().clearSelection();

            ResolutionChoices.orient(combo, false);

            assertNull(combo.getSelectionModel().getSelectedItem());
        });
    }
}
