package incari.mesher;

/**
 * What the mesher knows about a block state, one bit each ({@link MeshTables#stateFlags}). The
 * table builder reads every one of them from the game.
 */
public final class StateFlags {

    private StateFlags() {}

    public static final int AIR = 1;
    /** Drawn from its block model (the game's {@code RenderShape.MODEL}). */
    public static final int MODEL = 1 << 1;
    /** Fills its block and hides what's behind it ({@code isSolidRender}). */
    public static final int SOLID_RENDER = 1 << 2;
    /** Lets light through at corners ({@code isLightPermeable}): ambient occlusion reads it. */
    public static final int LIGHT_PERMEABLE = 1 << 3;
    /** Always drawn at full brightness ({@code emissiveRendering}). */
    public static final int EMISSIVE = 1 << 4;
    /** The legacy "solid" material flag fluids use for their height at a neighbor. */
    public static final int LEGACY_SOLID = 1 << 5;
    /** In the {@code blocks_fluid_flow} tag. */
    public static final int BLOCKS_FLUID_FLOW = 1 << 6;
    /** An ice block: never a solid face for flowing fluids. */
    public static final int ICE = 1 << 7;
    /**
     * Its collision shape, and so its shade, full-block test and sturdy faces, depend on where it
     * is: the snapshot carries them for each position ({@link Snapshot#dynamicCount}).
     */
    public static final int DYNAMIC_SHAPE = 1 << 8;
    /** Leaves drawn opaque (the "cutout leaves" option off): every quad goes in the solid layer. */
    public static final int FORCE_SOLID_LAYER = 1 << 9;
    /** Its collision shape is the full block ({@code isCollisionShapeFullBlock}). */
    public static final int COLLISION_FULL = 1 << 10;
    /** A falling fluid ({@code FlowingFluid.FALLING}). */
    public static final int FLUID_FALLING = 1 << 11;
    /** Its model is tinted by where it is: the snapshot carries the colors. */
    public static final int TINTED = 1 << 12;
    /** Its model couldn't be put in the tables: a section with it is meshed by the game instead. */
    public static final int UNSUPPORTED = 1 << 13;
    /** Its faces hide from some neighbors by a rule of its block ({@code skipRendering}). */
    public static final int SKIP_RULE = 1 << 14;
    /** Glass-like or leaves: a fluid's side against it shows the fluid's overlay texture. */
    public static final int FLUID_OVERLAY = 1 << 15;

    /** Six bits from here: {@code isFaceSturdy} (full support) toward each direction. */
    public static final int STURDY_SHIFT = 16;
    /** Six bits from here: its own shape hides a fluid face toward each direction. */
    public static final int HIDES_OWN_FLUID_SHIFT = 22;
}
