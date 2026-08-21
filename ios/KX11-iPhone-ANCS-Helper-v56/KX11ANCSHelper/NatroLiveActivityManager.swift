import ActivityKit
import CoreFoundation
import Foundation
import UIKit

private func natroLiveCommandCallback(
    _ center: CFNotificationCenter?,
    _ observer: UnsafeMutableRawPointer?,
    _ name: CFNotificationName?,
    _ object: UnsafeRawPointer?,
    _ userInfo: CFDictionary?
) {
    _ = center
    _ = name
    _ = object
    _ = userInfo
    guard let observer else { return }
    let manager = Unmanaged<NatroLiveActivityManager>.fromOpaque(observer)
        .takeUnretainedValue()
    DispatchQueue.main.async { manager.consumePendingCommand() }
}

/// One state owner for the in-app dashboard and both Live Activities.
/// Demo state never changes the real ANCS boolean used by App Intents.
final class NatroLiveActivityManager: NSObject {
    static let shared = NatroLiveActivityManager()
    static let statusChanged = Notification.Name("NatroLiveActivityStatusChanged")

    private weak var remote: CarRemoteClient?
    private var commandObserverInstalled = false
    private var notificationsInstalled = false
    private var lastANCSConnected = false
    private var lastDemoMode = false
    private var demoValues: [UInt8: Int32] = [:]

    private(set) var statusText = "Live Activity ещё не запущены"
    var isANCSConnected: Bool { remote?.ancsConnected == true }
    var isDemoMode: Bool { NatroLiveActivityPreferences.demoMode }

    var runningCount: Int {
        guard #available(iOS 16.2, *) else { return 0 }
        return Activity<NatroLiveActivityAttributes>.activities.count
    }

    var isRunning: Bool { runningCount > 0 }

    private override init() {
        super.init()
        resetDemoState()
    }

    func bind(to remote: CarRemoteClient) {
        dispatchPrecondition(condition: .onQueue(.main))
        self.remote = remote
        installObserversIfNeeded()
        consumePendingCommand()
        remoteDidChange(remote)
    }

