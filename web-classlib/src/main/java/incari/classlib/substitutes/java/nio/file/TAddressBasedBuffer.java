package incari.classlib.substitutes.java.nio.file;

import org.teavm.interop.Address;

/**
 * TeaVM's own {@code java.nio.file.AddressBasedBuffer} (not a JDK type): a buffer whose contents
 * start at an address in the module's linear memory. TeaVM's direct buffers and their typed views
 * implement it. Declared here, the same as TeaVM's, so {@link incari.classlib.memory.Heap} can name
 * it: javac knows only the JDK's {@code java.nio}, and the build maps this name onto TeaVM's.
 */
public interface TAddressBasedBuffer {

    Address getDataAddress();
}
