package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.Platforms;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Editor-side discovery of Android emulator instance <em>names</em>, for the {@link
 * com.botmaker.studio.ui.render.components.EmulatorArgPicker}. All the actual config/registry parsing lives in
 * shared ({@code com.botmaker.shared.emulator}), consumed by both the SDK (connect at runtime) and Studio
 * (this picker) — so there is no duplicated logic here: this just projects shared's discovered instances down
 * to a de-duplicated list of names.
 *
 * <p>Best-effort: shared's {@link Platforms#discoverAll()} never throws, and on a machine with no emulator
 * installed the list is empty. It shells out to {@code reg query} + reads config files, so call it off the FX
 * thread.
 */
public final class EmulatorInstanceScanner {

    /** Distinct instance names across every supported product, in discovery order. Never throws. */
    public List<String> instanceNames() {
        Set<String> names = new LinkedHashSet<>();
        for (EmulatorInstance instance : Platforms.discoverAll()) {
            if (instance.name() != null && !instance.name().isBlank()) {
                names.add(instance.name());
            }
        }
        return new ArrayList<>(names);
    }
}
