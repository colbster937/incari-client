package incari.client.render.mesher;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import incari.mesher.Snapshot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Closed chests drawn as part of their section's terrain (ADR 0030), Enhanced Block Entities'
 * idea. The game draws every chest in sight every frame as a block entity: its render state
 * extracted, its model posed and drawn. A closed chest doesn't move, so here its section's snapshot
 * takes the chest's model as finished quads instead (posed, textured and lit as its renderer would
 * draw it), and leaves the chest out of the section's block entities, which the game then never
 * draws. When a chest starts opening its section is meshed again without it, and its renderer
 * animates the lid; once it has closed, again with it.
 *
 * <p>The chests' textures join the blocks atlas, which the terrain draws from. What differs from
 * the game's drawing: the entity shader's lighting by the surface's direction is worked out per
 * corner into the vertex color, to the nearest of 256 steps; terrain's texture is mipmapped where an
 * entity's isn't; a chest breaking shows no cracks; and a chest starts to open a few frames late,
 * when its section's new mesh arrives. Only the page turns it on: the desktop's meshes are compared
 * with the game's own.
 *
 * <p>Runs where the section's snapshot is made: the game's background pool, which in the page is
 * the page's one thread.
 */
public final class ChestBaking {

    /** Ticks a closing lid takes (it closes a tenth a tick), and one more. */
    private static final int CLOSE_TICKS = 11;

    // The entity shader's level lighting (minecraft:light.glsl): two directions, a power and an
    // ambient term, the second direction pointing down in the Nether.
    private static final Vector3fc LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3fc LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3fc NETHER_LIGHT_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();
    private static final float LIGHT_POWER = 0.6F;
    private static final float AMBIENT = 0.4F;

    private static volatile boolean enabled;

    private static final Map<ChestType, ChestModel> MODELS = new EnumMap<>(ChestType.class);
    private static @Nullable EntityModelSet modelsFrom;
    private static final Capture CAPTURE = new Capture();
    private static final List<Closing> CLOSING = new ArrayList<>();

    private ChestBaking() {}

    /** The page's: chests meshed into terrain (and their textures into the blocks atlas). */
    public static void enable() {
        enabled = true;
    }

    /**
     * Hook ({@code SpriteSourceList.load}): whether the atlas being listed should take the chests'
     * sprites too.
     */
    public static boolean intoBlocksAtlas(Identifier atlasId) {
        return enabled && AtlasIds.BLOCKS.equals(atlasId);
    }

