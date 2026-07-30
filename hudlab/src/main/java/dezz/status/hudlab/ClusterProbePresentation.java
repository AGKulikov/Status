package dezz.status.hudlab;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/* loaded from: classes4.dex */
final class ClusterProbePresentation extends Presentation {
    private final int targetDisplayId;

    ClusterProbePresentation(Context context, Display display) {
        super(context, display, R.style.Theme_HudLab);
        this.targetDisplayId = display.getDisplayId();
        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private View makeContent() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(1);
        root.setGravity(17);
        root.setPadding(dp(36), dp(24), dp(36), dp(24));
        root.setBackgroundColor(Color.rgb(5, 54, 40));
        TextView title = new TextView(getContext());
        title.setText("HUD LAB · PRESENTATION\nDISPLAY ID " + this.targetDisplayId);
        title.setTextColor(-1);
        title.setTextSize(38.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(17);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView marker = new TextView(getContext());
        marker.setText("ТЕСТ ПРИЛОЖЕНИЯ НА ПРИБОРКЕ\n\nСмотрите, появились ли поверх зелёного поля штатные нижние блоки оборотов и скорости.");
        marker.setTextColor(Color.rgb(159, 255, 211));
        marker.setTextSize(23.0f);
        marker.setGravity(17);
        marker.setPadding(0, dp(30), 0, dp(30));
        root.addView(marker, new LinearLayout.LayoutParams(-1, -2));
        TextView footer = new TextView(getContext());
        footer.setText("Окно закроется автоматически через 12 секунд.\nТрассировка идёт на центральном экране.");
        footer.setTextColor(Color.rgb(255, 221, 142));
        footer.setTextSize(17.0f);
        footer.setGravity(17);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override // android.app.Dialog
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.addFlags(896);
            window.getDecorView().setSystemUiVisibility(5894);
        }
        setContentView(makeContent());
        if (window != null) {
            window.setLayout(-1, -1);
        }
    }
}
