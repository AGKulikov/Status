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
    _ = object
    _ = userInfo
    guard let observer else { return }
    let manager = Unmanaged<NatroLiveActivityManager>.fromOpaque(observer)
        .takeUnretainedValue()
    let directAction = NatroLiveActivityCommandMailbox.action(
        fromDirectNotification: name
    )
    DispatchQueue.main.async {
        if let directAction {
            manager.receiveExternalCommand(directAction)
        } else {
            manager.consumePendingCommand()
        }
    }
}

/// One state owner for the in-app dashboard and both Live Activities.
/// Demo state never changes the real ANCS boolean used by App Intents.
final class NatroLiveActivityManager: NSObject {
    static let shared = NatroLiveActivityManager()
    static let statusChanged = Notification.Name("NatroLiveActivityStatusChanged")

    private weak var remote: CarRemoteClient?
    private var commandObserverInstalled = false
    private var notificationsInstalled = false
    private var lastVehicleConnected = false
    private var lastDemoMode = false
    private var demoValues: [UInt8: Int32] = [:]
    private var replacingActivities = false
    private var endingActivities = false
    private var creatingActivities = false
    private var pendingStartAfterEnd = false
    private var pendingStartAfterEndIsForced = false
    private var lastActivityFingerprints: [String: Data] = [:]
    // Keep iOS-16 ActivityKit types out of stored properties because the host app still supports
    // iOS 14. The compact state is decoded only inside availability-gated update methods.
    private var pendingActivityStates: [String: Data] = [:]
    private var scheduledActivityUpdates = Set<String>()
    private var lastActivityUpdateDates: [String: Date] = [:]
    private var activityUpdatesTask: Task<Void, Never>?
    private static let maximumEncodedStateBytes = 3_500
    // Two concurrent cards used to emit up to two ActivityKit transactions every 750 ms.
    // Coalesce telemetry bursts into a human-visible cadence; explicit demo commands still ask
    // for an urgent flush so the tapped control responds immediately.
    private static let minimumActivityUpdateInterval: TimeInterval = 0.5

