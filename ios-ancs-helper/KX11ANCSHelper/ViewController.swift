import CoreBluetooth
import UIKit

final class ViewController: UIViewController {
    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F01")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F01")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F01")

    private let localLogicalName = "iPhone_ANCS"
    private let remoteLogicalName = "Geely_ANCS"
    private let runPreference = "KX11ANCSHelper.runRequested"
    private let trustedGeelyPreference = "KX11ANCSHelper.trustedGeelyIdentifier"
    private let centralRestoreIdentifier = "ru.natro.kx11ancshelper.central.v5"
    private let peripheralRestoreIdentifier = "ru.natro.kx11ancshelper.peripheral.v5"

    private let statusLabel = UILabel()
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)

    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!

    private var runRequested = true
    private var servicePublished = false
    private var serviceAddPending = false
    private var infoCharacteristic: CBMutableCharacteristic?
    private var controlCharacteristic: CBMutableCharacteristic?
    private var secureCharacteristic: CBMutableCharacteristic?

    private var geelyPeripheral: CBPeripheral?
    private var geelyInfoCharacteristic: CBCharacteristic?
    private var geelyControlCharacteristic: CBCharacteristic?
    private var geelySecureCharacteristic: CBCharacteristic?
    private var geelyIdentityVerified = false
    private var secureReadAttempts = 0
    private var reconnectAttempt = 0
    private var reconnectWorkItem: DispatchWorkItem?
    private var connectTimeoutWorkItem: DispatchWorkItem?

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()

        let defaults = UserDefaults.standard
        if defaults.object(forKey: runPreference) == nil {
            defaults.set(true, forKey: runPreference)
        }
        runRequested = defaults.bool(forKey: runPreference)
        updateButtons()

        append("v5: \(remoteLogicalName) — основной входящий маршрут.")
        append("\(localLogicalName) — резервная реклама старого маршрута.")
        append("Classic Bluetooth и его имя приложение не изменяет.")

        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: .main,
            options: [
                CBPeripheralManagerOptionShowPowerAlertKey: true,
                CBPeripheralManagerOptionRestoreIdentifierKey: peripheralRestoreIdentifier
            ]
        )
        centralManager = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [
                CBCentralManagerOptionShowPowerAlertKey: true,
                CBCentralManagerOptionRestoreIdentifierKey: centralRestoreIdentifier
            ]
        )
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v5"
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

        configureButton(
            startButton,
            title: "Запустить постоянное ANCS-соединение",
            action: #selector(startTapped)
        )
        configureButton(
            stopButton,
            title: "Остановить оба BLE-маршрута",
            action: #selector(stopTapped)
        )
        configureButton(
            resetButton,
            title: "Сбросить привязку Geely_ANCS",
            action: #selector(resetTapped)
        )

        let buttonStack = UIStackView(
            arrangedSubviews: [startButton, stopButton, resetButton]
        )
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
            stack.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: 16
            ),
            stack.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: 16
            ),
            stack.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -16
            ),
            stack.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -16
            )
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
        resumeCentralRoute()
    }

    @objc private func stopTapped() {
        runRequested = false
        UserDefaults.standard.set(false, forKey: runPreference)
        stopCentralRoute()
        stopPeripheralRoute()
        setStatus("ОСТАНОВЛЕНО", color: .systemGray)
        updateButtons()
        append("Оба BLE-маршрута остановлены пользователем")
    }

    @objc private func resetTapped() {
        UserDefaults.standard.removeObject(forKey: trustedGeelyPreference)
        append("Сохранённая привязка \(remoteLogicalName) удалена")
        stopCentralRoute()
        if runRequested {
            reconnectAttempt = 0
            resumeCentralRoute()
        }
    }

    private func updateButtons() {
        startButton.isEnabled = !runRequested
        stopButton.isEnabled = runRequested
        resetButton.isEnabled = true
    }

    // MARK: - iPhone peripheral / compatibility route

    private func publishServiceIfPossible() {
        guard runRequested, peripheralManager?.state == .poweredOn else { return }
        guard !serviceAddPending else { return }
        if servicePublished {
            startAdvertising()
            return
        }

        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()

        let info = CBMutableCharacteristic(
            type: infoUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let control = CBMutableCharacteristic(
            type: controlUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let secure = CBMutableCharacteristic(
            type: secureUUID,
            properties: [.read, .write],
            value: nil,
            permissions: [
                .readable,
                .writeable,
                .readEncryptionRequired,
                .writeEncryptionRequired
            ]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [info, control, secure]

        infoCharacteristic = info
        controlCharacteristic = control
        secureCharacteristic = secure
        append("Публикую резервный GATT \(localLogicalName)")
        serviceAddPending = true
        peripheralManager.add(service)
    }

    private func clearPublishedService() {
        servicePublished = false
        serviceAddPending = false
        infoCharacteristic = nil
        controlCharacteristic = nil
        secureCharacteristic = nil
    }

    private func startAdvertising() {
        guard runRequested, servicePublished else { return }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: localLogicalName
        ])
        append("\(localLogicalName) рекламирует UUID \(serviceUUID.uuidString)")
    }

    private func stopPeripheralRoute() {
        guard peripheralManager != nil else { return }
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()
    }

    private func responseData(for characteristic: CBCharacteristic) -> Data? {
        if characteristic.uuid == infoUUID {
            return Data("\(localLogicalName)/5".utf8)
        }
        if characteristic.uuid == secureUUID {
            return Data("SECURE IPHONE OK".utf8)
        }
        return nil
    }

    // MARK: - iPhone central / primary Geely_ANCS route

    private var trustedGeelyIdentifier: UUID? {
        guard let raw = UserDefaults.standard.string(forKey: trustedGeelyPreference) else {
            return nil
        }
        return UUID(uuidString: raw)
    }

    private func resumeCentralRoute() {
        guard runRequested, centralManager?.state == .poweredOn else { return }
        guard geelyPeripheral == nil else {
            if geelyPeripheral?.state == .connected {
                beginDiscovery()
            }
            return
        }

        if let identifier = trustedGeelyIdentifier,
           let cached = centralManager.retrievePeripherals(
               withIdentifiers: [identifier]
           ).first {
            append("Восстанавливаю сохранённый \(remoteLogicalName): \(identifier)")
            connect(to: cached, reason: "saved CoreBluetooth identity")
            return
        }
        startCentralScan()
    }

    private func startCentralScan() {
        guard runRequested, centralManager.state == .poweredOn,
              geelyPeripheral == nil else { return }
        if centralManager.isScanning {
            centralManager.stopScan()
        }
        centralManager.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        setStatus("ИЩУ \(remoteLogicalName)", color: .systemOrange)
        append("Фоновый scan только по UUID \(serviceUUID.uuidString)")
    }

    private func connect(to peripheral: CBPeripheral, reason: String) {
        guard runRequested else { return }
        if let current = geelyPeripheral, current.identifier == peripheral.identifier {
            return
        }
        centralManager.stopScan()
        cancelConnectTimeout()
        geelyPeripheral = peripheral
        peripheral.delegate = self
        clearRemoteCharacteristics()
        setStatus("ПОДКЛЮЧАЮ \(remoteLogicalName)", color: .systemOrange)
        append("connect \(peripheral.identifier) · \(reason)")

        if peripheral.state == .connected {
            beginDiscovery()
        } else {
            centralManager.connect(
                peripheral,
                options: [
                    CBConnectPeripheralOptionNotifyOnConnectionKey: true,
                    CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
                ]
            )
            scheduleConnectTimeout(for: peripheral)
        }
    }

    private func scheduleConnectTimeout(for peripheral: CBPeripheral) {
        cancelConnectTimeout()
        let work = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self, self.runRequested,
                  let peripheral,
                  self.geelyPeripheral?.identifier == peripheral.identifier,
                  peripheral.state != .connected else { return }
            self.append("Таймаут подключения; перехожу к новому service scan")
            self.centralManager.cancelPeripheralConnection(peripheral)
            self.clearCentralLink()
            self.scheduleReconnect()
        }
        connectTimeoutWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: work)
    }

    private func cancelConnectTimeout() {
        connectTimeoutWorkItem?.cancel()
        connectTimeoutWorkItem = nil
    }

    private func beginDiscovery() {
        guard runRequested, let peripheral = geelyPeripheral,
              peripheral.state == .connected else { return }
        cancelConnectTimeout()
        peripheral.delegate = self
        clearRemoteCharacteristics()
        setStatus("\(remoteLogicalName) · DISCOVERY", color: .systemOrange)
        peripheral.discoverServices([serviceUUID])
    }

    private func clearRemoteCharacteristics() {
        geelyInfoCharacteristic = nil
        geelyControlCharacteristic = nil
        geelySecureCharacteristic = nil
        geelyIdentityVerified = false
        secureReadAttempts = 0
    }

    private func clearCentralLink() {
        cancelConnectTimeout()
        geelyPeripheral = nil
        clearRemoteCharacteristics()
    }

    private func stopCentralRoute() {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        cancelConnectTimeout()
        if centralManager?.isScanning == true {
            centralManager.stopScan()
        }
        if let peripheral = geelyPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        clearCentralLink()
    }

    private func scheduleReconnect() {
        guard runRequested, reconnectWorkItem == nil else { return }
        let delays: [Double] = [1, 2, 4, 8, 15]
        let delay = delays[min(reconnectAttempt, delays.count - 1)]
        reconnectAttempt += 1
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.reconnectWorkItem = nil
            self.resumeCentralRoute()
        }
        reconnectWorkItem = work
        setStatus("ПОВТОР ЧЕРЕЗ \(Int(delay)) С", color: .systemOrange)
        append("Переподключение \(remoteLogicalName) через \(Int(delay)) с")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func verifyRemoteInfo(_ data: Data, from peripheral: CBPeripheral) {
        let value = String(data: data, encoding: .utf8) ?? ""
        append("INFO \(peripheral.identifier) = `\(value)`")
        guard value.hasPrefix(remoteLogicalName) else {
            append("Отклонено: найден сервис с чужой логической ролью")
            centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        geelyIdentityVerified = true
        UserDefaults.standard.set(
            peripheral.identifier.uuidString,
            forKey: trustedGeelyPreference
        )
        writePair()
    }

    private func writePair() {
        guard geelyIdentityVerified,
              let peripheral = geelyPeripheral,
              peripheral.state == .connected,
              let characteristic = geelyControlCharacteristic else { return }
        append("WRITE PAIR → \(remoteLogicalName)")
        peripheral.writeValue(Data("PAIR".utf8), for: characteristic, type: .withResponse)
    }

    private func readSecure() {
        guard geelyIdentityVerified,
              let peripheral = geelyPeripheral,
              peripheral.state == .connected,
              let characteristic = geelySecureCharacteristic else { return }
        secureReadAttempts += 1
        append("READ SECURE → \(remoteLogicalName), попытка \(secureReadAttempts)")
        peripheral.readValue(for: characteristic)
    }

    private func retrySecureReadIfPossible() {
        guard runRequested, secureReadAttempts < 3 else {
            if let peripheral = geelyPeripheral {
                centralManager.cancelPeripheralConnection(peripheral)
            }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.readSecure()
        }
    }

    private func handleCentralFailure(_ message: String, peripheral: CBPeripheral?) {
        append(message)
        if let peripheral, peripheral.state != .disconnected {
            centralManager.cancelPeripheralConnection(peripheral)
        } else {
            clearCentralLink()
            scheduleReconnect()
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
        if logView.text.isEmpty {
            logView.text = line
        } else {
            logView.text.append("\n\(line)")
        }
        let end = NSRange(location: max(0, logView.text.utf16.count - 1), length: 1)
        logView.scrollRangeToVisible(end)
    }
}

extension ViewController: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        append("CBPeripheralManager state=\(peripheral.state.rawValue)")
        guard peripheral.state == .poweredOn else {
            clearPublishedService()
            if runRequested {
                setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed)
            }
            return
        }
        if runRequested {
            publishServiceIfPossible()
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        willRestoreState dict: [String: Any]
    ) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService]
        append("Peripheral restore: services=\(services?.count ?? 0)")
        guard runRequested else {
            peripheral.stopAdvertising()
            peripheral.removeAllServices()
            clearPublishedService()
            return
        }

        if let service = services?.first(where: { $0.uuid == serviceUUID }) {
            for characteristic in service.characteristics ?? [] {
                switch characteristic.uuid {
                case infoUUID:
                    infoCharacteristic = characteristic as? CBMutableCharacteristic
                case controlUUID:
                    controlCharacteristic = characteristic as? CBMutableCharacteristic
                case secureUUID:
                    secureCharacteristic = characteristic as? CBMutableCharacteristic
                default:
                    break
                }
            }
            servicePublished = infoCharacteristic != nil
                && controlCharacteristic != nil
                && secureCharacteristic != nil
            serviceAddPending = false
        }

        // A restoration callback may run while the manager initializer has not
        // assigned self.peripheralManager yet. Defer all manager-dependent work.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.runRequested else { return }
            if self.servicePublished {
                self.append("Восстановлен резервный GATT \(self.localLogicalName)")
                self.startAdvertising()
            } else {
                self.publishServiceIfPossible()
            }
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didAdd service: CBService,
        error: Error?
    ) {
        serviceAddPending = false
        if let error {
            append("didAdd service error: \(error.localizedDescription)")
            setStatus("ОШИБКА \(localLogicalName)", color: .systemRed)
            return
        }
        servicePublished = true
        append("GATT service \(localLogicalName) опубликован")
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(
        _ peripheral: CBPeripheralManager,
        error: Error?
    ) {
        if let error {
            append("Advertising error: \(error.localizedDescription)")
            setStatus("ОШИБКА BLE-РЕКЛАМЫ", color: .systemRed)
            return
        }
        append("\(localLogicalName) BLE advertising active")
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveRead request: CBATTRequest
    ) {
        let central = request.central.identifier.uuidString
        append(
            "LOCAL READ \(request.characteristic.uuid.uuidString) "
                + "central=\(central) offset=\(request.offset)"
        )
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
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests {
            let text = request.value.flatMap {
                String(data: $0, encoding: .utf8)
            } ?? ""
            append(
                "LOCAL WRITE \(request.characteristic.uuid.uuidString) "
                    + "value=`\(text)`"
            )
            if request.characteristic.uuid == controlUUID,
               text.uppercased() == "PAIR" {
                peripheral.respond(to: request, withResult: .success)
                setStatus("ANDROID FALLBACK ПОДКЛЮЧЁН", color: .systemGreen)
            } else if request.characteristic.uuid == secureUUID,
                      text.uppercased() == "ANCS" {
                peripheral.respond(to: request, withResult: .success)
            } else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
            }
        }
    }
}

