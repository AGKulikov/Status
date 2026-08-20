/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.systemui;

public final class SystemStatusBarContentPolicy {
    private static final java.lang.String CLOCK_SLOT = "clock";
    private static final java.lang.String ICON_BLACKLIST = "icon_blacklist";
    private static final java.lang.String INVALID_SHELL_OUTPUT = " invalid-shell-output";
    private static final int PER_USER_UID_RANGE = 100000;
    private static final java.lang.Object REQUEST_LOCK = new java.lang.Object();
    private static final java.util.ArrayDeque<dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest> REQUEST_QUEUE = new java.util.ArrayDeque<>();
    private static final java.lang.String SHELL_VALUE_BEGIN = ":__NATRO_ICON_BLACKLIST_BEGIN__:";
    private static final java.lang.String SHELL_VALUE_END = ":__NATRO_ICON_BLACKLIST_END__:";
    private static final java.lang.String SHELL_VALUE_META = ":__NATRO_ICON_BLACKLIST_META__:";
    private static final java.lang.String STATUS_BAR_ICONS_RESOURCE = "config_statusBarIcons";
    private static boolean requestActive;
    private static boolean resetBarrier;

    public interface Callback {
        void onComplete(boolean z, java.lang.String str);
    }

    static /* synthetic */ void lambda$applyStored$0(boolean z, java.lang.String str) {
    }

    private SystemStatusBarContentPolicy() {
    }

