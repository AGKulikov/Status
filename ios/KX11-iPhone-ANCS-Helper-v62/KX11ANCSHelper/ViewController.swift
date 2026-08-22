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
    private let liveActivityStatusLabel = UILabel()
    private let liveActivitySettingsButton = UIButton(type: .system)
    private let showLogsSwitch = UISwitch()
    private let journalTextView = UITextView()
    private let exportJournalButton = UIButton(type: .system)
    private let clearJournalButton = UIButton(type: .system)
    private var switchRuntime: HelperSwitchRuntimeCoordinator?
    private var telemetrySource: HelperTelemetrySource?
    private var enrollmentTimer: Timer?
    private var enrollmentExpiresAt: Date?
    private var lastJournalSnapshot: String?
    private let diagnosticJournalQueue = DispatchQueue(
        label: "ru.natro.kx11ancshelper.diagnostic-journal"
    )
    private var lastDiagnosticAt: [String: Date] = [:]
    private var suppressedDiagnostics: [String: Int] = [:]
    lazy var carRemoteClient = CarRemoteClient { [weak self] frame in
        self?.switchRuntime?.sendCarRemoteFrame(frame)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
        ANCSConnectionJournal.shared.append("app", "Helper 62 запущен")
        carRemoteClient.onDiagnostic = { [weak self] message in
            self?.recordDiagnostic("C5", message)
        }
        carRemoteClient.start()
        NatroLiveActivityManager.shared.bind(to: carRemoteClient)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(renderLiveActivityStatus),
            name: NatroLiveActivityManager.statusChanged,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(renderJournal),
            name: ANCSConnectionJournal.changed,
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
            runtime.onDiagnosticEvent = { [weak self] message in
                self?.recordDiagnostic("BLE", message)
            }
            runtime.onSnapshot = { [weak self] snapshot in
                DispatchQueue.main.async {
                    self?.recordJournalSnapshot(snapshot)
                    self?.render(snapshot)
                    self?.carRemoteClient.setTransportReady(snapshot.phase == .active)
                }
            }
            runtime.onEnrollmentEvent = { [weak self] event in
                ANCSConnectionJournal.shared.append("enrollment", event.journalDescription)
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
            ANCSConnectionJournal.shared.append(
                "BLE",
                "не удалось создать runtime: \(error.localizedDescription)"
            )
            renderConstructionFailure(error)
        }
#endif
    }

    deinit {
        enrollmentTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    /// Keeps the connection journal useful during C5 catalog streaming. High-rate success
    /// callbacks are summarized; state transitions and errors are still written immediately.
    private func recordDiagnostic(_ component: String, _ message: String) {
        diagnosticJournalQueue.async { [weak self] in
            guard let self else { return }
            let interval: TimeInterval?
            if message.contains("получен валидный C5 кадр автомобиля") {
                interval = 30
            } else if message.contains("C5 HELLO отправлен") {
                interval = 60
            } else {
                interval = nil
            }
            guard let interval else {
                ANCSConnectionJournal.shared.append(component, message)
                return
            }
            let key = "\(component)|\(message)"
            let now = Date()
            if let previous = self.lastDiagnosticAt[key],
               now.timeIntervalSince(previous) < interval {
                self.suppressedDiagnostics[key, default: 0] += 1
                return
            }
            let suppressed = self.suppressedDiagnostics.removeValue(forKey: key) ?? 0
            self.lastDiagnosticAt[key] = now
            let suffix = suppressed > 0 ? " · повторов подавлено: \(suppressed)" : ""
            ANCSConnectionJournal.shared.append(component, message + suffix)
        }
    }

    private func configureView() {
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = UIColor(red: 0.018, green: 0.024, blue: 0.037, alpha: 1)
        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .largeTitle)
        title.text = "Настройки Natro"
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
        liveActivityStatusLabel.font = .preferredFont(forTextStyle: .footnote)
        liveActivityStatusLabel.textColor = .secondaryLabel
        liveActivityStatusLabel.adjustsFontForContentSizeCategory = true
        liveActivityStatusLabel.numberOfLines = 0
        liveActivitySettingsButton.setTitle("Настроить Live Activity", for: .normal)
        liveActivitySettingsButton.setImage(
            UIImage(systemName: "rectangle.inset.filled.and.person.filled"),
            for: .normal
        )
        liveActivitySettingsButton.addTarget(
            self,
            action: #selector(openLiveActivitySettings),
            for: .touchUpInside
        )
        renderLiveActivityStatus()
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

        let journalTitle = UILabel()
        journalTitle.font = .preferredFont(forTextStyle: .headline)
        journalTitle.text = "Журнал подключения ANCS"
        journalTitle.adjustsFontForContentSizeCategory = true
        let journalHint = UILabel()
        journalHint.font = .preferredFont(forTextStyle: .footnote)
        journalHint.textColor = .secondaryLabel
        journalHint.numberOfLines = 0
        journalHint.text = "Пишется всегда и хранит этапы BLE, привязки, CONTROL, C5 и ANCS без ключей, идентификаторов и текста уведомлений."
        let showLogsLabel = UILabel()
        showLogsLabel.text = "Показывать журнал"
        showLogsLabel.font = .preferredFont(forTextStyle: .body)
        showLogsLabel.adjustsFontForContentSizeCategory = true
        let journalToggleRow = UIStackView(arrangedSubviews: [showLogsLabel, showLogsSwitch])
        journalToggleRow.axis = .horizontal
        journalToggleRow.alignment = .center
        journalToggleRow.distribution = .equalSpacing
        showLogsSwitch.isOn = UserDefaults.standard.bool(
            forKey: ANCSConnectionJournal.showLogsKey
        )
        showLogsSwitch.addTarget(
            self,
            action: #selector(showLogsChanged),
            for: .valueChanged
        )
        journalTextView.isEditable = false
        journalTextView.isSelectable = true
        journalTextView.alwaysBounceVertical = true
        journalTextView.backgroundColor = .secondarySystemBackground
        journalTextView.layer.cornerRadius = 12
        journalTextView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        journalTextView.textColor = .secondaryLabel
        journalTextView.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)
        journalTextView.heightAnchor.constraint(equalToConstant: 280).isActive = true
        exportJournalButton.setTitle("Экспортировать журнал", for: .normal)
        exportJournalButton.setImage(UIImage(systemName: "square.and.arrow.up"), for: .normal)
        exportJournalButton.addTarget(self, action: #selector(exportJournal), for: .touchUpInside)
        clearJournalButton.setTitle("Очистить", for: .normal)
        clearJournalButton.setTitleColor(.systemRed, for: .normal)
        clearJournalButton.addTarget(self, action: #selector(confirmClearJournal), for: .touchUpInside)
        let journalButtons = UIStackView(arrangedSubviews: [exportJournalButton, clearJournalButton])
        journalButtons.axis = .horizontal
        journalButtons.alignment = .center
        journalButtons.distribution = .equalSpacing

        let stack = UIStackView(arrangedSubviews: [
            title, roleControl, desiredLabel, activeLabel, phaseLabel, detailLabel,
            liveActivityStatusLabel, liveActivitySettingsButton,
            bindingLabel, enrollmentLabel, sasLabel, startEnrollmentButton,
            confirmSASButton, cancelEnrollmentButton, resetBindingButton, retryButton,
            journalTitle, journalHint, journalToggleRow, journalTextView, journalButtons
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
        renderJournal()
    }

    @objc private func showLogsChanged() {
        UserDefaults.standard.set(showLogsSwitch.isOn, forKey: ANCSConnectionJournal.showLogsKey)
        ANCSConnectionJournal.shared.append(
            "settings",
            showLogsSwitch.isOn ? "отображение журнала включено" : "отображение журнала выключено"
        )
        renderJournal()
    }

    @objc private func renderJournal() {
        let visible = showLogsSwitch.isOn
        journalTextView.isHidden = !visible
        guard visible else { return }
        journalTextView.text = ANCSConnectionJournal.shared.tailText()
        if !journalTextView.text.isEmpty {
            let end = NSRange(location: journalTextView.text.utf16.count - 1, length: 1)
            journalTextView.scrollRangeToVisible(end)
        }
    }

    @objc private func exportJournal() {
        do {
            let url = try ANCSConnectionJournal.shared.exportURL()
            ANCSConnectionJournal.shared.append("journal", "подготовлен экспорт журнала")
            let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let popover = controller.popoverPresentationController {
                popover.sourceView = exportJournalButton
                popover.sourceRect = exportJournalButton.bounds
            }
            present(controller, animated: true)
        } catch {
            let alert = UIAlertController(
                title: "Не удалось экспортировать",
                message: "Журнал остался в приложении. Повторите попытку.",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "ОК", style: .default))
            present(alert, animated: true)
        }
    }

    @objc private func confirmClearJournal() {
        let alert = UIAlertController(
            title: "Очистить журнал?",
            message: "Предыдущие диагностические записи будут удалены.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Очистить", style: .destructive) { _ in
            ANCSConnectionJournal.shared.clear()
        })
        present(alert, animated: true)
    }

    @objc private func openLiveActivitySettings() {
        navigationController?.pushViewController(LiveActivitySettingsViewController(), animated: true)
    }

    @objc private func renderLiveActivityStatus() {
        let manager = NatroLiveActivityManager.shared
        let connection = NatroLiveActivityPreferences.demoMode
            ? "ДЕМО · реальный ANCS не подменяется"
            : (manager.isANCSConnected ? "ANCS подключён" : "ANCS не подключён")
        liveActivityStatusLabel.text = "Live Activity: \(manager.statusText)\n\(connection)"
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

    private func recordJournalSnapshot(_ snapshot: HelperSwitchRuntimeCoordinator.Snapshot) {
        let role = snapshot.activeRole.map(name(for:)) ?? "нет"
        let value = "\(snapshot.phase.title)|\(role)|\(snapshot.detail)"
        guard value != lastJournalSnapshot else { return }
        lastJournalSnapshot = value
        ANCSConnectionJournal.shared.append(
            "runtime",
            "состояние: \(snapshot.phase.title); активный маршрут: \(role); \(snapshot.detail)"
        )
    }

    private func renderSimulatorUnavailable() {
        ANCSConnectionJournal.shared.append(
            "simulator",
            "Core Bluetooth owner не создаётся в iPhone Simulator"
        )
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

private extension HelperPeripheralRoute.EnrollmentEvent {
    var journalDescription: String {
        switch self {
        case .sessionStarted(_, let replacing):
            return replacing
                ? "окно привязки открыто; прежняя связь сохраняется до commit"
                : "окно первой привязки открыто"
        case .sasReady:
            return "SAS рассчитан; ожидается визуальное сравнение без записи самого кода"
        case .waitingForAndroidConfirmation:
            return "SAS подтверждён на iPhone; ожидается подтверждение Natro"
        case .commitStaged:
            return "новая привязка записана в pending и ожидает routine proof"
        case .completed:
            return "защищённая привязка полностью подтверждена"
        case .cancelled:
            return "привязка отменена"
        case .expired:
            return "окно привязки истекло"
        case .failed(let reason):
            return "ошибка привязки: \(reason)"
        }
    }
}
