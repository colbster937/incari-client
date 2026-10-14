package incari.client.gui;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import org.jspecify.annotations.Nullable;

/**
 * A player's skin drawn in the GUI (the title screen's player, the skin customization screen) kept
 * between frames while it holds still. The game draws such a model into a texture of its own and
 * blits that, but drew it again every frame: a pass with depth, the whole model and its lighting
 * for the same picture. Now it's drawn again only when something it's drawn from changes: the
 * model, the skin's texture (a new skin is a new texture under the same name), the turn, the size
 * or the GUI scale.
 *
 * <p>Render thread.
 */
public final class SkinPictures {

    private SkinPictures() {}

    /** What one renderer drew last. */
    public static final class Last {

        private Model.@Nullable Simple model;
        private @Nullable GpuTextureView texture;
        private float rotationX;
        private float rotationY;
        private float pivotY;
        private float scale;
        private int width;
        private int height;
        private int guiScale;

        /**
         * {@code GuiSkinRenderer.textureIsReadyToBlit}: whether the texture already holds {@code
         * state}'s picture. If not, the renderer draws it now, and that's remembered.
         */
        @SuppressWarnings("ReferenceEquality") // the same model and texture objects, not equal ones
        public boolean unchanged(GuiSkinRenderState state) {
            GpuTextureView view = Minecraft.getInstance()
                    .getTextureManager()
                    .getTexture(state.texture())
                    .getTextureView();
            int scaleNow = Minecraft.getInstance().getWindow().getGuiScale();
            int widthNow = state.x1() - state.x0();
            int heightNow = state.y1() - state.y0();
            if (state.playerModel() == model
                    && view == texture
                    && state.rotationX() == rotationX
                    && state.rotationY() == rotationY
                    && state.pivotY() == pivotY
                    && state.scale() == scale
                    && widthNow == width
                    && heightNow == height
                    && scaleNow == guiScale) {
                return true;
            }
            model = state.playerModel();
            texture = view;
            rotationX = state.rotationX();
            rotationY = state.rotationY();
            pivotY = state.pivotY();
            scale = state.scale();
            width = widthNow;
            height = heightNow;
            guiScale = scaleNow;
            return false;
        }
    }
}
