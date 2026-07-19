package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.Platforms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Every configured instance across every supported product, in discovery order, de-duplicated by name (the
     * first descriptor for a given name wins — it carries the brand + ADB port the picker dialog shows). Unlike
     * {@link #instanceNames()} this keeps the full {@link EmulatorInstance} (brand/{@code platformId}, host +
     * port) so a picker can render the product brand and probe liveness. Never throws; empty when nothing is
     * installed. Call off the FX thread (registry + config reads).
     */
    public List<EmulatorInstance> instances() {
        Map<String, EmulatorInstance> byName = new LinkedHashMap<>();
        for (EmulatorInstance instance : Platforms.discoverAll()) {
            if (instance.name() != null && !instance.name().isBlank()) {
                byName.putIfAbsent(instance.name(), instance);
            }
        }
        return new ArrayList<>(byName.values());
    }
}
