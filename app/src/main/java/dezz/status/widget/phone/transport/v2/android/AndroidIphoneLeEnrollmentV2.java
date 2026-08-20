/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

public final class AndroidIphoneLeEnrollmentV2 implements java.lang.AutoCloseable {
    static final long CANDIDATE_SETTLE_MS = 2000;
    private static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int GATT_INSUFFICIENT_AUTHORIZATION = 8;
    private static final int GATT_INSUFFICIENT_ENCRYPTION = 15;
    private static final int GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE = 12;
    static final int MIN_ENROLLMENT_MTU = 103;
    static final int REQUESTED_MTU = 185;
    public static final long SESSION_TIMEOUT_MS = 60000;
    private final android.bluetooth.BluetoothAdapter adapter;
    private java.util.UUID androidInstallationId;
    private java.lang.Runnable bondPollTask;
    private android.content.BroadcastReceiver bondReceiver;
    private final java.util.Set<java.lang.String> bondedEventAddresses;
    private java.lang.Runnable candidateSettleTask;
    private final java.util.Map<java.lang.String, android.bluetooth.BluetoothDevice> candidates;
    private boolean commitMayHaveReachedHelper;
    private byte[] confirm;
    private final android.content.Context context;
    private boolean createBondAttempted;
    private long deadlineAtElapsedMillis;
    private java.lang.Runnable deadlineTask;
    private java.lang.String detail;
    private final dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.EligibilityGate eligibility;
    private boolean encryptedHReadInFlight;
    private boolean encryptedHRequested;
    private boolean encryptedHVerified;
    private android.bluetooth.BluetoothGattCharacteristic enrollmentCharacteristic;
    private java.security.KeyPair ephemeral;
    private dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind error;
    private byte[] finalCommit;
    private boolean finalCommitSent;
    private android.bluetooth.BluetoothGatt gatt;
    private final android.bluetooth.BluetoothGattCallback gattCallback;
    private boolean gattCallbackObserved;
    private long generation;
    private byte[] hello;
    private java.lang.Runnable helperConfirmPollTask;
    private final dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Listener listener;
    private final android.os.Handler main;
    private android.bluetooth.BluetoothGattCharacteristic peerProofCharacteristic;
    private boolean pendingRecordStaged;
    private byte[] pendingRoutineConfirm;
    private byte[] pendingRoutineHello;
    private dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.RoutineSession pendingRoutineSession;
    private dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase phase;
    private android.bluetooth.BluetoothDevice postBondDevice;
    private final java.util.Set<java.lang.String> preSessionBondAddresses;
    private final dezz.status.widget.Preferences preferences;
    private final java.lang.Object processGateKey;
    private boolean processGateOwned;
    private android.bluetooth.BluetoothDevice provisionalDevice;
    private final java.security.SecureRandom random;
    private final android.bluetooth.le.ScanCallback scanCallback;
    private boolean scanRunning;
    private android.bluetooth.le.BluetoothLeScanner scanner;
    private java.lang.String selectedClassicAddress;
    private dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession session;
    private boolean terminalPublished;

    public interface EligibilityGate {
        boolean exactSelectedClassicHfpActive(java.lang.String str);
    }

    public enum ErrorKind {
        NONE,
        PREREQUISITE,
        TIMEOUT,
        SCAN_FAILED,
        NO_CANDIDATE,
        MULTIPLE_CANDIDATES,
        GATT,
        MTU_UNAVAILABLE,
        PROTOCOL,
        SAS_REJECTED,
        HFP_LOST,
        BOND_FAILED,
        HELPER_ID_CONFLICT,
        PERSISTENCE
    }

    public interface Listener {
        void onEnrollmentState(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Snapshot snapshot);
    }

    public enum Phase {
        IDLE,
        WAITING_FOR_GATT_OWNER,
        SCANNING,
        CONNECTING,
        NEGOTIATING_MTU,
        DISCOVERING,
        SENDING_HELLO,
        READING_RESPONSE,
        WAITING_FOR_SAS_CONFIRMATION,
        SENDING_CONFIRM,
        WAITING_FOR_HELPER_SAS_CONFIRMATION,
        READING_ENCRYPTED_H,
        WAITING_FOR_BOND,
        STAGING_FINAL_COMMIT,
        SENDING_FINAL_COMMIT,
        READING_FINAL_ACK,
        SENDING_PENDING_ROUTINE_HELLO,
        READING_PENDING_ROUTINE_PROOF,
        SENDING_PENDING_ROUTINE_CONFIRM,
        READING_PENDING_ROUTINE_ACK,
        READING_PENDING_ROUTINE_H,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private static boolean isSmpStatus(int i) {
        return i == 5 || i == 8 || i == 12 || i == 15;
    }

    public static final class Snapshot {
        public final java.lang.String detail;
        public final dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind error;
        public final long generation;
        public final dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase phase;
        public final java.lang.String sas;

        public Snapshot(long j, dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase phase, dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind errorKind, java.lang.String str, java.lang.String str2) {
            this.generation = j;
            this.phase = phase;
            this.error = errorKind;
            this.sas = str == null ? "" : str;
            this.detail = str2 == null ? "" : str2;
        }

        public boolean terminal() {
            return this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SUCCEEDED || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.FAILED || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.CANCELLED;
        }
    }

    class AnonymousClass1 extends android.bluetooth.le.ScanCallback {
        AnonymousClass1() {
        }

        private void acceptPostedScanResult(android.bluetooth.le.ScanResult scanResult) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.acceptScanResult(scanResult);
        }

