import CoreBluetooth
import CoreTelephony
import UIKit

/// Helper v10 has exactly one BLE role: iPhone is the peripheral/GATT server and KX11 is the
/// central/GATT client. ANCS and Helper telemetry therefore travel through one Android-owned
/// connection and can never race each other by opening two competing links.
final class ViewController: UIViewController {
    private struct TelemetrySnapshot: Equatable {
        let batteryLevel: UInt8
        let powerFlags: UInt8
        let networkCode: UInt8
    }

    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F01")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F01")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F01")
    private let telemetryUUID = CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F01")

    private let logicalName = "iPhone_ANCS"
    private let runPreference = "KX11ANCSHelper.runRequested"
    private let restoreIdentifier = "ru.natro.kx11ancshelper.peripheral.v10.single-link"
    private let telemetrySampleInterval: TimeInterval = 1
    private let telemetryHeartbeatInterval: TimeInterval = 30

    private let statusLabel = UILabel()
    private let telemetryLabel = UILabel()
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)
    private let clearLogButton = UIButton(type: .system)
    private let shareLogButton = UIButton(type: .system)
    private var logLines: [String] = []
    private let maximumLogLines = 600

    private var peripheralManager: CBPeripheralManager!
    private var runRequested = true
    private var servicePublished = false
    private var serviceAddPending = false
    private var infoCharacteristic: CBMutableCharacteristic?
    private var controlCharacteristic: CBMutableCharacteristic?
    private var secureCharacteristic: CBMutableCharacteristic?
    private var telemetryCharacteristic: CBMutableCharacteristic?
    private var telemetrySubscribers: Set<UUID> = []
    /// Core Bluetooth can temporarily apply backpressure to notifications. Keep every changed
    /// state in order so a one-percent battery transition is never replaced by a newer frame.
    private var pendingTelemetryFrames: [Data] = []
    private var lastPublishedSnapshot: TelemetrySnapshot?
    private var lastTelemetryPublishAt = Date.distantPast
    private var lastTelemetryBackpressureLogAt = Date.distantPast
    private var telemetrySequence: UInt16 = 0
    private var telemetryTimer: Timer?
    private var settledTelemetryRefresh: DispatchWorkItem?
    private var lastAndroidReadAt: Date?
    private var lastAndroidReadLogAt = Date.distantPast
    private let telephonyInfo = CTTelephonyNetworkInfo()

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()

        let defaults = UserDefaults.standard
        if defaults.object(forKey: runPreference) == nil {
            defaults.set(true, forKey: runPreference)
        }
        runRequested = defaults.bool(forKey: runPreference)
        updateButtons()

        append("v10: мгновенная телеметрия по единому маршруту KX11 central → iPhone peripheral")
        append("ANCS и B4 используют один Android-owned GATT; обратного BLE-моста нет")
        append("Батарея, питание и радиосеть: события iOS + контроль каждую секунду")

        startTelemetryMonitoring()
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: .main,
            options: [
                CBPeripheralManagerOptionShowPowerAlertKey: true,
                CBPeripheralManagerOptionRestoreIdentifierKey: restoreIdentifier
            ]
        )
    }

    deinit {
        telemetryTimer?.invalidate()
        settledTelemetryRefresh?.cancel()
        NotificationCenter.default.removeObserver(self)
        telephonyInfo.delegate = nil
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v10"
        titleLabel.font = .boldSystemFont(ofSize: 24)
        titleLabel.textColor = .white

        statusLabel.text = "ЗАПУСК"
        statusLabel.font = .boldSystemFont(ofSize: 15)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = .systemBlue
        statusLabel.textAlignment = .center
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true
        statusLabel.heightAnchor.constraint(equalToConstant: 42).isActive = true

        telemetryLabel.text = "Телеметрия: ожидание iOS"
        telemetryLabel.font = .monospacedSystemFont(ofSize: 13, weight: .semibold)
        telemetryLabel.textColor = .white
        telemetryLabel.numberOfLines = 0
        telemetryLabel.textAlignment = .center

        configureButton(startButton, title: "Запустить единый BLE-сервис",
                        action: #selector(startTapped))
        configureButton(stopButton, title: "Остановить BLE-сервис",
                        action: #selector(stopTapped))
        configureButton(resetButton, title: "Перепубликовать GATT без сброса пары",
                        action: #selector(resetTapped))
        configureButton(clearLogButton, title: "Очистить журнал",
                        action: #selector(clearLogTapped), compact: true)
        configureButton(shareLogButton, title: "Поделиться журналом",
                        action: #selector(shareLogTapped), compact: true)

        let buttons = UIStackView(arrangedSubviews: [startButton, stopButton, resetButton])
        buttons.axis = .vertical
        buttons.spacing = 8
        let logActions = UIStackView(arrangedSubviews: [clearLogButton, shareLogButton])
        logActions.axis = .horizontal
        logActions.spacing = 8
        logActions.distribution = .fillEqually

        logView.backgroundColor = UIColor(white: 0.97, alpha: 1)
        logView.textColor = UIColor(white: 0.08, alpha: 1)
        logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.isEditable = false
        logView.layer.cornerRadius = 10
        logView.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)

        let stack = UIStackView(arrangedSubviews: [
            titleLabel, statusLabel, telemetryLabel, buttons, logActions, logView
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                                           constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                                            constant: -16),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                                          constant: -16)
        ])
        loadJournal()
    }

    private func configureButton(_ button: UIButton, title: String, action: Selector,
                                 compact: Bool = false) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.backgroundColor = UIColor(white: 0.93, alpha: 1)
        button.layer.cornerRadius = 8
        button.heightAnchor.constraint(equalToConstant: compact ? 36 : 44).isActive = true
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    @objc private func startTapped() {
        runRequested = true
        UserDefaults.standard.set(true, forKey: runPreference)
        updateButtons()
        publishServiceIfPossible()
    }

    @objc private func stopTapped() {
        runRequested = false
        UserDefaults.standard.set(false, forKey: runPreference)
        stopService()
        setStatus("ОСТАНОВЛЕНО", color: .systemGray)
        updateButtons()
        append("BLE-сервис остановлен пользователем")
    }

    @objc private func resetTapped() {
        guard peripheralManager != nil, peripheralManager.state == .poweredOn else { return }
        append("Перепубликую локальный GATT; системную LE-пару не удаляю")
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()
        publishServiceIfPossible()
    }

    @objc private func clearLogTapped() {
        logLines.removeAll()
        logView.text = ""
        persistJournal()
        append("Журнал подключения очищен")
    }

    @objc private func shareLogTapped() {
        let text = logLines.joined(separator: "\n")
        guard !text.isEmpty else { return }
        let controller = UIActivityViewController(activityItems: [text],
                                                  applicationActivities: nil)
        if let popover = controller.popoverPresentationController {
            popover.sourceView = shareLogButton
            popover.sourceRect = shareLogButton.bounds
        }
        present(controller, animated: true)
    }

    private func updateButtons() {
        startButton.isEnabled = !runRequested
        stopButton.isEnabled = runRequested
        resetButton.isEnabled = runRequested
    }

    // MARK: - One peripheral service

    private func publishServiceIfPossible() {
        guard runRequested, peripheralManager != nil,
              peripheralManager.state == .poweredOn, !serviceAddPending else { return }
        if servicePublished {
            startAdvertising()
            return
        }

        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()

        let info = CBMutableCharacteristic(
            type: infoUUID, properties: [.read], value: nil, permissions: [.readable]
        )
        let control = CBMutableCharacteristic(
            type: controlUUID, properties: [.write, .writeWithoutResponse], value: nil,
            permissions: [.writeable]
        )
        // B3 exists only for pairing/bootstrap compatibility. ANCS itself remains protected by
        // iOS. B4 below is intentionally readable before ANCS authorization so status data can
        // never be starved behind a long encrypted CCCD operation on Android 9.
        let secure = CBMutableCharacteristic(
            type: secureUUID, properties: [.read, .write, .notify], value: nil,
            permissions: [.readable, .writeable, .readEncryptionRequired,
                          .writeEncryptionRequired]
        )
        let telemetry = CBMutableCharacteristic(
            type: telemetryUUID, properties: [.read, .notify], value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [info, control, secure, telemetry]

        infoCharacteristic = info
        controlCharacteristic = control
        secureCharacteristic = secure
        telemetryCharacteristic = telemetry
        serviceAddPending = true
        append("Публикую один GATT \(logicalName), B4=READ+NOTIFY")
        peripheralManager.add(service)
    }

    private func clearPublishedService() {
        servicePublished = false
        serviceAddPending = false
        infoCharacteristic = nil
        controlCharacteristic = nil
        secureCharacteristic = nil
        telemetryCharacteristic = nil
        telemetrySubscribers.removeAll()
        pendingTelemetryFrames.removeAll()
    }

    private func startAdvertising() {
        guard runRequested, servicePublished, peripheralManager != nil,
              peripheralManager.state == .poweredOn else { return }
        if peripheralManager.isAdvertising {
            updateConnectionStatus()
            return
        }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: logicalName
        ])
    }

    private func stopService() {
        guard peripheralManager != nil else { return }
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()
    }

    private func responseData(for characteristic: CBCharacteristic) -> Data? {
        switch characteristic.uuid {
        case infoUUID:
            return Data("\(logicalName)/10/realtime-single-link".utf8)
        case secureUUID:
            return Data("SECURE IPHONE OK".utf8)
        case telemetryUUID:
            let snapshot = captureTelemetrySnapshot()
            lastPublishedSnapshot = snapshot
            lastTelemetryPublishAt = Date()
            return makeTelemetryFrame(for: snapshot, incrementSequence: true)
        default:
            return nil
        }
    }

    // MARK: - Exact Helper telemetry

    private func startTelemetryMonitoring() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        telephonyInfo.delegate = self
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(batteryLevelDidChange),
                           name: UIDevice.batteryLevelDidChangeNotification, object: nil)
        center.addObserver(self, selector: #selector(batteryStateDidChange),
                           name: UIDevice.batteryStateDidChangeNotification, object: nil)
        center.addObserver(self, selector: #selector(radioTechnologyDidChange),
                           name: .CTServiceRadioAccessTechnologyDidChange, object: nil)
        center.addObserver(self, selector: #selector(applicationBecameActive),
                           name: UIApplication.didBecomeActiveNotification, object: nil)
        let timer = Timer(timeInterval: telemetrySampleInterval, repeats: true) {
            [weak self] _ in self?.publishTelemetry(reason: "1s control")
        }
        timer.tolerance = 0.1
        telemetryTimer = timer
        RunLoop.main.add(timer, forMode: .common)
        publishTelemetry(reason: "startup", force: true)
    }

    @objc private func applicationBecameActive() {
        publishServiceIfPossible()
        publishTelemetry(reason: "foreground refresh", force: true)
        scheduleSettledTelemetryRefresh(reason: "foreground settled")
    }

    @objc private func batteryLevelDidChange() {
        publishTelemetryOnMain(reason: "battery level changed")
    }

    @objc private func batteryStateDidChange() {
        publishTelemetryOnMain(reason: "power state changed")
    }

    @objc private func radioTechnologyDidChange(_ notification: Notification) {
        let service = (notification.object as? String) ?? "unknown service"
        publishTelemetryOnMain(reason: "radio changed \(service)")
    }

    private func publishTelemetryOnMain(reason: String) {
        if Thread.isMainThread {
            publishTelemetry(reason: reason, force: true)
            scheduleSettledTelemetryRefresh(reason: "\(reason) settled")
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.publishTelemetry(reason: reason, force: true)
                self?.scheduleSettledTelemetryRefresh(reason: "\(reason) settled")
            }
        }
    }

    /// Some iOS notifications arrive in the same run-loop turn in which the underlying public
    /// property changes. Send immediately, then re-read once after 250 ms to catch that edge
    /// without waiting for the one-second safety sampler.
    private func scheduleSettledTelemetryRefresh(reason: String) {
        settledTelemetryRefresh?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.publishTelemetry(reason: reason)
        }
        settledTelemetryRefresh = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: item)
    }

    private func publishTelemetry(reason: String, force: Bool = false) {
        guard runRequested else { return }
        let snapshot = captureTelemetrySnapshot()
        let changed = snapshot != lastPublishedSnapshot
        let heartbeatDue = Date().timeIntervalSince(lastTelemetryPublishAt)
            >= telemetryHeartbeatInterval
        updateTelemetryLabel()
        guard force || changed || heartbeatDue else { return }
        let frame = makeTelemetryFrame(for: snapshot, incrementSequence: true)
        lastPublishedSnapshot = snapshot
        lastTelemetryPublishAt = Date()
        if changed || force {
            let power = String(snapshot.powerFlags, radix: 16, uppercase: true)
            append("B4 snapshot · \(reason) · battery=\(snapshot.batteryLevel)"
                + " · powerFlags=0x\(power) · network=\(currentNetworkType())"
                + " · seq=\(telemetrySequence) · service=\(servicePublished)"
                + " · subscribers=\(telemetrySubscribers.count)")
        }
        guard servicePublished, telemetryCharacteristic != nil,
              !telemetrySubscribers.isEmpty else { return }
        // A periodic heartbeat may be coalesced while the BLE transmit queue is blocked, but a
        // changed battery/power/network snapshot is always appended and kept in order.
        if changed || force || pendingTelemetryFrames.isEmpty {
            pendingTelemetryFrames.append(frame)
        } else {
            pendingTelemetryFrames[pendingTelemetryFrames.count - 1] = frame
        }
        drainTelemetryNotification()
    }

    private func drainTelemetryNotification() {
        guard runRequested, peripheralManager != nil,
              peripheralManager.state == .poweredOn,
              let characteristic = telemetryCharacteristic,
              !telemetrySubscribers.isEmpty else { return }
        while let frame = pendingTelemetryFrames.first {
            guard peripheralManager.updateValue(frame, for: characteristic,
                                                onSubscribedCentrals: nil) else {
                let now = Date()
                if now.timeIntervalSince(lastTelemetryBackpressureLogAt) >= 1 {
                    lastTelemetryBackpressureLogAt = now
                    append("B4 notify backpressure · queue=\(pendingTelemetryFrames.count)"
                        + " · subscribers=\(telemetrySubscribers.count)")
                }
                return
            }
            pendingTelemetryFrames.removeFirst()
        }
    }

    /// Fixed frame: A5, version, level, flags, network, sequence LE, CRC-8/ATM.
    private func makeTelemetryFrame(for snapshot: TelemetrySnapshot,
                                    incrementSequence: Bool) -> Data {
        if incrementSequence { telemetrySequence &+= 1 }
        var bytes: [UInt8] = [
            0xA5, 0x01, snapshot.batteryLevel, snapshot.powerFlags,
            snapshot.networkCode,
            UInt8(truncatingIfNeeded: telemetrySequence),
            UInt8(truncatingIfNeeded: telemetrySequence >> 8)
        ]
        bytes.append(crc8(bytes))
        return Data(bytes)
    }

    private func captureTelemetrySnapshot() -> TelemetrySnapshot {
        let device = UIDevice.current
        let level: UInt8
        if device.batteryLevel >= 0 {
            // Convert the public UIKit value once at the source. Android receives this exact
            // integer and never estimates, smooths or quantizes it.
            let percent = max(0, min(100,
                Int((Double(device.batteryLevel) * 100.0).rounded())))
            level = UInt8(percent)
        } else {
            level = 0xFF
        }

        var flags: UInt8 = 0
        switch device.batteryState {
        case .charging:
            flags = 0x01 | 0x02 | 0x04 | 0x08
        case .full:
            flags = 0x01 | 0x02 | 0x04 | 0x10
        case .unplugged:
            flags = 0x01 | 0x04
        case .unknown:
            flags = 0
        @unknown default:
            flags = 0
        }

        return TelemetrySnapshot(
            batteryLevel: level,
            powerFlags: flags,
            networkCode: currentNetworkCode()
        )
    }

    private func crc8(_ bytes: [UInt8]) -> UInt8 {
        var crc: UInt8 = 0
        for byte in bytes {
            crc ^= byte
            for _ in 0..<8 {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1
            }
        }
        return crc
    }

    private func currentNetworkCode() -> UInt8 {
        switch currentNetworkType() {
        case "5G": return 1
        case "LTE": return 2
        case "4G": return 3
        case "3G": return 4
        case "E": return 5
        case "G": return 6
        case "1X": return 7
        case "SOS": return 8
        case "SAT": return 9
        default: return 0
        }
    }

    private func currentNetworkType() -> String {
        let technologies: [String]
        if #available(iOS 13.0, *) {
            let byService = telephonyInfo.serviceCurrentRadioAccessTechnology ?? [:]
            if let identifier = telephonyInfo.dataServiceIdentifier,
               let current = byService[identifier] {
                technologies = [current]
            } else {
                technologies = Array(byService.values)
            }
        } else if let current = telephonyInfo.currentRadioAccessTechnology {
            technologies = [current]
        } else {
            technologies = []
        }
        let labels = technologies.map(networkLabel)
        return ["5G", "LTE", "4G", "3G", "E", "G", "1X"]
            .first(where: labels.contains) ?? "—"
    }

    private func networkLabel(_ technology: String) -> String {
        if #available(iOS 14.1, *),
           technology == CTRadioAccessTechnologyNR
            || technology == CTRadioAccessTechnologyNRNSA {
            return "5G"
        }
        switch technology {
        case CTRadioAccessTechnologyLTE:
            return "LTE"
        case CTRadioAccessTechnologyWCDMA,
             CTRadioAccessTechnologyHSDPA,
             CTRadioAccessTechnologyHSUPA,
             CTRadioAccessTechnologyCDMAEVDORev0,
             CTRadioAccessTechnologyCDMAEVDORevA,
             CTRadioAccessTechnologyCDMAEVDORevB,
             CTRadioAccessTechnologyeHRPD:
            return "3G"
        case CTRadioAccessTechnologyEdge:
            return "E"
        case CTRadioAccessTechnologyGPRS:
            return "G"
        case CTRadioAccessTechnologyCDMA1x:
            return "1X"
        default:
            return "—"
        }
    }

    private func batteryDescription() -> String {
        let level = UIDevice.current.batteryLevel >= 0
            ? "\(Int((Double(UIDevice.current.batteryLevel) * 100.0).rounded()))%" : "—%"
        switch UIDevice.current.batteryState {
        case .charging: return "\(level), питание подключено, зарядка"
        case .full: return "\(level), питание подключено, полный"
        case .unplugged: return "\(level), питание отключено"
        case .unknown: return "\(level), состояние питания неизвестно"
        @unknown default: return "\(level), состояние питания неизвестно"
        }
    }

    private func updateTelemetryLabel() {
        let read = lastAndroidReadAt.map {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            return formatter.string(from: $0)
        } ?? "ещё не было"
        telemetryLabel.text = "\(batteryDescription()) · \(currentNetworkType())\n"
            + "Android READ: \(read) · подписок: \(telemetrySubscribers.count)"
    }

    private func updateConnectionStatus() {
        guard runRequested else { return }
        if !telemetrySubscribers.isEmpty {
            setStatus("KX11 ПОДКЛЮЧЁН · ЕДИНЫЙ GATT", color: .systemGreen)
        } else if servicePublished {
            setStatus("ГОТОВ · ЖДУ KX11", color: .systemBlue)
        }
    }

    private func setStatus(_ text: String, color: UIColor) {
        statusLabel.text = text
        statusLabel.backgroundColor = color
    }

    private func append(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let line = "\(formatter.string(from: Date()))  \(message)"
        logLines.append(line)
        if logLines.count > maximumLogLines {
            logLines.removeFirst(logLines.count - maximumLogLines)
        }
        logView.text = logLines.joined(separator: "\n")
        persistJournal()
        guard !logView.text.isEmpty else { return }
        let end = NSRange(location: max(0, logView.text.utf16.count - 1), length: 1)
        logView.scrollRangeToVisible(end)
    }

    private var journalURL: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("KX11-phone-connection.log")
    }

    private func loadJournal() {
        guard let url = journalURL,
              let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        logLines = text.split(separator: "\n", omittingEmptySubsequences: true)
            .suffix(maximumLogLines).map(String.init)
        logView.text = logLines.joined(separator: "\n")
    }

    private func persistJournal() {
        guard let url = journalURL else { return }
        try? logLines.joined(separator: "\n").write(
            to: url, atomically: true, encoding: .utf8)
    }
}

