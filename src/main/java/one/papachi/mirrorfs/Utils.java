package one.papachi.mirrorfs;

public class Utils {

    enum OperatingSystemFamily {
        WINDOWS, LINUX, MAC, UNKNOWN;
    }

    public static OperatingSystemFamily getOperatingSystemFamily() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return OperatingSystemFamily.WINDOWS;
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return OperatingSystemFamily.LINUX;
        } else if (osName.contains("mac")) {
            return OperatingSystemFamily.MAC;
        }
        return OperatingSystemFamily.UNKNOWN;
    }

}
