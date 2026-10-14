package incari.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Builds a particle's quad (ADR 0024) as the game does: the four corners of a unit square
 * turned by the particle's rotation, scaled and moved to the particle. Particles that face the camera
 * all share the camera's rotation, so the turned corners are kept from one particle to the next and
 * only worked out again when the rotation changes: a frame of a few thousand particles turns four
 * corners a handful of times instead of four for each particle, and allocates nothing (the game
 * made a rotation and four vectors for each). The corners come from the same arithmetic, so the
 * vertices are the game's to the bit.
 *
 * <p>Render thread only, where particle quads are built. Hot path.
 */
public final class ParticleQuads {

    /** The corners in the game's order: x, y, and which u and v they take (1 for the second). */
    private static final float[] CORNER_X = {1.0F, 1.0F, -1.0F, -1.0F};

    private static final float[] CORNER_Y = {-1.0F, 1.0F, 1.0F, -1.0F};

    private static final Quaternionf ROTATION = new Quaternionf();
    private static final Vector3f SCRATCH = new Vector3f();
    private static final float[] TURNED = new float[12];
    private static int lastX = Float.floatToRawIntBits(Float.NaN);
    private static int lastY;
    private static int lastZ;
    private static int lastW;

    private ParticleQuads() {}

    /** Hook: writes one particle's quad. */
    public static void render(
            VertexConsumer builder,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot,
            float wRot,
            float scale,
            float u0,
            float u1,
            float v0,
            float v1,
            int color,
            int lightCoords) {
        int bx = Float.floatToRawIntBits(xRot);
        int by = Float.floatToRawIntBits(yRot);
        int bz = Float.floatToRawIntBits(zRot);
        int bw = Float.floatToRawIntBits(wRot);
        if (bx != lastX || by != lastY || bz != lastZ || bw != lastW) {
            ROTATION.set(xRot, yRot, zRot, wRot);
            for (int corner = 0; corner < 4; corner++) {
                SCRATCH.set(CORNER_X[corner], CORNER_Y[corner], 0.0F).rotate(ROTATION);
                TURNED[corner * 3] = SCRATCH.x;
                TURNED[corner * 3 + 1] = SCRATCH.y;
                TURNED[corner * 3 + 2] = SCRATCH.z;
            }
            lastX = bx;
            lastY = by;
            lastZ = bz;
            lastW = bw;
        }
        vertex(builder, 0, scale, x, y, z, u1, v1, color, lightCoords);
        vertex(builder, 1, scale, x, y, z, u1, v0, color, lightCoords);
        vertex(builder, 2, scale, x, y, z, u0, v0, color, lightCoords);
        vertex(builder, 3, scale, x, y, z, u0, v1, color, lightCoords);
    }

    private static void vertex(
            VertexConsumer builder,
            int corner,
            float scale,
            float x,
            float y,
            float z,
            float u,
            float v,
            int color,
            int lightCoords) {
        // As the game's Vector3f.mul(scale).add(x, y, z) does it, component by component.
        float px = TURNED[corner * 3] * scale + x;
        float py = TURNED[corner * 3 + 1] * scale + y;
        float pz = TURNED[corner * 3 + 2] * scale + z;
        builder.addVertex(px, py, pz).setUv(u, v).setColor(color).setLight(lightCoords);
    }
}
