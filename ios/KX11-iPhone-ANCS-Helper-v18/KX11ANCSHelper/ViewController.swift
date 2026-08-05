import CoreBluetooth
import CoreTelephony
import UIKit

/// Helper v18 exposes two mutually exclusive BLE routes. Peripheral is the v11 production route
/// and remains the default. Central connects to Geely_ANCS with RequiresANCS, then KX11 attaches
/// its ANCS GATT client to that same physical link. Classic Bluetooth is never modified here.
final class ViewController: UIViewController {
    private enum BleRole: Int {
        case peripheral = 0
        case central = 1

        var title: String { self == .peripheral ? "Peripheral" : "Central" }
    }

    private enum CentralHandshake {
        case idle
        case discovering
        case writingPair
        case readingSecure
        case ready
    }

    private struct TelemetrySnapshot: Equatable {
        let batteryLevel: UInt8
        let powerFlags: UInt8
        let networkCode: UInt8
    }

    // Private GATT generation 2 for both selectable routes. Android 9 and iOS cache a GATT
    // database by peer and UUID; reusing the v1 UUID after adding B4 NOTIFY/CCCD left Core
    // Bluetooth with stale handles and produced CBError.uuidNotAllowed. The Peripheral route's
    // behavior remains the v11 production flow, but both ends must use this fresh private UUID.
    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F02")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F02")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F02")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F02")
    private let telemetryUUID = CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F02")

    private let logicalName = "iPhone_ANCS"
    private let runPreference = "KX11ANCSHelper.runRequested"
    private let rolePreference = "KX11ANCSHelper.bleRole.v12"
    private let savedGeelyPeripheralPreference = "KX11ANCSHelper.geelyPeripheral.v12"
    // Generation 2 uses a new restoration owner as well as new UUIDs; otherwise Core Bluetooth
    // can restore the old F01 mutable service before this build gets a chance to publish F02.
    private let restoreIdentifier =
        "ru.natro.kx11ancshelper.peripheral.v18.single-link-g2"
    private let centralRestoreIdentifier =
        "ru.natro.kx11ancshelper.central.v18.geely-ancs-g2"
    private let telemetrySampleInterval: TimeInterval = 1
    private let telemetryHeartbeatInterval: TimeInterval = 30

    private let statusLabel = UILabel()
    private let telemetryLabel = UILabel()
    private let roleControl = UISegmentedControl(items: ["Peripheral", "Central"])
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)
    private let clearLogButton = UIButton(type: .system)
    private let shareLogButton = UIButton(type: .system)
    private var logLines: [String] = []
    private let maximumLogLines = 600

    private var peripheralManager: CBPeripheralManager!
    private var centralManager: CBCentralManager!
    private var role: BleRole = .peripheral
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
    private var lastBackgroundWakeLogAt = Date.distantPast
    private var telemetrySequence: UInt16 = 0
    private var telemetryTimer: Timer?
    private var settledTelemetryRefresh: DispatchWorkItem?
    private var lastAndroidReadAt: Date?
    private var lastAndroidReadLogAt = Date.distantPast
    private let telephonyInfo = CTTelephonyNetworkInfo()

    private var geelyPeripheral: CBPeripheral?
    private var centralService: CBService?
    private var centralControlCharacteristic: CBCharacteristic?
    private var centralSecureCharacteristic: CBCharacteristic?
    private var centralTelemetryCharacteristic: CBCharacteristic?
    private var centralTelemetryNotifySubscribed = false
    private var centralHandshake: CentralHandshake = .idle
    private var centralTelemetryFrames: [Data] = []
    private var centralTelemetryWriteInFlight = false
    private var centralReconnectWorkItem: DispatchWorkItem?
    private var centralConnectTimeoutWorkItem: DispatchWorkItem?
    private var centralSecureRetryWorkItem: DispatchWorkItem?
    private var centralSecureReadAttempt = 0
    private var centralLinkSecurityChallengeObserved = false
    private var lastCentralTransferAt: Date?
    private var centralHardResetReason: String?
    private var centralRequireFreshAdvertisement = false
    private var centralReconnectFailureCount = 0
    private let centralReconnectDelays: [TimeInterval] = [1, 2, 5, 10, 20, 30]

    override func viewDidLoad() {
        super.viewDidLoad()
        let defaults = UserDefaults.standard
        if defaults.object(forKey: runPreference) == nil {
            defaults.set(true, forKey: runPreference)
        }
        runRequested = defaults.bool(forKey: runPreference)
        role = BleRole(rawValue: defaults.integer(forKey: rolePreference)) ?? .peripheral
        buildInterface()
        roleControl.selectedSegmentIndex = role.rawValue
        updateButtons()

        append("v18: выбран iPhone \(role.title); Peripheral сохраняет маршрут v11")
        append("В каждый момент активен только один BLE-маршрут; Classic не изменяется")
        append("Телеметрия: события iOS + 1 с в foreground + B4 wake-poll в background")

        startTelemetryMonitoring()
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: .main,
            options: [
                CBPeripheralManagerOptionShowPowerAlertKey: true,
                CBPeripheralManagerOptionRestoreIdentifierKey: restoreIdentifier
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

    deinit {
        telemetryTimer?.invalidate()
        settledTelemetryRefresh?.cancel()
        centralReconnectWorkItem?.cancel()
        centralConnectTimeoutWorkItem?.cancel()
        centralSecureRetryWorkItem?.cancel()
        NotificationCenter.default.removeObserver(self)
        telephonyInfo.delegate = nil
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v18"
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

        roleControl.selectedSegmentTintColor = .systemBlue
        roleControl.setTitleTextAttributes([.foregroundColor: UIColor.white],
                                           for: .selected)
        roleControl.setTitleTextAttributes([.foregroundColor: UIColor.systemBlue],
                                           for: .normal)
        roleControl.addTarget(self, action: #selector(roleChanged),
                              for: .valueChanged)
        roleControl.heightAnchor.constraint(equalToConstant: 38).isActive = true

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
            titleLabel, roleControl, statusLabel, telemetryLabel, buttons, logActions, logView
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
        startSelectedRouteIfPossible()
    }

    @objc private func stopTapped() {
        runRequested = false
        UserDefaults.standard.set(false, forKey: runPreference)
        stopAllBleRoutes()
        setStatus("ОСТАНОВЛЕНО", color: .systemGray)
        updateButtons()
        append("Оба BLE-маршрута остановлены пользователем")
    }

    @objc private func resetTapped() {
        if role == .peripheral {
            guard peripheralManager != nil, peripheralManager.state == .poweredOn else { return }
            append("Перепубликую локальный GATT; системную LE-пару не удаляю")
            peripheralManager.stopAdvertising()
            peripheralManager.removeAllServices()
            clearPublishedService()
            publishServiceIfPossible()
        } else {
            append("Перезапускаю Central-маршрут; Classic и системную LE-пару не удаляю")
            stopCentralRoute(cancelConnection: true)
            startCentralRouteIfPossible()
        }
    }

    @objc private func roleChanged() {
        guard let selected = BleRole(rawValue: roleControl.selectedSegmentIndex),
              selected != role else { return }
        stopAllBleRoutes()
        role = selected
        UserDefaults.standard.set(selected.rawValue, forKey: rolePreference)
        clearCentralRuntime(keepPeripheral: false)
        lastPublishedSnapshot = nil
        append("Роль переключена: iPhone \(selected.title). Выберите ту же роль в Status Widget")
        updateButtons()
        updateTelemetryLabel()
        if runRequested { startSelectedRouteIfPossible() }
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
        startButton.setTitle("Запустить \(role.title)", for: .normal)
        stopButton.setTitle("Остановить BLE", for: .normal)
        resetButton.setTitle(role == .peripheral
            ? "Перепубликовать GATT без сброса пары"
            : "Переподключить Central без сброса пары", for: .normal)
    }

    private func startSelectedRouteIfPossible() {
        if role == .peripheral { publishServiceIfPossible() }
        else { startCentralRouteIfPossible() }
    }

    private func stopAllBleRoutes() {
        stopService()
        stopCentralRoute(cancelConnection: true)
    }

    // MARK: - One peripheral service

    private func publishServiceIfPossible() {
        guard runRequested, role == .peripheral, peripheralManager != nil,
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
        guard runRequested, role == .peripheral, servicePublished, peripheralManager != nil,
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

    // MARK: - iPhone central / Geely_ANCS route

    private func startCentralRouteIfPossible() {
        guard runRequested, role == .central, centralManager != nil,
              centralManager.state == .poweredOn else { return }
        if centralRequireFreshAdvertisement {
            geelyPeripheral = nil
            startCentralScan()
            return
        }
        if let peripheral = geelyPeripheral {
            switch peripheral.state {
            case .connected:
                peripheral.delegate = self
                if centralHandshake == .idle { beginCentralDiscovery(peripheral) }
                updateConnectionStatus()
                return
            case .connecting:
                armCentralConnectTimeout(peripheral)
                setStatus("CENTRAL · ПОДКЛЮЧЕНИЕ", color: .systemBlue)
                return
            case .disconnecting:
                centralRequireFreshAdvertisement = true
                geelyPeripheral = nil
                scheduleCentralReconnect(reason: "fresh advertisement after peer disconnecting")
                return
            case .disconnected:
                connectCentral(peripheral, reason: "retained peripheral")
                return
            @unknown default:
                break
            }
        }

        if let raw = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
           let identifier = UUID(uuidString: raw),
           let remembered = centralManager.retrievePeripherals(
                withIdentifiers: [identifier]).first {
            connectCentral(remembered, reason: "saved Geely_ANCS identity")
            return
        }
        if let connected = centralManager.retrieveConnectedPeripherals(
                withServices: [serviceUUID]).first {
            connectCentral(connected, reason: "already connected Geely_ANCS")
            return
        }
        startCentralScan()
    }

    private func startCentralScan() {
        guard runRequested, role == .central, centralManager.state == .poweredOn else { return }
        if centralManager.isScanning {
            updateConnectionStatus()
            return
        }
        centralManager.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        setStatus("ИЩУ GEELY_ANCS", color: .systemBlue)
        append("Central scan: service \(serviceUUID.uuidString), только Geely_ANCS")
    }

    private func connectCentral(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central, centralManager.state == .poweredOn else { return }
        centralReconnectWorkItem?.cancel()
        centralReconnectWorkItem = nil
        centralManager.stopScan()
        if let previous = geelyPeripheral, previous.identifier != peripheral.identifier,
           previous.state != .disconnected {
            centralManager.cancelPeripheralConnection(previous)
        }
        geelyPeripheral = peripheral
        peripheral.delegate = self
        clearCentralRuntime(keepPeripheral: true)
        if peripheral.state == .connected {
            beginCentralDiscovery(peripheral)
            return
        }
        if peripheral.state == .connecting {
            armCentralConnectTimeout(peripheral)
            setStatus("CENTRAL · ЖДУ СИСТЕМНОЕ ПОДКЛЮЧЕНИЕ", color: .systemOrange)
            return
        }

        let options: [String: Any] = [
            CBConnectPeripheralOptionRequiresANCS: true,
            CBConnectPeripheralOptionNotifyOnConnectionKey: false,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: false
        ]
        // The v17 system-auto-reconnect request raced this app's fresh-advertisement recovery.
        // In the reverse route Android deliberately changes from GATT-server to ANCS-client on
        // the same encrypted peer; iOS can then invalidate the old D2D9 handles. One owner is
        // deterministic: Helper v18 always resolves the next generation from a fresh advert.
        centralManager.connect(peripheral, options: options)
        armCentralConnectTimeout(peripheral)
        setStatus("CENTRAL · ПОДКЛЮЧЕНИЕ", color: .systemBlue)
        append("connect Geely_ANCS · RequiresANCS=true · AutoReconnect=false"
            + " · ManualFreshAdvertisement=true · \(reason)")
    }

    private func beginCentralDiscovery(_ peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected else { return }
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        centralHandshake = .discovering
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralTelemetryCharacteristic = nil
        centralTelemetryNotifySubscribed = false
        peripheral.discoverServices([serviceUUID])
        setStatus("ПОДКЛЮЧЕНО · ИЩУ GATT", color: .systemOrange)
        append("Geely_ANCS connected; discover D2D9 service on the same BLE link")
    }

    private func stopCentralRoute(cancelConnection: Bool) {
        centralReconnectWorkItem?.cancel()
        centralReconnectWorkItem = nil
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralHardResetReason = nil
        centralRequireFreshAdvertisement = false
        centralReconnectFailureCount = 0
        guard centralManager != nil else {
            clearCentralRuntime(keepPeripheral: false)
            return
        }
        if centralManager.isScanning { centralManager.stopScan() }
        let previous = geelyPeripheral
        clearCentralRuntime(keepPeripheral: false)
        if cancelConnection, let previous = previous,
           previous.state != .disconnected {
            centralManager.cancelPeripheralConnection(previous)
        }
    }

    private func clearCentralRuntime(keepPeripheral: Bool) {
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralTelemetryCharacteristic = nil
        centralTelemetryNotifySubscribed = false
        centralHandshake = .idle
        centralTelemetryFrames.removeAll()
        centralTelemetryWriteInFlight = false
        centralSecureReadAttempt = 0
        centralLinkSecurityChallengeObserved = false
        if !keepPeripheral { geelyPeripheral = nil }
    }

    /// A restored/saved CBPeripheral can remain in `.connecting` indefinitely after Android
    /// republishes its GATT database. Bound that wait, then require a fresh filtered advertisement
    /// so Core Bluetooth receives the current D2D9 generation instead of an inert retained owner.
    private func armCentralConnectTimeout(_ peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state != .connected else { return }
        centralConnectTimeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralConnectTimeoutWorkItem = nil
            guard self.runRequested, self.role == .central,
                  peripheral.identifier == self.geelyPeripheral?.identifier,
                  peripheral.state != .connected else { return }
            self.resetCentralLink(reason: "connect timeout; fresh D2D9 advertisement required")
        }
        centralConnectTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: item)
    }

    private func scheduleCentralReconnect(reason: String) {
        guard runRequested, role == .central, centralReconnectWorkItem == nil else { return }
        clearCentralRuntime(keepPeripheral: true)
        let delayIndex = min(centralReconnectFailureCount,
                             centralReconnectDelays.count - 1)
        let delay = centralReconnectDelays[delayIndex]
        centralReconnectFailureCount = min(centralReconnectFailureCount + 1,
                                           centralReconnectDelays.count - 1)
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.centralReconnectWorkItem = nil
            guard self.runRequested, self.role == .central else { return }
            self.startCentralRouteIfPossible()
        }
        centralReconnectWorkItem = item
        setStatus("CENTRAL · ПЕРЕПОДКЛЮЧЕНИЕ", color: .systemOrange)
        append("Central reconnect через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Drops the whole Core Bluetooth connection when its service database or encryption state
    /// is no longer valid. Apple invalidates all discovered handles on disconnect; reconnecting
    /// only after a fresh D2D9 advertisement prevents the one-second stale-cache loop seen in v12.
    private func resetCentralLink(reason: String) {
        guard runRequested, role == .central, centralManager != nil else { return }
        if centralHardResetReason != nil { return }
        centralReconnectWorkItem?.cancel()
        centralReconnectWorkItem = nil
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralHardResetReason = reason
        centralRequireFreshAdvertisement = true
        clearCentralRuntime(keepPeripheral: true)
        setStatus("CENTRAL · СБРОС BLE-СЕАНСА", color: .systemOrange)
        append("Полный сброс BLE-сеанса; жду свежую D2D9 рекламу · \(reason)")
        guard let peripheral = geelyPeripheral,
              peripheral.state != .disconnected else {
            centralHardResetReason = nil
            geelyPeripheral = nil
            scheduleCentralReconnect(reason: "fresh advertisement after \(reason)")
            return
        }
        centralManager.cancelPeripheralConnection(peripheral)
    }

    private func isCentralEncryptionError(_ error: Error) -> Bool {
        let value = error as NSError
        guard value.domain == CBATTErrorDomain else { return false }
        return value.code == CBATTError.Code.insufficientEncryption.rawValue
            || value.code == CBATTError.Code.insufficientEncryptionKeySize.rawValue
            || value.code == CBATTError.Code.insufficientAuthentication.rawValue
    }

    private func isCentralInvalidHandleError(_ error: Error) -> Bool {
        let value = error as NSError
        if value.domain == CBATTErrorDomain {
            return value.code == CBATTError.Code.invalidHandle.rawValue
                || value.code == CBATTError.Code.attributeNotFound.rawValue
        }
        return value.domain == CBErrorDomain
            && value.code == CBError.Code.invalidHandle.rawValue
    }

    private func isCentralUuidNotAllowedError(_ error: Error) -> Bool {
        let value = error as NSError
        return value.domain == CBErrorDomain
            && value.code == CBError.Code.uuidNotAllowed.rawValue
    }

    /// Uses characteristics only from the CBService instance delivered by the current
    /// didDiscoverServices callback. Core Bluetooth often keeps this already-valid list across a
    /// reconnect; asking it to rediscover the same UUIDs can itself return uuidNotAllowed.
    @discardableResult
    private func useCurrentCentralCharacteristics(_ peripheral: CBPeripheral,
                                                  service: CBService,
                                                  source: String) -> Bool {
        let characteristics = service.characteristics ?? []
        guard let control = characteristics.first(where: { $0.uuid == controlUUID }),
              let secure = characteristics.first(where: { $0.uuid == secureUUID }),
              let telemetry = characteristics.first(where: { $0.uuid == telemetryUUID }) else {
            return false
        }
        centralControlCharacteristic = control
        centralSecureCharacteristic = secure
        centralTelemetryCharacteristic = telemetry
        guard control.properties.contains(.write),
              secure.properties.contains(.read),
              telemetry.properties.contains(.write) else {
            append("Geely_ANCS properties invalid: B2 WRITE, B3 READ, B4 WRITE required")
            resetCentralLink(reason: "Geely_ANCS characteristic properties invalid")
            return true
        }
        if !telemetry.properties.contains(.notify) {
            append("B4 NOTIFY ещё не виден в кэше iOS; подключение продолжается без wake-poll")
        }
        append("D2D9 characteristics ready · \(source)")
        writeCentralPair(peripheral)
        return true
    }

    private func centralErrorDescription(_ error: Error) -> String {
        let value = error as NSError
        return "\(value.localizedDescription) [\(value.domain):\(value.code)]"
    }

    private func writeCentralPair(_ peripheral: CBPeripheral) {
        guard let control = centralControlCharacteristic else {
            resetCentralLink(reason: "CONTROL B2 missing")
            return
        }
        centralHandshake = .writingPair
        peripheral.writeValue(Data("PAIR".utf8), for: control, type: .withResponse)
        setStatus("CENTRAL · PAIR / CURRENT LINK", color: .systemOrange)
        append("WRITE PAIR → Geely_ANCS CONTROL")
    }

    private func readCentralSecure(_ peripheral: CBPeripheral) {
        guard let secure = centralSecureCharacteristic else {
            resetCentralLink(reason: "SECURE B3 missing")
            return
        }
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralSecureReadAttempt += 1
        centralHandshake = .readingSecure
        peripheral.readValue(for: secure)
        append("READ CURRENT LINK B3 · attempt \(centralSecureReadAttempt)")
    }

    private func retryCentralSecure(_ peripheral: CBPeripheral, error: Error) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier else { return }
        let reason = centralErrorDescription(error)
        if isCentralInvalidHandleError(error) {
            resetCentralLink(reason: "SECURE stale handle · \(reason)")
            return
        }
        let expectedChallenge = (error as NSError).domain == CBATTErrorDomain
            && (error as NSError).code == CBATTError.Code.insufficientAuthentication.rawValue
        if expectedChallenge && !centralLinkSecurityChallengeObserved {
            centralLinkSecurityChallengeObserved = true
            append("B3 current-link challenge получен; Core Bluetooth восстанавливает LE security")
        }
        // HA1175 deliberately returns one status-5 challenge on every new physical link. A second
        // B3 read confirms that the callback still belongs to that link. Persistent auth/key-size
        // errors mean the session did not advance; reconnect instead of repeating for 90 seconds.
        if isCentralEncryptionError(error) && centralSecureReadAttempt >= 5 {
            resetCentralLink(reason: "current-link security did not advance · \(reason)")
            return
        }
        guard centralSecureReadAttempt < 15 else {
            append("CURRENT LINK не подтверждён за 30 с · \(reason)")
            resetCentralLink(reason: "current-link confirmation timeout")
            return
        }
        centralSecureRetryWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral,
                  self.runRequested, self.role == .central,
                  peripheral.state == .connected else { return }
            self.centralSecureRetryWorkItem = nil
            self.readCentralSecure(peripheral)
        }
        centralSecureRetryWorkItem = item
        let delay: TimeInterval = expectedChallenge ? 1 : 2
        setStatus("CENTRAL · ВОССТАНАВЛИВАЮ LE SECURITY", color: .systemOrange)
        append("CURRENT LINK B3 повтор через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func markCentralReady(_ peripheral: CBPeripheral, value: Data?) {
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralHandshake = .ready
        centralReconnectFailureCount = 0
        UserDefaults.standard.set(peripheral.identifier.uuidString,
                                  forKey: savedGeelyPeripheralPreference)
        let text = value.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        append("CURRENT LINK OK · saved Geely identity \(peripheral.identifier.uuidString)"
            + (text.isEmpty ? "" : " · `\(text)`"))
        if let telemetry = centralTelemetryCharacteristic,
           telemetry.properties.contains(.notify) {
            peripheral.setNotifyValue(true, for: telemetry)
            append("B4 background wake subscription requested")
        }
        updateConnectionStatus()
        publishTelemetry(reason: "central secure ready", force: true)
    }

    private func drainCentralTelemetryWrite() {
        guard role == .central, runRequested, centralHandshake == .ready,
              !centralTelemetryWriteInFlight,
              let peripheral = geelyPeripheral, peripheral.state == .connected,
              let characteristic = centralTelemetryCharacteristic,
              let frame = centralTelemetryFrames.first else { return }
        centralTelemetryWriteInFlight = true
        peripheral.writeValue(frame, for: characteristic, type: .withResponse)
    }

    private func responseData(for characteristic: CBCharacteristic) -> Data? {
        switch characteristic.uuid {
        case infoUUID:
            return Data("\(logicalName)/17/realtime-single-link".utf8)
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
        startSelectedRouteIfPossible()
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
        if UIApplication.shared.applicationState == .active { updateTelemetryLabel() }
        guard force || changed || heartbeatDue else { return }
        let frame = makeTelemetryFrame(for: snapshot, incrementSequence: true)
        lastPublishedSnapshot = snapshot
        lastTelemetryPublishAt = Date()
        let backgroundWake = reason == "KX11 background wake poll"
        let backgroundLogDue = Date().timeIntervalSince(lastBackgroundWakeLogAt) >= 60
        if changed || force && (!backgroundWake || backgroundLogDue) {
            if backgroundWake { lastBackgroundWakeLogAt = Date() }
            let power = String(snapshot.powerFlags, radix: 16, uppercase: true)
            append("B4 snapshot · \(reason) · battery=\(snapshot.batteryLevel)"
                + " · powerFlags=0x\(power) · network=\(currentNetworkType())"
                + " · seq=\(telemetrySequence) · service=\(servicePublished)"
                + " · subscribers=\(telemetrySubscribers.count)")
        }
        if role == .central {
            guard centralHandshake == .ready,
                  centralTelemetryCharacteristic != nil,
                  geelyPeripheral?.state == .connected else { return }
            // Keep every changed sample, but coalesce an unchanged heartbeat behind an already
            // queued write. Android receives these frames on its encrypted B4 endpoint.
            if changed || force || centralTelemetryFrames.isEmpty {
                centralTelemetryFrames.append(frame)
            } else {
                centralTelemetryFrames[centralTelemetryFrames.count - 1] = frame
            }
            drainCentralTelemetryWrite()
            return
        }
        guard role == .peripheral, servicePublished, telemetryCharacteristic != nil,
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
            // A retained CoreTelephony object can keep its last foreground cache while the app is
            // suspended.  Every BLE wake therefore asks a fresh public snapshot first and falls
            // back to the delegate-owned instance only if iOS has not populated it yet.
            let liveInfo = CTTelephonyNetworkInfo()
            let liveByService = liveInfo.serviceCurrentRadioAccessTechnology ?? [:]
            let byService = liveByService.isEmpty
                ? (telephonyInfo.serviceCurrentRadioAccessTechnology ?? [:])
                : liveByService
            let dataIdentifier = liveInfo.dataServiceIdentifier
                ?? telephonyInfo.dataServiceIdentifier
            if let identifier = dataIdentifier,
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
        if role == .central {
            let write = lastCentralTransferAt.map {
                let formatter = DateFormatter()
                formatter.dateFormat = "HH:mm:ss"
                return formatter.string(from: $0)
            } ?? "ещё не было"
            telemetryLabel.text = "\(batteryDescription()) · \(currentNetworkType())\n"
                + "KX11 WRITE: \(write) · "
                + "\(centralHandshake == .ready ? "защищён" : "ожидание") · "
                + "wake=\(centralTelemetryNotifySubscribed ? "on" : "off")"
            return
        }
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
        if role == .central {
            if centralHandshake == .ready {
                setStatus("KX11 ПОДКЛЮЧЁН · IPHONE CENTRAL", color: .systemGreen)
            } else if geelyPeripheral?.state == .connected {
                setStatus("ПОДКЛЮЧЕНО · ANCS HANDSHAKE", color: .systemOrange)
            } else if centralManager?.isScanning == true {
                setStatus("ИЩУ GEELY_ANCS", color: .systemBlue)
            } else {
                setStatus("CENTRAL · ОЖИДАНИЕ KX11", color: .systemBlue)
            }
            return
        }
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

extension ViewController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        append("CBCentralManager state=\(central.state.rawValue)")
        guard role == .central else {
            if central.isScanning { central.stopScan() }
            return
        }
        guard central.state == .poweredOn else {
            centralConnectTimeoutWorkItem?.cancel()
            centralConnectTimeoutWorkItem = nil
            clearCentralRuntime(keepPeripheral: false)
            if runRequested { setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed) }
            return
        }
        if runRequested { startCentralRouteIfPossible() }
    }

    func centralManager(_ central: CBCentralManager,
                        willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey]
            as? [CBPeripheral] ?? []
        append("Central restore: peripherals=\(peripherals.count)")
        guard runRequested, role == .central else {
            peripherals.forEach { central.cancelPeripheralConnection($0) }
            return
        }
        guard let restored = peripherals.first else { return }
        geelyPeripheral = restored
        restored.delegate = self
        if restored.state == .connected {
            beginCentralDiscovery(restored)
        } else if restored.state == .disconnected {
            connectCentral(restored, reason: "Core Bluetooth restoration")
        } else {
            armCentralConnectTimeout(restored)
            setStatus("CENTRAL · СИСТЕМНОЕ ВОССТАНОВЛЕНИЕ", color: .systemOrange)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard runRequested, role == .central,
              geelyPeripheral == nil || geelyPeripheral?.identifier == peripheral.identifier
              else { return }
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey]
            as? [CBUUID] ?? []
        guard advertisedServices.contains(serviceUUID) else { return }
        append("Найден Geely_ANCS · id=\(peripheral.identifier.uuidString) · RSSI=\(RSSI)")
        centralRequireFreshAdvertisement = false
        connectCentral(peripheral, reason: "filtered D2D9 advertisement")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        append("Central connected · \(peripheral.identifier.uuidString)")
        peripheral.delegate = self
        beginCentralDiscovery(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        append("Central connect failed · \(error?.localizedDescription ?? "без ошибки")")
        centralRequireFreshAdvertisement = true
        clearCentralRuntime(keepPeripheral: false)
        scheduleCentralReconnect(reason: "fresh advertisement after didFailToConnect")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        handleCentralDisconnect(peripheral, isReconnecting: false, error: error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        handleCentralDisconnect(peripheral, isReconnecting: isReconnecting, error: error)
    }

    private func handleCentralDisconnect(_ peripheral: CBPeripheral, isReconnecting: Bool,
                                         error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        append("Central disconnected · systemReconnect=\(isReconnecting) · "
            + (error?.localizedDescription ?? "без ошибки"))
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralRequireFreshAdvertisement = true
        clearCentralRuntime(keepPeripheral: false)
        guard runRequested, role == .central else { return }
        if isReconnecting {
            // Handles a restored connection originally created by an older Helper. New v18
            // connections never request Core Bluetooth auto-reconnect.
            central.cancelPeripheralConnection(peripheral)
        }
        let reason = hardReset.map { "fresh advertisement after \($0)" }
            ?? "fresh advertisement after link disconnected"
        scheduleCentralReconnect(reason: reason)
    }

    func centralManager(_ central: CBCentralManager,
                        didUpdateANCSAuthorizationFor peripheral: CBPeripheral) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        append("ANCS authorization changed · allowed=\(peripheral.ancsAuthorized)")
        if !peripheral.ancsAuthorized {
            setStatus("РАЗРЕШИТЕ ПОКАЗ УВЕДОМЛЕНИЙ", color: .systemOrange)
        } else {
            updateConnectionStatus()
        }
    }
}

