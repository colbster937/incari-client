package incari.tools.shaders;

/** A shader that couldn't be compiled, translated or validated; the message says which and why. */
final class ShaderException extends Exception {

    private static final long serialVersionUID = 1L;

    ShaderException(String message) {
        super(message);
    }
}
