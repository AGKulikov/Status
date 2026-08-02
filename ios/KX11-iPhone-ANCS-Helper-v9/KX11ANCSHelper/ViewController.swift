import CoreBluetooth
import CoreTelephony
import UIKit

/// Helper v9 has exactly one BLE role: iPhone is the peripheral/GATT server and KX11 is the
/// central/GATT client. ANCS and Helper telemetry therefore travel through one Android-owned
/// connection and can never race each other by opening two competing links.
final class ViewController: UIViewController {
    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F01")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F01")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F01")
    private let telemetryUUID = CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F01")

    private let logicalName = "iPhone_ANCS"
    private let runPreference = "KX11ANCSHelper.runRequested"
    private let restoreIdentifier = "ru.natro.kx11ancshelper.peripheral.v9.single-link"

    private let statusLabel = UILabel()
    private let telemetryLabel = UILabel()
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)

    private var peripheralManager: CBPeripheralManager!
    private var runRequested = true
    private var servicePublished = false
    private var serviceAddPending = false
    private var infoCharacteristic: CBMutableCharacteristic?
    private var controlCharacteristic: CBMutableCharacteristic?
    private var secureCharacteristic: CBMutableCharacteristic?
    private var telemetryCharacteristic: CBMutableCharacteristic?
    private var telemetrySubscribers: Set<UUID> = []
    private var pendingTelemetryFrame: Data?
    private var latestTelemetryFrame = Data()
    private var telemetrySequence: UInt16 = 0
    private var telemetryTimer: Timer?
    private var lastAndroidReadAt: Date?
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

        append("v9: единый маршрут KX11 central → iPhone peripheral")
        append("ANCS и B4 используют один Android-owned GATT; обратного BLE-моста нет")
        append("Заряд и радиосеть передаются только из публичных iOS API")

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
        NotificationCenter.default.removeObserver(self)
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v9"
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

        let buttons = UIStackView(arrangedSubviews: [startButton, stopButton, resetButton])
        buttons.axis = .vertical
        buttons.spacing = 8

        logView.backgroundColor = UIColor(white: 0.97, alpha: 1)
        logView.textColor = UIColor(white: 0.08, alpha: 1)
        logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.isEditable = false
        logView.layer.cornerRadius = 10
        logView.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)

        let stack = UIStackView(arrangedSubviews: [
            titleLabel, statusLabel, telemetryLabel, buttons, logView
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
    }

    private func configureButton(_ button: UIButton, title: String, action: Selector) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.backgroundColor = UIColor(white: 0.93, alpha: 1)
        button.layer.cornerRadius = 8
        button.heightAnchor.constraint(equalToConstant: 44).isActive = true
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
        pendingTelemetryFrame = nil
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
            return Data("\(logicalName)/9/single-link".utf8)
        case secureUUID:
            return Data("SECURE IPHONE OK".utf8)
        case telemetryUUID:
            return makeTelemetryFrame(incrementSequence: true)
        default:
            return nil
        }
    }

    // MARK: - Exact Helper telemetry

    private func startTelemetryMonitoring() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(telemetryDidChange),
                           name: UIDevice.batteryLevelDidChangeNotification, object: nil)
        center.addObserver(self, selector: #selector(telemetryDidChange),
                           name: UIDevice.batteryStateDidChangeNotification, object: nil)
        center.addObserver(self, selector: #selector(telemetryDidChange),
                           name: .CTServiceRadioAccessTechnologyDidChange, object: nil)
        center.addObserver(self, selector: #selector(applicationBecameActive),
                           name: UIApplication.didBecomeActiveNotification, object: nil)
        telemetryTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) {
            [weak self] _ in self?.publishTelemetry(reason: "heartbeat")
        }
        if let timer = telemetryTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
        latestTelemetryFrame = makeTelemetryFrame(incrementSequence: true)
        updateTelemetryLabel()
    }

    @objc private func applicationBecameActive() {
        publishServiceIfPossible()
        publishTelemetry(reason: "foreground refresh")
    }

    @objc private func telemetryDidChange() {
        if Thread.isMainThread {
            publishTelemetry(reason: "iOS change")
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.publishTelemetry(reason: "iOS change")
            }
        }
    }

    private func publishTelemetry(reason: String) {
        guard runRequested else { return }
        latestTelemetryFrame = makeTelemetryFrame(incrementSequence: true)
        updateTelemetryLabel()
        guard servicePublished, telemetryCharacteristic != nil,
              !telemetrySubscribers.isEmpty else { return }
        pendingTelemetryFrame = latestTelemetryFrame
        drainTelemetryNotification()
        if reason != "heartbeat" {
            append("B4 snapshot · \(reason) · subscribers=\(telemetrySubscribers.count)")
        }
    }

    private func drainTelemetryNotification() {
        guard runRequested, peripheralManager != nil,
              peripheralManager.state == .poweredOn,
              let characteristic = telemetryCharacteristic,
              let frame = pendingTelemetryFrame,
              !telemetrySubscribers.isEmpty else { return }
        if peripheralManager.updateValue(frame, for: characteristic,
                                         onSubscribedCentrals: nil) {
            pendingTelemetryFrame = nil
        }
    }

    /// Fixed frame: A5, version, level, flags, network, sequence LE, CRC-8/ATM.
    private func makeTelemetryFrame(incrementSequence: Bool) -> Data {
        if incrementSequence { telemetrySequence &+= 1 }
        let device = UIDevice.current
        let level: UInt8
        if device.batteryLevel >= 0 {
            let percent = max(0, min(100, Int((device.batteryLevel * 100).rounded())))
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

        var bytes: [UInt8] = [
            0xA5, 0x01, level, flags, currentNetworkCode(),
            UInt8(truncatingIfNeeded: telemetrySequence),
            UInt8(truncatingIfNeeded: telemetrySequence >> 8)
        ]
        bytes.append(crc8(bytes))
        return Data(bytes)
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
            ? "\(Int((UIDevice.current.batteryLevel * 100).rounded()))%" : "—%"
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
        formatter.dateFormat = "HH:mm:ss.SSS"
        let line = "\(formatter.string(from: Date()))  \(message)"
        if logView.text.isEmpty { logView.text = line }
        else { logView.text.append("\n\(line)") }
        guard !logView.text.isEmpty else { return }
        let end = NSRange(location: max(0, logView.text.utf16.count - 1), length: 1)
        logView.scrollRangeToVisible(end)
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
            self.publishTelemetry(reason: "state restoration")
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
        publishTelemetry(reason: "B4 subscribed")
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
            latestTelemetryFrame = fullValue
            lastAndroidReadAt = Date()
            updateTelemetryLabel()
            append("KX11 READ B4 OK · 8 bytes · \(request.central.identifier.uuidString)")
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
