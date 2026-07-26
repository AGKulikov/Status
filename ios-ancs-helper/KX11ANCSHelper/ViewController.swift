import CoreBluetooth
import UIKit

final class ViewController: UIViewController {
    private enum LinkPhase {
        case idle
        case scanning
        case connecting
        case cancellingForReconnect
        case reconnectDelay
        case connected
    }

    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01")
    private let pairUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F01")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F01")

    private let statusLabel = UILabel()
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let secureButton = UIButton(type: .system)
    private let disconnectButton = UIButton(type: .system)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var pairCharacteristic: CBCharacteristic?
    private var secureCharacteristic: CBCharacteristic?
    private var scanningRequested = true
    private var secureAttempts = 0
    private var sessionGeneration = 0
    private var pendingSecureWork: DispatchWorkItem?
    private var phase: LinkPhase = .idle
    private var connectAttempt = 0
    private var ancsBootstrapReconnectUsed = false
    private var serviceDatabaseReconnectUsed = false
    private var pendingReconnect = false
    private var reconnectReason = ""
    private var pendingCharacteristicDiscoveries = 0
    private var connectTimeoutWork: DispatchWorkItem?
    private var authorizationGraceWork: DispatchWorkItem?
    private var cancelWatchdogWork: DispatchWorkItem?
    private var reconnectDelayWork: DispatchWorkItem?

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()
        append("На магнитоле сначала нажмите «Ждать iPhone».")
        append("Приложение подключится с системным флагом RequiresANCS.")
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionShowPowerAlertKey: true]
        )
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v3"
        titleLabel.font = .boldSystemFont(ofSize: 24)
        titleLabel.textColor = .white

        statusLabel.text = "ЗАПУСК"
        statusLabel.font = .boldSystemFont(ofSize: 15)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = UIColor.systemBlue
        statusLabel.textAlignment = .center
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true
        statusLabel.heightAnchor.constraint(equalToConstant: 42).isActive = true

        configureButton(startButton, title: "Найти магнитолу", action: #selector(startTapped))
        configureButton(secureButton, title: "Повторить защищённый тест", action: #selector(secureTapped))
        configureButton(disconnectButton, title: "Отключиться", action: #selector(disconnectTapped))
        secureButton.isEnabled = false
        disconnectButton.isEnabled = false

        let buttonStack = UIStackView(arrangedSubviews: [
            startButton, secureButton, disconnectButton
        ])
        buttonStack.axis = .vertical
        buttonStack.spacing = 8

        logView.backgroundColor = UIColor(white: 0.97, alpha: 1)
        logView.textColor = UIColor(white: 0.08, alpha: 1)
        logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.isEditable = false
        logView.layer.cornerRadius = 10
        logView.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)

        let stack = UIStackView(arrangedSubviews: [
            titleLabel, statusLabel, buttonStack, logView
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16)
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
        scanningRequested = true
        startScanIfPossible()
    }

    @objc private func secureTapped() {
        secureAttempts = 0
        runSecureTest()
    }

    @objc private func disconnectTapped() {
        scanningRequested = false
        central.stopScan()
        if let peripheral {
            central.cancelPeripheralConnection(peripheral)
        }
        resetConnection(resetRetryBudget: true)
        setStatus("ОТКЛЮЧЕНО", color: .systemGray)
    }

    private func startScanIfPossible() {
        guard central.state == .poweredOn else {
            append("Bluetooth пока не готов: \(central.state.rawValue)")
            return
        }
        if let peripheral {
            central.cancelPeripheralConnection(peripheral)
        }
        resetConnection(resetRetryBudget: true)
        central.stopScan()
        phase = .scanning
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        setStatus("ПОИСК МАГНИТОЛЫ", color: .systemBlue)
        append("Сканирую service \(serviceUUID.uuidString)")
    }

    private func connect(_ discovered: CBPeripheral) {
        central.stopScan()
        peripheral = discovered
        discovered.delegate = self
        beginConnect(discovered, reason: "найдена diagnostic-реклама")
    }

    private func beginConnect(_ target: CBPeripheral, reason: String) {
        cancelConnectionWork()
        phase = .connecting
        connectAttempt += 1
        setStatus("ПОДКЛЮЧЕНИЕ", color: .systemOrange)
        append("Найдена магнитола: \(target.name ?? "без имени")")
        append("Connect #\(connectAttempt) с RequiresANCS=true · \(reason)")
        central.connect(
            target,
            options: [CBConnectPeripheralOptionRequiresANCS: true]
        )
        let timeout = connectAttempt == 1 ? 40.0 : 15.0
        scheduleConnectTimeout(after: timeout, for: target)
    }

    private func scheduleConnectTimeout(after delay: TimeInterval, for target: CBPeripheral) {
        connectTimeoutWork?.cancel()
        let expectedGeneration = sessionGeneration
        let expectedIdentifier = target.identifier
        let expectedAttempt = connectAttempt
        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  self.sessionGeneration == expectedGeneration,
                  self.peripheral?.identifier == expectedIdentifier,
                  self.connectAttempt == expectedAttempt,
                  self.phase == .connecting else {
                return
            }
            if target.ancsAuthorized && !self.ancsBootstrapReconnectUsed {
                self.ancsBootstrapReconnectUsed = true
                self.beginInternalReconnect(
                    target,
                    reason: "ANCS уже разрешён, но didConnect не получен"
                )
            } else {
                self.append("CONNECT TIMEOUT #\(expectedAttempt)")
                self.setStatus("GATT CONNECT TIMEOUT", color: .systemRed)
                self.central.cancelPeripheralConnection(target)
                self.resetConnection()
            }
        }
        connectTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func scheduleAuthorizationReconnect(for target: CBPeripheral) {
        authorizationGraceWork?.cancel()
        let expectedGeneration = sessionGeneration
        let expectedIdentifier = target.identifier
        let expectedAttempt = connectAttempt
        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  self.sessionGeneration == expectedGeneration,
                  self.peripheral?.identifier == expectedIdentifier,
                  self.connectAttempt == expectedAttempt,
                  self.phase == .connecting else {
                return
            }
            self.beginInternalReconnect(
                target,
                reason: "ANCS разрешён до didConnect"
            )
        }
        authorizationGraceWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }

    private func beginInternalReconnect(_ target: CBPeripheral, reason: String) {
        guard isCurrent(target),
              phase == .connecting || phase == .connected,
              !pendingReconnect else {
            return
        }
        cancelConnectionWork()
        pendingReconnect = true
        reconnectReason = reason
        phase = .cancellingForReconnect
        setStatus("ПЕРЕПОДКЛЮЧЕНИЕ", color: .systemOrange)
        append("\(reason); отменяю текущую попытку перед reconnect")
        central.cancelPeripheralConnection(target)

        let expectedGeneration = sessionGeneration
        let expectedIdentifier = target.identifier
        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  self.sessionGeneration == expectedGeneration,
                  self.peripheral?.identifier == expectedIdentifier,
                  self.phase == .cancellingForReconnect else {
                return
            }
            self.pendingReconnect = false
            self.append("Cancel callback не получен за 4 с; нажмите «Найти магнитолу»")
            self.setStatus("ОЖИДАНИЕ CANCEL TIMEOUT", color: .systemRed)
        }
        cancelWatchdogWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0, execute: work)
    }

    private func scheduleReconnectAfterCancel(for target: CBPeripheral) {
        cancelWatchdogWork?.cancel()
        cancelWatchdogWork = nil
        phase = .reconnectDelay
        pairCharacteristic = nil
        secureCharacteristic = nil
        pendingCharacteristicDiscoveries = 0
        secureAttempts = 0
        secureButton.isEnabled = false

        let expectedGeneration = sessionGeneration
        let expectedIdentifier = target.identifier
        let reason = reconnectReason
        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  self.sessionGeneration == expectedGeneration,
                  self.peripheral?.identifier == expectedIdentifier,
                  self.phase == .reconnectDelay else {
                return
            }
            self.pendingReconnect = false
            self.beginConnect(target, reason: "автоповтор после \(reason)")
        }
        reconnectDelayWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: work)
    }

    private func scheduleServiceDatabaseReconnect(for target: CBPeripheral) {
        guard !serviceDatabaseReconnectUsed else {
            append("CONTROL отсутствует и после повтора; перезапустите тест на магнитоле")
            setStatus("CONTROL НЕ НАЙДЕН", color: .systemRed)
            return
        }
        serviceDatabaseReconnectUsed = true
        beginInternalReconnect(
            target,
            reason: "iOS получила старую GATT-базу без CONTROL"
        )
    }

    private func cancelConnectionWork() {
        connectTimeoutWork?.cancel()
        authorizationGraceWork?.cancel()
        cancelWatchdogWork?.cancel()
        reconnectDelayWork?.cancel()
        connectTimeoutWork = nil
        authorizationGraceWork = nil
        cancelWatchdogWork = nil
        reconnectDelayWork = nil
    }

    private func runSecureTest() {
        guard let peripheral, let secureCharacteristic else {
            append("Защищённая характеристика ещё не найдена")
            return
        }
        secureAttempts += 1
        append("SECURE ATT: попытка чтения \(secureAttempts)")
        peripheral.readValue(for: secureCharacteristic)
    }

    private func scheduleSecureTest(after delay: TimeInterval, for peripheral: CBPeripheral) {
        pendingSecureWork?.cancel()
        let expectedGeneration = sessionGeneration
        let expectedIdentifier = peripheral.identifier
        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  self.sessionGeneration == expectedGeneration,
                  self.peripheral?.identifier == expectedIdentifier else {
                return
            }
            self.runSecureTest()
        }
        pendingSecureWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func isCurrent(_ callbackPeripheral: CBPeripheral) -> Bool {
        peripheral?.identifier == callbackPeripheral.identifier
    }

    private func resetConnection(resetRetryBudget: Bool = false) {
        cancelConnectionWork()
        pendingSecureWork?.cancel()
        pendingSecureWork = nil
        sessionGeneration += 1
        phase = .idle
        peripheral = nil
        pairCharacteristic = nil
        secureCharacteristic = nil
        secureAttempts = 0
        pendingCharacteristicDiscoveries = 0
        pendingReconnect = false
        reconnectReason = ""
        if resetRetryBudget {
            connectAttempt = 0
            ancsBootstrapReconnectUsed = false
            serviceDatabaseReconnectUsed = false
        }
        secureButton.isEnabled = false
        disconnectButton.isEnabled = false
    }

    private func setStatus(_ text: String, color: UIColor) {
        statusLabel.text = text
        statusLabel.backgroundColor = color
    }

    private func append(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        let line = "\(formatter.string(from: Date()))  \(message)"
        if logView.text.isEmpty {
            logView.text = line
        } else {
            logView.text.append("\n\(line)")
        }
        let end = NSRange(location: max(0, logView.text.utf16.count - 1), length: 1)
        logView.scrollRangeToVisible(end)
    }
}

