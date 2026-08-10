import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let controller = ViewController()
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        // State-restoration launches can remain background-only. Force ViewController to create
        // both stable Core Bluetooth managers before didFinishLaunching returns, instead of
        // waiting for a later foreground layout pass.
        _ = controller.view
        return true
    }
}
