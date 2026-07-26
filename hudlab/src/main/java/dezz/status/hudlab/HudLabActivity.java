/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A compact, independent test console for the physical ECARX HUD.
 */
public final class HudLabActivity extends Activity implements HudLabController.Listener {
    private static final int BG = Color.rgb(9, 12, 18);
    private static final int CARD = Color.rgb(19, 25, 36);
    private static final int CARD_BORDER = Color.rgb(45, 58, 78);
    private static final int TEXT = Color.rgb(236, 241, 249);
    private static final int MUTED = Color.rgb(155, 169, 190);
    private static final int BLUE = Color.rgb(34, 122, 222);
    private static final int GREEN = Color.rgb(22, 139, 83);
    private static final int RED = Color.rgb(174, 55, 55);
    private static final int AMBER = Color.rgb(176, 116, 28);

    private final List<Button> commandButtons = new ArrayList<>();
    private HudLabController controller;
    private TextView connectionBadge;
    private TextView snapshotView;
    private TextView logView;
    private TextView visualIndexView;
    private int visualIndex;
    private String fullStatus = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        setContentView(buildUi());
        setCommandsEnabled(false);
        controller = new HudLabController(this, this);
        controller.start();
    }

    @Override
    protected void onDestroy() {
        HudLabController current = controller;
        controller = null;
        if (current != null) current.close();
        super.onDestroy();
    }

    @Override
    public void onUpdated(String snapshot, String eventLog, boolean connected) {
        snapshotView.setText(snapshot);
        logView.setText(eventLog);
        connectionBadge.setText(connected ? "ECARX: ГОТОВО" : "ECARX: ОЖИДАНИЕ");
        connectionBadge.setTextColor(connected ? Color.rgb(102, 231, 156)
                : Color.rgb(255, 192, 92));
        fullStatus = snapshot + "\nСОБЫТИЯ\n" + eventLog;
        setCommandsEnabled(connected);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(BG);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView warning = text(
                "Экспериментальный стенд: используйте только на стоящей машине. "
                        + "Автоматических команд, перезагрузки HUD и остановки системных приложений нет.",
                14, Color.rgb(255, 199, 98), false);
        warning.setPadding(dp(10), dp(5), dp(10), dp(8));
        root.addView(warning);

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        horizontal.setHorizontalScrollBarEnabled(false);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(0, 0, 0, dp(4));
        horizontal.addView(columns, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        columns.addView(buildStatusColumn(), weightedColumn(1.18f, 0, dp(6)));
        columns.addView(buildActivationColumn(), weightedColumn(0.92f, dp(6), dp(6)));
        columns.addView(buildExperimentColumn(), weightedColumn(1.0f, dp(6), 0));

        root.addView(horizontal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button close = button("← Закрыть", CARD_BORDER, false);
        close.setOnClickListener(view -> finish());
        header.addView(close, fixedButton(dp(130)));

        TextView title = text("HUD Lab 0.1", 23, TEXT, true);
        title.setPadding(dp(16), 0, dp(18), 0);
        header.addView(title);

        connectionBadge = text("ECARX: ОЖИДАНИЕ", 15, Color.rgb(255, 192, 92), true);
        header.addView(connectionBadge, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = button("Обновить", BLUE, false);
        refresh.setOnClickListener(view -> {
            if (controller != null) controller.refreshNow();
        });
        header.addView(refresh, fixedButton(dp(132)));

        Button copy = button("Копировать статус", CARD_BORDER, false);
        copy.setOnClickListener(view -> copyStatus());
        LinearLayout.LayoutParams copyParams = fixedButton(dp(190));
        copyParams.leftMargin = dp(8);
        header.addView(copy, copyParams);
        return header;
    }

    private View buildStatusColumn() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(cardDrawable());

        body.addView(sectionTitle("Живые статусы и обратная связь"));

        snapshotView = text("Подключение…", 13, TEXT, false);
        snapshotView.setTypeface(Typeface.MONOSPACE);
        snapshotView.setTextIsSelectable(true);
        body.addView(snapshotView);

        TextView events = sectionTitle("Журнал команд");
        events.setPadding(0, dp(12), 0, dp(6));
        body.addView(events);

        logView = text("", 12, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        body.addView(logView);

        return scroll(body);
    }

    private View buildActivationColumn() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("1. Каналы включения HUD"));
        body.addView(note(
                "Проверяйте по одному каналу. Settings API и VFHUD отображены отдельно, "
                        + "хотя в этой версии SDK Settings вызывает VFHUD внутри."));

        body.addView(label("AdaptAPI SETTING_FUNC_HUD_ACTIVE"));
        body.addView(commandPair("OFF", RED,
                () -> controller.setSettingsActive(false),
                "ON", GREEN, () -> controller.setSettingsActive(true)));

        body.addView(label("Прямой VFHUD CB_VF_HUD_ActvReq"));
        body.addView(commandPair("OFF", RED, () -> controller.setVfActive(false),
                "ON", GREEN, () -> controller.setVfActive(true)));

        body.addView(label("Прямой DIM HudDispActvReq (signal 30788)"));
        body.addView(commandPair("OFF", RED, () -> controller.setDimActive(false),
                "ON", GREEN, () -> controller.setDimActive(true)));

        body.addView(label("AR через AdaptAPI"));
        body.addView(commandPair("AR OFF", RED, () -> controller.setSettingsAr(false),
                "AR ON", GREEN, () -> controller.setSettingsAr(true)));

        body.addView(label("AR напрямую через VFHUD"));
        body.addView(commandPair("AR OFF", RED, () -> controller.setVfAr(false),
                "AR ON", GREEN, () -> controller.setVfAr(true)));

        body.addView(label("Все три канала + AR одной командой"));
        body.addView(commandPair("ВСЁ OFF", RED,
                () -> controller.setAllActivationChannels(false),
                "ВСЁ ON", GREEN, () -> controller.setAllActivationChannels(true)));
        body.addView(note(
                "«ВСЁ ON» — быстрый возврат штатных запросов после эксперимента. "
                        + "Публикацию нашей панели этот стенд не запускает."));
        return scroll(body);
    }

    private View buildExperimentColumn() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("2. Режимы и visual mask"));
        body.addView(note(
                "Текущий режим по вашему дампу — IntellDrv (1). Сначала проверьте Simple (3), "
                        + "затем visual mask: это отдельный необработанный сигнал к DIM."));

        body.addView(label("VFHUD CB_HUD_DispModSet"));
        body.addView(modeGrid(false));

        body.addView(label("Прямой DIM HudDispModSetgReq · PEN=1"));
        body.addView(modeGrid(true));

        body.addView(label("HUD VisFctSetgReq: 20 функций"));
        body.addView(commandPair("Все 0", RED, () -> controller.setAllVisualFunctions(0),
                "Все 1", GREEN, () -> controller.setAllVisualFunctions(1)));

        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = commandButton("− индекс", CARD_BORDER, this::previousVisualIndex);
        selector.addView(previous, new LinearLayout.LayoutParams(0, dp(46), 1f));
        visualIndexView = text("F00", 18, TEXT, true);
        visualIndexView.setGravity(Gravity.CENTER);
        selector.addView(visualIndexView, new LinearLayout.LayoutParams(dp(74), dp(46)));
        Button next = commandButton("+ индекс", CARD_BORDER, this::nextVisualIndex);
        selector.addView(next, new LinearLayout.LayoutParams(0, dp(46), 1f));
        body.addView(selector);

        body.addView(commandPair("Выбранную OFF", RED,
                () -> controller.setVisualFunction(visualIndex, 0),
                "Выбранную ON", GREEN,
                () -> controller.setVisualFunction(visualIndex, 1)));

        body.addView(commandPair("PEN 0", AMBER, () -> controller.setVisualPen(0),
                "PEN 1", BLUE, () -> controller.setVisualPen(1)));
        body.addView(note(
                "Начальная локальная маска стенда: все 1, PEN=1. SDK не даёт getter для этой "
                        + "маски, поэтому «Все 1» — предполагаемый возврат видимости, а не "
                        + "считанное исходное состояние."));

        return scroll(body);
    }

    private View modeGrid(boolean directDim) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(modeRow(directDim, 0, "0 Guide", 1, "1 Drive"));
        grid.addView(modeRow(directDim, 2, "2 AR", 3, "3 Simple"));
        return grid;
    }

    private View modeRow(boolean directDim, int leftMode, String leftLabel,
                         int rightMode, String rightLabel) {
        return commandPair(leftLabel, BLUE,
                () -> setMode(directDim, leftMode),
                rightLabel, BLUE,
                () -> setMode(directDim, rightMode));
    }

    private void setMode(boolean directDim, int mode) {
        if (directDim) {
            controller.setDimDisplayMode(mode, 1);
        } else {
            controller.setVfDisplayMode(mode);
        }
    }

    private View commandPair(String leftText, int leftColor, Runnable leftAction,
                             String rightText, int rightColor, Runnable rightAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(7));
        Button left = commandButton(leftText, leftColor, leftAction);
        Button right = commandButton(rightText, rightColor, rightAction);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        leftParams.rightMargin = dp(4);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        rightParams.leftMargin = dp(4);
        row.addView(left, leftParams);
        row.addView(right, rightParams);
        return row;
    }

    private Button commandButton(String label, int color, Runnable action) {
        Button button = button(label, color, true);
        button.setOnClickListener(view -> {
            if (controller != null) action.run();
        });
        commandButtons.add(button);
        return button;
    }

    private void previousVisualIndex() {
        visualIndex = (visualIndex + 19) % 20;
        updateVisualIndex();
    }

    private void nextVisualIndex() {
        visualIndex = (visualIndex + 1) % 20;
        updateVisualIndex();
    }

    private void updateVisualIndex() {
        visualIndexView.setText(String.format(Locale.ROOT, "F%02d", visualIndex));
    }

    private void setCommandsEnabled(boolean enabled) {
        for (Button button : commandButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.42f);
        }
    }

    private void copyStatus() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("HUD Lab status", fullStatus));
        Toast.makeText(this, "Статус HUD Lab скопирован", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout columnBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(cardDrawable());
        return body;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(child, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 18, TEXT, true);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 13, TEXT, true);
        view.setPadding(0, dp(8), 0, dp(5));
        return view;
    }

    private TextView note(String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setPadding(0, 0, 0, dp(5));
        return view;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, int color, boolean allCapsOff) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setAllCaps(!allCapsOff);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        return button;
    }

    private GradientDrawable cardDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(CARD);
        drawable.setStroke(dp(1), CARD_BORDER);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private LinearLayout.LayoutParams fixedButton(int width) {
        return new LinearLayout.LayoutParams(width, dp(46));
    }

    private LinearLayout.LayoutParams weightedColumn(float weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.leftMargin = left;
        params.rightMargin = right;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
