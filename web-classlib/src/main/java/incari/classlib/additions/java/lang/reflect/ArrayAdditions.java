package incari.classlib.additions.java.lang.reflect;

import java.lang.reflect.Array;

/** {@code java.lang.reflect.Array} methods TeaVM lacks. */
public final class ArrayAdditions {

    private ArrayAdditions() {}

    /** A multi-dimensional array, built one dimension at a time from the one-dimensional form. */
    public static Object newInstance(Class<?> componentType, int... dimensions) {
        if (dimensions.length == 0) {
            throw new IllegalArgumentException("Empty dimensions array");
        }
        return create(componentType, dimensions, 0);
    }

    private static Object create(Class<?> componentType, int[] dimensions, int index) {
        if (index == dimensions.length - 1) {
            return Array.newInstance(componentType, dimensions[index]);
        }
        Class<?> elementType = componentType;
        for (int i = index + 1; i < dimensions.length; i++) {
            elementType = Array.newInstance(elementType, 0).getClass();
        }
        Object array = Array.newInstance(elementType, dimensions[index]);
        for (int i = 0; i < dimensions[index]; i++) {
            Array.set(array, i, create(componentType, dimensions, index + 1));
        }
        return array;
    }
}
