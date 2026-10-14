import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Regression probe for tools/teavm-patches' TZipFile fix: TeaVM's ZipFile gave its raw
 * inflater exactly an entry's compressed bytes, and InflaterInputStream threw EOFException when
 * the inflater still asked for input at their end, as it can for a whole entry (638 textures of a
 * community resource pack). The JDK's ZipFile adds a dummy byte; the patch does too. Writes entries
 * of many sizes and kinds, reads each back to its end, and prints "probe passed" when all match.
 */
public final class ZipFileProbe {

    private ZipFileProbe() {}

    public static void main(String[] args) throws IOException {
        Random random = new Random(5);
        byte[][] contents = new byte[400][];
        File file = new File("/probe.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < contents.length; i++) {
                byte[] data = new byte[random.nextInt(4000)];
                // Some random (incompressible), some patterned, some mixed: PNG-like variety.
                for (int j = 0; j < data.length; j++) {
                    data[j] = (byte) (i % 3 == 0 ? random.nextInt() : i % 3 == 1 ? j / 7 : (j % 13) * random.nextInt(3));
                }
                contents[i] = data;
                zip.putNextEntry(new ZipEntry("entry" + i));
                zip.write(data);
                zip.closeEntry();
            }
        }
        int failures = 0;
        String first = "";
        try (ZipFile zip = new ZipFile(file)) {
            for (int i = 0; i < contents.length; i++) {
                try (InputStream in = zip.getInputStream(zip.getEntry("entry" + i))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[512];
                    for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                        out.write(buffer, 0, read);
                    }
                    if (!Arrays.equals(out.toByteArray(), contents[i])) {
                        failures++;
                        first = first.isEmpty() ? "entry" + i + " differs" : first;
                    }
                } catch (IOException e) {
                    failures++;
                    first = first.isEmpty() ? "entry" + i + ": " + e : first;
                }
            }
        }
        System.out.println(failures == 0 ? "probe passed" : "probe failed: " + failures + " entries, first " + first);
    }
}
