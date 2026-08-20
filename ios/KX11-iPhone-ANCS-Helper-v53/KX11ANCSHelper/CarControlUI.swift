import UIKit

/// Main Helper surface: vehicle controls first, Bluetooth/enrollment details in Settings.
final class ViewController: UITabBarController {
    private let settingsController = HelperSettingsViewController()
    private var controlControllers: [CarControlSectionViewController] = []

    override func viewDidLoad() {
        super.viewDidLoad()

        // AppDelegate deliberately forces this root view during launch for Core Bluetooth
        // restoration. Load the settings owner now even when its tab is not visible.
        _ = settingsController.view
        let remote = settingsController.carRemoteClient
        let sections: [(CarRemoteControlDefinition.Section, String, String)] = [
            (.climate, "Климат", "thermometer"),
            (.seats, "Сиденья", "car"),
            (.media, "Медиа", "play.circle"),
            (.comfort, "Комфорт", "sparkles")
        ]
        controlControllers = sections.map { section, title, symbol in
            let controller = CarControlSectionViewController(section: section, remote: remote)
            controller.title = title
            controller.tabBarItem = UITabBarItem(
                title: title,
                image: UIImage(systemName: symbol),
                selectedImage: UIImage(systemName: "\(symbol).fill")
                    ?? UIImage(systemName: symbol)
            )
            return controller
        }
        settingsController.title = "Настройки"
        settingsController.tabBarItem = UITabBarItem(
            title: "Настройки",
            image: UIImage(systemName: "gearshape"),
            selectedImage: UIImage(systemName: "gearshape.fill")
        )

        remote.onChange = { [weak self] in
            self?.controlControllers.forEach { $0.reloadRemoteState() }
        }
        viewControllers = (controlControllers.map { navigationController(for: $0) }
            + [navigationController(for: settingsController)])
        selectedIndex = 0
    }

    private func navigationController(for root: UIViewController) -> UINavigationController {
        let navigation = UINavigationController(rootViewController: root)
        navigation.navigationBar.prefersLargeTitles = true
        return navigation
    }
}

private final class CarControlSectionViewController: UITableViewController {
    private let section: CarRemoteControlDefinition.Section
    private let remote: CarRemoteClient
    private let controls: [CarRemoteControlDefinition]
    private let statusLabel = UILabel()

    init(section: CarRemoteControlDefinition.Section, remote: CarRemoteClient) {
        self.section = section
        self.remote = remote
        controls = CarRemoteCatalogV1.controls.filter { $0.section == section }
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 76
        tableView.allowsSelection = section == .comfort
        configureStatusHeader()
        reloadRemoteState()
    }

    func reloadRemoteState() {
        guard isViewLoaded else { return }
        statusLabel.text = remote.statusText
        statusLabel.textColor = remote.isSynced ? .systemGreen : .secondaryLabel
        tableView.reloadData()
    }

