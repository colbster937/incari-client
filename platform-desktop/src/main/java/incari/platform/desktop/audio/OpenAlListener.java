package incari.platform.desktop.audio;

import incari.platform.api.audio.AudioListener;
import org.lwjgl.openal.AL10;

/** OpenAL's listener, which belongs to the current context: one per device here. */
final class OpenAlListener implements AudioListener {

    /** Reused: the listener moves every frame, and this is the sound thread's hot path. */
    private final float[] orientation = new float[6];

    @Override
    public void setPosition(double x, double y, double z) {
        AL10.alListener3f(AL10.AL_POSITION, (float) x, (float) y, (float) z);
    }

    @Override
    public void setOrientation(double forwardX, double forwardY, double forwardZ, double upX, double upY, double upZ) {
        orientation[0] = (float) forwardX;
        orientation[1] = (float) forwardY;
        orientation[2] = (float) forwardZ;
        orientation[3] = (float) upX;
        orientation[4] = (float) upY;
        orientation[5] = (float) upZ;
        AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
    }
}
