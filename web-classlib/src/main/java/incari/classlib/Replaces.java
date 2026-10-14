package incari.classlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an addition that replaces the method TeaVM's class of the same name already has, where
 * TeaVM's behaves differently from what the browser build needs (resources from the class path
 * read from the page's file system instead of from data compiled into the module). The web build
 * refers to this annotation by name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Replaces {}
