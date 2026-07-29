package dezz.status.hudlab;

import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* loaded from: classes4.dex */
final class HudQnxTimeGapInstaller implements Closeable {
    private static final String APPS_DEVICE = "/dev/disk/uda0.713CFB34-9488-44D9-9382-401F21CCCAB3.33";
    private static final String BACKUP = "/apps/cluster/FX11_HEV/bin/hmi/GLY_KX11_A2/hud/hud.kzb.hudlab-original";
    private static final String FACTORY_MD5 = "6bade26a702dbd227036a7cabc60ca2b";
    private static final String PATCHED_MD5 = "29846744b982b35529713bc3f161eb04";
    private static final String PATCH_BLOCK = "/shared/.hudlab-timegap.block";
    private static final int PATCH_UPLOAD_CHUNK = 256;
    private static final int QNX_PORT = 23;
    private static final String TARGET = "/apps/cluster/FX11_HEV/bin/hmi/GLY_KX11_A2/hud/hud.kzb";
    private static final String WORK = "/shared/.hudlab-hud.kzb.new";
    private volatile boolean closed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() { // from class: dezz.status.hudlab.HudQnxTimeGapInstaller.1
        @Override // java.util.concurrent.ThreadFactory
        public final Thread newThread(Runnable runnable) {
            return HudQnxTimeGapInstaller.lambda$new$0(runnable);
        }
    });
    private static final String[] QNX_HOSTS = {"192.168.118.2", "198.18.34.2"};
    private static final Pattern MD5 = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])");

    enum Action {
        INSPECT,
        INSTALL,
        RESTORE
    }

    interface Listener {
        void onFinished(boolean z, String str);

        void onProgress(String str);
    }

    private static final class QnxShell implements Closeable {
        private static final int DONT = 254;
        private static final int IAC = 255;
        private static final int WILL = 251;
        private static final int WONT = 252;
        private static final int f10SB = 250;
        private static final int f11SE = 240;
        private static final int f9DO = 253;
        private final InputStream input;
        private int markerCounter;
        private final OutputStream output;
        private final Socket socket;

        static final class Result {
            final int exitCode;
            final String output;

            Result(int i, String str) {
                this.exitCode = i;
                this.output = str;
            }
        }

        private QnxShell(String str) throws Exception {
            Socket socket = new Socket();
            this.socket = socket;
            try {
                socket.connect(new InetSocketAddress(str, 23), 2500);
                socket.setSoTimeout(750);
                this.input = socket.getInputStream();
                this.output = socket.getOutputStream();
                authenticate();
            } catch (Throwable th) {
                try {
                    this.socket.close();
                } catch (Throwable th2) {
                }
                throw th;
            }
        }

        private void authenticate() throws Exception {
            String untilAny = readUntilAny(new String[]{"login:", "Login:", "# ", "$ ", "\n#", "\n$"}, 8000);
            if (hasPrompt(untilAny)) {
                return;
            }
            if (untilAny.toLowerCase(Locale.ROOT).contains("login:")) {
                sendLine("root");
                String untilAny2 = readUntilAny(new String[]{"password:", "Password:", "# ", "$ ", "\n#", "\n$", "login:"}, 6000);
                if (hasPrompt(untilAny2)) {
                    return;
                }
                if (untilAny2.toLowerCase(Locale.ROOT).contains("password:")) {
                    sendLine(loginSecret());
                    String untilAny3 = readUntilAny(new String[]{"# ", "$ ", "\n#", "\n$", "login:", "incorrect", "denied"}, 7000);
                    String lowerCase = untilAny3.toLowerCase(Locale.ROOT);
                    if (!hasPrompt(untilAny3) || lowerCase.contains("incorrect") || lowerCase.contains("denied") || lowerCase.contains("login:")) {
                        throw new IOException("QNX Telnet отклонил авторизацию");
                    }
                    return;
                }
                throw new IOException("QNX Telnet не выдал password prompt");
            }
            throw new IOException("QNX Telnet не выдал login prompt");
        }

        static QnxShell connect() throws Exception {
            Throwable th = null;
            for (String str : HudQnxTimeGapInstaller.QNX_HOSTS) {
                try {
                    return new QnxShell(str);
                } catch (Throwable th2) {
                    th = th2;
                }
            }
            throw new IOException("QNX Telnet 23 недоступен на штатных адресах", th);
        }

        private void handleIac() throws Exception {
            int i = this.input.read();
            if (i < 0 || i == 255) {
                return;
            }
            int i2 = -1;
            if (i != 250) {
                int i3 = this.input.read();
                if (i3 < 0) {
                    return;
                }
                if (i == 251) {
                    this.output.write(new byte[]{-1, -2, (byte) i3});
                    this.output.flush();
                    return;
                } else {
                    if (i == 253) {
                        this.output.write(new byte[]{-1, -4, (byte) i3});
                        this.output.flush();
                        return;
                    }
                    return;
                }
            }
            while (true) {
                int i4 = this.input.read();
                if (i4 < 0) {
                    return;
                }
                if (i2 == 255 && i4 == 240) {
                    return;
                } else {
                    i2 = i4;
                }
            }
        }

        private static boolean hasPrompt(String str) {
            String strReplace = str.replace("\r", "");
            return strReplace.contains("# ") || strReplace.contains("$ ") || strReplace.endsWith("#") || strReplace.endsWith("$") || strReplace.contains("\n#") || strReplace.contains("\n$");
        }

        private static String loginSecret() {
            int[] iArr = {60, 29, 50, 110, 62, 59, 54, 44, 18, 42, 110, 47, 56, 55, 56, 104};
            char[] cArr = new char[16];
            for (int i = 0; i < 16; i++) {
                cArr[i] = (char) (iArr[i] ^ 90);
            }
            return new String(cArr);
        }

        private String readFor(int i) throws Exception {
            int i2;
            long jCurrentTimeMillis = System.currentTimeMillis() + i;
            StringBuilder sb = new StringBuilder();
            while (System.currentTimeMillis() < jCurrentTimeMillis) {
                try {
                    i2 = this.input.read();
                } catch (SocketTimeoutException e) {
                }
                if (i2 < 0) {
                    break;
                }
                if (i2 == 255) {
                    handleIac();
                } else if (i2 != 0 && i2 != 13) {
                    sb.append((char) i2);
                }
            }
            return sb.toString();
        }

        private String readUntil(String str, int i) throws Exception {
            return readUntilAny(new String[]{str}, i);
        }

        private String readUntilAny(String[] strArr, int i) throws Exception {
            int i2;
            long jCurrentTimeMillis = System.currentTimeMillis() + i;
            StringBuilder sb = new StringBuilder();
            while (System.currentTimeMillis() < jCurrentTimeMillis) {
                try {
                    i2 = this.input.read();
                } catch (SocketTimeoutException e) {
                }
                if (i2 < 0) {
                    throw new IOException("QNX закрыл Telnet");
                }
                if (i2 == 255) {
                    handleIac();
                } else if (i2 != 0 && i2 != 13) {
                    sb.append((char) i2);
                    String string = sb.toString();
                    String lowerCase = string.toLowerCase(Locale.ROOT);
                    for (String str : strArr) {
                        if (string.contains(str) || lowerCase.contains(str.toLowerCase(Locale.ROOT))) {
                            return string;
                        }
                    }
                }
            }
            throw new IOException("тайм-аут ответа QNX");
        }

        private void sendLine(String str) throws Exception {
            this.output.write((str + "\n").getBytes(StandardCharsets.US_ASCII));
            this.output.flush();
        }

        private static String tail(String str) {
            String strTrim = str.trim();
            int iLastIndexOf = strTrim.lastIndexOf(10);
            if (iLastIndexOf >= 0) {
                strTrim = strTrim.substring(iLastIndexOf + 1);
            }
            return strTrim.length() <= 220 ? strTrim : strTrim.substring(strTrim.length() - 220);
        }

        @Override // java.io.Closeable, java.lang.AutoCloseable
        public void close() {
            try {
                this.socket.close();
            } catch (Throwable th) {
            }
        }

        Result exec(String str, int i) throws Exception {
            int i2 = this.markerCounter + 1;
            this.markerCounter = i2;
            String str2 = "__HUDLAB_DONE_" + i2 + "__";
            sendLine(str + "; _hl_rc=$?; echo __HUDLAB_''DONE_" + i2 + "__:${_hl_rc}");
            String str3 = readUntil(str2, i) + readFor(450);
            Matcher matcher = Pattern.compile(Pattern.quote(str2) + ":(\\d+)").matcher(str3);
            if (matcher.find()) {
                return new Result(Integer.parseInt(matcher.group(1)), str3.substring(0, matcher.start()).trim());
            }
            throw new IOException("QNX-команда завершилась без маркера результата");
        }

        Result require(String str, int i) throws Exception {
            Result resultExec = exec(str, i);
            if (resultExec.exitCode != 0) {
                throw new IOException("QNX rc=" + resultExec.exitCode + (resultExec.output.isEmpty() ? "" : " · " + tail(resultExec.output)));
            }
            return resultExec;
        }

        void sendWithoutWait(String str) throws Exception {
            sendLine(str);
        }
    }

    HudQnxTimeGapInstaller() {
    }

    private static String backupSummary(String str) {
        return str == null ? " Резервной копии пока нет." : FACTORY_MD5.equals(str) ? " Заводская резервная копия подтверждена." : " ВНИМАНИЕ: резервная копия неизвестна (" + str + ").";
    }

    private void finish(final Listener listener, final boolean z, final String str) {
        if (this.closed) {
            return;
        }
        this.main.post(new Runnable() { // from class: dezz.status.hudlab.HudQnxTimeGapInstaller.2
            @Override // java.lang.Runnable
            public final void run() {
                listener.onFinished(z, str);
            }
        });
    }

    private void inspect(QnxShell qnxShell, Listener listener) throws Exception {
        String strRemoteMd5 = remoteMd5(qnxShell, TARGET);
        String strRemoteMd5IfPresent = remoteMd5IfPresent(qnxShell, BACKUP);
        if (FACTORY_MD5.equals(strRemoteMd5)) {
            finish(listener, true, "QNX: заводской hud.kzb подтверждён. Патч машинки не установлен." + backupSummary(strRemoteMd5IfPresent));
        } else if (PATCHED_MD5.equals(strRemoteMd5)) {
            finish(listener, true, "QNX: точечный патч машинки уже установлен." + backupSummary(strRemoteMd5IfPresent));
        } else {
            finish(listener, false, "QNX: неизвестная версия hud.kzb (" + strRemoteMd5 + "). Запись заблокирована." + backupSummary(strRemoteMd5IfPresent));
        }
    }

    private void install(QnxShell r14, Listener r15) throws Exception {
        throw new UnsupportedOperationException("Method not decompiled: dezz.status.hudlab.HudQnxTimeGapInstaller.install(dezz.status.hudlab.HudQnxTimeGapInstaller$QnxShell, dezz.status.hudlab.HudQnxTimeGapInstaller$Listener):void");
    }

    static Thread lambda$new$0(Runnable runnable) {
        Thread thread = new Thread(runnable, "hud-qnx-timegap");
        thread.setDaemon(true);
        return thread;
    }

    public void lambda$run$1(Listener listener, Action action) {
        if (this.closed) {
            return;
        }
        try {
            QnxShell qnxShellConnect = QnxShell.connect();
            progress(listener, "QNX подключён. Проверяю штатный hud.kzb…");
            int iOrdinal = action.ordinal();
            if (iOrdinal == 0) {
                inspect(qnxShellConnect, listener);
            } else if (iOrdinal == 1) {
                install(qnxShellConnect, listener);
            } else {
                if (iOrdinal != 2) {
                    throw new IllegalStateException(String.valueOf(action));
                }
                restore(qnxShellConnect, listener);
            }
            if (qnxShellConnect != null) {
                qnxShellConnect.close();
            }
        } catch (Throwable th) {
            finish(listener, false, "QNX: ERROR · " + shortFailure(th));
        }
    }

    private void progress(final Listener listener, final String str) {
        if (this.closed) {
            return;
        }
        this.main.post(new Runnable() { // from class: dezz.status.hudlab.HudQnxTimeGapInstaller.3
            @Override // java.lang.Runnable
            public final void run() {
                listener.onProgress(str);
            }
        });
    }

    private static String remoteMd5(QnxShell qnxShell, String str) throws Exception {
        Matcher matcher = MD5.matcher(qnxShell.require("/bin/md5 " + str, 40000).output);
        if (matcher.find()) {
            return matcher.group().toLowerCase(Locale.ROOT);
        }
        throw new IOException("MD5 не прочитан для " + str);
    }

    private static String remoteMd5IfPresent(QnxShell qnxShell, String str) throws Exception {
        if (qnxShell.exec("test -f " + str, 10000).exitCode == 0) {
            return remoteMd5(qnxShell, str);
        }
        return null;
    }

    private static void remountReadOnly(QnxShell qnxShell) throws Exception {
        qnxShell.require("mount -o remount,ro /dev/disk/uda0.713CFB34-9488-44D9-9382-401F21CCCAB3.33 /apps", 20000);
    }

    private static boolean requestReboot(QnxShell qnxShell) {
        try {
            qnxShell.sendWithoutWait("sync; shutdown -S reboot");
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private void restore(QnxShell r6, Listener r7) throws Exception {
        throw new UnsupportedOperationException("Method not decompiled: dezz.status.hudlab.HudQnxTimeGapInstaller.restore(dezz.status.hudlab.HudQnxTimeGapInstaller$QnxShell, dezz.status.hudlab.HudQnxTimeGapInstaller$Listener):void");
    }

    private static void restoreVerifiedFactory(QnxShell qnxShell) throws Exception {
        qnxShell.require("cp /apps/cluster/FX11_HEV/bin/hmi/GLY_KX11_A2/hud/hud.kzb.hudlab-original /apps/cluster/FX11_HEV/bin/hmi/GLY_KX11_A2/hud/hud.kzb", 40000);
        if (!FACTORY_MD5.equals(remoteMd5(qnxShell, TARGET))) {
            throw new IOException("восстановленный hud.kzb не прошёл контроль MD5");
        }
    }

    private static String shortFailure(Throwable th) {
        String message = th.getMessage();
        return th.getClass().getSimpleName() + ((message == null || message.trim().isEmpty()) ? "" : " · " + message);
    }

    private void uploadPatch(QnxShell qnxShell, byte[] bArr, Listener listener) throws Exception {
        int i;
        qnxShell.require(": > /shared/.hudlab-timegap.block", 10000);
        int length = (bArr.length + 255) / 256;
        for (int i2 = 0; i2 < length; i2++) {
            int i3 = i2 * 256;
            int iMin = Math.min(i3 + 256, bArr.length);
            StringBuilder sb = new StringBuilder((iMin - i3) * 4);
            while (i3 < iMin) {
                byte b = bArr[i3];
                int i4 = b & (-1);
                sb.append('\\').append((char) (((i4 >>> 6) & 7) + 48)).append((char) (((i4 >>> 3) & 7) + 48)).append((char) ((b & 7) + 48));
                i3++;
            }
            qnxShell.require("printf '" + ((Object) sb) + "' >> /shared/.hudlab-timegap.block", 20000);
            if (i2 == 0 || (i = i2 + 1) == length || i % 6 == 0) {
                progress(listener, "Передача точечного блока: " + Math.round(((i2 + 1) * 100.0f) / length) + "%");
            }
        }
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() {
        this.closed = true;
        this.worker.shutdownNow();
    }

    void run(final Action action, final Listener listener) {
        this.worker.execute(new Runnable() { // from class: dezz.status.hudlab.HudQnxTimeGapInstaller.4
            @Override // java.lang.Runnable
            public final void run() {
                HudQnxTimeGapInstaller.this.lambda$run$1(listener, action);
            }
        });
    }
}
