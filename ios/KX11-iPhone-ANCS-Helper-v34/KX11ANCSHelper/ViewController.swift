import CoreBluetooth
import CoreTelephony
import UIKit

/// Helper v34 keeps one Central owner, recycles a stale pending connection, and gates
/// ANCS-READY on real iOS authorization.
/// PAIR, current-link B3 and ANCS-READY all run on that same owner; there is no deliberate
/// disconnect between trust and ANCS discovery. The iPhone-owned F05 relay carries telemetry and
/// Android's post-CCCD ANCS-SUBSCRIBED proof. Classic Bluetooth is never modified here.
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
        case waitingAncsAuthorization
        case writingAncsReady
        case ready
    }

    private struct TelemetrySnapshot: Equatable {
        let batteryLevel: UInt8
        let powerFlags: UInt8
        let networkCode: UInt8
    }

    // These fixed F04 UUIDs remain for the legacy iPhone-peripheral route. In Central mode v34
    // learns a new Android-owned namespace from the stable beacon for every server publication.
    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F04")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F04")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F04")
    private let telemetryUUID = CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F04")
    private let managedIncomingBeaconUUID =
        CBUUID(string: "D2D9E4BF-47F1-4E44-A8BB-A932FD5AFFFF")
    private let managedIncomingManufacturerID: UInt16 = 0xFFFF
    private let managedIncomingNamespaceProtocol: UInt8 = 1
    private var centralNamespaceResolved = false
    private var centralNamespaceGeneration: UInt16 = 0x2F04
    private var centralServiceUUID =
        CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04")
    private var centralControlUUID =
        CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F04")
    private var centralSecureUUID =
        CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F04")
    private var centralWakeUUID =
        CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F04")
    // Generation 5 is iPhone-owned and shares the one physical link with Android's ANCS client.
    // Keeping it separate from Android's generation-4 bootstrap database prevents either side
    // from reusing the opposite GATT role's cached B4 handle.
    private let telemetryRelayServiceUUID =
        CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F05")
    private let telemetryRelayUUID =
        CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F05")

    private let logicalName = "iPhone_ANCS"
    private let runPreference = "KX11ANCSHelper.runRequested"
    private let rolePreference = "KX11ANCSHelper.bleRole.v12"
    private let savedGeelyPeripheralPreference = "KX11ANCSHelper.geelyPeripheral.v12"
    private let centralNamespacePreference = "KX11ANCSHelper.geelyNamespace.v32"
    // These IDs intentionally do not contain a version: Core Bluetooth restoration requires the
    // same manager identifier on every subsequent app launch/update.
    private let restoreIdentifier =
        "ru.natro.kx11ancshelper.peripheral.stable"
    private let centralRestoreIdentifier =
        "ru.natro.kx11ancshelper.central.stable"
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
    private var publishedServiceUUID: CBUUID?
    private var telemetrySubscribers: Set<UUID> = []
    /// Core Bluetooth can temporarily apply backpressure to notifications. Keep every changed
    /// state in order so a one-percent battery transition is never replaced by a newer frame.
    private var pendingTelemetryFrames: [Data] = []
    private var lastPublishedSnapshot: TelemetrySnapshot?
    private var lastTelemetryPublishAt = Date.distantPast
    private var lastTelemetryBackpressureLogAt = Date.distantPast
    private var lastBackgroundWakeLogAt = Date.distantPast
    private var telemetrySequence: UInt16 = 0
    private var phoneLocked = false
    private var telemetryTimer: Timer?
    private var settledTelemetryRefresh: DispatchWorkItem?
    private var lastAndroidReadAt: Date?
    private var lastAndroidReadLogAt = Date.distantPast
    private let telephonyInfo = CTTelephonyNetworkInfo()

    private var geelyPeripheral: CBPeripheral?
    private var centralService: CBService?
    private var centralControlCharacteristic: CBCharacteristic?
    private var centralSecureCharacteristic: CBCharacteristic?
    private var centralWakeCharacteristic: CBCharacteristic?
    private var centralHandshake: CentralHandshake = .idle
    private var centralReconnectWorkItem: DispatchWorkItem?
    private var centralReconnectToken: UInt64 = 0
    private var centralConnectTimeoutWorkItem: DispatchWorkItem?
    private var centralConnectTimeoutToken: UInt64 = 0
    private var centralConnectTimeoutPeripheralID: UUID?
    /// True only when this manager either issued the connect with RequiresANCS or restored that
    /// exact v34 owner. A system-wide connection discovered through another app is never adopted
    /// as proof of our ANCS contract.
    private var centralOwnerConfiguredForAncs = false
    /// iOS 17 normally owns reconnect after a callback with isReconnecting=true. While this is
    /// set, Helper never issues a competing connect(). A watchdog may cancel that same owner if
    /// Core Bluetooth leaves it pending beyond the bounded recovery window.
    private var centralSystemAutoReconnectActive = false
    private var centralSecureRetryWorkItem: DispatchWorkItem?
    private var centralSecureReadAttempt = 0
    private var centralLinkSecurityChallengeObserved = false
    private var centralCharacteristicDiscoveryWorkItem: DispatchWorkItem?
    private var centralServiceRediscoveryWorkItem: DispatchWorkItem?
    private var centralWakeSubscriptionWorkItem: DispatchWorkItem?
    private var centralWakeSubscriptionAttempt = 0
    private var centralCharacteristicDiscoveryAttempt = 0
    private var centralServiceRediscoveryAttempt = 0
    private let centralCharacteristicDiscoveryLimit = 3
    private let centralServiceRediscoveryLimit = 2
    private var centralHardResetReason: String?
    private var centralRequireFreshAdvertisement = false
    private var centralReconnectFailureCount = 0
    private let centralReconnectDelays: [TimeInterval] = [1, 2, 5, 10, 20, 30]
    private var centralHelperConfirmed = false
    /// Snapshot of CBPeripheral.ancsAuthorized for the retained RequiresANCS owner.
    private var centralAncsAuthorized = false
    /// True only after CURRENT LINK B3 proved encryption on this exact ATT owner.
    private var centralSecureLinkReady = false
    private var centralB4Subscribed = false
    private var centralAncsCccdConfirmed = false
    private var centralReadinessProofWorkItem: DispatchWorkItem?
    private let centralReadinessProofTimeout: TimeInterval = 30
    private let centralConnectTimeout: TimeInterval = 15

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

        let savedNamespace = defaults.integer(forKey: centralNamespacePreference)
        if savedNamespace > 0 && savedNamespace < 0xFFFF {
            applyCentralNamespace(UInt16(savedNamespace), persist: false)
        }

        append("v34: выбран iPhone \(role.title); Peripheral сохраняет маршрут v11")
        append("Один Central owner: RequiresANCS=true с первого connect")
        append("PAIR → B3 → ANCS-READY выполняются без разрыва текущего ATT link")
        append("Зелёный: Helper ACK + ANCS CCCD + B4 CCCD + battery + network")
        append("Watchdog: зависший .connecting owner через 15 с отменяется и ищется свежий namespace")
        append("iOS AutoReconnect и ручной backoff взаимоисключающие; второго connect нет")
        append("Central/Peripheral restoration IDs и dynamic namespace сохраняются")
        append("Телеметрия: батарея, сеть и блокировка + B4 READ/NOTIFY")

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
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralServiceRediscoveryWorkItem?.cancel()
        centralWakeSubscriptionWorkItem?.cancel()
        centralReadinessProofWorkItem?.cancel()
        NotificationCenter.default.removeObserver(self)
        telephonyInfo.delegate = nil
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS HELPER v34"
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
        publishServiceIfPossible()
        if role == .central { startCentralRouteIfPossible() }
    }

    private func stopAllBleRoutes() {
        stopService()
        stopCentralRoute(cancelConnection: true)
    }

    // MARK: - One peripheral service

    private func publishServiceIfPossible() {
        guard runRequested, peripheralManager != nil,
              peripheralManager.state == .poweredOn, !serviceAddPending else { return }
        if servicePublished {
            if role == .peripheral { startAdvertising() }
            else { updateConnectionStatus() }
            return
        }

        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService()

        let relay = role == .central
        let telemetry = CBMutableCharacteristic(
            type: relay ? telemetryRelayUUID : telemetryUUID,
            properties: relay ? [.read, .notify, .write] : [.read, .notify], value: nil,
            permissions: relay ? [.readable, .writeable] : [.readable]
        )
        let service = CBMutableService(
            type: relay ? telemetryRelayServiceUUID : serviceUUID, primary: true)
        if relay {
            service.characteristics = [telemetry]
            infoCharacteristic = nil
            controlCharacteristic = nil
            secureCharacteristic = nil
        } else {
            let info = CBMutableCharacteristic(
                type: infoUUID, properties: [.read], value: nil, permissions: [.readable]
            )
            let control = CBMutableCharacteristic(
                type: controlUUID, properties: [.write, .writeWithoutResponse], value: nil,
                permissions: [.writeable]
            )
            // B3 exists only for pairing/bootstrap compatibility. ANCS itself remains protected
            // by iOS. B4 stays readable before ANCS authorization.
            let secure = CBMutableCharacteristic(
                type: secureUUID, properties: [.read, .write, .notify], value: nil,
                permissions: [.readable, .writeable, .readEncryptionRequired,
                              .writeEncryptionRequired]
            )
            service.characteristics = [info, control, secure, telemetry]
            infoCharacteristic = info
            controlCharacteristic = control
            secureCharacteristic = secure
        }
        telemetryCharacteristic = telemetry
        publishedServiceUUID = service.uuid
        serviceAddPending = true
        append(relay
            ? "Публикую B4 telemetry relay generation 5 без отдельной рекламы"
            : "Публикую один GATT \(logicalName), B4=READ+NOTIFY")
        peripheralManager.add(service)
    }

    private func clearPublishedService() {
        servicePublished = false
        serviceAddPending = false
        infoCharacteristic = nil
        controlCharacteristic = nil
        secureCharacteristic = nil
        telemetryCharacteristic = nil
        publishedServiceUUID = nil
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

    private func continueCentralConnected(_ peripheral: CBPeripheral) {
        cancelCentralConnectTimeout()
        peripheral.delegate = self
        if centralHandshake == .idle {
            centralReconnectFailureCount = 0
            centralAncsAuthorized = peripheral.ancsAuthorized
            centralSecureLinkReady = false
            publishServiceIfPossible()
            append("RequiresANCS didConnect получен · ancsAuthorized=\(centralAncsAuthorized); "
                + "продолжаю PAIR/B3 на этом же owner")
            beginCentralDiscovery(peripheral)
        }
    }

    private func startCentralRouteIfPossible() {
        guard runRequested, role == .central, centralManager != nil,
              centralManager.state == .poweredOn else { return }
        publishServiceIfPossible()
        if !centralNamespaceResolved && !centralRequireFreshAdvertisement {
            let saved = UserDefaults.standard.integer(forKey: centralNamespacePreference)
            if saved > 0 && saved < 0xFFFF {
                applyCentralNamespace(UInt16(saved), persist: false)
            }
        }
        if centralHelperConfirmed && geelyPeripheral?.state == .connected {
            updateConnectionStatus()
            return
        }
        if !centralNamespaceResolved {
            startCentralScan()
            return
        }
        if centralRequireFreshAdvertisement {
            geelyPeripheral = nil
            startCentralScan()
            return
        }
        if let peripheral = geelyPeripheral {
            switch peripheral.state {
            case .connected:
                continueCentralConnected(peripheral)
                updateConnectionStatus()
                return
            case .connecting:
                armCentralConnectTimeout(peripheral)
                setStatus("CENTRAL · СИСТЕМА ПОДКЛЮЧАЕТ", color: .systemBlue)
                return
            case .disconnecting:
                setStatus("CENTRAL · ЖДУ DISCONNECT CALLBACK", color: .systemOrange)
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
        // Do not adopt retrieveConnectedPeripherals here. Such a system-wide owner may have been
        // opened by another app and therefore cannot prove RequiresANCS was present on connect.
        startCentralScan()
    }

    private func startCentralScan() {
        guard runRequested, role == .central, centralManager.state == .poweredOn else { return }
        if centralManager.isScanning {
            updateConnectionStatus()
            return
        }
        centralManager.scanForPeripherals(
            withServices: [managedIncomingBeaconUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        setStatus("ИЩУ GEELY_ANCS", color: .systemBlue)
        append("Central scan: stable beacon \(managedIncomingBeaconUUID.uuidString)")
    }

    private func advertisedCentralNamespace(
        _ advertisementData: [String: Any]
    ) -> UInt16? {
        if let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey]
                as? Data,
           let generation = decodeCentralNamespace(manufacturer, includesCompanyID: true) {
            return generation
        }
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey]
                as? [CBUUID: Data],
           let payload = serviceData[managedIncomingBeaconUUID],
           let generation = decodeCentralNamespace(payload, includesCompanyID: false) {
            return generation
        }
        return nil
    }

    private func decodeCentralNamespace(_ data: Data,
                                        includesCompanyID: Bool) -> UInt16? {
        let bytes = [UInt8](data)
        let offset = includesCompanyID ? 2 : 0
        if includesCompanyID {
            guard bytes.count >= 5 else { return nil }
            let companyID = UInt16(bytes[0]) | (UInt16(bytes[1]) << 8)
            guard companyID == managedIncomingManufacturerID else { return nil }
        }
        guard bytes.count >= offset + 3,
              bytes[offset] == managedIncomingNamespaceProtocol else { return nil }
        let generation = (UInt16(bytes[offset + 1]) << 8) | UInt16(bytes[offset + 2])
        return generation == 0 || generation == 0xFFFF ? nil : generation
    }

    private func applyCentralNamespace(_ generation: UInt16, persist: Bool = true) {
        centralNamespaceGeneration = generation
        centralServiceUUID = managedIncomingUUID(kind: 0, generation: generation)
        centralControlUUID = managedIncomingUUID(kind: 2, generation: generation)
        centralSecureUUID = managedIncomingUUID(kind: 3, generation: generation)
        centralWakeUUID = managedIncomingUUID(kind: 4, generation: generation)
        centralNamespaceResolved = true
        if persist {
            UserDefaults.standard.set(Int(generation), forKey: centralNamespacePreference)
        }
        append("Geely_ANCS namespace resolved · generation="
            + String(format: "%04X", Int(generation))
            + " · service=" + centralServiceUUID.uuidString)
    }

    private func managedIncomingUUID(kind: Int, generation: UInt16) -> CBUUID {
        CBUUID(string: String(format:
            "D2D9E4B%X-47F1-4E44-A8BB-A932FD5A%04X",
            kind, Int(generation)))
    }

    private func managedIncomingGeneration(from serviceUUID: CBUUID) -> UInt16? {
        let value = serviceUUID.uuidString.uppercased()
        let prefix = "D2D9E4B0-47F1-4E44-A8BB-A932FD5A"
        guard value.hasPrefix(prefix), value.count == 36,
              let generation = UInt16(value.suffix(4), radix: 16),
              generation != 0, generation != 0xFFFF else { return nil }
        return generation
    }

    private func connectCentral(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central, centralManager.state == .poweredOn else { return }
        guard centralNamespaceResolved else {
            startCentralScan()
            return
        }
        cancelCentralReconnect()
        centralManager.stopScan()
        if let previous = geelyPeripheral, previous.identifier != peripheral.identifier,
           previous.state != .disconnected {
            centralManager.cancelPeripheralConnection(previous)
        }
        geelyPeripheral = peripheral
        peripheral.delegate = self
        clearCentralRuntime(keepPeripheral: true)
        if peripheral.state == .connected {
            guard centralOwnerConfiguredForAncs else {
                setStatus("CENTRAL · ПЕРЕОТКРЫВАЮ С REQUIRES_ANCS", color: .systemOrange)
                append("Не принимаю чужой already-connected owner без RequiresANCS; "
                    + "однократно закрываю его до собственного connect")
                centralSystemAutoReconnectActive = false
                centralManager.cancelPeripheralConnection(peripheral)
                return
            }
            continueCentralConnected(peripheral)
            return
        }
        if peripheral.state == .connecting {
            guard centralOwnerConfiguredForAncs else {
                setStatus("CENTRAL · ОТМЕНЯЮ ЧУЖОЙ PENDING OWNER", color: .systemOrange)
                append("Не принимаю неизвестный .connecting owner без v34 restoration; "
                    + "следующий connect сразу получит RequiresANCS")
                centralSystemAutoReconnectActive = false
                centralManager.cancelPeripheralConnection(peripheral)
                return
            }
            armCentralConnectTimeout(peripheral)
            setStatus("CENTRAL · ЖДУ СИСТЕМНОЕ ПОДКЛЮЧЕНИЕ", color: .systemOrange)
            return
        }
        if peripheral.state == .disconnecting {
            setStatus("CENTRAL · ЖДУ DISCONNECT CALLBACK", color: .systemOrange)
            return
        }

        var options: [String: Any] = [
            CBConnectPeripheralOptionRequiresANCS: true,
            CBConnectPeripheralOptionNotifyOnConnectionKey: false,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: false
        ]
        let systemAutoReconnect: Bool
        if #available(iOS 17.0, *) {
            options[CBConnectPeripheralOptionEnableAutoReconnect] = true
            systemAutoReconnect = true
        } else {
            systemAutoReconnect = false
        }
        centralOwnerConfiguredForAncs = true
        centralSystemAutoReconnectActive = false
        centralManager.connect(peripheral, options: options)
        armCentralConnectTimeout(peripheral, replaceExisting: true)
        setStatus("CENTRAL · ПОДКЛЮЧЕНИЕ", color: .systemBlue)
        append("connect Geely_ANCS · singleOwner=true · RequiresANCS=true"
            + " · AutoReconnect=" + (systemAutoReconnect ? "system" : "manual")
            + " · namespace=" + String(format: "%04X", Int(centralNamespaceGeneration))
            + " · \(reason)")
    }

    private func beginCentralDiscovery(_ peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .idle else { return }
        cancelCentralConnectTimeout()
        centralHandshake = .discovering
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        cancelCentralDiscoveryWork()
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        lastAndroidReadAt = nil
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        // Normal starts use the persisted namespace and targeted discovery. A restored owner may
        // arrive before UserDefaults is available or after an app migration; in that one case an
        // unfiltered pass recovers the generation from the already-connected service database.
        if centralNamespaceResolved {
            peripheral.discoverServices([centralServiceUUID])
        } else {
            peripheral.discoverServices(nil)
        }
        setStatus("REQUIRES_ANCS · ИЩУ GATT", color: .systemOrange)
        append(centralNamespaceResolved
            ? "Single owner connected; discover dynamic D2D9 service "
                + centralServiceUUID.uuidString
            : "Restored owner connected; recover persisted D2D9 namespace in-place")
    }

    private func stopCentralRoute(cancelConnection: Bool) {
        cancelCentralReconnect()
        cancelCentralConnectTimeout()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralNamespaceResolved = false
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
        cancelCentralDiscoveryWork()
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralWakeSubscriptionAttempt = 0
        centralHandshake = .idle
        centralAncsAuthorized = false
        centralSecureLinkReady = false
        centralSecureReadAttempt = 0
        centralLinkSecurityChallengeObserved = false
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        if !keepPeripheral { geelyPeripheral = nil }
    }

    private func centralTelemetryValidity() -> (battery: Bool, network: Bool) {
        let snapshot = captureTelemetrySnapshot()
        return (snapshot.batteryLevel <= 100, snapshot.networkCode != 0)
    }

    private func centralReadyForGreen() -> Bool {
        let valid = centralTelemetryValidity()
        return centralHelperConfirmed
            && centralAncsAuthorized
            && centralAncsCccdConfirmed
            && centralB4Subscribed
            && valid.battery
            && valid.network
    }

    private func refreshCentralReadiness(_ source: String) {
        guard runRequested, role == .central else { return }
        let valid = centralTelemetryValidity()
        if centralReadyForGreen() {
            centralReadinessProofWorkItem?.cancel()
            centralReadinessProofWorkItem = nil
            setStatus("ANCS + B4 + ДАННЫЕ АКТИВНЫ", color: .systemGreen)
        } else {
            var missing: [String] = []
            if !centralHelperConfirmed { missing.append("Helper ACK") }
            if !centralAncsAuthorized { missing.append("iPhone ANCS permission") }
            if !centralAncsCccdConfirmed { missing.append("ANCS CCCD") }
            if !centralB4Subscribed { missing.append("B4 CCCD") }
            if !valid.battery { missing.append("battery") }
            if !valid.network { missing.append("network") }
            setStatus("ЖДУ: " + missing.joined(separator: " + "), color: .systemOrange)
        }
        append("Readiness · \(source) · helper=\(centralHelperConfirmed)"
            + " · authorized=\(centralAncsAuthorized)"
            + " · ancsCCCD=\(centralAncsCccdConfirmed) · b4CCCD=\(centralB4Subscribed)"
            + " · battery=\(valid.battery) · network=\(valid.network)")
        updateTelemetryLabel()
    }

    private func confirmCentralB4Subscription(_ source: String) {
        guard runRequested, role == .central else { return }
        let firstProof = !centralB4Subscribed
        centralB4Subscribed = true
        if firstProof { append("Android подписался на B4 CCCD · \(source)") }
        refreshCentralReadiness(source)
    }

    private func confirmCentralAncsReady(_ source: String) {
        guard runRequested, role == .central else { return }
        let firstProof = !centralAncsCccdConfirmed
        centralAncsCccdConfirmed = true
        if firstProof { append("Android подтвердил обе ANCS CCCD · \(source)") }
        refreshCentralReadiness(source)
    }

    private func observeCentralReadiness(_ reason: String,
                                         timeout: TimeInterval? = nil) {
        guard runRequested, role == .central, centralHelperConfirmed else { return }
        centralReadinessProofWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.centralReadinessProofWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralHelperConfirmed, !self.centralReadyForGreen() else { return }
            self.append("Полная готовность ещё не доказана после \(reason); один owner сохраняю")
            self.refreshCentralReadiness("timeout after \(reason)")
        }
        centralReadinessProofWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + (timeout ?? centralReadinessProofTimeout), execute: item)
    }

    private func cancelCentralConnectTimeout() {
        centralConnectTimeoutToken &+= 1
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutWorkItem = nil
        centralConnectTimeoutPeripheralID = nil
    }

    /// A normal Core Bluetooth connection completes well inside this window. If the same retained
    /// owner is still `.connecting` after the deadline, cancel that one attempt and require a fresh
    /// D2D9 advertisement. This mirrors the proven manual reconnect path without ever opening a
    /// second owner in parallel.
    private func armCentralConnectTimeout(_ peripheral: CBPeripheral,
                                          delay: TimeInterval? = nil,
                                          replaceExisting: Bool = false) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state != .connected else { return }
        if !replaceExisting, centralConnectTimeoutWorkItem != nil,
           centralConnectTimeoutPeripheralID == peripheral.identifier {
            return
        }
        centralConnectTimeoutWorkItem?.cancel()
        centralConnectTimeoutToken &+= 1
        let token = centralConnectTimeoutToken
        centralConnectTimeoutPeripheralID = peripheral.identifier
        let timeout = delay ?? centralConnectTimeout
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            guard token == self.centralConnectTimeoutToken else { return }
            self.centralConnectTimeoutWorkItem = nil
            self.centralConnectTimeoutPeripheralID = nil
            guard self.runRequested, self.role == .central,
                  peripheral.identifier == self.geelyPeripheral?.identifier else { return }
            switch peripheral.state {
            case .connected:
                self.cancelCentralConnectTimeout()
                peripheral.delegate = self
                self.append("Central уже connected при проверке timeout; продолжаю текущую фазу")
                self.continueCentralConnected(peripheral)
            case .connecting:
                let owner = self.centralSystemAutoReconnectActive
                    ? "system AutoReconnect" : "RequiresANCS connect"
                self.append("Central .connecting завис на \(Int(timeout)) с; "
                    + "отменяю только текущий \(owner) и запрашиваю свежий namespace")
                self.setStatus("CENTRAL · WATCHDOG ПЕРЕПОДКЛЮЧАЕТ", color: .systemOrange)
                self.resetCentralLink(reason: "stale .connecting watchdog · \(owner)")
            case .disconnecting:
                self.append("Central .disconnecting; жду системный didDisconnect без cancel")
            case .disconnected:
                if self.centralSystemAutoReconnectActive {
                    self.append("System AutoReconnect не восстановил link за \(Int(timeout)) с; "
                        + "отменяю только retained owner и запрашиваю свежий namespace")
                    self.setStatus("CENTRAL · WATCHDOG ПЕРЕПОДКЛЮЧАЕТ", color: .systemOrange)
                    self.resetCentralLink(reason: "stale system AutoReconnect watchdog")
                } else {
                    self.scheduleCentralReconnect(reason: "observed disconnected owner")
                }
            @unknown default:
                self.append("Central connection state unknown; owner не отменяю")
            }
        }
        centralConnectTimeoutWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: item)
    }

    private func scheduleCentralReconnect(reason: String) {
        guard runRequested, role == .central, centralReconnectWorkItem == nil else { return }
        clearCentralRuntime(keepPeripheral: true)
        let delayIndex = min(centralReconnectFailureCount,
                             centralReconnectDelays.count - 1)
        let delay = centralReconnectDelays[delayIndex]
        centralReconnectFailureCount = min(centralReconnectFailureCount + 1,
                                           centralReconnectDelays.count - 1)
        centralReconnectToken &+= 1
        let token = centralReconnectToken
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            guard token == self.centralReconnectToken else { return }
            self.centralReconnectWorkItem = nil
            guard self.runRequested, self.role == .central else { return }
            self.startCentralRouteIfPossible()
        }
        centralReconnectWorkItem = item
        setStatus("CENTRAL · ПЕРЕПОДКЛЮЧЕНИЕ", color: .systemOrange)
        append("Central reconnect через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func cancelCentralReconnect() {
        centralReconnectToken &+= 1
        centralReconnectWorkItem?.cancel()
        centralReconnectWorkItem = nil
    }

    /// Drops the whole Core Bluetooth connection when its service database or encryption state
    /// is no longer valid. Apple invalidates all discovered handles on disconnect; reconnecting
    /// only after a fresh D2D9 advertisement prevents the one-second stale-cache loop seen in v12.
    private func resetCentralLink(reason: String) {
        guard runRequested, role == .central, centralManager != nil else { return }
        if centralHardResetReason != nil { return }
        cancelCentralReconnect()
        cancelCentralConnectTimeout()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralHardResetReason = reason
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralNamespaceResolved = false
        UserDefaults.standard.removeObject(forKey: centralNamespacePreference)
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

    private func cancelCentralDiscoveryWork() {
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralCharacteristicDiscoveryWorkItem = nil
        centralServiceRediscoveryWorkItem?.cancel()
        centralServiceRediscoveryWorkItem = nil
    }

    /// Core Bluetooth rejects overlapping discovery calls with CBError.uuidNotAllowed on this
    /// firmware. Always leave the delegate callback first, then issue one unfiltered request.
    private func scheduleCentralCharacteristicDiscovery(_ peripheral: CBPeripheral,
                                                        service: CBService,
                                                        reason: String,
                                                        delay: TimeInterval = 0.25) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              centralService === service else { return }
        centralCharacteristicDiscoveryWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self, weak peripheral, weak service] in
            guard let self = self, let peripheral = peripheral, let service = service else {
                return
            }
            self.centralCharacteristicDiscoveryWorkItem = nil
            guard self.runRequested, self.role == .central,
                  peripheral.identifier == self.geelyPeripheral?.identifier,
                  peripheral.state == .connected,
                  self.centralHandshake == .discovering,
                  self.centralService === service,
                  peripheral.services?.contains(where: { $0 === service }) == true else { return }
            self.centralCharacteristicDiscoveryAttempt += 1
            self.append("D2D9 unfiltered characteristic discovery · attempt "
                + "\(self.centralCharacteristicDiscoveryAttempt) · \(reason)")
            peripheral.discoverCharacteristics(nil, for: service)
        }
        centralCharacteristicDiscoveryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func recoverCentralCharacteristicDiscovery(_ peripheral: CBPeripheral,
                                                       service: CBService,
                                                       reason: String) {
        if centralCharacteristicDiscoveryAttempt < centralCharacteristicDiscoveryLimit {
            scheduleCentralCharacteristicDiscovery(
                peripheral, service: service, reason: reason, delay: 0.5)
            return
        }
        scheduleCentralServiceRediscovery(
            peripheral, reason: "characteristic discovery exhausted · \(reason)")
    }

    /// An invalidated CBService must never be reused. Refresh the service list on the same
    /// connected ATT link before considering a destructive disconnect/reconnect cycle.
    private func scheduleCentralServiceRediscovery(_ peripheral: CBPeripheral,
                                                   reason: String,
                                                   delay: TimeInterval = 0.75) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected else { return }
        guard centralServiceRediscoveryAttempt < centralServiceRediscoveryLimit else {
            resetCentralLink(reason: "service rediscovery exhausted · \(reason)")
            return
        }
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralCharacteristicDiscoveryWorkItem = nil
        centralServiceRediscoveryWorkItem?.cancel()
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralHandshake = .discovering
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt += 1
        let attempt = centralServiceRediscoveryAttempt
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralServiceRediscoveryWorkItem = nil
            guard self.runRequested, self.role == .central,
                  peripheral.identifier == self.geelyPeripheral?.identifier,
                  peripheral.state == .connected,
                  self.centralHandshake == .discovering else { return }
            self.append("D2D9 targeted service rediscovery · attempt \(attempt) · \(reason)")
            peripheral.discoverServices([self.centralServiceUUID])
        }
        centralServiceRediscoveryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Uses characteristics only from the CBService instance delivered by the current
    /// didDiscoverServices callback. Core Bluetooth often keeps this already-valid list across a
    /// reconnect; asking it to rediscover the same UUIDs can itself return uuidNotAllowed.
    @discardableResult
    private func useCurrentCentralCharacteristics(_ peripheral: CBPeripheral,
                                                  service: CBService,
                                                  source: String) -> Bool {
        let characteristics = service.characteristics ?? []
        guard let secure = characteristics.first(where: { $0.uuid == centralSecureUUID }) else {
            return false
        }
        let control = characteristics.first(where: { $0.uuid == centralControlUUID })
        let wake = characteristics.first(where: { $0.uuid == centralWakeUUID })
        centralControlCharacteristic = control
        centralSecureCharacteristic = secure
        guard let currentControl = control,
              let currentWake = wake,
              currentControl.properties.contains(.write),
              secure.properties.contains(.read),
              secure.properties.contains(.write),
              currentWake.properties.contains(.notify) else {
            append("Geely_ANCS properties invalid: B2 WRITE, B3 READ/WRITE, B4 NOTIFY required")
            resetCentralLink(reason: "Geely_ANCS characteristic properties invalid")
            return true
        }
        centralControlCharacteristic = currentControl
        centralWakeCharacteristic = currentWake
        cancelCentralDiscoveryWork()
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        append("D2D9 single-owner characteristics ready · \(source)")
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
        // HA1176 deliberately returns one status-5 challenge on every new physical link. A second
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
        centralReconnectFailureCount = 0
        UserDefaults.standard.set(peripheral.identifier.uuidString,
                                  forKey: savedGeelyPeripheralPreference)
        let text = value.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        append("CURRENT LINK OK · saved Geely identity \(peripheral.identifier.uuidString)"
            + (text.isEmpty ? "" : " · `\(text)`"))
        centralSecureLinkReady = true
        centralAncsAuthorized = peripheral.ancsAuthorized
        publishTelemetry(reason: "central secure ready", force: true)
        continueCentralAfterSecurity(peripheral)
    }

    private func continueCentralAfterSecurity(_ peripheral: CBPeripheral) {
        centralAncsAuthorized = peripheral.ancsAuthorized
        guard centralAncsAuthorized else {
            centralHandshake = .waitingAncsAuthorization
            setStatus("РАЗРЕШИТЕ УВЕДОМЛЕНИЯ ДЛЯ GEELY_ANCS", color: .systemOrange)
            append("CURRENT LINK защищён, но iOS ancsAuthorized=false; "
                + "ANCS-READY не отправляю до системного разрешения")
            refreshCentralReadiness("waiting iPhone ANCS authorization")
            return
        }
        writeCentralAncsReady(peripheral)
    }

    private func writeCentralAncsReady(_ peripheral: CBPeripheral) {
        guard centralAncsAuthorized else {
            centralHandshake = .waitingAncsAuthorization
            setStatus("РАЗРЕШИТЕ УВЕДОМЛЕНИЯ ДЛЯ GEELY_ANCS", color: .systemOrange)
            return
        }
        guard let secure = centralSecureCharacteristic else {
            resetCentralLink(reason: "SECURE B3 missing before ANCS proof")
            return
        }
        centralHandshake = .writingAncsReady
        peripheral.writeValue(Data("ANCS-READY".utf8), for: secure, type: .withResponse)
        setStatus("REQUIRES_ANCS · ПОДТВЕРЖДАЮ OWNER", color: .systemOrange)
        append("WRITE ANCS-READY → same encrypted B3 owner")
    }

    /// Subscribe after PAIR/B3/ANCS-READY so Core Bluetooth can wake the Helper in background.
    /// Android's one-byte B4 notification contains no phone data; it only triggers a fresh public
    /// UIKit/CoreTelephony snapshot which is returned through the iPhone-owned F05/B4 relay.
    private func enableCentralWakeSubscription(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .ready,
              let wake = centralWakeCharacteristic else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        if wake.isNotifying {
            centralWakeSubscriptionAttempt = 0
            append("Android B4 wake CCCD active · \(reason)")
            return
        }
        centralWakeSubscriptionAttempt += 1
        let attempt = centralWakeSubscriptionAttempt
        peripheral.setNotifyValue(true, for: wake)
        append("Enable Android B4 wake CCCD · attempt \(attempt) · \(reason)")
        let watchdog = DispatchWorkItem { [weak self, weak peripheral, weak wake] in
            guard let self = self, let peripheral = peripheral, let wake = wake else { return }
            self.centralWakeSubscriptionWorkItem = nil
            guard self.runRequested, self.role == .central,
                  peripheral.identifier == self.geelyPeripheral?.identifier,
                  peripheral.state == .connected,
                  self.centralHandshake == .ready,
                  self.centralWakeCharacteristic === wake,
                  !wake.isNotifying else { return }
            self.scheduleCentralWakeSubscriptionRetry(
                peripheral, reason: "CCCD callback timeout")
        }
        centralWakeSubscriptionWorkItem = watchdog
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: watchdog)
    }

    private func scheduleCentralWakeSubscriptionRetry(_ peripheral: CBPeripheral,
                                                      reason: String) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .ready else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        let delays: [TimeInterval] = [1, 2, 5, 10, 30, 60]
        let delay = delays[min(max(0, centralWakeSubscriptionAttempt - 1), delays.count - 1)]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralWakeSubscriptionWorkItem = nil
            self.enableCentralWakeSubscription(peripheral, reason: "retry after \(reason)")
        }
        centralWakeSubscriptionWorkItem = item
        append("Android B4 wake CCCD retry через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func responseData(for characteristic: CBCharacteristic) -> Data? {
        switch characteristic.uuid {
        case infoUUID:
            return Data("\(logicalName)/32/realtime-single-owner".utf8)
        case secureUUID:
            return Data("SECURE IPHONE OK".utf8)
        case telemetryUUID, telemetryRelayUUID:
            let snapshot = captureTelemetrySnapshot()
            lastPublishedSnapshot = snapshot
            lastTelemetryPublishAt = Date()
            return makeTelemetryFrame(for: snapshot, incrementSequence: true)
        default:
            return nil
        }
    }

    private func isLocalTelemetryUUID(_ uuid: CBUUID) -> Bool {
        return uuid == telemetryUUID || uuid == telemetryRelayUUID
    }

    // MARK: - Exact Helper telemetry

    private func startTelemetryMonitoring() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        phoneLocked = !UIApplication.shared.isProtectedDataAvailable
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
        center.addObserver(self, selector: #selector(phoneDidLock),
                           name: UIApplication.protectedDataWillBecomeUnavailableNotification,
                           object: nil)
        center.addObserver(self, selector: #selector(phoneDidUnlock),
                           name: UIApplication.protectedDataDidBecomeAvailableNotification,
                           object: nil)
        let timer = Timer(timeInterval: telemetrySampleInterval, repeats: true) {
            [weak self] _ in self?.publishTelemetry(reason: "1s control")
        }
        timer.tolerance = 0.1
        telemetryTimer = timer
        RunLoop.main.add(timer, forMode: .common)
        publishTelemetry(reason: "startup", force: true)
    }

    @objc private func applicationBecameActive() {
        phoneLocked = false
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

    @objc private func phoneDidLock() {
        phoneLocked = true
        publishTelemetryOnMain(reason: "phone locked")
    }

    @objc private func phoneDidUnlock() {
        phoneLocked = false
        publishTelemetryOnMain(reason: "phone unlocked")
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
        if role == .central, centralHelperConfirmed, changed {
            refreshCentralReadiness("telemetry changed")
        }
        let backgroundWake = reason == "KX11 background wake poll"
        let backgroundLogDue = Date().timeIntervalSince(lastBackgroundWakeLogAt) >= 60
        if changed || force && (!backgroundWake || backgroundLogDue) {
            if backgroundWake { lastBackgroundWakeLogAt = Date() }
            let power = String(snapshot.powerFlags, radix: 16, uppercase: true)
            append("B4 snapshot · \(reason) · battery=\(snapshot.batteryLevel)"
                + " · powerFlags=0x\(power) · network=\(currentNetworkType())"
                + " · locked=\(phoneLocked)"
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

    /// Fixed frame: A5, version, level, power/lock flags, network, sequence LE, CRC-8/ATM.
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
        // Bit 5 is independent of the battery-valid bits and is backward compatible with v20.
        if phoneLocked { flags |= 0x20 }

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
            let relayRead = lastAndroidReadAt.map {
                let formatter = DateFormatter()
                formatter.dateFormat = "HH:mm:ss"
                return formatter.string(from: $0)
            } ?? "ещё не было"
            let valid = centralTelemetryValidity()
            telemetryLabel.text = "\(batteryDescription()) · \(currentNetworkType()) · "
                + (phoneLocked ? "заблокирован" : "разблокирован") + "\n"
                + "B4 RELAY: READ \(relayRead) · подписок: \(telemetrySubscribers.count) · "
                + "helper=\(centralHelperConfirmed ? "OK" : "—") · "
                + "ANCS=\(centralAncsCccdConfirmed ? "OK" : "—") · "
                + "B4=\(centralB4Subscribed ? "OK" : "—") · "
                + "data=\(valid.battery && valid.network ? "OK" : "—")"
            return
        }
        let read = lastAndroidReadAt.map {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            return formatter.string(from: $0)
        } ?? "ещё не было"
        telemetryLabel.text = "\(batteryDescription()) · \(currentNetworkType()) · "
            + (phoneLocked ? "заблокирован" : "разблокирован") + "\n"
            + "Android READ: \(read) · подписок: \(telemetrySubscribers.count)"
    }

    private func updateConnectionStatus() {
        guard runRequested else { return }
        if role == .central {
            if centralHandshake == .ready || centralHelperConfirmed {
                refreshCentralReadiness("status refresh")
            } else if geelyPeripheral?.state == .connected {
                setStatus("ПОДКЛЮЧЕНО · SINGLE-OWNER HANDSHAKE", color: .systemOrange)
            } else if geelyPeripheral?.state == .connecting {
                setStatus("CENTRAL · СИСТЕМА ПОДКЛЮЧАЕТ", color: .systemBlue)
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
            cancelCentralConnectTimeout()
            centralOwnerConfiguredForAncs = false
            centralSystemAutoReconnectActive = false
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
        let savedIdentifier = UserDefaults.standard.string(
            forKey: savedGeelyPeripheralPreference).flatMap(UUID.init(uuidString:))
        let preferred = savedIdentifier.flatMap { identifier in
            peripherals.first(where: { $0.identifier == identifier })
        }
        guard let restored = preferred
                ?? peripherals.first(where: { $0.state == .connected })
                ?? peripherals.first(where: { $0.state == .connecting })
                ?? peripherals.first else { return }
        central.stopScan()
        cancelCentralReconnect()
        centralHardResetReason = nil
        centralRequireFreshAdvertisement = false
        centralOwnerConfiguredForAncs = true
        centralSystemAutoReconnectActive = restored.state == .connecting
        if !centralNamespaceResolved,
           let generation = restored.services?.compactMap({
               managedIncomingGeneration(from: $0.uuid)
           }).first {
            applyCentralNamespace(generation)
        }
        geelyPeripheral = restored
        restored.delegate = self
        UserDefaults.standard.set(restored.identifier.uuidString,
                                  forKey: savedGeelyPeripheralPreference)
        clearCentralRuntime(keepPeripheral: true)
        append("Central restore owner retained · "
            + restored.identifier.uuidString + " · state=\(restored.state.rawValue)"
            + " · namespace=\(centralNamespaceResolved ? "saved" : "recover in-place")")
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested, self.role == .central else { return }
            switch restored.state {
            case .connected:
                self.continueCentralConnected(restored)
            case .connecting:
                self.armCentralConnectTimeout(restored, replaceExisting: true)
                self.setStatus("CENTRAL · RESTORED CONNECTING", color: .systemBlue)
            case .disconnecting:
                self.setStatus("CENTRAL · RESTORED DISCONNECTING", color: .systemOrange)
            case .disconnected:
                self.scheduleCentralReconnect(reason: "restored owner is disconnected")
            @unknown default:
                self.scheduleCentralReconnect(reason: "restored owner state unknown")
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard runRequested, role == .central,
              geelyPeripheral == nil || geelyPeripheral?.identifier == peripheral.identifier
              else { return }
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey]
            as? [CBUUID] ?? []
        guard advertisedServices.contains(managedIncomingBeaconUUID),
              let generation = advertisedCentralNamespace(advertisementData) else { return }
        applyCentralNamespace(generation)
        append("Найден Geely_ANCS · id=\(peripheral.identifier.uuidString) · RSSI=\(RSSI)")
        centralRequireFreshAdvertisement = false
        connectCentral(peripheral, reason: "filtered namespace beacon")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        if peripheral.identifier == geelyPeripheral?.identifier {
            cancelCentralConnectTimeout()
        }
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        guard centralOwnerConfiguredForAncs else {
            append("didConnect не принят: owner не был открыт/восстановлен с RequiresANCS")
            central.cancelPeripheralConnection(peripheral)
            return
        }
        guard centralHardResetReason == nil else {
            append("Late didConnect ignored: BLE reset уже начат")
            central.cancelPeripheralConnection(peripheral)
            return
        }
        cancelCentralReconnect()
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        append("Central connected · \(peripheral.identifier.uuidString)")
        continueCentralConnected(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        cancelCentralConnectTimeout()
        append("Central connect failed · \(error?.localizedDescription ?? "без ошибки")")
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        if let hardReset = hardReset {
            centralOwnerConfiguredForAncs = false
            centralSystemAutoReconnectActive = false
            centralRequireFreshAdvertisement = true
            centralNamespaceResolved = false
            clearCentralRuntime(keepPeripheral: false)
            scheduleCentralReconnect(reason: "fresh advertisement after \(hardReset)")
        } else {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            scheduleCentralReconnect(reason: "same owner after didFailToConnect")
        }
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
        cancelCentralConnectTimeout()
        append("Central disconnected · systemReconnect=\(isReconnecting) · "
            + (error?.localizedDescription ?? "без ошибки"))
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        if let hardReset = hardReset {
            centralRequireFreshAdvertisement = true
            centralNamespaceResolved = false
            clearCentralRuntime(keepPeripheral: false)
            guard runRequested, role == .central else { return }
            scheduleCentralReconnect(reason: "fresh advertisement after \(hardReset)")
            return
        }

        // An ordinary radio loss keeps the restored CBPeripheral, dynamic namespace, F05 GATT
        // server and Core Bluetooth auto-reconnect request. Never cancel a system-owned attempt.
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        guard runRequested, role == .central else { return }
        if isReconnecting {
            centralOwnerConfiguredForAncs = true
            centralSystemAutoReconnectActive = true
            centralReconnectFailureCount = 0
            append("Core Bluetooth auto-reconnect retained; no cancel and no second connect")
            setStatus("CENTRAL · SYSTEM RECONNECT", color: .systemBlue)
            armCentralConnectTimeout(
                peripheral,
                delay: centralConnectTimeout,
                replaceExisting: true
            )
            return
        }
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        scheduleCentralReconnect(reason: "ordinary disconnect; retained owner/namespace")
    }

    func centralManager(_ central: CBCentralManager,
                        didUpdateANCSAuthorizationFor peripheral: CBPeripheral) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        centralAncsAuthorized = peripheral.ancsAuthorized
        append("ANCS authorization changed · allowed=\(centralAncsAuthorized)"
            + " · handshake=\(centralHandshake)")
        if !centralAncsAuthorized {
            setStatus("РАЗРЕШИТЕ УВЕДОМЛЕНИЯ ДЛЯ GEELY_ANCS", color: .systemOrange)
            refreshCentralReadiness("iPhone denied ANCS")
        } else if centralSecureLinkReady
                    && centralHandshake == .waitingAncsAuthorization {
            append("iPhone разрешил ANCS; продолжаю ANCS-READY на сохранённом owner")
            writeCentralAncsReady(peripheral)
        } else {
            refreshCentralReadiness("iPhone ANCS authorized")
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
            scheduleCentralServiceRediscovery(
                peripheral, reason: "service discovery failed · \(detail)")
            return
        }
        if !centralNamespaceResolved,
           let generation = peripheral.services?.compactMap({
               managedIncomingGeneration(from: $0.uuid)
           }).first {
            applyCentralNamespace(generation)
            append("Restored owner namespace recovered without disconnect")
        }
        guard let service = peripheral.services?.first(where: {
            $0.uuid == centralServiceUUID
        }) else {
            append("Geely_ANCS service отсутствует после подключения")
            scheduleCentralServiceRediscovery(peripheral, reason: "D2D9 service missing")
            return
        }
        centralService = service
        if useCurrentCentralCharacteristics(peripheral, service: service,
                                            source: "current Core Bluetooth cache") {
            return
        }
        scheduleCentralCharacteristicDiscovery(
            peripheral, service: service, reason: "current service callback")
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              service.uuid == centralServiceUUID,
              let activeService = centralService,
              activeService === service,
              peripheral.services?.contains(where: { $0 === service }) == true else {
            append("Игнорирую stale characteristic callback от прошлого D2D9 поколения")
            return
        }
        if let error = error {
            let detail = centralErrorDescription(error)
            if isCentralUuidNotAllowedError(error) {
                if useCurrentCentralCharacteristics(
                    peripheral, service: service,
                    source: "current cache after uuidNotAllowed"
                ) {
                    return
                }
                // Repeating the identical discovery on this ATT owner reproduced CBError 8
                // indefinitely in the in-car trace. This is an exceptional corrupt/stale GATT
                // database, so request a new namespace; ordinary radio loss never takes this path.
                append("Characteristic discovery запрещён на текущем owner; "
                    + "не повторяю ту же операцию, запрашиваю свежий namespace")
                resetCentralLink(reason: "uuidNotAllowed on current ATT owner · \(detail)")
                return
            }
            append("Discover characteristics error: \(detail)")
            resetCentralLink(reason: "characteristic discovery failed · "
                + detail)
            return
        }
        guard useCurrentCentralCharacteristics(peripheral, service: service,
                                               source: "fresh characteristic callback") else {
            append("Geely_ANCS incomplete: CONTROL/SECURE/WAKE required; повторяю без overlap")
            recoverCentralCharacteristicDiscovery(
                peripheral, service: service, reason: "B2/B3 incomplete")
            return
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        if characteristic.uuid == centralControlUUID {
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
        if characteristic.uuid == centralSecureUUID,
           centralHandshake == .writingAncsReady {
            if let error = error {
                let detail = centralErrorDescription(error)
                append("WRITE ANCS-READY error: \(detail)")
                resetCentralLink(reason: "same-owner ANCS-READY failed · \(detail)")
            } else {
                centralHandshake = .ready
                centralReconnectFailureCount = 0
                centralHelperConfirmed = true
                publishServiceIfPossible()
                publishTelemetry(reason: "single-owner helper confirmed", force: true)
                append("ANCS-READY accepted on the original RequiresANCS owner; link retained")
                enableCentralWakeSubscription(peripheral, reason: "same-owner ANCS-READY")
                refreshCentralReadiness("Helper ANCS-READY")
                observeCentralReadiness("same-owner ANCS-READY")
            }
            return
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard peripheral.identifier == geelyPeripheral?.identifier else { return }
        if characteristic.uuid == centralWakeUUID {
            if let error = error {
                append("Android B4 wake notification error: \(centralErrorDescription(error))")
                scheduleCentralWakeSubscriptionRetry(
                    peripheral, reason: "wake notification error")
            } else {
                publishTelemetry(reason: "KX11 background wake poll", force: true)
            }
            return
        }
        guard characteristic.uuid == centralSecureUUID else { return }
        if let error = error {
            retryCentralSecure(peripheral, error: error)
            return
        }
        markCentralReady(peripheral, value: characteristic.value)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              characteristic.uuid == centralWakeUUID else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        if let error = error {
            append("Android B4 wake CCCD error: \(centralErrorDescription(error))")
            scheduleCentralWakeSubscriptionRetry(peripheral, reason: "CCCD error")
            return
        }
        if characteristic.isNotifying {
            centralWakeSubscriptionAttempt = 0
            append("Android B4 wake CCCD confirmed; background telemetry wake active")
            publishTelemetry(reason: "wake CCCD confirmed", force: true)
        } else {
            scheduleCentralWakeSubscriptionRetry(peripheral, reason: "CCCD disabled")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard runRequested, role == .central,
              peripheral.identifier == geelyPeripheral?.identifier,
              invalidatedServices.contains(where: {
                  $0.uuid == centralServiceUUID
              }) else { return }
        append("Geely_ANCS service invalidated; обновляю services на текущем BLE link")
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralWakeCharacteristic = nil
        centralServiceRediscoveryAttempt = 0
        scheduleCentralServiceRediscovery(peripheral, reason: "D2D9 service invalidated")
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
        let expectedService = role == .central ? telemetryRelayServiceUUID : serviceUUID
        if let service = services.first(where: { $0.uuid == expectedService }) {
            for characteristic in service.characteristics ?? [] {
                guard let mutable = characteristic as? CBMutableCharacteristic else { continue }
                switch characteristic.uuid {
                case infoUUID: infoCharacteristic = mutable
                case controlUUID: controlCharacteristic = mutable
                case secureUUID: secureCharacteristic = mutable
                case telemetryUUID, telemetryRelayUUID: telemetryCharacteristic = mutable
                default: break
                }
            }
            servicePublished = role == .central
                ? telemetryCharacteristic != nil
                : infoCharacteristic != nil && controlCharacteristic != nil
                    && secureCharacteristic != nil && telemetryCharacteristic != nil
            publishedServiceUUID = service.uuid
            serviceAddPending = false
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested else { return }
            if self.servicePublished {
                if self.role == .peripheral { self.startAdvertising() }
                else { self.updateConnectionStatus() }
            } else { self.publishServiceIfPossible() }
            self.publishTelemetry(reason: "state restoration", force: true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService,
                           error: Error?) {
        serviceAddPending = false
        let expectedService = role == .central ? telemetryRelayServiceUUID : serviceUUID
        guard runRequested, service.uuid == expectedService else {
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
        publishedServiceUUID = service.uuid
        if role == .central {
            append("B4 telemetry relay опубликован на iPhone GATT owner")
            updateConnectionStatus()
        } else {
            append("Единый GATT \(logicalName) опубликован")
            startAdvertising()
        }
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
        guard runRequested, isLocalTelemetryUUID(characteristic.uuid) else { return }
        telemetrySubscribers.insert(central.identifier)
        append("KX11 subscribed B4 · \(central.identifier.uuidString)")
        confirmCentralB4Subscription("CCCD subscription")
        updateConnectionStatus()
        publishTelemetry(reason: "B4 subscribed", force: true)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard isLocalTelemetryUUID(characteristic.uuid) else { return }
        telemetrySubscribers.remove(central.identifier)
        append("KX11 unsubscribed B4 · \(central.identifier.uuidString)")
        if role == .central && telemetrySubscribers.isEmpty {
            // Rebuild only the reverse Android client. The protected iPhone Central owner and
            // its auto-reconnect request remain untouched.
            centralB4Subscribed = false
            append("B4 CCCD снята; RequiresANCS owner и namespace сохраняю")
            refreshCentralReadiness("B4 unsubscribe")
        }
        updateTelemetryLabel()
        updateConnectionStatus()
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainTelemetryNotification()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveRead request: CBATTRequest) {
        guard runRequested else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        if role == .central && !isLocalTelemetryUUID(request.characteristic.uuid) {
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
        if isLocalTelemetryUUID(request.characteristic.uuid) {
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
        guard runRequested else {
            requests.forEach { peripheral.respond(to: $0, withResult: .requestNotSupported) }
            return
        }
        for request in requests {
            let command = request.value.flatMap { String(data: $0, encoding: .utf8) }?
                .trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
            let ancsAck = role == .central
                && request.characteristic.uuid == telemetryRelayUUID
                && command == "ANCS-SUBSCRIBED"
            let bootstrapCommand = role == .peripheral
                && ((request.characteristic.uuid == controlUUID && command == "PAIR")
                    || (request.characteristic.uuid == secureUUID && command == "ANCS"))
            let accepted = request.offset == 0 && (ancsAck || bootstrapCommand)
            peripheral.respond(to: request,
                               withResult: accepted ? .success : .requestNotSupported)
            append("KX11 WRITE \(request.characteristic.uuid.uuidString) `\(command)` "
                + (accepted ? "OK" : "REJECTED"))
            if accepted && ancsAck {
                confirmCentralAncsReady("F05/B4 ANCS-SUBSCRIBED")
            }
        }
    }
}