    private(set) var statusText = "Live Activity ещё не запущены"
    var isANCSConnected: Bool { remote?.ancsConnected == true }
    var isVehicleConnected: Bool { remote?.isSynced == true }
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
        installActivityObserversIfNeeded()
        consumePendingCommand()
        remoteDidChange(remote)
    }

    func remoteDidChange(_ remote: CarRemoteClient) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard self.remote === remote else { return }
        let connectedNow = remote.isSynced
        let becameConnected = connectedNow && !lastVehicleConnected
        let becameDisconnected = !connectedNow && lastVehicleConnected
        lastVehicleConnected = connectedNow

        if isDemoMode {
            if NatroLiveActivityPreferences.automaticStart, runningCount == 0 {
                _ = ensureRunning(reason: "ДЕМО", force: true)
            }
        } else if connectedNow {
            // C5 publishes dozens of catalog/state callbacks during one synchronization. Helper
            // 68 retried Activity.request on every callback whenever iOS (correctly) reported no
            // local activity in the background. Besides producing hundreds of log entries, that
            // also resent provisioning over C5 and starved Core Bluetooth's main-queue work.
            // Start/provision exactly on the disconnected -> connected edge. If iOS requires the
            // app in front, applicationDidBecomeActive is the one explicit retry boundary.
            if becameConnected {
                provisionLocalState(to: remote)
                if NatroLiveActivityPreferences.automaticStart {
                    _ = ensureRunning(reason: "магнитола подключена по Bluetooth")
                }
            }
        } else if becameDisconnected || runningCount > 0 {
            stop(reason: "магнитола отключена")
        }
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
            updateExistingActivities(immediate: true)
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
        // remote.send applies the predicted state before returning. Flush that exact state now;
        // the slower telemetry coalescer remains in place for unsolicited sensor bursts.
        updateExistingActivities(immediate: true)
    }

    @discardableResult
    func ensureRunning(reason: String, force: Bool = false) -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        ANCSConnectionJournal.shared.append(
            "live-activity",
            "start reason=\(reason); force=\(force); existing=\(runningCount); demo=\(isDemoMode)"
        )
        guard #available(iOS 16.2, *) else {
            publishStatus("Для Live Activity требуется iOS 16.2 или новее")
            return false
        }
        guard force || NatroLiveActivityPreferences.automaticStart else {
            publishStatus("Автозапуск Live Activity выключен")
            return false
        }
        guard isDemoMode || isVehicleConnected else {
            publishStatus("Live Activity ждёт подключения магнитолы по Bluetooth")
            return false
        }
        guard !endingActivities else {
            pendingStartAfterEnd = true
            pendingStartAfterEndIsForced = pendingStartAfterEndIsForced || force
            publishStatus("Live Activity перезапустится после завершения старых карточек")
            return false
        }
        guard !creatingActivities else {
            publishStatus("Live Activity уже запускается")
            return false
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            publishStatus("Live Activity отключены в настройках iPhone")
            return false
        }

        let existing = Activity<NatroLiveActivityAttributes>.activities
        if !replacingActivities, existing.contains(where: { activity in
            activity.attributes.controlIDs == nil
                || activity.attributes.vehicleName == nil
                || activity.attributes.showVehicle == nil
        }) {
            replaceActivities(reason: "обновление Helper 69")
            return true
        }
        for activity in existing { update(activity) }
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

        creatingActivities = true
        defer { creatingActivities = false }
        var created = 0
        var failureText: String?
        for panel in missing {
            do {
                let attributes = attributes(for: panel)
                _ = try Activity<NatroLiveActivityAttributes>.request(
                    attributes: attributes,
                    content: ActivityContent(
                        state: contentState(for: attributes.resolvedControls),
                        staleDate: nil
                    ),
                    // Personal Team profiles cannot carry aps-environment. Request a strictly
                    // local activity so creation does not fail for lack of a push entitlement.
                    pushType: nil
                )
                created += 1
                ANCSConnectionJournal.shared.append(
                    "live-activity",
                    "created panel=\(panel.rawValue); stateBytes="
                        + "\(encodedSize(of: contentState(for: attributes.resolvedControls)))"
                )
            } catch {
                failureText = error.localizedDescription
                ANCSConnectionJournal.shared.append(
                    "live-activity",
                    "create failed panel=\(panel.rawValue); error=\(error.localizedDescription)"
                )
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
        stop(reason: "ручная остановка")
    }

    private func stop(reason: String) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard #available(iOS 16.2, *) else { return }
        guard !endingActivities else { return }
        let activities = Activity<NatroLiveActivityAttributes>.activities
        guard !activities.isEmpty else {
            clearActivityCaches()
            publishStatus("Live Activity остановлены · \(reason)")
            return
        }
        endingActivities = true
        pendingStartAfterEnd = false
        pendingStartAfterEndIsForced = false
        ANCSConnectionJournal.shared.append(
            "live-activity",
            "end reason=\(reason); activities=\(activities.count)"
        )
        Task {
            for activity in activities {
                let finalContent = ActivityContent(
                    state: contentState(for: activity.attributes.resolvedControls),
                    staleDate: nil
                )
                await activity.end(finalContent, dismissalPolicy: .immediate)
            }
            await MainActor.run {
                self.clearActivityCaches()
                self.endingActivities = false
                let restart = self.pendingStartAfterEnd
                    && (self.isDemoMode || self.isVehicleConnected)
                let force = self.pendingStartAfterEndIsForced
                self.pendingStartAfterEnd = false
                self.pendingStartAfterEndIsForced = false
                self.publishStatus("Live Activity остановлены · \(reason)")
                if restart {
                    _ = self.ensureRunning(
                        reason: self.isDemoMode ? "ДЕМО" : "магнитола переподключена",
                        force: force
                    )
                }
            }
        }
    }

    func consumePendingCommand() {
        dispatchPrecondition(condition: .onQueue(.main))
        while let action = NatroLiveActivityCommandMailbox.consume() {
            execute(action)
        }
    }

    func receiveExternalCommand(_ action: String) {
        dispatchPrecondition(condition: .onQueue(.main))
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
            let names: [CFString] = NatroLiveActivityCommandMailbox.supportsSharedDelivery
                ? [NatroLiveActivityCommandMailbox.notification]
                : NatroLiveActivityCommandMailbox.directNotificationNames
            for name in names {
                CFNotificationCenterAddObserver(
                    CFNotificationCenterGetDarwinNotifyCenter(),
                    Unmanaged.passUnretained(self).toOpaque(),
                    natroLiveCommandCallback,
                    name,
                    nil,
                    .deliverImmediately
                )
            }
        }
    }

    private func installActivityObserversIfNeeded() {
        guard #available(iOS 16.2, *) else { return }
        // Personal Team cannot provision APNs. This observes only the local ActivityKit lifecycle;
        // push-to-start and per-activity push-token observers intentionally do not exist.
        if activityUpdatesTask == nil {
            activityUpdatesTask = Task { [weak self] in
                for await activity in Activity<NatroLiveActivityAttributes>.activityUpdates {
                    guard !Task.isCancelled else { return }
                    guard let manager = self else { return }
                    await MainActor.run {
                        if manager.isDemoMode || manager.isVehicleConnected {
                            manager.updateExistingActivities(immediate: true)
                            manager.publishChangeOnly()
                        } else {
                            let final = ActivityContent(
                                state: manager.contentState(
                                    for: activity.attributes.resolvedControls),
                                staleDate: nil
                            )
                            Task { await activity.end(final, dismissalPolicy: .immediate) }
                        }
                    }
                }
            }
        }
    }

    private func provisionLocalState(to remote: CarRemoteClient) {
        guard remote.isSynced else { return }
        sendConfiguration(to: remote)
    }

    private func sendConfiguration(to remote: CarRemoteClient) {
        let name = Array(NatroLiveActivityPreferences.vehicleName.utf8.prefix(48))
        var payload: [UInt8] = [1]
        var flags: UInt8 = 0
        if NatroLiveActivityPreferences.automaticStart { flags |= 1 }
        if NatroLiveActivityPreferences.showVehicle { flags |= 2 }
        payload.append(flags)
        payload.append(contentsOf: paddedControlIDs(NatroLiveActivityPreferences.climateControls))
        payload.append(contentsOf: paddedControlIDs(NatroLiveActivityPreferences.functionControls))
        payload.append(UInt8(name.count))
        payload.append(contentsOf: name)
        remote.sendLiveActivityProvisioning(type: .configuration, payload: Data(payload))
    }

    private func paddedControlIDs(_ controls: [NatroLiveControl]) -> [UInt8] {
        var ids = Array(controls.prefix(4)).map(\.controlID)
        while ids.count < 4 { ids.append(1) }
        return ids
    }

    @objc private func applicationDidBecomeActive() {
        consumePendingCommand()
        syncDemoTransition()
        if isDemoMode {
            _ = ensureRunning(reason: "ДЕМО", force: true)
        } else if isVehicleConnected && NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "Helper открыт")
        } else if !isVehicleConnected && runningCount > 0 {
            stop(reason: "магнитола не подключена")
        }
    }

    @objc private func preferencesChanged() {
        syncDemoTransition()
        if !isDemoMode && !isVehicleConnected {
            if runningCount > 0 { stop(reason: "магнитола не подключена") }
        } else if runningCount > 0 {
            if #available(iOS 16.2, *) {
                replaceActivities(reason: isDemoMode ? "ДЕМО" : "настройки обновлены")
            }
        } else if isDemoMode {
            _ = ensureRunning(reason: "ДЕМО", force: true)
        } else if NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "настройки обновлены")
        }
        updateExistingActivities()
        if let remote, remote.isSynced { provisionLocalState(to: remote) }
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
        values[64] = 1
        values[65] = 2
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
            // Show one-shot commands in demo instead of making the tap appear broken. The real
            // car exposes no durable active state for these pulses, so the highlight clears.
            demoValues[controlID] = 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                guard let self, self.isDemoMode else { return }
                self.demoValues[controlID] = 0
                self.updateExistingActivities()
                self.publishChangeOnly()
            }
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
        return true
    }

    private func updateExistingActivities(immediate: Bool = false) {
        guard #available(iOS 16.2, *) else { return }
        let activities = Activity<NatroLiveActivityAttributes>.activities
        let liveIDs = Set(activities.map(\.id))
        lastActivityFingerprints = lastActivityFingerprints.filter { liveIDs.contains($0.key) }
        pendingActivityStates = pendingActivityStates.filter { liveIDs.contains($0.key) }
        scheduledActivityUpdates = scheduledActivityUpdates.intersection(liveIDs)
        lastActivityUpdateDates = lastActivityUpdateDates.filter { liveIDs.contains($0.key) }
        for activity in activities { update(activity, immediate: immediate) }
    }

    @available(iOS 16.2, *)
    private func update(
        _ activity: Activity<NatroLiveActivityAttributes>,
        immediate: Bool = false
    ) {
        let state = contentState(for: activity.attributes.resolvedControls)
        guard encodedSize(of: state) <= Self.maximumEncodedStateBytes else {
            publishStatus("Live Activity не обновлена: внутренний payload превышает лимит")
            ANCSConnectionJournal.shared.append(
                "live-activity",
                "update blocked locally; stateBytes=\(encodedSize(of: state))"
            )
            return
        }
        var stableState = state
        stableState.updatedAtEpoch = 0
        guard let fingerprint = try? JSONEncoder().encode(stableState),
              lastActivityFingerprints[activity.id] != fingerprint else { return }
        // Record before starting the async update so a burst of identical BLE callbacks cannot
        // enqueue the same ActivityKit update multiple times.
        lastActivityFingerprints[activity.id] = fingerprint
        guard let pendingPayload = try? JSONEncoder().encode(state) else { return }
        pendingActivityStates[activity.id] = pendingPayload
        if scheduledActivityUpdates.contains(activity.id) {
            if immediate { flushUpdate(activity) }
            return
        }
        let elapsed = Date().timeIntervalSince(lastActivityUpdateDates[activity.id] ?? .distantPast)
        let delay = immediate ? 0 : max(0, Self.minimumActivityUpdateInterval - elapsed)
        scheduledActivityUpdates.insert(activity.id)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self, weak activity] in
            guard let self, let activity else { return }
            self.flushUpdate(activity)
        }
    }

    @available(iOS 16.2, *)
    private func flushUpdate(_ activity: Activity<NatroLiveActivityAttributes>) {
        dispatchPrecondition(condition: .onQueue(.main))
        scheduledActivityUpdates.remove(activity.id)
        guard let payload = pendingActivityStates.removeValue(forKey: activity.id),
              let state = try? JSONDecoder().decode(
                NatroLiveActivityAttributes.ContentState.self, from: payload) else { return }
        lastActivityUpdateDates[activity.id] = Date()
        Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
    }

    @available(iOS 16.1, *)
    private func contentState(
        for controls: [NatroLiveControl]
    ) -> NatroLiveActivityAttributes.ContentState {
        let demo = isDemoMode
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
        let statusCode: UInt8 = demo ? 4 : remote?.ancsConnected == true ? 3
            : remote?.ancsStateKnown == true ? 2 : remote?.isSynced == true ? 1 : 0
        let target = states[11].flatMap { $0.known ? Int($0.value) : nil }
        var values: [Int32] = []
        var flags: [UInt8] = []
        for control in controls.prefix(4) {
            let state = states[control.controlID]
            let available = demo || remote?.available.contains(control.controlID) == true
            var byte: UInt8 = available ? 0x01 : 0
            if state?.known == true { byte |= 0x02 }
            if state?.known == true,
               state?.active == true || demoActive(
                controlID: control.controlID, value: state?.value ?? 0) { byte |= 0x04 }
            values.append(state?.value ?? 0)
            flags.append(byte)
        }
        return NatroLiveActivityAttributes.ContentState(
            statusCode: statusCode,
            targetTemperatureHundredths: target.map { Int16(clamping: $0) },
            cabinTemperatureTenths: (demo ? Int32(190) : remote?.cabinTemperatureTenths)
                .map { Int16(clamping: $0) },
            outdoorTemperatureTenths: (demo ? Int32(70) : remote?.outdoorTemperatureTenths)
                .map { Int16(clamping: $0) },
            values: values,
            valueFlags: flags,
            updatedAtEpoch: UInt32(clamping: Int64(Date().timeIntervalSince1970))
        )
    }

    @available(iOS 16.2, *)
    private func attributes(for panel: NatroLivePanel) -> NatroLiveActivityAttributes {
        let controls = panel == .climate
            ? NatroLiveActivityPreferences.climateControls
            : NatroLiveActivityPreferences.functionControls
        return NatroLiveActivityAttributes(
            panel: panel,
            controlIDs: Array(controls.prefix(4)).map(\.controlID),
            vehicleName: NatroLiveActivityPreferences.vehicleName,
            showVehicle: NatroLiveActivityPreferences.showVehicle
        )
    }

    @available(iOS 16.2, *)
    private func replaceActivities(reason: String) {
        guard !replacingActivities,
              UIApplication.shared.applicationState == .active else {
            updateExistingActivities()
            return
        }
        replacingActivities = true
        let activities = Activity<NatroLiveActivityAttributes>.activities
        Task {
            for activity in activities {
                let final = ActivityContent(
                    state: contentState(for: activity.attributes.resolvedControls),
                    staleDate: nil
                )
                await activity.end(final, dismissalPolicy: .immediate)
            }
            await MainActor.run {
                self.clearActivityCaches()
                self.replacingActivities = false
                _ = self.ensureRunning(reason: reason, force: true)
            }
        }
    }

    @available(iOS 16.1, *)
    private func encodedSize(of state: NatroLiveActivityAttributes.ContentState) -> Int {
        (try? JSONEncoder().encode(state).count) ?? Int.max
    }

    private func publishStatus(_ text: String) {
        statusText = text
        publishChangeOnly()
    }

    private func clearActivityCaches() {
        lastActivityFingerprints.removeAll()
        pendingActivityStates.removeAll()
        scheduledActivityUpdates.removeAll()
        lastActivityUpdateDates.removeAll()
    }

    private func publishChangeOnly() {
        NotificationCenter.default.post(name: Self.statusChanged, object: self)
    }
}
