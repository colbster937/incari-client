package incari.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Math;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Vertex arithmetic the game's hot paths share: the game's {@code addVertex(pose, x, y, z)}
 * and {@code addVertexWith2DPose} allocate a vector per vertex to transform into, which on the web
 * build's garbage-collected heap is most of what writing a glyph costs, in the world and in the
 * GUI. Here the same arithmetic, in the same order (JOML's transforms), without the vector. And
 * {@code putBakedQuad}, which made five vectors a quad of every item drawn in the world.
 */
public final class VertexMath {

    // Render thread only: the game's other threads (the desktop's section compiler) write quads too,
    // and take the game's own path.
    private static final Vector3f NORMAL = new Vector3f();
    private static final Vector3f POSITION = new Vector3f();

    private static volatile boolean quads = true;

    private VertexMath() {}

    /** Whether {@link #putBakedQuad} writes quads (the page's {@code ?quads=game} turns it off, to compare). */
    public static void setQuadsEnabled(boolean enabled) {
        quads = enabled;
    }

    /**
     * Hook: {@code builder.putBakedQuad(pose, quad, instance)} as the game writes it, with two vectors
     * reused instead of five made, on the render thread; false elsewhere, where the caller takes the
     * game's own path.
     */
    public static boolean putBakedQuad(
            VertexConsumer builder, PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
        // Its texture coordinates were baked in: no sprite getter notes it as drawn (ADR 0030).
        AnimatedSprites.used(quad);
        if (!quads || !RenderSystem.isOnRenderThread()) {
            return false;
        }
        Vector3f normal = pose.transformNormal(quad.direction().getUnitVec3f(), NORMAL);
        Matrix4f matrix = pose.pose();
        int lightEmission = quad.materialInfo().lightEmission();
        for (int vertex = 0; vertex < 4; vertex++) {
            long packedUv = quad.packedUV(vertex);
            int color = instance.getColor(vertex);
            int light = instance.getLightCoordsWithEmission(vertex, lightEmission);
            Vector3f position = matrix.transformPosition(quad.position(vertex), POSITION);
            builder.addVertex(
                    position.x(),
                    position.y(),
                    position.z(),
                    color,
                    UVPair.unpackU(packedUv),
                    UVPair.unpackV(packedUv),
                    instance.overlayCoords(),
                    light,
                    normal.x(),
                    normal.y(),
                    normal.z());
        }
        return true;
    }

    /** Hook: {@code builder.addVertex(pose, x, y, z)}, allocating nothing: JOML's generic position
     * transform, fused multiply-adds from the constant term out. */
    public static VertexConsumer addVertex(VertexConsumer builder, Matrix4fc pose, float x, float y, float z) {
        return builder.addVertex(
                Math.fma(pose.m00(), x, Math.fma(pose.m10(), y, Math.fma(pose.m20(), z, pose.m30()))),
                Math.fma(pose.m01(), x, Math.fma(pose.m11(), y, Math.fma(pose.m21(), z, pose.m31()))),
                Math.fma(pose.m02(), x, Math.fma(pose.m12(), y, Math.fma(pose.m22(), z, pose.m32()))));
    }

    /** Hook: {@code builder.addVertexWith2DPose(pose, x, y)}, allocating nothing. */
    public static VertexConsumer addVertexWith2DPose(VertexConsumer builder, Matrix3x2fc pose, float x, float y) {
        return builder.addVertex(
                pose.m00() * x + pose.m10() * y + pose.m20(), pose.m01() * x + pose.m11() * y + pose.m21(), 0.0F);
    }
}