extension ViewController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        append("CBCentralManager state=\(central.state.rawValue)")
        guard central.state == .poweredOn else {
            if runRequested {
                setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed)
            }
            return
        }
        if runRequested {
            resumeCentralRoute()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        willRestoreState dict: [String: Any]
    ) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey]
            as? [CBPeripheral] ?? []
        append("Central restore: peripherals=\(peripherals.count)")
        guard runRequested else {
            for peripheral in peripherals {
                central.cancelPeripheralConnection(peripheral)
            }
            return
        }

        // CBCentralManager may invoke restoration from inside its initializer.
        // Resume on the next main-loop turn so self.centralManager is assigned.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.runRequested else { return }
            if let peripheral = peripherals.first {
                self.connect(
                    to: peripheral,
                    reason: "CoreBluetooth state restoration"
                )
            } else {
                self.resumeCentralRoute()
            }
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard runRequested, geelyPeripheral == nil else { return }

        if let trusted = trustedGeelyIdentifier,
           trusted != peripheral.identifier {
            return
        }

        let serviceData = advertisementData[CBAdvertisementDataServiceDataKey]
            as? [CBUUID: Data]
        let logicalData = serviceData?[serviceUUID]
        let logicalName = logicalData.flatMap {
            String(data: $0, encoding: .utf8)
        } ?? ""
        let localName = advertisementData[CBAdvertisementDataLocalNameKey]
            as? String ?? ""
        append(
            "SCAN candidate=\(peripheral.identifier) rssi=\(RSSI) "
                + "serviceData=`\(logicalName)` localName=`\(localName)`"
        )
        connect(to: peripheral, reason: "service UUID scan match")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard geelyPeripheral?.identifier == peripheral.identifier else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        reconnectAttempt = 0
        cancelConnectTimeout()
        append("CONNECTED \(peripheral.identifier)")
        beginDiscovery()
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard geelyPeripheral?.identifier == peripheral.identifier else { return }
        handleCentralFailure(
            "CONNECT FAILED: \(error?.localizedDescription ?? "unknown")",
            peripheral: nil
        )
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard geelyPeripheral?.identifier == peripheral.identifier else { return }
        append("DISCONNECTED: \(error?.localizedDescription ?? "normal")")
        clearCentralLink()
        if runRequested {
            scheduleReconnect()
        }
    }
}

