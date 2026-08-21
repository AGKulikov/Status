import ActivityKit
import UIKit

final class LiveActivitySettingsViewController: UITableViewController {
    private enum Section: Int, CaseIterable {
        case behavior
        case appearance
        case controls
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

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(
        _ tableView: UITableView,
        numberOfRowsInSection section: Int
    ) -> Int {
        switch Section(rawValue: section)! {
        case .behavior: return 4
        case .appearance: return 2
        case .controls: return 4
        case .actions: return 2
        case .shortcuts: return 2
        }
    }

    override func tableView(
        _ tableView: UITableView,
        titleForHeaderInSection section: Int
    ) -> String? {
        switch Section(rawValue: section)! {
        case .behavior: return "Поведение"
        case .appearance: return "Автомобиль"
        case .controls: return "Четыре кнопки"
        case .actions: return "Управление"
        case .shortcuts: return "Команды Apple"
        }
    }

    override func tableView(
        _ tableView: UITableView,
        titleForFooterInSection section: Int
    ) -> String? {
        switch Section(rawValue: section)! {
        case .behavior:
            return "Демо запускается сразу, подставляет 19°/7°/22° и позволяет нажимать кнопки без автомобиля; действия ANCS в Командах при этом по-прежнему возвращают только реальное состояние. Helper запускает обычную карточку при открытии и подтверждённом ANCS. Первый полностью фоновый запуск iOS разрешает только через APNs push-to-start."
        case .controls:
            return "Повторный выбор меняет кнопки местами. Команды передаются по тому же защищённому Bluetooth-каналу C5."
        case .shortcuts:
            return "iOS 26 не даёт приложениям добавлять собственный системный триггер автоматизации. Используйте штатный триггер Bluetooth автомобиля, затем «Ожидать подключения ANCS» и условие по результату."
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
                    title: "Автозапуск",
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
                    title: "Демо без автомобиля",
                    isOn: NatroLiveActivityPreferences.demoMode,
                    action: #selector(demoModeChanged(_:))
                )
            }
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
            cell.textLabel?.text = NatroLiveActivityManager.shared.isRunning
                ? "Карточка активна" : "Карточка не запущена"
            cell.detailTextLabel?.text = NatroLiveActivityManager.shared.statusText
            cell.detailTextLabel?.numberOfLines = 0
            cell.imageView?.image = UIImage(systemName: NatroLiveActivityManager.shared.isRunning
                ? "checkmark.circle.fill" : "circle")
            cell.imageView?.tintColor = NatroLiveActivityManager.shared.isRunning
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
                    cell.detailTextLabel?.text = "ДЕМО (реальный ANCS не подменяется)"
                    cell.detailTextLabel?.textColor = .systemOrange
                } else {
                    cell.detailTextLabel?.text = NatroLiveActivityManager.shared.isANCSConnected
                        ? "Подключён" : "Не подключён"
                    cell.detailTextLabel?.textColor = NatroLiveActivityManager.shared.isANCSConnected
                        ? .systemGreen : .secondaryLabel
                }
                cell.selectionStyle = .none
            }
            return cell
        case .controls:
            let control = NatroLiveActivityPreferences.controls[indexPath.row]
            let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
            cell.textLabel?.text = "Кнопка \(indexPath.row + 1)"
            cell.detailTextLabel?.text = control.title
            cell.imageView?.image = UIImage(systemName: control.systemImage)
            cell.imageView?.tintColor = .systemBlue
            cell.accessoryType = .disclosureIndicator
            return cell
        case .actions:
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            if indexPath.row == 0 {
                cell.textLabel?.text = "Запустить сейчас"
                cell.textLabel?.textColor = .systemBlue
                cell.imageView?.image = UIImage(systemName: "play.circle.fill")
            } else {
                cell.textLabel?.text = "Остановить"
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
        case .controls:
            selectControl(slot: indexPath.row, source: tableView.cellForRow(at: indexPath))
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

    private func switchCell(
        title: String,
        isOn: Bool,
        action: Selector
    ) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.text = title
        let control = UISwitch()
        control.isOn = isOn
        control.addTarget(self, action: action, for: .valueChanged)
        cell.accessoryView = control
        cell.selectionStyle = .none
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

    private func selectControl(slot: Int, source: UIView?) {
        let sheet = UIAlertController(
            title: "Кнопка \(slot + 1)",
            message: nil,
            preferredStyle: .actionSheet
        )
        for control in NatroLiveControl.allCases {
            sheet.addAction(UIAlertAction(title: control.title, style: .default) { _ in
                NatroLiveActivityPreferences.setControl(control, at: slot)
            })
        }
        sheet.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        sheet.popoverPresentationController?.sourceView = source ?? view
        sheet.popoverPresentationController?.sourceRect = source?.bounds ?? view.bounds
        present(sheet, animated: true)
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
