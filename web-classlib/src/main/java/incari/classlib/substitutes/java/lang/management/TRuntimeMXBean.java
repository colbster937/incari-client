package incari.classlib.substitutes.java.lang.management;

import java.util.List;
import java.util.Map;

/** {@code java.lang.management.RuntimeMXBean}, which TeaVM lacks (see {@link TManagementFactory}). */
public interface TRuntimeMXBean {
    long getPid();

    String getName();

    String getVmName();

    String getVmVendor();

    String getVmVersion();

    String getSpecName();

    String getSpecVendor();

    String getSpecVersion();

    List<String> getInputArguments();

    long getUptime();

    long getStartTime();

    Map<String, String> getSystemProperties();
}
