package incari.client.cosmetics;

import incari.online.Cosmetic;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * Emotes as the player model plays them (ADR 0061): each a pose for the arms and head, eased in and
 * out, some moving as they go (a wave, a clap). Laid over the game's own pose at the end of {@code
 * PlayerModel.setupAnim}, so the legs still walk if the player does, and sleeves, hats and capes
 * follow the parts they hang from.
 *
 * <p>Render thread; the pose is set while the model is drawn, so nothing here allocates.
 */
final class Emotes {

    /** Ticks an emote takes to ease in, and out. */
    private static final float EASE = 4;

    private Emotes() {}

    /** How long {@code emote} plays, in ticks. */
    static int duration(Cosmetic emote) {
        return switch (emote.id()) {
            case "emote.wave", "emote.clap", "emote.facepalm" -> 50;
            case "emote.cheer" -> 45;
            case "emote.salute", "emote.bow" -> 40;
            case "emote.shrug" -> 35;
            case "emote.dab" -> 30;
            default -> 40;
        };
    }

    /** {@code PlayerModel.setupAnim}'s hook: the emote's pose over the game's, if one is playing. */
    static void pose(PlayerModel model, AvatarRenderState state) {
        Worn worn = Cosmetics.worn(state);
        Cosmetic emote = worn.emote;
        if (emote == null) {
            return;
        }
        float elapsed = state.ageInTicks - worn.emoteStart;
        int duration = duration(emote);
        if (elapsed < 0 || elapsed >= duration) {
            return;
        }
        float weight = Math.min(1, Math.min(elapsed / EASE, (duration - elapsed) / EASE));
        float e = elapsed;
        ModelPart head = model.head;
        ModelPart right = model.rightArm;
        ModelPart left = model.leftArm;
        switch (emote.id()) {
            case "emote.wave" -> arm(right, weight, 0, 0, 2.6F + 0.35F * Mth.sin(e * 0.6F));
            case "emote.clap" -> {
                float swing = 0.25F + 0.3F * (0.5F + 0.5F * Mth.sin(e * 0.9F));
                arm(right, weight, -1.35F, -swing, 0);
                arm(left, weight, -1.35F, swing, 0);
            }
            case "emote.cheer" -> {
                float bounce = 0.15F * Mth.sin(e * 0.5F);
                arm(right, weight, -2.9F + bounce, 0, 0.35F);
                arm(left, weight, -2.9F - bounce, 0, -0.35F);
                turn(head, weight, -0.25F, 0, 0);
            }
            case "emote.salute" -> {
                arm(right, weight, -2.3F, -0.55F, 0.1F);
                turn(head, weight, -0.05F, 0, 0);
            }
            case "emote.facepalm" -> {
                arm(right, weight, -2.1F, -0.75F, 0);
                turn(head, weight, 0.35F, 0, 0);
            }
            case "emote.shrug" -> {
                float lift = 0.1F * Mth.sin(e * 0.4F);
                arm(right, weight, -0.45F, 0, 0.75F + lift);
                arm(left, weight, -0.45F, 0, -0.75F - lift);
                turn(head, weight, -0.1F, 0, 0.12F * Mth.sin(e * 0.3F));
            }
            case "emote.dab" -> {
                arm(right, weight, -1.6F, -1.0F, 0);
                arm(left, weight, -0.4F, 0, -2.3F);
                turn(head, weight, 0.55F, 0.6F, 0);
            }
            case "emote.bow" -> {
                arm(right, weight, -0.9F, -0.6F, 0);
                arm(left, weight, 0.3F, 0, 0);
                turn(head, weight, 0.75F, 0, 0);
            }
            default -> {}
        }
    }

    private static void arm(ModelPart arm, float weight, float xRot, float yRot, float zRot) {
        turn(arm, weight, xRot, yRot, zRot);
    }

    private static void turn(ModelPart part, float weight, float xRot, float yRot, float zRot) {
        part.xRot = Mth.lerp(weight, part.xRot, xRot);
        part.yRot = Mth.lerp(weight, part.yRot, yRot);
        part.zRot = Mth.lerp(weight, part.zRot, zRot);
    }
}
