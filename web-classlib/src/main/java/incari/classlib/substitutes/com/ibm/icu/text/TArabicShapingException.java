package incari.classlib.substitutes.com.ibm.icu.text;

import java.io.Serial;

/** ICU's {@code ArabicShapingException}; {@link TArabicShaping} never throws it. */
public class TArabicShapingException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public TArabicShapingException(String message) {
        super(message);
    }
}
