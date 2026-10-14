package incari.platform.web.audio;

/**
 * How loud a source is in each ear, by the model OpenAL Soft uses for stereo output, so the
 * browser sounds like the desktop. Hot path, no allocation: the sound thread runs it for every
 * playing source every frame.
 *
 * <p>The model, measured from OpenAL Soft's own output (its wave-file backend; ADR 0013 has the
 * numbers and {@code SpatializerTest} holds them):
 *
 * <ul>
 *   <li>A source's position is taken into the listener's frame (x right, y up, -z forward) unless
 *       it's already relative to the listener. A source at the listener counts as straight ahead.
 *   <li>Distance attenuation is per source: none, OpenAL's default inverse-distance model, or the
 *       linear model the game uses (full volume at the source, silent at the maximum distance).
 *   <li>Mono sources are panned by first-order ambisonics decoded to two speakers: each ear gets
 *       {@code 0.5 ± 0.5·right + 0.0957·front}. Directions in front are first spread by 1.5, as for
 *       speakers at ±30°, so a source 60° to the side is already fully on that side; one straight
 *       ahead is 0.596 in each ear, one straight behind 0.404, one straight above 0.5.
 *   <li>Stereo sources aren't positioned or attenuated at all: each channel plays at the source's
 *       gain. (That's why the game's positional sounds are mono.)
 * </ul>
 */
public final class Spatializer {

    /** The omnidirectional term of OpenAL Soft's stereo decoder. */
    static final double OMNI = 0.5;

    /** The left-right term. */
    static final double SIDE = 0.5;

    /**
     * The front-back term: 0.0552305643 in the decoder's SN3D-scaled matrix, times √3 for the
     * N3D-normalized coefficients it's applied to.
     */
    static final double FRONT = 0.0552305643 * 1.7320508075688772;

    /** How much directions in front are spread: speakers at ±30° cover up to ±90°. */
    private static final double FRONT_SPREAD = 1.5;

    private static final double HALF_PI = Math.PI / 2;

    /** Closer than this, a source is at the listener and sounds straight ahead. */
    private static final double MIN_DISTANCE = 1e-6;

    // The listener: its position, and its right, up and forward unit vectors.
    private double listenerX;
    private double listenerY;
    private double listenerZ;
    private double rightX = 1;
    private double rightY;
    private double rightZ;
    private double upX;
    private double upY = 1;
    private double upZ;
    private double forwardX;
    private double forwardY;
    private double forwardZ = -1;

    // The last computation's results.
    private double localX;
    private double localY;
    private double localZ;
    private double level;
    private double left;
    private double right;

    public void setListenerPosition(double x, double y, double z) {
        listenerX = x;
        listenerY = y;
        listenerZ = z;
    }

    /**
     * Sets which way the listener faces. As in OpenAL, the vectors needn't be normalized or
     * perpendicular: right is forward × up, and up is made perpendicular to both. A degenerate
     * pair (zero, or parallel) is ignored.
     */
    public void setListenerOrientation(double fx, double fy, double fz, double ux, double uy, double uz) {
        double forwardLength = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double sx = fy * uz - fz * uy;
        double sy = fz * ux - fx * uz;
        double sz = fx * uy - fy * ux;
        double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (forwardLength < MIN_DISTANCE || sideLength < MIN_DISTANCE) {
            return;
        }
        forwardX = fx / forwardLength;
        forwardY = fy / forwardLength;
        forwardZ = fz / forwardLength;
        rightX = sx / sideLength;
        rightY = sy / sideLength;
        rightZ = sz / sideLength;
        upX = rightY * forwardZ - rightZ * forwardY;
        upY = rightZ * forwardX - rightX * forwardZ;
        upZ = rightX * forwardY - rightY * forwardX;
    }

