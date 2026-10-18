package dezz.status.widget.instrument;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

/** Read-only Android 9 `am stack list` snapshot; ambiguity never grants screen ownership. */
final class InstrumentDisplayOwner {
    private static final Pattern STACK = Pattern.compile("(?m)^Stack id=\\d+ [^\\r\\n]*\\bdisplayId=(\\d+)\\b[^\\r\\n]*$");
    // Android 9 uses ICU: unlike the host JVM, an unescaped closing brace is invalid.
    private static final Pattern TOP = Pattern.compile("\\bvisible=true\\s+topActivity=ComponentInfo\\{([^}]+)\\}");
    private static final Pattern BOUNDS = Pattern.compile("\\bbounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    /** Counts only: no raw task dump or unrelated application names in logs. */
    static String describe(String output, int display, String packageName) {
        Snapshot value = inspect(output, display, packageName);
        if (value == null) return "snapshot=invalid";
        return "target_stacks=" + value.stacks + ", visible_panel=" + value.own
                + ", visible_other=" + value.other + ", unparsed_visible=" + value.unparsed
                + ", overlapping_other=" + value.overlapping + ", outside_panel=" + value.outside
                + ", unknown_bounds=" + value.unknownBounds + ", scope=panel_stack_bounds";
    }
    static boolean owns(String output, int display, String packageName) {
        Snapshot value = inspect(output, display, packageName);
        return value != null && value.own == 1 && value.unparsed == 0
                && value.unknownBounds == 0 && value.overlapping == 0;
    }

    /** A foreign dashboard must retain DIM even after our own task has been removed. */
    static InstrumentPanelCloseCoordinator.Owner ownerAfterClose(String output, int display,
                                                                  String packageName) {
        if (output == null || !output.trim().startsWith("Stack id="))
            return InstrumentPanelCloseCoordinator.Owner.UNKNOWN;
        Snapshot value = inspect(output, display, packageName);
        if (value == null || value.stacks == 0 || value.tasks == 0 || value.unparsed != 0
                || value.unknownBounds != 0 || value.own > 1)
            return InstrumentPanelCloseCoordinator.Owner.UNKNOWN;
        return value.foreignDashboard == 0 ? InstrumentPanelCloseCoordinator.Owner.FREE
                : InstrumentPanelCloseCoordinator.Owner.OTHER;
    }

    private static Snapshot inspect(String output, int display, String packageName) {
        if (output == null || output.length() > 128 * 1024 || display < 0
                || packageName == null || packageName.isEmpty()) return null;
        Snapshot value = new Snapshot();
        Matcher headers = STACK.matcher(output);
        int start = -1;
        boolean target = false;
        String header = "";
        String component = packageName + "/dezz.status.widget.instrument.InstrumentPanelActivity";
        while (true) {
            boolean next = headers.find();
            int end = next ? headers.start() : output.length();
            if (start >= 0 && target) {
                value.stacks++;
                String owner = null;
                for (String line : output.substring(start, end).split("\\r?\\n")) {
                    // A malformed/truncated next stack header must not hide a foreign owner.
                    if (line.trim().startsWith("Stack id=")) value.unparsed++;
                    if (line.contains("taskId=")) {
                        value.tasks++;
                        if (!line.contains("visible=true") && !line.contains("visible=false"))
                            value.unparsed++;
                    }
                    if (!line.contains("visible=true")) continue;
                    Matcher top = TOP.matcher(line);
                    if (!top.find()) { value.unparsed++; continue; }
                    String observed = top.group(1);
                    if (top.find() || (owner != null && !owner.equals(observed))) value.unparsed++;
                    owner = observed;
                }
                if (owner != null) {
                    // StackInfo repeats the stack's visible/topActivity for each task.
                    // Use stack bounds, not an arbitrary (possibly background) task's bounds.
                    Area area = Area.read(header);
                    if (area == null) value.unknownBounds++;
                    if (component.equals(owner)) {
                        value.own++;
                        value.panel = area;
                    } else {
                        value.other++;
                        if (area != null) value.others.add(area);
                        // These are OEM presentation surfaces, not a competing dashboard.
                        // Any other visible activity (including another Natro page) retains DIM.
                        if (!owner.startsWith("com.ecarx.dimmenu/")
                                && !owner.startsWith("com.ecarx.hud/")) value.foreignDashboard++;
                    }
                }
            }
            if (!next) break;
            header = headers.group();
            start = headers.end();
            target = Integer.toString(display).equals(headers.group(1));
        }
        if (value.panel != null) for (Area area : value.others) {
            if (value.panel.intersects(area)) value.overlapping++;
            else value.outside++;
        }
        return value;
    }

    private static final class Snapshot {
        int stacks, tasks, own, other, unparsed, overlapping, outside, unknownBounds, foreignDashboard;
        Area panel;
        final List<Area> others = new ArrayList<>();
    }

    private static final class Area {
        final int left, top, right, bottom;
        Area(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        static Area read(String header) {
            Matcher bounds = BOUNDS.matcher(header);
            if (!bounds.find()) return null;
            try {
                Area area = new Area(Integer.parseInt(bounds.group(1)), Integer.parseInt(bounds.group(2)),
                        Integer.parseInt(bounds.group(3)), Integer.parseInt(bounds.group(4)));
                if (bounds.find() || area.left >= area.right || area.top >= area.bottom) return null;
                return area;
            } catch (NumberFormatException invalid) { return null; }
        }
        boolean intersects(Area other) {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
        }
    }
}
