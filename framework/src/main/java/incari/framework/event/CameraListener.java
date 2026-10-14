package incari.framework.event;

/** Receives {@link Events#CAMERA}. */
@FunctionalInterface
public interface CameraListener {

    /** The camera is being placed for this frame. */
    void onCamera(CameraAngles camera);
}
