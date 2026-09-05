import AppIntents
import Foundation

@available(iOS 16.0, *)
struct NatroANCSStatusIntent: AppIntent {
    static var title: LocalizedStringResource = "Получить состояние ANCS"
    static var description = IntentDescription(
        "Возвращает Да, когда Natro подтвердил готовое ANCS-подключение."
    )
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Bool> {
        .result(value: NatroLiveActivityManager.shared.isANCSConnected)
    }
}

@available(iOS 16.0, *)
struct NatroWaitForANCSIntent: AppIntent {
    static var title: LocalizedStringResource = "Ожидать подключения ANCS"
    static var description = IntentDescription(
        "Ждёт подтверждения ANCS до 25 секунд и возвращает результат для условия в Командах."
    )
    static var openAppWhenRun = false

    @Parameter(title: "Тайм-аут, секунд") var timeoutSeconds: Int

    init() { timeoutSeconds = 20 }
    init(timeoutSeconds: Int) { self.timeoutSeconds = timeoutSeconds }

    func perform() async throws -> some IntentResult & ReturnsValue<Bool> {
        let timeout = min(25, max(1, timeoutSeconds))
        let deadline = Date().addingTimeInterval(TimeInterval(timeout))
        while Date() < deadline {
            if await MainActor.run(body: {
                NatroLiveActivityManager.shared.isANCSConnected
            }) {
                return .result(value: true)
            }
            try await Task.sleep(nanoseconds: 500_000_000)
        }
        return .result(value: await MainActor.run {
            NatroLiveActivityManager.shared.isANCSConnected
        })
    }
}

@available(iOS 16.2, *)
struct NatroStartLiveActivityIntent: AppIntent {
    static var title: LocalizedStringResource = "Запустить Live Activity Natro"
    static var description = IntentDescription(
        "Запускает карточки, когда магнитола синхронизирована по Bluetooth или включён демо-режим."
    )
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<Bool> {
        .result(value: NatroLiveActivityManager.shared.ensureRunning(
            reason: "команда пользователя",
            force: true
        ))
    }
}

@available(iOS 16.2, *)
struct NatroAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: NatroANCSStatusIntent(),
            phrases: [
                "Проверить ANCS в \(.applicationName)",
                "Подключён ли ANCS в \(.applicationName)"
            ],
            shortTitle: "Статус ANCS",
            systemImageName: "antenna.radiowaves.left.and.right"
        )
        AppShortcut(
            intent: NatroWaitForANCSIntent(),
            phrases: ["Ожидать ANCS в \(.applicationName)"],
            shortTitle: "Ожидать ANCS",
            systemImageName: "hourglass"
        )
        AppShortcut(
            intent: NatroStartLiveActivityIntent(),
            phrases: ["Запустить виджет \(.applicationName)"],
            shortTitle: "Live Activity",
            systemImageName: "car.fill"
        )
    }

    static var shortcutTileColor: ShortcutTileColor { .blue }
}
