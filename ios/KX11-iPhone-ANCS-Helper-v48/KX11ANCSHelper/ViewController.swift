import UIKit

final class ViewController: UIViewController {
    private let roleControl = UISegmentedControl(items: ["iPhone Peripheral", "iPhone Central"])
    private let desiredLabel = UILabel()
    private let activeLabel = UILabel()
    private let phaseLabel = UILabel()
    private let detailLabel = UILabel()
    private let retryButton = UIButton(type: .system)
    private var switchRuntime: HelperSwitchRuntimeCoordinator?
    private var telemetrySource: HelperTelemetrySource?

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
        do {
            let runtime = try HelperSwitchRuntimeCoordinator()
            switchRuntime = runtime
            runtime.onSnapshot = { [weak self] snapshot in
                DispatchQueue.main.async { self?.render(snapshot) }
            }
            runtime.start()
            let telemetry = HelperTelemetrySource()
            telemetry.onSample = { [weak runtime] sample in
                runtime?.publishTelemetry(sample)
            }
            telemetrySource = telemetry
            telemetry.start()
        } catch {
            renderConstructionFailure(error)
        }
    }

    private func configureView() {
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .largeTitle)
        title.text = "KX11 ANCS v48"
        title.adjustsFontForContentSizeCategory = true

        for label in [desiredLabel, activeLabel, phaseLabel] {
            label.font = .preferredFont(forTextStyle: .headline)
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
        }
        detailLabel.font = .preferredFont(forTextStyle: .body)
        detailLabel.textColor = .secondaryLabel
        detailLabel.adjustsFontForContentSizeCategory = true
        detailLabel.numberOfLines = 0

        roleControl.addTarget(self, action: #selector(roleChanged), for: .valueChanged)
        retryButton.setTitle("Повторить безопасное переключение", for: .normal)
        retryButton.addTarget(self, action: #selector(retrySwitch), for: .touchUpInside)
        retryButton.isHidden = true

        let stack = UIStackView(arrangedSubviews: [
            title,
            roleControl,
            desiredLabel,
            activeLabel,
            phaseLabel,
            detailLabel,
            retryButton
        ])
        stack.axis = .vertical
        stack.spacing = 18
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24)
        ])
    }

    @objc private func roleChanged() {
        guard roleControl.isEnabled, let switchRuntime else { return }
        let role: BleRoleSwitchPolicy.Role = roleControl.selectedSegmentIndex == 0
            ? .helperPeripheralAndroidCentral
            : .helperCentralAndroidPeripheral
        switchRuntime.select(role)
    }

    @objc private func retrySwitch() {
        switchRuntime?.retryFailedSwitch()
    }

    private func render(_ snapshot: HelperSwitchRuntimeCoordinator.Snapshot) {
        roleControl.selectedSegmentIndex = index(for: snapshot.desiredRole)
        desiredLabel.text = "Желаемый режим: \(name(for: snapshot.desiredRole))"
        activeLabel.text = "Активный режим: \(snapshot.activeRole.map(name(for:)) ?? "нет")"
        phaseLabel.text = "Состояние: \(snapshot.phase.title)"
        detailLabel.text = snapshot.detail
        roleControl.isEnabled = snapshot.phase == .active
        retryButton.isHidden = snapshot.phase != .failed
        retryButton.isEnabled = snapshot.phase == .failed
        phaseLabel.textColor = snapshot.phase == .failed ? .systemRed : .label
    }

    private func renderConstructionFailure(_ error: Error) {
        roleControl.isEnabled = false
        desiredLabel.text = "Желаемый режим: недоступен"
        activeLabel.text = "Активный режим: нет"
        phaseLabel.text = "Состояние: ошибка"
        phaseLabel.textColor = .systemRed
        detailLabel.text = "Не удалось создать единственного владельца BLE: \(error)"
    }

    private func index(for role: BleRoleSwitchPolicy.Role) -> Int {
        role == .helperPeripheralAndroidCentral ? 0 : 1
    }

    private func name(for role: BleRoleSwitchPolicy.Role) -> String {
        switch role {
        case .helperPeripheralAndroidCentral:
            return "iPhone Peripheral / Android Central"
        case .helperCentralAndroidPeripheral:
            return "iPhone Central / Android Peripheral"
        }
    }
}

private extension HelperSwitchRuntimeCoordinator.Phase {
    var title: String {
        switch self {
        case .active: return "активен"
        case .switching: return "переключение — ввод заблокирован"
        case .failed: return "ошибка — новый маршрут не запущен"
        }
    }
}