    private func configureStatusHeader() {
        let header = UIView(frame: CGRect(x: 0, y: 0, width: 1, height: 58))
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.numberOfLines = 2
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: header.leadingAnchor, constant: 20),
            statusLabel.trailingAnchor.constraint(equalTo: header.trailingAnchor, constant: -20),
            statusLabel.topAnchor.constraint(equalTo: header.topAnchor, constant: 8),
            statusLabel.bottomAnchor.constraint(equalTo: header.bottomAnchor, constant: -8)
        ])
        tableView.tableHeaderView = header
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        section == .comfort ? 2 : 1
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection index: Int) -> String? {
        if section == .comfort && index == 0 { return "Сценарии" }
        return section.rawValue
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection index: Int) -> Int {
        if section == .comfort && index == 0 { return Scene.allCases.count }
        return controls.count
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        if section == .comfort && indexPath.section == 0 {
            let scene = Scene.allCases[indexPath.row]
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
            cell.textLabel?.text = scene.title
            cell.detailTextLabel?.text = scene.detail
            cell.detailTextLabel?.numberOfLines = 2
            cell.accessoryType = .disclosureIndicator
            cell.selectionStyle = remote.isSynced ? .default : .none
            cell.textLabel?.isEnabled = remote.isSynced
            cell.detailTextLabel?.isEnabled = remote.isSynced
            return cell
        }

        let definition = controls[indexPath.row]
        let cell = RemoteControlCell()
        let state = remote.states[definition.id]
        let modeReady = definition.id != 9 || remote.states[3]?.known == true
        let enabled = remote.isSynced && modeReady && remote.available.contains(definition.id)
            && (state?.available ?? true)
        cell.titleLabel.text = definition.title
        cell.detailLabel.text = detail(for: definition, state: state, enabled: enabled)
        cell.titleLabel.isEnabled = enabled
        cell.detailLabel.isEnabled = enabled

        switch definition.kind {
        case .toggle:
            let control = RemoteSwitch()
            control.controlID = definition.id
            control.isOn = state?.known == true ? state?.active == true || state?.value != 0 : false
            control.isEnabled = enabled
            control.addTarget(self, action: #selector(toggleChanged(_:)), for: .valueChanged)
            cell.installAccessory(control)
        case .levels:
            let button = RemoteButton(type: .system)
            button.controlID = definition.id
            button.setTitle(state?.known == true
                ? definition.displayValue(state?.value ?? 0) : "Выбрать", for: .normal)
            button.isEnabled = enabled
            button.addTarget(self, action: #selector(selectValue(_:)), for: .touchUpInside)
            cell.installAccessory(button)
        case .options:
            let button = RemoteButton(type: .system)
            button.controlID = definition.id
            button.setTitle("Следующий", for: .normal)
            button.isEnabled = enabled
            button.addTarget(self, action: #selector(cycleValue(_:)), for: .touchUpInside)
            cell.installAccessory(button)
        case .range:
            let slider = RemoteSlider()
            slider.controlID = definition.id
            slider.definition = definition
            slider.minimumValue = Float(definition.minimum)
            slider.maximumValue = Float(definition.maximum)
            slider.value = Float(state?.known == true ? state?.value ?? definition.minimum
                : definition.minimum)
            slider.pendingValue = Int32(slider.value.rounded())
            slider.isEnabled = enabled
            slider.valueLabel = cell.detailLabel
            slider.addTarget(self, action: #selector(sliderChanged(_:)), for: .valueChanged)
            slider.addTarget(self, action: #selector(sliderCommitted(_:)),
                             for: [.touchUpInside, .touchUpOutside, .touchCancel])
            cell.installAccessory(slider)
        case .action:
            let button = RemoteButton(type: .system)
            button.controlID = definition.id
            button.setTitle("Выполнить", for: .normal)
            button.isEnabled = enabled
            button.addTarget(self, action: #selector(activate(_:)), for: .touchUpInside)
            cell.installAccessory(button)
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard section == .comfort, indexPath.section == 0, remote.isSynced else { return }
        let scene = Scene.allCases[indexPath.row]
        remote.runScene(scene.commands)
    }

    private func detail(
        for definition: CarRemoteControlDefinition,
        state: CarRemoteClient.State?,
        enabled: Bool
    ) -> String {
        if !remote.isSynced { return "Ожидание Bluetooth" }
        if !remote.available.contains(definition.id) || state?.available == false {
            return "Недоступно в этой комплектации"
        }
        if definition.id == 9 && remote.states[3]?.known != true {
            return "Ожидание режима AUTO"
        }
        if !enabled { return "Недоступно в этой комплектации" }
        guard let state, state.known else { return "Состояние синхронизируется" }
        if definition.kind == .toggle { return state.active ? "Включено" : "Выключено" }
        if definition.kind == .action { return "Команда без постоянного состояния" }
        return definition.displayValue(state.value)
    }

    @objc private func toggleChanged(_ control: RemoteSwitch) {
        guard let definition = CarRemoteCatalogV1.byID[control.controlID] else { return }
        let value = control.isOn ? max(1, definition.maximum) : 0
        if definition.requiresConfirmation {
            let requested = control.isOn
            let old = remote.states[definition.id]?.active == true
            control.setOn(old, animated: true)
            confirmMechanical(definition) { [weak self] in
                self?.remote.send(controlID: definition.id, operation: .set,
                                  value: requested ? value : 0, confirmed: true)
            }
        } else {
            remote.send(controlID: definition.id, operation: .set, value: value)
        }
    }

    @objc private func selectValue(_ button: RemoteButton) {
        guard let definition = CarRemoteCatalogV1.byID[button.controlID] else { return }
        let sheet = UIAlertController(title: definition.title, message: nil, preferredStyle: .actionSheet)
        for (value, title) in selectableValues(for: definition) {
            sheet.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                self?.remote.send(controlID: definition.id, operation: .set, value: value)
            })
        }
        sheet.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        present(sheet, animated: true)
    }

    private func selectableValues(
        for definition: CarRemoteControlDefinition
    ) -> [(Int32, String)] {
        guard definition.id == 9 else { return definition.directValues }
        let auto = remote.states[3]?.active == true
        return definition.directValues.filter { value, _ in
            auto ? value >> 8 == Int32(0x100202)
                : value == 0 || value >> 8 == Int32(0x100201)
        }
    }

    @objc private func cycleValue(_ button: RemoteButton) {
        remote.send(controlID: button.controlID, operation: .cycle, value: 0)
    }

    @objc private func sliderChanged(_ slider: RemoteSlider) {
        guard let definition = slider.definition else { return }
        let step = max(1, definition.step)
        let rounded = Int32((Double(slider.value) / Double(step)).rounded()) * step
        slider.pendingValue = min(definition.maximum, max(definition.minimum, rounded))
        slider.value = Float(slider.pendingValue)
        slider.valueLabel?.text = definition.displayValue(slider.pendingValue)
    }

    @objc private func sliderCommitted(_ slider: RemoteSlider) {
        remote.send(controlID: slider.controlID, operation: .set, value: slider.pendingValue)
    }

    @objc private func activate(_ button: RemoteButton) {
        guard let definition = CarRemoteCatalogV1.byID[button.controlID] else { return }
        let execute = { [weak self] in
            self?.remote.send(controlID: definition.id, operation: .activate,
                              value: 1, confirmed: definition.requiresConfirmation)
        }
        if definition.requiresConfirmation {
            confirmMechanical(definition, execute: execute)
        } else {
            execute()
        }
    }

    private func confirmMechanical(
        _ definition: CarRemoteControlDefinition,
        execute: @escaping () -> Void
    ) {
        let alert = UIAlertController(
            title: definition.title,
            message: "Убедитесь, что рядом с автомобилем нет людей и препятствий.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Выполнить", style: .destructive) { _ in execute() })
        present(alert, animated: true)
    }
}

private final class RemoteControlCell: UITableViewCell {
    let titleLabel = UILabel()
    let detailLabel = UILabel()
    private let labels = UIStackView()
    private var installedAccessory: UIView?

    init() {
        super.init(style: .default, reuseIdentifier: nil)
        selectionStyle = .none
        titleLabel.font = .preferredFont(forTextStyle: .body)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.numberOfLines = 2
        detailLabel.font = .preferredFont(forTextStyle: .caption1)
        detailLabel.textColor = .secondaryLabel
        detailLabel.adjustsFontForContentSizeCategory = true
        detailLabel.numberOfLines = 2
        labels.axis = .vertical
        labels.spacing = 3
        labels.addArrangedSubview(titleLabel)
        labels.addArrangedSubview(detailLabel)
        labels.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(labels)
        NSLayoutConstraint.activate([
            labels.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            labels.topAnchor.constraint(greaterThanOrEqualTo: contentView.topAnchor, constant: 12),
            labels.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -12),
            labels.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            contentView.heightAnchor.constraint(greaterThanOrEqualToConstant: 68)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func installAccessory(_ accessory: UIView) {
        installedAccessory?.removeFromSuperview()
        installedAccessory = accessory
        accessory.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(accessory)
        let width = accessory is UISlider ? CGFloat(142) : CGFloat(104)
        NSLayoutConstraint.activate([
            accessory.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            accessory.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            accessory.widthAnchor.constraint(lessThanOrEqualToConstant: width),
            labels.trailingAnchor.constraint(lessThanOrEqualTo: accessory.leadingAnchor, constant: -12)
        ])
    }
}

private final class RemoteSwitch: UISwitch { var controlID: UInt8 = 0 }

private final class RemoteButton: UIButton { var controlID: UInt8 = 0 }

private final class RemoteSlider: UISlider {
    var controlID: UInt8 = 0
    var definition: CarRemoteControlDefinition?
    var pendingValue: Int32 = 0
    weak var valueLabel: UILabel?
}

private enum Scene: CaseIterable {
    case coolDown, winterMorning, comfort, allOff

    var title: String {
        switch self {
        case .coolDown: return "Быстро охладить"
        case .winterMorning: return "Зимнее утро"
        case .comfort: return "Комфортная поездка"
        case .allOff: return "Всё выключить"
        }
    }

    var detail: String {
        switch self {
        case .coolDown: return "A/C, сильный обдув и 18 °C"
        case .winterMorning: return "23 °C, стекло, сиденье и руль"
        case .comfort: return "AUTO 22 °C и мягкая подсветка"
        case .allOff: return "Климат, обогревы и вентиляция"
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
            return [
                Command(controlID: 20, operation: .set, value: 0),
                Command(controlID: 21, operation: .set, value: 0),
                Command(controlID: 22, operation: .set, value: 0),
                Command(controlID: 23, operation: .set, value: 0),
                Command(controlID: 24, operation: .set, value: 0),
                Command(controlID: 4, operation: .set, value: 0),
                Command(controlID: 5, operation: .set, value: 0),
                Command(controlID: 6, operation: .set, value: 0),
                Command(controlID: 2, operation: .set, value: 0),
                Command(controlID: 1, operation: .set, value: 0)
            ]
        }
    }
}
