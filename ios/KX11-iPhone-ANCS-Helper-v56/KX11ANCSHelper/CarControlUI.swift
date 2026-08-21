import UIKit

private let natroCyan = UIColor(red: 0.16, green: 0.70, blue: 0.96, alpha: 1)

/// Main Helper surface: a single Live-Activity-style dashboard plus diagnostics/settings.
final class ViewController: UITabBarController {
    private let settingsController = HelperSettingsViewController()
    private var dashboardController: VehicleDashboardViewController!

    override func viewDidLoad() {
        super.viewDidLoad()

        // AppDelegate forces this root view during launch for Core Bluetooth restoration.
        _ = settingsController.view
        let remote = settingsController.carRemoteClient
        dashboardController = VehicleDashboardViewController(remote: remote)
        dashboardController.title = "Автомобиль"
        dashboardController.tabBarItem = UITabBarItem(
            title: "Автомобиль",
            image: UIImage(systemName: "car"),
            selectedImage: UIImage(systemName: "car.fill")
        )
        settingsController.title = "Настройки"
        settingsController.tabBarItem = UITabBarItem(
            title: "Настройки",
            image: UIImage(systemName: "gearshape"),
            selectedImage: UIImage(systemName: "gearshape.fill")
        )

        remote.onChange = { [weak self, weak remote] in
            self?.dashboardController.reloadRemoteState()
            if let remote { NatroLiveActivityManager.shared.remoteDidChange(remote) }
        }
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(sharedStateChanged),
            name: NatroLiveActivityManager.statusChanged,
            object: nil
        )

        viewControllers = [
            navigationController(for: dashboardController),
            navigationController(for: settingsController)
        ]
        selectedIndex = 0
        tabBar.tintColor = natroCyan
        if #available(iOS 15.0, *) {
            let appearance = UITabBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = UIColor(red: 0.035, green: 0.045, blue: 0.065, alpha: 1)
            tabBar.standardAppearance = appearance
            tabBar.scrollEdgeAppearance = appearance
        }
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    @objc private func sharedStateChanged() { dashboardController?.reloadRemoteState() }

    private func navigationController(for root: UIViewController) -> UINavigationController {
        let navigation = UINavigationController(rootViewController: root)
        navigation.navigationBar.prefersLargeTitles = false
        if #available(iOS 15.0, *) {
            let appearance = UINavigationBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = UIColor(red: 0.025, green: 0.033, blue: 0.05, alpha: 1)
            appearance.titleTextAttributes = [.foregroundColor: UIColor.white]
            appearance.largeTitleTextAttributes = [.foregroundColor: UIColor.white]
            navigation.navigationBar.standardAppearance = appearance
            navigation.navigationBar.scrollEdgeAppearance = appearance
        }
        navigation.navigationBar.tintColor = natroCyan
        return navigation
    }
}

private final class VehicleDashboardViewController: UIViewController {
    private let remote: CarRemoteClient
    private let manager = NatroLiveActivityManager.shared
    private let scrollView = UIScrollView()
    private let contentStack = UIStackView()
    private var rebuildScheduled = false

    private let dark = UIColor(red: 0.018, green: 0.024, blue: 0.037, alpha: 1)
    private let card = UIColor(red: 0.055, green: 0.068, blue: 0.095, alpha: 1)

    init(remote: CarRemoteClient) {
        self.remote = remote
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = dark
        configureScrollView()
        rebuild()
    }