extension ViewController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard geelyPeripheral?.identifier == peripheral.identifier else { return }
        if let error {
            handleCentralFailure(
                "SERVICE DISCOVERY FAILED: \(error.localizedDescription)",
                peripheral: peripheral
            )
            return
        }
        guard let service = peripheral.services?.first(where: {
            $0.uuid == serviceUUID
        }) else {
            handleCentralFailure(
                "\(remoteLogicalName) service отсутствует",
                peripheral: peripheral
            )
            return
        }
        peripheral.discoverCharacteristics(
            [infoUUID, controlUUID, secureUUID],
            for: service
        )
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard geelyPeripheral?.identifier == peripheral.identifier else { return }
        if let error {
            handleCentralFailure(
                "CHARACTERISTIC DISCOVERY FAILED: \(error.localizedDescription)",
                peripheral: peripheral
            )
            return
        }
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid {
            case infoUUID:
                geelyInfoCharacteristic = characteristic
            case controlUUID:
                geelyControlCharacteristic = characteristic
            case secureUUID:
                geelySecureCharacteristic = characteristic
            default:
                break
            }
        }
        guard let info = geelyInfoCharacteristic,
              geelyControlCharacteristic != nil,
              geelySecureCharacteristic != nil else {
            handleCentralFailure(
                "\(remoteLogicalName) service неполный",
                peripheral: peripheral
            )
            return
        }
        peripheral.readValue(for: info)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard geelyPeripheral?.identifier == peripheral.identifier else { return }
        if characteristic.uuid == secureUUID {
            if let error {
                append("SECURE READ error: \(error.localizedDescription)")
                retrySecureReadIfPossible()
                return
            }
            let value = characteristic.value.flatMap {
                String(data: $0, encoding: .utf8)
            } ?? ""
            append("SECURE value=`\(value)`")
            guard value == "SECURE ATT OK" else {
                handleCentralFailure(
                    "Неверный SECURE-ответ \(remoteLogicalName)",
                    peripheral: peripheral
                )
                return
            }
            reconnectAttempt = 0
            setStatus("\(remoteLogicalName) ПОДКЛЮЧЁН", color: .systemGreen)
            append("Основной защищённый канал готов; Android может подключить ANCS client")
            return
        }

        if let error {
            handleCentralFailure(
                "READ FAILED: \(error.localizedDescription)",
                peripheral: peripheral
            )
            return
        }
        if characteristic.uuid == infoUUID, let data = characteristic.value {
            verifyRemoteInfo(data, from: peripheral)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard geelyPeripheral?.identifier == peripheral.identifier else { return }
        if let error {
            handleCentralFailure(
                "WRITE FAILED: \(error.localizedDescription)",
                peripheral: peripheral
            )
            return
        }
        if characteristic.uuid == controlUUID {
            append("PAIR принят \(remoteLogicalName); запускаю encrypted SECURE read")
            readSecure()
        }
    }
}