        @Override // android.bluetooth.le.ScanCallback
        public void onScanResult(int i, final android.bluetooth.le.ScanResult scanResult) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> acceptPostedScanResult(scanResult));
        }

        private void acceptPostedBatchResult(android.bluetooth.le.ScanResult scanResult) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.acceptScanResult(scanResult);
        }

        @Override // android.bluetooth.le.ScanCallback
        public void onBatchScanResults(java.util.List<android.bluetooth.le.ScanResult> list) {
            if (list == null) {
                return;
            }
            for (final android.bluetooth.le.ScanResult scanResult : list) {
                dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                        .post(() -> acceptPostedBatchResult(scanResult));
            }
        }

        private void handlePostedScanFailure(int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.SCAN_FAILED, "filtered Helper scan failed (code " + i + ")");
        }

        @Override // android.bluetooth.le.ScanCallback
        public void onScanFailed(final int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> handlePostedScanFailure(i));
        }
    }

    class AnonymousClass2 extends android.bluetooth.BluetoothGattCallback {
        AnonymousClass2() {
        }

        private void handlePostedConnectionState(android.bluetooth.BluetoothGatt bluetoothGatt, int i, int i2) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.handleConnectionState(bluetoothGatt, i, i2);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onConnectionStateChange(final android.bluetooth.BluetoothGatt bluetoothGatt, final int i, final int i2) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> handlePostedConnectionState(bluetoothGatt, i, i2));
        }

        private void handlePostedMtu(android.bluetooth.BluetoothGatt bluetoothGatt, int i, int i2) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.handleMtuChanged(bluetoothGatt, i, i2);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onMtuChanged(final android.bluetooth.BluetoothGatt bluetoothGatt, final int i, final int i2) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> handlePostedMtu(bluetoothGatt, i, i2));
        }

        private void handlePostedServices(android.bluetooth.BluetoothGatt bluetoothGatt, int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.handleServicesDiscovered(bluetoothGatt, i);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onServicesDiscovered(final android.bluetooth.BluetoothGatt bluetoothGatt, final int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> handlePostedServices(bluetoothGatt, i));
        }

        private void handlePostedCharacteristicWrite(android.bluetooth.BluetoothGatt bluetoothGatt, android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic, int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.handleCharacteristicWrite(bluetoothGatt, bluetoothGattCharacteristic, i);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicWrite(final android.bluetooth.BluetoothGatt bluetoothGatt, final android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic, final int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> handlePostedCharacteristicWrite(
                            bluetoothGatt, bluetoothGattCharacteristic, i));
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public void onCharacteristicRead(final android.bluetooth.BluetoothGatt bluetoothGatt, final android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic, final int i) {
            final byte[] bArr = (bluetoothGattCharacteristic == null || bluetoothGattCharacteristic.getValue() == null) ? null : (byte[]) bluetoothGattCharacteristic.getValue().clone();
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                    .post(() -> handlePostedCharacteristicRead(
                            bluetoothGatt, bluetoothGattCharacteristic, bArr, i));
        }

        private void handlePostedCharacteristicRead(android.bluetooth.BluetoothGatt bluetoothGatt, android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bArr, int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.handleCharacteristicRead(bluetoothGatt, bluetoothGattCharacteristic, bArr, i);
        }
    }

    public AndroidIphoneLeEnrollmentV2(android.content.Context context, dezz.status.widget.Preferences preferences, dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.EligibilityGate eligibilityGate, dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Listener listener) {
        this(context, preferences, eligibilityGate, listener, new java.security.SecureRandom());
    }

    AndroidIphoneLeEnrollmentV2(android.content.Context context, dezz.status.widget.Preferences preferences, dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.EligibilityGate eligibilityGate, dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Listener listener, java.security.SecureRandom secureRandom) {
        this.processGateKey = new java.lang.Object();
        this.candidates = new java.util.LinkedHashMap();
        this.preSessionBondAddresses = new java.util.LinkedHashSet();
        this.bondedEventAddresses = new java.util.LinkedHashSet();
        this.phase = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.IDLE;
        this.error = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.NONE;
        this.detail = "";
        this.selectedClassicAddress = "";
        this.scanCallback = new dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.AnonymousClass1();
        this.gattCallback = new dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.AnonymousClass2();
        this.context = context.getApplicationContext();
        this.preferences = preferences;
        this.eligibility = eligibilityGate;
        this.listener = listener;
        this.random = secureRandom;
        this.main = new android.os.Handler(android.os.Looper.getMainLooper());
        this.adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
    }

    public void start(final java.lang.String str, final java.util.UUID uuid) {
        this.main.post(() -> startOnMain(str, uuid));
    }

    private void startOnMain(java.lang.String str, java.util.UUID uuid) {
        android.bluetooth.BluetoothAdapter bluetoothAdapter;
        if (this.phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.IDLE) {
            return;
        }
        dezz.status.widget.phone.transport.v2.android.BlePairBridgeSignal.disarm(this.context);
        this.generation = nonZeroGeneration();
        java.lang.String strCanonicalAddress = canonicalAddress(str);
        this.selectedClassicAddress = strCanonicalAddress;
        this.androidInstallationId = uuid;
        if (strCanonicalAddress.isEmpty() || isZero(uuid) || (bluetoothAdapter = this.adapter) == null || !bluetoothAdapter.isEnabled()) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PREREQUISITE, "Bluetooth, selected Classic iPhone and installation identity required");
            return;
        }
        if (uniqueSelectedClassicBond() == null || !this.eligibility.exactSelectedClassicHfpActive(this.selectedClassicAddress)) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PREREQUISITE, "exact selected Classic bond and active HFP are required");
            return;
        }
        capturePreSessionBonds();
        registerBondReceiver();
        this.deadlineAtElapsedMillis = android.os.SystemClock.elapsedRealtime() + SESSION_TIMEOUT_MS;
        armMonotonicDeadline();
        beginScan();
    }

    public void confirmMatchingSas(final boolean z) {
        this.main.post(() -> confirmMatchingSasOnMain(z));
    }

    private void confirmMatchingSasOnMain(boolean z) {
        if (this.phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_SAS_CONFIRMATION || this.session == null) {
            return;
        }
        if (!z) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.SAS_REJECTED, "user rejected the displayed SAS; encrypted H was not read");
            return;
        }
        if (!eligibleNow()) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.HFP_LOST, "selected Classic HFP was lost before SAS confirmation");
            return;
        }
        try {
            this.confirm = this.session.encodeConfirm();
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_CONFIRM, "sending authenticated SAS confirmation", "");
            writeEnrollment(this.confirm);
        } catch (java.security.GeneralSecurityException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "could not construct enrollment confirmation");
        }
    }

    public dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Snapshot snapshot() {
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession enrollmentSession;
        long j = this.generation;
        dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase phase = this.phase;
        return new dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Snapshot(j, phase, this.error, (phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_SAS_CONFIRMATION || (enrollmentSession = this.session) == null) ? "" : enrollmentSession.sas, this.detail);
    }

    @Override // java.lang.AutoCloseable
    public void close() {
        this.main.post(this::closeOnMain);
    }

    private void closeOnMain() {
        if (!terminal()) {
            this.phase = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.CANCELLED;
            this.error = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.NONE;
            this.detail = "enrollment cancelled; existing binding preserved";
            this.terminalPublished = true;
            this.listener.onEnrollmentState(snapshot());
        }
        cleanup(false);
    }

    private void beginScan() {
        if (terminal()) {
            return;
        }
        android.bluetooth.le.BluetoothLeScanner bluetoothLeScanner = this.adapter.getBluetoothLeScanner();
        this.scanner = bluetoothLeScanner;
        if (bluetoothLeScanner == null) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.SCAN_FAILED, "Bluetooth LE scanner unavailable");
            return;
        }
        try {
            this.scanner.startScan(java.util.Collections.singletonList(new android.bluetooth.le.ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(dezz.status.widget.phone.transport.v2.IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE)).build()), new android.bluetooth.le.ScanSettings.Builder().setScanMode(2).build(), this.scanCallback);
            this.scanRunning = true;
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SCANNING, "foreground F201 scan active; candidates have no authority", "");
        } catch (java.lang.RuntimeException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.SCAN_FAILED, "filtered Helper scan could not start");
        }
    }

    public void acceptScanResult(android.bluetooth.le.ScanResult scanResult) {
        if (this.phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SCANNING || scanResult == null || scanResult.getDevice() == null) {
            return;
        }
        java.lang.String strSafeAddress = safeAddress(scanResult.getDevice());
        if (strSafeAddress.isEmpty()) {
            return;
        }
        this.candidates.put(strSafeAddress, scanResult.getDevice());
        if (this.candidates.size() > 1) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.MULTIPLE_CANDIDATES, "more than one F201 Helper candidate is visible; enrollment refused");
        } else {
            if (this.candidateSettleTask != null) {
                return;
            }
            java.lang.Runnable runnable = this::finishCandidateWindow;
            this.candidateSettleTask = runnable;
            this.main.postDelayed(runnable, CANDIDATE_SETTLE_MS);
        }
    }

    private void finishCandidateWindow() {
        this.candidateSettleTask = null;
        if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SCANNING && this.candidates.size() == 1) {
            this.provisionalDevice = this.candidates.values().iterator().next();
            stopScan();
            waitForProcessGattOwner();
        }
    }

    private void waitForProcessGattOwner() {
        if (terminal()) {
            return;
        }
        transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_GATT_OWNER, "waiting for the previous exact GATT owner to drain", "");
        if (dezz.status.widget.phone.transport.v2.android.ProcessGattRegistrationGateV2.tryAcquire(this.processGateKey)) {
            this.processGateOwned = true;
            connectProvisionalDevice();
        } else {
            dezz.status.widget.phone.transport.v2.android.ProcessGattRegistrationGateV2.whenFree(
                    this.processGateKey, this::onProcessGattGateAcquired);
        }
    }

    private void onProcessGattGateAcquired() {
        this.main.post(this::connectAfterProcessGattGate);
    }

    private void connectAfterProcessGattGate() {
        if (terminal()) {
            return;
        }
        if (!dezz.status.widget.phone.transport.v2.android.ProcessGattRegistrationGateV2.tryAcquire(this.processGateKey)) {
            waitForProcessGattOwner();
        } else {
            this.processGateOwned = true;
            connectProvisionalDevice();
        }
    }

    private void connectProvisionalDevice() {
        if (terminal() || this.provisionalDevice == null || this.gatt != null) {
            if (this.gatt != null) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "second GATT wrapper forbidden");
                return;
            }
            return;
        }
        transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.CONNECTING, "connecting the sole provisional F201 candidate", "");
        try {
            android.bluetooth.BluetoothGatt bluetoothGattConnectGatt = this.provisionalDevice.connectGatt(this.context, false, this.gattCallback, 2);
            this.gatt = bluetoothGattConnectGatt;
            if (bluetoothGattConnectGatt == null) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "connectGatt returned no wrapper");
            }
        } catch (java.lang.RuntimeException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "connectGatt rejected the provisional candidate");
        }
    }

    public void handleConnectionState(android.bluetooth.BluetoothGatt bluetoothGatt, int i, int i2) {
        boolean zRequestMtu;
        if (bluetoothGatt != this.gatt) {
            return;
        }
        this.gattCallbackObserved = true;
        if (terminal()) {
            closeObservedGatt();
            return;
        }
        if (i != 0 || i2 != 2) {
            if (i != 0 || i2 == 0) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "provisional LE link disconnected");
                return;
            }
            return;
        }
        transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.NEGOTIATING_MTU, "requesting an ATT MTU large enough for exact C4 frames", "");
        try {
            zRequestMtu = bluetoothGatt.requestMtu(185);
        } catch (java.lang.RuntimeException unused) {
            zRequestMtu = false;
        }
        if (zRequestMtu) {
            return;
        }
        fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.MTU_UNAVAILABLE, "MTU request rejected before C4 or encrypted H");
    }

    public void handleMtuChanged(android.bluetooth.BluetoothGatt bluetoothGatt, int i, int i2) {
        if (bluetoothGatt == this.gatt && this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.NEGOTIATING_MTU) {
            if (i2 != 0 || i < 103) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.MTU_UNAVAILABLE, "negotiated MTU cannot carry the exact 99-byte enrollment frame");
                return;
            }
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.DISCOVERING, "discovering F201 enrollment and encrypted H", "");
            try {
                if (bluetoothGatt.discoverServices()) {
                    return;
                }
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "GATT service discovery was not accepted");
            } catch (java.lang.RuntimeException unused) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "GATT service discovery failed to start");
            }
        }
    }

    public void handleServicesDiscovered(android.bluetooth.BluetoothGatt bluetoothGatt, int i) {
        if (bluetoothGatt == this.gatt && this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.DISCOVERING) {
            if (i != 0) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "F201 service discovery failed");
                return;
            }
            android.bluetooth.BluetoothGattService service = bluetoothGatt.getService(dezz.status.widget.phone.transport.v2.IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE);
            this.enrollmentCharacteristic = service == null ? null : service.getCharacteristic(dezz.status.widget.phone.transport.v2.IphoneBleProtocolV2.ENROLLMENT_CHARACTERISTIC);
            this.peerProofCharacteristic = service != null ? service.getCharacteristic(dezz.status.widget.phone.transport.v2.IphoneBleProtocolV2.PEER_PROOF_CHARACTERISTIC) : null;
            if (!plainReadWrite(this.enrollmentCharacteristic) || !readable(this.peerProofCharacteristic)) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "Helper v51 C4/H service graph is incomplete");
                return;
            }
            try {
                this.ephemeral = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.generateEphemeralKeyPair(this.random);
                this.hello = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.encodeEnrollmentHello(this.androidInstallationId, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.randomNonce(this.random), this.ephemeral.getPublic());
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_HELLO, "sending ephemeral enrollment hello", "");
                writeEnrollment(this.hello);
            } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "could not create ephemeral P-256 enrollment session");
            }
        }
    }

    public void handleCharacteristicWrite(android.bluetooth.BluetoothGatt bluetoothGatt, android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic, int i) {
        java.lang.String str;
        if (bluetoothGatt == this.gatt && bluetoothGattCharacteristic == this.enrollmentCharacteristic && !terminal()) {
            if (i != 0) {
                dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind errorKind = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL;
                if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_CONFIRM) {
                    str = "Helper did not accept local SAS confirmation";
                } else {
                    str = "Helper rejected an authenticated enrollment frame";
                }
                fail(errorKind, str);
                return;
            }
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_HELLO) {
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_RESPONSE, "reading Helper ephemeral response", "");
                read(this.enrollmentCharacteristic);
                return;
            }
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_CONFIRM) {
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_HELPER_SAS_CONFIRMATION, "waiting for the Helper user to confirm the same SAS", "");
                read(this.enrollmentCharacteristic);
                return;
            }
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_FINAL_COMMIT) {
                this.finalCommitSent = true;
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_FINAL_ACK, "reading Helper final authenticated commit acknowledgement", "");
                read(this.enrollmentCharacteristic);
            } else if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_PENDING_ROUTINE_HELLO) {
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_PROOF, "verifying crash-recoverable pending key", "");
                read(this.enrollmentCharacteristic);
            } else if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_PENDING_ROUTINE_CONFIRM) {
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_ACK, "reading pending-key promotion acknowledgement", "");
                read(this.enrollmentCharacteristic);
            }
        }
    }

    public void handleCharacteristicRead(android.bluetooth.BluetoothGatt bluetoothGatt, android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bArr, int i) {
        if (bluetoothGatt != this.gatt || terminal()) {
            return;
        }
        if (bluetoothGattCharacteristic == this.enrollmentCharacteristic) {
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_HELPER_SAS_CONFIRMATION) {
                handleHelperPreauthAck(bArr, i);
                return;
            }
            if (i != 0 || bArr == null) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "Helper C4 response unavailable");
                return;
            }
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_RESPONSE) {
                establishSession(bArr);
                return;
            }
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_FINAL_ACK) {
                finishFinalAck(bArr);
                return;
            } else if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_PROOF) {
                verifyPendingRoutineProof(bArr);
                return;
            } else {
                if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_ACK) {
                    finishPendingRoutineAck(bArr);
                    return;
                }
                return;
            }
        }
        android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic2 = this.peerProofCharacteristic;
        if (bluetoothGattCharacteristic == bluetoothGattCharacteristic2) {
            this.encryptedHReadInFlight = false;
        }
        if (bluetoothGattCharacteristic == bluetoothGattCharacteristic2) {
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_ENCRYPTED_H || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_BOND || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_H) {
                if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_H) {
                    finishPendingRoutineH(bArr, i);
                    return;
                }
                if (i == 0 && bArr != null) {
                    if (!verifyEncryptedH(bArr)) {
                        fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.HELPER_ID_CONFLICT, "encrypted H conflicts with the SAS-authenticated Helper identity");
                        return;
                    } else {
                        this.encryptedHVerified = true;
                        resolvePostBondFacadeOrWait();
                        return;
                    }
                }
                if (isSmpStatus(i)) {
                    transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_BOND, "waiting for native SMP bond created by encrypted H", "");
                    startNativeBondOrWait();
                } else {
                    fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "encrypted H read failed before bond proof");
                }
            }
        }
    }

    private void handleHelperPreauthAck(byte[] bArr, int i) {
        if (this.phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_HELPER_SAS_CONFIRMATION) {
            return;
        }
        if (i != 0 || bArr == null) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "plain C4 returned an error before both SAS confirmations; H was not read");
            return;
        }
        if (bArr.length != 66 || bArr[0] != 1) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "invalid Helper SAS poll frame");
            return;
        }
        byte b = bArr[1];
        if (b == -128) {
            try {
                dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession enrollmentSession = this.session;
                if (enrollmentSession == null || !enrollmentSession.verifyWaitingSas(this.confirm, bArr)) {
                    fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "waiting-SAS identity/MAC mismatch");
                    return;
                } else {
                    scheduleHelperConfirmPoll();
                    return;
                }
            } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "invalid authenticated waiting-SAS frame");
                return;
            }
        }
        if (b != -125) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "unexpected Helper SAS poll frame");
            return;
        }
        try {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession enrollmentSession2 = this.session;
            if (enrollmentSession2 == null || !enrollmentSession2.verifyPreauthAck(this.confirm, bArr)) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "Helper SAS confirmation identity/MAC mismatch");
                return;
            }
            if (!eligibleNow()) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.HFP_LOST, "selected Classic HFP was lost before encrypted H");
                return;
            }
            dezz.status.widget.phone.transport.v2.android.BlePairBridgeSignal.arm(this.context, this.provisionalDevice);
            this.encryptedHRequested = true;
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_ENCRYPTED_H, "both SAS confirmations verified; encrypted H may trigger native SMP", "");
            readEncryptedH();
        } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused2) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "invalid Helper SAS confirmation acknowledgement");
        }
    }

    private void scheduleHelperConfirmPoll() {
        if (this.helperConfirmPollTask == null && !terminal() && this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_HELPER_SAS_CONFIRMATION) {
            java.lang.Runnable runnable = this::pollHelperConfirmation;
            this.helperConfirmPollTask = runnable;
            this.main.postDelayed(runnable, 400L);
        }
    }

    private void pollHelperConfirmation() {
        this.helperConfirmPollTask = null;
        if (this.phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_HELPER_SAS_CONFIRMATION || terminal()) {
            return;
        }
        read(this.enrollmentCharacteristic);
    }

    private void startNativeBondOrWait() {
        if (!this.encryptedHRequested || this.provisionalDevice == null || terminal()) {
            return;
        }
        int iSafeBondState = safeBondState(this.provisionalDevice);
        if (iSafeBondState == 12 || iSafeBondState == 11) {
            scheduleBondPoll();
            return;
        }
        if (this.createBondAttempted) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "native LE SMP did not enter bonding after the authenticated request");
            return;
        }
        this.createBondAttempted = true;
        try {
            if (!this.provisionalDevice.createBond()) {
                fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "native LE SMP request was not accepted");
            } else {
                scheduleBondPoll();
            }
        } catch (java.lang.RuntimeException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "native LE SMP request was rejected");
        }
    }

    private void establishSession(byte[] bArr) {
        try {
            this.session = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.establishEnrollmentSession(this.hello, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.parseEnrollmentResponse(bArr), this.ephemeral.getPrivate());
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_SAS_CONFIRMATION, "compare this SAS with the unlocked foreground Helper", this.session.sas);
        } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "invalid Helper P-256 enrollment response");
        }
    }

    private boolean verifyEncryptedH(byte[] bArr) {
        if (this.session == null) {
            return false;
        }
        dezz.status.widget.phone.transport.v2.IphoneBleControlProtocolV2.Frame frameDecode = dezz.status.widget.phone.transport.v2.IphoneBleControlProtocolV2.decode(bArr);
        java.util.UUID uuidInstallationUuid = dezz.status.widget.phone.transport.v2.IphoneBleControlProtocolV2.installationUuid(frameDecode);
        return frameDecode != null && frameDecode.type == dezz.status.widget.phone.transport.v2.IphoneBleControlProtocolV2.Type.PEER_PROOF && frameDecode.mode == dezz.status.widget.phone.transport.v2.IphoneBleMode.ANDROID_CENTRAL && uuidInstallationUuid != null && uuidInstallationUuid.equals(this.session.helperInstallationId);
    }

    private void resolvePostBondFacadeOrWait() {
        if (!this.encryptedHVerified || this.provisionalDevice == null) {
            return;
        }
        java.util.List<android.bluetooth.BluetoothDevice> listBondedInventory = bondedInventory();
        java.util.ArrayList arrayList = new java.util.ArrayList(listBondedInventory.size());
        for (android.bluetooth.BluetoothDevice bluetoothDevice : listBondedInventory) {
            arrayList.add(new dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Facade(safeAddress(bluetoothDevice), safeType(bluetoothDevice), safeBondState(bluetoothDevice) == 12));
        }
        dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Result resultResolve = dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.resolve(safeAddress(this.provisionalDevice), this.selectedClassicAddress, this.preSessionBondAddresses, this.bondedEventAddresses, arrayList);
        if (resultResolve.path == dezz.status.widget.phone.transport.v2.PostBondFacadeResolverV2.Path.AMBIGUOUS) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "more than one new bonded LE facade appeared during the SMP generation");
            return;
        }
        this.postBondDevice = null;
        if (resultResolve.resolved()) {
            for (android.bluetooth.BluetoothDevice bluetoothDevice2 : listBondedInventory) {
                if (resultResolve.postBondAddress.equals(safeAddress(bluetoothDevice2))) {
                    this.postBondDevice = bluetoothDevice2;
                    break;
                }
            }
        }
        if (this.postBondDevice == null) {
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_BOND, "encrypted H verified; waiting for the exact post-SMP bonded facade", "");
            scheduleBondPoll();
        } else if (!eligibleNow()) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.HFP_LOST, "selected Classic HFP was lost before final enrollment commit");
        } else {
            stageAndSendFinalCommit();
        }
    }

    private void stageAndSendFinalCommit() {
        android.bluetooth.BluetoothDevice bluetoothDevice;
        if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.STAGING_FINAL_COMMIT || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_FINAL_COMMIT || this.finalCommitSent || this.session == null || (bluetoothDevice = this.postBondDevice) == null) {
            return;
        }
        java.lang.String strSafeAddress = safeAddress(bluetoothDevice);
        if (strSafeAddress.isEmpty()) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "post-SMP bonded facade has no usable locator");
            return;
        }
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 iphoneLeEnrollmentRecordV2 = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2.parse(this.preferences.phoneBleV2EnrollmentRecord());
        if (iphoneLeEnrollmentRecordV2 != null && (!iphoneLeEnrollmentRecordV2.selectedClassicAddress.equals(this.selectedClassicAddress) || !iphoneLeEnrollmentRecordV2.androidInstallationId.equals(this.androidInstallationId) || !iphoneLeEnrollmentRecordV2.helperInstallationId.equals(this.session.helperInstallationId))) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.HELPER_ID_CONFLICT, "existing binding belongs to a different selected owner or Helper; forget it explicitly");
            return;
        }
        java.lang.String strEncode = new dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2(this.selectedClassicAddress, strSafeAddress, this.session.helperInstallationId, this.androidInstallationId, this.session.copyLongTermKey(), java.lang.System.currentTimeMillis()).encode();
        transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.STAGING_FINAL_COMMIT, "staging encrypted crash-recovery record before final commit", "");
        if (!this.preferences.beginPhoneBleV2EnrollmentCommit(strEncode)) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PERSISTENCE, "could not durably stage the enrollment record");
            return;
        }
        this.pendingRecordStaged = true;
        try {
            this.finalCommit = this.session.encodeFinalCommit(this.confirm);
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_FINAL_COMMIT, "sending final authenticated post-bond commit", "");
            this.commitMayHaveReachedHelper = true;
            writeEnrollment(this.finalCommit);
        } catch (java.security.GeneralSecurityException unused) {
            this.preferences.clearPhoneBleV2PendingEnrollmentRecord();
            this.pendingRecordStaged = false;
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "could not construct final enrollment commit");
        }
    }

    private void finishFinalAck(byte[] bArr) {
        try {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession enrollmentSession = this.session;
            if (enrollmentSession != null && enrollmentSession.verifyFinalAck(this.confirm, this.finalCommit, bArr)) {
                startPendingRoutinePromotion();
                return;
            }
        } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused) {
        }
        fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "final Helper commit acknowledgement is invalid or unavailable");
    }

    private void startPendingRoutinePromotion() {
        if (this.session == null) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "pending-key recovery session is unavailable");
            return;
        }
        try {
            this.pendingRoutineHello = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.encodeRoutineHello(this.androidInstallationId, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.randomNonce(this.random));
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_PENDING_ROUTINE_HELLO, "final commit staged; proving pending key before either side promotes", "");
            writeEnrollment(this.pendingRoutineHello);
        } catch (java.lang.RuntimeException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "could not construct pending-key routine hello");
        }
    }

    private void verifyPendingRoutineProof(byte[] bArr) {
        try {
            this.pendingRoutineSession = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.verifyRoutineProof(this.pendingRoutineHello, bArr, this.session.copyLongTermKey());
            if (!this.session.helperInstallationId.equals(this.pendingRoutineSession.helperInstallationId)) {
                throw new java.security.GeneralSecurityException("pending Helper identity conflict");
            }
            this.pendingRoutineConfirm = this.pendingRoutineSession.encodeConfirm();
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_PENDING_ROUTINE_CONFIRM, "confirming the pending key promotion transaction", "");
            writeEnrollment(this.pendingRoutineConfirm);
        } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "Helper did not prove the staged pending enrollment key");
        }
    }

    private void finishPendingRoutineAck(byte[] bArr) {
        try {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.RoutineSession routineSession = this.pendingRoutineSession;
            if (routineSession != null && routineSession.verifyAck(this.pendingRoutineConfirm, bArr)) {
                android.bluetooth.BluetoothDevice bluetoothDevice = this.postBondDevice;
                if (bluetoothDevice == null || safeBondState(bluetoothDevice) != 12 || !eligibleNow()) {
                    fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.HFP_LOST, "selected bonded owner was lost before pending-key promotion H");
                    return;
                } else {
                    transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_PENDING_ROUTINE_H, "pending key ACK verified; requiring a fresh encrypted H before promotion", "");
                    readEncryptedH();
                    return;
                }
            }
        } catch (java.lang.RuntimeException | java.security.GeneralSecurityException unused) {
        }
        fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "pending-key promotion acknowledgement is invalid");
    }

    private void finishPendingRoutineH(byte[] bArr, int i) {
        android.bluetooth.BluetoothDevice bluetoothDevice;
        if (i != 0 || bArr == null || !verifyEncryptedH(bArr) || (bluetoothDevice = this.postBondDevice) == null || safeBondState(bluetoothDevice) != 12 || !eligibleNow()) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PROTOCOL, "pending-key ACK was not followed by exact bonded encrypted H");
            return;
        }
        java.lang.String strPhoneBleV2PendingEnrollmentRecord = this.preferences.phoneBleV2PendingEnrollmentRecord();
        if (strPhoneBleV2PendingEnrollmentRecord.isEmpty() || !this.preferences.completePhoneBleV2EnrollmentCommit(strPhoneBleV2PendingEnrollmentRecord)) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PERSISTENCE, "pending routine ACK verified but local binding promotion failed");
            return;
        }
        this.pendingRecordStaged = false;
        dezz.status.widget.phone.transport.v2.android.BlePairBridgeSignal.disarm(this.context);
        this.phase = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SUCCEEDED;
        this.error = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.NONE;
        this.detail = "secure LE enrollment completed; routine route may now use the post-bond locator";
        this.terminalPublished = true;
        this.listener.onEnrollmentState(snapshot());
        cleanup(true);
    }

    public void handleBondEvent(android.bluetooth.BluetoothDevice bluetoothDevice, int i) {
        if (!this.encryptedHRequested || terminal() || bluetoothDevice == null) {
            return;
        }
        if (i == 10 && sameDevice(bluetoothDevice, this.provisionalDevice)) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.BOND_FAILED, "native SMP bond was rejected or removed");
            return;
        }
        if (i != 12) {
            return;
        }
        java.lang.String strSafeAddress = safeAddress(bluetoothDevice);
        if (!strSafeAddress.isEmpty()) {
            this.bondedEventAddresses.add(strSafeAddress);
        }
        if (this.encryptedHVerified) {
            resolvePostBondFacadeOrWait();
        } else if (sameDevice(bluetoothDevice, this.provisionalDevice) || safeBondState(this.provisionalDevice) == 12) {
            transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_ENCRYPTED_H, "post-SMP facade observed; re-reading encrypted H", "");
            readEncryptedH();
        }
    }

    private void scheduleBondPoll() {
        if (this.bondPollTask != null || terminal()) {
            return;
        }
        java.lang.Runnable runnable = this::pollBondState;
        this.bondPollTask = runnable;
        this.main.postDelayed(runnable, 500L);
    }

    private void pollBondState() {
        this.bondPollTask = null;
        if (terminal() || this.provisionalDevice == null || this.phase != dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_BOND) {
            return;
        }
        if (safeBondState(this.provisionalDevice) == 12) {
            if (this.encryptedHVerified) {
                resolvePostBondFacadeOrWait();
                return;
            } else {
                transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.READING_ENCRYPTED_H, "bonded facade present; re-reading encrypted H", "");
                readEncryptedH();
                return;
            }
        }
        scheduleBondPoll();
    }

    private void writeEnrollment(byte[] bArr) {
        android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic = this.enrollmentCharacteristic;
        if (this.gatt == null || bluetoothGattCharacteristic == null || bArr == null) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "C4 write owner is unavailable");
            return;
        }
        try {
            bluetoothGattCharacteristic.setWriteType(2);
            bluetoothGattCharacteristic.setValue(bArr);
            if (this.gatt.writeCharacteristic(bluetoothGattCharacteristic)) {
                return;
            }
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_FINAL_COMMIT) {
                this.commitMayHaveReachedHelper = false;
            }
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "C4 write was not accepted");
        } catch (java.lang.RuntimeException unused) {
            if (this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SENDING_FINAL_COMMIT) {
                this.commitMayHaveReachedHelper = false;
            }
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "C4 write failed to start");
        }
    }

    private void read(android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        android.bluetooth.BluetoothGatt bluetoothGatt = this.gatt;
        if (bluetoothGatt == null || bluetoothGattCharacteristic == null) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "GATT read owner is unavailable");
            return;
        }
        try {
            if (bluetoothGatt.readCharacteristic(bluetoothGattCharacteristic)) {
                return;
            }
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "GATT read was not accepted");
        } catch (java.lang.RuntimeException unused) {
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.GATT, "GATT read failed to start");
        }
    }

    private void readEncryptedH() {
        if (terminal() || this.encryptedHReadInFlight) {
            return;
        }
        java.lang.Runnable runnable = this.bondPollTask;
        if (runnable != null) {
            this.main.removeCallbacks(runnable);
            this.bondPollTask = null;
        }
        this.encryptedHReadInFlight = true;
        read(this.peerProofCharacteristic);
    }

    private boolean eligibleNow() {
        return uniqueSelectedClassicBond() != null && this.eligibility.exactSelectedClassicHfpActive(this.selectedClassicAddress);
    }

    private android.bluetooth.BluetoothDevice uniqueSelectedClassicBond() {
        if (this.adapter != null && !this.selectedClassicAddress.isEmpty()) {
            try {
                java.util.Set<android.bluetooth.BluetoothDevice> bondedDevices = this.adapter.getBondedDevices();
                if (bondedDevices == null) {
                    return null;
                }
                int i = 0;
                android.bluetooth.BluetoothDevice bluetoothDevice = null;
                for (android.bluetooth.BluetoothDevice bluetoothDevice2 : bondedDevices) {
                    if (this.selectedClassicAddress.equals(safeAddress(bluetoothDevice2))) {
                        i++;
                        bluetoothDevice = bluetoothDevice2;
                    }
                }
                if (i == 1 && bluetoothDevice != null && bluetoothDevice.getBondState() == 12) {
                    return bluetoothDevice;
                }
            } catch (java.lang.RuntimeException unused) {
            }
        }
        return null;
    }

    private void capturePreSessionBonds() {
        this.preSessionBondAddresses.clear();
        try {
            java.util.Set<android.bluetooth.BluetoothDevice> bondedDevices = this.adapter.getBondedDevices();
            if (bondedDevices == null) {
                return;
            }
            java.util.Iterator<android.bluetooth.BluetoothDevice> it = bondedDevices.iterator();
            while (it.hasNext()) {
                java.lang.String strSafeAddress = safeAddress(it.next());
                if (!strSafeAddress.isEmpty()) {
                    this.preSessionBondAddresses.add(strSafeAddress);
                }
            }
        } catch (java.lang.RuntimeException unused) {
        }
    }

    private java.util.List<android.bluetooth.BluetoothDevice> bondedInventory() {
        try {
            java.util.Set<android.bluetooth.BluetoothDevice> bondedDevices = this.adapter.getBondedDevices();
            return bondedDevices == null ? java.util.Collections.emptyList() : new java.util.ArrayList(bondedDevices);
        } catch (java.lang.RuntimeException unused) {
            return java.util.Collections.emptyList();
        }
    }

    private static int safeType(android.bluetooth.BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice == null) {
            return 0;
        }
        try {
            return bluetoothDevice.getType();
        } catch (java.lang.RuntimeException unused) {
            return 0;
        }
    }

    private static int safeBondState(android.bluetooth.BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice == null) {
            return 10;
        }
        try {
            return bluetoothDevice.getBondState();
        } catch (java.lang.RuntimeException unused) {
            return 10;
        }
    }

    class AnonymousClass3 extends android.content.BroadcastReceiver {
        AnonymousClass3() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if ("android.bluetooth.device.action.BOND_STATE_CHANGED".equals(intent.getAction())) {
                final android.bluetooth.BluetoothDevice bluetoothDevice = (android.bluetooth.BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                final int intExtra = intent.getIntExtra("android.bluetooth.device.extra.BOND_STATE", 10);
                dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.main
                        .post(() -> handlePostedBondEvent(bluetoothDevice, intExtra));
            }
        }

        private void handlePostedBondEvent(android.bluetooth.BluetoothDevice bluetoothDevice, int i) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.this.handleBondEvent(bluetoothDevice, i);
        }
    }

    private void registerBondReceiver() {
        dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.AnonymousClass3 anonymousClass3 = new dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.AnonymousClass3();
        this.bondReceiver = anonymousClass3;
        try {
            this.context.registerReceiver(anonymousClass3, new android.content.IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED"));
        } catch (java.lang.RuntimeException unused) {
            this.bondReceiver = null;
            fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.PREREQUISITE, "bond-state observation is unavailable");
        }
    }

    private void transition(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase phase, java.lang.String str, java.lang.String str2) {
        if (terminal()) {
            return;
        }
        this.phase = phase;
        this.error = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.NONE;
        this.detail = str;
        this.listener.onEnrollmentState(new dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Snapshot(this.generation, this.phase, this.error, str2, this.detail));
    }

    public void fail(dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind errorKind, java.lang.String str) {
        if (terminal()) {
            return;
        }
        dezz.status.widget.phone.transport.v2.android.BlePairBridgeSignal.disarm(this.context);
        this.phase = dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.FAILED;
        this.error = errorKind;
        this.detail = str;
        this.terminalPublished = true;
        this.listener.onEnrollmentState(snapshot());
        if (this.pendingRecordStaged && !this.commitMayHaveReachedHelper) {
            this.preferences.clearPhoneBleV2PendingEnrollmentRecord();
            this.pendingRecordStaged = false;
        }
        cleanup(false);
    }

    private void cleanup(boolean z) {
        dezz.status.widget.phone.transport.v2.android.BlePairBridgeSignal.disarm(this.context);
        stopScan();
        cancelTimers();
        unregisterBondReceiver();
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession enrollmentSession = this.session;
        if (enrollmentSession != null) {
            enrollmentSession.destroy();
        }
        this.session = null;
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.RoutineSession routineSession = this.pendingRoutineSession;
        if (routineSession != null) {
            routineSession.destroy();
        }
        this.pendingRoutineSession = null;
        this.ephemeral = null;
        this.hello = null;
        this.confirm = null;
        this.finalCommit = null;
        this.pendingRoutineHello = null;
        this.pendingRoutineConfirm = null;
        this.enrollmentCharacteristic = null;
        this.peerProofCharacteristic = null;
        this.encryptedHReadInFlight = false;
        if (this.gatt == null) {
            releaseProcessGate();
        } else if (this.gattCallbackObserved) {
            closeObservedGatt();
        }
    }

    private void closeObservedGatt() {
        android.bluetooth.BluetoothGatt bluetoothGatt = this.gatt;
        this.gatt = null;
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
            } catch (java.lang.RuntimeException unused) {
            }
            try {
                bluetoothGatt.close();
            } catch (java.lang.RuntimeException unused2) {
            }
        }
        releaseProcessGate();
    }

    private void releaseProcessGate() {
        dezz.status.widget.phone.transport.v2.android.ProcessGattRegistrationGateV2.cancelWaiter(this.processGateKey);
        if (this.processGateOwned) {
            this.processGateOwned = false;
            dezz.status.widget.phone.transport.v2.android.ProcessGattRegistrationGateV2.release(this.processGateKey);
        }
    }

    private void stopScan() {
        if (this.scanRunning) {
            this.scanRunning = false;
            try {
                android.bluetooth.le.BluetoothLeScanner bluetoothLeScanner = this.scanner;
                if (bluetoothLeScanner != null) {
                    bluetoothLeScanner.stopScan(this.scanCallback);
                }
            } catch (java.lang.RuntimeException unused) {
            }
            this.scanner = null;
        }
    }

    private void cancelTimers() {
        java.lang.Runnable runnable = this.deadlineTask;
        if (runnable != null) {
            this.main.removeCallbacks(runnable);
        }
        java.lang.Runnable runnable2 = this.candidateSettleTask;
        if (runnable2 != null) {
            this.main.removeCallbacks(runnable2);
        }
        java.lang.Runnable runnable3 = this.bondPollTask;
        if (runnable3 != null) {
            this.main.removeCallbacks(runnable3);
        }
        java.lang.Runnable runnable4 = this.helperConfirmPollTask;
        if (runnable4 != null) {
            this.main.removeCallbacks(runnable4);
        }
        this.deadlineTask = null;
        this.candidateSettleTask = null;
        this.bondPollTask = null;
        this.helperConfirmPollTask = null;
    }

    private void armMonotonicDeadline() {
        java.lang.String str;
        if (terminal()) {
            return;
        }
        long jElapsedRealtime = this.deadlineAtElapsedMillis - android.os.SystemClock.elapsedRealtime();
        if (jElapsedRealtime <= 0) {
            dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind errorKind = this.candidates.isEmpty() ? dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.NO_CANDIDATE : dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.ErrorKind.TIMEOUT;
            if (this.candidates.isEmpty()) {
                str = "no foreground Helper candidate found within 60 seconds";
            } else {
                str = "enrollment did not complete within 60 seconds";
            }
            fail(errorKind, str);
            return;
        }
        java.lang.Runnable runnable = this::handleSessionDeadline;
        this.deadlineTask = runnable;
        this.main.postDelayed(runnable, jElapsedRealtime);
    }

    private void handleSessionDeadline() {
        this.deadlineTask = null;
        armMonotonicDeadline();
    }

    private void unregisterBondReceiver() {
        android.content.BroadcastReceiver broadcastReceiver = this.bondReceiver;
        if (broadcastReceiver == null) {
            return;
        }
        try {
            this.context.unregisterReceiver(broadcastReceiver);
        } catch (java.lang.RuntimeException unused) {
        }
        this.bondReceiver = null;
    }

    private boolean terminal() {
        return this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.SUCCEEDED || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.FAILED || this.phase == dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2.Phase.CANCELLED;
    }

    private static boolean plainReadWrite(android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return (!readable(bluetoothGattCharacteristic) || bluetoothGattCharacteristic == null || (bluetoothGattCharacteristic.getProperties() & 8) == 0) ? false : true;
    }

    private static boolean readable(android.bluetooth.BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        return (bluetoothGattCharacteristic == null || (bluetoothGattCharacteristic.getProperties() & 2) == 0) ? false : true;
    }

    private static boolean sameDevice(android.bluetooth.BluetoothDevice bluetoothDevice, android.bluetooth.BluetoothDevice bluetoothDevice2) {
        return (bluetoothDevice == null || bluetoothDevice2 == null || !safeAddress(bluetoothDevice).equals(safeAddress(bluetoothDevice2))) ? false : true;
    }

    private static java.lang.String safeAddress(android.bluetooth.BluetoothDevice bluetoothDevice) {
        if (bluetoothDevice == null) {
            return "";
        }
        try {
            return canonicalAddress(bluetoothDevice.getAddress());
        } catch (java.lang.RuntimeException unused) {
            return "";
        }
    }

    private static java.lang.String canonicalAddress(java.lang.String str) {
        if (str == null) {
            return "";
        }
        java.lang.String upperCase = str.trim().toUpperCase(java.util.Locale.US);
        if (upperCase.length() != 17) {
            return "";
        }
        for (int i = 0; i < upperCase.length(); i++) {
            char cCharAt = upperCase.charAt(i);
            if (i % 3 == 2) {
                if (cCharAt != ':') {
                    return "";
                }
            } else if ((cCharAt < '0' || cCharAt > '9') && (cCharAt < 'A' || cCharAt > 'F')) {
                return "";
            }
        }
        return upperCase;
    }

    private long nonZeroGeneration() {
        long jNextLong;
        do {
            jNextLong = this.random.nextLong();
        } while (jNextLong == 0);
        return jNextLong;
    }

    private static boolean isZero(java.util.UUID uuid) {
        if (uuid != null) {
            return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0;
        }
        return true;
    }
}