    func reloadRemoteState() {
        guard isViewLoaded, !rebuildScheduled else { return }
        rebuildScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.rebuildScheduled = false
            self.rebuild()
        }
    }

    private func configureScrollView() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        scrollView.backgroundColor = dark
        contentStack.axis = .vertical
        contentStack.spacing = 12
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)
        scrollView.addSubview(contentStack)
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            contentStack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor,
                                                   constant: 14),
            contentStack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor,
                                                    constant: -14),
            contentStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor,
                                               constant: 14),
            contentStack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                                                  constant: -28),
            contentStack.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor,
                                                 constant: -28)
        ])
    }

    private func rebuild() {
        let offset = scrollView.contentOffset
        contentStack.arrangedSubviews.forEach {
            contentStack.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }
        contentStack.addArrangedSubview(makeHeader())
        contentStack.addArrangedSubview(makeHero())
        contentStack.addArrangedSubview(sectionTitle("СЦЕНАРИИ"))
        contentStack.addArrangedSubview(makeScenes())
        for section in CarRemoteControlDefinition.Section.allCases {
            let controls = CarRemoteCatalogV1.controls.filter { $0.section == section }
            guard !controls.isEmpty else { continue }
            contentStack.addArrangedSubview(sectionTitle(section.rawValue.uppercased()))
            for definition in controls {
                contentStack.addArrangedSubview(makeControlCard(definition))
            }
        }
        view.layoutIfNeeded()
        scrollView.setContentOffset(offset, animated: false)
    }

    private func makeHeader() -> UIView {
        let container = UIStackView()
        container.axis = .horizontal
        container.alignment = .center
        container.spacing = 10
        let mark = UILabel()
        mark.text = "N"
        mark.font = .italicSystemFont(ofSize: 28)
        mark.textColor = natroCyan
        mark.setContentHuggingPriority(.required, for: .horizontal)
        let text = UIStackView()
        text.axis = .vertical
        text.spacing = 1
        let title = UILabel()
        title.text = "NATRO"
        title.font = .systemFont(ofSize: 17, weight: .bold)
        title.textColor = .white
        let status = UILabel()
        let connected = manager.isDemoMode || manager.isANCSConnected
        status.text = "●  " + (manager.isDemoMode
            ? "ДЕМО · ANCS подключён"
            : (connected ? "ANCS подключён" : remote.statusText))
        status.font = .systemFont(ofSize: 11, weight: .medium)
        status.textColor = connected ? .systemGreen : .secondaryLabel
        status.numberOfLines = 2
        text.addArrangedSubview(title)
        text.addArrangedSubview(status)
        container.addArrangedSubview(mark)
        container.addArrangedSubview(text)
        container.addArrangedSubview(UIView())
        if manager.isDemoMode {
            let badge = UILabel()
            badge.text = "  ДЕМО  "
            badge.font = .systemFont(ofSize: 10, weight: .bold)
            badge.textColor = .systemOrange
            badge.backgroundColor = UIColor.systemOrange.withAlphaComponent(0.14)
            badge.layer.cornerRadius = 9
            badge.clipsToBounds = true
            container.addArrangedSubview(badge)
        }
        let live = UIButton(type: .system)
        live.setImage(UIImage(systemName: "rectangle.stack.badge.play"), for: .normal)
        live.tintColor = natroCyan
        live.accessibilityLabel = "Настройки Live Activity"
        live.addTarget(self, action: #selector(openLiveActivitySettings), for: .touchUpInside)
        container.addArrangedSubview(live)
        return container
    }

    private func makeHero() -> UIView {
        let hero = UIView()
        hero.backgroundColor = card
        hero.layer.cornerRadius = 22
        hero.layer.cornerCurve = .continuous
        hero.clipsToBounds = true
        hero.heightAnchor.constraint(equalToConstant: 230).isActive = true

        let image = UIImageView(image: UIImage(named: "Monjaro"))
        image.contentMode = .scaleAspectFit
        image.alpha = NatroLiveActivityPreferences.showVehicle ? 1 : 0
        image.translatesAutoresizingMaskIntoConstraints = false
        hero.addSubview(image)

        let carName = UILabel()
        carName.text = NatroLiveActivityPreferences.vehicleName.uppercased()
        carName.font = .systemFont(ofSize: 11, weight: .semibold)
        carName.textColor = UIColor.white.withAlphaComponent(0.75)
        carName.translatesAutoresizingMaskIntoConstraints = false
        hero.addSubview(carName)

        let temp = UILabel()
        let targetState = manager.state(for: 11)
        temp.text = temperatureText(targetState?.known == true ? targetState?.value : nil)
        temp.font = .monospacedDigitSystemFont(ofSize: 42, weight: .medium)
        temp.textColor = .white
        temp.textAlignment = .center
        temp.translatesAutoresizingMaskIntoConstraints = false
        hero.addSubview(temp)

        let minus = circleButton(symbol: "minus", tag: -1, action: #selector(changeTemperature(_:)))
        let plus = circleButton(symbol: "plus", tag: 1, action: #selector(changeTemperature(_:)))
        minus.translatesAutoresizingMaskIntoConstraints = false
        plus.translatesAutoresizingMaskIntoConstraints = false
        hero.addSubview(minus)
        hero.addSubview(plus)

        let cabin = telemetryLabel("Салон", manager.isDemoMode ? 190 : remote.cabinTemperatureTenths)
        let outdoor = telemetryLabel("Улица", manager.isDemoMode ? 70 : remote.outdoorTemperatureTenths)
        let telemetry = UIStackView(arrangedSubviews: [cabin, UIView(), outdoor])
        telemetry.axis = .horizontal
        telemetry.translatesAutoresizingMaskIntoConstraints = false
        hero.addSubview(telemetry)

        let quick = UIStackView()
        quick.axis = .horizontal
        quick.distribution = .fillEqually
        quick.spacing = 7
        for id: UInt8 in [2, 3, 5, 8] {
            guard let definition = CarRemoteCatalogV1.byID[id] else { continue }
            quick.addArrangedSubview(makeQuickButton(definition))
        }
        quick.translatesAutoresizingMaskIntoConstraints = false
        hero.addSubview(quick)

        NSLayoutConstraint.activate([
            carName.leadingAnchor.constraint(equalTo: hero.leadingAnchor, constant: 16),
            carName.topAnchor.constraint(equalTo: hero.topAnchor, constant: 12),
            image.centerXAnchor.constraint(equalTo: hero.centerXAnchor, constant: 20),
            image.topAnchor.constraint(equalTo: hero.topAnchor, constant: 19),
            image.widthAnchor.constraint(equalTo: hero.widthAnchor, multiplier: 0.72),
            image.heightAnchor.constraint(equalToConstant: 108),
            temp.centerXAnchor.constraint(equalTo: hero.centerXAnchor),
            temp.centerYAnchor.constraint(equalTo: image.centerYAnchor, constant: 14),
            minus.leadingAnchor.constraint(equalTo: hero.leadingAnchor, constant: 16),
            minus.centerYAnchor.constraint(equalTo: temp.centerYAnchor),
            minus.widthAnchor.constraint(equalToConstant: 44),
            minus.heightAnchor.constraint(equalToConstant: 44),
            plus.trailingAnchor.constraint(equalTo: hero.trailingAnchor, constant: -16),
            plus.centerYAnchor.constraint(equalTo: temp.centerYAnchor),
            plus.widthAnchor.constraint(equalToConstant: 44),
            plus.heightAnchor.constraint(equalToConstant: 44),
            telemetry.leadingAnchor.constraint(equalTo: hero.leadingAnchor, constant: 16),
            telemetry.trailingAnchor.constraint(equalTo: hero.trailingAnchor, constant: -16),
            telemetry.topAnchor.constraint(equalTo: image.bottomAnchor, constant: -6),
            quick.leadingAnchor.constraint(equalTo: hero.leadingAnchor, constant: 10),
            quick.trailingAnchor.constraint(equalTo: hero.trailingAnchor, constant: -10),
            quick.bottomAnchor.constraint(equalTo: hero.bottomAnchor, constant: -10),
            quick.heightAnchor.constraint(equalToConstant: 58)
        ])
        return hero
    }

    private func makeQuickButton(_ definition: CarRemoteControlDefinition) -> UIView {
        let button = DashboardButton(type: .system)
        button.definition = definition
        let state = manager.state(for: definition.id)
        let active = state?.known == true && (state?.active == true || state?.value != 0)
        button.setTitle(definition.title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 10, weight: .medium)
        button.titleLabel?.numberOfLines = 2
        button.titleLabel?.textAlignment = .center
        button.setTitleColor(active ? natroCyan : .white, for: .normal)
        button.backgroundColor = active
            ? natroCyan.withAlphaComponent(0.13)
            : UIColor.white.withAlphaComponent(0.055)
        button.layer.cornerRadius = 14
        button.isEnabled = manager.isAvailable(definition.id)
        button.alpha = button.isEnabled ? 1 : 0.36
        button.addTarget(self, action: #selector(quickTapped(_:)), for: .touchUpInside)
        return button
    }

    private func makeScenes() -> UIView {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 7
        stack.distribution = .fillEqually
        for scene in DashboardScene.allCases {
            let button = SceneButton(type: .system)
            button.scene = scene
            button.setTitle(scene.title, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 10, weight: .semibold)
            button.titleLabel?.numberOfLines = 3
            button.titleLabel?.textAlignment = .center
            button.setTitleColor(.white, for: .normal)
            button.backgroundColor = card
            button.layer.cornerRadius = 14
            button.heightAnchor.constraint(equalToConstant: 58).isActive = true
            button.addTarget(self, action: #selector(sceneTapped(_:)), for: .touchUpInside)
            stack.addArrangedSubview(button)
        }
        return stack
    }

    private func makeControlCard(_ definition: CarRemoteControlDefinition) -> UIView {
        let state = manager.state(for: definition.id)
        let available = manager.isAvailable(definition.id) && state?.available != false
        let control = DashboardControlCard(
            definition: definition,
            state: state,
            available: available,
            background: card
        )
        control.tap = { [weak self] in self?.controlTapped(definition) }
        control.commit = { [weak self] value in
            self?.manager.send(controlID: definition.id, operation: .set, value: value)
        }
        return control
    }

    private func controlTapped(_ definition: CarRemoteControlDefinition) {
        guard manager.isAvailable(definition.id) else { return }
        switch definition.kind {
        case .toggle:
            let execute = { [weak self] in
                _ = self?.manager.send(
                    controlID: definition.id,
                    operation: .toggle,
                    confirmed: definition.requiresConfirmation
                )
            }
            definition.requiresConfirmation ? confirmMechanical(definition, execute) : execute()
        case .levels, .options:
            selectValue(definition)
        case .range:
            break
        case .action:
            let execute = { [weak self] in
                _ = self?.manager.send(
                    controlID: definition.id,
                    operation: .activate,
                    value: 1,
                    confirmed: definition.requiresConfirmation
                )
            }
            definition.requiresConfirmation ? confirmMechanical(definition, execute) : execute()
        }
    }

    private func selectValue(_ definition: CarRemoteControlDefinition) {
        let sheet = UIAlertController(title: definition.title, message: nil,
                                      preferredStyle: .actionSheet)
        for (value, title) in selectableValues(for: definition) {
            sheet.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                self?.manager.send(controlID: definition.id, operation: .set, value: value)
            })
        }
        sheet.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(
            x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1
        )
        present(sheet, animated: true)
    }

    private func selectableValues(
        for definition: CarRemoteControlDefinition
    ) -> [(Int32, String)] {
        guard definition.id == 9 else { return definition.directValues }
        let auto = manager.state(for: 3)?.active == true
        return definition.directValues.filter { value, _ in
            auto ? value >> 8 == Int32(0x100202)
                : value == 0 || value >> 8 == Int32(0x100201)
        }
    }

    private func confirmMechanical(
        _ definition: CarRemoteControlDefinition,
        _ execute: @escaping () -> Void
    ) {
        let alert = UIAlertController(
            title: definition.title,
            message: "Проверьте автомобиль и убедитесь, что рядом с механизмом нет людей и препятствий.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Выполнить", style: .destructive) { _ in execute() })
        present(alert, animated: true)
    }

    private func runScene(_ scene: DashboardScene, index: Int = 0) {
        guard index < scene.commands.count else { return }
        let command = scene.commands[index]
        manager.send(
            controlID: command.controlID,
            operation: command.operation,
            value: command.value
        ) { [weak self] result in
            guard result == .ok else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                self?.runScene(scene, index: index + 1)
            }
        }
    }

    @objc private func openLiveActivitySettings() {
        navigationController?.pushViewController(LiveActivitySettingsViewController(), animated: true)
    }

    @objc private func changeTemperature(_ button: UIButton) {
        guard let definition = CarRemoteCatalogV1.byID[11] else { return }
        let current = manager.state(for: 11)?.value ?? 2_200
        let requested = min(
            definition.maximum,
            max(definition.minimum, current + Int32(button.tag) * definition.step)
        )
        manager.send(controlID: 11, operation: .set, value: requested)
    }

    @objc private func quickTapped(_ button: DashboardButton) {
        guard let definition = button.definition else { return }
        if definition.kind == .levels || definition.kind == .options {
            selectValue(definition)
        } else {
            controlTapped(definition)
        }
    }

    @objc private func sceneTapped(_ button: SceneButton) {
        guard let scene = button.scene else { return }
        runScene(scene)
    }

    private func sectionTitle(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.textColor = UIColor.white.withAlphaComponent(0.58)
        label.font = .systemFont(ofSize: 11, weight: .bold)
        label.accessibilityTraits = .header
        return label
    }

    private func circleButton(symbol: String, tag: Int, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: symbol), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        button.layer.cornerRadius = 22
        button.tag = tag
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    private func telemetryLabel(_ title: String, _ tenths: Int32?) -> UILabel {
        let label = UILabel()
        label.text = title + " " + smallTemperatureText(tenths)
        label.font = .systemFont(ofSize: 11, weight: .medium)
        label.textColor = UIColor.white.withAlphaComponent(0.62)
        return label
    }
}

