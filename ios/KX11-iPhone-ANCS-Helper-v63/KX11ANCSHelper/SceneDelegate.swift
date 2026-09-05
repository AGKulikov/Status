import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        _ = session
        _ = connectionOptions
        guard let windowScene = scene as? UIWindowScene,
              let appDelegate = UIApplication.shared.delegate as? AppDelegate else {
            return
        }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = appDelegate.rootViewController
        window.makeKeyAndVisible()
        self.window = window
        handle(connectionOptions.urlContexts)
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        _ = scene
        handle(URLContexts)
    }

    private func handle(_ contexts: Set<UIOpenURLContext>) {
        guard let action = contexts.lazy.compactMap({ context in
            NatroLiveActivityCommandLink.action(from: context.url)
        }).first else { return }
        // The URL route is a deliberate fallback for development profiles that cannot provision
        // the shared App Group. Production profiles keep the in-place App Intent path and do not
        // bring the app forward for a normal Live Activity tap. A URL scheme is not an identity
        // boundary, so every fallback command requires an in-app confirmation before C5 sees it.
        guard let presenter = window?.rootViewController else { return }
        let alert = UIAlertController(
            title: "Команда Natro",
            message: "Выполнить «\(commandTitle(action))»?",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Выполнить", style: .default) { _ in
            let delivered = NatroLiveActivityCommandMailbox.requiresConfirmation(action)
                ? "confirmed|\(action)" : action
            NatroLiveActivityManager.shared.receiveExternalCommand(delivered)
        })
        (presenter.presentedViewController ?? presenter).present(alert, animated: true)
    }

    private func commandTitle(_ action: String) -> String {
        let plain = action.hasPrefix("confirmed|")
            ? String(action.dropFirst("confirmed|".count)) : action
        let parts = plain.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return "управление автомобилем" }
        if parts[0] == "temperature" {
            return parts[1] == "-1" ? "уменьшить температуру" : "увеличить температуру"
        }
        guard let id = UInt8(parts[1]),
              let control = NatroLiveControl.allCases.first(where: { $0.controlID == id }) else {
            return "управление автомобилем"
        }
        return control.title
    }
}
