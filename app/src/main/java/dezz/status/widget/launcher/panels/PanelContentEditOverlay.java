/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Reusable direct-on-HOME editor hook for a cell-based panel.
 *
 * <p>The overlay owns no panel state. A lightweight model supplies rectangles and atomically
 * accepts or rejects drag/resize operations, so media or another panel can reuse it later without
 * inheriting navigation behavior.</p>
 */
public final class PanelContentEditOverlay extends View {
    public static final class Item {
        @NonNull public final String id;
        @NonNull public final String label;
        public final int column;
        public final int row;
        public final int columnSpan;
        public final int rowSpan;

        public Item(@NonNull String id, @NonNull String label, int column, int row,
                    int columnSpan, int rowSpan) {
            this.id = id;
            this.label = label;
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }
    }

    public interface Model {
        int columns();
        int rows();
        @NonNull List<Item> items();
        boolean setPlacement(@NonNull String id, int column, int row,
                             int columnSpan, int rowSpan);
    }

    public interface Listener {
        /** {@code finished} is true on ACTION_UP, suitable for a final persistence flush. */
        void onPlacementChanged(@NonNull String id, boolean finished);
    }

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private final float handlePx;
    @Nullable private Model model;
    @Nullable private Listener listener;
    @Nullable private String selectedId;
    private float downX;
    private float downY;
    private int startColumn;
    private int startRow;
    private int startColumnSpan;
    private int startRowSpan;
    private boolean resizing;
    private boolean changedDuringGesture;

    public PanelContentEditOverlay(@NonNull Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        handlePx = 30f * density;
        gridPaint.setColor(Color.argb(75, 95, 165, 255));
        gridPaint.setStrokeWidth(Math.max(1f, density));
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(Math.max(2f, 2f * density));
        outlinePaint.setColor(Color.argb(210, 125, 190, 255));
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(Math.max(3f, 3f * density));
        selectedPaint.setColor(Color.rgb(40, 145, 255));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(58, 30, 130, 245));
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(12f * density);
        labelPaint.setFakeBoldText(true);
        setClickable(true);
        setFocusable(false);
        setVisibility(GONE);
    }

    public void setModel(@Nullable Model model, @Nullable Listener listener) {
        this.model = model;
        this.listener = listener;
        selectedId = null;
        invalidate();
    }

    public void setEditing(boolean editing) {
        selectedId = null;
        setVisibility(editing ? VISIBLE : GONE);
        setEnabled(editing);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        Model current = model;
        if (current == null) return;
        int columns = Math.max(1, current.columns());
        int rows = Math.max(1, current.rows());
        for (int column = 1; column < columns; column++) {
            float x = column * getWidth() / (float) columns;
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
        }
        for (int row = 1; row < rows; row++) {
            float y = row * getHeight() / (float) rows;
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }
        for (Item item : safeItems(current)) {
            itemRect(item, current, scratch);
            canvas.drawRect(scratch, fillPaint);
            canvas.drawRect(scratch, item.id.equals(selectedId) ? selectedPaint : outlinePaint);
            float labelX = scratch.left + dp(6);
            float labelY = Math.min(scratch.bottom - dp(5), scratch.top + dp(16));
            canvas.save();
            canvas.clipRect(scratch);
            canvas.drawText(item.label, labelX, labelY, labelPaint);
            canvas.restore();
            if (item.id.equals(selectedId)) {
                canvas.drawRect(Math.max(scratch.left, scratch.right - handlePx),
                        Math.max(scratch.top, scratch.bottom - handlePx),
                        scratch.right, scratch.bottom, selectedPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        Model current = model;
        if (!isEnabled() || current == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                Item hit = findHit(current, event.getX(), event.getY());
                if (hit == null) {
                    selectedId = null;
                    invalidate();
                    return true;
                }
                selectedId = hit.id;
                downX = event.getX();
                downY = event.getY();
                startColumn = hit.column;
                startRow = hit.row;
                startColumnSpan = hit.columnSpan;
                startRowSpan = hit.rowSpan;
                itemRect(hit, current, scratch);
                resizing = event.getX() >= scratch.right - handlePx
                        && event.getY() >= scratch.bottom - handlePx;
                changedDuringGesture = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selectedId == null) return true;
                float cellWidth = getWidth() / (float) Math.max(1, current.columns());
                float cellHeight = getHeight() / (float) Math.max(1, current.rows());
                int deltaColumn = Math.round((event.getX() - downX)
                        / Math.max(1f, cellWidth));
                int deltaRow = Math.round((event.getY() - downY)
                        / Math.max(1f, cellHeight));
                int column = resizing ? startColumn : startColumn + deltaColumn;
                int row = resizing ? startRow : startRow + deltaRow;
                int columnSpan = resizing
                        ? Math.max(1, startColumnSpan + deltaColumn) : startColumnSpan;
                int rowSpan = resizing
                        ? Math.max(1, startRowSpan + deltaRow) : startRowSpan;
                if (current.setPlacement(selectedId, column, row, columnSpan, rowSpan)) {
                    changedDuringGesture = true;
                    Listener callback = listener;
                    if (callback != null) callback.onPlacementChanged(selectedId, false);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (selectedId != null && changedDuringGesture) {
                    Listener callback = listener;
                    if (callback != null) callback.onPlacementChanged(selectedId, true);
                }
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Nullable
    private Item findHit(@NonNull Model current, float x, float y) {
        List<Item> items = safeItems(current);
        for (int index = items.size() - 1; index >= 0; index--) {
            Item item = items.get(index);
            itemRect(item, current, scratch);
            if (scratch.contains(x, y)) return item;
        }
        return null;
    }

    private void itemRect(@NonNull Item item, @NonNull Model current,
                          @NonNull RectF destination) {
        int columns = Math.max(1, current.columns());
        int rows = Math.max(1, current.rows());
        destination.set(
                item.column * getWidth() / (float) columns,
                item.row * getHeight() / (float) rows,
                (item.column + item.columnSpan) * getWidth() / (float) columns,
                (item.row + item.rowSpan) * getHeight() / (float) rows);
    }

    @NonNull
    private static List<Item> safeItems(@NonNull Model model) {
        List<Item> value = model.items();
        return value == null ? Collections.emptyList() : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