private final class DashboardControlCard: UIControl {
    var tap: (() -> Void)?
    var commit: ((Int32) -> Void)?
    private let definition: CarRemoteControlDefinition
    private let valueLabel = UILabel()
    private var slider: UISlider?

    init(
        definition: CarRemoteControlDefinition,
        state: CarRemoteClient.State?,
        available: Bool,
        background: UIColor
    ) {
        self.definition = definition
        super.init(frame: .zero)
        self.backgroundColor = background
        layer.cornerRadius = 16
        layer.cornerCurve = .continuous
        alpha = available ? 1 : 0.42
        isEnabled = available
        heightAnchor.constraint(greaterThanOrEqualToConstant: definition.kind == .range ? 82 : 66)
            .isActive = true

        let icon = UIImageView(image: UIImage(systemName: iconName(definition)))
        icon.tintColor = accent(definition)
        icon.contentMode = .scaleAspectFit
        icon.translatesAutoresizingMaskIntoConstraints = false
        let title = UILabel()
        title.text = definition.title
        title.font = .systemFont(ofSize: 14, weight: .semibold)
        title.textColor = .white
        title.numberOfLines = 2
        valueLabel.text = detail(definition, state, available)
        valueLabel.font = .systemFont(ofSize: 11, weight: .medium)
        valueLabel.textColor = .secondaryLabel
        valueLabel.numberOfLines = 2
        let labels = UIStackView(arrangedSubviews: [title, valueLabel])
        labels.axis = .vertical
        labels.spacing = 3
        labels.translatesAutoresizingMaskIntoConstraints = false
        addSubview(icon)
        addSubview(labels)

        var trailing = labels.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor,
                                                         constant: -52)
        if definition.kind == .range {
            let range = UISlider()
            range.minimumValue = Float(definition.minimum)
            range.maximumValue = Float(definition.maximum)
            range.value = Float(state?.known == true ? state?.value ?? definition.minimum
                : definition.minimum)
            range.minimumTrackTintColor = accent(definition)
            range.addTarget(self, action: #selector(sliderChanged(_:)), for: .valueChanged)
            range.addTarget(self, action: #selector(sliderCommitted(_:)),
                            for: [.touchUpInside, .touchUpOutside, .touchCancel])
            range.isEnabled = available
            range.translatesAutoresizingMaskIntoConstraints = false
            addSubview(range)
            slider = range
            trailing = labels.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14)
            NSLayoutConstraint.activate([
                range.leadingAnchor.constraint(equalTo: labels.leadingAnchor),
                range.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
                range.topAnchor.constraint(equalTo: labels.bottomAnchor, constant: 4),
                range.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -7)
            ])
        } else if definition.kind == .levels {
            let levels = makeLevelStrip(definition: definition, state: state)
            levels.translatesAutoresizingMaskIntoConstraints = false
            addSubview(levels)
            NSLayoutConstraint.activate([
                levels.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
                levels.centerYAnchor.constraint(equalTo: centerYAnchor),
                levels.widthAnchor.constraint(equalToConstant: 58)
            ])
        } else {
            let glyph = UIImageView(image: UIImage(systemName:
                definition.requiresConfirmation ? "lock.fill" : "chevron.right"))
            glyph.tintColor = definition.requiresConfirmation ? .systemOrange : .secondaryLabel
            glyph.translatesAutoresizingMaskIntoConstraints = false
            addSubview(glyph)
            NSLayoutConstraint.activate([
                glyph.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
                glyph.centerYAnchor.constraint(equalTo: centerYAnchor)
            ])
        }

        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 15),
            icon.centerYAnchor.constraint(equalTo: centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 24),
            icon.heightAnchor.constraint(equalToConstant: 24),
            labels.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 12),
            labels.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            trailing
        ])
        if definition.kind != .range {
            labels.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -12).isActive = true
            addTarget(self, action: #selector(tapped), for: .touchUpInside)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func tapped() { tap?() }

    @objc private func sliderChanged(_ slider: UISlider) {
        let step = max(1, definition.step)
        let rounded = Int32((Double(slider.value) / Double(step)).rounded()) * step
        let value = min(definition.maximum, max(definition.minimum, rounded))
        slider.value = Float(value)
        valueLabel.text = definition.displayValue(value)
    }

    @objc private func sliderCommitted(_ slider: UISlider) {
        commit?(Int32(slider.value.rounded()))
    }
}

