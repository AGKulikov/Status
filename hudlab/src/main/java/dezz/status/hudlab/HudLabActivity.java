/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.widget.FrameLayout;
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
    private static final int REQUEST_STORAGE = 401;
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
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<View> tabPages = new ArrayList<>();
    private HudLabController controller;
    private TextView connectionBadge;
    private TextView snapshotView;
    private TextView logView;
    private TextView visualIndexView;
    private TextView visualPenView;
    private TextView heldProbeIndexView;
    private TextView profileSearchModeView;
    private TextView profileSearchStatusView;
    private TextView fallbackStatusView;
    private TextView exportStatusView;
    private Button exportButton;
    private Button fallbackEnableButton;
    private int visualIndex;
    private int heldProbeIndex;
    private int visualPen = 1;
    private int profileSearchMode;
    private int selectedFallbackMode = HudModeFallbackStore.NO_MODE;
    private boolean commandChannelConnected;
    private String fullStatus = "";
    private String lastDumpPath = "";

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

        HudModeFallbackStore.Config fallback = HudModeFallbackStore.read(this);
        if (fallback.enabled && HudModeFallbackStore.isTargetMode(fallback.target)) {
            selectedFallbackMode = fallback.target;
        }
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            exportSystemDump();
        } else {
            exportStatusView.setText(
                    "Нет доступа к общему Download. Разрешите доступ к файлам и повторите.");
            Toast.makeText(this, "Доступ к файлам не выдан", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onUpdated(String snapshot, String eventLog, boolean connected) {
        snapshotView.setText(snapshot);
        logView.setText(eventLog);
        connectionBadge.setText(connected ? "ECARX: ГОТОВО" : "ECARX: ОЖИДАНИЕ");
        connectionBadge.setTextColor(connected ? Color.rgb(102, 231, 156)
                : Color.rgb(255, 192, 92));
        fullStatus = snapshot + "\nСОБЫТИЯ\n" + eventLog;
        if (profileSearchStatusView != null) {
            profileSearchStatusView.setText(findStatusLine(snapshot, "Поиск 01:"));
        }
        updateFallbackStatus();
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
                        + "Команды выполняются только по нажатию. Поиск F00–F19 не сохраняется "
                        + "автоматически и не перезагружает HUD.",
                14, Color.rgb(255, 199, 98), false);
        warning.setPadding(dp(10), dp(5), dp(10), dp(8));
        root.addView(warning);

        root.addView(buildTabBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout pages = new FrameLayout(this);
        addTabPage(pages, buildNewPathsTab());
        addTabPage(pages, buildSystemDumpTab());
        addTabPage(pages, buildElementsTab());
        addTabPage(pages, buildMaskTab());
        addTabPage(pages, buildActivationTab());
        addTabPage(pages, buildStatusTab());
        root.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        selectTab(0);
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button close = button("← Закрыть", CARD_BORDER, false);
        close.setOnClickListener(view -> finish());
        header.addView(close, fixedButton(dp(130)));

        TextView title = text("HUD Lab 0.18", 23, TEXT, true);
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

    private View buildTabBar() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, 0, 0, dp(5));
        addTabButton(tabs, "01 + СКОРОСТЬ", 0);
        addTabButton(tabs, "СИСТЕМНЫЙ ДАМП", 1);
        addTabButton(tabs, "DISPLAY_*", 2);
        addTabButton(tabs, "MASK", 3);
        addTabButton(tabs, "КАНАЛЫ", 4);
        addTabButton(tabs, "СТАТУС", 5);
        return tabs;
    }

    private void addTabButton(LinearLayout parent, String label, int index) {
        Button tab = button(label, CARD_BORDER, true);
        tab.setTextSize(12);
        tab.setOnClickListener(view -> selectTab(index));
        tabButtons.add(tab);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(39), 1f);
        if (index > 0) params.leftMargin = dp(5);
        parent.addView(tab, params);
    }

    private void addTabPage(FrameLayout parent, View page) {
        page.setVisibility(View.GONE);
        tabPages.add(page);
        parent.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void selectTab(int index) {
        for (int position = 0; position < tabPages.size(); position++) {
            boolean selected = position == index;
            tabPages.get(position).setVisibility(selected ? View.VISIBLE : View.GONE);
            tabButtons.get(position).setBackgroundTintList(
                    ColorStateList.valueOf(selected ? BLUE : CARD_BORDER));
        }
    }

    private View buildSystemDumpTab() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("Экспорт фактической реализации HUD этой прошивки"));
        body.addView(note(
                "HUD Lab 0.2 подтвердил: DISPLAY_DRIVE_ENVIRONMENT и DISPLAY_SAFETY "
                        + "присутствуют в SDK, но магнитола отвечает accepted=false. "
                        + "Повторять эти команды больше не нужно."));
        body.addView(note(
                "Кнопка ниже соберёт в один ZIP установленные системные APK HUD, DIMProtocol, "
                        + "PowerSomeIP, AdaptAPI, OpenAPI, читаемые ECARX/Geely framework-JAR, "
                        + "низкоуровневые IPCP/VHAL/UART/LIN/LVDS/MCU-файлы и журнал HUD Lab. "
                        + "Личные данные приложений не читаются, настройки не меняются."));

        exportButton = button("СОБРАТЬ ZIP В DOWNLOAD", BLUE, false);
        exportButton.setOnClickListener(view -> requestSystemDump());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        exportParams.topMargin = dp(14);
        body.addView(exportButton, exportParams);

        exportStatusView = text(
                "ZIP будет сохранён в Download/HudLabDump. После завершения пришлите его сюда.",
                14, TEXT, false);
        exportStatusView.setTextIsSelectable(true);
        exportStatusView.setPadding(0, dp(14), 0, dp(10));
        body.addView(exportStatusView);

        Button copyPath = button("КОПИРОВАТЬ ПУТЬ К ZIP", CARD_BORDER, false);
        copyPath.setOnClickListener(view -> copyDumpPath());
        body.addView(copyPath, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        body.addView(note(
                "Экспорт можно выполнить без подключения ноутбука и без root. "
                        + "На Android 9 при первом запуске потребуется разрешить доступ к файлам."));
        return scroll(body);
    }

    private void requestSystemDump() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            return;
        }
        exportSystemDump();
    }

    private void exportSystemDump() {
        if (exportButton == null || !exportButton.isEnabled()) return;
        exportButton.setEnabled(false);
        exportButton.setAlpha(0.55f);
        exportStatusView.setText(
                "Собираю системные пакеты и библиотеки… Не закрывайте HUD Lab.");

        Thread worker = new Thread(() -> {
            try {
                HudSystemDumpExporter.Result result =
                        HudSystemDumpExporter.export(getApplicationContext(), fullStatus);
                runOnUiThread(() -> {
                    lastDumpPath = result.file.getAbsolutePath();
                    exportStatusView.setText(result.summary()
                            + "\n\nПришлите этот ZIP в чат целиком.");
                    restoreExportButton();
                    Toast.makeText(this, "Системный дамп HUD готов",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable failure) {
                runOnUiThread(() -> {
                    String message = failure.getMessage();
                    exportStatusView.setText("Ошибка экспорта: "
                            + failure.getClass().getSimpleName()
                            + (message == null ? "" : "\n" + message));
                    restoreExportButton();
                    Toast.makeText(this, "Не удалось собрать ZIP",
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "hud-system-export");
        worker.start();
    }

    private void restoreExportButton() {
        exportButton.setEnabled(true);
        exportButton.setAlpha(1f);
    }

    private void copyDumpPath() {
        if (lastDumpPath.isEmpty()) {
            Toast.makeText(this, "Сначала соберите ZIP", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("HUD Lab dump", lastDumpPath));
        Toast.makeText(this, "Путь скопирован", Toast.LENGTH_SHORT).show();
    }

    private View buildNewPathsTab() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("Точный поиск внутри подтверждённого способа 01"));
        body.addView(note(
                "Вы подтвердили, что ProfileTransfer действительно меняет штатный HUD: "
                        + "в отдельных режимах исчезает машинка, а скорость перемещается. "
                        + "Выберите ниже режим, в котором машинки уже нет, затем запустите "
                        + "поэлементный перебор. Каждая комбинация держится на HUD 3,6 секунды."));

        body.addView(label("Режим 01, внутри которого искать отдельный слой скорости"));
        body.addView(commandRow(BLUE,
                new String[]{"0 GUIDE", "1 DRIVE", "2 AR", "3 SIMPLE"},
                new Runnable[]{
                        () -> selectProfileSearchMode(0),
                        () -> selectProfileSearchMode(1),
                        () -> selectProfileSearchMode(2),
                        () -> selectProfileSearchMode(3)
                }));
        profileSearchModeView = text("Выбран режим поиска: 0 GUIDE", 16, TEXT, true);
        profileSearchModeView.setPadding(dp(8), dp(5), dp(8), dp(8));
        body.addView(profileSearchModeView);

        body.addView(label("Ручной фиксированный тест · активный профиль"));
        LinearLayout heldSelector = new LinearLayout(this);
        heldSelector.setOrientation(LinearLayout.HORIZONTAL);
        heldSelector.setGravity(Gravity.CENTER_VERTICAL);
        heldSelector.addView(commandButton("− F", CARD_BORDER, this::previousHeldProbe),
                new LinearLayout.LayoutParams(0, dp(46), 1f));
        heldProbeIndexView = text("F00", 18, TEXT, true);
        heldProbeIndexView.setGravity(Gravity.CENTER);
        heldSelector.addView(heldProbeIndexView,
                new LinearLayout.LayoutParams(dp(86), dp(46)));
        heldSelector.addView(commandButton("+ F", CARD_BORDER, this::nextHeldProbe),
                new LinearLayout.LayoutParams(0, dp(46), 1f));
        body.addView(heldSelector);
        body.addView(commandPair("ВСЕ 1 → ВЫБРАННАЯ OFF", AMBER,
                () -> controller.applyHeldVisualProbe(
                        profileSearchMode, heldProbeIndex),
                "ВОССТАНОВИТЬ ВСЕ 1", GREEN,
                () -> controller.applyHeldVisualBaseline(profileSearchMode, 1)));
        body.addView(note(
                "Ручная проба сначала отправляет полный baseline all=1, затем полный 20-полевой "
                        + "вектор с одним Fxx=0. Результат остаётся до восстановления. "
                        + "В журнал пишется точный protobuf, а не условное «SUCCESS». "
                        + "Если ProfPenSts1 недоступен, версия 0.18 безопасно использует "
                        + "активный профиль PA33845 как PEN (в вашем дампе это профиль 13)."));

        body.addView(label("Безопасный автоматический проход · активный PEN"));
        body.addView(singleCommand("ЗАПУСТИТЬ ПОЛНЫЙ ЦИКЛ", AMBER,
                () -> controller.startSafeProfileVisualScan(profileSearchMode)));
        body.addView(note(
                "Строгая последовательность: all=0 держится 3,6 с и автоматически снимается; "
                        + "затем all=1; затем F00…F19 по одному становятся 0 при остальных=1. "
                        + "Последний обязательный шаг снова отправляет all=1. При ошибке или "
                        + "закрытии стенд также пытается немедленно восстановить all=1."));

        body.addView(commandPair("ЗАПИСАТЬ Fxx И ВОССТАНОВИТЬ", GREEN,
                () -> controller.markProfileVisualScanFound(),
                "ОСТАНОВИТЬ И ВОССТАНОВИТЬ", BLUE,
                () -> controller.restoreProfileVisualSearch()));
        profileSearchStatusView = text("Поиск 01: не запущен", 15,
                Color.rgb(255, 214, 125), true);
        profileSearchStatusView.setTypeface(Typeface.MONOSPACE);
        profileSearchStatusView.setPadding(dp(8), dp(5), dp(8), dp(12));
        body.addView(profileSearchStatusView);
        body.addView(note(
                "Как только скорость полностью исчезнет, сразу нажмите зелёную кнопку. "
                        + "Её mode, активный PEN и Fxx будут зафиксированы в журнале, после чего "
                        + "маска автоматически вернётся в all=1. Стенд работает только с "
                        + "подтверждённым vendor framework диапазоном профилей 0…13."));

        body.addView(sectionTitle("Точные дополнительные состояния из системного ECARX Navi API"));
        body.addView(note(
                "Это не случайные значения. Системный NaviInteraction отправляет их в соседний "
                        + "VFHUD CB33260: 4 — перестроение маршрута, 5 — въезд в тоннель, "
                        + "6 — выезд из тоннеля. Проверьте HUD после каждого нажатия; обратная "
                        + "связь отображается как PA33894."));
        body.addView(commandRow(AMBER,
                new String[]{"4 REROUTING", "5 TUNNEL ENTER", "6 TUNNEL END"},
                new Runnable[]{
                        () -> controller.setVfDisplayMode(4),
                        () -> controller.setVfDisplayMode(5),
                        () -> controller.setVfDisplayMode(6)
                }));

        body.addView(sectionTitle("Ручная проверка и прежние диагностические пути"));
        body.addView(label("01 · ProfileTransfer HUD mode · CB33278 / PA33937"));
        body.addView(commandRow(BLUE,
                new String[]{"0 GUIDE", "1 DRIVE", "2 AR", "3 SIMPLE", "ОТКАТ"},
                new Runnable[]{
                        () -> applyProfileTransferMode(0),
                        () -> applyProfileTransferMode(1),
                        () -> applyProfileTransferMode(2),
                        () -> applyProfileTransferMode(3),
                        () -> controller.restoreProfileTransferMode()
                }));
        body.addView(singleCommand("−1 RAW → CB33278 (В ОБХОД SDK)", RED,
                this::applyRawProfileTransferMode));
        body.addView(note(
                "Обычный ECARX SDK принимает здесь только 0…3 и отбрасывает −1 до отправки. "
                        + "Эта отдельная кнопка передаёт −1 напрямую в тот же CB33278 через "
                        + "ECARX property service. Команда разрешается только после сохранения "
                        + "валидного режима для возврата. Если PA33937 не читается, сначала "
                        + "нажмите любой рабочий режим 0…3. Для возврата нажмите «ОТКАТ»."));

        body.addView(sectionTitle("Резервный автоповтор · по умолчанию ВЫКЛ"));
        body.addView(note(
                "Включайте только после визуального подтверждения выбранного режима 0…3 выше. "
                        + "Резерв повторяет только CB33278 после загрузки, смены профиля, "
                        + "переходов HUD/ADAS и пересечения 20 км/ч. Watchdog проверяет состояние "
                        + "раз в минуту; действует лимит 5 записей/мин и circuit breaker. "
                        + "RAW −1, mask 30816, save 29892 и ProfileTransfer reboot "
                        + "не отправляются."));
        fallbackStatusView = text("", 14, Color.rgb(255, 214, 125), true);
        fallbackStatusView.setTypeface(Typeface.MONOSPACE);
        fallbackStatusView.setPadding(dp(8), dp(5), dp(8), dp(8));
        body.addView(fallbackStatusView);
        LinearLayout fallbackControls = new LinearLayout(this);
        fallbackControls.setOrientation(LinearLayout.HORIZONTAL);
        fallbackControls.setPadding(0, 0, 0, dp(7));
        fallbackEnableButton = commandButton("ВКЛЮЧИТЬ ВЫБРАННЫЙ TARGET", AMBER,
                () -> controller.enableModeFallback(selectedFallbackMode));
        Button disableFallback = button("OFF · НЕМЕДЛЕННО ПРЕКРАТИТЬ", RED, true);
        disableFallback.setOnClickListener(view -> {
            try {
                HudModeFallbackService.disable(this);
                Toast.makeText(this, "Резервный автоповтор выключен",
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable failure) {
                Toast.makeText(this, "Не удалось выключить: "
                                + failure.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
            updateFallbackStatus();
        });
        LinearLayout.LayoutParams fallbackLeft =
                new LinearLayout.LayoutParams(0, dp(48), 1f);
        fallbackLeft.rightMargin = dp(4);
        LinearLayout.LayoutParams fallbackRight =
                new LinearLayout.LayoutParams(0, dp(48), 1f);
        fallbackRight.leftMargin = dp(4);
        fallbackControls.addView(fallbackEnableButton, fallbackLeft);
        fallbackControls.addView(disableFallback, fallbackRight);
        body.addView(fallbackControls);
        body.addView(note(
                "OFF сначала синхронно сохраняет запрет, затем снимает callbacks и останавливает "
                        + "foreground service. Автоматического отката при OFF нет: после остановки "
                        + "при необходимости нажмите обычный «ОТКАТ»."));
        updateFallbackStatus();

        body.addView(label("02 · CEM HUD mode с реальным активным PEN · signal 30814"));
        body.addView(commandRow(BLUE,
                new String[]{"0 GUIDE", "1 DRIVE", "2 AR", "3 SIMPLE"},
                new Runnable[]{
                        () -> controller.setActiveProfileDimMode(0),
                        () -> controller.setActiveProfileDimMode(1),
                        () -> controller.setActiveProfileDimMode(2),
                        () -> controller.setActiveProfileDimMode(3)
                }));

        body.addView(label("03 · Visual mask с реальным активным PEN · signal 30816"));
        body.addView(commandPair("ВСЕ F00–F19 = 0", RED,
                () -> controller.setActiveProfileVisualMask(true),
                "ВСЕ F00–F19 = 1", GREEN,
                () -> controller.setActiveProfileVisualMask(false)));
        body.addView(note(
                "Это временная диагностическая маска, а не доказанный постоянный способ. "
                        + "Словарь F00–F19 в прошивке отсутствует, поэтому all=0 может скрыть "
                        + "в том числе штатные предупреждения. При закрытии стенда любая "
                        + "неединичная маска автоматически возвращается в all=1."));

        body.addView(label("04 · Driver HMI background · signal 30805 · значения 0…5"));
        body.addView(commandRow(AMBER,
                new String[]{"BG 0", "BG 1", "BG 2", "BG 3", "BG 4", "BG 5"},
                new Runnable[]{
                        () -> controller.setDriverHmiBackground(0),
                        () -> controller.setDriverHmiBackground(1),
                        () -> controller.setDriverHmiBackground(2),
                        () -> controller.setDriverHmiBackground(3),
                        () -> controller.setDriverHmiBackground(4),
                        () -> controller.setDriverHmiBackground(5)
                }));

        body.addView(label("05 · Driver HMI user interface · signal 30807 · default/variants 1…9"));
        body.addView(commandRow(AMBER,
                new String[]{"UI 0", "UI 1", "UI 2", "UI 3", "UI 4"},
                new Runnable[]{
                        () -> controller.setDriverHmiInterface(0),
                        () -> controller.setDriverHmiInterface(1),
                        () -> controller.setDriverHmiInterface(2),
                        () -> controller.setDriverHmiInterface(3),
                        () -> controller.setDriverHmiInterface(4)
                }));
        body.addView(commandRow(AMBER,
                new String[]{"UI 5", "UI 6", "UI 7", "UI 8", "UI 9"},
                new Runnable[]{
                        () -> controller.setDriverHmiInterface(5),
                        () -> controller.setDriverHmiInterface(6),
                        () -> controller.setDriverHmiInterface(7),
                        () -> controller.setDriverHmiInterface(8),
                        () -> controller.setDriverHmiInterface(9)
                }));

        body.addView(label("06 · DIM information layer · signal 30792"));
        body.addView(commandRow(AMBER,
                new String[]{"0 INFO OFF", "1 INFO ON", "2 PARTIAL", "3 WELCOME"},
                new Runnable[]{
                        () -> controller.setMultimediaInformationMode(0),
                        () -> controller.setMultimediaInformationMode(1),
                        () -> controller.setMultimediaInformationMode(2),
                        () -> controller.setMultimediaInformationMode(3)
                }));

        body.addView(label("07 · Individual DIM theme · signal 30785"));
        body.addView(commandPair("INDIVIDUAL OFF", RED,
                () -> controller.setIndividualTheme(false),
                "INDIVIDUAL ON", GREEN,
                () -> controller.setIndividualTheme(true)));

        body.addView(label("08 · HMI theme renderer · signal 30787"));
        body.addView(commandRow(BLUE,
                new String[]{"0 NIGHT", "1 DAY", "2 AUTO", "3 RESERVED"},
                new Runnable[]{
                        () -> controller.setHmiThemeMode(0),
                        () -> controller.setHmiThemeMode(1),
                        () -> controller.setHmiThemeMode(2),
                        () -> controller.setHmiThemeMode(3)
                }));

        body.addView(label("09 · Driver display template · signal 30803 / feedback 30873"));
        body.addView(commandRow(BLUE,
                new String[]{"0 COMFORT", "1 ECO", "2 DYNAMIC"},
                new Runnable[]{
                        () -> controller.setDriverDisplayTheme(0),
                        () -> controller.setDriverDisplayTheme(1),
                        () -> controller.setDriverDisplayTheme(2)
                }));

        body.addView(label("10 · Vehicle model clear · CB33284 / PA33943"));
        body.addView(commandRow(RED,
                new String[]{"CLEAR 0", "CLEAR 1", "ТОЧНЫЙ ОТКАТ"},
                new Runnable[]{
                        () -> controller.setVehicleModelClear(false),
                        () -> controller.setVehicleModelClear(true),
                        () -> controller.restoreVehicleModelClear()
                }));

        body.addView(sectionTitle("Откат и сохранение только подтверждённого результата"));
        body.addView(singleCommand("ПЕРЕЗАГРУЗИТЬ АКТИВНЫЙ ПРОФИЛЬ", GREEN,
                () -> controller.reloadActiveProfile()));
        body.addView(note(
                "04/05/09 отправляются transient и сами в память не записываются. "
                        + "«Перезагрузить активный профиль» возвращает сохранённую штатную "
                        + "компоновку. Сохранение найденного варианта выполняйте только после "
                        + "визуального подтверждения на HUD."));
        body.addView(singleCommand("СОХРАНИТЬ ТЕКУЩИЙ НАЙДЕННЫЙ ВАРИАНТ (29892 0→1)",
                AMBER, () -> controller.persistCurrentProfileSettings()));
        body.addView(note(
                "Сохранение 29892 не используется во время поиска: связь этого общего сигнала "
                        + "с HUD-mask не доказана. Нажимайте его только после повторного "
                        + "визуального подтверждения найденного Fxx."));
        return scroll(body);
    }

    private void selectProfileSearchMode(int mode) {
        profileSearchMode = mode;
        if (profileSearchModeView != null) {
            String label;
            switch (mode) {
                case 0:
                    label = "0 GUIDE";
                    break;
                case 1:
                    label = "1 DRIVE";
                    break;
                case 2:
                    label = "2 AR";
                    break;
                case 3:
                    label = "3 SIMPLE";
                    break;
                default:
                    label = Integer.toString(mode);
                    break;
            }
            profileSearchModeView.setText("Проверяется режим поиска: " + label);
        }
        if (controller != null) {
            controller.setProfileTransferMode(mode,
                    () -> confirmFallbackTarget(mode));
        }
    }

    private void applyProfileTransferMode(int mode) {
        if (controller != null) {
            controller.setProfileTransferMode(mode,
                    () -> confirmFallbackTarget(mode));
        }
    }

    private void confirmFallbackTarget(int mode) {
        selectedFallbackMode = HudProfileTransferMode.requireSdkMode(mode);
        if (profileSearchMode == mode && profileSearchModeView != null) {
            profileSearchModeView.setText("Выбран режим поиска: "
                    + HudModeFallbackStore.modeLabel(mode));
        }
        updateFallbackStatus();
        Toast.makeText(this,
                "Режим " + mode + " применён; target готов к ручному включению",
                Toast.LENGTH_SHORT).show();
    }

    private void applyRawProfileTransferMode() {
        if (controller != null) controller.setRawProfileTransferMinusOne();
        updateFallbackStatus();
    }

    private void updateFallbackStatus() {
        if (fallbackStatusView == null) return;
        fallbackStatusView.setText("Выбран target: "
                + HudModeFallbackStore.modeLabel(selectedFallbackMode)
                + "\n" + HudModeFallbackStore.describe(this));
        updateFallbackEnableButton();
    }

    private void updateFallbackEnableButton() {
        if (fallbackEnableButton == null) return;
        boolean enabled = commandChannelConnected
                && HudModeFallbackStore.isTargetMode(selectedFallbackMode);
        fallbackEnableButton.setEnabled(enabled);
        fallbackEnableButton.setAlpha(enabled ? 1f : 0.42f);
    }

    private static String findStatusLine(String status, String prefix) {
        if (status == null || status.isEmpty()) return prefix + " —";
        String[] lines = status.split("\\n");
        for (String line : lines) {
            if (line.startsWith(prefix)) return line;
        }
        return prefix + " —";
    }

    private View buildElementsTab() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("Отдельное скрытие штатного содержимого HUD"));
        body.addView(sectionTitle("Штатный AR-флаг активного профиля · старое меню HUD"));
        body.addView(note(
                "В старых ECARX Settings переключатель «AR режим» записывал функцию "
                        + "654443008 в поле vfhudbyte0 полного профиля автомобиля. "
                        + "Здесь сохраняются точные raw-байты PA33873, меняется только protobuf-"
                        + "поле 111 и выполняется подтверждённое чтение обратно. Публичный JSON "
                        + "не используется: он не содержит 65 скрытых vendor-полей."));
        body.addView(commandRow(AMBER,
                new String[]{"AR ON · 1", "AR OFF · 0", "ТОЧНЫЙ ОТКАТ"},
                new Runnable[]{
                        () -> controller.setUserProfileHudAr(true),
                        () -> controller.setUserProfileHudAr(false),
                        () -> controller.restoreUserProfileHudAr()
                }));
        body.addView(note(
                "Сначала проверьте AR ON · 1: именно это значение использовал старый штатный "
                        + "переключатель. Текущий profile/value виден во вкладке «Статус». "
                        + "Откат возвращает исходное значение AR в свежий raw-профиль того же "
                        + "активного Profile ID, сохраняя последующие изменения других настроек."));

        body.addView(sectionTitle("Категории содержимого через текущий Settings API"));
        body.addView(note(
                "Это новый путь ECARX Settings API, не использовавшийся в HUD Lab 0.1. "
                        + "Он не выключает питание HUD и не запускает нашу панель. "
                        + "После нажатия смотрите результат и значение support/value во вкладке «Статус»."));

        body.addView(label("Машинка и дорожное окружение · DISPLAY_DRIVE_ENVIRONMENT"));
        body.addView(commandPair("СКРЫТЬ МАШИНКУ", RED,
                () -> controller.setDisplayDriveEnvironment(false),
                "ПОКАЗАТЬ МАШИНКУ", GREEN,
                () -> controller.setDisplayDriveEnvironment(true)));

        body.addView(label("Скорость и информация безопасности · DISPLAY_SAFETY"));
        body.addView(commandPair("СКРЫТЬ СКОРОСТЬ", RED,
                () -> controller.setDisplaySafety(false),
                "ПОКАЗАТЬ СКОРОСТЬ", GREEN,
                () -> controller.setDisplaySafety(true)));

        body.addView(label("Обе искомые категории одной командой"));
        body.addView(commandPair("СКРЫТЬ ОБЕ", RED,
                () -> controller.setPrimaryDisplayElements(false),
                "ПОКАЗАТЬ ОБЕ", GREEN,
                () -> controller.setPrimaryDisplayElements(true)));

        body.addView(label("Остальные отдельные категории"));
        body.addView(commandPair("МЕДИА OFF", AMBER,
                () -> controller.setDisplayMedia(false),
                "МЕДИА ON", BLUE,
                () -> controller.setDisplayMedia(true)));
        body.addView(commandPair("НАВИГАЦИЯ OFF", AMBER,
                () -> controller.setDisplayNavigation(false),
                "НАВИГАЦИЯ ON", BLUE,
                () -> controller.setDisplayNavigation(true)));
        body.addView(commandPair("ТЕЛЕФОН OFF", AMBER,
                () -> controller.setDisplayBtPhone(false),
                "ТЕЛЕФОН ON", BLUE,
                () -> controller.setDisplayBtPhone(true)));

        body.addView(singleCommand("ВОССТАНОВИТЬ ВСЕ ПЯТЬ КАТЕГОРИЙ", GREEN,
                () -> controller.restoreAllDisplayElements()));
        body.addView(note(
                "Если SDK ответит notavailable/accepted=false, команда на этой прошивке "
                        + "не связана с DIM. Это диагностический результат, а не успешное скрытие."));
        return scroll(body);
    }

    private View buildStatusTab() {
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

    private View buildActivationTab() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("Полное включение HUD и режимы — не искомое скрытие"));
        body.addView(note(
                "Эти команды сохранены только для диагностики и восстановления. "
                        + "OFF здесь может погасить HUD целиком; машинку и скорость отдельно "
                        + "они, как уже подтверждено тестом, не скрывают."));

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

        body.addView(label("VFHUD CB_HUD_DispModSet"));
        body.addView(modeGrid(false));

        body.addView(label("Прямой DIM HudDispModSetgReq · PEN=1"));
        body.addView(modeGrid(true));
        body.addView(note(
                "Режимы: 0 IntellGuide, 1 IntellDrive, 2 AR, 3 Simple. "
                        + "Они не являются переключателями машинки или скорости."));
        return scroll(body);
    }

    private View buildMaskTab() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("Низкоуровневая visual mask · профили PEN 0–13"));
        body.addView(note(
                "PEN в ECARX — идентификатор профиля, а не Android-дисплей. Дамп подтверждает "
                        + "профили 0…11, CarSharing 12 и Default 13. Значения 14 и 15 стенд "
                        + "не отправляет: их смысл в протоколе не доказан."));

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

        LinearLayout penSelector = new LinearLayout(this);
        penSelector.setOrientation(LinearLayout.HORIZONTAL);
        penSelector.setGravity(Gravity.CENTER_VERTICAL);
        Button previousPen = commandButton("− PEN", CARD_BORDER, this::previousVisualPen);
        penSelector.addView(previousPen, new LinearLayout.LayoutParams(0, dp(46), 1f));
        visualPenView = text("PEN 1", 17, TEXT, true);
        visualPenView.setGravity(Gravity.CENTER);
        penSelector.addView(visualPenView, new LinearLayout.LayoutParams(dp(150), dp(46)));
        Button nextPen = commandButton("+ PEN", CARD_BORDER, this::nextVisualPen);
        penSelector.addView(nextPen, new LinearLayout.LayoutParams(0, dp(46), 1f));
        body.addView(penSelector);

        body.addView(singleCommand("Применить выбранный PEN", BLUE,
                () -> controller.setVisualPen(visualPen)));
        body.addView(note(
                "SDK не даёт getter маски signal 30816: каждая отправка полностью записывается "
                        + "в журнал как вектор F00…F19 + PEN и сериализованный protobuf. «Все 1» — "
                        + "контролируемое восстановление, а не считанная заводская конфигурация."));

        return scroll(body);
    }

    private View singleCommand(String label, int color, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(3), 0, dp(8));
        row.addView(commandButton(label, color, action), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return row;
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

    private View commandRow(int color, String[] labels, Runnable[] actions) {
        if (labels.length != actions.length || labels.length == 0) {
            throw new IllegalArgumentException("labels/actions");
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(7));
        for (int index = 0; index < labels.length; index++) {
            Button button = commandButton(labels[index], color, actions[index]);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (index > 0) params.leftMargin = dp(4);
            if (index + 1 < labels.length) params.rightMargin = dp(4);
            row.addView(button, params);
        }
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

    private void previousHeldProbe() {
        heldProbeIndex = (heldProbeIndex + 19) % 20;
        updateHeldProbe();
    }

    private void nextHeldProbe() {
        heldProbeIndex = (heldProbeIndex + 1) % 20;
        updateHeldProbe();
    }

    private void updateHeldProbe() {
        heldProbeIndexView.setText(String.format(Locale.ROOT, "F%02d", heldProbeIndex));
    }

    private void updateVisualIndex() {
        visualIndexView.setText(String.format(Locale.ROOT, "F%02d", visualIndex));
    }

    private void previousVisualPen() {
        visualPen = visualPen <= HudVisualProbePlan.MIN_PROFILE_PEN
                ? HudVisualProbePlan.MAX_PROFILE_PEN : visualPen - 1;
        updateVisualPen();
    }

    private void nextVisualPen() {
        visualPen = visualPen >= HudVisualProbePlan.MAX_PROFILE_PEN
                ? HudVisualProbePlan.MIN_PROFILE_PEN : visualPen + 1;
        updateVisualPen();
    }

    private void updateVisualPen() {
        visualPenView.setText(String.format(Locale.ROOT, "PEN %d", visualPen));
    }

    private void setCommandsEnabled(boolean enabled) {
        commandChannelConnected = enabled;
        for (Button button : commandButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.42f);
        }
        updateFallbackEnableButton();
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
