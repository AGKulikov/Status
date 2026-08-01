package dezz.status.widget;

/** Pure sizing rule for the media timeline drawn directly below the visible track title. */
public final class MediaProgressWidthPolicy {
    private MediaProgressWidthPolicy() {
    }

    public static int width(float measuredTitleWidth,
                            int compoundPaddingLeft,
                            int compoundPaddingRight,
                            int titleViewportWidth) {
        float safeTextWidth = Float.isFinite(measuredTitleWidth)
                ? Math.max(0f, measuredTitleWidth)
                : 0f;
        int safePadding = Math.max(0, compoundPaddingLeft)
                + Math.max(0, compoundPaddingRight);
        int naturalWidth = Math.max(1, (int) Math.ceil(safeTextWidth) + safePadding);
        return titleViewportWidth > 0
                ? Math.min(naturalWidth, titleViewportWidth)
                : naturalWidth;
    }
}