private func makeLevelStrip(
    definition: CarRemoteControlDefinition,
    state: CarRemoteClient.State?
) -> UIStackView {
    let stack = UIStackView()
    stack.axis = .horizontal
    stack.spacing = 3
    stack.distribution = .fillEqually
    let raw = state?.known == true ? state?.value ?? 0 : 0
    let suffix = Int(raw & 0xff)
    let level = suffix == 0x0f ? 3 : ((1...3).contains(suffix) ? suffix : 0)
    for index in 1...3 {
        let capsule = UIView()
        capsule.backgroundColor = index <= level
            ? accent(definition) : UIColor.white.withAlphaComponent(0.1)
        capsule.layer.cornerRadius = 2
        capsule.heightAnchor.constraint(equalToConstant: 5).isActive = true
        stack.addArrangedSubview(capsule)
    }
    if suffix == 0x0f {
        let automatic = UILabel()
        automatic.text = "A"
        automatic.font = .systemFont(ofSize: 8, weight: .bold)
        automatic.textColor = accent(definition)
        stack.addArrangedSubview(automatic)
    }
    return stack
}

private func detail(
    _ definition: CarRemoteControlDefinition,
    _ state: CarRemoteClient.State?,
    _ available: Bool
) -> String {
    guard available else { return "Недоступно в этой комплектации" }
    guard let state, state.known else { return "Состояние синхронизируется" }
    if definition.kind == .toggle { return state.active || state.value != 0 ? "Включено" : "Выключено" }
    if definition.kind == .action { return "Команда с подтверждением" }
    return definition.displayValue(state.value)
}

