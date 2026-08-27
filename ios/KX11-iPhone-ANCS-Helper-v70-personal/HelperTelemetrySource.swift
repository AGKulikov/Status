import CoreTelephony
import Foundation
import Network
import UIKit

public struct HelperTelemetrySample: Equatable {
    public let batteryPercent: UInt8?
    public let externalPower: Bool
    public let chargeState: IphoneBleWireProtocolV2.ChargeState
    public let network: IphoneBleWireProtocolV2.Network
    public let locked: Bool
}

/// Public-API iPhone status producer. It contains no BLE ownership; the top switch runtime assigns
/// a per-generation sequence and routes samples only to the exact ACTIVE coordinator.
public final class HelperTelemetrySource: NSObject, CTTelephonyNetworkInfoDelegate {
    public var onSample: ((HelperTelemetrySample) -> Void)?

    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "ru.natro.kx11ancshelper.v70.telemetry.path")
    private let telephony = CTTelephonyNetworkInfo()
    private var network: IphoneBleWireProtocolV2.Network = .unknown
    private var lastLoggedNetwork: IphoneBleWireProtocolV2.Network?
    private var started = false
    private var dataServiceRefreshGeneration: UInt = 0

    public override init() {
        super.init()
    }

    public func start() {
        precondition(Thread.isMainThread, "telemetry source is main-thread owned")
        guard !started else { return }
        started = true
        telephony.delegate = self
        UIDevice.current.isBatteryMonitoringEnabled = true
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(systemStatusChanged),
            name: UIDevice.batteryLevelDidChangeNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(systemStatusChanged),
            name: UIDevice.batteryStateDidChangeNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(systemStatusChanged),
            name: UIApplication.protectedDataDidBecomeAvailableNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(systemStatusChanged),
            name: UIApplication.protectedDataWillBecomeUnavailableNotification,
            object: nil
        )

        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let telephony = self?.telephony else { return }
            let next = Self.classify(path: path, telephony: telephony)
            DispatchQueue.main.async { [weak self] in
                guard let self, self.started else { return }
                self.applyNetwork(next, reason: "NWPath")
            }
        }
        pathMonitor.start(queue: pathQueue)
        refreshNetworkAndEmit()
    }

    /// Emits one current sample for an authenticated Android R request. There is deliberately no
    /// periodic timer: battery, power, lock and path changes are event-driven, and Android asks for
    /// one refresh only after its own 30-second ANCS/GATT quiet window.
    public func requestFreshSample() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.requestFreshSample() }
            return
        }
        guard started else { return }
        // Re-read currentPath and the active data-service identifier together so a data-SIM switch
        // cannot reuse the radio class of the previously selected line.
        refreshNetworkAndEmit()
    }

    public func stop() {
        precondition(Thread.isMainThread, "telemetry source is main-thread owned")
        guard started else { return }
        started = false
        dataServiceRefreshGeneration &+= 1
        telephony.delegate = nil
        pathMonitor.cancel()
        NotificationCenter.default.removeObserver(self)
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    deinit {
        telephony.delegate = nil
        pathMonitor.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    /// CoreTelephony sends this callback when the line that owns packet data changes. NWPath may
    /// remain merely "cellular" across that switch and therefore does not have to emit a new path.
    public func dataServiceIdentifierDidChange(_ identifier: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.started else { return }
            ANCSConnectionJournal.shared.append(
                "telemetry",
                "активная SIM для мобильных данных изменилась; перепроверяем radio class"
            )
            self.scheduleDataServiceRefreshes()
        }
    }

    @objc private func systemStatusChanged() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.emitSample() }
            return
        }
        emitSample()
    }

    private func scheduleDataServiceRefreshes() {
        precondition(Thread.isMainThread, "data-service refresh is main-thread owned")
        dataServiceRefreshGeneration &+= 1
        let generation = dataServiceRefreshGeneration
        // CoreTelephony can publish the new data-service identifier just before its per-service
        // radio dictionary. A bounded settling sequence avoids retaining the old SIM until the
        // process is restarted while keeping telemetry event-driven.
        for delay in [0.0, 0.25, 1.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, self.started,
                      self.dataServiceRefreshGeneration == generation else { return }
                self.refreshNetworkAndEmit()
            }
        }
    }

    private func refreshNetworkAndEmit() {
        precondition(Thread.isMainThread, "telemetry source is main-thread owned")
        let next = Self.classify(path: pathMonitor.currentPath, telephony: telephony)
        applyNetwork(next, reason: "CoreTelephony")
    }

    private func applyNetwork(
        _ next: IphoneBleWireProtocolV2.Network,
        reason: String
    ) {
        precondition(Thread.isMainThread, "telemetry source is main-thread owned")
        network = next
        if lastLoggedNetwork != next {
            lastLoggedNetwork = next
            ANCSConnectionJournal.shared.append(
                "telemetry",
                "network=\(Self.networkLabel(next)); source=\(reason); "
                    + "activeDataService=\(telephony.dataServiceIdentifier == nil ? "missing" : "present"); "
                    + "serviceCount=\(telephony.serviceCurrentRadioAccessTechnology?.count ?? 0)"
            )
        }
        emitSample()
    }

    private static func networkLabel(_ value: IphoneBleWireProtocolV2.Network) -> String {
        switch value {
        case .unknown: return "unknown"
        case .offline: return "offline"
        case .wifi: return "wifi"
        case .lte: return "lte"
        case .nr5G: return "5g"
        case .cellular3G: return "3g"
        case .cellular2G: return "2g"
        }
    }

    private func emitSample() {
        guard started, Thread.isMainThread else { return }
        let level = UIDevice.current.batteryLevel
        let percent: UInt8?
        if level < 0 {
            percent = nil
        } else {
            percent = UInt8(max(0, min(100, Int((level * 100).rounded()))))
        }
        let batteryState = UIDevice.current.batteryState
        let chargeState: IphoneBleWireProtocolV2.ChargeState
        switch batteryState {
        case .unplugged:
            chargeState = .discharging
        case .charging:
            chargeState = .charging
        case .full:
            chargeState = .full
        case .unknown:
            chargeState = .unknown
        @unknown default:
            chargeState = .unknown
        }
        onSample?(HelperTelemetrySample(
            batteryPercent: percent,
            externalPower: batteryState == .charging || batteryState == .full,
            chargeState: chargeState,
            network: network,
            locked: !UIApplication.shared.isProtectedDataAvailable
        ))
    }

    private static func classify(
        path: NWPath,
        telephony: CTTelephonyNetworkInfo
    ) -> IphoneBleWireProtocolV2.Network {
        guard path.status == .satisfied else { return .offline }
        if path.usesInterfaceType(.wifi) { return .wifi }
        guard path.usesInterfaceType(.cellular) else { return .unknown }

        let byService = telephony.serviceCurrentRadioAccessTechnology ?? [:]
        let activeTechnology: String?
        if let activeIdentifier = telephony.dataServiceIdentifier {
            // Apple's dataServiceIdentifier is the only public key that tells which SIM currently
            // provides packet data. Never aggregate radio technologies from inactive lines.
            activeTechnology = byService[activeIdentifier]
        } else if byService.count == 1 {
            // Single-SIM devices can transiently omit the identifier; one entry is unambiguous.
            activeTechnology = byService.values.first
        } else {
            activeTechnology = nil
        }
        guard let technology = activeTechnology else { return .unknown }

        if #available(iOS 14.1, *),
           technology == CTRadioAccessTechnologyNRNSA ||
                technology == CTRadioAccessTechnologyNR {
            return .nr5G
        }
        if technology == CTRadioAccessTechnologyLTE { return .lte }
        if technology == CTRadioAccessTechnologyWCDMA ||
            technology == CTRadioAccessTechnologyHSDPA ||
            technology == CTRadioAccessTechnologyHSUPA ||
            technology == "CTRadioAccessTechnologyCDMA1xEVDORev0" ||
            technology == "CTRadioAccessTechnologyCDMA1xEVDORevA" ||
            technology == "CTRadioAccessTechnologyCDMA1xEVDORevB" ||
            technology == "CTRadioAccessTechnologyeHRPD" {
            return .cellular3G
        }
        if technology == CTRadioAccessTechnologyGPRS ||
            technology == CTRadioAccessTechnologyEdge ||
            technology == "CTRadioAccessTechnologyCDMA1x" {
            return .cellular2G
        }
        return .unknown
    }
}
