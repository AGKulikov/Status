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
public final class HelperTelemetrySource {
    public var onSample: ((HelperTelemetrySample) -> Void)?

    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "ru.natro.kx11ancshelper.v48.telemetry.path")
    private let telephony = CTTelephonyNetworkInfo()
    private var network: IphoneBleWireProtocolV2.Network = .unknown
    private var timer: Timer?
    private var started = false

    public init() {}

    public func start() {
        precondition(Thread.isMainThread, "telemetry source is main-thread owned")
        guard !started else { return }
        started = true
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
            let next = Self.classify(path: path, telephony: self?.telephony)
            DispatchQueue.main.async { [weak self] in
                guard let self, self.started else { return }
                self.network = next
                self.emitSample()
            }
        }
        pathMonitor.start(queue: pathQueue)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.emitSample()
        }
        emitSample()
    }

    public func stop() {
        precondition(Thread.isMainThread, "telemetry source is main-thread owned")
        guard started else { return }
        started = false
        timer?.invalidate()
        timer = nil
        pathMonitor.cancel()
        NotificationCenter.default.removeObserver(self)
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    deinit {
        timer?.invalidate()
        pathMonitor.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func systemStatusChanged() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.emitSample() }
            return
        }
        emitSample()
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
        telephony: CTTelephonyNetworkInfo?
    ) -> IphoneBleWireProtocolV2.Network {
        guard path.status == .satisfied else { return .offline }
        if path.usesInterfaceType(.wifi) { return .wifi }
        guard path.usesInterfaceType(.cellular) else { return .unknown }
        let technologies: [String]
        if #available(iOS 12.0, *),
           let byService = telephony?.serviceCurrentRadioAccessTechnology {
            technologies = Array(byService.values)
        } else if let value = telephony?.currentRadioAccessTechnology {
            technologies = [value]
        } else {
            technologies = []
        }
        if #available(iOS 14.1, *), technologies.contains(where: {
            $0 == CTRadioAccessTechnologyNRNSA || $0 == CTRadioAccessTechnologyNR
        }) { return .nr5G }
        if technologies.contains(CTRadioAccessTechnologyLTE) { return .lte }
        if technologies.contains(where: {
            $0 == CTRadioAccessTechnologyWCDMA ||
                $0 == CTRadioAccessTechnologyHSDPA ||
                $0 == CTRadioAccessTechnologyHSUPA ||
                $0 == "CTRadioAccessTechnologyCDMA1xEVDORev0" ||
                $0 == "CTRadioAccessTechnologyCDMA1xEVDORevA" ||
                $0 == "CTRadioAccessTechnologyCDMA1xEVDORevB" ||
                $0 == "CTRadioAccessTechnologyeHRPD"
        }) { return .cellular3G }
        if technologies.contains(where: {
            $0 == CTRadioAccessTechnologyGPRS ||
                $0 == CTRadioAccessTechnologyEdge ||
                $0 == "CTRadioAccessTechnologyCDMA1x"
        }) { return .cellular2G }
        return .unknown
    }
}