extension ViewController: CTTelephonyNetworkInfoDelegate {
    /// Radio technology and the SIM currently carrying mobile data are separate CoreTelephony
    /// events. Listening to both prevents a stale EDGE/3G label after iOS moves data to LTE/5G
    /// on another line.
    func dataServiceIdentifierDidChange(_ identifier: String) {
        publishTelemetryOnMain(reason: "data service changed \(identifier)")
    }
}

extension ViewController: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        append("CBPeripheralManager state=\(peripheral.state.rawValue)")
        guard peripheral.state == .poweredOn else {
            clearPublishedService()
            if runRequested { setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed) }
            return
        }
        if runRequested { publishServiceIfPossible() }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           willRestoreState dict: [String: Any]) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey]
            as? [CBMutableService] ?? []
        append("Peripheral restore: services=\(services.count)")
        guard runRequested else {
            peripheral.stopAdvertising()
            peripheral.removeAllServices()
            clearPublishedService()
            return
        }
        if let service = services.first(where: { $0.uuid == serviceUUID }) {
            for characteristic in service.characteristics ?? [] {
                guard let mutable = characteristic as? CBMutableCharacteristic else { continue }
                switch characteristic.uuid {
                case infoUUID: infoCharacteristic = mutable
                case controlUUID: controlCharacteristic = mutable
                case secureUUID: secureCharacteristic = mutable
                case telemetryUUID: telemetryCharacteristic = mutable
                default: break
                }
            }
            servicePublished = infoCharacteristic != nil && controlCharacteristic != nil
                && secureCharacteristic != nil && telemetryCharacteristic != nil
            serviceAddPending = false
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested else { return }
            if self.servicePublished { self.startAdvertising() }
            else { self.publishServiceIfPossible() }
            self.publishTelemetry(reason: "state restoration", force: true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService,
                           error: Error?) {
        serviceAddPending = false
        if let failure = error {
            append("didAdd service error: \(failure.localizedDescription)")
            setStatus("ОШИБКА GATT", color: .systemRed)
            return
        }
        servicePublished = true
        append("Единый GATT \(logicalName) опубликован")
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager,
                                               error: Error?) {
        if let failure = error {
            append("Advertising error: \(failure.localizedDescription)")
            setStatus("ОШИБКА BLE-РЕКЛАМЫ", color: .systemRed)
            return
        }
        append("\(logicalName) advertising active")
        updateConnectionStatus()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == telemetryUUID else { return }
        telemetrySubscribers.insert(central.identifier)
        append("KX11 subscribed B4 · \(central.identifier.uuidString)")
        updateConnectionStatus()
        publishTelemetry(reason: "B4 subscribed", force: true)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == telemetryUUID else { return }
        telemetrySubscribers.remove(central.identifier)
        append("KX11 unsubscribed B4 · \(central.identifier.uuidString)")
        updateTelemetryLabel()
        updateConnectionStatus()
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainTelemetryNotification()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveRead request: CBATTRequest) {
        guard let fullValue = responseData(for: request.characteristic) else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        guard request.offset >= 0, request.offset <= fullValue.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = fullValue.subdata(in: request.offset..<fullValue.count)
        peripheral.respond(to: request, withResult: .success)
        if request.characteristic.uuid == telemetryUUID {
            let now = Date()
            lastAndroidReadAt = now
            updateTelemetryLabel()
            // The label shows every one-second read. Keep the on-screen journal bounded enough
            // for long drives by writing a proof line only once per 30 seconds.
            if now.timeIntervalSince(lastAndroidReadLogAt) >= 30 {
                lastAndroidReadLogAt = now
                append("KX11 READ B4 LIVE · 8 bytes · \(request.central.identifier.uuidString)")
            }
            updateConnectionStatus()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            let command = request.value.flatMap { String(data: $0, encoding: .utf8) }?
                .trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
            let accepted = request.offset == 0
                && ((request.characteristic.uuid == controlUUID && command == "PAIR")
                    || (request.characteristic.uuid == secureUUID && command == "ANCS"))
            peripheral.respond(to: request,
                               withResult: accepted ? .success : .requestNotSupported)
            append("KX11 WRITE \(request.characteristic.uuid.uuidString) `\(command)` "
                + (accepted ? "OK" : "REJECTED"))
        }
    }
}
