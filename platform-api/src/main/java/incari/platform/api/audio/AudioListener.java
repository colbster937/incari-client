package incari.platform.api.audio;

/**
 * Where the player hears from. Positions are in the game's world coordinates (blocks); sources
 * are placed in the same space.
 */
public interface AudioListener {

    void setPosition(double x, double y, double z);

    /** Which way the listener faces, as a forward and an up vector (need not be normalized). */
    void setOrientation(double forwardX, double forwardY, double forwardZ, double upX, double upY, double upZ);
}
