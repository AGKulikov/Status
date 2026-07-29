package dezz.status.hudlab;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dezz.status.hudlab.HudLabController;
import dezz.status.hudlab.HudPrivilegedCommandRunner;
import dezz.status.hudlab.HudQnxTimeGapInstaller;
import dezz.status.hudlab.HudSystemDumpExporter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/* loaded from: classes4.dex */
public final class HudLabActivity extends Activity implements HudLabController.Listener {
    private static final int REQUEST_STORAGE = 401;
    private ClusterSignalSnapshot clusterProbeBaseline;
    private Set<String> clusterProbeBaselineLayers;
    private boolean clusterProbeAfterScheduled;
    private boolean clusterExactTraceActive;
    private int clusterProbeGeneration;
    private boolean clusterProbeLaunchStarted;
    private String clusterProbeLogcatStart;
    private ClusterProbePresentation clusterProbePresentation;
    private BroadcastReceiver clusterProbeReceiver;
    private boolean clusterProbeRunning;
    private TextView clusterProbeStatusView;
    private ClusterNavigationTransfer clusterNavigationTransfer;
    private TextView clusterNavigationStatusView;
    private TextView connectionBadge;
    private HudLabController controller;
    private TextView displayExperimentStatusView;
    private boolean displayStackCommandRunning;
    private Button exportButton;
    private TextView exportStatusView;
    private int heldProbeIndex;
    private TextView heldProbeIndexView;
    private TextView lastCommandView;
    private TextView logView;
    private HudPrivilegedCommandRunner privilegedCommands;
    private int profileSearchMode;
    private TextView profileSearchModeView;
    private TextView profileSearchStatusView;
    private HudQnxTimeGapInstaller qnxInstaller;
    private boolean qnxOperationRunning;
    private TextView qnxPatchStatusView;
    private TextView snapshotView;
    private int visualIndex;
    private TextView visualIndexView;
    private TextView visualPenView;
    private static final int f7BG = Color.rgb(9, 12, 18);
    private static final int CARD = Color.rgb(19, 25, 36);
    private static final int CARD_BORDER = Color.rgb(45, 58, 78);
    private static final int TEXT = Color.rgb(236, 241, 249);
    private static final int MUTED = Color.rgb(155, 169, 190);
    private static final int BLUE = Color.rgb(34, 122, 222);
    private static final int GREEN = Color.rgb(22, 139, 83);
    private static final int RED = Color.rgb(174, 55, 55);
    private static final int AMBER = Color.rgb(176, 116, 28);
    private int clusterProbeDisplayId = -1;
    private final Handler clusterProbeHandler = new Handler(Looper.getMainLooper());
    private final List<String> clusterProbeTrace = new ArrayList();
    private final List<Button> commandButtons = new ArrayList();
    private final List<Button> qnxButtons = new ArrayList();
    private final List<Button> tabButtons = new ArrayList();
    private final List<View> tabPages = new ArrayList();
    private int visualPen = 1;
    private String fullStatus = "";
    private String lastDumpPath = "";

