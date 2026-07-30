package dezz.status.hudlab;

/* loaded from: classes4.dex */
final class HudDynamicModeRunnable implements Runnable {
    private final HudLabActivity activity;
    private final int mode;

    HudDynamicModeRunnable(HudLabActivity hudLabActivity, int i) {
        this.activity = hudLabActivity;
        this.mode = i;
    }

    @Override // java.lang.Runnable
    public void run() {
        int i = this.mode;
        this.activity.sendDynamicMode(i == -1 ? 1212435456 : 1212435457 + i);
    }
}
