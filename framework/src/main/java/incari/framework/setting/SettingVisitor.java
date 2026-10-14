package incari.framework.setting;

/**
 * One method per kind of setting. The generated settings screen, the config codec and anything
 * else that handles every kind implements this, so a new kind fails to compile wherever it isn't
 * handled yet.
 *
 * @param <R> what each visit returns
 */
public interface SettingVisitor<R> {

    /** An on/off value. */
    R visit(BooleanSetting setting);

    /** A whole number in a range. */
    R visit(IntSetting setting);

    /** A decimal number in a range. */
    R visit(FloatSetting setting);

    /** One of a fixed set of choices. */
    R visit(ChoiceSetting<?> setting);

    /** A color. */
    R visit(ColorSetting setting);

    /** A key or mouse button. */
    R visit(KeySetting setting);

    /** A button, with no value. */
    R visit(ActionSetting setting);

    /** A square of cells, each on or off. */
    R visit(GridSetting setting);
}
