package incari.framework.setting;

/**
 * A button in the settings screen that does something rather than holding a value: Waypoints'
 * "Manage waypoints". Nothing is stored; the profile leaves it out.
 */
public final class ActionSetting extends Setting<ActionSetting> {

    private final String label;
    private final Runnable action;

    /**
     * @param id unique among the settings, like any setting's
     * @param name what the settings screen calls the row
     * @param label what the button says
     * @param action what pressing it does; game thread
     */
    public ActionSetting(String id, String name, String label, Runnable action) {
        super(id, name);
        this.label = label;
        this.action = action;
    }

    /** What the button says. */
    public String label() {
        return label;
    }

    /** Presses the button. */
    public void run() {
        action.run();
    }

    /** Always: there's no value. */
    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public void reset() {}

    @Override
    public <R> R accept(SettingVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    protected ActionSetting self() {
        return this;
    }
}
