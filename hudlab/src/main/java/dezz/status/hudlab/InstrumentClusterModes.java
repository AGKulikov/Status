package dezz.status.hudlab;

/* loaded from: classes4.dex */
final class InstrumentClusterModes {
    static final int NAVI_AR = 4;
    static final int NAVI_FULL = 3;
    static final int NAVI_OFF = 1;
    static final int NAVI_SIMPLIFY = 2;

    private InstrumentClusterModes() {
    }

    static int requireDriverHmiInterface(int value) {
        return requireRange("Driver HMI UI", value, 0, 9);
    }

    static int requireDriverHmiBackground(int value) {
        return requireRange("Driver HMI background", value, 0, 5);
    }

    static int requireDriverDisplayTemplate(int value) {
        return requireRange("Driver display template", value, 0, 2);
    }

    static int requireInformationLayer(int value) {
        return requireRange("Multimedia information layer", value, 0, 3);
    }

    static int requireHmiTheme(int value) {
        return requireRange("HMI theme", value, 0, 3);
    }

    static int requireNaviMode(int value) {
        return requireRange("Navi mode", value, 1, 4);
    }

    static String naviModeName(int value) {
        switch (requireNaviMode(value)) {
            case 1:
                return "OFF";
            case 2:
                return "SIMPLIFY";
            case 3:
                return "FULL";
            case 4:
                return "AR";
            default:
                throw new AssertionError();
        }
    }

    private static int requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + "=" + value + " вне диапазона " + min + "…" + max);
        }
        return value;
    }
}
