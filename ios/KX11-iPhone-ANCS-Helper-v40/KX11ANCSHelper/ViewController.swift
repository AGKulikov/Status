import CoreBluetooth
import CoreTelephony
import UIKit

/// Helper v40 keeps one long-lived RequiresANCS Central owner and performs one minimal security
/// handshake against Android's permanent F04 service. PAIR and the encrypted B3 read prove the
/// current ACL before Android registers its reverse ANCS client; no UUID rotation or connection
/// watchdog is used. Restoration ownership claims are bounded: the first may reconcile a restored
/// request after exact F04 reachability, while a second is allowed only when Core Bluetooth's F04
/// system table proves a physical link without an app-local didConnect callback. The iPhone-owned
/// F05 relay carries telemetry and Android's authoritative post-CCCD proof. A corrupt/stale F04
/// namespace may consume one destructive reconnect for the whole retained owner lineage; terminal
/// callbacks never re-arm that budget.
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
        case writingAncsReady
        case ready
    }

    private struct TelemetrySnapshot: Equatable {
        let batteryLevel: UInt8
        let powerFlags: UInt8
        let networkCode: UInt8
    }

    /// A delayed connect is represented as data before any delay begins. Keeping the exact
    /// CBPeripheral strongly referenced closes the power-off race between a terminal callback and
    /// its delayed reconnect closure.
    private struct DeferredCentralConnectIntent {
        let peripheral: CBPeripheral
        let reason: String
        let notBefore: Date
        let token: UInt64
    }

    // F04 is the permanent link-anchor UUID. Central mode scans the stable FFFF beacon and uses
    // only F04's B2/B3/B4 handshake; Peripheral mode keeps the legacy diagnostic service.
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
    /// Local GATT publication is an object lineage, not a UUID flag. `removeAllServices()` does
    /// not prevent a late didAdd for the removed same-UUID object, so every new/restored service
    /// gets a monotonic generation and exact pending/published object identity.
    private var localServicePublicationGeneration: UInt64 = 0
    private var pendingLocalService: CBMutableService?
    private var pendingLocalServiceGeneration: UInt64?
    private var publishedLocalService: CBMutableService?
    private var publishedLocalServiceGeneration: UInt64?
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
    /// True only when this manager issued the app-local connect with RequiresANCS or restored it.
    private var centralOwnerConfiguredForAncs = false
    /// iOS 17 normally owns reconnect after a callback with isReconnecting=true. While this is
    /// set, Helper never issues a competing connect or cancel.
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
    /// Automatic protocol recovery may cancel the retained RequiresANCS owner only once for the
    /// current CBPeripheral/restoration lineage. This budget intentionally survives every
    /// didDisconnect/didConnect pair: clearing `centralHardResetReason` at a terminal callback is
    /// not evidence of a new GATT database.
    private var centralDestructiveRecoveryOwnerID: UUID?
    private var centralDestructiveRecoveryConsumed = false
    private var centralDestructiveRecoveryWaitingForFreshF04 = false
    private var centralDestructiveRecoveryFirstReason: String?
    private var centralManualReconnectPending = false
    private var centralRequireFreshAdvertisement = false
    /// A namespace that already produced an invalid ATT database must never be accepted as
    /// "fresh" merely because Android is still advertising it. Wait for a different generation.
    private var centralRejectedNamespaceGeneration: UInt16?
    private var centralRejectedNamespaceLogAt = Date.distantPast
    /// State restoration may arrive while CBCentralManager is still unknown/resetting. Core
    /// Bluetooth commands are legal only after poweredOn, so retain the owner and resume later.
    private var centralRestorationAwaitingPower = false
    /// Core Bluetooth can restore a pending owner but omit both didConnect and didFailToConnect.
    /// Do not time that request out: only the matching stable F04 system/beacon proof may arm it.
    private var centralRestoredPendingOwner = false
    private var centralRestorationRecoveryAttempted = false
    private var centralRestorationRecoveryWorkItem: DispatchWorkItem?
    /// A read-only reconciliation probe is allowed while the restored request is pending. It
    /// never cancels by age; it only observes the system F04 table and arms recovery on proof.
    private var centralRestorationProofProbeWorkItem: DispatchWorkItem?
    private var centralRestorationProofProbeAttempt = 0
    /// Set only after the one evidence-driven cancel was issued. The replacement connect is
    /// deferred until a terminal callback or the same owner's `.disconnected` state proves close.
    private var centralRestorationReconnectPending = false
    /// Core Bluetooth can also omit the terminal delegate callback after canceling a restored
    /// request. Observe CBPeripheral.state without issuing any second cancel/connect; `.disconnected`
    /// is an equivalent terminal boundary for reopening the same app-local owner.
    private var centralRestorationPostCancelProbeWorkItem: DispatchWorkItem?
    private var centralRestorationPostCancelProbeAttempt = 0
    /// A restored B4 subscription is usable only when both managers independently restore the
    /// same persisted owner: Central as already `.connected`, and Peripheral with the exact F05
    /// characteristic object listing that owner in subscribedCentrals. Either callback may arrive
    /// first. The hint is one-shot and never survives a fresh didConnect or disconnect.
    private var centralRestoredConnectedOwner: CBPeripheral?
    private var centralRestoredF05Characteristic: CBMutableCharacteristic?
    private var centralRestoredF05SubscriberIDs: Set<UUID> = []
    private var centralRestoredB4HintConsumed = false
    /// Number of destructive ownership claims in the current restoration lineage. Claim #1
    /// closes the stale restored request. Claim #2 is permitted only after an exact F04 entry in
    /// retrieveConnectedPeripherals proves that a fresh app-local connect reached the physical
    /// link but lost didConnect. No third claim exists.
    private var centralRestoreOwnershipClaimCount = 0
    private let centralRestoreOwnershipClaimLimit = 2
    private var centralRestoreFreshConnectAwaitingCallback = false
    private var centralRestoreFreshConnectProofWorkItem: DispatchWorkItem?
    private var centralRestoreFreshConnectProofToken: UInt64 = 0
    private let centralRestoreFreshConnectCallbackGrace: TimeInterval = 1.5
    private var centralDeferredStopScan = false
    private var centralDeferredCancellations: [UUID: CBPeripheral] = [:]
    private var centralDeferredConnectIntent: DeferredCentralConnectIntent?
    private var centralDeferredConnectWorkItem: DispatchWorkItem?
    private var centralDeferredConnectToken: UInt64 = 0
    /// Manual/hard-reset cancel may cross a Bluetooth power transition before its terminal
    /// callback. Observe only state until the exact owner becomes disconnected, then materialize
    /// one deferred connect intent through the poweredOn/F05 route.
    private var centralPendingTerminalStateProbeWorkItem: DispatchWorkItem?
    private var centralPendingTerminalStateProbeAttempt = 0
    private var centralReconnectFailureCount = 0
    private let centralReconnectDelays: [TimeInterval] = [1, 2, 5, 10, 20, 30]
    private var centralHelperConfirmed = false
    /// Snapshot of CBPeripheral.ancsAuthorized for the retained RequiresANCS owner.
    private var centralAncsAuthorized = false
    /// True only after Android has subscribed both real ANCS CCCDs and returned the matched
    /// F05/B4 ANCS-SUBSCRIBED proof. This is stronger than a transient Core Bluetooth snapshot.
    private var centralAncsAccessProven = false
    private var centralAncsAuthorizationCallbackObserved = false
    /// ANCS-READY is an exact-owner/B3 gate, not an ANCS privacy decision. Keep an explicit bit so
    /// a late authorization callback or duplicate B3 value can never write it twice.
    private var centralAncsReadyWriteIssued = false
    /// True only after CURRENT LINK B3 proved encryption on this exact ATT owner.
    private var centralSecureLinkReady = false
    private var centralB4Subscribed = false
    private var centralAncsCccdConfirmed = false
    private var centralReadinessProofWorkItem: DispatchWorkItem?
    private let centralReadinessProofTimeout: TimeInterval = 30
    private let centralRestorationEvidenceGrace: TimeInterval = 1.5
    private let centralRestorationProofProbeDelays: [TimeInterval] = [0.5, 1, 2, 5, 10]
    private let centralRestorationPostCancelProbeDelays: [TimeInterval] = [0.25, 0.5, 1, 2, 5]
    private let centralPendingTerminalStateProbeDelays: [TimeInterval] = [0.25, 0.5, 1, 2, 5]

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

        append("v40: один destructive F04 recovery на owner lineage; manual отдельно")
        append("Один Central owner: RequiresANCS=true с первого connect")
        append("PAIR → B3 → ANCS-READY выполняются на том же owner без UUID rotation")
        append("Pending connect не имеет watchdog; rescue только по F04 system/beacon proof")
        append("Зелёный: connected owner + ANCS permission + ANCS CCCD + B4 CCCD + данные")
        append("Все Central-команды ждут poweredOn; restoration возобновляется автоматически")
        append("iOS 17+ AutoReconnect и ручной backoff взаимоисключающие; второго connect нет")
        append("Central/Peripheral restoration IDs и Android identity сохраняются")
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
        centralRestorationRecoveryWorkItem?.cancel()
        centralRestorationProofProbeWorkItem?.cancel()
        centralRestorationPostCancelProbeWorkItem?.cancel()
        centralDeferredConnectWorkItem?.cancel()
        centralPendingTerminalStateProbeWorkItem?.cancel()
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
        titleLabel.text = "KX11 ANCS HELPER v40"
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
            append("Ручной reconnect: отменяю только текущий owner и жду didDisconnect")
            cancelCentralReconnect()
            clearCentralDeferredConnectIntent()
            clearCentralPendingTerminalStateObservation()
            clearCentralRestorationRecovery()
            resetCentralRestoreOwnershipClaims()
            clearCentralRestoredB4Hint()
            centralHardResetReason = nil
            // CONTRACT_V40_MANUAL_RECONNECT_IS_INDEPENDENT: a user action may reopen the exact
            // owner even after automatic destructive recovery is spent. It does not re-arm the
            // automatic budget; only a new owner/route or exact F04 Service Changed may do that.
            centralDestructiveRecoveryWaitingForFreshF04 = false
            centralManualReconnectPending = true
            centralHelperConfirmed = false
            centralB4Subscribed = false
            centralAncsCccdConfirmed = false
            if let current = geelyPeripheral, current.state != .disconnected {
                cancelCentralConnectionSafely(current, manager: centralManager,
                                                reason: "explicit manual reconnect")
                setStatus("CENTRAL · РУЧНОЙ DISCONNECT", color: .systemOrange)
            } else if let current = geelyPeripheral {
                centralManualReconnectPending = false
                centralOwnerConfiguredForAncs = false
                clearCentralRuntime(keepPeripheral: true)
                issueCentralConnect(current, reason: "explicit manual reconnect")
            } else {
                centralManualReconnectPending = false
                startCentralRouteIfPossible()
            }
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
            guard let published = publishedLocalService,
                  publishedLocalServiceGeneration == localServicePublicationGeneration,
                  published.uuid == publishedServiceUUID else {
                append("Local GATT publication lineage invalid; republishing current role")
                clearPublishedService()
                publishServiceIfPossible()
                return
            }
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
        pendingLocalService = service
        pendingLocalServiceGeneration = localServicePublicationGeneration
        serviceAddPending = true
        append(relay
            ? "Публикую B4 telemetry relay generation 5 без отдельной рекламы"
            : "Публикую один GATT \(logicalName), B4=READ+NOTIFY")
        peripheralManager.add(service)
    }

    private func clearPublishedService() {
        // CONTRACT_V39_LOCAL_SERVICE_GENERATION_INVALIDATES_LATE_DIDADD
        localServicePublicationGeneration &+= 1
        servicePublished = false
        serviceAddPending = false
        pendingLocalService = nil
        pendingLocalServiceGeneration = nil
        publishedLocalService = nil
        publishedLocalServiceGeneration = nil
        infoCharacteristic = nil
        controlCharacteristic = nil
        secureCharacteristic = nil
        telemetryCharacteristic = nil
        publishedServiceUUID = nil
        telemetrySubscribers.removeAll()
        pendingTelemetryFrames.removeAll()
    }

    private func startAdvertising() {
        guard runRequested, role == .peripheral, servicePublished,
              let published = publishedLocalService,
              publishedLocalServiceGeneration == localServicePublicationGeneration,
              published.uuid == serviceUUID,
              publishedServiceUUID == serviceUUID,
              peripheralManager != nil,
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

    private func clearCentralRestoredB4Hint() {
        centralRestoredConnectedOwner = nil
        centralRestoredF05Characteristic = nil
        centralRestoredF05SubscriberIDs.removeAll()
        centralRestoredB4HintConsumed = false
    }

    private func consumeRestoredB4HintIfEligible(_ peripheral: CBPeripheral) -> Bool {
        guard !centralRestoredB4HintConsumed,
              centralRestoredConnectedOwner === peripheral,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              let raw = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedIdentifier = UUID(uuidString: raw),
              peripheral.identifier == savedIdentifier,
              let restoredF05 = centralRestoredF05Characteristic,
              restoredF05 === telemetryCharacteristic,
              centralRestoredF05SubscriberIDs.contains(savedIdentifier) else { return false }
        // CONTRACT_V39_TWO_SIDED_RESTORED_B4_HINT
        centralRestoredB4HintConsumed = true
        centralRestoredConnectedOwner = nil
        centralRestoredF05Characteristic = nil
        centralRestoredF05SubscriberIDs.removeAll()
        append("Exact two-sided restoration proof restored B4 subscription for current owner")
        return true
    }

    private func continueCentralConnected(_ peripheral: CBPeripheral,
                                          allowRestoredB4Hint: Bool = false) {
        peripheral.delegate = self
        bindCentralDestructiveRecoveryLineage(peripheral,
                                              source: "connected RequiresANCS owner")
        if centralHandshake == .idle {
            centralAncsAuthorized = peripheral.ancsAuthorized
            centralAncsAuthorizationCallbackObserved = false
            centralAncsAccessProven = false
            centralAncsReadyWriteIssued = false
            centralSecureLinkReady = false
            centralHelperConfirmed = false
            // CONTRACT_V39_NEW_DIDCONNECT_REQUIRES_FRESH_B4_SUBSCRIBE: only the no-new-didConnect
            // `.connected` restoration path may consume the two-sided one-shot hint. Every fresh
            // app-local owner starts false and waits for exact current F05 didSubscribe.
            centralB4Subscribed = allowRestoredB4Hint
                && consumeRestoredB4HintIfEligible(peripheral)
            centralReconnectFailureCount = 0
            centralSystemAutoReconnectActive = false
            append("RequiresANCS owner connected · ancsAuthorized=\(centralAncsAuthorized); "
                + "проверяю current-link encryption через стабильный F04/B3")
            beginCentralDiscovery(peripheral)
        }
    }

    private func startCentralRouteIfPossible() {
        guard runRequested, role == .central, centralManager != nil,
              centralManager.state == .poweredOn else { return }
        publishServiceIfPossible()
        guard servicePublished,
              let published = publishedLocalService,
              publishedLocalServiceGeneration == localServicePublicationGeneration,
              published.uuid == telemetryRelayServiceUUID,
              publishedServiceUUID == telemetryRelayServiceUUID else {
            setStatus("CENTRAL · ПУБЛИКУЮ F05", color: .systemOrange)
            return
        }
        if consumePoweredOnPendingTerminalIntentIfPossible() { return }
        if consumeCentralDeferredConnectIfPossible() { return }
        if centralRestoreFreshConnectAwaitingCallback, let peripheral = geelyPeripheral {
            armFreshRestoreConnectProofObservation(peripheral)
        }
        if centralHelperConfirmed && geelyPeripheral?.state == .connected {
            updateConnectionStatus()
            return
        }
        if centralRestorationReconnectPending {
            // CONTRACT_V38_REARM_POST_CANCEL_AFTER_POWER: read-only state observation only.
            armRestoredOwnerPostCancelObservation()
            setStatus("CENTRAL · ЖДУ RESTORE DISCONNECT", color: .systemOrange)
            return
        }
        if let peripheral = geelyPeripheral {
            switch peripheral.state {
            case .connected:
                if centralRestoredPendingOwner {
                    if restoredOwnerHasSystemLinkProof(peripheral) {
                        scheduleRestoredOwnerRecovery(
                            peripheral,
                            evidence: "system F04 table while restored wrapper became connected")
                    }
                    startCentralScan()
                    armRestoredOwnerProofObservation(peripheral)
                    setStatus("CENTRAL · RESTORE ЖДЁТ LOCAL CALLBACK",
                              color: .systemBlue)
                    return
                }
                if centralOwnerConfiguredForAncs {
                    // A process-restored `.connected` owner may not replay F05 didSubscribe.
                    // Only this exact restoration path is allowed to consume the two-sided hint.
                    continueCentralConnected(
                        peripheral,
                        allowRestoredB4Hint: centralRestoredConnectedOwner === peripheral)
                } else {
                    connectCentral(peripheral, reason: "retained system-connected peripheral")
                }
                updateConnectionStatus()
                return
            case .connecting:
                if centralRestoredPendingOwner {
                    if restoredOwnerHasSystemLinkProof(peripheral) {
                        // The system connection table is stronger evidence than elapsed time.
                        // Some restored CBPeripheral wrappers nevertheless remain `.connecting`,
                        // so run the same one-shot terminal-callback recovery used for a beacon.
                        scheduleRestoredOwnerRecovery(
                            peripheral,
                            evidence: "retrieveConnectedPeripherals(F04), same saved owner")
                    }
                    // A restored pending connect can legitimately wait forever while the car is
                    // absent. Scan in parallel, but do not cancel anything until the exact saved
                    // owner advertises the permanent anchor again.
                    startCentralScan()
                    armRestoredOwnerProofObservation(peripheral)
                    setStatus("CENTRAL · RESTORE ЖДЁТ F04", color: .systemBlue)
                } else {
                    setStatus("CENTRAL · СИСТЕМА ПОДКЛЮЧАЕТ", color: .systemBlue)
                }
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
        if let connected = centralManager.retrieveConnectedPeripherals(
                withServices: [serviceUUID]).first {
            connectCentral(connected, reason: "system-connected stable anchor")
            return
        }
        startCentralScan()
    }

    private func restoredOwnerHasSystemLinkProof(_ restored: CBPeripheral) -> Bool {
        guard centralManager.state == .poweredOn,
              restored === geelyPeripheral,
              let raw = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedIdentifier = UUID(uuidString: raw),
              restored.identifier == savedIdentifier else { return false }
        return centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
            .contains(where: { $0.identifier == savedIdentifier })
    }

    /// Keep observing while the restored request is pending because Android may establish the
    /// ACL after Helper's first route pass and stop advertising before didDiscover is delivered.
    /// These probes are read-only and increasingly sparse; they never turn elapsed time into a
    /// cancel decision. Only a same-identifier F04 result can arm the one destructive action.
    private func armRestoredOwnerProofObservation(_ restored: CBPeripheral) {
        guard centralRestoredPendingOwner, !centralRestorationRecoveryAttempted,
              centralRestorationProofProbeWorkItem == nil,
              restored === geelyPeripheral else { return }
        let index = min(centralRestorationProofProbeAttempt,
                        centralRestorationProofProbeDelays.count - 1)
        let delay = centralRestorationProofProbeDelays[index]
        let item = DispatchWorkItem { [weak self, weak restored] in
            guard let self = self, let restored = restored else { return }
            self.centralRestorationProofProbeWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestoredPendingOwner,
                  !self.centralRestorationRecoveryAttempted,
                  restored === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if self.restoredOwnerHasSystemLinkProof(restored) {
                self.scheduleRestoredOwnerRecovery(restored,
                    evidence: "read-only F04 system-table observation")
                return
            }
            self.centralRestorationProofProbeAttempt = min(
                self.centralRestorationProofProbeAttempt + 1,
                self.centralRestorationProofProbeDelays.count - 1)
            self.armRestoredOwnerProofObservation(restored)
        }
        centralRestorationProofProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// A pending connection has no elapsed-time watchdog. This one-shot check is armed only by
    /// the system F04 connection table or an advertisement from the exact saved owner.
    private func scheduleRestoredOwnerRecovery(_ restored: CBPeripheral,
                                                evidence: String) {
        guard centralRestoredPendingOwner, !centralRestorationRecoveryAttempted,
              centralRestorationRecoveryWorkItem == nil,
              restored === geelyPeripheral else { return }
        centralRestorationProofProbeWorkItem?.cancel()
        centralRestorationProofProbeWorkItem = nil
        append("Stable F04 owner is present; one restore reconciliation armed"
            + " · \(evidence)")
        let item = DispatchWorkItem { [weak self, weak restored] in
            guard let self = self, let restored = restored else { return }
            self.centralRestorationRecoveryWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestoredPendingOwner,
                  !self.centralRestorationRecoveryAttempted,
                  restored === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            self.centralRestorationRecoveryAttempted = true
            self.centralRestoredPendingOwner = false
            self.stopCentralScanSafely(self.centralManager,
                                        reason: "restore evidence consumed")
            switch restored.state {
            case .connected:
                // retrieveConnectedPeripherals proves a physical system-wide link, not this
                // app's RequiresANCS ownership. Close the stale restored request once and wait
                // for its callback before issuing a fresh app-local connect(options:).
                self.centralRestoreOwnershipClaimCount = max(
                    self.centralRestoreOwnershipClaimCount, 1)
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.clearCentralRuntime(keepPeripheral: true)
                self.setStatus("CENTRAL · ЗАКРЫВАЮ RESTORED OWNER",
                               color: .systemOrange)
                self.append("System-wide F04 link exists but app-local didConnect is absent; "
                    + "cancel restored request once and wait for didDisconnect")
                self.cancelCentralConnectionSafely(restored, manager: self.centralManager,
                    reason: "claim app-local RequiresANCS owner after F04 proof")
                self.armRestoredOwnerPostCancelObservation()
            case .connecting:
                self.centralRestoreOwnershipClaimCount = max(
                    self.centralRestoreOwnershipClaimCount, 1)
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.clearCentralRuntime(keepPeripheral: true)
                self.setStatus("CENTRAL · ЗАКРЫВАЮ RESTORED OWNER",
                               color: .systemOrange)
                self.append("Restored owner is still .connecting after stable F04 proof; "
                    + "cancel once, then wait for terminal callback")
                self.cancelCentralConnectionSafely(restored, manager: self.centralManager,
                    reason: "one-shot restored-owner recovery after stable F04 proof")
                self.armRestoredOwnerPostCancelObservation()
            case .disconnecting:
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.append("Restored owner is already .disconnecting; wait before reconnect")
                self.armRestoredOwnerPostCancelObservation()
            case .disconnected:
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.append("Restored owner became disconnected after stable F04 proof; "
                    + "enter shared terminal reopen")
                self.reopenRestoredOwnerAfterTerminalCallback(restored,
                    callback: "restoration grace observed .disconnected")
            @unknown default:
                self.append("Restored owner has unknown state; no destructive recovery issued")
            }
        }
        centralRestorationRecoveryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + centralRestorationEvidenceGrace,
                                      execute: item)
    }

    private func clearCentralRestorationRecovery() {
        centralRestorationRecoveryWorkItem?.cancel()
        centralRestorationRecoveryWorkItem = nil
        centralRestorationProofProbeWorkItem?.cancel()
        centralRestorationProofProbeWorkItem = nil
        centralRestorationProofProbeAttempt = 0
        centralRestorationPostCancelProbeWorkItem?.cancel()
        centralRestorationPostCancelProbeWorkItem = nil
        centralRestorationPostCancelProbeAttempt = 0
        centralRestoredPendingOwner = false
        centralRestorationRecoveryAttempted = false
        centralRestorationReconnectPending = false
    }

    private func resetCentralRestoreOwnershipClaims() {
        centralRestoreFreshConnectProofToken &+= 1
        centralRestoreFreshConnectProofWorkItem?.cancel()
        centralRestoreFreshConnectProofWorkItem = nil
        centralRestoreFreshConnectAwaitingCallback = false
        centralRestoreOwnershipClaimCount = 0
    }

    /// Polling is read-only. It may wait forever; only an exact same-identifier entry returned by
    /// retrieveConnectedPeripherals(F04) may advance to the callback-grace check below.
    private func armFreshRestoreConnectProofObservation(_ peripheral: CBPeripheral) {
        guard centralRestoreFreshConnectAwaitingCallback,
              centralRestoreFreshConnectProofWorkItem == nil,
              peripheral === geelyPeripheral else { return }
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralRestoreFreshConnectProofWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestoreFreshConnectAwaitingCallback,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if self.restoredOwnerHasSystemLinkProof(peripheral) {
                self.observeFreshRestoreConnectPhysicalProof(peripheral)
            } else {
                self.armFreshRestoreConnectProofObservation(peripheral)
            }
        }
        centralRestoreFreshConnectProofWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: item)
    }

    /// Reconcile only a fresh post-restoration connect which has exact physical F04 proof but no
    /// app-local didConnect. The physical proof arms a short callback grace; elapsed time or a
    /// beacon can never enter this method on their own. The destructive budget is capped at two.
    private func observeFreshRestoreConnectPhysicalProof(_ peripheral: CBPeripheral) {
        guard centralRestoreFreshConnectAwaitingCallback,
              centralRestoreOwnershipClaimCount == 1,
              centralRestoreFreshConnectProofWorkItem == nil,
              peripheral === geelyPeripheral,
              centralManager.state == .poweredOn,
              restoredOwnerHasSystemLinkProof(peripheral) else { return }
        centralRestoreFreshConnectProofToken &+= 1
        let token = centralRestoreFreshConnectProofToken
        append("Fresh RequiresANCS connect has exact F04 physical proof; "
            + "waiting briefly for app-local didConnect before claim #2")
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralRestoreFreshConnectProofWorkItem = nil
            guard token == self.centralRestoreFreshConnectProofToken,
                  self.runRequested, self.role == .central,
                  self.centralRestoreFreshConnectAwaitingCallback,
                  self.centralRestoreOwnershipClaimCount == 1,
                  self.centralRestoreOwnershipClaimCount
                    < self.centralRestoreOwnershipClaimLimit,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn,
                  self.restoredOwnerHasSystemLinkProof(peripheral) else { return }
            // CONTRACT_V39_RESTORE_SECOND_CLAIM_EXACT_F04_ONLY
            self.centralRestoreOwnershipClaimCount = 2
            self.centralRestoreFreshConnectAwaitingCallback = false
            if self.centralDeferredConnectIntent?.peripheral === peripheral {
                self.clearCentralDeferredConnectIntent()
            }
            self.centralRestorationReconnectPending = true
            self.centralOwnerConfiguredForAncs = false
            self.centralSystemAutoReconnectActive = false
            self.clearCentralRuntime(keepPeripheral: true)
            self.setStatus("CENTRAL · F04 OWNER CLAIM #2", color: .systemOrange)
            self.append("Fresh physical F04 link omitted didConnect; bounded ownership claim #2/2")
            self.cancelCentralConnectionSafely(peripheral, manager: self.centralManager,
                reason: "bounded F04 ownership claim #2 after exact system-table proof")
            self.armRestoredOwnerPostCancelObservation()
        }
        centralRestoreFreshConnectProofWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + centralRestoreFreshConnectCallbackGrace, execute: item)
    }

    /// A terminal callback is preferred, but state restoration has already demonstrated that
    /// callbacks can be lost. This read-only observer never makes an age-based decision and never
    /// repeats cancel/connect. It only reuses the terminal path once the same owner reports
    /// `.disconnected`.
    private func armRestoredOwnerPostCancelObservation() {
        guard centralRestorationReconnectPending,
              centralRestorationPostCancelProbeWorkItem == nil,
              let peripheral = geelyPeripheral else { return }
        let index = min(centralRestorationPostCancelProbeAttempt,
                        centralRestorationPostCancelProbeDelays.count - 1)
        let delay = centralRestorationPostCancelProbeDelays[index]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralRestorationPostCancelProbeWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestorationReconnectPending,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if peripheral.state == .disconnected {
                self.reopenRestoredOwnerAfterTerminalCallback(peripheral,
                    callback: "read-only post-cancel state=.disconnected")
                return
            }
            self.centralRestorationPostCancelProbeAttempt = min(
                self.centralRestorationPostCancelProbeAttempt + 1,
                self.centralRestorationPostCancelProbeDelays.count - 1)
            self.armRestoredOwnerPostCancelObservation()
        }
        centralRestorationPostCancelProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Complete the evidence-driven handover only after Core Bluetooth confirms that the stale
    /// restored request is closed. This guarantees that two app-local owners are never opened in
    /// parallel and preserves the same CBPeripheral identity and RequiresANCS options.
    private func reopenRestoredOwnerAfterTerminalCallback(_ peripheral: CBPeripheral,
                                                           callback: String) {
        guard centralRestorationReconnectPending,
              peripheral === geelyPeripheral else { return }
        // CONTRACT_V39_ALL_RESTORE_TERMINALS_ENTER_CLAIM_ONE: callback, read-only state and an
        // already-disconnected owner at reconciliation grace all establish the same claim #1
        // lineage before any replacement connect is materialized.
        centralRestoreOwnershipClaimCount = max(centralRestoreOwnershipClaimCount, 1)
        clearCentralRestorationRecovery()
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        append("Restored-owner terminal callback received · \(callback); "
            + "reopen same owner with RequiresANCS")
        guard runRequested, role == .central else { return }
        // Claim #1's replacement is observed for exact physical proof. After claim #2 the final
        // replacement is left to Core Bluetooth; no third destructive action can be armed.
        centralRestoreFreshConnectAwaitingCallback =
            centralRestoreOwnershipClaimCount < centralRestoreOwnershipClaimLimit
        queueCentralConnectIntent(peripheral,
            reason: "same stable owner after bounded restoration claim "
                + "\(centralRestoreOwnershipClaimCount)/\(centralRestoreOwnershipClaimLimit)",
            delay: 0.25)
    }

    /// Manual reconnect uses the same terminal boundary for both possible Core Bluetooth
    /// callbacks. Consuming the flag here prevents a cancelled pending connect's
    /// didFailToConnect from leaking manual intent into a later system reconnect.
    private func reopenManualOwnerAfterTerminalCallback(_ peripheral: CBPeripheral,
                                                         callback: String) {
        guard centralManualReconnectPending,
              peripheral === geelyPeripheral else { return }
        centralManualReconnectPending = false
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        append("Manual reconnect terminal callback received · \(callback); "
            + "reopen same owner")
        guard runRequested, role == .central else { return }
        queueCentralConnectIntent(peripheral,
            reason: "explicit manual reconnect", delay: 0.25)
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

    /// CBCentralManager logs an API MISUSE (and may assert in a future iOS release) when stop or
    /// cancel is sent before poweredOn. Restoration callbacks are explicitly allowed to precede
    /// that state, therefore every destructive Central command passes through these gates.
    private func stopCentralScanSafely(_ manager: CBCentralManager, reason: String) {
        guard manager.state == .poweredOn else {
            if !centralDeferredStopScan {
                append("Central stopScan отложен до poweredOn · \(reason)")
            }
            centralDeferredStopScan = true
            return
        }
        centralDeferredStopScan = false
        if manager.isScanning { manager.stopScan() }
    }

    private func cancelCentralConnectionSafely(_ peripheral: CBPeripheral,
                                                manager: CBCentralManager,
                                                reason: String) {
        guard manager.state == .poweredOn else {
            let firstRequest = centralDeferredCancellations[peripheral.identifier] == nil
            centralDeferredCancellations[peripheral.identifier] = peripheral
            if firstRequest {
                append("Central cancel отложен до poweredOn · \(reason)")
            }
            return
        }
        centralDeferredCancellations.removeValue(forKey: peripheral.identifier)
        if peripheral.state != .disconnected {
            manager.cancelPeripheralConnection(peripheral)
        }
    }

    private func flushDeferredCentralCommands(_ manager: CBCentralManager) {
        guard manager.state == .poweredOn else { return }
        if centralDeferredStopScan {
            centralDeferredStopScan = false
            if manager.isScanning { manager.stopScan() }
            append("Central deferred stopScan выполнен после poweredOn")
        }
        let cancellations = Array(centralDeferredCancellations.values)
        centralDeferredCancellations.removeAll()
        var issued = 0
        for peripheral in cancellations where peripheral.state != .disconnected {
            manager.cancelPeripheralConnection(peripheral)
            issued += 1
        }
        if issued > 0 {
            append("Central deferred cancel выполнен после poweredOn · owners=\(issued)")
        }
    }

    /// Power can return after Core Bluetooth has already moved a cancelled manual/hard-reset
    /// owner to `.disconnected` but before delivering its terminal callback. Consume that state
    /// exactly once before the ordinary retained-owner route can reconnect with stale source flags.
    private func consumePoweredOnPendingTerminalIntentIfPossible() -> Bool {
        guard centralDeferredConnectIntent == nil,
              centralManualReconnectPending || centralHardResetReason != nil,
              let peripheral = geelyPeripheral else { return false }
        guard centralManager != nil, centralManager.state == .poweredOn else { return true }
        switch peripheral.state {
        case .disconnected:
            let source: String
            if centralManualReconnectPending {
                source = "power-resume explicit manual reconnect"
            } else {
                source = "power-resume hard reset · \(centralHardResetReason ?? "unknown")"
            }
            // CONTRACT_V38_POWER_RESUME_SOURCE_FLAGS_CLEAR_BEFORE_CONNECT
            centralManualReconnectPending = false
            centralHardResetReason = nil
            clearCentralPendingTerminalStateObservation()
            centralOwnerConfiguredForAncs = false
            centralSystemAutoReconnectActive = false
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            append("PoweredOn/F05 observed exact owner disconnected; "
                + "materialize one deferred intent · \(source)")
            guard queueCentralConnectIntent(peripheral, reason: source) else { return true }
            _ = consumeCentralDeferredConnectIfPossible()
            return true
        case .connected, .connecting, .disconnecting:
            armCentralPendingTerminalStateObservation()
            setStatus("CENTRAL · ЖДУ TERMINAL STATE", color: .systemOrange)
            return true
        @unknown default:
            armCentralPendingTerminalStateObservation()
            return true
        }
    }

    /// Read-only state reconciliation for a manual/hard-reset cancellation that crossed power
    /// state. No second cancel/connect is issued; the poweredOn/F05 route owns materialization.
    private func armCentralPendingTerminalStateObservation() {
        guard centralDeferredConnectIntent == nil,
              centralManualReconnectPending || centralHardResetReason != nil,
              centralPendingTerminalStateProbeWorkItem == nil,
              let peripheral = geelyPeripheral else { return }
        let index = min(centralPendingTerminalStateProbeAttempt,
                        centralPendingTerminalStateProbeDelays.count - 1)
        let delay = centralPendingTerminalStateProbeDelays[index]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralPendingTerminalStateProbeWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralDeferredConnectIntent == nil,
                  self.centralManualReconnectPending || self.centralHardResetReason != nil,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if peripheral.state == .disconnected {
                // CONTRACT_V38_PENDING_SOURCE_STATE_REENTERS_F05_ROUTE
                self.startCentralRouteIfPossible()
                return
            }
            self.centralPendingTerminalStateProbeAttempt = min(
                self.centralPendingTerminalStateProbeAttempt + 1,
                self.centralPendingTerminalStateProbeDelays.count - 1)
            self.armCentralPendingTerminalStateObservation()
        }
        centralPendingTerminalStateProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func clearCentralPendingTerminalStateObservation() {
        centralPendingTerminalStateProbeWorkItem?.cancel()
        centralPendingTerminalStateProbeWorkItem = nil
        centralPendingTerminalStateProbeAttempt = 0
    }

    /// Record delayed connect intent synchronously, before returning from a terminal callback.
    /// No Core Bluetooth command is sent here. The first exact-owner intent wins until it is
    /// consumed or explicitly cleared by stop/role change.
    @discardableResult
    private func queueCentralConnectIntent(_ peripheral: CBPeripheral,
                                           reason: String,
                                           delay: TimeInterval = 0) -> Bool {
        guard runRequested, role == .central else { return false }
        if let current = geelyPeripheral,
           current !== peripheral {
            append("Deferred connect ignored for stale owner · \(peripheral.identifier.uuidString)")
            return false
        }
        if let existing = centralDeferredConnectIntent {
            guard existing.peripheral === peripheral else {
                append("Deferred connect already belongs to another exact owner; new intent ignored")
                return false
            }
            geelyPeripheral = existing.peripheral
            existing.peripheral.delegate = self
            armCentralDeferredConnectWake()
            return true
        }
        clearCentralPendingTerminalStateObservation()
        centralDeferredConnectToken &+= 1
        let intent = DeferredCentralConnectIntent(
            peripheral: peripheral,
            reason: reason,
            notBefore: Date().addingTimeInterval(max(0, delay)),
            token: centralDeferredConnectToken
        )
        centralDeferredConnectIntent = intent
        geelyPeripheral = peripheral
        peripheral.delegate = self
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        armCentralDeferredConnectWake()
        setStatus("CENTRAL · CONNECT ОТЛОЖЕН", color: .systemOrange)
        append("Deferred exact-owner connect retained in RAM · "
            + peripheral.identifier.uuidString + " · \(reason)")
        return true
    }

    private func armCentralDeferredConnectWake() {
        guard centralDeferredConnectWorkItem == nil,
              let intent = centralDeferredConnectIntent else { return }
        let delay = max(0, intent.notBefore.timeIntervalSinceNow)
        let token = intent.token
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.centralDeferredConnectWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralDeferredConnectIntent?.token == token else { return }
            // CONTRACT_V38_DEFERRED_WAKE_USES_NORMAL_ROUTE: the route owns the only consume.
            self.startCentralRouteIfPossible()
        }
        centralDeferredConnectWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Atomically remove one intent before entering the ordinary exact-owner connection path.
    /// Returning true means route processing is fully owned by the deferred intent, including
    /// when it is still waiting for its notBefore boundary.
    private func consumeCentralDeferredConnectIfPossible() -> Bool {
        guard let intent = centralDeferredConnectIntent else { return false }
        guard runRequested, role == .central else {
            clearCentralDeferredConnectIntent()
            return false
        }
        guard centralManager != nil, centralManager.state == .poweredOn else { return true }
        guard geelyPeripheral == nil
                || geelyPeripheral === intent.peripheral else {
            append("Deferred connect dropped: retained owner identity changed")
            clearCentralDeferredConnectIntent()
            return false
        }
        let remaining = intent.notBefore.timeIntervalSinceNow
        if remaining > 0 {
            armCentralDeferredConnectWake()
            setStatus("CENTRAL · CONNECT ОТЛОЖЕН", color: .systemOrange)
            return true
        }
        if intent.peripheral.state == .disconnecting {
            centralDeferredConnectWorkItem?.cancel()
            centralDeferredConnectWorkItem = nil
            centralDeferredConnectToken &+= 1
            let replacement = DeferredCentralConnectIntent(
                peripheral: intent.peripheral,
                reason: intent.reason,
                notBefore: Date().addingTimeInterval(0.25),
                token: centralDeferredConnectToken
            )
            centralDeferredConnectIntent = replacement
            armCentralDeferredConnectWake()
            setStatus("CENTRAL · ЖДУ DISCONNECT", color: .systemOrange)
            return true
        }
        if intent.peripheral.state == .connecting {
            // CONTRACT_V39_CONNECTING_RETAINS_SOLE_INTENT: state alone proves neither app-local
            // ownership nor a terminal boundary. Retain the exact intent and wait read-only; only
            // its matching didConnect may consume it, or the restoration lineage may enter the
            // exact-F04 bounded claim route.
            centralDeferredConnectWorkItem?.cancel()
            centralDeferredConnectWorkItem = nil
            centralDeferredConnectToken &+= 1
            let replacement = DeferredCentralConnectIntent(
                peripheral: intent.peripheral,
                reason: intent.reason,
                notBefore: Date().addingTimeInterval(0.5),
                token: centralDeferredConnectToken
            )
            centralDeferredConnectIntent = replacement
            armCentralDeferredConnectWake()
            append("Deferred exact-owner intent retained while owner is .connecting; "
                + "read-only wait for matching didConnect")
            setStatus("CENTRAL · ЖДУ CALLBACK PENDING OWNER", color: .systemOrange)
            return true
        }
        // CONTRACT_V38_POWERED_ON_SINGLE_CONSUME: clear before the normal connect path.
        centralDeferredConnectIntent = nil
        centralDeferredConnectToken &+= 1
        centralDeferredConnectWorkItem?.cancel()
        centralDeferredConnectWorkItem = nil
        geelyPeripheral = intent.peripheral
        append("Deferred exact-owner connect consumed once after poweredOn · \(intent.reason)")
        connectCentral(intent.peripheral, reason: "deferred after poweredOn · \(intent.reason)")
        return true
    }

    private func clearCentralDeferredConnectIntent() {
        centralDeferredConnectToken &+= 1
        centralDeferredConnectWorkItem?.cancel()
        centralDeferredConnectWorkItem = nil
        centralDeferredConnectIntent = nil
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
        guard runRequested, role == .central else { return }
        guard centralManager != nil else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        guard centralManager.state == .poweredOn else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        cancelCentralReconnect()
        stopCentralScanSafely(centralManager, reason: "connect owner")
        if let previous = geelyPeripheral, previous.identifier != peripheral.identifier,
           previous.state != .disconnected {
            cancelCentralConnectionSafely(previous, manager: centralManager,
                                            reason: "replace previous owner")
        }
        geelyPeripheral = peripheral
        peripheral.delegate = self
        clearCentralRuntime(keepPeripheral: true)
        if peripheral.state == .connected {
            guard centralOwnerConfiguredForAncs else {
                // Apple explicitly requires an app-local connect even when another app/system
                // already owns the physical link. Do not cancel that healthy system connection.
                issueCentralConnect(peripheral,
                    reason: "app-local ownership of already-connected anchor · \(reason)")
                return
            }
            continueCentralConnected(peripheral)
            return
        }
        if peripheral.state == .connecting {
            // CONTRACT_V39_CONNECTING_NEVER_DUPLICATES_CONNECT: `.connecting` already represents
            // a pending Core Bluetooth request. Restoration may reconcile it only through exact
            // F04 proof -> bounded cancel -> terminal boundary -> one fresh connect.
            setStatus(centralOwnerConfiguredForAncs
                ? "CENTRAL · ЖДУ СИСТЕМНОЕ ПОДКЛЮЧЕНИЕ"
                : "CENTRAL · ЖДУ CALLBACK PENDING OWNER", color: .systemOrange)
            return
        }
        if peripheral.state == .disconnecting {
            setStatus("CENTRAL · ЖДУ DISCONNECT CALLBACK", color: .systemOrange)
            return
        }

        issueCentralConnect(peripheral, reason: reason)
    }

    private func issueCentralConnect(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central else { return }
        guard centralManager != nil else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        // CONTRACT_V38_ISSUE_CONNECT_POWER_GATE: never call Core Bluetooth while unavailable.
        guard centralManager.state == .poweredOn else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        if let intent = centralDeferredConnectIntent {
            guard intent.peripheral === peripheral else {
                append("connect blocked: deferred intent belongs to another exact owner")
                return
            }
            clearCentralDeferredConnectIntent()
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
        if centralRestoreFreshConnectAwaitingCallback {
            armFreshRestoreConnectProofObservation(peripheral)
        }
        setStatus("CENTRAL · ПОДКЛЮЧЕНИЕ", color: .systemBlue)
        append("connect Geely_ANCS · singleOwner=true · RequiresANCS=true"
            + " · AutoReconnect=" + (systemAutoReconnect ? "system" : "manual")
            + " · pendingConnect=system-owned/no-timeout"
            + " · \(reason)")
    }

    private func beginCentralDiscovery(_ peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .idle else { return }
        centralHandshake = .discovering
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        cancelCentralDiscoveryWork()
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        centralHelperConfirmed = false
        centralAncsCccdConfirmed = false
        lastAndroidReadAt = nil
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        peripheral.discoverServices([serviceUUID])
        setStatus("REQUIRES_ANCS · ИЩУ F04", color: .systemOrange)
        append("Single owner connected; targeted discovery stable F04 "
            + serviceUUID.uuidString)
    }

    private func stopCentralRoute(cancelConnection: Bool) {
        cancelCentralReconnect()
        clearCentralDeferredConnectIntent()
        clearCentralPendingTerminalStateObservation()
        clearCentralRestorationRecovery()
        resetCentralRestoreOwnershipClaims()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        clearCentralRestoredB4Hint()
        centralHardResetReason = nil
        clearCentralDestructiveRecoveryLineage()
        centralManualReconnectPending = false
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralNamespaceResolved = false
        centralRequireFreshAdvertisement = false
        centralRejectedNamespaceGeneration = nil
        centralRestorationAwaitingPower = false
        centralReconnectFailureCount = 0
        guard centralManager != nil else {
            clearCentralRuntime(keepPeripheral: false)
            return
        }
        stopCentralScanSafely(centralManager, reason: "stop Central route")
        let previous = geelyPeripheral
        clearCentralRuntime(keepPeripheral: false)
        if cancelConnection, let previous = previous,
           previous.state != .disconnected {
            cancelCentralConnectionSafely(previous, manager: centralManager,
                                            reason: "stop Central route")
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
        centralAncsAccessProven = false
        centralAncsAuthorizationCallbackObserved = false
        centralAncsReadyWriteIssued = false
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
        let effectiveAncsAccess = centralAncsAuthorized || centralAncsAccessProven
        return geelyPeripheral?.state == .connected
            && centralSecureLinkReady
            && centralHandshake == .ready
            && centralAncsReadyWriteIssued
            && centralHelperConfirmed
            && effectiveAncsAccess
            && centralAncsCccdConfirmed
            && centralB4Subscribed
            && valid.battery
            && valid.network
    }

    private func refreshCentralReadiness(_ source: String) {
        guard runRequested, role == .central else { return }
        let valid = centralTelemetryValidity()
        if centralReadyForGreen() {
            // CONTRACT_V40_FULL_PROOF_REARMS_BUDGET: didConnect, service discovery, B3 alone, or
            // Helper ACK alone cannot re-arm cancellation. Only the complete current-owner proof
            // accepted by `centralReadyForGreen` starts another automatic recovery lineage.
            if let peripheral = geelyPeripheral {
                rearmCentralDestructiveRecoveryAfterFullProof(
                    peripheral, source: "B3 + ANCS-READY + Android ANCS/B4 CCCD proof")
            }
            centralReconnectFailureCount = 0
            centralReadinessProofWorkItem?.cancel()
            centralReadinessProofWorkItem = nil
            setStatus("ANCS + B4 + ДАННЫЕ АКТИВНЫ", color: .systemGreen)
        } else {
            var missing: [String] = []
            if !centralHelperConfirmed { missing.append("Helper ACK") }
            if !(centralAncsAuthorized || centralAncsAccessProven) {
                missing.append(centralAncsAuthorizationCallbackObserved
                    ? "iPhone ANCS permission" : "ANCS access proof")
            }
            if !centralAncsCccdConfirmed { missing.append("ANCS CCCD") }
            if !centralB4Subscribed { missing.append("B4 CCCD") }
            if !valid.battery { missing.append("battery") }
            if !valid.network { missing.append("network") }
            setStatus("ЖДУ: " + missing.joined(separator: " + "), color: .systemOrange)
        }
        append("Readiness · \(source) · helper=\(centralHelperConfirmed)"
            + " · authorized=\(centralAncsAuthorized)"
            + " · accessProof=\(centralAncsAccessProven)"
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
        guard runRequested, role == .central,
              geelyPeripheral?.state == .connected,
              centralSecureLinkReady,
              centralHandshake == .ready,
              centralAncsReadyWriteIssued,
              centralHelperConfirmed,
              centralB4Subscribed else { return }
        let firstProof = !centralAncsCccdConfirmed
        centralAncsCccdConfirmed = true
        centralAncsAccessProven = true
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

    /// A non-auto-reconnecting terminal callback ends the previous connect request. Materialize
    /// the replacement as exact-owner data immediately; a matching late didConnect may consume
    /// it before its backoff expires.
    private func queueTerminalCentralReconnect(_ peripheral: CBPeripheral, reason: String) {
        let delayIndex = min(centralReconnectFailureCount,
                             centralReconnectDelays.count - 1)
        let delay = centralReconnectDelays[delayIndex]
        centralReconnectFailureCount = min(centralReconnectFailureCount + 1,
                                           centralReconnectDelays.count - 1)
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        _ = queueCentralConnectIntent(peripheral,
            reason: "terminal exact-owner reconnect · \(reason)", delay: delay)
        append("Terminal callback materialized one exact-owner connect intent через "
            + "\(Int(delay)) с · \(reason)")
    }

    private func cancelCentralReconnect() {
        centralReconnectToken &+= 1
        centralReconnectWorkItem?.cancel()
        centralReconnectWorkItem = nil
    }

    private func clearCentralDestructiveRecoveryLineage() {
        centralDestructiveRecoveryOwnerID = nil
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
    }

    /// A different persisted CBPeripheral is a genuinely new physical lineage. Ordinary
    /// terminal callbacks for the same identifier are not: Core Bluetooth can keep returning the
    /// same stale F04 database through an arbitrary number of disconnect/reconnect callbacks.
    private func bindCentralDestructiveRecoveryLineage(_ peripheral: CBPeripheral,
                                                       source: String) {
        guard centralDestructiveRecoveryOwnerID != peripheral.identifier else { return }
        centralDestructiveRecoveryOwnerID = peripheral.identifier
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        append("Destructive recovery lineage bound · owner="
            + peripheral.identifier.uuidString + " · budget=1 · \(source)")
    }

    /// Service Changed is the only automatic signal that the retained owner can expose a new
    /// exact F04 publication. It starts a new namespace lineage without replacing the owner.
    private func rearmCentralDestructiveRecoveryForFreshF04(_ peripheral: CBPeripheral,
                                                            source: String) {
        bindCentralDestructiveRecoveryLineage(peripheral, source: source)
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        append("Fresh exact F04 publication re-armed destructive recovery budget · \(source)")
    }

    /// A completed current session is also a safe lineage boundary: B3 proved encryption,
    /// ANCS-READY was accepted, Android proved both real ANCS CCCDs on the same owner, F05/B4 is
    /// subscribed, and live telemetry is valid. Mere didConnect/F04 discovery never calls this.
    private func rearmCentralDestructiveRecoveryAfterFullProof(_ peripheral: CBPeripheral,
                                                               source: String) {
        bindCentralDestructiveRecoveryLineage(peripheral, source: source)
        guard centralDestructiveRecoveryConsumed
                || centralDestructiveRecoveryWaitingForFreshF04 else { return }
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        append("Current owner fully proven; destructive recovery budget re-armed · \(source)")
    }

    /// Once the single automatic cancel has been spent, keep the physical owner intact. A manual
    /// reconnect remains available, but automatic code waits for an exact F04 invalidation/new
    /// publication instead of producing a didConnect -> UUID-not-allowed -> cancel storm.
    private func waitForFreshCentralF04Publication(reason: String) {
        cancelCentralReconnect()
        cancelCentralDiscoveryWork()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralHardResetReason = nil
        centralDestructiveRecoveryWaitingForFreshF04 = true
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralAncsAccessProven = false
        centralAncsReadyWriteIssued = false
        centralSecureLinkReady = false
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralHandshake = .discovering
        setStatus("CENTRAL · ЖДУ SERVICE CHANGED", color: .systemOrange)
        append("Destructive recovery budget 1/1 исчерпан; owner сохранён без cancel · "
            + "жду Service Changed/new exact F04 publication или ручной reconnect · \(reason)"
            + " · first=" + (centralDestructiveRecoveryFirstReason ?? "unknown"))
    }

    /// A protocol failure after didConnect may justify one controlled reconnect of this same
    /// stable owner. Identity, pending reconnect contract, F04/F05 UUIDs and the saved pair are
    /// retained; ordinary `.connecting` and radio-loss paths never call this method.
    private func resetCentralLink(reason: String) {
        guard runRequested, role == .central, centralManager != nil else { return }
        if centralHardResetReason != nil { return }
        if let peripheral = geelyPeripheral {
            bindCentralDestructiveRecoveryLineage(peripheral, source: "protocol recovery")
        }
        guard !centralDestructiveRecoveryConsumed else {
            waitForFreshCentralF04Publication(reason: reason)
            return
        }
        // CONTRACT_V40_ONE_DESTRUCTIVE_RECOVERY_PER_LINEAGE: this flag is deliberately not
        // cleared by didDisconnect, didFailToConnect, or the replacement didConnect.
        centralDestructiveRecoveryConsumed = true
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = reason
        cancelCentralReconnect()
        clearCentralRestorationRecovery()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralHardResetReason = reason
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        setStatus("CENTRAL · ВОССТАНАВЛИВАЮ OWNER", color: .systemOrange)
        append("Один controlled reconnect 1/1 того же F04 owner · \(reason)")
        guard let peripheral = geelyPeripheral else {
            centralHardResetReason = nil
            scheduleCentralReconnect(reason: "resolve stable owner after \(reason)")
            return
        }
        if peripheral.state == .disconnected {
            centralHardResetReason = nil
            centralOwnerConfiguredForAncs = false
            issueCentralConnect(peripheral, reason: "controlled recovery · \(reason)")
            return
        }
        cancelCentralConnectionSafely(peripheral, manager: centralManager,
                                        reason: "controlled stable-owner recovery · \(reason)")
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
              !centralDestructiveRecoveryWaitingForFreshF04,
              peripheral === geelyPeripheral,
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
                  !self.centralDestructiveRecoveryWaitingForFreshF04,
                  peripheral === self.geelyPeripheral,
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
              !centralDestructiveRecoveryWaitingForFreshF04,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        guard centralServiceRediscoveryAttempt < centralServiceRediscoveryLimit else {
            setStatus("CENTRAL · ЖДУ F04 SERVICE CHANGED", color: .systemOrange)
            append("F04 пока не готов после \(centralServiceRediscoveryAttempt) попыток; "
                + "owner сохраняю и жду service invalidation/ручную диагностику · \(reason)")
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
                  !self.centralDestructiveRecoveryWaitingForFreshF04,
                  peripheral === self.geelyPeripheral,
                  peripheral.state == .connected,
                  self.centralHandshake == .discovering else { return }
            self.append("D2D9 targeted service rediscovery · attempt \(attempt) · \(reason)")
            peripheral.discoverServices([self.serviceUUID])
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
            setStatus("НЕСОВМЕСТИМЫЙ F04 GATT", color: .systemRed)
            return true
        }
        centralControlCharacteristic = currentControl
        centralWakeCharacteristic = currentWake
        cancelCentralDiscoveryWork()
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        if centralDestructiveRecoveryWaitingForFreshF04 {
            // A successful explicit manual reconnect may prove a usable namespace. Keep the
            // automatic budget spent for this lineage, but leave the waiting state.
            centralDestructiveRecoveryWaitingForFreshF04 = false
            append("Exact F04 characteristics usable after explicit recovery; "
                + "automatic destructive budget remains spent")
        }
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
              peripheral === geelyPeripheral else { return }
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
                  peripheral === self.geelyPeripheral,
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
        if !centralAncsAuthorized {
            setStatus("CURRENT LINK OK · ПРОВЕРЯЮ ANCS", color: .systemOrange)
            append("CURRENT LINK защищён; ancsAuthorized snapshot=false пока диагностический. "
                + "ANCS-READY отправляю, фактический доступ подтвердят Android CCCD")
        }
        // CONTRACT_V39_B3_ALWAYS_WRITES_READY: exact-owner B3 is the protocol gate. The Boolean
        // ANCS snapshot may still be stale while iOS resolves privacy after pairing, so it must
        // never deadlock Android's actual ANCS discovery/subscription attempt.
        writeCentralAncsReady(peripheral)
    }

    private func writeCentralAncsReady(_ peripheral: CBPeripheral) {
        guard centralSecureLinkReady, !centralAncsReadyWriteIssued else { return }
        guard let secure = centralSecureCharacteristic else {
            resetCentralLink(reason: "SECURE B3 missing before ANCS proof")
            return
        }
        centralAncsReadyWriteIssued = true
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
              peripheral === geelyPeripheral,
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
                  peripheral === self.geelyPeripheral,
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
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .ready else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        let delays: [TimeInterval] = [1, 2, 5, 10, 30, 60]
        let delay = delays[min(max(0, centralWakeSubscriptionAttempt - 1), delays.count - 1)]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            guard self.runRequested, self.role == .central,
                  peripheral === self.geelyPeripheral else { return }
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
            if centralDestructiveRecoveryWaitingForFreshF04 {
                setStatus("CENTRAL · ЖДУ SERVICE CHANGED", color: .systemOrange)
            } else if centralHandshake == .ready || centralHelperConfirmed {
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
        guard central.state == .poweredOn else {
            centralRestorationRecoveryWorkItem?.cancel()
            centralRestorationRecoveryWorkItem = nil
            centralRestorationProofProbeWorkItem?.cancel()
            centralRestorationProofProbeWorkItem = nil
            centralRestorationPostCancelProbeWorkItem?.cancel()
            centralRestorationPostCancelProbeWorkItem = nil
            centralRestoreFreshConnectProofToken &+= 1
            centralRestoreFreshConnectProofWorkItem?.cancel()
            centralRestoreFreshConnectProofWorkItem = nil
            centralPendingTerminalStateProbeWorkItem?.cancel()
            centralPendingTerminalStateProbeWorkItem = nil
            centralSystemAutoReconnectActive = false
            // willRestoreState may arrive before this state callback. Preserve that exact owner;
            // it is the only one known to have the RequiresANCS contract.
            if !centralRestorationAwaitingPower && !centralRestoredPendingOwner
                && !centralRestorationReconnectPending
                && !centralRestoreFreshConnectAwaitingCallback
                && !centralManualReconnectPending
                && centralHardResetReason == nil
                && centralDeferredConnectIntent == nil {
                centralOwnerConfiguredForAncs = false
                clearCentralRuntime(keepPeripheral: false)
            } else if centralDeferredConnectIntent != nil {
                // CONTRACT_V38_POWER_OFF_PRESERVES_DEFERRED_EXACT_OWNER
                clearCentralRuntime(keepPeripheral: true)
                append("Bluetooth unavailable; deferred exact-owner connect retained in RAM")
            }
            if runRequested { setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed) }
            return
        }
        flushDeferredCentralCommands(central)
        guard role == .central else {
            centralRestorationAwaitingPower = false
            clearCentralDeferredConnectIntent()
            clearCentralRestorationRecovery()
            stopCentralScanSafely(central, reason: "role is not Central")
            return
        }
        if centralRestorationAwaitingPower {
            centralRestorationAwaitingPower = false
            append("Central restoration продолжен после poweredOn")
        }
        if runRequested {
            // CONTRACT_V38_POWERED_ON_ENTERS_SINGLE_CONSUME_ROUTE
            startCentralRouteIfPossible()
        }
    }

    func centralManager(_ central: CBCentralManager,
                        willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey]
            as? [CBPeripheral] ?? []
        append("Central restore: peripherals=\(peripherals.count)")
        guard runRequested, role == .central else {
            peripherals.forEach {
                cancelCentralConnectionSafely($0, manager: central,
                                                reason: "restored while route is disabled")
            }
            return
        }
        let savedIdentifier = UserDefaults.standard.string(
            forKey: savedGeelyPeripheralPreference).flatMap(UUID.init(uuidString:))
        guard let savedIdentifier = savedIdentifier,
              let restored = peripherals.first(where: {
                $0.identifier == savedIdentifier
              }) else {
            append("Central restore ignored: no peripheral matches persisted Geely identity; "
                + "fallback owner is not persisted")
            return
        }
        stopCentralScanSafely(central, reason: "retain restored owner")
        cancelCentralReconnect()
        clearCentralRestorationRecovery()
        resetCentralRestoreOwnershipClaims()
        centralHardResetReason = nil
        centralRequireFreshAdvertisement = false
        centralOwnerConfiguredForAncs = true
        centralSystemAutoReconnectActive = restored.state == .connecting
        centralRestoredPendingOwner = restored.state == .connecting
        geelyPeripheral = restored
        restored.delegate = self
        UserDefaults.standard.set(restored.identifier.uuidString,
                                  forKey: savedGeelyPeripheralPreference)
        clearCentralRuntime(keepPeripheral: true)
        if restored.state == .connected {
            // CONTRACT_V39_RESTORED_CONNECTED_OWNER_HALF: do not infer B4 from the central
            // restoration callback alone. Preserve this exact wrapper until the independently
            // restored F05 characteristic proves that the same persisted owner was subscribed.
            centralRestoredConnectedOwner = restored
            centralRestoredB4HintConsumed = false
        } else {
            // `.connecting` restoration, scan/retrieve and future didConnect paths must start B4
            // false. They can only regain it through an exact current didSubscribe callback.
            clearCentralRestoredB4Hint()
        }
        append("Central restore owner retained · "
            + restored.identifier.uuidString + " · state=\(restored.state.rawValue)"
            + " · stable link-anchor")
        if centralRestoredPendingOwner {
            append("Restored .connecting owner retained without timeout; "
                + "waiting for same-owner F04 system/beacon proof")
        }
        guard central.state == .poweredOn else {
            centralRestorationAwaitingPower = true
            setStatus("CENTRAL · ЖДУ POWERED ON", color: .systemOrange)
            append("Central restore сохранён; BLE-команды отложены до poweredOn")
            return
        }
        centralRestorationAwaitingPower = false
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested, self.role == .central else { return }
            self.startCentralRouteIfPossible()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard runRequested, role == .central,
              geelyPeripheral == nil || peripheral === geelyPeripheral
              else { return }
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey]
            as? [CBUUID] ?? []
        guard advertisedServices.contains(managedIncomingBeaconUUID) else { return }
        append("Найден Geely_ANCS · id=\(peripheral.identifier.uuidString) · RSSI=\(RSSI)")
        centralRequireFreshAdvertisement = false
        if centralRestoredPendingOwner, let restored = geelyPeripheral,
           restored === peripheral {
            scheduleRestoredOwnerRecovery(restored,
                evidence: "matching stable beacon, RSSI=\(RSSI)")
            return
        }
        connectCentral(peripheral, reason: "stable anchor beacon")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral else {
            cancelCentralConnectionSafely(peripheral, manager: central,
                                            reason: "didConnect for inactive owner")
            return
        }
        if centralRestorationReconnectPending {
            append("Late didConnect arrived after one-shot cancel; wait for didDisconnect")
            armRestoredOwnerPostCancelObservation()
            setStatus("CENTRAL · ЖДУ RESTORE DISCONNECT", color: .systemOrange)
            return
        }
        if centralManualReconnectPending || centralHardResetReason != nil {
            append("Late didConnect arrived while manual/hard terminal state is pending; wait")
            armCentralPendingTerminalStateObservation()
            setStatus("CENTRAL · ЖДУ TERMINAL STATE", color: .systemOrange)
            return
        }
        if let intent = centralDeferredConnectIntent,
           intent.peripheral === peripheral {
            // CONTRACT_V39_LATE_DIDCONNECT_CONSUMES_EXACT_INTENT: a terminal callback may be
            // followed by an already-queued didConnect from the previous RequiresANCS request.
            // Accept that exact owner and atomically suppress the replacement instead of
            // cancelling a healthy physical link.
            clearCentralDeferredConnectIntent()
            centralOwnerConfiguredForAncs = true
            append("Matching late didConnect consumed deferred exact-owner reconnect intent")
        }
        guard centralOwnerConfiguredForAncs else {
            append("didConnect не принят: owner не был открыт/восстановлен с RequiresANCS")
            cancelCentralConnectionSafely(peripheral, manager: central,
                                            reason: "didConnect without RequiresANCS")
            return
        }
        cancelCentralReconnect()
        clearCentralRestorationRecovery()
        resetCentralRestoreOwnershipClaims()
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        // CONTRACT_V39_FRESH_DIDCONNECT_CLEARS_RESTORED_B4_HINT
        clearCentralRestoredB4Hint()
        append("Central connected · \(peripheral.identifier.uuidString)")
        continueCentralConnected(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        guard peripheral === geelyPeripheral else { return }
        clearCentralRestoredB4Hint()
        append("Central connect failed · \(error?.localizedDescription ?? "без ошибки")")
        if centralRestorationReconnectPending {
            reopenRestoredOwnerAfterTerminalCallback(peripheral,
                callback: "didFailToConnect")
            return
        }
        if centralManualReconnectPending {
            reopenManualOwnerAfterTerminalCallback(peripheral,
                callback: "didFailToConnect")
            return
        }
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        if let hardReset = hardReset {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            centralOwnerConfiguredForAncs = false
            clearCentralPendingTerminalStateObservation()
            queueCentralConnectIntent(peripheral,
                reason: "same stable owner after \(hardReset)", delay: 0.5)
        } else {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            queueTerminalCentralReconnect(peripheral,
                reason: "same owner after didFailToConnect")
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
        guard peripheral === geelyPeripheral else { return }
        clearCentralRestoredB4Hint()
        append("Central disconnected · systemReconnect=\(isReconnecting) · "
            + (error?.localizedDescription ?? "без ошибки"))
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        if centralRestorationReconnectPending {
            reopenRestoredOwnerAfterTerminalCallback(peripheral,
                callback: "didDisconnect")
            return
        }
        if centralManualReconnectPending {
            reopenManualOwnerAfterTerminalCallback(peripheral,
                callback: "didDisconnect")
            return
        }
        if let hardReset = hardReset {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            guard runRequested, role == .central else { return }
            centralOwnerConfiguredForAncs = false
            clearCentralPendingTerminalStateObservation()
            queueCentralConnectIntent(peripheral,
                reason: "same stable owner after \(hardReset)", delay: 0.5)
            return
        }

        // An ordinary radio loss keeps the restored CBPeripheral, F05 GATT server and Core
        // Bluetooth auto-reconnect request. Never cancel a system-owned attempt.
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        guard runRequested, role == .central else { return }
        if isReconnecting {
            centralOwnerConfiguredForAncs = true
            centralSystemAutoReconnectActive = true
            append("Core Bluetooth auto-reconnect retained; no cancel and no second connect")
            setStatus("CENTRAL · SYSTEM RECONNECT", color: .systemBlue)
            return
        }
        // CONTRACT_V39_TERMINAL_MATERIALIZES_EXACT_INTENT: isReconnecting=false is a terminal
        // boundary. Keep the identity, pair and F04/F05 services, but represent the next request
        // explicitly. A matching late didConnect atomically consumes this intent above.
        queueTerminalCentralReconnect(peripheral,
            reason: "ordinary disconnect; retained exact owner")
    }

    func centralManager(_ central: CBCentralManager,
                        didUpdateANCSAuthorizationFor peripheral: CBPeripheral) {
        guard peripheral === geelyPeripheral else { return }
        centralAncsAuthorized = peripheral.ancsAuthorized
        centralAncsAuthorizationCallbackObserved = true
        if !centralAncsAuthorized {
            // CONTRACT_V39_EXPLICIT_AUTH_FALSE_INVALIDATES_CURRENT_PROOF: an observed revoke is
            // stronger than the previous Android CCCD proof. Keep the physical link and B3/READY
            // state, but require a new current-owner ANCS-SUBSCRIBED before green can return.
            centralAncsAccessProven = false
            centralAncsCccdConfirmed = false
        }
        append("ANCS authorization changed · allowed=\(centralAncsAuthorized)"
            + " · accessProof=\(centralAncsAccessProven)"
            + " · handshake=\(centralHandshake); link retained")
        // CONTRACT_V39_AUTH_UPDATE_NEVER_TEARS_LINK: Android's current-owner CCCD result proves
        // data-path availability. A false privacy callback revokes that old proof, but never
        // cancels this owner or suppresses the already B3-gated ANCS-READY write.
        refreshCentralReadiness(centralAncsAuthorized
            ? "iPhone ANCS authorization allowed"
            : "iPhone ANCS authorization denied; waiting for new Android proof")
        updateConnectionStatus()
    }
}

extension ViewController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .discovering else { return }
        if let error = error {
            let detail = centralErrorDescription(error)
            append("Discover services error: \(detail)")
            scheduleCentralServiceRediscovery(
                peripheral, reason: "service discovery failed · \(detail)")
            return
        }
        guard let service = peripheral.services?.first(where: {
            $0.uuid == serviceUUID
        }) else {
            append("Stable F04 service отсутствует после подключения")
            scheduleCentralServiceRediscovery(peripheral, reason: "stable F04 missing")
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
              peripheral === geelyPeripheral,
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
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        if let currentControl = centralControlCharacteristic,
           characteristic === currentControl,
           centralHandshake == .writingPair {
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
        if let currentSecure = centralSecureCharacteristic,
           characteristic === currentSecure,
           centralHandshake == .writingAncsReady {
            if let error = error {
                let detail = centralErrorDescription(error)
                append("WRITE ANCS-READY error: \(detail)")
                resetCentralLink(reason: "same-owner ANCS-READY failed · \(detail)")
            } else {
                centralHandshake = .ready
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
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        if let currentWake = centralWakeCharacteristic,
           characteristic === currentWake,
           centralHandshake == .ready {
            if let error = error {
                append("Android B4 wake notification error: \(centralErrorDescription(error))")
                scheduleCentralWakeSubscriptionRetry(
                    peripheral, reason: "wake notification error")
            } else {
                publishTelemetry(reason: "KX11 background wake poll", force: true)
            }
            return
        }
        guard let currentSecure = centralSecureCharacteristic,
              characteristic === currentSecure,
              centralHandshake == .readingSecure else { return }
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
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .ready,
              let currentWake = centralWakeCharacteristic,
              characteristic === currentWake else { return }
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
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              let currentService = centralService,
              invalidatedServices.contains(where: { $0 === currentService }) else { return }
        // CONTRACT_V40_SERVICE_CHANGED_REARMS_BUDGET: unlike didDisconnect, this callback is
        // direct evidence that Core Bluetooth discarded the exact current F04 object. A stale
        // same-UUID CBService from an earlier publication cannot re-arm cancellation.
        rearmCentralDestructiveRecoveryForFreshF04(
            peripheral, source: "Service Changed invalidated exact F04")
        append("Stable F04 invalidated; rediscovering once on the retained owner")
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralHandshake = .discovering
        centralServiceRediscoveryAttempt = 0
        scheduleCentralServiceRediscovery(peripheral, reason: "stable F04 invalidated")
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
        // CONTRACT_V39_RESTORED_SERVICE_INSTALLS_EXACT_LINEAGE: restoration supersedes any
        // pre-callback local add attempt and owns a fresh monotonic publication generation.
        clearPublishedService()
        let expectedService = role == .central ? telemetryRelayServiceUUID : serviceUUID
        if let service = services.first(where: { $0.uuid == expectedService }) {
            for characteristic in service.characteristics ?? [] {
                guard let mutable = characteristic as? CBMutableCharacteristic else { continue }
                switch characteristic.uuid {
                case infoUUID: infoCharacteristic = mutable
                case controlUUID: controlCharacteristic = mutable
                case secureUUID: secureCharacteristic = mutable
                case telemetryUUID, telemetryRelayUUID:
                    telemetryCharacteristic = mutable
                    telemetrySubscribers = Set(
                        (mutable.subscribedCentrals ?? []).map { $0.identifier })
                    if role == .central,
                       service.uuid == telemetryRelayServiceUUID,
                       mutable.uuid == telemetryRelayUUID {
                        // CONTRACT_V39_RESTORED_F05_SUBSCRIBER_HALF: retain the exact restored
                        // characteristic object and its restored central IDs. This half never
                        // sets B4 by itself and is safe whether it arrives before or after the
                        // CBCentralManager restoration callback.
                        centralRestoredF05Characteristic = mutable
                        centralRestoredF05SubscriberIDs = Set(
                            (mutable.subscribedCentrals ?? []).map { $0.identifier })
                    }
                default: break
                }
            }
            servicePublished = role == .central
                ? telemetryCharacteristic != nil
                : infoCharacteristic != nil && controlCharacteristic != nil
                    && secureCharacteristic != nil && telemetryCharacteristic != nil
            if servicePublished {
                publishedLocalService = service
                publishedLocalServiceGeneration = localServicePublicationGeneration
                publishedServiceUUID = service.uuid
            }
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested else { return }
            if self.servicePublished {
                if self.role == .peripheral { self.startAdvertising() }
                else {
                    if !self.centralRestoredB4HintConsumed {
                        self.centralB4Subscribed = false
                    }
                    self.append("Restored F05 subscriber list retained as one half of exact "
                        + "two-sided restoration proof; it cannot establish readiness alone")
                    self.updateConnectionStatus()
                    self.startCentralRouteIfPossible()
                }
            } else { self.publishServiceIfPossible() }
            self.publishTelemetry(reason: "state restoration", force: true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService,
                           error: Error?) {
        guard let pending = pendingLocalService,
              let pendingGeneration = pendingLocalServiceGeneration,
              service === pending,
              pendingGeneration == localServicePublicationGeneration else {
            // CONTRACT_V39_STALE_DIDADD_IS_OBSERVATION_ONLY: never clear flags, remove services,
            // publish a route, or disturb the exact newer generation for a late same-UUID add.
            append("Ignoring stale local GATT didAdd callback · uuid=\(service.uuid.uuidString)")
            return
        }
        let expectedService = role == .central ? telemetryRelayServiceUUID : serviceUUID
        guard runRequested, service.uuid == expectedService else {
            peripheral.remove(pending)
            clearPublishedService()
            return
        }
        serviceAddPending = false
        pendingLocalService = nil
        pendingLocalServiceGeneration = nil
        if let failure = error {
            append("didAdd service error: \(failure.localizedDescription)")
            setStatus("ОШИБКА GATT", color: .systemRed)
            clearPublishedService()
            return
        }
        servicePublished = true
        publishedLocalService = pending
        publishedLocalServiceGeneration = pendingGeneration
        publishedServiceUUID = service.uuid
        if role == .central {
            append("B4 telemetry relay опубликован на iPhone GATT owner")
            updateConnectionStatus()
            DispatchQueue.main.async { [weak self] in
                self?.startCentralRouteIfPossible()
            }
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
        guard runRequested else { return }
        if role == .central {
            guard let currentTelemetry = telemetryCharacteristic,
                  characteristic === currentTelemetry else { return }
        } else {
            guard isLocalTelemetryUUID(characteristic.uuid) else { return }
        }
        telemetrySubscribers.insert(central.identifier)
        append("KX11 subscribed B4 · \(central.identifier.uuidString)")
        if role != .central || central.identifier == geelyPeripheral?.identifier {
            confirmCentralB4Subscription("CCCD subscription on current owner")
        } else {
            append("B4 subscription сохранена, но не относится к current Central owner")
        }
        updateConnectionStatus()
        publishTelemetry(reason: "B4 subscribed", force: true)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        if role == .central {
            guard let currentTelemetry = telemetryCharacteristic,
                  characteristic === currentTelemetry else { return }
        } else {
            guard isLocalTelemetryUUID(characteristic.uuid) else { return }
        }
        telemetrySubscribers.remove(central.identifier)
        append("KX11 unsubscribed B4 · \(central.identifier.uuidString)")
        if role == .central && central.identifier == geelyPeripheral?.identifier {
            // Rebuild only the reverse Android client. The protected iPhone Central owner and
            // its auto-reconnect request remain untouched.
            centralB4Subscribed = false
            centralAncsCccdConfirmed = false
            centralAncsAccessProven = false
            append("B4/ANCS proofs очищены для current owner; RequiresANCS link сохраняю")
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
        if role == .central {
            guard let currentTelemetry = telemetryCharacteristic,
                  request.characteristic === currentTelemetry else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
                return
            }
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
            let exactCurrentRelay = telemetryCharacteristic.map {
                request.characteristic === $0
            } ?? false
            let ancsAck = role == .central
                && exactCurrentRelay
                && command == "ANCS-SUBSCRIBED"
                && request.central.identifier == geelyPeripheral?.identifier
                && telemetrySubscribers.contains(request.central.identifier)
                && geelyPeripheral?.state == .connected
                && centralSecureLinkReady
                && centralHandshake == .ready
                && centralAncsReadyWriteIssued
                && centralHelperConfirmed
                && centralB4Subscribed
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
