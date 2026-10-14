package incari.classlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in an {@code incari.classlib.additions} class that the web build adds to its
 * target class as an instance method; the first parameter is the receiver. Unmarked additions are
 * added as static methods. The web build refers to this annotation by name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InstanceMethod {}
