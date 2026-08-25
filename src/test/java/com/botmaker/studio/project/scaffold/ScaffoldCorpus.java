package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.TemplateConstants;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.FlowNode;
import com.botmaker.studio.services.ActivityService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole projects, as Studio would write them — the corpus {@link ScaffoldCompileTest} compiles.
 *
 * <h2>Whole projects, and more than one</h2>
 *
 * <p>Both things matter. <b>Whole</b>, because the interesting failures are between files: a stub's
 * {@code Outcome} constant routed from the driver, {@code Activities} named by a stub's {@code isEnabled},
 * {@code ActivityRegistry}'s singletons held by the driver's table. Rendering one file and asserting on its
 * text cannot see any of that. <b>More than one</b>, because a generated file's shape depends on the model:
 * an empty flow emits a different table from a branching one, and a project with no variables emits an
 * {@code Activities} with no {@code Wire} call in it at all. A single fixture would leave most of the
 * generators' branches unexercised, which is exactly what the corpus this replaced did.
 *
 * <p>This is the whole of what replaced {@code ScaffoldScan} — 484 lines of JDT visitor that parsed the
 * generators' output to work out which SDK members it named, because the output was assembled from text
 * blocks and nothing else could be asked. The output is assembled from the SDK's own templates now, so the
 * question is put to {@code javac} instead.
 */
final class ScaffoldCorpus {

    private ScaffoldCorpus() {}

    /**
     * One project to render, named so a failure says which shape broke.
     *
     * @param activities null for {@link ProjectTemplate#EMPTY}, whose scaffold has no activity model at all
     */
    record Model(String name, ProjectTemplate template, ActivitiesConfig activities) {}

    /** The model whose rendered files carry an example of every fragment Studio injects. */
    static final String RICHEST = "one activity, one variable of every storable type";

    /** Every shape worth rendering. Add one here and both tests pick it up. */
    static List<Model> models() {
        return List.of(
                new Model("empty project", ProjectTemplate.EMPTY, null),
                new Model("game bot, no activities yet", ProjectTemplate.GAME_BOT, ActivitiesConfig.empty()),
                new Model(RICHEST, ProjectTemplate.GAME_BOT, everyVariableType()),
                new Model("a branching flow", ProjectTemplate.GAME_BOT, branchingFlow()));
    }

    /** One model by name — for a test that needs a particular shape rather than all of them. */
    static Model named(String name) {
        return models().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such model: " + name));
    }

    /**
     * One activity holding a variable of every type that can be stored, so a type that becomes storable turns
     * up here on its own rather than waiting for someone to remember it. It is the model that exercises
     * {@code Activities} — a field declaration and a {@code Wire} read per type, which is most of what
     * Studio still injects.
     */
    private static ActivitiesConfig everyVariableType() {
        // One of each single value *and* one list: the two go through different Wire members (Wire.many with
        // a method reference, rather than Wire.one), so a corpus with no list leaves that half unexercised.
        List<ActivityVariable> variables = new ArrayList<>();
        for (BotType type : BotType.storableTypes()) {
            variables.add(ActivityVariable.create("each" + type.name(), BotType.Choice.of(type)));
        }
        variables.add(ActivityVariable.create("someKeys", BotType.Choice.listOf(BotType.KEY)));

        ActivityDefinition mining = ActivityDefinition.create("Mining", "Dig.")
                .withGoHome(true).withPopupCheck(true);
        return ActivitiesConfig.of(List.of(mining), variables)
                .withFlow(new ActivityFlow(List.of(new FlowNode("Mining", 0, 0)),
                        List.of(new FlowEdge("Mining", "Mining", FlowEdge.NEXT_OUTCOME))));
    }

    /**
     * Every shape the flow table has to express, in one flow: Mining branches on two outcomes, Travel loops
     * back to it, Smelt wires nothing (so reaching it ends the run), Idle is placed but unreachable, and the
     * two per-activity ticks disagree — Mining does not go home, Travel does not check for popups.
     */
    private static ActivitiesConfig branchingFlow() {
        ActivityDefinition mining = ActivityDefinition.create("Mining", "")
                .withOutcomes(List.of("BAG_FULL", "NO_ORE")).withGoHome(false);
        ActivityDefinition travel = ActivityDefinition.create("Travel", "").withPopupCheck(false);
        return ActivitiesConfig.of(
                        List.of(mining,
                                ActivityDefinition.create("Smelt", ""),
                                travel,
                                ActivityDefinition.create("Idle", "")),
                        List.of(ActivityVariable.create("oreLimit", BotType.Choice.of(BotType.WHOLE_NUMBER))))
                .withFlow(new ActivityFlow(
                        List.of(new FlowNode("Mining", 0, 0), new FlowNode("Smelt", 0, 0),
                                new FlowNode("Travel", 0, 0), new FlowNode("Idle", 0, 0)),
                        List.of(new FlowEdge("Mining", "Smelt", "BAG_FULL"),
                                new FlowEdge("Mining", "Travel", "NO_ORE"),
                                new FlowEdge("Travel", "Mining", FlowEdge.NEXT_OUTCOME)),
                        "Mining", 250));
    }

    /**
     * Renders {@code model} as {@code absolute file -> source}, exactly where a real project would hold each
     * file: the seeds from {@link ProjectCreator}, the generated ones from {@link ActivityService}, and a
     * stub per activity. Nothing is written to disk here — that is the compiling test's business.
     */
    static Map<Path, String> render(Model model, ProjectConfig config) throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, String> seed
                : ProjectCreator.sourcesFor(model.template(), config.className(), config.packageName())
                .entrySet()) {
            files.put(config.mainPackageDir().resolve(seed.getKey()), seed.getValue());
        }
        if (model.activities() == null) return files;

        ActivitiesConfig cfg = model.activities();
        ActivityService service = new ActivityService(config, null, null);
        files.put(config.activitiesSourceFile(), service.generateActivitiesSource(cfg));
        files.put(config.parametersSourceFile(), service.generateParametersSource(cfg));
        files.put(config.activityRegistrySourceFile(), service.generateRegistrySource(cfg));
        files.put(config.flowDriverSourceFile(), service.generateDriverSource(cfg));
        for (ActivityDefinition activity : cfg.activities()) {
            files.put(config.activitiesPackageDir().resolve(activity.name() + ".java"),
                    service.generateStubSource(activity));
        }
        // Names no SDK element at all — its constants are plain strings — but it is part of what a project
        // has to compile as a whole, and the day it starts naming one this corpus already covers it.
        files.put(config.templatesSourceFile(),
                TemplateConstants.generateSource(config.packageName(), List.of("home", "mail")));
        return files;
    }
}
