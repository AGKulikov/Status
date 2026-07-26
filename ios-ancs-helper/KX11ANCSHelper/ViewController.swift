import CoreBluetooth
import UIKit

final class ViewController: UIViewController {
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
        titleLabel.text = "KX11 ANCS HELPER"
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
        resetConnection()
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
        resetConnection()
        central.stopScan()
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
        setStatus("ПОДКЛЮЧЕНИЕ", color: .systemOrange)
        append("Найдена магнитола: \(discovered.name ?? "без имени")")
        append("Connect с CBConnectPeripheralOptionRequiresANCS=true")
        central.connect(
            discovered,
            options: [CBConnectPeripheralOptionRequiresANCS: true]
        )
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

    private func resetConnection() {
        pendingSecureWork?.cancel()
        pendingSecureWork = nil
        sessionGeneration += 1
        peripheral = nil
        pairCharacteristic = nil
        secureCharacteristic = nil
        secureAttempts = 0
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
        setStatus("ОТКЛЮЧЕНО", color: .systemGray)
        resetConnection()
    }

    func centralManager(
        _ central: CBCentralManager,
        didUpdateANCSAuthorizationFor peripheral: CBPeripheral
    ) {
        guard isCurrent(peripheral) else { return }
        append("ANCS authorization changed: \(peripheral.ancsAuthorized)")
        setStatus(
            peripheral.ancsAuthorized ? "ANCS РАЗРЕШЁН" : "ANCS НЕ РАЗРЕШЁН",
            color: peripheral.ancsAuthorized ? .systemGreen : .systemOrange
        )
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
        for service in services where service.uuid == serviceUUID {
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
            return
        }
        for characteristic in service.characteristics ?? [] {
            append("CHAR \(characteristic.uuid.uuidString) props=\(characteristic.properties.rawValue)")
            if characteristic.uuid == pairUUID {
                pairCharacteristic = characteristic
            } else if characteristic.uuid == secureUUID {
                secureCharacteristic = characteristic
            }
        }
        guard let pairCharacteristic else {
            append("CONTROL characteristic не найдена — нужен Android APK v2")
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
