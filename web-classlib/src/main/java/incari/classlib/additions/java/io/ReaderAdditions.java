package incari.classlib.additions.java.io;

import incari.classlib.InstanceMethod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/** {@code java.io.Reader} methods TeaVM lacks. */
public final class ReaderAdditions {

    private ReaderAdditions() {}

    @InstanceMethod
    public static long transferTo(Reader self, Writer out) throws IOException {
        char[] buffer = new char[8192];
        long total = 0;
        int read;
        while ((read = self.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    @InstanceMethod
    public static String readAllAsString(Reader self) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = self.read(buffer)) >= 0) {
            text.append(buffer, 0, read);
        }
        return text.toString();
    }
}