extension ViewController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .discovering else { return }
        if let error = error {
            let detail = centralErrorDescription(error)
            append("Discover services error: \(detail)")
            resetCentralLink(reason: "service discovery failed · \(detail)")
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            append("Geely_ANCS service отсутствует после подключения")
            resetCentralLink(reason: "D2D9 service missing")
            return
        }
        centralService = service
        if useCurrentCentralCharacteristics(peripheral, service: service,
                                            source: "current Core Bluetooth cache") {
            return
        }
        let cached = Set((service.characteristics ?? []).map { $0.uuid })
        let missing = [controlUUID, secureUUID, telemetryUUID].filter { !cached.contains($0) }
        peripheral.discoverCharacteristics(missing, for: service)
        append("D2D9 service current; discover missing characteristics: "
            + missing.map(\.uuidString).joined(separator: ", "))
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              service.uuid == serviceUUID,
              let activeService = centralService,
              activeService === service,
              peripheral.services?.contains(where: { $0 === service }) == true else {
            append("Игнорирую stale characteristic callback от прошлого D2D9 поколения")
            return
        }
        if let error = error {
            let detail = centralErrorDescription(error)
            if isCentralUuidNotAllowedError(error),
               useCurrentCentralCharacteristics(peripheral, service: service,
                                                source: "cache fallback after uuidNotAllowed") {
                return
            }
            append("Discover characteristics error: \(detail)")
            resetCentralLink(reason: "characteristic discovery failed · "
                + detail)
            return
        }
        guard useCurrentCentralCharacteristics(peripheral, service: service,
                                               source: "fresh characteristic callback") else {
            append("Geely_ANCS incomplete: CONTROL/SECURE/TELEMETRY required")
            resetCentralLink(reason: "Geely_ANCS characteristics incomplete")
            return
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        if characteristic.uuid == controlUUID {
            if let error = error {
                let detail = centralErrorDescription(error)
                append("WRITE PAIR error: \(detail)")
                resetCentralLink(reason: "PAIR write failed · \(detail)")
            } else {
                append("WRITE PAIR accepted; проверяю текущий ATT link")
                readCentralSecure(peripheral)
            }
            return
        }
        if characteristic.uuid == telemetryUUID {
            centralTelemetryWriteInFlight = false
            if let error = error {
                let detail = centralErrorDescription(error)
                append("B4 telemetry WRITE error: \(detail)")
                resetCentralLink(reason: "current-link telemetry write failed · "
                    + detail)
                return
            }
            if !centralTelemetryFrames.isEmpty { centralTelemetryFrames.removeFirst() }
            lastCentralTransferAt = Date()
            if UIApplication.shared.applicationState == .active { updateTelemetryLabel() }
            drainCentralTelemetryWrite()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        if characteristic.uuid == telemetryUUID {
            guard centralHandshake == .ready else { return }
            if let error = error {
                append("B4 background wake error: \(centralErrorDescription(error))")
                return
            }
            // bluetooth-central wakes a suspended app for this notification. Re-read UIKit and
            // CoreTelephony only now, then answer on the same live encrypted B4 endpoint.
            publishTelemetry(reason: "KX11 background wake poll", force: true)
            return
        }
        guard characteristic.uuid == secureUUID else { return }
        if let error = error {
            retryCentralSecure(peripheral, error: error)
            return
        }
        markCentralReady(peripheral, value: characteristic.value)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier,
              characteristic.uuid == telemetryUUID else { return }
        centralTelemetryNotifySubscribed = error == nil && characteristic.isNotifying
        if let error = error {
            append("B4 background wake subscription error: "
                + centralErrorDescription(error))
            return
        }
        append("B4 background wake subscription="
            + "\(centralTelemetryNotifySubscribed)")
        if centralTelemetryNotifySubscribed {
            publishTelemetry(reason: "background wake subscription ready", force: true)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              invalidatedServices.contains(where: { $0.uuid == serviceUUID }) else { return }
        append("Geely_ANCS service invalidated; stale handles больше не используются")
        resetCentralLink(reason: "D2D9 service invalidated")
    }
}

extension ViewController: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        append("CBPeripheralManager state=\(peripheral.state.rawValue)")
        guard role == .peripheral else {
            peripheral.stopAdvertising()
            peripheral.removeAllServices()
            clearPublishedService()
            return
        }
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
        guard runRequested, role == .peripheral else {
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
        guard runRequested, role == .peripheral else {
            peripheral.removeAllServices()
            clearPublishedService()
            return
        }
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
        guard runRequested, role == .peripheral else {
            peripheral.stopAdvertising()
            return
        }
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
        guard runRequested, role == .peripheral,
              characteristic.uuid == telemetryUUID else { return }
        telemetrySubscribers.insert(central.identifier)
        append("KX11 subscribed B4 · \(central.identifier.uuidString)")
        updateConnectionStatus()
        publishTelemetry(reason: "B4 subscribed", force: true)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard role == .peripheral, characteristic.uuid == telemetryUUID else { return }
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
        guard runRequested, role == .peripheral else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
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
        guard runRequested, role == .peripheral else {
            requests.forEach { peripheral.respond(to: $0, withResult: .requestNotSupported) }
            return
        }
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
