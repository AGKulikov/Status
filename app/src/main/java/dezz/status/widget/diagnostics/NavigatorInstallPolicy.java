/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

/** Pure release/attempt rules shared by the installer and its regression tests. */
public final class NavigatorInstallPolicy {
    public static final String PACKAGE = "ru.yandex.yandexnavi";
    public static final String APK_SHA256 =
            "0a7cf06f5adeadfd0ab1b4340e941b6df5e44595653920b41016447f6a19e6bc";
    public static final long APK_BYTES = 155133347L;
    public static final String CERT_SHA256 =
            "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75";
    public static final String IDLE = "idle", READING = "reading", READY = "ready",
            BLOCKED = "blocked", WRITING = "writing", COMMITTED = "committed",
            USER_ACTION = "user_action", WAITING = "waiting", DONE = "done",
            LOCAL_ERROR = "local_error", UNKNOWN = "unknown";

    private NavigatorInstallPolicy() { }

    public static boolean exactArtifact(long bytes, String sha256) {
        return bytes == APK_BYTES && APK_SHA256.equals(sha256);
    }

    public static boolean pending(String phase) {
        return WRITING.equals(phase) || COMMITTED.equals(phase)
                || USER_ACTION.equals(phase) || WAITING.equals(phase);
    }

    public static boolean canSelect(String phase) {
        return !READING.equals(phase) && !pending(phase);
    }

    public static boolean acceptsResult(String phase, int expectedSession, String expectedNonce,
                                        int session, String nonce, int status) {
        return (COMMITTED.equals(phase) || USER_ACTION.equals(phase) || WAITING.equals(phase)
                || (UNKNOWN.equals(phase) && status >= 0))
                && expectedSession >= 0 && expectedSession == session
                && expectedNonce != null && !expectedNonce.isEmpty()
                && expectedNonce.equals(nonce) && status >= -1;
    }

    public static String statusText(int status) {
        switch (status) {
            case -1: return "Android запрашивает подтверждение установки.";
            case 0: return "Android сообщил: Навигатор установлен.";
            case 1: return "Установка не выполнена. Подробная причина — в отчёте ниже.";
            case 2: return "Установка заблокирована системой. Причина — в отчёте ниже.";
            case 3: return "Установка отменена.";
            case 4: return "Android отклонил APK как недопустимый. Причина — в отчёте ниже.";
            case 5: return "Android сообщил о конфликте приложений. Причина — в отчёте ниже.";
            case 6: return "Android сообщил о проблеме хранилища. Причина — в отчёте ниже.";
            case 7: return "Android сообщил о несовместимости. Причина — в отчёте ниже.";
            default: return "Получен ответ Android: " + status + ". Подробности — в отчёте ниже.";
        }
    }
}