    /**
     * Adds a closed chest's model to the snapshot, at block {@code x y z} of the section.
     *
     * @return true if it was added: the section leaves it out of its block entities
     */
    @SuppressWarnings({"unchecked", "rawtypes", "ReferenceEquality"}) // the renderer; the missing sprite
    static boolean bake(BlockEntity blockEntity, Snapshot snapshot, int x, int y, int z) {
        if (!enabled || !(blockEntity instanceof LidBlockEntity) || blockEntity.getLevel() == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntityRenderer renderer =
                minecraft.getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (!(renderer instanceof ChestRenderer chestRenderer)) {
            return false;
        }
        ChestRenderState state = chestRenderer.createRenderState();
        // Closed now and a tick ago: a lid still closing is left to the renderer.
        chestRenderer.extractRenderState(blockEntity, state, 0.0F, Vec3.ZERO, null);
        if (state.open != 0.0F) {
            return false;
        }
        chestRenderer.extractRenderState(blockEntity, state, 1.0F, Vec3.ZERO, null);
        if (state.open != 0.0F) {
            return false;
        }
        TextureAtlas blocks = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite sprite =
                blocks.getSprite(Sheets.chooseSprite(state.material, state.type).texture());
        if (sprite == blocks.missingSprite()) {
            return false;
        }
        ChestModel model = model(minecraft.getEntityModels(), state.type);
        model.setupAnim(0.0F);
        PoseStack pose = new PoseStack();
        pose.translate(x, y, z);
        pose.mulPose(ChestRenderer.modelTransformation(state.facing));
        boolean nether = blockEntity.getLevel().dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER;
        CAPTURE.begin(snapshot, sprite, state.lightCoords, nether ? NETHER_LIGHT_1 : LIGHT_1);
        model.renderToBuffer(pose, CAPTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, -1);
        return true;
    }

    @SuppressWarnings("ReferenceEquality") // the model set the game holds, by identity
    private static ChestModel model(EntityModelSet models, ChestType type) {
        if (modelsFrom != models) {
            MODELS.clear();
            modelsFrom = models;
        }
        return MODELS.computeIfAbsent(type, t -> new ChestModel(models.bakeLayer(ChestRenderer.LAYERS.select(t))));
    }

    /**
     * Hook ({@code ChestBlockEntity} and {@code EnderChestBlockEntity}'s {@code triggerEvent}): a
     * chest's lid was told to open or close. Opening, its section (and its other half's) is meshed
     * again now, without it; closing, once the lid is down.
     */
    public static void lidChanged(BlockEntity blockEntity, boolean open) {
        Level level = blockEntity.getLevel();
        if (!enabled || level == null || !level.isClientSide()) {
            return;
        }
        if (open) {
            remesh(blockEntity);
        } else {
            CLOSING.add(new Closing(blockEntity, level.getGameTime() + CLOSE_TICKS));
        }
    }

    /** Once a client tick: chests whose lids have had time to close are meshed in again. */
    @SuppressWarnings("ReferenceEquality") // the level object itself
    public static void tick(@Nullable Level level) {
        if (CLOSING.isEmpty()) {
            return;
        }
        Iterator<Closing> it = CLOSING.iterator();
        while (it.hasNext()) {
            Closing closing = it.next();
            if (level == null
                    || closing.blockEntity().getLevel() != level
                    || closing.blockEntity().isRemoved()) {
                it.remove();
            } else if (level.getGameTime() >= closing.at()) {
                it.remove();
                remesh(closing.blockEntity());
            }
        }
    }

    private static void remesh(BlockEntity blockEntity) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = blockEntity.getBlockPos();
        dirty(minecraft, pos);
        BlockState state = blockEntity.getBlockState();
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            dirty(minecraft, pos.relative(ChestBlock.getConnectedDirection(state)));
        }
    }

    private static void dirty(Minecraft minecraft, BlockPos pos) {
        minecraft.levelExtractor.setSectionDirty(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private record Closing(BlockEntity blockEntity, long at) {}

    /**
     * Takes the vertices a model writes, four to a quad, into the snapshot: texture coordinates
     * moved into the chest's sprite of the blocks atlas, and the entity shader's lighting by the
     * surface's direction worked into each corner's color.
     */
    private static final class Capture implements VertexConsumer {

        private @Nullable Snapshot snapshot;
        private @Nullable TextureAtlasSprite sprite;
        private int light;
        private Vector3fc light1 = LIGHT_1;
        private final float[] positions = new float[12];
        private final float[] uvs = new float[8];
        private final int[] colors = new int[4];
        private int corner;

        void begin(Snapshot target, TextureAtlasSprite atlasSprite, int lightCoords, Vector3fc secondLight) {
            snapshot = target;
            sprite = atlasSprite;
            light = lightCoords;
            light1 = secondLight;
            corner = 0;
        }

        @Override
        public void addVertex(
                float x,
                float y,
                float z,
                int color,
                float u,
                float v,
                int overlayCoords,
                int lightCoords,
                float nx,
                float ny,
                float nz) {
            Snapshot target = snapshot;
            TextureAtlasSprite atlasSprite = sprite;
            if (target == null || atlasSprite == null) {
                return;
            }
            positions[corner * 3] = x;
            positions[corner * 3 + 1] = y;
            positions[corner * 3 + 2] = z;
            uvs[corner * 2] = atlasSprite.getU(u);
            uvs[corner * 2 + 1] = atlasSprite.getV(v);
            float lit = Math.min(
                    1.0F,
                    (Math.max(0.0F, LIGHT_0.x() * nx + LIGHT_0.y() * ny + LIGHT_0.z() * nz)
                                            + Math.max(0.0F, light1.x() * nx + light1.y() * ny + light1.z() * nz))
                                    * LIGHT_POWER
                            + AMBIENT);
            colors[corner] = scale(color, lit);
            if (++corner == 4) {
                corner = 0;
                target.addBakedQuad(positions, uvs, colors, light);
            }
        }

        /** An ARGB color with its red, green and blue scaled by {@code factor}, rounded. */
        private static int scale(int color, float factor) {
            int r = Math.round((color >> 16 & 0xFF) * factor);
            int g = Math.round((color >> 8 & 0xFF) * factor);
            int b = Math.round((color & 0xFF) * factor);
            return (color & 0xFF000000) | r << 16 | g << 8 | b;
        }

        // Models write whole vertices through the method above; nothing else calls these.

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            throw new UnsupportedOperationException("Chest models write whole vertices");
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
