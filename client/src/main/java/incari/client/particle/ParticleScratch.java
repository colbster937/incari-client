package incari.client.particle;

import org.joml.Quaternionf;

/**
 * The rotation the game's particle path allocated for every particle every frame, to turn it to
 * face the camera (ADR 0024), reused instead. The quads' own per-vertex objects are gone
 * with {@link ParticleQuads}.
 *
 * <p>Render thread only: particles are extracted and their quads built there, one at a time, and
 * each object is used up before the next call asks for it again.
 */
public final class ParticleScratch {

    private static final Quaternionf FACING = new Quaternionf();

    private ParticleScratch() {}

    /** Hook: the identity rotation, for a particle to turn to face the camera. */
    public static Quaternionf facing() {
        return FACING.identity();
    }
}
