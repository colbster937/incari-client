package incari.client.render.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The one color an item drawn instanced gets ({@link ModelBatches#sharedTint},
 * {@link ModelBatches#itemColor}, ADR 0031): the color the game's {@code ItemFeatureRenderer} gives
 * each of its quads, for quads that share a tint index.
 */
class ItemTintTest {

    @SuppressWarnings("NullAway") // Only the tint index is read.
    private static BakedQuad quad(int tintIndex) {
        BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(null, null, null, null, null, tintIndex, null, 0);
        Vector3f corner = new Vector3f();
        long uv = UVPair.pack(0.0F, 0.0F);
        return new BakedQuad(corner, corner, corner, corner, uv, uv, uv, uv, Direction.UP, material);
    }

    @Test
    void generatedItemLayersShareTheirLayersIndex() {
        // The game's generated items give every quad of layer 0 the tint index 0.
        assertThat(ModelBatches.sharedTint(List.of(quad(0), quad(0), quad(0)))).isZero();
        assertThat(ModelBatches.sharedTint(List.of(quad(-1), quad(-1)))).isEqualTo(-1);
        assertThat(ModelBatches.sharedTint(List.of(quad(-2)))).isEqualTo(-1);
    }

    @Test
    void layersThatTintDifferentlyDontShare() {
        // A potion: its tinted liquid (0) and its untinted bottle (1).
        assertThat(ModelBatches.sharedTint(List.of(quad(0), quad(1)))).isEqualTo(Integer.MIN_VALUE);
        assertThat(ModelBatches.sharedTint(List.of(quad(-1), quad(0)))).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void colorIsTheGamesLayerColor() {
        int green = 0xFF48B518;
        // An item with no tint source has no layer colors: its tinted quads draw white, as the game's.
        assertThat(ModelBatches.itemColor(new int[0], 0)).isEqualTo(-1);
        assertThat(ModelBatches.itemColor(new int[] {green}, 0)).isEqualTo(green);
        assertThat(ModelBatches.itemColor(new int[] {green}, 1)).isEqualTo(-1);
        assertThat(ModelBatches.itemColor(new int[] {green}, -1)).isEqualTo(-1);
    }
}