private func iconName(_ definition: CarRemoteControlDefinition) -> String {
    switch definition.section {
    case .climate: return definition.id == 9 ? "fanblades" : "snowflake"
    case .seats: return definition.id == 24 ? "steeringwheel" : "seat.max"
    case .vehicle: return definition.id >= 55 ? "window.shade.closed" : "car.fill"
    case .comfort: return definition.id == 42 ? "display" : "lightbulb.fill"
    case .media: return "play.circle.fill"
    }
}

private func accent(_ definition: CarRemoteControlDefinition) -> UIColor {
    switch definition.section {
    case .climate: return natroCyan
    case .seats:
        return [22, 23, 27, 28].contains(definition.id) ? natroCyan : .systemOrange
    case .vehicle: return .systemOrange
    case .comfort: return .systemPurple
    case .media: return .systemGreen
    }
}

private func temperatureText(_ hundredths: Int32?) -> String {
    guard let hundredths else { return "—°" }
    return String(format: "%.1f°", Double(hundredths) / 100)
}

private func smallTemperatureText(_ tenths: Int32?) -> String {
    guard let tenths else { return "—°" }
    return String(format: "%.0f°", Double(tenths) / 10)
}

private final class DashboardButton: UIButton {
    var definition: CarRemoteControlDefinition?
}

