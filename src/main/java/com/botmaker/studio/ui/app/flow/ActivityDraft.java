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

    private double x;
    private double y;

    public ActivityDraft(String name, String description, boolean enabled, List<ActivityVariable> params,
                         List<String> outcomes, boolean goHome, double x, double y) {
        this.name.set(name);
        this.description.set(description == null ? "" : description);
        this.enabled.set(enabled);
        this.params.setAll(params);
        this.outcomes.setAll(outcomes);
        this.goHome.set(goHome);
        this.x = x;
        this.y = y;
    }

    /** A draft of an existing activity, placed at {@code (x, y)}. */
    public static ActivityDraft of(ActivityDefinition def, double x, double y) {
        return new ActivityDraft(def.name(), def.description(), def.enabled(), def.params(), def.outcomes(),
                def.goHome(), x, y);
    }

    /** The immutable definition this draft currently describes. */
    public ActivityDefinition toDefinition() {
        return new ActivityDefinition(name.get(), enabled.get(), description.get(), List.copyOf(params),
                false, List.copyOf(outcomes), goHome.get());
    }

    /**
     * Every outcome this activity's card has a port for: the implicit default first, then the declared ones.
     * Mirrors {@link ActivityDefinition#allOutcomes()} — the ports and the generated enum are the same list.
     */
    public List<String> allOutcomes() {
        List<String> all = new ArrayList<>(outcomes.size() + 1);
        all.add(FlowEdge.NEXT_OUTCOME);
        for (String o : outcomes) {
            if (!all.contains(o)) all.add(o);
        }
        return all;
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty descriptionProperty() { return description; }
    public BooleanProperty enabledProperty() { return enabled; }
    public BooleanProperty goHomeProperty() { return goHome; }
    public ObservableList<ActivityVariable> params() { return params; }
    public ObservableList<String> outcomes() { return outcomes; }

    public String name() { return name.get(); }
    public String description() { return description.get(); }
    public boolean enabled() { return enabled.get(); }
    public boolean goHome() { return goHome.get(); }

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
