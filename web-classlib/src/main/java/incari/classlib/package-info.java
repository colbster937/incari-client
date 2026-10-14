/**
 * The JDK as the browser build needs it: classes and methods TeaVM's class library lacks, written
 * for a single-threaded page where Java threads are coroutines.
 *
 * <ul>
 *   <li>{@code incari.classlib.substitutes.<package>.T<Name>} stands in for {@code <package>.<Name>};
 *   <li>{@code incari.classlib.additions.<package>.<Name>Additions} adds methods to a class TeaVM
 *       has ({@link incari.classlib.InstanceMethod});
 *   <li>{@link incari.classlib.Removed} is what code the build strips turns into.
 * </ul>
 *
 * <p>The code runs on a desktop JVM too, which is how its tests run. Nothing here may be copied
 * from the JDK: it's written fresh against the documented behaviour.
 */
@NullMarked
package incari.classlib;

import org.jspecify.annotations.NullMarked;
