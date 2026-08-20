import UIKit

final class HelperSettingsViewController: UIViewController {
    private let roleControl = UISegmentedControl(items: ["Route A · основной", "Route B · диагностика"])
    private let desiredLabel = UILabel()
    private let activeLabel = UILabel()
    private let phaseLabel = UILabel()
    private let detailLabel = UILabel()
    private let bindingLabel = UILabel()
    private let enrollmentLabel = UILabel()
    private let sasLabel = UILabel()
    private let startEnrollmentButton = UIButton(type: .system)
    private let confirmSASButton = UIButton(type: .system)
    private let cancelEnrollmentButton = UIButton(type: .system)
    private let resetBindingButton = UIButton(type: .system)
    private let retryButton = UIButton(type: .system)
    private var switchRuntime: HelperSwitchRuntimeCoordinator?
    private var telemetrySource: HelperTelemetrySource?
    private var enrollmentTimer: Timer?
    private var enrollmentExpiresAt: Date?
    lazy var carRemoteClient = CarRemoteClient { [weak self] frame in
        self?.switchRuntime?.sendCarRemoteFrame(frame)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
        carRemoteClient.start()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
#if targetEnvironment(simulator)
        // Simulator cannot establish this iPhone ANCS accessory topology. Creating CoreBluetooth
        // managers there only opens an unusable daemon client and produces the misleading
        // "XPC connection invalid" console line.
        renderSimulatorUnavailable()
#else
        do {
            let runtime = try HelperSwitchRuntimeCoordinator()
            let telemetry = HelperTelemetrySource()
            switchRuntime = runtime
            runtime.onSnapshot = { [weak self] snapshot in
                DispatchQueue.main.async {
                    self?.render(snapshot)
                    self?.carRemoteClient.setTransportReady(snapshot.phase == .active)
                }
            }
            runtime.onEnrollmentEvent = { [weak self] event in
                DispatchQueue.main.async { self?.renderEnrollment(event) }
            }
            telemetry.onSample = { [weak runtime] sample in
                runtime?.publishTelemetry(sample)
            }
            runtime.onTelemetryRefreshRequest = { [weak telemetry] in
                DispatchQueue.main.async { telemetry?.requestFreshSample() }
            }
            runtime.onCarRemoteFrame = { [weak self] frame in
                self?.carRemoteClient.accept(frame)
            }
            telemetrySource = telemetry
            telemetry.start()
            runtime.start()
            refreshBindingState()
        } catch {
            renderConstructionFailure(error)
        }
#endif
    }