    /**
     * Computes a source's position in the listener's frame and the gain of each ear, read back
     * with {@link #localX()}... and {@link #left()}/{@link #right()}.
     *
     * @param relative whether the position is already relative to the listener, in its frame
     * @param gain the source's own gain
     * @param stereo whether the source has two channels (then it isn't positioned or attenuated)
     */
    public void compute(
            double x, double y, double z, boolean relative, Attenuation attenuation, double gain, boolean stereo) {
        if (relative) {
            localX = x;
            localY = y;
            localZ = z;
        } else {
            double dx = x - listenerX;
            double dy = y - listenerY;
            double dz = z - listenerZ;
            localX = dx * rightX + dy * rightY + dz * rightZ;
            localY = dx * upX + dy * upY + dz * upZ;
            localZ = -(dx * forwardX + dy * forwardY + dz * forwardZ);
        }
        if (stereo) {
            level = gain;
            left = gain;
            right = gain;
            return;
        }
        double distance = Math.sqrt(localX * localX + localY * localY + localZ * localZ);
        level = gain * attenuation.gain(distance);
        pan(distance);
        left *= level;
        right *= level;
    }

    /** The panning gains for the direction of {@link #localX}..., at {@code distance}. */
    private void pan(double distance) {
        double sideways;
        double front;
        if (distance < MIN_DISTANCE) {
            sideways = 0;
            front = 1;
        } else {
            double horizontal = Math.sqrt(localX * localX + localZ * localZ);
            double azimuth = Math.atan2(localX, -localZ);
            double spread = Math.abs(azimuth) < HALF_PI
                    ? Math.copySign(Math.min(Math.abs(azimuth) * FRONT_SPREAD, HALF_PI), azimuth)
                    : azimuth;
            // cos(elevation): the horizontal part of the unit direction; elevation itself is unchanged.
            double level = horizontal / distance;
            sideways = Math.sin(spread) * level;
            front = Math.cos(spread) * level;
        }
        left = OMNI - SIDE * sideways + FRONT * front;
        right = OMNI + SIDE * sideways + FRONT * front;
    }

    /**
     * The source's gain after distance attenuation, before panning, from the last {@link
     * #compute}: what an HRTF panner, which pans by itself, is given.
     */
    public double level() {
        return level;
    }

    /** The left ear's gain from the last {@link #compute}. */
    public double left() {
        return left;
    }

    /** The right ear's gain from the last {@link #compute}. */
    public double right() {
        return right;
    }

    /** The source's position in the listener's frame (right) from the last {@link #compute}. */
    public double localX() {
        return localX;
    }

    /** ...up. */
    public double localY() {
        return localY;
    }

    /** ...and backwards: forward is -z, as in OpenAL and Web Audio. */
    public double localZ() {
        return localZ;
    }

    /** How a source fades with distance. Mutable, one per source: set without allocating. */
    public static final class Attenuation {

        private enum Model {
            NONE,
            INVERSE_CLAMPED,
            LINEAR
        }

        private Model model = Model.INVERSE_CLAMPED;
        private double maxDistance;

        /** OpenAL's default: inverse distance from 1 block, clamped, with a rolloff of 1. */
        public void inverseClamped() {
            model = Model.INVERSE_CLAMPED;
        }

        /** Full volume at any distance. */
        public void none() {
            model = Model.NONE;
        }

        /**
         * OpenAL's unclamped linear model with a reference distance of 0 and a rolloff of 1: 1 at
         * the source, 0 at {@code maxDistance} and beyond. A maximum of 0 means no attenuation, as
         * OpenAL skips the model when the maximum equals the reference distance.
         */
        public void linear(double maxDistance) {
            model = Model.LINEAR;
            this.maxDistance = maxDistance;
        }

        /** The gain at {@code distance} blocks, 0 to 1. */
        public double gain(double distance) {
            return switch (model) {
                case NONE -> 1;
                case INVERSE_CLAMPED -> 1 / Math.max(distance, 1);
                case LINEAR -> maxDistance == 0 ? 1 : Math.max(0, 1 - Math.min(distance, maxDistance) / maxDistance);
            };
        }
    }
}
