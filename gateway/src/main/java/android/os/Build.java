package android.os;

/** Build 桩：只提供 spider 常读的版本信息 */
public final class Build {
    public static final String MANUFACTURER = "catvod";
    public static final String BRAND = "catvod";
    public static final String MODEL = "gateway";
    public static final String DEVICE = "gateway";
    public static final String PRODUCT = "gateway";

    private Build() {
    }

    public static final class VERSION {
        public static final int SDK_INT = 34;
        public static final String RELEASE = "14";

        private VERSION() {
        }
    }
}