    deinit {
        enrollmentTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    private func configureView() {
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .largeTitle)
        title.text = "Настройки Helper"
        title.adjustsFontForContentSizeCategory = true

        for label in [desiredLabel, activeLabel, phaseLabel, bindingLabel] {
            label.font = .preferredFont(forTextStyle: .headline)
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
        }
        for label in [detailLabel, enrollmentLabel] {
            label.font = .preferredFont(forTextStyle: .body)
            label.textColor = .secondaryLabel
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
        }
        sasLabel.font = .monospacedDigitSystemFont(ofSize: 34, weight: .bold)
        sasLabel.textAlignment = .center
        sasLabel.accessibilityLabel = "Код безопасного сравнения"
        sasLabel.isHidden = true

        roleControl.addTarget(self, action: #selector(roleChanged), for: .valueChanged)
        startEnrollmentButton.setTitle("Начать безопасную привязку · 60 с", for: .normal)
        startEnrollmentButton.addTarget(self, action: #selector(startEnrollment), for: .touchUpInside)
        confirmSASButton.setTitle("Коды совпадают", for: .normal)
        confirmSASButton.addTarget(self, action: #selector(confirmSAS), for: .touchUpInside)
        confirmSASButton.isHidden = true
        cancelEnrollmentButton.setTitle("Отменить привязку", for: .normal)
        cancelEnrollmentButton.addTarget(self, action: #selector(cancelEnrollment), for: .touchUpInside)
        cancelEnrollmentButton.isHidden = true
        resetBindingButton.setTitle("Сбросить связь Natro", for: .normal)
        resetBindingButton.setTitleColor(.systemRed, for: .normal)
        resetBindingButton.addTarget(self, action: #selector(confirmResetBinding), for: .touchUpInside)
        resetBindingButton.isHidden = true
        retryButton.setTitle("Повторить безопасное переключение", for: .normal)
        retryButton.addTarget(self, action: #selector(retrySwitch), for: .touchUpInside)
        retryButton.isHidden = true

        let stack = UIStackView(arrangedSubviews: [
            title, roleControl, desiredLabel, activeLabel, phaseLabel, detailLabel,
            bindingLabel, enrollmentLabel, sasLabel, startEnrollmentButton,
            confirmSASButton, cancelEnrollmentButton, resetBindingButton, retryButton
        ])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40)
        ])
    }

    @objc private func startEnrollment() {
        guard UIApplication.shared.applicationState == .active else {
            enrollmentLabel.text = "Откройте Helper на экране iPhone и повторите."
            return
        }
        switchRuntime?.beginEnrollment()
    }

    @objc private func confirmSAS() {
        confirmSASButton.isEnabled = false
        switchRuntime?.confirmEnrollmentSAS()
    }

    @objc private func cancelEnrollment() {
        switchRuntime?.cancelEnrollment()
    }

    @objc private func applicationDidEnterBackground() {
        // A pairing sheet does not trigger didEnterBackground, but leaving Helper cancels the
        // in-memory ECDH/SAS session and never changes the last durable binding.
        switchRuntime?.cancelEnrollment()
        clearEnrollmentPresentation()
    }

    @objc private func confirmResetBinding() {
        let alert = UIAlertController(
            title: "Сбросить связь Natro?",
            message: "Удалятся активная и ожидающая защищённые привязки Natro. Системная Classic-пара автомобиля и идентификатор Helper не изменятся.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Сбросить", style: .destructive) { [weak self] _ in
            self?.switchRuntime?.resetEnrollmentBinding { [weak self] result in
                DispatchQueue.main.async {
                    switch result {
                    case .success:
                        self?.enrollmentLabel.text = "Защищённая связь Natro сброшена."
                        self?.refreshBindingState()
                    case .failure:
                        self?.enrollmentLabel.text = "Не удалось сбросить связь в текущем состоянии BLE."
                    }
                }
            }
        })
        present(alert, animated: true)
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

    private func renderEnrollment(_ event: HelperPeripheralRoute.EnrollmentEvent) {
        switch event {
        case .sessionStarted(let expiresAt, let replacing):
            enrollmentExpiresAt = expiresAt
            enrollmentLabel.text = replacing
                ? "Старая связь сохранится до полного подтверждения новой. Ожидание Natro…"
                : "Ожидание защищённого запроса Natro…"
            sasLabel.isHidden = true
            confirmSASButton.isHidden = true
            cancelEnrollmentButton.isHidden = false
            startEnrollmentButton.isEnabled = false
            startCountdown()
        case .sasReady(let code, let expiresAt):
            enrollmentExpiresAt = expiresAt
            sasLabel.text = code
            sasLabel.isHidden = false
            confirmSASButton.isHidden = false
            confirmSASButton.isEnabled = true
            cancelEnrollmentButton.isHidden = false
            enrollmentLabel.text = "Сравните все 8 цифр с экраном Natro. Подтверждайте только полное совпадение."
            startCountdown()
        case .waitingForAndroidConfirmation(let expiresAt):
            enrollmentExpiresAt = expiresAt
            enrollmentLabel.text = "Код подтверждён на iPhone. Завершите проверку на Natro…"
            confirmSASButton.isHidden = true
            startCountdown()
        case .commitStaged:
            clearEnrollmentPresentation()
            enrollmentLabel.text = "Новый ключ ожидает crash-safe подтверждения Natro."
            refreshBindingState()
        case .completed:
            clearEnrollmentPresentation()
            enrollmentLabel.text = "Защищённая связь Natro подтверждена."
            refreshBindingState()
        case .cancelled:
            clearEnrollmentPresentation()
            enrollmentLabel.text = "Привязка отменена."
        case .expired:
            clearEnrollmentPresentation()
            enrollmentLabel.text = "60 секунд истекли. Запустите привязку заново."
        case .failed(let reason):
            clearEnrollmentPresentation()
            enrollmentLabel.text = reason
        }
    }

    private func startCountdown() {
        enrollmentTimer?.invalidate()
        enrollmentTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) {
            [weak self] timer in
            guard let self, let expiresAt = self.enrollmentExpiresAt else {
                timer.invalidate()
                return
            }
            let remaining = max(0, Int(ceil(expiresAt.timeIntervalSinceNow)))
            self.cancelEnrollmentButton.setTitle("Отменить привязку · \(remaining) с", for: .normal)
            if remaining == 0 { timer.invalidate() }
        }
    }

    private func clearEnrollmentPresentation() {
        enrollmentTimer?.invalidate()
        enrollmentTimer = nil
        enrollmentExpiresAt = nil
        sasLabel.text = nil
        sasLabel.isHidden = true
        confirmSASButton.isHidden = true
        confirmSASButton.isEnabled = false
        cancelEnrollmentButton.isHidden = true
        cancelEnrollmentButton.setTitle("Отменить привязку", for: .normal)
        startEnrollmentButton.isEnabled = true
    }

    private func refreshBindingState() {
        switchRuntime?.enrollmentBindingState { [weak self] state in
            DispatchQueue.main.async {
                switch state {
                case .none:
                    self?.bindingLabel.text = "Связь Natro: не настроена"
                    self?.resetBindingButton.isHidden = true
                case .active:
                    self?.bindingLabel.text = "Связь Natro: защищённая привязка активна"
                    self?.resetBindingButton.isHidden = false
                case .pendingRecovery:
                    self?.bindingLabel.text = "Связь Natro: ожидается crash-safe восстановление"
                    self?.resetBindingButton.isHidden = false
                }
            }
        }
    }

    private func render(_ snapshot: HelperSwitchRuntimeCoordinator.Snapshot) {
        roleControl.selectedSegmentIndex = index(for: snapshot.desiredRole)
        desiredLabel.text = "Желаемый режим: \(name(for: snapshot.desiredRole))"
        activeLabel.text = "Активный режим: \(snapshot.activeRole.map(name(for:)) ?? "нет")"
        phaseLabel.text = "Состояние: \(snapshot.phase.title)"
        detailLabel.text = snapshot.detail
        roleControl.isHidden = !snapshot.routeBDiagnosticsEnabled
        roleControl.isEnabled = snapshot.phase == .active
        roleControl.setEnabled(
            snapshot.phase == .active && snapshot.routeBDiagnosticsEnabled,
            forSegmentAt: 1
        )
        startEnrollmentButton.isHidden = snapshot.phase != .active ||
            snapshot.activeRole != .helperPeripheralAndroidCentral
        retryButton.isHidden = snapshot.phase != .failed
        retryButton.isEnabled = snapshot.phase == .failed
        phaseLabel.textColor = snapshot.phase == .failed ? .systemRed : .label
    }

    private func renderSimulatorUnavailable() {
        carRemoteClient.setTransportReady(false)
        roleControl.isHidden = true
        desiredLabel.text = "Желаемый режим: только на физическом iPhone"
        activeLabel.text = "Активный режим: нет"
        phaseLabel.text = "Состояние: симулятор"
        phaseLabel.textColor = .secondaryLabel
        detailLabel.text = "ANCS и Core Bluetooth проверяются на физическом iPhone. "
            + "Simulator intentionally does not start a BLE owner."
        bindingLabel.text = "Связь Natro: недоступна в Simulator"
        startEnrollmentButton.isHidden = true
        resetBindingButton.isHidden = true
        retryButton.isHidden = true
    }

    private func renderConstructionFailure(_ error: Error) {
        carRemoteClient.setTransportReady(false)
        roleControl.isEnabled = false
        desiredLabel.text = "Желаемый режим: недоступен"
        activeLabel.text = "Активный режим: нет"
        phaseLabel.text = "Состояние: ошибка"
        phaseLabel.textColor = .systemRed
        detailLabel.text = "Не удалось создать единственного владельца BLE: \(error)"
        startEnrollmentButton.isHidden = true
        resetBindingButton.isHidden = true
    }

    private func index(for role: BleRoleSwitchPolicy.Role) -> Int {
        role == .helperPeripheralAndroidCentral ? 0 : 1
    }

    private func name(for role: BleRoleSwitchPolicy.Role) -> String {
        switch role {
        case .helperPeripheralAndroidCentral:
            return "iPhone Peripheral / Android Central"
        case .helperCentralAndroidPeripheral:
            return "iPhone Central / Android Peripheral (экспериментальный)"
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
