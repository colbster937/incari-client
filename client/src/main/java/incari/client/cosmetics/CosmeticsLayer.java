package incari.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import incari.online.Cosmetic;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Draws a player's hat and back cosmetic (ADR 0061), from what their render state carries ({@link
 * Worn}). The cape is the game's own cape layer's, with the cosmetic's texture as the skin's cape
 * ({@link Cosmetics#dress}), so it moves as capes do.
 *
 * <p>A hat hides under a helmet (the halo floats above one); a back cosmetic hides under an elytra,
 * stands off a chestplate and hangs behind a cape. Nothing is drawn for an invisible player, or past {@link
 * #MAX_DISTANCE} blocks, where a hat is a pixel or two. Render thread; per player per frame, so
 * nothing here allocates.
 */
final class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    /** Past this, worn models aren't drawn. */
    static final double MAX_DISTANCE = 48;

    /** The cape's top edge from the body's pivot, in blocks: the body's back. */
    private static final float CAPE_TOP = 2.0F / 16.0F;

    /** How far a back cosmetic moves back to clear a cape: its pixel, and a gap. */
    private static final float BEHIND_CAPE = 1.0F / 16.0F;

    CosmeticsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            AvatarRenderState state,
            float yRot,
            float xRot) {
        Worn worn = Cosmetics.worn(state);
        if (worn.isEmpty() || state.isInvisible || state.distanceToCameraSq > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        Cosmetic hat = worn.hat;
        if (hat != null && (state.headEquipment.isEmpty() || hat.id().equals("hat.halo"))) {
            draw(poseStack, submitNodeCollector, lightCoords, state, CosmeticModels.of(hat), true);
        }
        Cosmetic back = worn.back;
        if (back != null && !state.chestEquipment.has(DataComponents.GLIDER)) {
            draw(poseStack, submitNodeCollector, lightCoords, state, CosmeticModels.of(back), false);
        }
    }

    private void draw(
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            AvatarRenderState state,
            CosmeticModels.@Nullable Made made,
            boolean onHead) {
        if (made == null) {
            return;
        }
        PlayerModel parent = getParentModel();
        poseStack.pushPose();
        parent.root().translateAndRotate(poseStack);
        if (onHead) {
            parent.head.translateAndRotate(poseStack);
        } else {
            parent.body.translateAndRotate(poseStack);
            if (!state.chestEquipment.isEmpty()) {
                // A chestplate stands a pixel out from the body.
                poseStack.translate(0.0F, 0.0F, 0.0625F);
            }
            if (state.showCape && state.skin.cape() != null) {
                behindCape(poseStack, state);
            }
        }
        boolean glow = made.look() == CosmeticModels.Look.GLOW;
        submitNodeCollector.submitModel(
                made.model(),
                state,
                poseStack,
                made.model().renderType(made.texture()),
                glow ? 0xF000F0 : lightCoords,
                LivingEntityRenderer.getOverlayCoords(state, 0.0F),
                state.outlineColor);
        poseStack.popPose();
    }

    /**
     * Over a cape, a back cosmetic hangs behind it and swings out with it, as the cape layer turns
     * the cape ({@code PlayerCapeModel.setupAnim}): otherwise the cape, which hangs from the
     * shoulders a pixel thick and swings back as the player moves, cuts through it. (A backpack
     * hides the cape instead, {@link Cosmetics#dress}.)
     */
    private static void behindCape(PoseStack poseStack, AvatarRenderState state) {
        float swing = (6.0F + state.capeLean / 2.0F + state.capeFlap) * Mth.DEG_TO_RAD;
        // About the cape's top edge, at the body's back (z = 2 pixels).
        poseStack.translate(0.0F, 0.0F, CAPE_TOP);
        poseStack.rotate(Axis.XP, swing);
        poseStack.translate(0.0F, 0.0F, -CAPE_TOP + BEHIND_CAPE);
    }
}
