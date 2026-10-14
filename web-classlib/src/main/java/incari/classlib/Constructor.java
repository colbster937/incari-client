package incari.classlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in an {@code incari.classlib.additions} class that the web build adds to its
 * target class as a constructor with the same parameters. The constructor passes what the method
 * returns to the class's own one-argument constructor for that type, so the addition only works
 * out the argument ({@code String(byte[], int, int, int)} becomes {@code this(char[])}). The web
 * build refers to this annotation by name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Constructor {}