    func remoteDidChange(_ remote: CarRemoteClient) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard self.remote === remote else { return }
        let connectedNow = remote.ancsConnected
        if connectedNow && !lastANCSConnected && NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "ANCS подключён")
        }
        lastANCSConnected = connectedNow
        updateExistingActivities()
        publishChangeOnly()
    }

    /// Returns the exact effective state used by the dashboard and widget.
    func state(for controlID: UInt8) -> CarRemoteClient.State? {
        dispatchPrecondition(condition: .onQueue(.main))
        if isDemoMode, let value = demoValues[controlID] {
            return CarRemoteClient.State(
                available: true,
                known: true,
                active: demoActive(controlID: controlID, value: value),
                value: value
            )
        }
        return remote?.states[controlID]
    }

    func isAvailable(_ controlID: UInt8) -> Bool {
        isDemoMode ? CarRemoteCatalogV1.byID[controlID] != nil
            : remote?.available.contains(controlID) == true
    }

    /// Shared command path for dashboard controls and Live Activity mailbox actions.
    func send(
        controlID: UInt8,
        operation: CarRemoteProtocolV1.Operation,
        value: Int32 = 0,
        confirmed: Bool = false,
        completion: ((CarRemoteProtocolV1.ResultCode) -> Void)? = nil
    ) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let definition = CarRemoteCatalogV1.byID[controlID] else {
            completion?(.invalid)
            return
        }
        guard !definition.requiresConfirmation || confirmed else {
            publishStatus("Команда требует явного подтверждения")
            completion?(.rejected)
            return
        }
        if isDemoMode {
            applyDemo(controlID: controlID, operation: operation, requestedValue: value)
            publishStatus("Обе Live Activity активны · ДЕМО")
            updateExistingActivities()
            completion?(.ok)
            return
        }
        guard let remote, remote.isSynced, remote.available.contains(controlID) else {
            publishStatus("Команда не отправлена: функция ещё не синхронизирована")
            completion?(.unsupported)
            return
        }
        remote.send(
            controlID: controlID,
            operation: operation,
            value: value,
            confirmed: confirmed,
            completion: completion
        )
    }

    @discardableResult
    func ensureRunning(reason: String, force: Bool = false) -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        guard #available(iOS 16.2, *) else {
            publishStatus("Для Live Activity требуется iOS 16.2 или новее")
            return false
        }
        guard force || NatroLiveActivityPreferences.automaticStart else {
            publishStatus("Автозапуск Live Activity выключен")
            return false
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            publishStatus("Live Activity отключены в настройках iPhone")
            return false
        }

        let existing = Activity<NatroLiveActivityAttributes>.activities
        for activity in existing {
            update(activity, with: contentState())
        }
        let panels = Set(existing.map { $0.attributes.resolvedPanel })
        let missing = NatroLivePanel.allCases.filter { !panels.contains($0) }
        if missing.isEmpty {
            publishStatus("Обе Live Activity активны")
            return true
        }

        // Local Activity.request remains foreground-only. Once created, BLE state can update it
        // in the background. A true background-first start would require APNs push-to-start.
        guard UIApplication.shared.applicationState == .active else {
            publishStatus("Откройте Helper один раз: iOS не разрешает локальный фоновый старт")
            return !existing.isEmpty
        }

        var created = 0
        var failureText: String?
        for panel in missing {
            do {
                _ = try Activity<NatroLiveActivityAttributes>.request(
                    attributes: NatroLiveActivityAttributes(panel: panel),
                    content: ActivityContent(state: contentState(), staleDate: nil),
                    pushType: nil
                )
                created += 1
            } catch {
                failureText = error.localizedDescription
            }
        }
        if runningCount >= 2 || existing.count + created >= 2 {
            publishStatus("Обе Live Activity запущены · \(reason)")
            return true
        }
        if let failureText {
            publishStatus("Запущено \(existing.count + created) из 2: \(failureText)")
        } else {
            publishStatus("Запущено \(existing.count + created) из 2")
        }
        return existing.count + created > 0
    }

    func stop() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard #available(iOS 16.2, *) else { return }
        let finalContent = ActivityContent(state: contentState(), staleDate: nil)
        Task {
            for activity in Activity<NatroLiveActivityAttributes>.activities {
                await activity.end(finalContent, dismissalPolicy: .immediate)
            }
            await MainActor.run { self.publishStatus("Обе Live Activity остановлены") }
        }
    }

    func consumePendingCommand() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let action = NatroLiveActivityCommandMailbox.consume() else { return }
        execute(action)
    }

    private func installObserversIfNeeded() {
        if !notificationsInstalled {
            notificationsInstalled = true
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(applicationDidBecomeActive),
                name: UIApplication.didBecomeActiveNotification,
                object: nil
            )
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(preferencesChanged),
                name: NatroLiveActivityPreferences.changed,
                object: nil
            )
        }
        if !commandObserverInstalled {
            commandObserverInstalled = true
            CFNotificationCenterAddObserver(
                CFNotificationCenterGetDarwinNotifyCenter(),
                Unmanaged.passUnretained(self).toOpaque(),
                natroLiveCommandCallback,
                NatroLiveActivityCommandMailbox.notification,
                nil,
                .deliverImmediately
            )
        }
    }

    @objc private func applicationDidBecomeActive() {
        consumePendingCommand()
        syncDemoTransition()
        if isDemoMode {
            _ = ensureRunning(reason: "ДЕМО", force: true)
        } else if NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "Helper открыт")
        }
    }

    @objc private func preferencesChanged() {
        syncDemoTransition()
        if isDemoMode {
            _ = ensureRunning(reason: "ДЕМО", force: true)
            publishStatus("Обе Live Activity активны · ДЕМО")
        } else if NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "настройки обновлены")
        }
        updateExistingActivities()
        publishChangeOnly()
    }

    private func syncDemoTransition() {
        let demo = isDemoMode
        if demo && !lastDemoMode { resetDemoState() }
        lastDemoMode = demo
    }

    private func resetDemoState() {
        var values: [UInt8: Int32] = [:]
        for definition in CarRemoteCatalogV1.controls {
            switch definition.kind {
            case .toggle, .action:
                values[definition.id] = 0
            case .levels, .options:
                values[definition.id] = definition.directValues.first?.0 ?? 0
            case .range:
                values[definition.id] = definition.minimum
            }
        }
        values[1] = 1
        values[2] = 1
        values[3] = 1
        values[9] = Int32(0x10020103)
        values[11] = 2_200
        values[12] = 2_200
        values[16] = 2_200
        values[17] = 2_200
        values[20] = Int32(0x10050202)
        values[21] = Int32(0x10050201)
        values[22] = Int32(0x10050101)
        values[24] = Int32(0x10090101)
        values[40] = 1
        values[41] = 5_000
        values[45] = Int32(0x200a0203)
        values[46] = Int32(0x2a080101)
        values[47] = Int32(0x2a010206)
        demoValues = values
    }

    private func execute(_ rawAction: String) {
        let confirmed = rawAction.hasPrefix("confirmed|")
        let action = confirmed ? String(rawAction.dropFirst("confirmed|".count)) : rawAction
        let parts = action.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        if parts[0] == "temperature", let direction = Int32(parts[1]) {
            let current = state(for: 11)?.value ?? 2_200
            guard let definition = CarRemoteCatalogV1.byID[11] else { return }
            let requested = min(
                definition.maximum,
                max(definition.minimum, current + direction * definition.step)
            )
            send(controlID: 11, operation: .set, value: requested)
            return
        }
        guard let controlID = UInt8(parts[1]),
              let definition = CarRemoteCatalogV1.byID[controlID] else { return }
        if definition.requiresConfirmation && !confirmed {
            publishStatus("Команда отклонена: подтверждение не получено")
            return
        }
        let operation: CarRemoteProtocolV1.Operation
        switch parts[0] {
        case "toggle": operation = .toggle
        case "cycle": operation = .cycle
        case "activate": operation = .activate
        default: return
        }
        send(
            controlID: controlID,
            operation: operation,
            value: operation == .activate ? 1 : 0,
            confirmed: confirmed
        )
    }

    private func applyDemo(
        controlID: UInt8,
        operation: CarRemoteProtocolV1.Operation,
        requestedValue: Int32
    ) {
        guard let definition = CarRemoteCatalogV1.byID[controlID] else { return }
        let current = demoValues[controlID] ?? 0
        switch operation {
        case .set:
            demoValues[controlID] = clampedDemoValue(requestedValue, for: definition)
        case .toggle:
            demoValues[controlID] = current == 0 ? toggleOnValue(definition) : 0
        case .cycle:
            let values = definition.directValues.map(\.0)
            guard !values.isEmpty else { return }
            let index = values.firstIndex(of: current) ?? -1
            demoValues[controlID] = values[(index + 1) % values.count]
        case .activate:
            // One-shot actions have no durable active state in the car either.
            demoValues[controlID] = 0
        case .none:
            return
        }
    }

    private func clampedDemoValue(
        _ requested: Int32,
        for definition: CarRemoteControlDefinition
    ) -> Int32 {
        if !definition.directValues.isEmpty {
            return definition.directValues.contains(where: { $0.0 == requested })
                ? requested : (definition.directValues.first?.0 ?? 0)
        }
        return min(definition.maximum, max(definition.minimum, requested))
    }

    private func toggleOnValue(_ definition: CarRemoteControlDefinition) -> Int32 {
        definition.directValues.first(where: { $0.0 != 0 })?.0
            ?? max(1, definition.maximum)
    }

    private func demoActive(controlID: UInt8, value: Int32) -> Bool {
        guard value != 0 else { return false }
        if [8, 9, 10, 15, 20, 21, 22, 23, 24, 25, 26, 27, 28].contains(controlID) {
            return true
        }
        return CarRemoteCatalogV1.byID[controlID]?.kind != .action
    }

    private func updateExistingActivities() {
        guard #available(iOS 16.2, *) else { return }
        let state = contentState()
        for activity in Activity<NatroLiveActivityAttributes>.activities {
            update(activity, with: state)
        }
    }

    @available(iOS 16.2, *)
    private func update(
        _ activity: Activity<NatroLiveActivityAttributes>,
        with state: NatroLiveActivityAttributes.ContentState
    ) {
        Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
    }

    @available(iOS 16.1, *)
    private func contentState() -> NatroLiveActivityAttributes.ContentState {
        let demo = isDemoMode
        let available: [Int] = demo
            ? CarRemoteCatalogV1.controls.map { Int($0.id) }
            : (remote?.available.map(Int.init) ?? [])
        let states: [UInt8: CarRemoteClient.State] = demo
            ? Dictionary(uniqueKeysWithValues: demoValues.map { id, value in
                (id, CarRemoteClient.State(
                    available: true,
                    known: true,
                    active: demoActive(controlID: id, value: value),
                    value: value
                ))
            })
            : (remote?.states ?? [:])
        let active = states.compactMap { id, state in
            state.known && (state.active || demoActive(controlID: id, value: state.value))
                ? Int(id) : nil
        }.sorted()
        let snapshots = NatroLiveControl.allCases.compactMap { control -> NatroLiveControlSnapshot? in
            guard let definition = CarRemoteCatalogV1.byID[control.controlID],
                  let state = states[control.controlID] else { return nil }
            return NatroLiveControlSnapshot(
                controlID: Int(control.controlID),
                known: state.known,
                value: Int(state.value),
                valueText: state.known ? definition.displayValue(state.value) : "—",
                level: stageLevel(controlID: control.controlID, value: state.value),
                automatic: state.value & 0xff == 0x0f
            )
        }
        let status: String
        if demo {
            status = "ДЕМО · ANCS подключён"
        } else if remote?.ancsConnected == true {
            status = "ANCS подключён"
        } else if remote?.ancsStateKnown == true {
            status = "ANCS отключён"
        } else if remote?.isSynced == true {
            status = "Состояние ANCS уточняется"
        } else {
            status = "Ожидание Natro"
        }
        let target = states[11].flatMap { $0.known ? Int($0.value) : nil }
        return NatroLiveActivityAttributes.ContentState(
            vehicleName: NatroLiveActivityPreferences.vehicleName,
            status: status,
            ancsConnected: demo || remote?.ancsConnected == true,
            isDemo: demo,
            targetTemperatureHundredths: target,
            cabinTemperatureTenths: demo ? 190 : remote?.cabinTemperatureTenths.map(Int.init),
            outdoorTemperatureTenths: demo ? 70 : remote?.outdoorTemperatureTenths.map(Int.init),
            activeControlIDs: active,
            availableControlIDs: available.sorted(),
            controls: NatroLiveActivityPreferences.climateControls,
            functionControls: NatroLiveActivityPreferences.functionControls,
            controlSnapshots: snapshots,
            showVehicle: NatroLiveActivityPreferences.showVehicle,
            updatedAt: Date()
        )
    }

    private func stageLevel(controlID: UInt8, value: Int32) -> Int? {
        guard [20, 21, 22, 23, 24, 25, 26, 27, 28].contains(controlID) else { return nil }
        if value == 0 { return 0 }
        let suffix = Int(value & 0xff)
        if suffix == 0x0f { return 3 }
        return (1...3).contains(suffix) ? suffix : nil
    }

    private func publishStatus(_ text: String) {
        statusText = text
        publishChangeOnly()
    }

    private func publishChangeOnly() {
        NotificationCenter.default.post(name: Self.statusChanged, object: self)
    }
}
