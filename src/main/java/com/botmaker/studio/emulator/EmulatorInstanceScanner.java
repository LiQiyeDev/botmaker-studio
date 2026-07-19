package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.Platforms.DiscoveryReport;
import com.botmaker.shared.emulator.Platforms.PlatformStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Editor-side discovery of Android emulator instances, for the emulator pickers ({@link
 * com.botmaker.studio.ui.render.components.EmulatorPickerDialog}, the capture-source picker). All the actual
 * config/registry parsing lives in shared ({@code com.botmaker.shared.emulator}), consumed by both the SDK
 * (connect at runtime) and Studio (these pickers) — so there is no duplicated logic here: this just projects
 * shared's discovered instances into what the pickers render.
 *
 * <p>Instances are de-duplicated by a stable <em>identity</em> — {@code platformId + host + adbPort}, which is
 * unique per instance — <b>not</b> by display name. Keying on name silently collapsed two instances that
 * happen to share a name (a common default) into one, and let the first product in {@link Platforms#ALL} win a
 * shared name so a MuMu instance could render with the BlueStacks brand. Identity keying fixes both.
 *
 * <p>Best-effort: shared's discovery never throws, and on a machine with no emulator installed the list is
 * empty. It shells out to {@code reg query} + reads config files, so call it off the FX thread.
 */
public final class EmulatorInstanceScanner {

    /**
     * A full scan: every distinct instance the pickers should show, plus the per-product {@link PlatformStatus}
     * so the UI can tell the user what was detected ("MuMu: 2 instances · BlueStacks: not installed") instead
     * of a bare "nothing found".
     */
    public record Scan(List<EmulatorInstance> instances, List<PlatformStatus> statuses) {
        public Scan {
            instances = List.copyOf(instances);
            statuses = List.copyOf(statuses);
        }
    }

    /** Discovery + per-product status, instances de-duplicated by identity. Never throws. Call off the FX thread. */
    public Scan scan() {
        DiscoveryReport report = Platforms.discoverDetailed();
        return new Scan(dedupByIdentity(report.instances()), report.statuses());
    }

    /**
     * Every configured instance across every supported product, de-duplicated by identity (not name). Each keeps
     * its full {@link EmulatorInstance} (brand/{@code platformId}, host + port) so a picker can render the
     * product brand and probe liveness. Never throws; empty when nothing is installed. Call off the FX thread.
     */
    public List<EmulatorInstance> instances() {
        return dedupByIdentity(Platforms.discoverAll());
    }

    /** Distinct instance names across every supported product, in discovery order. Never throws. */
    public List<String> instanceNames() {
        Set<String> names = new LinkedHashSet<>();
        for (EmulatorInstance instance : instances()) {
            if (instance.name() != null && !instance.name().isBlank()) {
                names.add(instance.name());
            }
        }
        return new ArrayList<>(names);
    }

    /** De-duplicates by {@code platformId@host:adbPort} (unique per instance), preserving discovery order. */
    private static List<EmulatorInstance> dedupByIdentity(List<EmulatorInstance> all) {
        Map<String, EmulatorInstance> byIdentity = new LinkedHashMap<>();
        for (EmulatorInstance instance : all) {
            byIdentity.putIfAbsent(identity(instance), instance);
        }
        return new ArrayList<>(byIdentity.values());
    }

    private static String identity(EmulatorInstance instance) {
        return instance.platformId() + "@" + instance.host() + ":" + instance.adbPort();
    }
}
