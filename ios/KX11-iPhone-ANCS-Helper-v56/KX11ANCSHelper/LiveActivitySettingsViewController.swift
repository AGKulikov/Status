import ActivityKit
import UIKit

private let liveActivityCyan = UIColor(red: 0.16, green: 0.70, blue: 0.96, alpha: 1)

final class LiveActivitySettingsViewController: UITableViewController {
    private enum Section: Int, CaseIterable {
        case behavior
        case appearance
        case climateControls
        case functionControls
        case actions
        case shortcuts
    }

    init() { super.init(style: .insetGrouped) }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Live Activity"
        navigationItem.largeTitleDisplayMode = .never
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 56
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(reloadStatus),
            name: NatroLiveActivityManager.statusChanged,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(reloadStatus),
            name: NatroLiveActivityPreferences.changed,
            object: nil
        )
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    override func numberOfSections(in tableView: UITableView) -> Int { Section.allCases.count }

    override func tableView(
        _ tableView: UITableView,
        numberOfRowsInSection section: Int
    ) -> Int {
        switch Section(rawValue: section)! {
        case .behavior: return 4
        case .appearance: return 2
        case .climateControls: return 4
        case .functionControls: return 10
        case .actions, .shortcuts: return 2
        }
    }

    override func tableView(
        _ tableView: UITableView,
        titleForHeaderInSection section: Int
    ) -> String? {
        switch Section(rawValue: section)! {
        case .behavior: return "Поведение"
        case .appearance: return "Автомобиль"
        case .climateControls: return "Верхняя Activity · климат"
        case .functionControls: return "Нижняя Activity · функции"
        case .actions: return "Обе карточки"
        case .shortcuts: return "Команды Apple"
        }
    }

    override func tableView(
        _ tableView: UITableView,
        titleForFooterInSection section: Int
    ) -> String? {
        switch Section(rawValue: section)! {
        case .behavior:
            return "Демо общее для экрана управления и обеих Live Activity. Оно не подменяет реальный статус ANCS, который получают Команды. Первый локальный запуск iOS разрешает только в foreground; затем карточки обновляются от BLE в фоне."
        case .climateControls:
            return "Четыре быстрые функции под изображением автомобиля и температурой."
        case .functionControls:
            return "Десять кнопок в два ряда. Уровни подогрева и вентиляции показаны тремя сегментами; Auto отмечается буквой A."
        case .shortcuts:
            return "Системный пользовательский триггер iOS создать нельзя. Действия Helper возвращают точное состояние ANCS, поэтому их можно использовать после штатного Bluetooth-триггера автомобиля."
        default:
            return nil
        }
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        guard #available(iOS 16.2, *) else {
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
            cell.textLabel?.text = "Live Activity недоступна"
            cell.detailTextLabel?.text = "Требуется iOS 16.2 или новее"
            cell.selectionStyle = .none
            return cell
        }
        switch Section(rawValue: indexPath.section)! {
        case .behavior:
            if indexPath.row == 0 {
                return switchCell(
                    title: "Автозапуск двух карточек",
                    isOn: NatroLiveActivityPreferences.automaticStart,
                    action: #selector(automaticStartChanged(_:))
                )
            }
            if indexPath.row == 1 {
                return switchCell(
                    title: "Показывать автомобиль",
                    isOn: NatroLiveActivityPreferences.showVehicle,
                    action: #selector(showVehicleChanged(_:))
                )
            }
            if indexPath.row == 2 {
                return switchCell(
                    title: "Общий демо-режим",
                    isOn: NatroLiveActivityPreferences.demoMode,
                    action: #selector(demoModeChanged(_:))
                )
            }
            let manager = NatroLiveActivityManager.shared
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
            cell.textLabel?.text = "Активно карточек: \(manager.runningCount) из 2"
            cell.detailTextLabel?.text = manager.statusText
            cell.detailTextLabel?.numberOfLines = 0
            cell.imageView?.image = UIImage(systemName: manager.runningCount == 2
                ? "checkmark.circle.fill" : "circle.dashed")
            cell.imageView?.tintColor = manager.runningCount == 2
                ? .systemGreen : .secondaryLabel
            cell.selectionStyle = .none
            return cell
        case .appearance:
            let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
            if indexPath.row == 0 {
                cell.textLabel?.text = "Название"
                cell.detailTextLabel?.text = NatroLiveActivityPreferences.vehicleName
                cell.accessoryType = .disclosureIndicator
            } else {
                cell.textLabel?.text = "Статус ANCS"
                if NatroLiveActivityPreferences.demoMode {
                    cell.detailTextLabel?.text = "ДЕМО · реальный не подменён"
                    cell.detailTextLabel?.textColor = .systemOrange
                } else {
                    let connected = NatroLiveActivityManager.shared.isANCSConnected
                    cell.detailTextLabel?.text = connected ? "Подключён" : "Не подключён"
                    cell.detailTextLabel?.textColor = connected ? .systemGreen : .secondaryLabel
                }
                cell.selectionStyle = .none
            }
            return cell
        case .climateControls:
            return controlCell(
                slot: indexPath.row,
                control: NatroLiveActivityPreferences.climateControls[indexPath.row]
            )
        case .functionControls:
            return controlCell(
                slot: indexPath.row,
                control: NatroLiveActivityPreferences.functionControls[indexPath.row]
            )
        case .actions:
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            if indexPath.row == 0 {
                cell.textLabel?.text = "Запустить обе сейчас"
                cell.textLabel?.textColor = .systemBlue
                cell.imageView?.image = UIImage(systemName: "play.circle.fill")
            } else {
                cell.textLabel?.text = "Остановить обе"
                cell.textLabel?.textColor = .systemRed
                cell.imageView?.image = UIImage(systemName: "stop.circle.fill")
            }
            return cell
        case .shortcuts:
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
            if indexPath.row == 0 {
                cell.textLabel?.text = "Получить состояние ANCS"
                cell.detailTextLabel?.text = "Мгновенно возвращает Да / Нет"
                cell.imageView?.image = UIImage(systemName: "antenna.radiowaves.left.and.right")
            } else {
                cell.textLabel?.text = "Ожидать подключения ANCS"
                cell.detailTextLabel?.text = "Ждёт до 25 секунд и возвращает результат"
                cell.imageView?.image = UIImage(systemName: "hourglass")
            }
            cell.detailTextLabel?.numberOfLines = 0
            cell.selectionStyle = .none
            return cell
        }
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard #available(iOS 16.2, *) else { return }
        switch Section(rawValue: indexPath.section)! {
        case .appearance where indexPath.row == 0:
            editVehicleName()
        case .climateControls:
            showPicker(slot: indexPath.row, climateOnly: true)
        case .functionControls:
            showPicker(slot: indexPath.row, climateOnly: false)
        case .actions where indexPath.row == 0:
            _ = NatroLiveActivityManager.shared.ensureRunning(
                reason: "ручной запуск",
                force: true
            )
        case .actions where indexPath.row == 1:
            NatroLiveActivityManager.shared.stop()
        default:
            break
        }
    }

    private func switchCell(title: String, isOn: Bool, action: Selector) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.text = title
        let control = UISwitch()
        control.isOn = isOn
        control.addTarget(self, action: action, for: .valueChanged)
        cell.accessoryView = control
        cell.selectionStyle = .none
        return cell
    }

    private func controlCell(slot: Int, control: NatroLiveControl) -> UITableViewCell {
        let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
        cell.textLabel?.text = "Кнопка \(slot + 1)"
        cell.detailTextLabel?.text = control.title
        cell.imageView?.image = UIImage(systemName: control.systemImage)
        cell.imageView?.tintColor = control.isVentilation ? liveActivityCyan : .systemOrange
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    private func editVehicleName() {
        let alert = UIAlertController(
            title: "Название автомобиля",
            message: "До 32 символов",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.text = NatroLiveActivityPreferences.vehicleName
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Сохранить", style: .default) { _ in
            NatroLiveActivityPreferences.vehicleName = alert.textFields?.first?.text ?? ""
        })
        present(alert, animated: true)
    }

    private func showPicker(slot: Int, climateOnly: Bool) {
        let picker = LiveControlPickerViewController(
            selected: climateOnly
                ? NatroLiveActivityPreferences.climateControls[slot]
                : NatroLiveActivityPreferences.functionControls[slot],
            candidates: climateOnly
                ? NatroLiveControl.climateCandidates
                : NatroLiveControl.functionCandidates
        ) { control in
            if climateOnly {
                NatroLiveActivityPreferences.setClimateControl(control, at: slot)
            } else {
                NatroLiveActivityPreferences.setFunctionControl(control, at: slot)
            }
        }
        navigationController?.pushViewController(picker, animated: true)
    }

    @objc private func automaticStartChanged(_ control: UISwitch) {
        NatroLiveActivityPreferences.automaticStart = control.isOn
    }

    @objc private func showVehicleChanged(_ control: UISwitch) {
        NatroLiveActivityPreferences.showVehicle = control.isOn
    }

    @objc private func demoModeChanged(_ control: UISwitch) {
        NatroLiveActivityPreferences.demoMode = control.isOn
    }

    @objc private func reloadStatus() { tableView.reloadData() }
}

