import CoreBluetooth
import UIKit

final class ViewController: UIViewController {
    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F01")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F01")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F01")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F01")

    private let statusLabel = UILabel()
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)

    private var peripheralManager: CBPeripheralManager!
    private var publishRequested = true
    private var servicePublished = false
    private var infoCharacteristic: CBMutableCharacteristic?
    private var controlCharacteristic: CBMutableCharacteristic?
    private var secureCharacteristic: CBMutableCharacteristic?

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()
        append("v4 работает как GPSTether: iPhone рекламирует BLE-сервис.")
        append("На магнитоле нажмите «Подключить iPhone BLE».")
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: .main,
            options: [CBPeripheralManagerOptionShowPowerAlertKey: true]
        )
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v4"
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

        configureButton(
            startButton,
            title: "Рекламировать iPhone по BLE",
            action: #selector(startTapped)
        )
        configureButton(
            stopButton,
            title: "Остановить BLE-рекламу",
            action: #selector(stopTapped)
        )
        stopButton.isEnabled = false

        let buttonStack = UIStackView(arrangedSubviews: [startButton, stopButton])
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
        publishRequested = true
        publishServiceIfPossible()
    }

    @objc private func stopTapped() {
        publishRequested = false
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()
        setStatus("ОСТАНОВЛЕНО", color: .systemGray)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        append("BLE-реклама и локальный GATT-сервис остановлены")
    }

    private func publishServiceIfPossible() {
        guard peripheralManager.state == .poweredOn else {
            append("Bluetooth пока не готов: \(peripheralManager.state.rawValue)")
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
        setStatus("ПУБЛИКАЦИЯ GATT", color: .systemOrange)
        append("Добавляю service \(serviceUUID.uuidString)")
        append("SECURE требует encrypted read/write и должен инициировать LE bonding")
        peripheralManager.add(service)
    }

    private func clearPublishedService() {
        servicePublished = false
        infoCharacteristic = nil
        controlCharacteristic = nil
        secureCharacteristic = nil
    }

    private func startAdvertising() {
        guard publishRequested, servicePublished else { return }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "KX11-iPhone"
        ])
        setStatus("ЗАПУСК BLE-РЕКЛАМЫ", color: .systemOrange)
        append("Рекламирую UUID \(serviceUUID.uuidString)")
    }

    private func responseData(for characteristic: CBCharacteristic) -> Data? {
        if characteristic.uuid == infoUUID {
            return Data("KX11 iPhone Peripheral v4".utf8)
        }
        if characteristic.uuid == secureUUID {
            return Data("SECURE IPHONE OK".utf8)
        }
        return nil
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
            setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed)
            return
        }
        setStatus("ГОТОВО К РЕКЛАМЕ", color: .systemGreen)
        if publishRequested {
            publishServiceIfPossible()
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didAdd service: CBService,
        error: Error?
    ) {
        if let error {
            append("didAdd service error: \(error.localizedDescription)")
            setStatus("ОШИБКА GATT SERVICE", color: .systemRed)
            return
        }
        servicePublished = true
        append("GATT service опубликован")
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
        setStatus("IPHONE BLE РЕКЛАМИРУЕТСЯ", color: .systemGreen)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        append("Android должен найти KX11-iPhone и сам вызвать connectGatt")
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveRead request: CBATTRequest
    ) {
        let central = request.central.identifier.uuidString
        append(
            "READ \(request.characteristic.uuid.uuidString) "
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
        if request.characteristic.uuid == secureUUID {
            setStatus("SECURE READ · BLE ЗАШИФРОВАН", color: .systemGreen)
            append("SECURE IPHONE OK: Android прочитал encrypted characteristic")
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests {
            let central = request.central.identifier.uuidString
            let text = request.value.flatMap {
                String(data: $0, encoding: .utf8)
            } ?? ""
            append(
                "WRITE \(request.characteristic.uuid.uuidString) "
                    + "central=\(central) value=`\(text)`"
            )
            if request.characteristic.uuid == controlUUID, text.uppercased() == "PAIR" {
                peripheral.respond(to: request, withResult: .success)
                setStatus("ANDROID ПОДКЛЮЧЁН", color: .systemGreen)
            } else if request.characteristic.uuid == secureUUID,
                      text.uppercased() == "ANCS" {
                peripheral.respond(to: request, withResult: .success)
                setStatus("SECURE WRITE · BLE ЗАШИФРОВАН", color: .systemGreen)
            } else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
            }
        }
    }
}
