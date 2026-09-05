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

final class NatroLiveActivityManager: NSObject {
    static let shared = NatroLiveActivityManager()
    static let statusChanged = Notification.Name("NatroLiveActivityStatusChanged")

    private weak var remote: CarRemoteClient?
    private var commandObserverInstalled = false
    private var notificationsInstalled = false
    private var lastANCSConnected = false
    private var lastDemoMode = false
    private var demoTargetTemperature = 2_200
    private var demoActiveControlIDs = Set([2, 3])

    private(set) var statusText = "Live Activity ещё не запущена"
    var isANCSConnected: Bool { remote?.ancsConnected == true }
    var isRunning: Bool {
        guard #available(iOS 16.2, *) else { return false }
        return !Activity<NatroLiveActivityAttributes>.activities.isEmpty
    }

    private override init() { super.init() }

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
        if let activity = Activity<NatroLiveActivityAttributes>.activities.first {
            update(activity, with: contentState())
            publishStatus("Live Activity активна")
            return true
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            publishStatus("Live Activity отключены в настройках iPhone")
            return false
        }
        // Activity.request is a foreground-only local API. Existing activities can still receive
        // background BLE updates; a truly background first start requires APNs push-to-start.
        guard UIApplication.shared.applicationState == .active else {
            publishStatus("Откройте Helper один раз: iOS не разрешает локальный фоновый старт")
            return false
        }
        do {
            _ = try Activity<NatroLiveActivityAttributes>.request(
                attributes: NatroLiveActivityAttributes(),
                content: ActivityContent(state: contentState(), staleDate: nil),
                pushType: nil
            )
            publishStatus("Live Activity запущена · \(reason)")
            return true
        } catch {
            publishStatus("Не удалось запустить Live Activity: \(error.localizedDescription)")
            return false
        }
    }

    func stop() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard #available(iOS 16.2, *) else { return }
        let finalContent = ActivityContent(state: contentState(), staleDate: nil)
        Task {
            for activity in Activity<NatroLiveActivityAttributes>.activities {
                await activity.end(finalContent, dismissalPolicy: .immediate)
            }
            await MainActor.run { self.publishStatus("Live Activity остановлена") }
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
        let demo = NatroLiveActivityPreferences.demoMode
        if demo && !lastDemoMode {
            resetDemoState()
        }
        lastDemoMode = demo
        if demo {
            _ = ensureRunning(reason: "ДЕМО", force: true)
        } else if NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "Helper открыт")
        }
    }

    @objc private func preferencesChanged() {
        let demo = NatroLiveActivityPreferences.demoMode
        if demo && !lastDemoMode {
            resetDemoState()
        }
        lastDemoMode = demo
        if demo {
            _ = ensureRunning(reason: "ДЕМО", force: true)
            publishStatus("Live Activity активна · ДЕМО")
        } else if NatroLiveActivityPreferences.automaticStart {
            _ = ensureRunning(reason: "настройки обновлены")
        }
        updateExistingActivities()
    }

    private func resetDemoState() {
        demoTargetTemperature = 2_200
        demoActiveControlIDs = Set([2, 3])
    }

    private func execute(_ action: String) {
        if NatroLiveActivityPreferences.demoMode {
            executeDemo(action)
            return
        }
        guard let remote, remote.isSynced else {
            publishStatus("Команда не отправлена: Natro ещё не синхронизирован")
            return
        }
        let parts = action.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        if parts[0] == "temperature", let direction = Int(parts[1]),
           let definition = CarRemoteCatalogV1.byID[11],
           let current = remote.states[11], current.known {
            let requested = min(
                definition.maximum,
                max(definition.minimum, current.value + Int32(direction) * definition.step)
            )
            remote.send(controlID: 11, operation: .set, value: requested)
            return
        }
        guard let rawID = UInt8(parts[1]), remote.available.contains(rawID),
              let definition = CarRemoteCatalogV1.byID[rawID] else {
            publishStatus("Эта кнопка недоступна в комплектации автомобиля")
            return
        }
        switch parts[0] {
        case "toggle":
            let active = remote.states[rawID]?.active == true
                || remote.states[rawID]?.value != 0
            remote.send(
                controlID: rawID,
                operation: .set,
                value: active ? 0 : max(1, definition.maximum)
            )
        case "cycle":
            remote.send(controlID: rawID, operation: .cycle, value: 0)
        default:
            return
        }
    }

    private func executeDemo(_ action: String) {
        let parts = action.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        if parts[0] == "temperature", let direction = Int(parts[1]) {
            demoTargetTemperature = min(3_000, max(1_600,
                demoTargetTemperature + direction * 50))
        } else if let controlID = Int(parts[1]), parts[0] == "toggle" {
            if demoActiveControlIDs.contains(controlID) {
                demoActiveControlIDs.remove(controlID)
            } else {
                demoActiveControlIDs.insert(controlID)
            }
        } else if let controlID = Int(parts[1]), parts[0] == "cycle" {
            if demoActiveControlIDs.contains(controlID) {
                demoActiveControlIDs.remove(controlID)
            } else {
                demoActiveControlIDs.insert(controlID)
            }
        }
        publishStatus("Live Activity активна · ДЕМО")
        updateExistingActivities()
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
        if NatroLiveActivityPreferences.demoMode {
            return NatroLiveActivityAttributes.ContentState(
                vehicleName: NatroLiveActivityPreferences.vehicleName,
                status: "ДЕМО · ANCS подключён",
                ancsConnected: true,
                isDemo: true,
                targetTemperatureHundredths: demoTargetTemperature,
                cabinTemperatureTenths: 190,
                outdoorTemperatureTenths: 70,
                activeControlIDs: demoActiveControlIDs.sorted(),
                availableControlIDs: CarRemoteCatalogV1.controls.map { Int($0.id) }.sorted(),
                controls: NatroLiveActivityPreferences.controls,
                showVehicle: NatroLiveActivityPreferences.showVehicle,
                updatedAt: Date()
            )
        }
        let remote = remote
        let active = remote?.states.compactMap { id, state in
            state.known && (state.active || state.value != 0) ? Int(id) : nil
        }.sorted() ?? []
        let available = remote?.available.map(Int.init).sorted() ?? []
        let target = remote?.states[11].flatMap { $0.known ? Int($0.value) : nil }
        let status: String
        if remote?.ancsConnected == true {
            status = "ANCS подключён"
        } else if remote?.ancsStateKnown == true {
            status = "ANCS отключён"
        } else if remote?.isSynced == true {
            status = "Состояние ANCS уточняется"
        } else {
            status = "Ожидание Natro"
        }
        return NatroLiveActivityAttributes.ContentState(
            vehicleName: NatroLiveActivityPreferences.vehicleName,
            status: status,
            ancsConnected: remote?.ancsConnected == true,
            isDemo: false,
            targetTemperatureHundredths: target,
            cabinTemperatureTenths: remote?.cabinTemperatureTenths.map(Int.init),
            outdoorTemperatureTenths: remote?.outdoorTemperatureTenths.map(Int.init),
            activeControlIDs: active,
            availableControlIDs: available,
            controls: NatroLiveActivityPreferences.controls,
            showVehicle: NatroLiveActivityPreferences.showVehicle,
            updatedAt: Date()
        )
    }

    private func publishStatus(_ text: String) {
        statusText = text
        NotificationCenter.default.post(name: Self.statusChanged, object: self)
    }
}