private final class LiveControlPickerViewController: UITableViewController {
    private let selected: NatroLiveControl
    private let groups: [(NatroLiveControlSection, [NatroLiveControl])]
    private let selection: (NatroLiveControl) -> Void

    init(
        selected: NatroLiveControl,
        candidates: [NatroLiveControl],
        selection: @escaping (NatroLiveControl) -> Void
    ) {
        self.selected = selected
        self.selection = selection
        self.groups = NatroLiveControlSection.allCases.compactMap { section in
            let controls = candidates.filter { $0.section == section }
            return controls.isEmpty ? nil : (section, controls)
        }
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Выберите функцию"
        navigationItem.largeTitleDisplayMode = .never
    }

    override func numberOfSections(in tableView: UITableView) -> Int { groups.count }

    override func tableView(
        _ tableView: UITableView,
        numberOfRowsInSection section: Int
    ) -> Int { groups[section].1.count }

    override func tableView(
        _ tableView: UITableView,
        titleForHeaderInSection section: Int
    ) -> String? { groups[section].0.rawValue }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        let control = groups[indexPath.section].1[indexPath.row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.text = control.settingsTitle
        cell.detailTextLabel?.text = control.isThreeStage ? "Выкл · 1 · 2 · 3 · Auto" : nil
        cell.imageView?.image = UIImage(systemName: control.systemImage)
        cell.imageView?.tintColor = control.isVentilation ? liveActivityCyan : .systemOrange
        cell.accessoryType = control == selected ? .checkmark : .none
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let control = groups[indexPath.section].1[indexPath.row]
        selection(control)
        navigationController?.popViewController(animated: true)
    }
}