extension ViewController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        append("CoreBluetooth state=\(central.state.rawValue)")
        guard central.state == .poweredOn else {
            setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed)
            return
        }
        if scanningRequested {
            startScanIfPossible()
        } else {
            setStatus("ГОТОВО", color: .systemGreen)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard self.peripheral == nil else { return }
        append("didDiscover RSSI=\(RSSI)")
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard isCurrent(peripheral) else { return }
        if phase == .cancellingForReconnect {
            append("Поздний didConnect во время cancel; продолжаю отмену")
            central.cancelPeripheralConnection(peripheral)
            return
        }
        guard phase == .connecting else {
            append("didConnect проигнорирован в неожиданной фазе \(phase)")
            return
        }
        cancelConnectionWork()
        phase = .connected
        pendingReconnect = false
        setStatus("BLE ПОДКЛЮЧЁН", color: .systemGreen)
        disconnectButton.isEnabled = true
        append("didConnect; ancsAuthorized=\(peripheral.ancsAuthorized)")
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard isCurrent(peripheral) else { return }
        append("didFailToConnect: \(error?.localizedDescription ?? "unknown")")
        if phase == .cancellingForReconnect, pendingReconnect {
            scheduleReconnectAfterCancel(for: peripheral)
            return
        }
        setStatus("ОШИБКА ПОДКЛЮЧЕНИЯ", color: .systemRed)
        resetConnection()
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard isCurrent(peripheral) else { return }
        append("didDisconnect: \(error?.localizedDescription ?? "без ошибки")")
        if phase == .cancellingForReconnect, pendingReconnect {
            scheduleReconnectAfterCancel(for: peripheral)
            return
        }
        setStatus("ОТКЛЮЧЕНО", color: .systemGray)
        resetConnection()
    }

    func centralManager(
        _ central: CBCentralManager,
        didUpdateANCSAuthorizationFor peripheral: CBPeripheral
    ) {
        guard isCurrent(peripheral) else { return }
        append("ANCS authorization changed: \(peripheral.ancsAuthorized)")
        if peripheral.ancsAuthorized, phase == .connecting {
            setStatus("ANCS РАЗРЕШЁН · ЖДУ GATT", color: .systemOrange)
            if !ancsBootstrapReconnectUsed {
                ancsBootstrapReconnectUsed = true
                scheduleAuthorizationReconnect(for: peripheral)
            }
        } else {
            setStatus(
                peripheral.ancsAuthorized ? "ANCS РАЗРЕШЁН" : "ANCS НЕ РАЗРЕШЁН",
                color: peripheral.ancsAuthorized ? .systemGreen : .systemOrange
            )
        }
    }
}