    private void addTabButton(LinearLayout linearLayout, String str, final int i) {
        Button button = button(str, CARD_BORDER, true);
        button.setTextSize(12.0f);
        button.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$addTabButton$3(i, view);
            }
        });
        this.tabButtons.add(button);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, m3dp(39), 1.0f);
        if (i > 0) {
            layoutParams.leftMargin = m3dp(5);
        }
        linearLayout.addView(button, layoutParams);
    }

    private void addTabPage(FrameLayout frameLayout, View view) {
        view.setVisibility(8);
        this.tabPages.add(view);
        frameLayout.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    public void applyRawProfileTransferMode() {
        HudLabController hudLabController = this.controller;
        if (hudLabController != null) {
            hudLabController.setRawProfileTransferMinusOne();
        }
    }

    private View buildActivationTab() {
        LinearLayout linearLayoutColumnBody = columnBody();
        linearLayoutColumnBody.addView(sectionTitle("Полное включение HUD и режимы — не искомое скрытие"));
        linearLayoutColumnBody.addView(note("Эти команды сохранены только для диагностики и восстановления. OFF здесь может погасить HUD целиком; машинку и скорость отдельно они, как уже подтверждено тестом, не скрывают."));
        linearLayoutColumnBody.addView(label("AdaptAPI SETTING_FUNC_HUD_ACTIVE"));
        int i = RED;
        Runnable runnable = new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.2
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$92();
            }
        };
        int i2 = GREEN;
        linearLayoutColumnBody.addView(commandPair("OFF", i, runnable, "ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.3
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$93();
            }
        }));
        linearLayoutColumnBody.addView(label("Прямой VFHUD CB_VF_HUD_ActvReq"));
        linearLayoutColumnBody.addView(commandPair("OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.4
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$94();
            }
        }, "ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.5
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$95();
            }
        }));
        linearLayoutColumnBody.addView(label("Прямой DIM HudDispActvReq (signal 30788)"));
        linearLayoutColumnBody.addView(commandPair("OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.6
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$96();
            }
        }, "ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.7
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$97();
            }
        }));
        linearLayoutColumnBody.addView(label("AR через AdaptAPI"));
        linearLayoutColumnBody.addView(commandPair("AR OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.8
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$98();
            }
        }, "AR ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.9
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$99();
            }
        }));
        linearLayoutColumnBody.addView(label("AR напрямую через VFHUD"));
        linearLayoutColumnBody.addView(commandPair("AR OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.10
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$100();
            }
        }, "AR ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.11
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$101();
            }
        }));
        linearLayoutColumnBody.addView(label("Все три канала + AR одной командой"));
        linearLayoutColumnBody.addView(commandPair("ВСЁ OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.12
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$102();
            }
        }, "ВСЁ ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.13
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$103();
            }
        }));
        linearLayoutColumnBody.addView(note("«ВСЁ ON» — быстрый возврат штатных запросов после эксперимента. Публикацию нашей панели этот стенд не запускает."));
        linearLayoutColumnBody.addView(label("VFHUD CB33267 · только navigation flag false/true"));
        linearLayoutColumnBody.addView(commandPair("0 · FALSE", AMBER, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.14
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$104();
            }
        }, "1 · TRUE", BLUE, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.15
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildActivationTab$105();
            }
        }));
        linearLayoutColumnBody.addView(note("Это не прямой четырёхпозиционный mode setter. Native-модуль отличает только 1 от всех остальных значений и затем сам вычисляет итоговый режим по состояниям HUD/AR/ADAS."));
        linearLayoutColumnBody.addView(label("Прямой DIM HudDispModSetgReq · реальный активный PEN"));
        linearLayoutColumnBody.addView(modeGrid(true));
        linearLayoutColumnBody.addView(note("Режимы: 0 IntellGuide, 1 IntellDrive, 2 AR, 3 Simple. В 0.18 здесь ошибочно был жёстко задан PEN=1; теперь используется ProfPenSts1 с подтверждённым fallback на PA33845."));
        return scroll(linearLayoutColumnBody);
    }

    private View buildElementsTab() {
        LinearLayout linearLayoutColumnBody = columnBody();
        linearLayoutColumnBody.addView(sectionTitle("Отдельное скрытие штатного содержимого HUD"));
        linearLayoutColumnBody.addView(sectionTitle("Штатный AR-флаг активного профиля · старое меню HUD"));
        linearLayoutColumnBody.addView(note("В старых ECARX Settings переключатель «AR режим» записывал функцию 654443008 в поле vfhudbyte0 полного профиля автомобиля. Здесь сохраняются точные raw-байты PA33873, меняется только protobuf-поле 111 и выполняется подтверждённое чтение обратно. Публичный JSON не используется: он не содержит 65 скрытых vendor-полей."));
        int i = AMBER;
        linearLayoutColumnBody.addView(commandRow(i, new String[]{"ПРОЧИТАТЬ AR", "AR ON · 1", "AR OFF · 0", "ТОЧНЫЙ ОТКАТ"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.16
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$75();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.17
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$76();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.18
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$77();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.19
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$78();
            }
        }}));
        linearLayoutColumnBody.addView(note("Сначала проверьте AR ON · 1: именно это значение использовал старый штатный переключатель. Чтение выполняется только по кнопке и больше не вызывается в фоновом обновлении статуса. Текущий profile/value виден во вкладке «Статус». Откат возвращает исходное значение AR в свежий raw-профиль того же активного Profile ID, сохраняя последующие изменения других настроек."));
        linearLayoutColumnBody.addView(sectionTitle("Категории Settings API · на этой прошивке NOT AVAILABLE"));
        linearLayoutColumnBody.addView(note("Константы существуют, но HUD.buildFunctions их не регистрирует. Ваш прошлый runtime уже вернул support=notavailable, value=255, allowed=null. Эти кнопки сохранены только как повторяемая диагностика; визуального эффекта от них на этой прошивке не ожидается. Результат каждой пробы теперь постоянно виден над вкладками."));
        linearLayoutColumnBody.addView(label("Машинка и дорожное окружение · DISPLAY_DRIVE_ENVIRONMENT"));
        int i2 = RED;
        Runnable runnable = new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.20
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$79();
            }
        };
        int i3 = GREEN;
        linearLayoutColumnBody.addView(commandPair("ПРОБА OFF", i2, runnable, "ПРОБА ON", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.21
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$80();
            }
        }));
        linearLayoutColumnBody.addView(label("Скорость и информация безопасности · DISPLAY_SAFETY"));
        linearLayoutColumnBody.addView(commandPair("ПРОБА OFF", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.22
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$81();
            }
        }, "ПРОБА ON", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.23
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$82();
            }
        }));
        linearLayoutColumnBody.addView(label("Обе искомые категории одной командой"));
        linearLayoutColumnBody.addView(commandPair("ПРОБА ОБЕ OFF", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.24
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$83();
            }
        }, "ПРОБА ОБЕ ON", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.25
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$84();
            }
        }));
        linearLayoutColumnBody.addView(label("Остальные отдельные категории"));
        Runnable runnable2 = new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.26
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$85();
            }
        };
        int i4 = BLUE;
        linearLayoutColumnBody.addView(commandPair("МЕДИА OFF", i, runnable2, "МЕДИА ON", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.27
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$86();
            }
        }));
        linearLayoutColumnBody.addView(commandPair("НАВИГАЦИЯ OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.28
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$87();
            }
        }, "НАВИГАЦИЯ ON", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.29
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$88();
            }
        }));
        linearLayoutColumnBody.addView(commandPair("ТЕЛЕФОН OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.30
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$89();
            }
        }, "ТЕЛЕФОН ON", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.31
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$90();
            }
        }));
        linearLayoutColumnBody.addView(singleCommand("ВОССТАНОВИТЬ ВСЕ ПЯТЬ КАТЕГОРИЙ", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.32
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildElementsTab$91();
            }
        }));
        linearLayoutColumnBody.addView(note("Если SDK ответит notavailable/accepted=false, команда на этой прошивке не связана с DIM. Это диагностический результат, а не успешное скрытие."));
        return scroll(linearLayoutColumnBody);
    }

    private View buildHeader() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        int i = CARD_BORDER;
        Button button = button("← Закрыть", i, false);
        button.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.33
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$buildHeader$0(view);
            }
        });
        linearLayout.addView(button, fixedButton(m3dp(130)));
        TextView textViewText = text("HUD Lab 0.30", 23, TEXT, true);
        textViewText.setPadding(m3dp(16), 0, m3dp(18), 0);
        linearLayout.addView(textViewText);
        TextView textViewText2 = text("ECARX: ОЖИДАНИЕ", 15, Color.rgb(255, 192, 92), true);
        this.connectionBadge = textViewText2;
        linearLayout.addView(textViewText2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button button2 = button("Обновить", BLUE, false);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.34
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$buildHeader$1(view);
            }
        });
        linearLayout.addView(button2, fixedButton(m3dp(132)));
        Button button3 = button("Копировать статус", i, false);
        button3.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.35
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$buildHeader$2(view);
            }
        });
        LinearLayout.LayoutParams layoutParamsFixedButton = fixedButton(m3dp(190));
        layoutParamsFixedButton.leftMargin = m3dp(8);
        linearLayout.addView(button3, layoutParamsFixedButton);
        return linearLayout;
    }

    private View buildMaskTab() {
        LinearLayout linearLayoutColumnBody = columnBody();
        linearLayoutColumnBody.addView(sectionTitle("Низкоуровневая visual mask · профили PEN 0–13"));
        linearLayoutColumnBody.addView(note("PEN в ECARX — идентификатор профиля, а не Android-дисплей. Дамп подтверждает профили 0…11, CarSharing 12 и Default 13. Значения 14 и 15 стенд не отправляет: их смысл в протоколе не доказан."));
        linearLayoutColumnBody.addView(label("HUD VisFctSetgReq: 20 функций"));
        int i = RED;
        Runnable runnable = new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.36
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildMaskTab$106();
            }
        };
        int i2 = GREEN;
        linearLayoutColumnBody.addView(commandPair("Все 0", i, runnable, "Все 1", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.37
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildMaskTab$107();
            }
        }));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        int i3 = CARD_BORDER;
        linearLayout.addView(commandButton("− индекс", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.38
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.previousVisualIndex();
            }
        }), new LinearLayout.LayoutParams(0, m3dp(46), 1.0f));
        int i4 = TEXT;
        TextView textViewText = text("F00", 18, i4, true);
        this.visualIndexView = textViewText;
        textViewText.setGravity(17);
        linearLayout.addView(this.visualIndexView, new LinearLayout.LayoutParams(m3dp(74), m3dp(46)));
        linearLayout.addView(commandButton("+ индекс", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.39
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.nextVisualIndex();
            }
        }), new LinearLayout.LayoutParams(0, m3dp(46), 1.0f));
        linearLayoutColumnBody.addView(linearLayout);
        linearLayoutColumnBody.addView(commandPair("Выбранную OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.40
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildMaskTab$108();
            }
        }, "Выбранную ON", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.41
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildMaskTab$109();
            }
        }));
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.addView(commandButton("− PEN", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.42
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.previousVisualPen();
            }
        }), new LinearLayout.LayoutParams(0, m3dp(46), 1.0f));
        TextView textViewText2 = text("PEN 1", 17, i4, true);
        this.visualPenView = textViewText2;
        textViewText2.setGravity(17);
        linearLayout2.addView(this.visualPenView, new LinearLayout.LayoutParams(m3dp(150), m3dp(46)));
        linearLayout2.addView(commandButton("+ PEN", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.43
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.nextVisualPen();
            }
        }), new LinearLayout.LayoutParams(0, m3dp(46), 1.0f));
        linearLayoutColumnBody.addView(linearLayout2);
        linearLayoutColumnBody.addView(singleCommand("Применить выбранный PEN", BLUE, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.44
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildMaskTab$110();
            }
        }));
        linearLayoutColumnBody.addView(note("SDK не даёт getter маски signal 30816: каждая отправка полностью записывается в журнал как вектор F00…F19 + PEN и сериализованный protobuf. «Все 1» — контролируемое восстановление, а не считанная заводская конфигурация."));
        return scroll(linearLayoutColumnBody);
    }

    private View buildInstrumentClusterTab() {
        LinearLayout body = columnBody();
        body.addView(sectionTitle("Точный вывод собственного экрана HUD Lab на приборку"));
        body.addView(note("Навигатор здесь вообще не запускается. Кнопка создаёт отдельную зелёную Activity HUD Lab с крупной надписью и отправляет её на Android displayId=2. Параметры взяты непосредственно из mNavi 2.0: ACTION_MAIN, NEW_TASK, setLaunchDisplayId(2), SplitScreenShownPosition=0 и windowingMode=5. Перед запуском воспроизводится правильный импульс NaviMode 3→1/[0]→3/[1]."));
        body.addView(note("Для полного совпадения с mNavi включите один раз службу «HUD Lab · запуск на приборке» в специальных возможностях. Если она не включена, тот же Intent и тот же ActivityOptions отправляются из открытой Activity и этот fallback будет явно отмечен в журнале."));
        body.addView(note("Чтобы найти источник нижних «крыльев», журнал сравнивает ДО / ВО ВРЕМЯ / ПОСЛЕ: ECARX resource/priority и DIM-сигналы, фактический task/window displayId, размеры окна, системные insets, ClusterActivityState, SurfaceFlinger-слои и logcat. Каждое изменение относительно состояния ДО помечается ★. Полная трасса одновременно сохраняется в отдельный TXT-файл; его путь появится первой строкой. Как только визуально увидите крылья, сразу нажмите отдельную кнопку ручной метки — в этот момент будет снят дополнительный полный срез. Экран держится 30 секунд либо закрывается третьей кнопкой."));
        body.addView(commandRow(GREEN, new String[]{
                "1 · СПЕЦВОЗМОЖНОСТИ",
                "2 · ЭКРАН HUD LAB → ID 2",
                "ЗАКРЫТЬ / ВЕРНУТЬ DIM"
        }, new Runnable[]{
                new Runnable() {
                    @Override
                    public void run() {
                        openClusterAccessibilitySettings();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        startExactClusterTextTest();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        stopExactClusterTextTest();
                    }
                }
        }));
        body.addView(singleCommand(
                "★★ МЕТКА: НИЖНИЕ КРЫЛЬЯ ПОЯВИЛИСЬ",
                AMBER,
                new Runnable() {
                    @Override
                    public void run() {
                        markClusterWingsVisible();
                    }
                }));
        TextView transferTrace = text(
                "Тест собственного экрана ещё не запускался.",
                13,
                Color.rgb(255, 214, 125),
                false);
        this.clusterNavigationStatusView = transferTrace;
        transferTrace.setTypeface(Typeface.MONOSPACE);
        transferTrace.setTextIsSelectable(true);
        transferTrace.setPadding(m3dp(8), m3dp(5), m3dp(8), m3dp(12));
        body.addView(transferTrace);
        body.addView(sectionTitle("Неподтверждённые команды оформления приборки"));
        body.addView(note("По вашему тесту A/B/C/D/F/G нижние блоки не убрали; DAY/NIGHT изменяет только светлое/тёмное оформление. Команды оставлены для диагностики и отправляются один раз. Они не обозначены как готовое решение."));
        body.addView(label("A · Driver HMI user interface · signal 30807 · default + variants 1…9"));
        body.addView(commandRow(AMBER, new String[]{"0 DEFAULT", "1 VARIANT", "2 VARIANT", "3 VARIANT", "4 VARIANT"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda31
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$4();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda32
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$5();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda34
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$6();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda35
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$7();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda36
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$8();
            }
        }}));
        body.addView(commandRow(AMBER, new String[]{"5 VARIANT", "6 VARIANT", "7 VARIANT", "8 VARIANT", "9 VARIANT"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda37
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$9();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda47
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$10();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda48
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$11();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda49
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$12();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda50
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$13();
            }
        }}));
        body.addView(label("B · Driver HMI background · signal 30805 · варианты 0…5"));
        body.addView(commandRow(AMBER, new String[]{"BG 0", "BG 1", "BG 2", "BG 3", "BG 4", "BG 5"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda51
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$14();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$15();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$16();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$17();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$18();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$19();
            }
        }}));
        body.addView(label("C · Driver display template · signal 30803 / feedback 30873"));
        body.addView(commandRow(BLUE, new String[]{"0 COMFORT", "1 ECO", "2 DYNAMIC"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$20();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$21();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda9
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$22();
            }
        }}));
        body.addView(label("D · Multimedia information layer · signal 30792"));
        body.addView(commandRow(AMBER, new String[]{"0 INFO OFF", "1 INFO ON", "2 PARTIAL", "3 WELCOME"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$23();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda12
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$24();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda13
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$25();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda14
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$26();
            }
        }}));
        body.addView(label("E · HMI theme renderer · signal 30787"));
        body.addView(commandRow(BLUE, new String[]{"0 NIGHT", "1 DAY", "2 AUTO", "3 RESERVED"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$27();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda16
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$28();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda17
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$29();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda19
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$30();
            }
        }}));
        body.addView(label("F · Individual DIM theme · signal 30785"));
        body.addView(commandPair("0 INDIVIDUAL OFF", RED, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda20
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$31();
            }
        }, "1 INDIVIDUAL ON", GREEN, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda21
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$32();
            }
        }));
        body.addView(label("G · Навигационный слой приборки · штатный DimMenuInteraction"));
        body.addView(commandRow(GREEN, new String[]{"1 OFF", "2 SIMPLIFY", "3 FULL", "4 AR"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda23
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$33();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda24
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$34();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda25
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$35();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda26
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$36();
            }
        }}));
        body.addView(note("Это не HUD mode 0…3. Кнопки вызывают штатный switchNaviMode(1…4), который сохраняет NaviMode и отправляет DIM protocol opcode 13. Исходное значение запоминается перед первой успешной сменой."));
        body.addView(singleCommand("ВОССТАНОВИТЬ ИСХОДНЫЙ NAVI MODE", GREEN, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda27
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$37();
            }
        }));
        body.addView(sectionTitle("Откат и сохранение"));
        body.addView(singleCommand("ПЕРЕЗАГРУЗИТЬ АКТИВНЫЙ ПРОФИЛЬ", GREEN, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda28
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$38();
            }
        }));
        body.addView(note("Перезагрузка активного профиля возвращает сохранённые штатные значения transient-команд A/B/C. Для Navi Mode используйте точный откат выше."));
        body.addView(singleCommand("СОХРАНИТЬ ТЕКУЩИЕ ПРОФИЛЬНЫЕ НАСТРОЙКИ (29892 0→1)", AMBER, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda29
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildInstrumentClusterTab$39();
            }
        }));
        body.addView(note("Сохранение выполняется только по этой отдельной кнопке — после визуальной проверки нужной компоновки на стоящей машине."));
        return scroll(body);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$0() {
        startClusterProbe(findLikelyClusterDisplayId());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$1() {
        startClusterProbe(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$2() {
        startClusterProbe(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$3() {
        stopClusterProbe(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$4() {
        this.controller.setDriverHmiInterface(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$5() {
        this.controller.setDriverHmiInterface(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$6() {
        this.controller.setDriverHmiInterface(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$7() {
        this.controller.setDriverHmiInterface(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$8() {
        this.controller.setDriverHmiInterface(4);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$9() {
        this.controller.setDriverHmiInterface(5);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$10() {
        this.controller.setDriverHmiInterface(6);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$11() {
        this.controller.setDriverHmiInterface(7);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$12() {
        this.controller.setDriverHmiInterface(8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$13() {
        this.controller.setDriverHmiInterface(9);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$14() {
        this.controller.setDriverHmiBackground(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$15() {
        this.controller.setDriverHmiBackground(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$16() {
        this.controller.setDriverHmiBackground(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$17() {
        this.controller.setDriverHmiBackground(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$18() {
        this.controller.setDriverHmiBackground(4);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$19() {
        this.controller.setDriverHmiBackground(5);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$20() {
        this.controller.setDriverDisplayTheme(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$21() {
        this.controller.setDriverDisplayTheme(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$22() {
        this.controller.setDriverDisplayTheme(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$23() {
        this.controller.setMultimediaInformationMode(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$24() {
        this.controller.setMultimediaInformationMode(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$25() {
        this.controller.setMultimediaInformationMode(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$26() {
        this.controller.setMultimediaInformationMode(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$27() {
        this.controller.setHmiThemeMode(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$28() {
        this.controller.setHmiThemeMode(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$29() {
        this.controller.setHmiThemeMode(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$30() {
        this.controller.setHmiThemeMode(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$31() {
        this.controller.setIndividualTheme(false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$32() {
        this.controller.setIndividualTheme(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$33() {
        this.controller.setDimNavigationMode(1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$34() {
        this.controller.setDimNavigationMode(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$35() {
        this.controller.setDimNavigationMode(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$36() {
        this.controller.setDimNavigationMode(4);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$37() {
        this.controller.restoreDimNavigationMode();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$38() {
        this.controller.reloadActiveProfile();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$buildInstrumentClusterTab$39() {
        this.controller.persistCurrentProfileSettings();
    }

    private View buildNewPathsTab() {
        LinearLayout linearLayoutColumnBody = columnBody();
        linearLayoutColumnBody.addView(sectionTitle("Универсальный динамический патч · управление"));
        linearLayoutColumnBody.addView(note("Работает после установки варианта «УНИВЕРСАЛЬНЫЙ ДИНАМИЧЕСКИЙ» из компьютерного скрипта. Каждая кнопка отправляет ровно одну команду CB33267: MODE 0–3 фиксирует режим и не даёт LCA вернуть стандартный; AUTO возвращает штатное переключение."));
        linearLayoutColumnBody.addView(commandRow(BLUE, new String[]{"MODE 0", "MODE 1", "MODE 2 AR", "MODE 3"}, new Runnable[]{new HudDynamicModeRunnable(this, 0), new HudDynamicModeRunnable(this, 1), new HudDynamicModeRunnable(this, 2), new HudDynamicModeRunnable(this, 3)}));
        linearLayoutColumnBody.addView(singleCommand("AUTO · ШТАТНОЕ УПРАВЛЕНИЕ", GREEN, new HudDynamicModeRunnable(this, -1)));
        linearLayoutColumnBody.addView(note("В HUD Lab нет автозапуска, таймеров и повторов. Удаление APK не удаляет нативный патч. После перезапуска службы или магнитолы динамический выбор сбрасывается в AUTO."));
        linearLayoutColumnBody.addView(sectionTitle("Точное решение · нативный QNX/Kanzi TimeGap"));
        linearLayoutColumnBody.addView(note("Машинка находится не в Android displayId 2. Её рисует отдельное окно QNX hud-hmi из узла ADAS/TimeGap/TimeGapIcon поверх Android-композиции. Патч заменяет только шесть картинок DistanceDetection White/Purple 1–3 на прозрачные, сохраняя их размеры. Скорость, навигация и предупреждения не изменяются."));
        int i = AMBER;
        linearLayoutColumnBody.addView(qnxCommandRow(i, new String[]{"ПРОВЕРИТЬ QNX", "УСТАНОВИТЬ ПАТЧ", "ОТКАТИТЬ ПАТЧ"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.45
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$9();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.46
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.confirmQnxInstall();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.47
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.confirmQnxRestore();
            }
        }}));
        TextView textViewText = text("QNX: статус ещё не проверялся. Запись разрешена только для точной заводской версии hud.kzb; перед заменой создаётся проверяемый backup.", 14, Color.rgb(255, 214, 125), true);
        this.qnxPatchStatusView = textViewText;
        textViewText.setTypeface(Typeface.MONOSPACE);
        this.qnxPatchStatusView.setTextIsSelectable(true);
        this.qnxPatchStatusView.setPadding(m3dp(8), m3dp(5), m3dp(8), m3dp(12));
        linearLayoutColumnBody.addView(this.qnxPatchStatusView);
        linearLayoutColumnBody.addView(note("Установка и откат выполняются только по нажатию, временно переводят /apps в режим записи, обязательно возвращают read-only и перезагружают QNX. Проводить только на стоящей машине."));
        linearLayoutColumnBody.addView(sectionTitle("Способ A · ProfileTransfer + DIM + SAVE"));
        linearLayoutColumnBody.addView(note("Каждая кнопка отправляет выбранный режим ровно один раз: режим 0–3 в профиль CB33278, затем тот же режим напрямую в DIM через signal30814 с реальным активным PEN и, последним шагом, сохранение signal29892. Никаких таймеров, автозапуска, контроля скорости и повторов после закрытия нет."));
        int i2 = BLUE;
        linearLayoutColumnBody.addView(commandRow(i2, new String[]{"MODE 0", "MODE 1", "MODE 2", "MODE 3"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.48
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$10();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.49
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$11();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.50
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$12();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.51
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$13();
            }
        }}));
        linearLayoutColumnBody.addView(note("Используется подтверждённый машиной профиль 13, когда ProfPenSts1 возвращает служебное −1. Результат всех трёх стадий и прочитанный PA33937 сразу появятся в жёлтой строке над вкладками."));
        linearLayoutColumnBody.addView(sectionTitle("Способ B · UserProfile field 124"));
        linearLayoutColumnBody.addView(note("Это отдельный постоянный профильный путь CB33264/PA33873. В ECARX CAR_FUNC_HUD_MODE (251660288) прямо сопоставлен полю profiletransferbyte3, protobuf tag 124. Стенд заменяет только varint этого поля в полном raw-профиле, проверяет остальные байты и читает field124 обратно. Перед первой записью сохраняется исходное значение."));
        linearLayoutColumnBody.addView(commandRow(i, new String[]{"FIELD 0", "FIELD 1", "FIELD 2", "FIELD 3"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.52
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$14();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.53
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$15();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.54
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$16();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.55
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$17();
            }
        }}));
        Runnable runnable = new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.56
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$18();
            }
        };
        int i3 = GREEN;
        linearLayoutColumnBody.addView(commandPair("ПРОЧИТАТЬ FIELD 124", i2, runnable, "ТОЧНЫЙ ОТКАТ FIELD 124", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.57
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$19();
            }
        }));
        linearLayoutColumnBody.addView(sectionTitle("Способ C · прямой DIM активного профиля"));
        linearLayoutColumnBody.addView(note("Одна команда signal30814 / VHAL 0x2170785E с payload [mode, active PEN]. Здесь нет ProfileTransfer, сохранения 29892 и фонового повторения, поэтому можно отдельно проверить именно реакцию DIM."));
        linearLayoutColumnBody.addView(commandRow(i2, new String[]{"DIM 0", "DIM 1", "DIM 2", "DIM 3"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.58
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$20();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.59
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$21();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.60
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$22();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.61
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$23();
            }
        }}));
        linearLayoutColumnBody.addView(sectionTitle("Способ D · visual mask активного профиля"));
        linearLayoutColumnBody.addView(note("Нижний ECARX-сигнал 30816 содержит 20 отдельных HUD-флагов. Это пока исследовательский путь: используйте ручной Fxx-тест ниже, чтобы найти флаг именно белой машинки, не выключая скорость целиком."));
        linearLayoutColumnBody.addView(sectionTitle("Способ E · физический HUD: stack 2 → stack 22"));
        linearLayoutColumnBody.addView(note("Это точная проверка вашей гипотезы. Штатный com.ecarx.hud продолжает публиковать окна в Android displayId/layerStack 2, но физический local:2 на 12 секунд переключается на layerStack 22. На stack 22 создаётся зелёная метка «HUD LAB · STACK 22». Затем в finally выполняется один обязательный возврат на stack 2. Таймеров команд, автоповтора и запуска после перезагрузки нет."));
        int i4 = RED;
        linearLayoutColumnBody.addView(singleStandaloneCommand("ТЕСТ STACK 22 · 12 СЕКУНД", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.62
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.runDisplayStack22Test();
            }
        }));
        linearLayoutColumnBody.addView(singleStandaloneCommand("АВАРИЙНО ВЕРНУТЬ STACK 2", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.63
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.restoreDisplayStack2();
            }
        }));
        linearLayoutColumnBody.addView(note("Тест выполняется под локальным shell-UID через ADB 5555/7777 или Telnet 23, потому что SurfaceFlinger закономерно запрещает эту операцию обычному APK. Root не используется. Проводить только на стоящей машине."));
        linearLayoutColumnBody.addView(sectionTitle("Способ F · занять штатный Android displayId 2"));
        linearLayoutColumnBody.addView(note("Штатный BootBroadcastReceiver запускает HomeActivity ровно через setLaunchDisplayId(2), windowingMode=5, splitPosition=1. Здесь HUD Lab запускает своё непрозрачное Activity с теми же параметрами. Если белая машинка является окном com.ecarx.hud, чёрный Activity должен закрыть её композиционно; если она останется, источник находится ниже Android окон — в DIM/HWC/отдельном аппаратном слое."));
        linearLayoutColumnBody.addView(commandRowStandalone(i2, new String[]{"МЕТКА НА ID 2", "ЧЁРНЫЙ COVER ID 2", "УБРАТЬ COVER"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.64
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.startDisplay2Marker();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.65
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.startDisplay2Black();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.66
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.stopDisplay2Cover();
            }
        }}));
        TextView textViewText2 = text(displayInventory(), 13, Color.rgb(255, 214, 125), true);
        this.displayExperimentStatusView = textViewText2;
        textViewText2.setTypeface(Typeface.MONOSPACE);
        this.displayExperimentStatusView.setTextIsSelectable(true);
        this.displayExperimentStatusView.setPadding(0, m3dp(6), 0, m3dp(12));
        linearLayoutColumnBody.addView(this.displayExperimentStatusView);
        linearLayoutColumnBody.addView(sectionTitle("Точный поиск внутри подтверждённого способа 01"));
        linearLayoutColumnBody.addView(note("Вы подтвердили, что ProfileTransfer действительно меняет штатный HUD: в отдельных режимах исчезает машинка, а скорость перемещается. Выберите ниже режим, в котором машинки уже нет, затем запустите поэлементный перебор. Каждая комбинация держится на HUD 3,6 секунды."));
        linearLayoutColumnBody.addView(label("Режим 01 · нажатие сразу отправляет CB33278"));
        linearLayoutColumnBody.addView(commandRow(i2, new String[]{"0 GUIDE", "1 DRIVE", "2 AR", "3 SIMPLE"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.67
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$24();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.68
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$25();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.69
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$26();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.70
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$27();
            }
        }}));
        int i5 = TEXT;
        TextView textViewText3 = text("Выбран режим поиска: 0 GUIDE", 16, i5, true);
        this.profileSearchModeView = textViewText3;
        textViewText3.setPadding(m3dp(8), m3dp(5), m3dp(8), m3dp(8));
        linearLayoutColumnBody.addView(this.profileSearchModeView);
        linearLayoutColumnBody.addView(label("Ручной фиксированный тест · активный профиль"));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        int i6 = CARD_BORDER;
        linearLayout.addView(commandButton("− F", i6, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.71
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.previousHeldProbe();
            }
        }), new LinearLayout.LayoutParams(0, m3dp(46), 1.0f));
        TextView textViewText4 = text("F00", 18, i5, true);
        this.heldProbeIndexView = textViewText4;
        textViewText4.setGravity(17);
        linearLayout.addView(this.heldProbeIndexView, new LinearLayout.LayoutParams(m3dp(86), m3dp(46)));
        linearLayout.addView(commandButton("+ F", i6, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.72
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.nextHeldProbe();
            }
        }), new LinearLayout.LayoutParams(0, m3dp(46), 1.0f));
        linearLayoutColumnBody.addView(linearLayout);
        linearLayoutColumnBody.addView(commandPair("ВСЕ 1 → ВЫБРАННАЯ OFF", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.73
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$28();
            }
        }, "ВОССТАНОВИТЬ ВСЕ 1", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.74
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$29();
            }
        }));
        linearLayoutColumnBody.addView(note("Ручная проба сначала отправляет полный baseline all=1, затем полный 20-полевой вектор с одним Fxx=0. Результат остаётся до восстановления. В журнал пишется точный protobuf, а не условное «SUCCESS». Если ProfPenSts1 недоступен, версия 0.21 безопасно использует активный профиль PA33845 как PEN (в вашем дампе это профиль 13)."));
        linearLayoutColumnBody.addView(label("Безопасный автоматический проход · активный PEN"));
        linearLayoutColumnBody.addView(singleCommand("ЗАПУСТИТЬ ПОЛНЫЙ ЦИКЛ", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.75
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$30();
            }
        }));
        linearLayoutColumnBody.addView(note("Строгая последовательность: all=0 держится 3,6 с и автоматически снимается; затем all=1; затем F00…F19 по одному становятся 0 при остальных=1. Последний обязательный шаг снова отправляет all=1. При ошибке или закрытии стенд также пытается немедленно восстановить all=1."));
        linearLayoutColumnBody.addView(commandPair("ЗАПИСАТЬ Fxx И ВОССТАНОВИТЬ", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.76
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$31();
            }
        }, "ОСТАНОВИТЬ И ВОССТАНОВИТЬ", i2, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.77
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$32();
            }
        }));
        TextView textViewText5 = text("Поиск 01: не запущен", 15, Color.rgb(255, 214, 125), true);
        this.profileSearchStatusView = textViewText5;
        textViewText5.setTypeface(Typeface.MONOSPACE);
        this.profileSearchStatusView.setPadding(m3dp(8), m3dp(5), m3dp(8), m3dp(12));
        linearLayoutColumnBody.addView(this.profileSearchStatusView);
        linearLayoutColumnBody.addView(note("Как только скорость полностью исчезнет, сразу нажмите зелёную кнопку. Её mode, активный PEN и Fxx будут зафиксированы в журнале, после чего маска автоматически вернётся в all=1. Стенд работает только с подтверждённым vendor framework диапазоном профилей 0…13."));
        linearLayoutColumnBody.addView(sectionTitle("Состояния Navi 4/5/6 — не дополнительные HUD-режимы"));
        linearLayoutColumnBody.addView(note("4 REROUTING, 5 TUNNEL ENTER и 6 TUNNEL END действительно найдены в Navi API, но native-модуль CB33267 проверяет только value==1. Значения 0, 2, 3, 4, 5 и 6 сворачиваются в один false-флаг. Поэтому кнопки 4/5/6 из 0.18 удалены: они создавали видимость новых режимов, но не могли включить отдельное состояние HUD."));
        linearLayoutColumnBody.addView(sectionTitle("Ручная проверка и прежние диагностические пути"));
        linearLayoutColumnBody.addView(label("01 · Откат ProfileTransfer HUD mode · CB33278 / PA33937"));
        linearLayoutColumnBody.addView(singleCommand("ОТКАТИТЬ РЕЖИМ 01", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.78
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$33();
            }
        }));
        linearLayoutColumnBody.addView(singleCommand("−1 RAW → CB33278 (В ОБХОД SDK)", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.79
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.applyRawProfileTransferMode();
            }
        }));
        linearLayoutColumnBody.addView(note("Обычный ECARX SDK принимает здесь только 0…3 и отбрасывает −1 до отправки. Эта отдельная кнопка передаёт −1 напрямую в тот же CB33278 через ECARX property service. Команда разрешается только после сохранения валидного режима для возврата. Если PA33937 не читается, сначала нажмите любой рабочий режим 0…3. Для возврата нажмите «ОТКАТ»."));
        linearLayoutColumnBody.addView(label("02 · CEM HUD mode с реальным активным PEN · signal 30814"));
        linearLayoutColumnBody.addView(commandRow(i2, new String[]{"0 GUIDE", "1 DRIVE", "2 AR", "3 SIMPLE"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.80
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$34();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.81
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$35();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.82
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$36();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.83
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$37();
            }
        }}));
        linearLayoutColumnBody.addView(label("03 · Visual mask с реальным активным PEN · signal 30816"));
        linearLayoutColumnBody.addView(commandPair("ВСЕ F00–F19 = 0", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.84
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$38();
            }
        }, "ВСЕ F00–F19 = 1", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.85
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$39();
            }
        }));
        linearLayoutColumnBody.addView(note("Это временная диагностическая маска, а не доказанный постоянный способ. Словарь F00–F19 в прошивке отсутствует, поэтому all=0 может скрыть в том числе штатные предупреждения. При закрытии стенда любая неединичная маска автоматически возвращается в all=1."));
        linearLayoutColumnBody.addView(label("04 · Driver HMI background · signal 30805 · значения 0…5"));
        linearLayoutColumnBody.addView(commandRow(i, new String[]{"BG 0", "BG 1", "BG 2", "BG 3", "BG 4", "BG 5"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.86
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$40();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.87
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$41();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.88
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$42();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.89
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$43();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.90
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$44();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.91
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$45();
            }
        }}));
        linearLayoutColumnBody.addView(label("05 · Driver HMI user interface · signal 30807 · default/variants 1…9"));
        linearLayoutColumnBody.addView(commandRow(i, new String[]{"UI 0", "UI 1", "UI 2", "UI 3", "UI 4"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.92
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$46();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.93
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$47();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.94
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$48();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.95
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$49();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.96
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$50();
            }
        }}));
        linearLayoutColumnBody.addView(commandRow(i, new String[]{"UI 5", "UI 6", "UI 7", "UI 8", "UI 9"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.97
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$51();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.98
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$52();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.99
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$53();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.100
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$54();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.101
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$55();
            }
        }}));
        linearLayoutColumnBody.addView(label("06 · DIM information layer · signal 30792"));
        linearLayoutColumnBody.addView(commandRow(i, new String[]{"0 INFO OFF", "1 INFO ON", "2 PARTIAL", "3 WELCOME"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.102
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$56();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.103
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$57();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.104
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$58();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.105
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$59();
            }
        }}));
        linearLayoutColumnBody.addView(label("07 · Individual DIM theme · signal 30785"));
        linearLayoutColumnBody.addView(commandPair("INDIVIDUAL OFF", i4, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.106
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$60();
            }
        }, "INDIVIDUAL ON", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.107
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$61();
            }
        }));
        linearLayoutColumnBody.addView(label("08 · HMI theme renderer · signal 30787"));
        linearLayoutColumnBody.addView(commandRow(i2, new String[]{"0 NIGHT", "1 DAY", "2 AUTO", "3 RESERVED"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.108
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$62();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.109
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$63();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.110
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$64();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.111
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$65();
            }
        }}));
        linearLayoutColumnBody.addView(label("09 · Driver display template · signal 30803 / feedback 30873"));
        linearLayoutColumnBody.addView(commandRow(i2, new String[]{"0 COMFORT", "1 ECO", "2 DYNAMIC"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.112
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$66();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.113
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$67();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.114
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$68();
            }
        }}));
        linearLayoutColumnBody.addView(label("10 · Vehicle model clear · CB33284 / PA33943"));
        linearLayoutColumnBody.addView(commandRow(i4, new String[]{"CLEAR 0", "CLEAR 1", "ТОЧНЫЙ ОТКАТ"}, new Runnable[]{new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.115
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$69();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.116
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$70();
            }
        }, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.117
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$71();
            }
        }}));
        linearLayoutColumnBody.addView(sectionTitle("Откат и сохранение только подтверждённого результата"));
        linearLayoutColumnBody.addView(singleCommand("ПЕРЕЗАГРУЗИТЬ АКТИВНЫЙ ПРОФИЛЬ", i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.118
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$72();
            }
        }));
        linearLayoutColumnBody.addView(note("04/05/09 отправляются transient и сами в память не записываются. «Перезагрузить активный профиль» возвращает сохранённую штатную компоновку. Сохранение найденного варианта выполняйте только после визуального подтверждения на HUD."));
        linearLayoutColumnBody.addView(singleCommand("СОХРАНИТЬ ТЕКУЩИЙ НАЙДЕННЫЙ ВАРИАНТ (29892 0→1)", i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.119
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$buildNewPathsTab$73();
            }
        }));
        linearLayoutColumnBody.addView(note("Сохранение 29892 не используется во время поиска: связь этого общего сигнала с HUD-mask не доказана. Нажимайте его только после повторного визуального подтверждения найденного Fxx."));
        return scroll(linearLayoutColumnBody);
    }

    private View buildStatusTab() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(m3dp(12), m3dp(10), m3dp(12), m3dp(10));
        linearLayout.setBackground(cardDrawable());
        linearLayout.addView(sectionTitle("Живые статусы и обратная связь"));
        TextView textViewText = text("Подключение…", 13, TEXT, false);
        this.snapshotView = textViewText;
        textViewText.setTypeface(Typeface.MONOSPACE);
        this.snapshotView.setTextIsSelectable(true);
        linearLayout.addView(this.snapshotView);
        TextView textViewSectionTitle = sectionTitle("Журнал команд");
        textViewSectionTitle.setPadding(0, m3dp(12), 0, m3dp(6));
        linearLayout.addView(textViewSectionTitle);
        TextView textViewText2 = text("", 12, MUTED, false);
        this.logView = textViewText2;
        textViewText2.setTypeface(Typeface.MONOSPACE);
        this.logView.setTextIsSelectable(true);
        linearLayout.addView(this.logView);
        return scroll(linearLayout);
    }

    private View buildSystemDumpTab() {
        LinearLayout linearLayoutColumnBody = columnBody();
        linearLayoutColumnBody.addView(sectionTitle("Экспорт фактической реализации HUD этой прошивки"));
        linearLayoutColumnBody.addView(note("HUD Lab 0.2 подтвердил: DISPLAY_DRIVE_ENVIRONMENT и DISPLAY_SAFETY присутствуют в SDK, но магнитола отвечает accepted=false. Повторять эти команды больше не нужно."));
        linearLayoutColumnBody.addView(note("Кнопка ниже соберёт в один ZIP установленные системные APK HUD, DIMProtocol, PowerSomeIP, AdaptAPI, OpenAPI, читаемые ECARX/Geely framework-JAR, низкоуровневые IPCP/VHAL/UART/LIN/LVDS/MCU-файлы и журнал HUD Lab. Личные данные приложений не читаются, настройки не меняются."));
        Button button = button("СОБРАТЬ ZIP В DOWNLOAD", BLUE, false);
        this.exportButton = button;
        button.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.120
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$buildSystemDumpTab$4(view);
            }
        });
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, m3dp(54));
        layoutParams.topMargin = m3dp(14);
        linearLayoutColumnBody.addView(this.exportButton, layoutParams);
        TextView textViewText = text("ZIP будет сохранён в Download/HudLabDump. После завершения пришлите его сюда.", 14, TEXT, false);
        this.exportStatusView = textViewText;
        textViewText.setTextIsSelectable(true);
        this.exportStatusView.setPadding(0, m3dp(14), 0, m3dp(10));
        linearLayoutColumnBody.addView(this.exportStatusView);
        Button button2 = button("КОПИРОВАТЬ ПУТЬ К ZIP", CARD_BORDER, false);
        button2.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.121
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$buildSystemDumpTab$5(view);
            }
        });
        linearLayoutColumnBody.addView(button2, new LinearLayout.LayoutParams(-1, m3dp(48)));
        linearLayoutColumnBody.addView(note("Экспорт можно выполнить без подключения ноутбука и без root. На Android 9 при первом запуске потребуется разрешить доступ к файлам."));
        return scroll(linearLayoutColumnBody);
    }

    private View buildTabBar() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setPadding(0, 0, 0, m3dp(5));
        addTabButton(linearLayout, "ONE-SHOT", 0);
        addTabButton(linearLayout, "ПРИБОРКА", 1);
        addTabButton(linearLayout, "СИСТЕМНЫЙ ДАМП", 2);
        addTabButton(linearLayout, "DISPLAY_*", 3);
        addTabButton(linearLayout, "MASK", 4);
        addTabButton(linearLayout, "КАНАЛЫ", 5);
        addTabButton(linearLayout, "СТАТУС", 6);
        return linearLayout;
    }

    private View buildUi() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(m3dp(14), m3dp(10), m3dp(14), m3dp(10));
        linearLayout.setBackgroundColor(f7BG);
        linearLayout.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));
        TextView textViewText = text("Экспериментальный стенд: используйте только на стоящей машине. Команды выполняются только по нажатию. Поиск F00–F19 не сохраняется автоматически и не перезагружает HUD.", 14, Color.rgb(255, 199, 98), false);
        textViewText.setPadding(m3dp(10), m3dp(5), m3dp(10), m3dp(8));
        linearLayout.addView(textViewText);
        TextView textViewText2 = text("Последняя команда: команды ещё не отправлялись", 14, Color.rgb(255, 214, 125), true);
        this.lastCommandView = textViewText2;
        textViewText2.setTypeface(Typeface.MONOSPACE);
        this.lastCommandView.setPadding(m3dp(10), m3dp(4), m3dp(10), m3dp(8));
        linearLayout.addView(this.lastCommandView);
        linearLayout.addView(buildTabBar(), new LinearLayout.LayoutParams(-1, m3dp(44)));
        FrameLayout frameLayout = new FrameLayout(this);
        addTabPage(frameLayout, buildNewPathsTab());
        addTabPage(frameLayout, buildInstrumentClusterTab());
        addTabPage(frameLayout, buildSystemDumpTab());
        addTabPage(frameLayout, buildElementsTab());
        addTabPage(frameLayout, buildMaskTab());
        addTabPage(frameLayout, buildActivationTab());
        addTabPage(frameLayout, buildStatusTab());
        linearLayout.addView(frameLayout, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        selectTab(0);
        return linearLayout;
    }

    private Button button(String str, int i, boolean z) {
        Button button = new Button(this);
        button.setText(str);
        button.setTextSize(13.0f);
        button.setTextColor(-1);
        button.setGravity(17);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(m3dp(8), 0, m3dp(8), 0);
        button.setAllCaps(!z);
        button.setBackgroundTintList(ColorStateList.valueOf(i));
        return button;
    }

    private GradientDrawable cardDrawable() {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(CARD);
        gradientDrawable.setStroke(m3dp(1), CARD_BORDER);
        gradientDrawable.setCornerRadius(m3dp(10));
        return gradientDrawable;
    }

    private LinearLayout columnBody() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(m3dp(12), m3dp(10), m3dp(12), m3dp(10));
        linearLayout.setBackground(cardDrawable());
        return linearLayout;
    }

    private Button commandButton(final String str, int i, final Runnable runnable) {
        Button button = button(str, i, true);
        button.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.122
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                HudLabActivity.this.lambda$commandButton$119(str, runnable, view);
            }
        });
        this.commandButtons.add(button);
        return button;
    }

    private View commandPair(String str, int i, Runnable runnable, String str2, int i2, Runnable runnable2) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setPadding(0, 0, 0, m3dp(7));
        Button buttonCommandButton = commandButton(str, i, runnable);
        Button buttonCommandButton2 = commandButton(str2, i2, runnable2);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, m3dp(48), 1.0f);
        layoutParams.rightMargin = m3dp(4);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(0, m3dp(48), 1.0f);
        layoutParams2.leftMargin = m3dp(4);
        linearLayout.addView(buttonCommandButton, layoutParams);
        linearLayout.addView(buttonCommandButton2, layoutParams2);
        return linearLayout;
    }

    private View commandRow(int i, String[] strArr, Runnable[] runnableArr) {
        if (strArr.length != runnableArr.length || strArr.length == 0) {
            throw new IllegalArgumentException("labels/actions");
        }
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setPadding(0, 0, 0, m3dp(7));
        int i2 = 0;
        while (i2 < strArr.length) {
            Button buttonCommandButton = commandButton(strArr[i2], i, runnableArr[i2]);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, m3dp(48), 1.0f);
            if (i2 > 0) {
                layoutParams.leftMargin = m3dp(4);
            }
            i2++;
            if (i2 < strArr.length) {
                layoutParams.rightMargin = m3dp(4);
            }
            linearLayout.addView(buttonCommandButton, layoutParams);
        }
        return linearLayout;
    }

    private View commandRowStandalone(int i, String[] strArr, Runnable[] runnableArr) {
        return standaloneCommandRow(i, strArr, runnableArr, false);
    }

    public void confirmQnxInstall() {
        new AlertDialog.Builder(this).setTitle("Установить точечный QNX-патч?").setMessage("HUD Lab проверит заводскую контрольную сумму, создаст резервную копию, заменит только шесть TimeGap-текстур и перезагрузит QNX. Приборная панель и HUD временно погаснут. Машина должна стоять.").setNegativeButton("Отмена", (DialogInterface.OnClickListener) null).setPositiveButton("Установить", new DialogInterface.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.123
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                HudLabActivity.this.lambda$confirmQnxInstall$114(dialogInterface, i);
            }
        }).show();
    }

    public void confirmQnxRestore() {
        new AlertDialog.Builder(this).setTitle("Восстановить заводской HUD?").setMessage("Будет использована только резервная копия с подтверждённой заводской контрольной суммой. После восстановления QNX перезагрузится.").setNegativeButton("Отмена", (DialogInterface.OnClickListener) null).setPositiveButton("Восстановить", new DialogInterface.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.124
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                HudLabActivity.this.lambda$confirmQnxRestore$115(dialogInterface, i);
            }
        }).show();
    }

    private void copyDumpPath() {
        if (this.lastDumpPath.isEmpty()) {
            Toast.makeText(this, "Сначала соберите ZIP", 0).show();
            return;
        }
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService("clipboard");
        if (clipboardManager == null) {
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("HUD Lab dump", this.lastDumpPath));
        Toast.makeText(this, "Путь скопирован", 0).show();
    }

    private void copyStatus() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService("clipboard");
        if (clipboardManager == null) {
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("HUD Lab status", this.fullStatus));
        Toast.makeText(this, "Статус HUD Lab скопирован", 0).show();
    }

    private void disableLegacyFallback() {
        Context applicationContext = getApplicationContext();
        if (applicationContext == null) {
            applicationContext = this;
        }
        Context contextCreateDeviceProtectedStorageContext = applicationContext.createDeviceProtectedStorageContext();
        if (contextCreateDeviceProtectedStorageContext != null) {
            applicationContext = contextCreateDeviceProtectedStorageContext;
        }
        if (!applicationContext.getSharedPreferences("hud_mode_fallback_v1", 0).edit().putBoolean("enabled", false).putString("status", "Удалено в HUD Lab 0.21").putLong("status_at", System.currentTimeMillis()).commit()) {
            throw new IllegalStateException("не удалось снять старый fallback-флаг");
        }
    }

    private String displayInventory() {
        StringBuilder sb = new StringBuilder("Android displays:");
        try {
            DisplayManager displayManager = (DisplayManager) getSystemService("display");
            Display[] displays = displayManager == null ? new Display[0] : displayManager.getDisplays();
            Arrays.sort(displays, new Comparator() { // from class: dezz.status.hudlab.HudLabActivity.125
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return Integer.compare(((Display) obj).getDisplayId(), ((Display) obj2).getDisplayId());
                }
            });
            boolean z = false;
            boolean z2 = false;
            for (Display display : displays) {
                DisplayMetrics displayMetrics = new DisplayMetrics();
                display.getRealMetrics(displayMetrics);
                int displayId = display.getDisplayId();
                boolean z3 = true;
                z |= displayId == 2;
                if (displayId != 22) {
                    z3 = false;
                }
                z2 |= z3;
                sb.append("\nID ").append(displayId).append(" · ").append(display.getName()).append(" · ").append(displayMetrics.widthPixels).append('×').append(displayMetrics.heightPixels).append(" · flags=0x").append(Integer.toHexString(display.getFlags())).append("\n  ").append(display);
            }
            sb.append("\nИтог: displayId 2 ").append(z ? "найден" : "НЕ НАЙДЕН").append("; displayId 22 ").append(z2 ? "уже существует" : "не существует");
        } catch (Throwable th) {
            sb.append("\nERROR ").append(th.getClass().getSimpleName()).append(" · ").append(th.getMessage());
        }
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void appendClusterProbeTrace(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        this.clusterProbeTrace.add(text);
        ClusterNavigationTransfer transfer = this.clusterNavigationTransfer;
        if (this.clusterExactTraceActive && transfer != null) {
            transfer.appendTelemetry(text);
        }
        TextView status = this.clusterProbeStatusView;
        if (status != null) {
            StringBuilder out = new StringBuilder();
            out.append("ТЕСТ ПРИБОРКИ · displayId=").append(this.clusterProbeDisplayId).append(this.clusterProbeRunning ? " · ВЫПОЛНЯЕТСЯ" : " · ЗАВЕРШЁН");
            for (String line : this.clusterProbeTrace) {
                out.append("\n\n").append(line);
            }
            status.setText(out);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: beginClusterProbeAfterSamples, reason: merged with bridge method [inline-methods] */
    public void lambda$scheduleClusterProbeSamples$45(final int generation) {
        if (generation == this.clusterProbeGeneration && this.clusterProbeRunning) {
            dismissClusterProbeWindow();
            appendClusterProbeTrace("Тестовое окно закрыто; снимаю состояние после возврата штатной приборки.");
            scheduleClusterSignalSample(generation, "AFTER +0.8s", 800L, false);
            scheduleClusterSignalSample(generation, "AFTER +2.5s", 2500L, true);
            this.clusterProbeHandler.postDelayed(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda41
                @Override // java.lang.Runnable
                public final void run() {
                    HudLabActivity.this.lambda$beginClusterProbeAfterSamples$40(generation);
                }
            }, 1200L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$beginClusterProbeAfterSamples$40(int generation) {
        if (generation == this.clusterProbeGeneration) {
            captureSurfaceLayers(generation, "AFTER", null);
        }
    }

    private void captureSurfaceLayers(final int generation, final String phase, final Runnable continuation) {
        if (generation != this.clusterProbeGeneration) {
            return;
        }
        HudPrivilegedCommandRunner runner = this.privilegedCommands;
        if (runner == null) {
            appendClusterProbeTrace("LAYERS " + phase + ": локальный shell ещё не готов");
            if (continuation != null) {
                continuation.run();
                return;
            }
            return;
        }
        runner.runTrusted("dumpsys SurfaceFlinger --list", new HudPrivilegedCommandRunner.Callback() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda11
            @Override // dezz.status.hudlab.HudPrivilegedCommandRunner.Callback
            public final void onFinished(String str, String str2) {
                HudLabActivity.this.lambda$captureSurfaceLayers$41(generation, phase, continuation, str, str2);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$captureSurfaceLayers$41(int generation, String phase, Runnable continuation, String output, String error) {
        if (generation != this.clusterProbeGeneration) {
            return;
        }
        if (error != null) {
            appendClusterProbeTrace("LAYERS " + phase + ": ERROR " + error);
        } else {
            Set<String> current = parseSurfaceLayers(output);
            if ("BEFORE".equals(phase)) {
                this.clusterProbeBaselineLayers = new LinkedHashSet(current);
                appendClusterProbeTrace("LAYERS BEFORE: " + current.size() + " слоёв; эталон сохранён");
            } else {
                appendClusterProbeTrace(formatLayerDifference(phase, current));
            }
        }
        if (continuation != null) {
            continuation.run();
        }
    }

    private int findLikelyClusterDisplayId() {
        DisplayManager manager = (DisplayManager) getSystemService("display");
        if (manager == null) {
            return -1;
        }
        Display exact = manager.getDisplay(3);
        if (exact != null && exact.isValid()) {
            return 3;
        }
        int candidate = -1;
        for (Display display : manager.getDisplays()) {
            int id = display.getDisplayId();
            if (display.isValid() && id != 0 && id != 2) {
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                if (metrics.heightPixels == 720) {
                    candidate = Math.max(candidate, id);
                }
            }
        }
        return candidate;
    }

    private void dismissClusterProbeWindow() {
        ClusterProbePresentation presentation = this.clusterProbePresentation;
        this.clusterProbePresentation = null;
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable th) {
            }
        }
        ClusterProbeActivity.finishActive();
    }

    private void openClusterAccessibilitySettings() {
        ClusterNavigationTransfer transfer = this.clusterNavigationTransfer;
        if (transfer == null) {
            setClusterNavigationTrace("ERROR: контроллер теста displayId=2 ещё не готов");
            return;
        }
        transfer.openAccessibilitySettings();
    }

    private void startExactClusterTextTest() {
        final ClusterNavigationTransfer transfer = this.clusterNavigationTransfer;
        final HudLabController activeController = this.controller;
        if (transfer == null || activeController == null) {
            setClusterNavigationTrace("ERROR: контроллер теста displayId=2 ещё не готов");
            return;
        }
        if (this.clusterProbeRunning) {
            Toast.makeText(this, "Трасса уже выполняется; сначала закройте текущий тест", 0).show();
            return;
        }
        final int generation = this.clusterProbeGeneration + 1;
        this.clusterProbeGeneration = generation;
        this.clusterProbeDisplayId = 2;
        this.clusterProbeRunning = true;
        this.clusterExactTraceActive = true;
        this.clusterProbeAfterScheduled = false;
        this.clusterProbeBaseline = null;
        this.clusterProbeBaselineLayers = null;
        this.clusterProbeLogcatStart = null;
        this.clusterProbeTrace.clear();
        activeController.captureClusterProbeState(
                "ДО ЗАПУСКА",
                new HudLabController.ClusterProbeCallback() {
                    @Override
                    public void onCaptured(ClusterSignalSnapshot snapshot) {
                        captureExactClusterBaseline(generation, snapshot, transfer);
                    }
                });
    }

    private void stopExactClusterTextTest() {
        if (this.clusterNavigationTransfer == null) {
            setClusterNavigationTrace("ERROR: контроллер теста displayId=2 ещё не готов");
            return;
        }
        scheduleExactClusterAfterTelemetry();
    }

    private void markClusterWingsVisible() {
        if (!this.clusterProbeRunning || !this.clusterExactTraceActive) {
            Toast.makeText(this, "Сначала запустите тест собственного экрана", 0).show();
            return;
        }
        final int generation = this.clusterProbeGeneration;
        ClusterNavigationTransfer transfer = this.clusterNavigationTransfer;
        if (transfer != null) {
            transfer.captureManualWindowState();
        }
        appendClusterProbeTrace("★★ РУЧНАЯ МЕТКА: НИЖНИЕ КРЫЛЬЯ ВИДНЫ");
        HudLabController activeController = this.controller;
        if (activeController != null) {
            activeController.captureClusterProbeState(
                    "★★ МЕТКА КРЫЛЬЕВ",
                    new HudLabController.ClusterProbeCallback() {
                        @Override
                        public void onCaptured(ClusterSignalSnapshot snapshot) {
                            if (generation == HudLabActivity.this.clusterProbeGeneration) {
                                appendClusterProbeTrace(
                                        snapshot.formatAgainst(
                                                HudLabActivity.this.clusterProbeBaseline));
                            }
                        }
                    });
        }
        captureSurfaceLayers(generation, "★★ МЕТКА КРЫЛЬЕВ", null);
        captureClusterLogcat(generation, "★★ МЕТКА КРЫЛЬЕВ");
    }

    private void captureExactClusterBaseline(
            final int generation,
            final ClusterSignalSnapshot snapshot,
            final ClusterNavigationTransfer transfer) {
        if (generation != this.clusterProbeGeneration || !this.clusterProbeRunning) {
            return;
        }
        HudPrivilegedCommandRunner runner = this.privilegedCommands;
        if (runner == null) {
            launchAfterExactClusterBaseline(generation, snapshot, transfer, null, null);
            return;
        }
        runner.runTrusted(
                "date '+%m-%d %H:%M:%S.000'",
                new HudPrivilegedCommandRunner.Callback() {
                    @Override
                    public void onFinished(String output, String error) {
                        if (generation != HudLabActivity.this.clusterProbeGeneration
                                || !HudLabActivity.this.clusterProbeRunning) {
                            return;
                        }
                        String timestamp = error == null ? sanitizeLogcatTimestamp(output) : null;
                        HudPrivilegedCommandRunner current = HudLabActivity.this.privilegedCommands;
                        if (current == null) {
                            launchAfterExactClusterBaseline(
                                    generation,
                                    snapshot,
                                    transfer,
                                    timestamp,
                                    null);
                            return;
                        }
                        current.runTrusted(
                                "dumpsys SurfaceFlinger --list",
                                new HudPrivilegedCommandRunner.Callback() {
                                    @Override
                                    public void onFinished(String layers, String layerError) {
                                        launchAfterExactClusterBaseline(
                                                generation,
                                                snapshot,
                                                transfer,
                                                timestamp,
                                                layerError == null ? layers : null);
                                    }
                                });
                    }
                });
    }

    private void launchAfterExactClusterBaseline(
            int generation,
            ClusterSignalSnapshot snapshot,
            ClusterNavigationTransfer transfer,
            String logcatStart,
            String layers) {
        if (generation != this.clusterProbeGeneration || !this.clusterProbeRunning) {
            return;
        }
        this.clusterProbeBaseline = snapshot;
        this.clusterProbeLogcatStart = logcatStart;
        this.clusterProbeBaselineLayers = layers == null
                ? null
                : new LinkedHashSet<>(parseSurfaceLayers(layers));
        transfer.moveOwnScreenToCluster();
        appendClusterProbeTrace(snapshot.formatAgainst(snapshot));
        appendClusterProbeTrace(this.clusterProbeBaselineLayers == null
                ? "LAYERS ДО: локальный shell недоступен"
                : "LAYERS ДО: сохранён эталон из "
                + this.clusterProbeBaselineLayers.size() + " SurfaceFlinger-слоёв");
        appendClusterProbeTrace(logcatStart == null
                ? "LOGCAT ДО: точная временная метка недоступна"
                : "LOGCAT ДО: начало трассы " + logcatStart);
        scheduleExactClusterDuringTelemetry(generation);
    }

    private void scheduleExactClusterDuringTelemetry(final int generation) {
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +0.4с", 400L, false);
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +1.2с", 1200L, false);
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +2.4с", 2400L, false);
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +4.0с", 4000L, false);
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +7.0с", 7000L, false);
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +12.0с", 12000L, false);
        scheduleClusterSignalSample(generation, "ВО ВРЕМЯ +20.0с", 20000L, false);
        this.clusterProbeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureSurfaceLayers(generation, "ВО ВРЕМЯ +2.8с", null);
            }
        }, 2800L);
        this.clusterProbeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureSurfaceLayers(generation, "ВО ВРЕМЯ +9.0с", null);
            }
        }, 9000L);
        this.clusterProbeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureClusterLogcat(generation, "ВО ВРЕМЯ +4.5с");
            }
        }, 4500L);
        this.clusterProbeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureClusterLogcat(generation, "ВО ВРЕМЯ +13.0с");
            }
        }, 13000L);
    }

    private void scheduleExactClusterAfterTelemetry() {
        if (!this.clusterProbeRunning || this.clusterProbeAfterScheduled) {
            return;
        }
        this.clusterProbeAfterScheduled = true;
        final int generation = this.clusterProbeGeneration;
        ClusterNavigationTransfer transfer = this.clusterNavigationTransfer;
        if (transfer != null) {
            transfer.restore();
        }
        scheduleClusterSignalSample(generation, "ПОСЛЕ +0.4с", 400L, false);
        scheduleClusterSignalSample(generation, "ПОСЛЕ +1.0с", 1000L, false);
        scheduleClusterSignalSample(generation, "ПОСЛЕ +2.2с", 2200L, true);
        this.clusterProbeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureSurfaceLayers(generation, "ПОСЛЕ +1.2с", null);
            }
        }, 1200L);
        this.clusterProbeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureClusterLogcat(generation, "ИТОГ ПОСЛЕ +1.6с");
            }
        }, 1600L);
    }

    private void captureClusterLogcat(final int generation, final String phase) {
        if (generation != this.clusterProbeGeneration || !this.clusterProbeRunning) {
            return;
        }
        HudPrivilegedCommandRunner runner = this.privilegedCommands;
        if (runner == null) {
            appendClusterProbeTrace("LOGCAT " + phase + ": локальный shell недоступен");
            return;
        }
        String start = this.clusterProbeLogcatStart;
        String range = start == null ? "-t 900" : "-T '" + start + "'";
        String command = "logcat -d -v threadtime " + range
                + " | grep -Ei 'ActivityTaskManager|ActivityManager|WindowManager|"
                + "DisplayManager|SurfaceFlinger|Cluster|DIM|dimmenu|"
                + "monjaro_dashboard|hudlab29|ClusterProbeActivity|ecarx|navi'"
                + " | tail -n 700";
        runner.runTrusted(command, new HudPrivilegedCommandRunner.Callback() {
            @Override
            public void onFinished(String output, String error) {
                if (generation != HudLabActivity.this.clusterProbeGeneration) {
                    return;
                }
                String value = output == null ? "" : output.trim();
                appendClusterProbeTrace(error == null
                        ? "LOGCAT " + phase + ":\n"
                        + (value.isEmpty() ? "совпадений нет" : value)
                        : "LOGCAT " + phase + ": ERROR " + error);
            }
        });
    }

    private static String sanitizeLogcatTimestamp(String value) {
        if (value == null) {
            return null;
        }
        String timestamp = value.trim();
        return timestamp.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")
                ? timestamp
                : null;
    }

    private void setClusterNavigationTrace(String value) {
        TextView status = this.clusterNavigationStatusView;
        if (status != null) {
            status.setText(value);
        }
    }

    private String formatLayerDifference(String phase, Set<String> current) {
        Collection<?> before = this.clusterProbeBaselineLayers;
        if (before == null) {
            return "LAYERS " + phase + ": " + current.size() + " слоёв; эталон BEFORE отсутствует";
        }
        LinkedHashSet<String> added = new LinkedHashSet<>(current);
        added.removeAll(before);
        LinkedHashSet<String> removed = new LinkedHashSet<>((Collection<? extends String>) before);
        removed.removeAll(current);
        StringBuilder out = new StringBuilder("LAYERS ").append(phase).append(": всего=").append(current.size()).append(", +").append(added.size()).append(", −").append(removed.size());
        int emitted = 0;
        Iterator<String> it = added.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            String layer = it.next();
            int emitted2 = emitted + 1;
            if (emitted >= 30) {
                out.append("\n  … остальные добавленные слои пропущены");
                break;
            }
            out.append("\n  ★ + ").append(layer);
            emitted = emitted2;
        }
        int emitted3 = 0;
        Iterator<String> it2 = removed.iterator();
        while (true) {
            if (!it2.hasNext()) {
                break;
            }
            String layer2 = it2.next();
            int emitted4 = emitted3 + 1;
            if (emitted3 >= 15) {
                out.append("\n  … остальные исчезнувшие слои пропущены");
                break;
            }
            out.append("\n  ★ − ").append(layer2);
            emitted3 = emitted4;
        }
        return out.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: launchClusterProbe, reason: merged with bridge method [inline-methods] */
    public void lambda$startClusterProbe$48(final int generation, final int displayId) {
        if (generation != this.clusterProbeGeneration || !this.clusterProbeRunning || this.clusterProbeLaunchStarted) {
            return;
        }
        this.clusterProbeLaunchStarted = true;
        DisplayManager manager = (DisplayManager) getSystemService("display");
        Display target = manager == null ? null : manager.getDisplay(displayId);
        if (target == null || !target.isValid()) {
            this.clusterProbeRunning = false;
            appendClusterProbeTrace("PRESENTATION: displayId=" + displayId + " исчез до запуска");
            return;
        }
        try {
            final ClusterProbePresentation presentation = new ClusterProbePresentation(this, target);
            presentation.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda0
                @Override // android.content.DialogInterface.OnDismissListener
                public final void onDismiss(DialogInterface dialogInterface) {
                    HudLabActivity.this.lambda$launchClusterProbe$42(presentation, generation, displayId, dialogInterface);
                }
            });
            this.clusterProbePresentation = presentation;
            presentation.show();
            appendClusterProbeTrace("LAUNCH: зелёное Presentation-окно показано на displayId=" + displayId + " без междисплейного запуска Activity");
            scheduleClusterProbeSamples(generation);
        } catch (Throwable presentationFailure) {
            this.clusterProbePresentation = null;
            appendClusterProbeTrace("PRESENTATION: ERROR " + presentationFailure.getClass().getSimpleName() + " · " + presentationFailure.getMessage() + "\nПробую старый ActivityOptions как резерв.");
            Intent intent = new Intent(this, (Class<?>) ClusterProbeActivity.class).putExtra("duration_ms", 12000L).addFlags(411041792);
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                startActivity(intent, options.toBundle());
                appendClusterProbeTrace("LAUNCH: тестовая Activity отправлена на displayId=" + displayId + " через Android ActivityOptions");
                scheduleClusterProbeSamples(generation);
            } catch (Throwable directFailure) {
                appendClusterProbeTrace("LAUNCH API: ERROR " + directFailure.getClass().getSimpleName() + " · " + directFailure.getMessage() + "\nПробую тот же запуск через локальный ADB.");
                launchClusterProbeViaShell(generation, displayId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$launchClusterProbe$42(ClusterProbePresentation presentation, int generation, int displayId, DialogInterface dialog) {
        if (this.clusterProbePresentation == presentation) {
            this.clusterProbePresentation = null;
        }
        if (generation == this.clusterProbeGeneration) {
            appendClusterProbeTrace("PRESENTATION CLOSED · displayId=" + displayId);
        }
    }

    private void launchClusterProbeViaShell(final int generation, int displayId) {
        HudPrivilegedCommandRunner runner = this.privilegedCommands;
        if (runner == null) {
            this.clusterProbeRunning = false;
            appendClusterProbeTrace("LAUNCH ADB: локальный shell недоступен; тест не запущен");
        } else {
            String component = getPackageName() + "/" + ClusterProbeActivity.class.getName();
            runner.runTrusted("am start --display " + displayId + " -n " + component + " --el duration_ms 12000", new HudPrivilegedCommandRunner.Callback() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda38
                @Override // dezz.status.hudlab.HudPrivilegedCommandRunner.Callback
                public final void onFinished(String str, String str2) {
                    HudLabActivity.this.lambda$launchClusterProbeViaShell$43(generation, str, str2);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$launchClusterProbeViaShell$43(int generation, String output, String error) {
        if (generation != this.clusterProbeGeneration) {
            return;
        }
        if (error != null || output == null || output.contains("Error:") || output.contains("Exception")) {
            this.clusterProbeRunning = false;
            appendClusterProbeTrace("LAUNCH ADB: ERROR " + (error == null ? output : error));
        } else {
            appendClusterProbeTrace("LAUNCH: Activity отправлена через ADB:\n" + output.trim());
            scheduleClusterProbeSamples(generation);
        }
    }

    private static Set<String> parseSurfaceLayers(String output) {
        LinkedHashSet<String> layers = new LinkedHashSet<>();
        if (output == null) {
            return layers;
        }
        for (String line : output.split("\\r?\\n")) {
            String value = line.trim();
            if (!value.isEmpty()) {
                layers.add(value);
            }
        }
        return layers;
    }

    private void registerClusterProbeReceiver() {
        this.clusterProbeReceiver = new BroadcastReceiver() { // from class: dezz.status.hudlab.HudLabActivity.126
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !"dezz.status.hudlab29.action.CLUSTER_PROBE_STATE".equals(intent.getAction())) {
                    return;
                }
                String event = intent.getStringExtra("event");
                int displayId = intent.getIntExtra("display_id", -1);
                String state = intent.getStringExtra("state");
                ClusterNavigationTransfer transfer = HudLabActivity.this.clusterNavigationTransfer;
                if (transfer != null) {
                    transfer.onProbeEvent(event, displayId, state);
                }
                if (HudLabActivity.this.clusterProbeStatusView != null) {
                    HudLabActivity.this.appendClusterProbeTrace("ACTIVITY " + event + " · displayId=" + displayId + "\n" + state);
                }
                if (ClusterProbeActivity.EVENT_STOPPED.equals(event)) {
                    HudLabActivity.this.scheduleExactClusterAfterTelemetry();
                }
            }
        };
        registerReceiver(this.clusterProbeReceiver, new IntentFilter("dezz.status.hudlab29.action.CLUSTER_PROBE_STATE"));
    }

    private void scheduleClusterProbeSamples(final int generation) {
        scheduleClusterSignalSample(generation, "DURING +0.4s", 400L, false);
        scheduleClusterSignalSample(generation, "DURING +1.2s", 1200L, false);
        scheduleClusterSignalSample(generation, "DURING +3.0s", 3000L, false);
        scheduleClusterSignalSample(generation, "DURING +6.0s", 6000L, false);
        scheduleClusterSignalSample(generation, "DURING +10.0s", 10000L, false);
        this.clusterProbeHandler.postDelayed(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$scheduleClusterProbeSamples$44(generation);
            }
        }, 2200L);
        this.clusterProbeHandler.postDelayed(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda33
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$scheduleClusterProbeSamples$45(generation);
            }
        }, 12300L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleClusterProbeSamples$44(int generation) {
        if (generation == this.clusterProbeGeneration && this.clusterProbeRunning) {
            captureSurfaceLayers(generation, "DURING", null);
        }
    }

    private void scheduleClusterSignalSample(final int generation, final String phase, long delayMs, final boolean finish) {
        this.clusterProbeHandler.postDelayed(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda40
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$scheduleClusterSignalSample$47(generation, phase, finish);
            }
        }, delayMs);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleClusterSignalSample$47(final int generation, String phase, final boolean finish) {
        if (generation != this.clusterProbeGeneration || !this.clusterProbeRunning) {
            return;
        }
        HudLabController activeController = this.controller;
        if (activeController == null) {
            appendClusterProbeTrace(phase + ": ECARX controller недоступен");
            if (finish) {
                this.clusterProbeRunning = false;
                return;
            }
            return;
        }
        activeController.captureClusterProbeState(phase, new HudLabController.ClusterProbeCallback() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda39
            @Override // dezz.status.hudlab.HudLabController.ClusterProbeCallback
            public final void onCaptured(ClusterSignalSnapshot clusterSignalSnapshot) {
                HudLabActivity.this.lambda$scheduleClusterSignalSample$46(generation, finish, clusterSignalSnapshot);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$scheduleClusterSignalSample$46(int generation, boolean finish, ClusterSignalSnapshot snapshot) {
        if (generation != this.clusterProbeGeneration) {
            return;
        }
        appendClusterProbeTrace(snapshot.formatAgainst(this.clusterProbeBaseline));
        if (finish) {
            appendClusterProbeTrace("TEST COMPLETE: трасса ДО / ВО ВРЕМЯ / ПОСЛЕ собрана.");
            this.clusterProbeRunning = false;
        }
    }

    private void startClusterProbe(final int displayId) {
        this.clusterExactTraceActive = false;
        if (this.clusterProbeRunning) {
            Toast.makeText(this, "Тест уже выполняется; сначала остановите его", 0).show();
            return;
        }
        DisplayManager manager = (DisplayManager) getSystemService("display");
        Display target = manager == null ? null : manager.getDisplay(displayId);
        if (displayId < 0 || target == null || !target.isValid()) {
            this.clusterProbeDisplayId = displayId;
            this.clusterProbeTrace.clear();
            appendClusterProbeTrace("ERROR: displayId=" + displayId + " не найден.\n" + displayInventory());
            return;
        }
        HudLabController activeController = this.controller;
        if (activeController == null) {
            Toast.makeText(this, "ECARX controller ещё не готов", 0).show();
            return;
        }
        final int generation = this.clusterProbeGeneration + 1;
        this.clusterProbeGeneration = generation;
        this.clusterProbeDisplayId = displayId;
        this.clusterProbeRunning = true;
        this.clusterProbeLaunchStarted = false;
        this.clusterProbeBaseline = null;
        this.clusterProbeBaselineLayers = null;
        this.clusterProbeTrace.clear();
        DisplayMetrics metrics = new DisplayMetrics();
        target.getRealMetrics(metrics);
        appendClusterProbeTrace("START: target=" + target.getName() + " · " + metrics.widthPixels + "×" + metrics.heightPixels);
        activeController.captureClusterProbeState("BEFORE", new HudLabController.ClusterProbeCallback() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda43
            @Override // dezz.status.hudlab.HudLabController.ClusterProbeCallback
            public final void onCaptured(ClusterSignalSnapshot clusterSignalSnapshot) {
                HudLabActivity.this.lambda$startClusterProbe$50(generation, displayId, clusterSignalSnapshot);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startClusterProbe$50(final int generation, final int displayId, ClusterSignalSnapshot snapshot) {
        if (generation != this.clusterProbeGeneration || !this.clusterProbeRunning) {
            return;
        }
        this.clusterProbeBaseline = snapshot;
        appendClusterProbeTrace(snapshot.formatAgainst(snapshot));
        captureSurfaceLayers(generation, "BEFORE", new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda45
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$startClusterProbe$48(generation, displayId);
            }
        });
        this.clusterProbeHandler.postDelayed(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda46
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$startClusterProbe$49(generation, displayId);
            }
        }, 1200L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$startClusterProbe$49(int generation, int displayId) {
        if (generation == this.clusterProbeGeneration && this.clusterProbeRunning) {
            lambda$startClusterProbe$48(generation, displayId);
        }
    }

    private void stopClusterProbe(boolean captureAfter) {
        if (!this.clusterProbeRunning) {
            appendClusterProbeTrace("STOP: активного тестового окна уже нет.");
            return;
        }
        final int generation = this.clusterProbeGeneration + 1;
        this.clusterProbeGeneration = generation;
        dismissClusterProbeWindow();
        appendClusterProbeTrace("STOP: тестовое окно закрыто вручную.");
        if (!captureAfter || this.controller == null) {
            HudLabActivity hudLabActivity = this;
            hudLabActivity.clusterProbeRunning = false;
        } else {
            scheduleClusterSignalSample(generation, "AFTER MANUAL +0.4s", 400L, false);
            scheduleClusterSignalSample(generation, "AFTER MANUAL +1.8s", 1800L, true);
            this.clusterProbeHandler.postDelayed(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity$$ExternalSyntheticLambda42
                @Override // java.lang.Runnable
                public final void run() {
                    HudLabActivity.this.lambda$stopClusterProbe$51(generation);
                }
            }, 900L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$stopClusterProbe$51(int generation) {
        if (generation == this.clusterProbeGeneration) {
            captureSurfaceLayers(generation, "AFTER MANUAL", null);
        }
    }

    private int m3dp(int i) {
        return Math.round(i * getResources().getDisplayMetrics().density);
    }

    private void exportSystemDump() {
        Button button = this.exportButton;
        if (button == null || !button.isEnabled()) {
            return;
        }
        this.exportButton.setEnabled(false);
        this.exportButton.setAlpha(0.55f);
        this.exportStatusView.setText("Собираю системные пакеты и библиотеки… Не закрывайте HUD Lab.");
        new Thread(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.127
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$exportSystemDump$8();
            }
        }, "hud-system-export").start();
    }

    private static String findStatusLine(String str, String str2) {
        if (str == null || str.isEmpty()) {
            return str2 + " —";
        }
        for (String str3 : str.split("\\n")) {
            if (str3.startsWith(str2)) {
                return str3;
            }
        }
        return str2 + " —";
    }

    private LinearLayout.LayoutParams fixedButton(int i) {
        return new LinearLayout.LayoutParams(i, m3dp(46));
    }

    private TextView label(String str) {
        TextView textViewText = text(str, 13, TEXT, true);
        textViewText.setPadding(0, m3dp(8), 0, m3dp(5));
        return textViewText;
    }

    public void lambda$addTabButton$3(int i, View view) {
        selectTab(i);
    }

    public void lambda$buildActivationTab$100() {
        this.controller.setVfAr(false);
    }

    public void lambda$buildActivationTab$101() {
        this.controller.setVfAr(true);
    }

    public void lambda$buildActivationTab$102() {
        this.controller.setAllActivationChannels(false);
    }

    public void lambda$buildActivationTab$103() {
        this.controller.setAllActivationChannels(true);
    }

    public void lambda$buildActivationTab$104() {
        this.controller.setVfDisplayMode(0);
    }

    public void lambda$buildActivationTab$105() {
        this.controller.setVfDisplayMode(1);
    }

    public void lambda$buildActivationTab$92() {
        this.controller.setSettingsActive(false);
    }

    public void lambda$buildActivationTab$93() {
        this.controller.setSettingsActive(true);
    }

    public void lambda$buildActivationTab$94() {
        this.controller.setVfActive(false);
    }

    public void lambda$buildActivationTab$95() {
        this.controller.setVfActive(true);
    }

    public void lambda$buildActivationTab$96() {
        this.controller.setDimActive(false);
    }

    public void lambda$buildActivationTab$97() {
        this.controller.setDimActive(true);
    }

    public void lambda$buildActivationTab$98() {
        this.controller.setSettingsAr(false);
    }

    public void lambda$buildActivationTab$99() {
        this.controller.setSettingsAr(true);
    }

    public void lambda$buildElementsTab$75() {
        this.controller.refreshUserProfileHudAr();
    }

    public void lambda$buildElementsTab$76() {
        this.controller.setUserProfileHudAr(true);
    }

    public void lambda$buildElementsTab$77() {
        this.controller.setUserProfileHudAr(false);
    }

    public void lambda$buildElementsTab$78() {
        this.controller.restoreUserProfileHudAr();
    }

    public void lambda$buildElementsTab$79() {
        this.controller.setDisplayDriveEnvironment(false);
    }

    public void lambda$buildElementsTab$80() {
        this.controller.setDisplayDriveEnvironment(true);
    }

    public void lambda$buildElementsTab$81() {
        this.controller.setDisplaySafety(false);
    }

    public void lambda$buildElementsTab$82() {
        this.controller.setDisplaySafety(true);
    }

    public void lambda$buildElementsTab$83() {
        this.controller.setPrimaryDisplayElements(false);
    }

    public void lambda$buildElementsTab$84() {
        this.controller.setPrimaryDisplayElements(true);
    }

    public void lambda$buildElementsTab$85() {
        this.controller.setDisplayMedia(false);
    }

    public void lambda$buildElementsTab$86() {
        this.controller.setDisplayMedia(true);
    }

    public void lambda$buildElementsTab$87() {
        this.controller.setDisplayNavigation(false);
    }

    public void lambda$buildElementsTab$88() {
        this.controller.setDisplayNavigation(true);
    }

    public void lambda$buildElementsTab$89() {
        this.controller.setDisplayBtPhone(false);
    }

    public void lambda$buildElementsTab$90() {
        this.controller.setDisplayBtPhone(true);
    }

    public void lambda$buildElementsTab$91() {
        this.controller.restoreAllDisplayElements();
    }

    public void lambda$buildHeader$0(View view) {
        finish();
    }

    public void lambda$buildHeader$1(View view) {
        HudLabController hudLabController = this.controller;
        if (hudLabController != null) {
            hudLabController.refreshNow();
        }
    }

    public void lambda$buildHeader$2(View view) {
        copyStatus();
    }

    public void lambda$buildMaskTab$106() {
        this.controller.setAllVisualFunctions(0);
    }

    public void lambda$buildMaskTab$107() {
        this.controller.setAllVisualFunctions(1);
    }

    public void lambda$buildMaskTab$108() {
        this.controller.setVisualFunction(this.visualIndex, 0);
    }

    public void lambda$buildMaskTab$109() {
        this.controller.setVisualFunction(this.visualIndex, 1);
    }

    public void lambda$buildMaskTab$110() {
        this.controller.setVisualPen(this.visualPen);
    }

    public void lambda$buildNewPathsTab$10() {
        this.controller.applyPersistentHudMode(0, "ONE-SHOT способ A · mode=0");
    }

    public void lambda$buildNewPathsTab$11() {
        this.controller.applyPersistentHudMode(1, "ONE-SHOT способ A · mode=1");
    }

    public void lambda$buildNewPathsTab$12() {
        this.controller.applyPersistentHudMode(2, "ONE-SHOT способ A · mode=2");
    }

    public void lambda$buildNewPathsTab$13() {
        this.controller.applyPersistentHudMode(3, "ONE-SHOT способ A · mode=3");
    }

    public void lambda$buildNewPathsTab$14() {
        this.controller.setUserProfileHudMode(0);
    }

    public void lambda$buildNewPathsTab$15() {
        this.controller.setUserProfileHudMode(1);
    }

    public void lambda$buildNewPathsTab$16() {
        this.controller.setUserProfileHudMode(2);
    }

    public void lambda$buildNewPathsTab$17() {
        this.controller.setUserProfileHudMode(3);
    }

    public void lambda$buildNewPathsTab$18() {
        this.controller.refreshUserProfileHudMode();
    }

    public void lambda$buildNewPathsTab$19() {
        this.controller.restoreUserProfileHudMode();
    }

    public void lambda$buildNewPathsTab$20() {
        this.controller.setActiveProfileDimMode(0);
    }

    public void lambda$buildNewPathsTab$21() {
        this.controller.setActiveProfileDimMode(1);
    }

    public void lambda$buildNewPathsTab$22() {
        this.controller.setActiveProfileDimMode(2);
    }

    public void lambda$buildNewPathsTab$23() {
        this.controller.setActiveProfileDimMode(3);
    }

    public void lambda$buildNewPathsTab$24() {
        selectProfileSearchMode(0);
    }

    public void lambda$buildNewPathsTab$25() {
        selectProfileSearchMode(1);
    }

    public void lambda$buildNewPathsTab$26() {
        selectProfileSearchMode(2);
    }

    public void lambda$buildNewPathsTab$27() {
        selectProfileSearchMode(3);
    }

    public void lambda$buildNewPathsTab$28() {
        this.controller.applyHeldVisualProbe(this.profileSearchMode, this.heldProbeIndex);
    }

    public void lambda$buildNewPathsTab$29() {
        this.controller.applyHeldVisualBaseline(this.profileSearchMode, 1);
    }

    public void lambda$buildNewPathsTab$30() {
        this.controller.startSafeProfileVisualScan(this.profileSearchMode);
    }

    public void lambda$buildNewPathsTab$31() {
        this.controller.markProfileVisualScanFound();
    }

    public void lambda$buildNewPathsTab$32() {
        this.controller.restoreProfileVisualSearch();
    }

    public void lambda$buildNewPathsTab$33() {
        this.controller.restoreProfileTransferMode();
    }

    public void lambda$buildNewPathsTab$34() {
        this.controller.setActiveProfileDimMode(0);
    }

    public void lambda$buildNewPathsTab$35() {
        this.controller.setActiveProfileDimMode(1);
    }

    public void lambda$buildNewPathsTab$36() {
        this.controller.setActiveProfileDimMode(2);
    }

    public void lambda$buildNewPathsTab$37() {
        this.controller.setActiveProfileDimMode(3);
    }

    public void lambda$buildNewPathsTab$38() {
        this.controller.setActiveProfileVisualMask(true);
    }

    public void lambda$buildNewPathsTab$39() {
        this.controller.setActiveProfileVisualMask(false);
    }

    public void lambda$buildNewPathsTab$40() {
        this.controller.setDriverHmiBackground(0);
    }

    public void lambda$buildNewPathsTab$41() {
        this.controller.setDriverHmiBackground(1);
    }

    public void lambda$buildNewPathsTab$42() {
        this.controller.setDriverHmiBackground(2);
    }

    public void lambda$buildNewPathsTab$43() {
        this.controller.setDriverHmiBackground(3);
    }

    public void lambda$buildNewPathsTab$44() {
        this.controller.setDriverHmiBackground(4);
    }

    public void lambda$buildNewPathsTab$45() {
        this.controller.setDriverHmiBackground(5);
    }

    public void lambda$buildNewPathsTab$46() {
        this.controller.setDriverHmiInterface(0);
    }

    public void lambda$buildNewPathsTab$47() {
        this.controller.setDriverHmiInterface(1);
    }

    public void lambda$buildNewPathsTab$48() {
        this.controller.setDriverHmiInterface(2);
    }

    public void lambda$buildNewPathsTab$49() {
        this.controller.setDriverHmiInterface(3);
    }

    public void lambda$buildNewPathsTab$50() {
        this.controller.setDriverHmiInterface(4);
    }

    public void lambda$buildNewPathsTab$51() {
        this.controller.setDriverHmiInterface(5);
    }

    public void lambda$buildNewPathsTab$52() {
        this.controller.setDriverHmiInterface(6);
    }

    public void lambda$buildNewPathsTab$53() {
        this.controller.setDriverHmiInterface(7);
    }

    public void lambda$buildNewPathsTab$54() {
        this.controller.setDriverHmiInterface(8);
    }

    public void lambda$buildNewPathsTab$55() {
        this.controller.setDriverHmiInterface(9);
    }

    public void lambda$buildNewPathsTab$56() {
        this.controller.setMultimediaInformationMode(0);
    }

    public void lambda$buildNewPathsTab$57() {
        this.controller.setMultimediaInformationMode(1);
    }

    public void lambda$buildNewPathsTab$58() {
        this.controller.setMultimediaInformationMode(2);
    }

    public void lambda$buildNewPathsTab$59() {
        this.controller.setMultimediaInformationMode(3);
    }

    public void lambda$buildNewPathsTab$60() {
        this.controller.setIndividualTheme(false);
    }

    public void lambda$buildNewPathsTab$61() {
        this.controller.setIndividualTheme(true);
    }

    public void lambda$buildNewPathsTab$62() {
        this.controller.setHmiThemeMode(0);
    }

    public void lambda$buildNewPathsTab$63() {
        this.controller.setHmiThemeMode(1);
    }

    public void lambda$buildNewPathsTab$64() {
        this.controller.setHmiThemeMode(2);
    }

    public void lambda$buildNewPathsTab$65() {
        this.controller.setHmiThemeMode(3);
    }

    public void lambda$buildNewPathsTab$66() {
        this.controller.setDriverDisplayTheme(0);
    }

    public void lambda$buildNewPathsTab$67() {
        this.controller.setDriverDisplayTheme(1);
    }

    public void lambda$buildNewPathsTab$68() {
        this.controller.setDriverDisplayTheme(2);
    }

    public void lambda$buildNewPathsTab$69() {
        this.controller.setVehicleModelClear(false);
    }

    public void lambda$buildNewPathsTab$70() {
        this.controller.setVehicleModelClear(true);
    }

    public void lambda$buildNewPathsTab$71() {
        this.controller.restoreVehicleModelClear();
    }

    public void lambda$buildNewPathsTab$72() {
        this.controller.reloadActiveProfile();
    }

    public void lambda$buildNewPathsTab$73() {
        this.controller.persistCurrentProfileSettings();
    }

    public void lambda$buildNewPathsTab$9() {
        runQnxAction(HudQnxTimeGapInstaller.Action.INSPECT);
    }

    public void lambda$buildSystemDumpTab$4(View view) {
        requestSystemDump();
    }

    public void lambda$buildSystemDumpTab$5(View view) {
        copyDumpPath();
    }

    public void lambda$commandButton$119(String str, Runnable runnable, View view) {
        if (this.controller != null) {
            TextView textView = this.lastCommandView;
            if (textView != null) {
                textView.setText("Последняя команда: нажата «" + str + "» — отправка…");
            }
            runnable.run();
        }
    }

    public void lambda$confirmQnxInstall$114(DialogInterface dialogInterface, int i) {
        runQnxAction(HudQnxTimeGapInstaller.Action.INSTALL);
    }

    public void lambda$confirmQnxRestore$115(DialogInterface dialogInterface, int i) {
        runQnxAction(HudQnxTimeGapInstaller.Action.RESTORE);
    }

    public void lambda$exportSystemDump$6(HudSystemDumpExporter.Result result) {
        this.lastDumpPath = result.file.getAbsolutePath();
        this.exportStatusView.setText(result.summary() + "\n\nПришлите этот ZIP в чат целиком.");
        restoreExportButton();
        Toast.makeText(this, "Системный дамп HUD готов", 1).show();
    }

    public void lambda$exportSystemDump$7(Throwable th) {
        String message = th.getMessage();
        this.exportStatusView.setText("Ошибка экспорта: " + th.getClass().getSimpleName() + (message == null ? "" : "\n" + message));
        restoreExportButton();
        Toast.makeText(this, "Не удалось собрать ZIP", 1).show();
    }

    public void lambda$exportSystemDump$8() {
        try {
            final HudSystemDumpExporter.Result resultExport = HudSystemDumpExporter.export(getApplicationContext(), this.fullStatus);
            runOnUiThread(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.128
                @Override // java.lang.Runnable
                public final void run() {
                    HudLabActivity.this.lambda$exportSystemDump$6(resultExport);
                }
            });
        } catch (Throwable th) {
            runOnUiThread(new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.129
                @Override // java.lang.Runnable
                public final void run() {
                    HudLabActivity.this.lambda$exportSystemDump$7(th);
                }
            });
        }
    }

    public void lambda$runDisplayStackCommand$117(boolean z, String str, String str2, String str3) {
        StringBuilder sbAppend;
        String strTrim;
        StringBuilder sbAppend2;
        if (z) {
            this.displayStackCommandRunning = false;
        }
        StringBuilder sb = new StringBuilder();
        if (str3 == null || str3.trim().isEmpty()) {
            if (str2 == null || str2.trim().isEmpty()) {
                sbAppend = new StringBuilder().append("STACK ").append(str);
                strTrim = ": команда завершилась без вывода";
            } else {
                sbAppend = new StringBuilder().append("STACK ").append(str).append(":\n");
                strTrim = str2.trim();
            }
            sbAppend2 = sbAppend.append(strTrim);
        } else {
            sbAppend2 = new StringBuilder().append("STACK ").append(str).append(": ERROR · ").append(str3);
        }
        setDisplayExperimentStatus(sb.append(sbAppend2.toString()).append("\n\n").append(displayInventory()).toString());
    }

    public void lambda$selectProfileSearchMode$74(int i) {
        Toast.makeText(this, "Режим " + i + " применён один раз", 0).show();
    }

    private View modeGrid(boolean z) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.addView(modeRow(z, 0, "0 Guide", 1, "1 Drive"));
        linearLayout.addView(modeRow(z, 2, "2 AR", 3, "3 Simple"));
        return linearLayout;
    }

    private View modeRow(final boolean z, final int i, String str, final int i2, String str2) {
        int i3 = BLUE;
        return commandPair(str, i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.130
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$modeRow$112(z, i);
            }
        }, str2, i3, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.131
            @Override // java.lang.Runnable
            public final void run() {
                HudLabActivity.this.lambda$modeRow$112(z, i2);
            }
        });
    }

    public void nextHeldProbe() {
        this.heldProbeIndex = (this.heldProbeIndex + 1) % 20;
        updateHeldProbe();
    }

    public void nextVisualIndex() {
        this.visualIndex = (this.visualIndex + 1) % 20;
        updateVisualIndex();
    }

    public void nextVisualPen() {
        int i = this.visualPen;
        this.visualPen = i >= 13 ? 0 : i + 1;
        updateVisualPen();
    }

    private TextView note(String str) {
        TextView textViewText = text(str, 12, MUTED, false);
        textViewText.setPadding(0, 0, 0, m3dp(5));
        return textViewText;
    }

    public void previousHeldProbe() {
        this.heldProbeIndex = (this.heldProbeIndex + 19) % 20;
        updateHeldProbe();
    }

    public void previousVisualIndex() {
        this.visualIndex = (this.visualIndex + 19) % 20;
        updateVisualIndex();
    }

    public void previousVisualPen() {
        int i = this.visualPen;
        this.visualPen = i <= 0 ? 13 : i - 1;
        updateVisualPen();
    }

    private View qnxCommandRow(int i, String[] strArr, Runnable[] runnableArr) {
        return standaloneCommandRow(i, strArr, runnableArr, true);
    }

    private void requestSystemDump() {
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQUEST_STORAGE);
        } else {
            exportSystemDump();
        }
    }

    public void restoreDisplayStack2() {
        runDisplayStackCommand("restore", "Отправляется аварийный возврат physical local:2 → layerStack 2…");
    }

    private void restoreExportButton() {
        this.exportButton.setEnabled(true);
        this.exportButton.setAlpha(1.0f);
    }

    public void runDisplayStack22Test() {
        runDisplayStackCommand("test", "Тест начат: physical local:2 → layerStack 22 на 12 секунд. Смотрите на реальный HUD; ожидается зелёная метка STACK 22.");
    }

    private void runDisplayStackCommand(final String str, String str2) {
        final boolean zEquals = "test".equals(str);
        if (zEquals && this.displayStackCommandRunning) {
            setDisplayExperimentStatus("Предыдущий stack-тест ещё выполняется. Он сам вернёт stack 2 не позднее чем через 12 секунд.");
            return;
        }
        HudPrivilegedCommandRunner hudPrivilegedCommandRunner = this.privilegedCommands;
        if (hudPrivilegedCommandRunner == null) {
            setDisplayExperimentStatus("Локальный shell-канал ещё не готов.");
            return;
        }
        if (zEquals) {
            this.displayStackCommandRunning = true;
        }
        setDisplayExperimentStatus(str2 + "\n\n" + displayInventory());
        hudPrivilegedCommandRunner.runTrusted("APK=$(pm path " + getPackageName() + " | sed -n '1s/^package://p'); if [ -z \"$APK\" ]; then echo 'ERROR base APK not found'; exit 70; fi; export CLASSPATH=\"$APK\"; app_process /system/bin " + HudDisplayStackBridgeMain.class.getName() + " " + str + " 2>&1", new HudPrivilegedCommandRunner.Callback() { // from class: dezz.status.hudlab.HudLabActivity.132
            @Override // dezz.status.hudlab.HudPrivilegedCommandRunner.Callback
            public final void onFinished(String str3, String str4) {
                HudLabActivity.this.lambda$runDisplayStackCommand$117(zEquals, str, str3, str4);
            }
        });
    }

    private void runQnxAction(HudQnxTimeGapInstaller.Action action) {
        HudQnxTimeGapInstaller hudQnxTimeGapInstaller = this.qnxInstaller;
        if (hudQnxTimeGapInstaller == null) {
            setQnxPatchStatus("QNX: установщик уже закрыт.", false);
            return;
        }
        setQnxButtonsEnabled(false);
        this.qnxOperationRunning = true;
        setQnxPatchStatus("QNX: подключение к нативной системе…", true);
        hudQnxTimeGapInstaller.run(action, new HudQnxTimeGapInstaller.Listener() { // from class: dezz.status.hudlab.HudLabActivity.133
            @Override // dezz.status.hudlab.HudQnxTimeGapInstaller.Listener
            public void onFinished(boolean z, String str) {
                HudLabActivity.this.qnxOperationRunning = false;
                HudLabActivity.this.setQnxPatchStatus(str, z);
                HudLabActivity.this.setQnxButtonsEnabled(true);
                if (HudLabActivity.this.lastCommandView != null) {
                    HudLabActivity.this.lastCommandView.setText("Последняя команда: " + str);
                }
                Toast.makeText(HudLabActivity.this, z ? "Операция QNX завершена" : "Операция QNX заблокирована", 1).show();
            }

            @Override // dezz.status.hudlab.HudQnxTimeGapInstaller.Listener
            public void onProgress(String str) {
                HudLabActivity.this.setQnxPatchStatus(str, true);
            }
        });
    }

    private ScrollView scroll(View view) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(view, new ViewGroup.LayoutParams(-1, -2));
        return scrollView;
    }

    private TextView sectionTitle(String str) {
        TextView textViewText = text(str, 18, TEXT, true);
        textViewText.setPadding(0, 0, 0, m3dp(8));
        return textViewText;
    }

    private void selectProfileSearchMode(final int i) {
        this.profileSearchMode = i;
        if (this.profileSearchModeView != null) {
            this.profileSearchModeView.setText("Проверяется режим поиска: " + (i != 0 ? i != 1 ? i != 2 ? i != 3 ? Integer.toString(i) : "3 SIMPLE" : "2 AR" : "1 DRIVE" : "0 GUIDE"));
        }
        HudLabController hudLabController = this.controller;
        if (hudLabController != null) {
            hudLabController.setProfileTransferMode(i, new Runnable() { // from class: dezz.status.hudlab.HudLabActivity.134
                @Override // java.lang.Runnable
                public final void run() {
                    HudLabActivity.this.lambda$selectProfileSearchMode$74(i);
                }
            });
        }
    }

    private void selectTab(int i) {
        int i2 = 0;
        while (i2 < this.tabPages.size()) {
            boolean z = i2 == i;
            this.tabPages.get(i2).setVisibility(z ? 0 : 8);
            this.tabButtons.get(i2).setBackgroundTintList(ColorStateList.valueOf(z ? BLUE : CARD_BORDER));
            i2++;
        }
    }

    private void setCommandsEnabled(boolean z) {
        for (Button button : this.commandButtons) {
            button.setEnabled(z);
            button.setAlpha(z ? 1.0f : 0.42f);
        }
    }

    private void setDisplayExperimentStatus(String str) {
        TextView textView = this.displayExperimentStatusView;
        if (textView != null) {
            textView.setText(str);
        }
        if (this.lastCommandView != null) {
            this.lastCommandView.setText("Последняя команда: " + (str == null ? "" : str.split("\\n", 2)[0]));
        }
    }

    public void lambda$modeRow$112(boolean z, int i) {
        if (z) {
            this.controller.setActiveProfileDimMode(i);
        } else {
            this.controller.setVfDisplayMode(i);
        }
    }

    public void setQnxButtonsEnabled(boolean z) {
        for (Button button : this.qnxButtons) {
            button.setEnabled(z);
            button.setAlpha(z ? 1.0f : 0.42f);
        }
    }

    public void setQnxPatchStatus(String str, boolean z) {
        TextView textView = this.qnxPatchStatusView;
        if (textView == null) {
            return;
        }
        textView.setText(str);
        this.qnxPatchStatusView.setTextColor(z ? Color.rgb(255, 214, 125) : Color.rgb(255, 125, 125));
    }

    private View singleCommand(String str, int i, Runnable runnable) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setPadding(0, m3dp(3), 0, m3dp(8));
        linearLayout.addView(commandButton(str, i, runnable), new LinearLayout.LayoutParams(-1, m3dp(48)));
        return linearLayout;
    }

    private View singleStandaloneCommand(String str, int i, final Runnable runnable) {
        Button button = button(str, i, true);
        button.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.135
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                runnable.run();
            }
        });
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setPadding(0, 0, 0, m3dp(7));
        linearLayout.addView(button, new LinearLayout.LayoutParams(-1, m3dp(50)));
        return linearLayout;
    }

    private View standaloneCommandRow(int i, String[] strArr, Runnable[] runnableArr, boolean z) {
        if (strArr.length != runnableArr.length || strArr.length == 0) {
            throw new IllegalArgumentException("labels/actions");
        }
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setPadding(0, 0, 0, m3dp(7));
        int i2 = 0;
        while (i2 < strArr.length) {
            Button button = button(strArr[i2], i, true);
            final Runnable runnable = runnableArr[i2];
            button.setOnClickListener(new View.OnClickListener() { // from class: dezz.status.hudlab.HudLabActivity.136
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    runnable.run();
                }
            });
            if (z) {
                this.qnxButtons.add(button);
            }
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, m3dp(48), 1.0f);
            if (i2 > 0) {
                layoutParams.leftMargin = m3dp(4);
            }
            i2++;
            if (i2 < strArr.length) {
                layoutParams.rightMargin = m3dp(4);
            }
            linearLayout.addView(button, layoutParams);
        }
        return linearLayout;
    }

    public void startDisplay2Black() {
        setDisplayExperimentStatus(HudDisplayCover.startBlack(this).message + "\n\n" + displayInventory());
    }

    public void startDisplay2Marker() {
        setDisplayExperimentStatus(HudDisplayCover.startMarker(this).message + "\n\n" + displayInventory());
    }

    public void stopDisplay2Cover() {
        HudDisplayCover.stop(this);
        setDisplayExperimentStatus("Команда остановки HUD cover отправлена.\n\n" + displayInventory());
    }

    private TextView text(String str, int i, int i2, boolean z) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(i);
        textView.setTextColor(i2);
        textView.setGravity(16);
        if (z) {
            textView.setTypeface(Typeface.DEFAULT, 1);
        }
        return textView;
    }

    private void updateHeldProbe() {
        this.heldProbeIndexView.setText(String.format(Locale.ROOT, "F%02d", Integer.valueOf(this.heldProbeIndex)));
    }

    private void updateVisualIndex() {
        this.visualIndexView.setText(String.format(Locale.ROOT, "F%02d", Integer.valueOf(this.visualIndex)));
    }

    private void updateVisualPen() {
        this.visualPenView.setText(String.format(Locale.ROOT, "PEN %d", Integer.valueOf(this.visualPen)));
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(128);
        Window window = getWindow();
        int i = f7BG;
        window.setStatusBarColor(i);
        getWindow().setNavigationBarColor(i);
        getWindow().getDecorView().setSystemUiVisibility(4358);
        try {
            disableLegacyFallback();
        } catch (Throwable th) {
        }
        this.qnxInstaller = new HudQnxTimeGapInstaller();
        setContentView(buildUi());
        setCommandsEnabled(false);
        this.privilegedCommands = new HudPrivilegedCommandRunner(this);
        this.clusterNavigationTransfer = new ClusterNavigationTransfer(
                this,
                this.privilegedCommands,
                new ClusterNavigationTransfer.Listener() {
                    @Override
                    public void onTraceChanged(String trace) {
                        setClusterNavigationTrace(trace);
                    }
                });
        this.clusterNavigationTransfer.showIdleStatus();
        registerClusterProbeReceiver();
        HudLabController hudLabController = new HudLabController(this, this);
        this.controller = hudLabController;
        hudLabController.start();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        this.clusterProbeGeneration++;
        this.clusterProbeRunning = false;
        this.clusterProbeHandler.removeCallbacksAndMessages(null);
        dismissClusterProbeWindow();
        BroadcastReceiver receiver = this.clusterProbeReceiver;
        this.clusterProbeReceiver = null;
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable th) {
            }
        }
        HudLabController hudLabController = this.controller;
        this.controller = null;
        if (hudLabController != null) {
            hudLabController.close();
        }
        HudDisplayCover.stop(this);
        HudPrivilegedCommandRunner hudPrivilegedCommandRunner = this.privilegedCommands;
        this.privilegedCommands = null;
        ClusterNavigationTransfer transfer = this.clusterNavigationTransfer;
        this.clusterNavigationTransfer = null;
        if (transfer != null) {
            transfer.close();
        }
        if (hudPrivilegedCommandRunner != null) {
            hudPrivilegedCommandRunner.close();
        }
        HudQnxTimeGapInstaller hudQnxTimeGapInstaller = this.qnxInstaller;
        this.qnxInstaller = null;
        if (hudQnxTimeGapInstaller != null) {
            hudQnxTimeGapInstaller.close();
        }
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        super.onRequestPermissionsResult(i, strArr, iArr);
        if (i != REQUEST_STORAGE) {
            return;
        }
        if (iArr.length > 0 && iArr[0] == 0) {
            exportSystemDump();
        } else {
            this.exportStatusView.setText("Нет доступа к общему Download. Разрешите доступ к файлам и повторите.");
            Toast.makeText(this, "Доступ к файлам не выдан", 1).show();
        }
    }

    @Override // dezz.status.hudlab.HudLabController.Listener
    public void onUpdated(String str, String str2, boolean z) {
        int i;
        int i2;
        int i3;
        this.snapshotView.setText(str);
        this.logView.setText(str2);
        this.connectionBadge.setText(z ? "ECARX: ГОТОВО" : "ECARX: ОЖИДАНИЕ");
        TextView textView = this.connectionBadge;
        if (z) {
            i = 231;
            i2 = 156;
            i3 = 102;
        } else {
            i = 192;
            i2 = 92;
            i3 = 255;
        }
        textView.setTextColor(Color.rgb(i3, i, i2));
        StringBuilder sbAppend = new StringBuilder().append(str).append("\nDISPLAY ID LAB\n");
        TextView textView2 = this.displayExperimentStatusView;
        StringBuilder sbAppend2 = sbAppend.append((Object) (textView2 == null ? displayInventory() : textView2.getText())).append("\nCLUSTER DISPLAY 2 TRACE\n");
        TextView transferTrace = this.clusterNavigationStatusView;
        sbAppend2.append((Object) (transferTrace == null ? "тест ещё не запускался" : transferTrace.getText())).append("\nQNX TIMEGAP PATCH\n");
        TextView textView22 = this.qnxPatchStatusView;
        this.fullStatus = sbAppend2.append((Object) (textView22 == null ? "статус ещё не проверялся" : textView22.getText())).append("\nСОБЫТИЯ\n").append(str2).toString();
        TextView textView3 = this.lastCommandView;
        if (textView3 != null && !this.qnxOperationRunning) {
            textView3.setText(findStatusLine(str, "Последняя команда:"));
        }
        TextView textView4 = this.profileSearchStatusView;
        if (textView4 != null) {
            textView4.setText(findStatusLine(str, "Поиск 01:"));
        }
        setCommandsEnabled(z);
    }

    void sendDynamicMode(int i) {
        HudLabController hudLabController = this.controller;
        if (hudLabController != null) {
            hudLabController.setVfDisplayMode(i);
        }
    }
}