    public static void applyStored(android.content.Context context) {
        dezz.status.widget.Preferences preferences = new dezz.status.widget.Preferences(context.getApplicationContext());
        boolean z = preferences.systemUiHideStockContentGlobally.get();
        if (!z && preferences.systemUiOwnedHiddenSlots.get().isEmpty() && preferences.pendingSystemStatusBarContentState() == null) {
            return;
        }
        apply(context, z, new dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda10
            @Override // dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback
            public final void onComplete(boolean z2, java.lang.String str) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$applyStored$0(z2, str);
            }
        });
    }

    public static void apply(android.content.Context context, boolean z, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        boolean z2;
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequestRemoveFirst;
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequest = new dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest(context.getApplicationContext(), z, false, callback);
        synchronized (REQUEST_LOCK) {
            z2 = true;
            pendingRequestRemoveFirst = null;
            if (!resetBarrier) {
                java.util.ArrayDeque<dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest> arrayDeque = REQUEST_QUEUE;
                arrayDeque.addLast(pendingRequest);
                if (!requestActive) {
                    requestActive = true;
                    pendingRequestRemoveFirst = arrayDeque.removeFirst();
                }
                z2 = false;
            }
        }
        if (z2) {
            deliver(callback, false, "SystemUI change rejected while settings reset is running");
        } else if (pendingRequestRemoveFirst != null) {
            startRequest(pendingRequestRemoveFirst);
        }
    }

    public static void restoreBeforeReset(android.content.Context context, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequestRemoveFirst;
        java.util.ArrayDeque<dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest> arrayDeque;
        boolean z = true;
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequest = new dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest(context.getApplicationContext(), false, true, callback);
        java.util.ArrayDeque arrayDeque2 = new java.util.ArrayDeque();
        synchronized (REQUEST_LOCK) {
            pendingRequestRemoveFirst = null;
            if (!resetBarrier) {
                resetBarrier = true;
                while (true) {
                    arrayDeque = REQUEST_QUEUE;
                    if (arrayDeque.isEmpty()) {
                        break;
                    } else {
                        arrayDeque2.addLast(arrayDeque.removeFirst());
                    }
                }
                arrayDeque.addLast(pendingRequest);
                if (!requestActive) {
                    requestActive = true;
                    pendingRequestRemoveFirst = arrayDeque.removeFirst();
                }
                z = false;
            }
        }
        while (!arrayDeque2.isEmpty()) {
            deliver(((dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest) arrayDeque2.removeFirst()).callback, false, "SystemUI change cancelled by settings reset");
        }
        if (z) {
            deliver(callback, false, "Settings reset is already restoring SystemUI");
        } else if (pendingRequestRemoveFirst != null) {
            startRequest(pendingRequestRemoveFirst);
        }
    }

    private static void startRequest(final dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequest) {
        runOnMain(new java.lang.Runnable() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequest2 = pendingRequest;
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.applyNow(pendingRequest2.context, pendingRequest2.enabled, new dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda12
                    @Override // dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback
                    public final void onComplete(boolean z, java.lang.String str) {
                        dezz.status.widget.systemui.SystemStatusBarContentPolicy.completeRequest(pendingRequest2, z, str);
                    }
                });
            }
        });
    }

    public static void completeRequest(final dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequest, final boolean z, final java.lang.String str) {
        runOnMain(() -> finishRequestOnMain(pendingRequest, z, str));
    }

    private static void finishRequestOnMain(
            dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest pendingRequest,
            boolean success,
            java.lang.String detail) {
        try {
            if (pendingRequest.callback != null) {
                pendingRequest.callback.onComplete(success, detail);
            }
        } finally {
            dezz.status.widget.systemui.SystemStatusBarContentPolicy.PendingRequest next = null;
            synchronized (REQUEST_LOCK) {
                if (pendingRequest.resetRequest) {
                    resetBarrier = false;
                }
                if (REQUEST_QUEUE.isEmpty()) {
                    requestActive = false;
                } else {
                    next = REQUEST_QUEUE.removeFirst();
                }
            }
            // Never invoke or start another request while holding REQUEST_LOCK. A callback may
            // synchronously enqueue another apply, and the reset callback may restart the app.
            if (next != null) {
                startRequest(next);
            }
        }
    }

    public static void applyNow(android.content.Context context, final boolean z, final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution slotResolutionSuccess;
        final android.content.Context applicationContext = context.getApplicationContext();
        dezz.status.widget.Preferences preferences = new dezz.status.widget.Preferences(applicationContext);
        if (preferences.pendingSystemStatusBarContentState() != null) {
            recoverPendingTransaction(applicationContext, preferences, new dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda11
                @Override // dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback
                public final void onComplete(boolean z2, java.lang.String str) {
                    dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$applyNow$4(applicationContext, z, callback, z2, str);
                }
            });
            return;
        }
        if (!z && preferences.systemUiOwnedHiddenSlots.get().isEmpty()) {
            boolean z2 = preferences.systemUiHideStockContentGlobally.get();
            java.util.Set<java.lang.String> set = preferences.systemUiOwnedHiddenSlots.get();
            if (preferences.saveVerifiedSystemStatusBarContentState(false, new java.util.LinkedHashSet())) {
                deliver(callback, true, "SystemUI setting already restored");
                return;
            } else {
                deliver(callback, false, "SystemUI was unchanged but Natro could not persist disabled state; previous preferences restored=" + preferences.saveVerifiedSystemStatusBarContentState(z2, set));
                return;
            }
        }
        if (z) {
            slotResolutionSuccess = resolveStockSystemSlots(applicationContext);
        } else {
            slotResolutionSuccess = dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution.success(new java.util.LinkedHashSet());
        }
        if (!slotResolutionSuccess.success) {
            deliver(callback, false, slotResolutionSuccess.detail);
            return;
        }
        java.util.Set<java.lang.String> setAndroidPDefaults = dezz.status.widget.systemui.SystemStatusBarContentState.androidPDefaults();
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead direct = readDirect(applicationContext);
        if (!direct.success) {
            applyWithShell(applicationContext, preferences, slotResolutionSuccess.slots, setAndroidPDefaults, z, null, null, false, callback);
            return;
        }
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution slotResolution = slotResolutionSuccess;
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(direct.value)) {
            deliver(callback, false, "Existing SystemUI icon_blacklist has unsupported exact syntax; unchanged; source=direct, " + dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(direct.value));
            return;
        }
        dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan = dezz.status.widget.systemui.SystemStatusBarContentState.plan(direct.value, setAndroidPDefaults, slotResolution.slots, preferences.systemUiOwnedHiddenSlots.get(), z);
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(plan.desiredRaw)) {
            deliver(callback, false, "Planned SystemUI icon_blacklist is unsupported or too long; unchanged");
            return;
        }
        if (dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(direct.value, plan.desiredRaw)) {
            finishVerified(applicationContext, preferences, z, plan, false, callback);
            return;
        }
        if (!preferences.beginPendingSystemStatusBarContentState(z, direct.value, plan.desiredRaw, plan.storedOwnership)) {
            deliver(callback, false, "Natro could not persist SystemUI recovery state; SystemUI unchanged");
            return;
        }
        if (writeDirect(applicationContext, plan.desiredRaw)) {
            dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead direct2 = readDirect(applicationContext);
            if (direct2.success && dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(direct2.value, plan.desiredRaw)) {
                finishVerified(applicationContext, preferences, z, plan, true, callback);
                return;
            }
        }
        applyWithShell(applicationContext, preferences, slotResolution.slots, setAndroidPDefaults, z, direct.value, plan, true, callback);
    }

    static /* synthetic */ void lambda$applyNow$4(android.content.Context context, boolean z, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, boolean z2, java.lang.String str) {
        if (z2) {
            applyNow(context, z, callback);
        } else {
            deliver(callback, false, str);
        }
    }

    private static dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution resolveStockSystemSlots(android.content.Context context) {
        try {
            android.content.res.Resources resources = context.getResources();
            int identifier = resources.getIdentifier(STATUS_BAR_ICONS_RESOURCE, "array", "android");
            if (identifier == 0) {
                return dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution.failure("android:array/config_statusBarIcons is unavailable; SystemUI unchanged");
            }
            java.lang.String[] stringArray = resources.getStringArray(identifier);
            java.util.LinkedHashSet linkedHashSet = new java.util.LinkedHashSet();
            for (java.lang.String str : stringArray) {
                if (str != null) {
                    if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeSlot(str)) {
                        return dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution.failure("Firmware declared an unsupported SystemUI slot; SystemUI unchanged");
                    }
                    linkedHashSet.add(str);
                }
            }
            if (linkedHashSet.isEmpty()) {
                return dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution.failure("android:array/config_statusBarIcons is empty; SystemUI unchanged");
            }
            linkedHashSet.add("clock");
            return dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution.success(linkedHashSet);
        } catch (java.lang.RuntimeException e) {
            return dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution.failure("Cannot enumerate stock SystemUI slots: " + e.getClass().getSimpleName());
        }
    }

    private static dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead readDirect(android.content.Context context) {
        try {
            return dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead.success(android.provider.Settings.Secure.getString(context.getContentResolver(), ICON_BLACKLIST));
        } catch (java.lang.RuntimeException e) {
            return dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead.failure(e.getClass().getSimpleName());
        }
    }

    private static boolean writeDirect(android.content.Context context, java.lang.String str) {
        try {
            return android.provider.Settings.Secure.putString(context.getContentResolver(), ICON_BLACKLIST, str);
        } catch (java.lang.RuntimeException unused) {
            return false;
        }
    }

    private static void applyWithShell(final android.content.Context context, final dezz.status.widget.Preferences preferences, final java.util.Set<java.lang.String> set, final java.util.Set<java.lang.String> set2, final boolean z, final java.lang.String str, final dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan, final boolean z2, final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        final java.lang.String str2 = "settings --user " + processUserId() + " ";
        final dezz.status.widget.shell.PrivilegedShell privilegedShell = dezz.status.widget.shell.PrivilegedShell.get(context);
        if (plan != null) {
            dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead direct = readDirect(context);
            if (direct.success) {
                int i = dezz.status.widget.systemui.SystemStatusBarContentPolicy.AnonymousClass1.$SwitchMap$dezz$status$widget$systemui$SystemStatusBarContentState$DirectShellPreflight[dezz.status.widget.systemui.SystemStatusBarContentState.directShellPreflight(direct.value, str, plan.desiredRaw).ordinal()];
                if (i == 1) {
                    failPossiblyPending(context, preferences, z2, "Existing SystemUI icon_blacklist changed to unsupported exact syntax; source=direct-preflight, " + dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(direct.value), callback);
                    return;
                }
                if (i == 2) {
                    finishVerified(context, preferences, z, plan, z2, callback);
                    return;
                } else if (i == 3) {
                    failPossiblyPending(context, preferences, z2, "Direct SystemUI write produced an unexpected value; source=direct-preflight, " + dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(direct.value), callback);
                    return;
                } else {
                    writePlanWithShell(context, preferences, z, direct.value, plan, z2, str2, privilegedShell, callback);
                    return;
                }
            }
        }
        privilegedShell.runCommand(shellReadCommand(str2), new dezz.status.widget.shell.PrivilegedShell.CommandCallback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda0
            @Override // dezz.status.widget.shell.PrivilegedShell.CommandCallback
            public final void onResult(java.lang.String str3, java.lang.String str4) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$applyWithShell$5(context, preferences, z2, callback, plan, z, str, set2, set, str2, privilegedShell, str3, str4);
            }
        });
    }

    static /* synthetic */ class AnonymousClass1 {
        static final /* synthetic */ int[] $SwitchMap$dezz$status$widget$systemui$SystemStatusBarContentState$DirectShellPreflight;

        static {
            int[] iArr = new int[dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.values().length];
            $SwitchMap$dezz$status$widget$systemui$SystemStatusBarContentState$DirectShellPreflight = iArr;
            try {
                iArr[dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.UNSAFE.ordinal()] = 1;
            } catch (java.lang.NoSuchFieldError unused) {
            }
            try {
                $SwitchMap$dezz$status$widget$systemui$SystemStatusBarContentState$DirectShellPreflight[dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.ALREADY_DESIRED.ordinal()] = 2;
            } catch (java.lang.NoSuchFieldError unused2) {
            }
            try {
                $SwitchMap$dezz$status$widget$systemui$SystemStatusBarContentState$DirectShellPreflight[dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.CHANGED_BY_OTHER.ordinal()] = 3;
            } catch (java.lang.NoSuchFieldError unused3) {
            }
            try {
                $SwitchMap$dezz$status$widget$systemui$SystemStatusBarContentState$DirectShellPreflight[dezz.status.widget.systemui.SystemStatusBarContentState.DirectShellPreflight.WRITE_PLANNED.ordinal()] = 4;
            } catch (java.lang.NoSuchFieldError unused4) {
            }
        }
    }

    static /* synthetic */ void lambda$applyWithShell$5(android.content.Context context, dezz.status.widget.Preferences preferences, boolean z, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan, boolean z2, java.lang.String str, java.util.Set set, java.util.Set set2, java.lang.String str2, dezz.status.widget.shell.PrivilegedShell privilegedShell, java.lang.String str3, java.lang.String str4) {
        if (str4 != null) {
            failPossiblyPending(context, preferences, z, "Cannot read per-user SystemUI icon_blacklist: " + safeDetail(str4), callback);
            return;
        }
        java.lang.String strNormalizeShellRead = normalizeShellRead(str3);
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(strNormalizeShellRead)) {
            failPossiblyPending(context, preferences, z, "Existing SystemUI icon_blacklist has unsupported exact syntax; unchanged; source=shell, reason=" + shellReadDiagnostic(str3), callback);
            return;
        }
        if (plan != null && dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(strNormalizeShellRead, plan.desiredRaw)) {
            finishVerified(context, preferences, z2, plan, true, callback);
            return;
        }
        if (plan != null) {
            if (!dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(strNormalizeShellRead, str)) {
                failPossiblyPending(context, preferences, true, "Direct SystemUI write produced an unexpected value", callback);
                return;
            }
        } else {
            plan = dezz.status.widget.systemui.SystemStatusBarContentState.plan(strNormalizeShellRead, set, set2, preferences.systemUiOwnedHiddenSlots.get(), z2);
        }
        writePlanWithShell(context, preferences, z2, strNormalizeShellRead, plan, z, str2, privilegedShell, callback);
    }

    private static void writePlanWithShell(final android.content.Context context, final dezz.status.widget.Preferences preferences, final boolean z, java.lang.String str, final dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan, boolean z2, final java.lang.String str2, final dezz.status.widget.shell.PrivilegedShell privilegedShell, final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        java.lang.String str3;
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(plan.desiredRaw)) {
            failPossiblyPending(context, preferences, z2, "Planned SystemUI icon_blacklist is unsupported or too long; unchanged; " + dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(plan.desiredRaw), callback);
            return;
        }
        if (dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(str, plan.desiredRaw)) {
            finishVerified(context, preferences, z, plan, z2, callback);
            return;
        }
        if (!shellSafe(plan.desiredRaw)) {
            failPossiblyPending(context, preferences, z2, "SystemUI icon_blacklist contains unsupported slot characters", callback);
            return;
        }
        if (!z2 && !preferences.beginPendingSystemStatusBarContentState(z, str, plan.desiredRaw, plan.storedOwnership)) {
            deliver(callback, false, "Natro could not persist SystemUI recovery state; SystemUI unchanged");
            return;
        }
        if (plan.desiredRaw == null) {
            str3 = str2 + "delete secure icon_blacklist";
        } else {
            str3 = str2 + "put secure icon_blacklist '" + plan.desiredRaw + "'";
        }
        privilegedShell.runCommand(str3, new dezz.status.widget.shell.PrivilegedShell.CommandCallback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda6
            @Override // dezz.status.widget.shell.PrivilegedShell.CommandCallback
            public final void onResult(java.lang.String str4, java.lang.String str5) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$writePlanWithShell$7(context, preferences, callback, plan, z, privilegedShell, str2, str4, str5);
            }
        });
    }

    static /* synthetic */ void lambda$writePlanWithShell$7(final android.content.Context context, final dezz.status.widget.Preferences preferences, final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, final dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan, final boolean z, dezz.status.widget.shell.PrivilegedShell privilegedShell, java.lang.String str, java.lang.String str2, java.lang.String str3) {
        if (str3 != null) {
            failPossiblyPending(context, preferences, true, "Cannot write per-user SystemUI icon_blacklist: " + safeDetail(str3), callback);
            return;
        }
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead direct = readDirect(context);
        if (direct.success) {
            if (!dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(direct.value, plan.desiredRaw)) {
                failPossiblyPending(context, preferences, true, "SystemUI rejected icon_blacklist; source=direct-readback, " + dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(direct.value), callback);
                return;
            } else {
                finishVerified(context, preferences, z, plan, true, callback);
                return;
            }
        }
        privilegedShell.runCommand(shellReadCommand(str), new dezz.status.widget.shell.PrivilegedShell.CommandCallback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda9
            @Override // dezz.status.widget.shell.PrivilegedShell.CommandCallback
            public final void onResult(java.lang.String str4, java.lang.String str5) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$writePlanWithShell$6(context, preferences, callback, plan, z, str4, str5);
            }
        });
    }

    static /* synthetic */ void lambda$writePlanWithShell$6(android.content.Context context, dezz.status.widget.Preferences preferences, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan, boolean z, java.lang.String str, java.lang.String str2) {
        java.lang.String strRawDiagnostic;
        if (str2 != null) {
            failPossiblyPending(context, preferences, true, "Cannot verify SystemUI icon_blacklist: " + safeDetail(str2), callback);
            return;
        }
        java.lang.String strNormalizeShellRead = normalizeShellRead(str);
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(strNormalizeShellRead, plan.desiredRaw)) {
            if (dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(strNormalizeShellRead)) {
                strRawDiagnostic = dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(strNormalizeShellRead);
            } else {
                strRawDiagnostic = "reason=" + shellReadDiagnostic(str);
            }
            failPossiblyPending(context, preferences, true, "SystemUI rejected icon_blacklist; source=shell-readback, " + strRawDiagnostic, callback);
            return;
        }
        finishVerified(context, preferences, z, plan, true, callback);
    }

    private static void finishVerified(android.content.Context context, dezz.status.widget.Preferences preferences, boolean z, dezz.status.widget.systemui.SystemStatusBarContentState.Plan plan, boolean z2, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        boolean zSaveVerifiedSystemStatusBarContentState;
        boolean z3 = preferences.systemUiHideStockContentGlobally.get();
        java.util.Set<java.lang.String> set = preferences.systemUiOwnedHiddenSlots.get();
        dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState = z2 ? preferences.pendingSystemStatusBarContentState() : null;
        if (z2) {
            zSaveVerifiedSystemStatusBarContentState = preferences.completePendingSystemStatusBarContentState(z, plan.storedOwnership);
        } else {
            zSaveVerifiedSystemStatusBarContentState = preferences.saveVerifiedSystemStatusBarContentState(z, plan.storedOwnership);
        }
        if (zSaveVerifiedSystemStatusBarContentState) {
            deliver(callback, true, "SystemUI setting applied and verified");
        } else {
            if (z2) {
                if (pendingSystemStatusBarContentState != null) {
                    preferences.restorePendingSystemStatusBarContentState(z3, set, pendingSystemStatusBarContentState);
                }
                failPossiblyPending(context, preferences, true, "Natro could not commit verified policy state", callback);
                return;
            }
            deliver(callback, false, "SystemUI was unchanged but Natro could not persist policy state; previous preferences restored=" + preferences.saveVerifiedSystemStatusBarContentState(z3, set));
        }
    }

    private static void failPossiblyPending(android.content.Context context, dezz.status.widget.Preferences preferences, boolean z, final java.lang.String str, final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        if (!z) {
            deliver(callback, false, str);
        } else {
            recoverPendingTransaction(context, preferences, new dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda7
                @Override // dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback
                public final void onComplete(boolean z2, java.lang.String str2) {
                    dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$failPossiblyPending$8(callback, str, z2, str2);
                }
            });
        }
    }

    static /* synthetic */ void lambda$failPossiblyPending$8(dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, java.lang.String str, boolean z, java.lang.String str2) {
        java.lang.String str3;
        if (z) {
            str3 = str + "; SystemUI rolled back";
        } else {
            str3 = str + "; recovery remains pending: " + str2;
        }
        deliver(callback, false, str3);
    }

    private static void recoverPendingTransaction(android.content.Context context, dezz.status.widget.Preferences preferences, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState = preferences.pendingSystemStatusBarContentState();
        if (pendingSystemStatusBarContentState == null) {
            deliver(callback, true, "No pending SystemUI recovery");
            return;
        }
        if (!isSafePendingState(pendingSystemStatusBarContentState)) {
            deliver(callback, false, "Pending SystemUI recovery record is invalid; SystemUI unchanged");
            return;
        }
        dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead direct = readDirect(context);
        if (direct.success) {
            if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(direct.value)) {
                deliver(callback, false, "Current SystemUI value is unsafe; pending recovery retained");
                return;
            }
            java.lang.String strPendingRollbackRaw = dezz.status.widget.systemui.SystemStatusBarContentState.pendingRollbackRaw(direct.value, dezz.status.widget.systemui.SystemStatusBarContentState.androidPDefaults(), pendingSystemStatusBarContentState.rollbackRaw, pendingSystemStatusBarContentState.desiredRaw);
            if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(strPendingRollbackRaw)) {
                deliver(callback, false, "Pending SystemUI rollback is too long or unsafe; recovery retained");
                return;
            }
            if (dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(direct.value, strPendingRollbackRaw)) {
                finishPendingRollback(preferences, pendingSystemStatusBarContentState, callback);
                return;
            } else if (writeDirect(context, strPendingRollbackRaw)) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead direct2 = readDirect(context);
                if (direct2.success && dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(direct2.value, strPendingRollbackRaw)) {
                    finishPendingRollback(preferences, pendingSystemStatusBarContentState, callback);
                    return;
                }
            }
        }
        recoverPendingWithShell(context, preferences, pendingSystemStatusBarContentState, callback);
    }

    private static void recoverPendingWithShell(android.content.Context context, final dezz.status.widget.Preferences preferences, final dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState, final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        final java.lang.String str = "settings --user " + processUserId() + " ";
        final dezz.status.widget.shell.PrivilegedShell privilegedShell = dezz.status.widget.shell.PrivilegedShell.get(context);
        privilegedShell.runCommand(shellReadCommand(str), new dezz.status.widget.shell.PrivilegedShell.CommandCallback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda4
            @Override // dezz.status.widget.shell.PrivilegedShell.CommandCallback
            public final void onResult(java.lang.String str2, java.lang.String str3) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$recoverPendingWithShell$11(callback, pendingSystemStatusBarContentState, preferences, str, privilegedShell, str2, str3);
            }
        });
    }

    static /* synthetic */ void lambda$recoverPendingWithShell$11(final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, final dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState, final dezz.status.widget.Preferences preferences, final java.lang.String str, final dezz.status.widget.shell.PrivilegedShell privilegedShell, java.lang.String str2, java.lang.String str3) {
        java.lang.String str4;
        if (str3 != null) {
            deliver(callback, false, "Pending SystemUI rollback read failed: " + safeDetail(str3));
            return;
        }
        java.lang.String strNormalizeShellRead = normalizeShellRead(str2);
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(strNormalizeShellRead)) {
            deliver(callback, false, "Pending SystemUI rollback read was invalid; recovery retained");
            return;
        }
        final java.lang.String strPendingRollbackRaw = dezz.status.widget.systemui.SystemStatusBarContentState.pendingRollbackRaw(strNormalizeShellRead, dezz.status.widget.systemui.SystemStatusBarContentState.androidPDefaults(), pendingSystemStatusBarContentState.rollbackRaw, pendingSystemStatusBarContentState.desiredRaw);
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(strPendingRollbackRaw)) {
            deliver(callback, false, "Pending SystemUI rollback is too long or unsafe; recovery retained");
            return;
        }
        if (dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(strNormalizeShellRead, strPendingRollbackRaw)) {
            finishPendingRollback(preferences, pendingSystemStatusBarContentState, callback);
            return;
        }
        if (strPendingRollbackRaw == null) {
            str4 = str + "delete secure icon_blacklist";
        } else {
            str4 = str + "put secure icon_blacklist '" + strPendingRollbackRaw + "'";
        }
        privilegedShell.runCommand(str4, new dezz.status.widget.shell.PrivilegedShell.CommandCallback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda2
            @Override // dezz.status.widget.shell.PrivilegedShell.CommandCallback
            public final void onResult(java.lang.String str5, java.lang.String str6) {
                dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$recoverPendingWithShell$10(callback, privilegedShell, str, strPendingRollbackRaw, preferences, pendingSystemStatusBarContentState, str5, str6);
            }
        });
    }

    static /* synthetic */ void lambda$recoverPendingWithShell$10(final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, dezz.status.widget.shell.PrivilegedShell privilegedShell, java.lang.String str, final java.lang.String str2, final dezz.status.widget.Preferences preferences, final dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState, java.lang.String str3, java.lang.String str4) {
        if (str4 != null) {
            deliver(callback, false, "Pending SystemUI rollback failed: " + safeDetail(str4));
        } else {
            privilegedShell.runCommand(shellReadCommand(str), new dezz.status.widget.shell.PrivilegedShell.CommandCallback() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda3
                @Override // dezz.status.widget.shell.PrivilegedShell.CommandCallback
                public final void onResult(java.lang.String str5, java.lang.String str6) {
                    dezz.status.widget.systemui.SystemStatusBarContentPolicy.lambda$recoverPendingWithShell$9(str2, callback, preferences, pendingSystemStatusBarContentState, str5, str6);
                }
            });
        }
    }

    static /* synthetic */ void lambda$recoverPendingWithShell$9(java.lang.String str, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, dezz.status.widget.Preferences preferences, dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState, java.lang.String str2, java.lang.String str3) {
        if (str3 != null || !dezz.status.widget.systemui.SystemStatusBarContentState.readBackMatches(normalizeShellRead(str2), str)) {
            deliver(callback, false, "Pending SystemUI rollback could not be verified");
        } else {
            finishPendingRollback(preferences, pendingSystemStatusBarContentState, callback);
        }
    }

    private static void finishPendingRollback(dezz.status.widget.Preferences preferences, dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
        if (preferences.clearPendingSystemStatusBarContentState()) {
            deliver(callback, true, "Pending SystemUI transaction rolled back");
        } else {
            preferences.restorePendingSystemStatusBarContentState(preferences.systemUiHideStockContentGlobally.get(), preferences.systemUiOwnedHiddenSlots.get(), pendingSystemStatusBarContentState);
            deliver(callback, false, "SystemUI was restored but Natro could not persist recovery completion");
        }
    }

    private static boolean isSafePendingState(dezz.status.widget.Preferences.PendingSystemStatusBarContentState pendingSystemStatusBarContentState) {
        if (!dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(pendingSystemStatusBarContentState.rollbackRaw) || !dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(pendingSystemStatusBarContentState.desiredRaw)) {
            return false;
        }
        for (java.lang.String str : pendingSystemStatusBarContentState.ownedSlots) {
            if (!"__natro_original_icon_blacklist_null__".equals(str) && !dezz.status.widget.systemui.SystemStatusBarContentState.isSafeSlot(str)) {
                return false;
            }
        }
        return true;
    }

    private static void deliver(final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback, final boolean z, java.lang.String str) {
        if (callback == null) {
            return;
        }
        final java.lang.String strSafeDetail = safeDetail(str);
        runOnMain(new java.lang.Runnable() { // from class: dezz.status.widget.systemui.SystemStatusBarContentPolicy$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                callback.onComplete(z, strSafeDetail);
            }
        });
    }

    private static void runOnMain(java.lang.Runnable runnable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run();
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
        }
    }

    private static int processUserId() {
        return android.os.Process.myUid() / PER_USER_UID_RANGE;
    }

    static java.lang.String normalizeShellRead(java.lang.String str) {
        if (str == null) {
            return INVALID_SHELL_OUTPUT;
        }
        int iIndexOf = str.indexOf(SHELL_VALUE_BEGIN);
        int iIndexOf2 = str.indexOf(SHELL_VALUE_META);
        int iIndexOf3 = str.indexOf(SHELL_VALUE_END);
        if (iIndexOf < 0 || iIndexOf2 < SHELL_VALUE_BEGIN.length() + iIndexOf || iIndexOf3 < iIndexOf2 || iIndexOf != str.lastIndexOf(SHELL_VALUE_BEGIN) || iIndexOf2 != str.lastIndexOf(SHELL_VALUE_META) || iIndexOf3 != str.lastIndexOf(SHELL_VALUE_END)) {
            return INVALID_SHELL_OUTPUT;
        }
        java.lang.String strSubstring = str.substring(SHELL_VALUE_META.length() + iIndexOf2, iIndexOf3);
        if (!strSubstring.matches("0:0:(?:0:1|1:0)")) {
            return INVALID_SHELL_OUTPUT;
        }
        java.lang.String strSubstring2 = str.substring(iIndexOf + SHELL_VALUE_BEGIN.length(), iIndexOf2);
        if (!strSubstring2.endsWith("\n")) {
            return INVALID_SHELL_OUTPUT;
        }
        java.lang.String strSubstring3 = strSubstring2.substring(0, strSubstring2.length() - 1);
        if (strSubstring.endsWith(":0")) {
            return null;
        }
        return strSubstring3;
    }

    static java.lang.String shellReadDiagnostic(java.lang.String str) {
        if (str == null) {
            return "output-null";
        }
        int iIndexOf = str.indexOf(SHELL_VALUE_BEGIN);
        int iIndexOf2 = str.indexOf(SHELL_VALUE_META);
        int iIndexOf3 = str.indexOf(SHELL_VALUE_END);
        if (iIndexOf < 0) {
            return "begin-marker-missing,length=" + str.length();
        }
        if (iIndexOf2 < SHELL_VALUE_BEGIN.length() + iIndexOf) {
            return "meta-marker-missing-or-out-of-order,length=" + str.length();
        }
        if (iIndexOf3 < iIndexOf2) {
            return "end-marker-missing-or-out-of-order,length=" + str.length();
        }
        if (iIndexOf != str.lastIndexOf(SHELL_VALUE_BEGIN) || iIndexOf2 != str.lastIndexOf(SHELL_VALUE_META) || iIndexOf3 != str.lastIndexOf(SHELL_VALUE_END)) {
            return "ambiguous-markers,length=" + str.length();
        }
        java.lang.String strSubstring = str.substring(SHELL_VALUE_META.length() + iIndexOf2, iIndexOf3);
        if (!strSubstring.matches("0:0:(?:0:1|1:0)")) {
            if (strSubstring.matches("[0-9]{1,3}:[0-9]{1,3}:[0-9]{1,3}:[01]")) {
                return "remote-status-unsupported,status=" + strSubstring;
            }
            return "remote-status-malformed,length=" + strSubstring.length() + ",fingerprint=" + java.lang.Integer.toHexString(strSubstring.hashCode());
        }
        java.lang.String strSubstring2 = str.substring(iIndexOf + SHELL_VALUE_BEGIN.length(), iIndexOf2);
        if (!strSubstring2.endsWith("\n")) {
            return "cli-newline-missing";
        }
        return "value-" + dezz.status.widget.systemui.SystemStatusBarContentState.rawDiagnostic(strSubstring2.substring(0, strSubstring2.length() - 1));
    }

    private static boolean shellSafe(java.lang.String str) {
        return dezz.status.widget.systemui.SystemStatusBarContentState.isSafeExplicitRaw(str);
    }

    static java.lang.String shellReadCommand(java.lang.String str) {
        return "natro_icon_list=$(" + str + "list secure 2>/dev/null); natro_list_status=$?; printf '%s\\n' \"$natro_icon_list\" | grep -q '^icon_blacklist='; natro_grep_status=$?; if [ \"$natro_grep_status\" -eq 0 ]; then natro_icon_present=1; else natro_icon_present=0; fi; printf ':__NATRO_ICON_BLACKLIST_BEGIN__:'; " + str + "get secure icon_blacklist 2>/dev/null; natro_get_status=$?; printf ':__NATRO_ICON_BLACKLIST_META__:%s:%s:%s:%s:__NATRO_ICON_BLACKLIST_END__:' \"$natro_get_status\" \"$natro_list_status\" \"$natro_grep_status\" \"$natro_icon_present\"";
    }

    private static java.lang.String safeDetail(java.lang.String str) {
        if (str == null) {
            return "unknown error";
        }
        java.lang.StringBuilder sb = new java.lang.StringBuilder(java.lang.Math.min(str.length(), 160));
        int i = 0;
        boolean z = false;
        while (i < str.length() && sb.length() < 160) {
            char cCharAt = str.charAt(i);
            boolean z2 = java.lang.Character.isWhitespace(cCharAt) || java.lang.Character.isISOControl(cCharAt);
            if (z2) {
                if (!z && sb.length() > 0) {
                    sb.append(' ');
                }
            } else {
                sb.append(cCharAt);
            }
            i++;
            z = z2;
        }
        int length = sb.length();
        while (length > 0 && sb.charAt(length - 1) == ' ') {
            length--;
        }
        java.lang.String strSubstring = sb.substring(0, length);
        return strSubstring.isEmpty() ? "unknown error" : strSubstring;
    }

    static final class PendingRequest {
        final dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback;
        final android.content.Context context;
        final boolean enabled;
        final boolean resetRequest;

        PendingRequest(android.content.Context context, boolean z, boolean z2, dezz.status.widget.systemui.SystemStatusBarContentPolicy.Callback callback) {
            this.context = context;
            this.enabled = z;
            this.resetRequest = z2;
            this.callback = callback;
        }
    }

    static final class DirectRead {
        final java.lang.String detail;
        final boolean success;
        final java.lang.String value;

        private DirectRead(boolean z, java.lang.String str, java.lang.String str2) {
            this.success = z;
            this.value = str;
            this.detail = str2;
        }

        static dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead success(java.lang.String str) {
            return new dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead(true, str, "");
        }

        static dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead failure(java.lang.String str) {
            return new dezz.status.widget.systemui.SystemStatusBarContentPolicy.DirectRead(false, null, str);
        }
    }

    private static final class SlotResolution {
        final java.lang.String detail;
        final java.util.Set<java.lang.String> slots;
        final boolean success;

        private SlotResolution(boolean z, java.util.Set<java.lang.String> set, java.lang.String str) {
            this.success = z;
            this.slots = set;
            this.detail = str;
        }

        static dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution success(java.util.Set<java.lang.String> set) {
            return new dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution(true, new java.util.LinkedHashSet(set), "");
        }

        static dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution failure(java.lang.String str) {
            return new dezz.status.widget.systemui.SystemStatusBarContentPolicy.SlotResolution(false, new java.util.LinkedHashSet(), str);
        }
    }
}
