package incari.client.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import incari.online.Cosmetic;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The hats and back cosmetics as models (ADR 0061): each a few boxes, described here, baked once,
 * with a texture painted from the same description, so the boxes and their pictures never disagree.
 * Coordinates are the player model's: a hat's from the head's pivot (the head's box is -4..4 across,
 * -8..0 up), a back cosmetic's from the body's (the body's back is at z = 2).
 *
 * <p>Render thread. A model and its texture are made the first time something wears it.
 */
final class CosmeticModels {

    /** Where a cosmetic sits on the player. */
    enum Anchor {
        HEAD,
        BODY
    }

    /** How a cosmetic is drawn. */
    enum Look {
        /** Opaque, with see-through pixels cut out. */
        CUTOUT,
        /** Partly see-through. */
        TRANSLUCENT,
        /** Glowing: lit by itself, at full brightness. */
        GLOW
    }

    /** What a box is painted with. */
    enum Material {
        GOLD,
        GOLD_GEM,
        VELVET,
        HALO,
        FELT,
        FELT_BAND,
        KNIT,
        POM,
        BONE,
        EAR,
        PACK,
        POCKET,
        WING
    }

    /** A box: where, how big, how much bigger than that, and what it's painted with. */
    private record Box(float x, float y, float z, int w, int h, int d, float grow, Material material, boolean mirror) {}

    /** A part: where it turns about, how it's turned, and its boxes. */
    private record Part(String name, PartPose pose, List<Box> boxes) {}

    /** A cosmetic's model as described. */
    private record Spec(Anchor anchor, Look look, List<Part> parts) {}

    private static final int TEXTURE = 64;

    /** Each cosmetic's model, made the first time it's drawn: null for one that isn't a model. */
    private static final Map<String, @Nullable Made> MADE = new HashMap<>();

    /** A model ready to draw, with its texture and where it sits. */
    record Made(CosmeticModel model, Identifier texture, Anchor anchor, Look look) {}

    private CosmeticModels() {}

    /** The model for {@code cosmetic}, made now if it's the first time; null if it isn't one. */
    static @Nullable Made of(Cosmetic cosmetic) {
        if (MADE.containsKey(cosmetic.id())) {
            return MADE.get(cosmetic.id());
        }
        Spec spec = spec(cosmetic.id());
        Made made = spec == null ? null : make(cosmetic.id(), spec);
        MADE.put(cosmetic.id(), made);
        return made;
    }

    // ---------------------------------------------------------------------------------------------
    // The descriptions
    // ---------------------------------------------------------------------------------------------

    private static @Nullable Spec spec(String id) {
        return switch (id) {
            case "hat.crown" -> {
                List<Box> boxes = new ArrayList<>();
                // A band round the top of the head, outside the skin's hat layer, its top velvet.
                boxes.add(box(-4, -10, -4, 8, 2, 8, 0.6F, Material.GOLD_GEM));
                for (float[] at : new float[][] {
                    {-4.6F, -4.6F},
                    {3.6F, -4.6F},
                    {-4.6F, 3.6F},
                    {3.6F, 3.6F},
                    {-0.5F, -4.6F},
                    {-0.5F, 3.6F},
                    {-4.6F, -0.5F},
                    {3.6F, -0.5F}
                }) {
                    boxes.add(box(at[0], -12.6F, at[1], 1, 2, 1, 0.05F, Material.GOLD));
                }
                yield new Spec(Anchor.HEAD, Look.CUTOUT, List.of(new Part("crown", PartPose.ZERO, boxes)));
            }
            case "hat.halo" ->
                new Spec(
                        Anchor.HEAD,
                        Look.GLOW,
                        List.of(new Part(
                                "ring",
                                PartPose.offset(0, -12.5F, 0),
                                List.of(
                                        box(-3, -0.5F, -3.5F, 6, 1, 1, 0, Material.HALO),
                                        box(-3, -0.5F, 2.5F, 6, 1, 1, 0, Material.HALO),
                                        box(-3.5F, -0.5F, -3, 1, 1, 6, 0, Material.HALO),
                                        box(2.5F, -0.5F, -3, 1, 1, 6, 0, Material.HALO)))));
            case "hat.top_hat" ->
                new Spec(
                        Anchor.HEAD,
                        Look.CUTOUT,
                        List.of(new Part(
                                "hat",
                                PartPose.ZERO,
                                List.of(
                                        box(-5, -9, -5, 10, 1, 10, 0.1F, Material.FELT),
                                        box(-4, -15, -4, 8, 6, 8, -0.5F, Material.FELT_BAND)))));
            case "hat.beanie" ->
                new Spec(
                        Anchor.HEAD,
                        Look.CUTOUT,
                        List.of(new Part(
                                "beanie",
                                PartPose.ZERO,
                                List.of(
                                        box(-4, -9, -4, 8, 3, 8, 0.7F, Material.KNIT),
                                        box(-1, -12, -1, 2, 2, 2, 0.3F, Material.POM)))));
            case "hat.horns" -> new Spec(Anchor.HEAD, Look.CUTOUT, List.of(horn("left", 1), horn("right", -1)));
            case "hat.cat_ears" -> new Spec(Anchor.HEAD, Look.CUTOUT, List.of(ear("left", 1), ear("right", -1)));
            case "back.angel_wings", "back.bat_wings" -> wings(Look.CUTOUT);
            case "back.frost_wings" -> wings(Look.TRANSLUCENT);
            case "back.backpack" ->
                new Spec(
                        Anchor.BODY,
                        Look.CUTOUT,
                        List.of(new Part(
                                "pack",
                                PartPose.ZERO,
                                List.of(
                                        box(-4, 1, 2, 8, 10, 4, 0, Material.PACK),
                                        box(-3, 5, 6, 6, 4, 1, 0, Material.POCKET)))));
            default -> null;
        };
    }

    private static Box box(float x, float y, float z, int w, int h, int d, float grow, Material material) {
        return new Box(x, y, z, w, h, d, grow, material, false);
    }

    private static Part horn(String name, int side) {
        return new Part(
                name,
                PartPose.offsetAndRotation(side * 2.5F, -8, -1, -0.2F, 0, side * 0.45F),
                List.of(
                        box(-1, -3, -1, 2, 3, 2, 0, Material.BONE),
                        box(-0.5F + side * 0.4F, -5.5F, -0.5F, 1, 3, 1, 0, Material.BONE)));
    }

    private static Part ear(String name, int side) {
        return new Part(
                name,
                PartPose.offsetAndRotation(side * 2.6F, -8.2F, -0.5F, 0, 0, side * 0.28F),
                List.of(
                        box(-1.5F, -3, -0.5F, 3, 3, 1, 0, Material.EAR),
                        box(-0.5F, -4, -0.5F, 1, 1, 1, 0, Material.EAR)));
    }

    /** How far back the wings are swept at rest, in radians. */
    private static final float WING_SWEEP = 0.28F;

    /** Two wings, panels with nothing but a front and a back, from the shoulder blades. */
    private static Spec wings(Look look) {
        // From the shoulder blades, out past the shoulders and up past them, swept back a little:
        // turning the left wing (+x) by a negative yRot takes its tip back (+z), away from the arms.
        Part left = new Part(
                "left_wing",
                PartPose.offsetAndRotation(1, 3, 2.4F, 0, -WING_SWEEP, -0.12F),
                List.of(new Box(0, -9, 0, 16, 16, 0, 0, Material.WING, false)));
        Part right = new Part(
                "right_wing",
                PartPose.offsetAndRotation(-1, 3, 2.4F, 0, WING_SWEEP, 0.12F),
                List.of(new Box(-16, -9, 0, 16, 16, 0, 0, Material.WING, true)));
        return new Spec(Anchor.BODY, look, List.of(left, right));
    }

    // ---------------------------------------------------------------------------------------------
    // Baking and painting
    // ---------------------------------------------------------------------------------------------

    private static Made make(String id, Spec spec) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        NativeImage image = new NativeImage(TEXTURE, TEXTURE, true);
        int shelfX = 0;
        int shelfY = 0;
        int shelfHeight = 0;
        for (Part part : spec.parts()) {
            CubeListBuilder cubes = CubeListBuilder.create();
            for (Box box : part.boxes()) {
                int width = 2 * box.d() + 2 * box.w();
                int height = box.d() + box.h();
                if (shelfX + width > TEXTURE) {
                    shelfX = 0;
                    shelfY += shelfHeight;
                    shelfHeight = 0;
                }
                int u = shelfX;
                int v = shelfY;
                shelfX += width;
                shelfHeight = Math.max(shelfHeight, height);
                cubes.texOffs(u, v).mirror(box.mirror());
                if (box.d() == 0) {
                    Set<Direction> sides = EnumSet.of(Direction.NORTH, Direction.SOUTH);
                    cubes.addBox(box.x(), box.y(), box.z(), box.w(), box.h(), box.d(), sides);
                } else {
                    cubes.addBox(box.x(), box.y(), box.z(), box.w(), box.h(), box.d(), new CubeDeformation(box.grow()));
                }
                paint(image, id, box, u, v);
            }
            root.addOrReplaceChild(part.name(), cubes, part.pose());
        }
        ModelPart baked = LayerDefinition.create(mesh, TEXTURE, TEXTURE).bakeRoot();
        Identifier texture = Identifier.fromNamespaceAndPath("incari", "cosmetics/" + id.replace('.', '/'));
        Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(texture::toString, image));
        CosmeticModel model = new CosmeticModel(baked, renderType(spec.look()), id);
        return new Made(model, texture, spec.anchor(), spec.look());
    }

    private static java.util.function.Function<Identifier, RenderType> renderType(Look look) {
        // Culled: a wing is a panel whose front and back lie in one plane, and drawn both ways they
        // fight for the same depth (and, translucent, swap as their sort changes with the camera).
        return switch (look) {
            case CUTOUT -> RenderTypes::entityCutoutCull;
            case TRANSLUCENT -> RenderTypes::entityTranslucentCull;
            case GLOW -> RenderTypes::eyes;
        };
    }

    /** Paints a box's six faces where the game's box mapping reads them. */
    private static void paint(NativeImage image, String id, Box box, int u, int v) {
        int w = box.w();
        int h = box.h();
        int d = box.d();
        // The game's layout of a box's faces, from (u, v): down and up along the top, then west,
        // north (the front), east and south (the back) side by side.
        face(image, id, box, 0, u + d, v, w, d);
        face(image, id, box, 1, u + d + w, v, w, d);
        face(image, id, box, 2, u, v + d, d, h);
        face(image, id, box, 3, u + d, v + d, w, h);
        face(image, id, box, 4, u + d + w, v + d, d, h);
        face(image, id, box, 5, u + 2 * d + w, v + d, w, h);
    }

    private static void face(NativeImage image, String id, Box box, int face, int x0, int y0, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = x0 + x;
                int py = y0 + y;
                if (px < TEXTURE && py < TEXTURE) {
                    image.setPixel(px, py, color(id, box.material(), face, x, y, width, height));
                }
            }
        }
    }

    /** A face's pixel: face 0 and 1 the bottom and top, 3 the front, 5 the back. */
    private static int color(String id, Material material, int face, int x, int y, int width, int height) {
        boolean flat = face <= 1;
        float row = height <= 1 ? 0 : y / (float) (height - 1);
        float grain = CosmeticArt.hash(x, y, face + material.name().length() * 7);
        return switch (material) {
            case GOLD, GOLD_GEM -> {
                if (material == Material.GOLD_GEM && flat) {
                    yield 0xFF000000 | CosmeticArt.mix(0x8E1B2E, 0x6A1222, grain);
                }
                if (material == Material.GOLD_GEM && face == 3 && Math.abs(x - (width - 1) / 2.0F) < 1 && y == 0) {
                    yield 0xFFE0344B;
                }
                int gold = CosmeticArt.mix(0xFFE58A, 0xD69A1E, flat ? 0.1F : row);
                yield 0xFF000000 | (grain < 0.08F ? CosmeticArt.mix(gold, 0xFFFFFF, 0.6F) : gold);
            }
            case VELVET -> 0xFF000000 | CosmeticArt.mix(0x8E1B2E, 0x6A1222, grain);
            case HALO -> 0xFF000000 | CosmeticArt.mix(0xFFF6D2, 0xFFDB7A, grain * 0.6F);
            case FELT -> 0xFF000000 | CosmeticArt.mix(0x2A2A33, 0x1A1A20, grain);
            case FELT_BAND ->
                !flat && y >= height - 2 ? 0xFFC0263A : 0xFF000000 | CosmeticArt.mix(0x2A2A33, 0x1A1A20, grain);
            case KNIT -> {
                if (!flat && y == height - 1) {
                    yield 0xFF4F8FC7;
                }
                int stripe = flat ? (x + y) / 2 : y;
                yield (stripe & 1) == 0 ? 0xFF7FC3F2 : 0xFFEAF6FF;
            }
            case POM -> 0xFF000000 | CosmeticArt.mix(0xFFFFFF, 0xDDE8F2, grain);
            case BONE -> 0xFF000000 | CosmeticArt.mix(0xF2E6CC, 0x9C8A6A, flat ? 0.3F : 1 - row);
            case EAR -> face == 3 && x > 0 && x < width - 1 && y > 0 ? 0xFFF2A6BC : 0xFF4A4A58;
            case PACK -> {
                if (face == 1) {
                    yield 0xFF7FC3F2;
                }
                boolean seam = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                yield seam ? 0xFF3B5B8A : 0xFF000000 | CosmeticArt.mix(0x22385A, 0x1B2D49, grain);
            }
            case POCKET -> (x == 0 || y == 0 || x == width - 1 || y == height - 1) ? 0xFFA9DCFF : 0xFF2C4A72;
            case WING -> {
                // The back (south) face has the root on its left; the front shows it mirrored, so
                // the two sides of the panel line up.
                float sx = (x + 0.5F) / width;
                float sy = (y + 0.5F) / height;
                yield CosmeticArt.wing(id, face == 5 ? sx : 1 - sx, sy);
            }
        };
    }

    /** A cosmetic's model: its parts posed from the player's state as it's drawn (halo, wings). */
    static final class CosmeticModel extends Model<AvatarRenderState> {

        private final @Nullable ModelPart ring;
        private final @Nullable ModelPart leftWing;
        private final @Nullable ModelPart rightWing;

        CosmeticModel(ModelPart root, java.util.function.Function<Identifier, RenderType> renderType, String id) {
            super(root, renderType);
            this.ring = id.equals("hat.halo") ? root.getChild("ring") : null;
            boolean wings = id.endsWith("_wings");
            this.leftWing = wings ? root.getChild("left_wing") : null;
            this.rightWing = wings ? root.getChild("right_wing") : null;
        }

        @Override
        public void setupAnim(AvatarRenderState state) {
            super.setupAnim(state);
            float age = state.ageInTicks;
            ModelPart halo = ring;
            if (halo != null) {
                halo.yRot = age * 0.04F;
                halo.y += (float) Math.sin(age * 0.08F) * 0.4F;
            }
            ModelPart left = leftWing;
            ModelPart right = rightWing;
            if (left != null && right != null) {
                // Swept further back by a slow beat at rest, folded back while sneaking, spread while
                // gliding; never swept forward far enough to reach the arms.
                float beat = (float) Math.sin(age * 0.12F) * 0.08F + 0.08F;
                float fold = state.isCrouching ? 0.25F : 0;
                float spread = state.isFallFlying ? 0.2F : 0;
                float sweep = Math.max(-WING_SWEEP + 0.1F, beat + fold - spread);
                left.yRot -= sweep;
                right.yRot += sweep;
            }
        }
    }
}