extension ViewController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard isCurrent(peripheral) else { return }
        if let error {
            append("didDiscoverServices error: \(error.localizedDescription)")
            return
        }
        let services = peripheral.services ?? []
        append("Найдено GATT services: \(services.count)")
        let diagnosticServices = services.filter { $0.uuid == serviceUUID }
        pendingCharacteristicDiscoveries = diagnosticServices.count
        guard !diagnosticServices.isEmpty else {
            append("Diagnostic service отсутствует; пробую обновить GATT-базу")
            scheduleServiceDatabaseReconnect(for: peripheral)
            return
        }
        for service in diagnosticServices {
            peripheral.discoverCharacteristics([pairUUID, secureUUID], for: service)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard isCurrent(peripheral) else { return }
        if let error {
            append("didDiscoverCharacteristics error: \(error.localizedDescription)")
        } else {
            for characteristic in service.characteristics ?? [] {
                append("CHAR \(characteristic.uuid.uuidString) props=\(characteristic.properties.rawValue)")
                if characteristic.uuid == pairUUID {
                    pairCharacteristic = characteristic
                } else if characteristic.uuid == secureUUID {
                    secureCharacteristic = characteristic
                }
            }
        }
        pendingCharacteristicDiscoveries = max(0, pendingCharacteristicDiscoveries - 1)
        guard pendingCharacteristicDiscoveries == 0 else { return }
        guard let pairCharacteristic else {
            append("CONTROL characteristic не найдена — вероятен старый GATT-кэш")
            scheduleServiceDatabaseReconnect(for: peripheral)
            return
        }
        secureButton.isEnabled = secureCharacteristic != nil
        append("Пишу PAIR в CONTROL; магнитола зафиксирует именно этот iPhone")
        peripheral.writeValue(Data("PAIR".utf8), for: pairCharacteristic, type: .withResponse)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard isCurrent(peripheral) else { return }
        if let error {
            append("WRITE \(characteristic.uuid.uuidString) error: \(error.localizedDescription)")
            return
        }
        append("WRITE \(characteristic.uuid.uuidString) OK")
        if characteristic.uuid == pairUUID {
            scheduleSecureTest(after: 0.8, for: peripheral)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard isCurrent(peripheral) else { return }
        if let error {
            append("READ \(characteristic.uuid.uuidString) error: \(error.localizedDescription)")
            if characteristic.uuid == secureUUID, secureAttempts < 3 {
                scheduleSecureTest(after: 1.5, for: peripheral)
            }
            return
        }
        let text = characteristic.value.flatMap {
            String(data: $0, encoding: .utf8)
        } ?? ""
        if characteristic.uuid == secureUUID {
            append("SECURE ATT OK · BLE link зашифрован · value=\(text)")
            setStatus(
                peripheral.ancsAuthorized ? "ANCS РАЗРЕШЁН" : "BLE ЗАШИФРОВАН",
                color: .systemGreen
            )
        } else {
            append("READ \(characteristic.uuid.uuidString) OK · value=\(text)")
        }
    }
}
