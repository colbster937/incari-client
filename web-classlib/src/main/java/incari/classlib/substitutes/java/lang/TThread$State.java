package incari.classlib.substitutes.java.lang;

/**
 * {@code java.lang.Thread.State}, which TeaVM's {@code Thread} lacks. A top-level class named for
 * the nested one, so it takes the JDK name {@code java.lang.Thread$State}; {@code Thread.getState}
 * comes from {@code ThreadAdditions}.
 */
@SuppressWarnings("TypeName") // The name is the JDK's nested class, as the class library maps it.
public enum TThread$State {
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED
}
