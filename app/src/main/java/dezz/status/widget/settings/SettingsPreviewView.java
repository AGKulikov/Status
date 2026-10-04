/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** A read-only view of the existing editor canvas; no duplicate vehicle/map subscriptions. */
public final class SettingsPreviewView extends View {
    private final View source;
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable frame = new Runnable() {
        @Override public void run() { if (isAttachedToWindow()) { invalidate(); postDelayed(this, 100); } }
    };
    public SettingsPreviewView(Context context,View source) {
        super(context);this.source=source;setTag(SettingsEditorLayout.PREVIEW_TAG);
        setContentDescription("Живой предпросмотр");caption.setColor(Color.WHITE);
        caption.setTextSize(20*getResources().getDisplayMetrics().scaledDensity);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(frame);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(frame);super.onDetachedFromWindow();}
    @Override protected void onDraw(Canvas canvas){
        canvas.drawColor(0xFF10151D);canvas.drawText("Предпросмотр",16,32,caption);
        if(source.getWidth()<=0||source.getHeight()<=0)return;
        float scale=Math.min((getWidth()-24f)/source.getWidth(),(getHeight()-70f)/source.getHeight());
        if(scale<=0)return;
        canvas.save();canvas.translate((getWidth()-source.getWidth()*scale)/2f,
                54+(getHeight()-54-source.getHeight()*scale)/2f);
        canvas.scale(scale,scale);source.draw(canvas);canvas.restore();
    }
}