private final class SceneButton: UIButton {
    var scene: DashboardScene?
}

private enum DashboardScene: CaseIterable {
    case coolDown, winterMorning, comfort, allOff

    var title: String {
        switch self {
        case .coolDown: return "Быстро\nохладить"
        case .winterMorning: return "Зимнее\nутро"
        case .comfort: return "Комфортная\nпоездка"
        case .allOff: return "Всё\nвыключить"
        }
    }

    var commands: [CarRemoteClient.SceneCommand] {
        typealias Command = CarRemoteClient.SceneCommand
        switch self {
        case .coolDown:
            return [
                Command(controlID: 1, operation: .set, value: 1),
                Command(controlID: 2, operation: .set, value: 1),
                Command(controlID: 3, operation: .set, value: 0),
                Command(controlID: 8, operation: .set, value: Int32(0x10030101)),
                Command(controlID: 9, operation: .set, value: Int32(0x10020107)),
                Command(controlID: 11, operation: .set, value: 1800),
                Command(controlID: 12, operation: .set, value: 1800)
            ]
        case .winterMorning:
            return [
                Command(controlID: 1, operation: .set, value: 1),
                Command(controlID: 3, operation: .set, value: 1),
                Command(controlID: 11, operation: .set, value: 2300),
                Command(controlID: 12, operation: .set, value: 2300),
                Command(controlID: 6, operation: .set, value: 1),
                Command(controlID: 20, operation: .set, value: Int32(0x10050203)),
                Command(controlID: 24, operation: .set, value: Int32(0x10090103))
            ]
        case .comfort:
            return [
                Command(controlID: 1, operation: .set, value: 1),
                Command(controlID: 3, operation: .set, value: 1),
                Command(controlID: 11, operation: .set, value: 2200),
                Command(controlID: 12, operation: .set, value: 2200),
                Command(controlID: 40, operation: .set, value: 1),
                Command(controlID: 41, operation: .set, value: 5000)
            ]
        case .allOff:
            return [20, 21, 22, 23, 24, 25, 26, 27, 28, 4, 5, 6, 2, 1].map {
                Command(controlID: UInt8($0), operation: .set, value: 0)
            }
        }
    }
}
