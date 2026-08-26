package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowEdge;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;

/**
 * One activity while it is being edited on the Activity Flow canvas: its schema (name, description, params),
 * whether it is enabled, and where its card sits. Mutable and observable — the node card, the side panel and
 * the preset bar all bind to the same draft, so a change in one is visible in the others immediately. It is
 * converted back to an immutable {@link ActivityDefinition} + {@link com.botmaker.studio.project.activity.FlowNode}
 * only on save.
 */
public final class ActivityDraft {

    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty description = new SimpleStringProperty("");
    private final BooleanProperty enabled = new SimpleBooleanProperty();
    private final ObservableList<ActivityVariable> params = FXCollections.observableArrayList();

    /**
     * The named outcomes this activity can report, excluding the implicit {@link FlowEdge#NEXT_OUTCOME}.
     * Observable because the card grows one output port per outcome — adding one in the side panel has to put
     * a port on the card immediately, or there is nothing to drag a wire from.
     */
    private final ObservableList<String> outcomes = FXCollections.observableArrayList();

    /** Run the project's {@code GoHome.run()} before this activity. On by default; see the card's tick. */
    private final BooleanProperty goHome = new SimpleBooleanProperty(true);

    /** Let the popup guard dismiss popups during this activity. On by default; see the card's tick. */
    private final BooleanProperty popupCheck = new SimpleBooleanProperty(true);

    private double x;
    private double y;

    public ActivityDraft(String name, String description, boolean enabled, List<ActivityVariable> params,
                         List<String> outcomes, boolean goHome, boolean popupCheck, double x, double y) {
        this.name.set(name);
        this.description.set(description == null ? "" : description);
        this.enabled.set(enabled);
        this.params.setAll(params);
        this.outcomes.setAll(outcomes);
        this.goHome.set(goHome);
        this.popupCheck.set(popupCheck);
        this.x = x;
        this.y = y;
    }

    /** A draft of an existing activity, placed at {@code (x, y)}. */
    public static ActivityDraft of(ActivityDefinition def, double x, double y) {
        return new ActivityDraft(def.name(), def.description(), def.enabled(), List.of(), def.outcomes(),
                def.goHome(), def.popupCheck(), x, y);
    }

    /** The immutable definition this draft currently describes. */
    public ActivityDefinition toDefinition() {
        return new ActivityDefinition(name.get(), enabled.get(), description.get(),
                List.copyOf(outcomes), goHome.get(), popupCheck.get());
    }

    /**
     * Every constant of this activity's generated {@code Outcome} enum: the implicit default first, then the
     * declared ones. Mirrors {@link ActivityDefinition#allOutcomes()}.
     */
    public List<String> allOutcomes() {
        List<String> all = new ArrayList<>(outcomes.size() + 1);
        all.add(FlowEdge.NEXT_OUTCOME);
        for (String o : outcomes) {
            if (FlowEdge.DISABLED_OUTCOME.equals(o)) continue; // a port, never an Outcome constant
            if (!all.contains(o)) all.add(o);
        }
        return all;
    }

    /**
     * Every outcome this activity's card has a port for: {@link #allOutcomes()}, then
     * {@link FlowEdge#DISABLED_OUTCOME} last. Mirrors {@link ActivityDefinition#flowPorts()}.
     *
     * <p>This is the list the canvas draws ports from <em>and</em> the list it prunes wires against, which is
     * what stops a {@code DISABLED} wire from being deleted the moment it is drawn.
     */
    public List<String> flowPorts() {
        List<String> ports = new ArrayList<>(allOutcomes());
        ports.add(FlowEdge.DISABLED_OUTCOME);
        return ports;
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty descriptionProperty() { return description; }
    public BooleanProperty enabledProperty() { return enabled; }
    public BooleanProperty goHomeProperty() { return goHome; }

    public BooleanProperty popupCheckProperty() { return popupCheck; }
    public ObservableList<ActivityVariable> params() { return params; }
    public ObservableList<String> outcomes() { return outcomes; }

    public String name() { return name.get(); }
    public String description() { return description.get(); }
    public boolean enabled() { return enabled.get(); }
    public boolean goHome() { return goHome.get(); }

    public boolean popupCheck() { return popupCheck.get(); }

    public double x() { return x; }
    public double y() { return y; }

    public void moveTo(double newX, double newY) {
        this.x = newX;
        this.y = newY;
    }

    @Override
    public String toString() {
        return name.get();
    }
}
