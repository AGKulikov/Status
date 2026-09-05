import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    // Construct the single app owner before UIKit asks for any scene. This preserves the
    // Core Bluetooth restoration timing that the pre-scene app delegate provided.
    let rootViewController = ViewController()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        _ = launchOptions
        // Loading the owner during launch lets the selected stable Core Bluetooth restoration
        // namespace exist even when iOS relaunches the app in the background without a UI scene.
        _ = rootViewController.view
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        _ = options
        let configuration = UISceneConfiguration(
            name: "Default Configuration",
            sessionRole: connectingSceneSession.role
        )
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}
