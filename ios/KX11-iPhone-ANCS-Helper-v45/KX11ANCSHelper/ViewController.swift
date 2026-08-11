import CoreBluetooth
import CoreTelephony
import Security
import UIKit

/// Helper v45 keeps one long-lived RequiresANCS Central owner and performs one minimal security
/// handshake against Android's permanent F04 service. A durable 128-bit P/Q frame and encrypted
/// B3 read prove the current ACL; exact L/Q then binds Android's reverse alias before A/Q may
/// establish ANCS CCCD access. The challenge is never logged and every managed frame is 17 bytes.
/// The v44 recovery/adoption/cancel budget is otherwise unchanged. Pair and B3 prove the
/// current ACL before Android registers its reverse ANCS client; no UUID rotation or connection
/// watchdog is used. Restoration claim #1 may reconcile a stale restored request after exact F04
/// reachability. Its legacy claim #2 is retired as soon as the replacement becomes an actual
/// app-issued request, because v43's publication root is then the sole destructive owner. The iPhone-owned
/// F05 relay carries telemetry and Android's authoritative post-CCCD proof. A corrupt/stale F04
/// namespace may consume one destructive reconnect for the whole retained owner lineage; terminal
/// callbacks never re-arm that budget. For an ordinary deferred reconnect, an exact F04 beacon
/// may authorize the same one-shot reclaim only after Helper has actually submitted that exact
/// saved-owner connect and captured its request token/current namespace; a queued intent or a
/// beacon by itself is never destructive evidence. A narrowly-scoped restoration exception may
/// adopt a system-owned `.connecting` wrapper only when the exact saved owner advertises a
/// strictly newer fixed-2F04 protocol-2 nonce after the current restoration boundary. The durable
/// `(owner, nonce)` consume is shared with ordinary publication arbitration and happens before
/// exactly one cancel; terminal state then permits exactly one RequiresANCS reopen. HA1208 adds a
/// UInt24 publication nonce to
/// manufacturer protocol 2 while retaining the fixed F04 UUID. Every actual app-issued connect,
/// including an explicit manual reopen, owns one bounded missing-didConnect recovery episode:
/// exact saved owner + post-boundary nonce + exact issued token + terminal generation may consume
/// one cancel before it is sent, then one terminal boundary may materialize one RequiresANCS
/// reopen. The reopen inherits the spent root claim. Legacy protocol 1 may discover/connect but
/// can never authorize publication adoption or a destructive missing-callback recovery.
final class ViewController: UIViewController {
    private enum BleRole: Int {
        case peripheral = 0
        case central = 1

        var title: String { self == .peripheral ? "Peripheral" : "Central" }
    }

    private enum CentralHandshake {
        case idle
        case discovering
        case writingPair
        case readingSecure
        case writingAncsReady
        case ready
    }

    /// Root origin controls only publication-adoption authority. An explicit user action may
    /// create one new episode on the same Android publication; automatic adoption may not. A
    /// fully-green session likewise creates one future post-green radio-loss episode without
    /// pretending that the same nonce is a newly-published F04 database.
    private enum CentralMissingConnectRootOrigin: String {
        case automaticPublication
        case explicitManual
        case postGreenReconnect
    }

    private enum CentralRestorationPublicationReopenPhase {
        case none
        case intentQueued
        case requestIssued
        case connected
        case exhausted
    }

    private struct CentralAdvertisementIdentity {
        let generation: UInt16
        let publicationNonce: UInt32?

        var hasPublicationAuthority: Bool { publicationNonce != nil }
    }

    private struct TelemetrySnapshot: Equatable {
        let batteryLevel: UInt8
        let powerFlags: UInt8
        let networkCode: UInt8
    }

    /// A delayed connect is represented as data before any delay begins. Keeping the exact
    /// CBPeripheral strongly referenced closes the power-off race between a terminal callback and
    /// its delayed reconnect closure.
    private struct DeferredCentralConnectIntent {
        let peripheral: CBPeripheral
        let reason: String
        let notBefore: Date
        let token: UInt64
    }

    // F04 is the permanent link-anchor UUID. Central mode scans the stable FFFF beacon and uses
    // only F04's B2/B3/B4 handshake; Peripheral mode keeps the legacy diagnostic service.
    private let serviceUUID = CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04")
    private let infoUUID = CBUUID(string: "D2D9E4B1-47F1-4E44-A8BB-A932FD5A2F04")
    private let controlUUID = CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F04")
    private let secureUUID = CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F04")
    private let telemetryUUID = CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F04")
    private let managedIncomingBeaconUUID =
        CBUUID(string: "D2D9E4BF-47F1-4E44-A8BB-A932FD5AFFFF")
    private let managedIncomingManufacturerID: UInt16 = 0xFFFF
    private let managedIncomingLegacyProtocol: UInt8 = 1
    private let managedIncomingPublicationProtocol: UInt8 = 2
    private var centralNamespaceResolved = false
    private var centralNamespaceGeneration: UInt16 = 0x2F04
    private var centralServiceUUID =
        CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04")
    private var centralControlUUID =
        CBUUID(string: "D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F04")
    private var centralSecureUUID =
        CBUUID(string: "D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F04")
    private var centralWakeUUID =
        CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F04")
    // Generation 5 is iPhone-owned and shares the one physical link with Android's ANCS client.
    // Keeping it separate from Android's generation-4 bootstrap database prevents either side
    // from reusing the opposite GATT role's cached B4 handle.
    private let telemetryRelayServiceUUID =
        CBUUID(string: "D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F05")
    private let telemetryRelayUUID =
        CBUUID(string: "D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F05")
    /// HA1211 managed proof frames fit the default ATT payload: one opcode plus a raw 128-bit Q.
    private let centralPairChallengeOpcode: UInt8 = 0x50
    private let centralLinkBoundOpcode: UInt8 = 0x4C
    private let centralAncsSubscribedOpcode: UInt8 = 0x41
    private let centralPairChallengeLength = 16
    private let centralProofFrameLength = 17

    private let logicalName = "iPhone_ANCS"
    private let runPreference = "KX11ANCSHelper.runRequested"
    private let rolePreference = "KX11ANCSHelper.bleRole.v12"
    private let savedGeelyPeripheralPreference = "KX11ANCSHelper.geelyPeripheral.v12"
    private let centralNamespacePreference = "KX11ANCSHelper.geelyNamespace.v32"
    private let centralLastAutomaticPublicationAdoptionPreference =
        "KX11ANCSHelper.lastAutomaticPublicationAdoption.v44"
    private let centralLegacyLastAutomaticPublicationAdoptionPreference =
        "KX11ANCSHelper.lastAutomaticPublicationAdoption.v43"
    private let centralLastObservedPublicationNoncePreference =
        "KX11ANCSHelper.lastObservedPublicationNonce.v43"
    private let centralLastObservedPublicationOwnerPreference =
        "KX11ANCSHelper.lastObservedPublicationOwner.v43"
    private let centralPairChallengePreference =
        "KX11ANCSHelper.pairChallenge.v45"
    // These IDs intentionally do not contain a version: Core Bluetooth restoration requires the
    // same manager identifier on every subsequent app launch/update.
    private let restoreIdentifier =
        "ru.natro.kx11ancshelper.peripheral.stable"
    private let centralRestoreIdentifier =
        "ru.natro.kx11ancshelper.central.stable"
    private let telemetrySampleInterval: TimeInterval = 1
    private let telemetryHeartbeatInterval: TimeInterval = 30

    private let statusLabel = UILabel()
    private let telemetryLabel = UILabel()
    private let roleControl = UISegmentedControl(items: ["Peripheral", "Central"])
    private let logView = UITextView()
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)
    private let clearLogButton = UIButton(type: .system)
    private let shareLogButton = UIButton(type: .system)
    private var logLines: [String] = []
    private let maximumLogLines = 600

    private var peripheralManager: CBPeripheralManager!
    private var centralManager: CBCentralManager!
    private var role: BleRole = .peripheral
    private var runRequested = true
    private var servicePublished = false
    private var serviceAddPending = false
    /// Local GATT publication is an object lineage, not a UUID flag. `removeAllServices()` does
    /// not prevent a late didAdd for the removed same-UUID object, so every new/restored service
    /// gets a monotonic generation and exact pending/published object identity.
    private var localServicePublicationGeneration: UInt64 = 0
    private var pendingLocalService: CBMutableService?
    private var pendingLocalServiceGeneration: UInt64?
    private var publishedLocalService: CBMutableService?
    private var publishedLocalServiceGeneration: UInt64?
    private var infoCharacteristic: CBMutableCharacteristic?
    private var controlCharacteristic: CBMutableCharacteristic?
    private var secureCharacteristic: CBMutableCharacteristic?
    private var telemetryCharacteristic: CBMutableCharacteristic?
    private var publishedServiceUUID: CBUUID?
    private var telemetrySubscribers: Set<UUID> = []
    /// Core Bluetooth can temporarily apply backpressure to notifications. Keep every changed
    /// state in order so a one-percent battery transition is never replaced by a newer frame.
    private var pendingTelemetryFrames: [Data] = []
    private var lastPublishedSnapshot: TelemetrySnapshot?
    private var lastTelemetryPublishAt = Date.distantPast
    private var lastTelemetryBackpressureLogAt = Date.distantPast
    private var lastBackgroundWakeLogAt = Date.distantPast
    private var telemetrySequence: UInt16 = 0
    private var phoneLocked = false
    private var telemetryTimer: Timer?
    private var settledTelemetryRefresh: DispatchWorkItem?
    private var lastAndroidReadAt: Date?
    private var lastAndroidReadLogAt = Date.distantPast
    private let telephonyInfo = CTTelephonyNetworkInfo()

    private var geelyPeripheral: CBPeripheral?
    private var centralService: CBService?
    private var centralControlCharacteristic: CBCharacteristic?
    private var centralSecureCharacteristic: CBCharacteristic?
    private var centralWakeCharacteristic: CBCharacteristic?
    private var centralHandshake: CentralHandshake = .idle
    private var centralReconnectWorkItem: DispatchWorkItem?
    private var centralReconnectToken: UInt64 = 0
    /// True only when this manager issued the app-local connect with RequiresANCS or restored it.
    private var centralOwnerConfiguredForAncs = false
    /// iOS 17 normally owns reconnect after a callback with isReconnecting=true. While this is
    /// set, Helper never issues a competing connect or cancel.
    private var centralSystemAutoReconnectActive = false
    private var centralSecureRetryWorkItem: DispatchWorkItem?
    private var centralSecureReadAttempt = 0
    private var centralLinkSecurityChallengeObserved = false
    private var centralCharacteristicDiscoveryWorkItem: DispatchWorkItem?
    private var centralServiceRediscoveryWorkItem: DispatchWorkItem?
    private var centralFreshF04ValidationWorkItem: DispatchWorkItem?
    private var centralFreshF04ValidationAttempt = 0
    private var centralFreshF04ValidationLastLogAt = Date.distantPast
    private let centralFreshF04ValidationDelays: [TimeInterval] = [1, 2, 5, 10]
    private var centralWakeSubscriptionWorkItem: DispatchWorkItem?
    private var centralWakeSubscriptionAttempt = 0
    private var centralCharacteristicDiscoveryAttempt = 0
    private var centralServiceRediscoveryAttempt = 0
    private let centralCharacteristicDiscoveryLimit = 3
    private let centralServiceRediscoveryLimit = 2
    private var centralHardResetReason: String?
    /// Automatic protocol recovery may cancel the retained RequiresANCS owner only once for the
    /// current CBPeripheral/restoration lineage. This budget intentionally survives every
    /// didDisconnect/didConnect pair: clearing `centralHardResetReason` at a terminal callback is
    /// not evidence of a new GATT database.
    private var centralDestructiveRecoveryOwnerID: UUID?
    private var centralDestructiveRecoveryConsumed = false
    private var centralDestructiveRecoveryWaitingForFreshF04 = false
    private var centralDestructiveRecoveryFirstReason: String?
    /// `didModifyServices` proves only that the old exact object was invalidated. It does not prove
    /// that Android has already published a replacement. Keep that object until a different exact
    /// CBService instance with valid B2/B3/B4 characteristics is observed.
    private var centralInvalidatedF04Service: CBService?
    private var centralFreshF04ValidationPending = false
    private var centralManualReconnectPending = false
    private var centralRequireFreshAdvertisement = false
    /// A namespace that already produced an invalid ATT database must never be accepted as
    /// "fresh" merely because Android is still advertising it. Wait for a different generation.
    private var centralRejectedNamespaceGeneration: UInt16?
    private var centralRejectedNamespaceLogAt = Date.distantPast
    /// State restoration may arrive while CBCentralManager is still unknown/resetting. Core
    /// Bluetooth commands are legal only after poweredOn, so retain the owner and resume later.
    private var centralRestorationAwaitingPower = false
    /// Core Bluetooth can restore a pending owner but omit both didConnect and didFailToConnect.
    /// Do not time that request out: only the matching stable F04 system/beacon proof may arm it.
    private var centralRestoredPendingOwner = false
    /// v44 records the exact process-restoration boundary separately from an app-issued request.
    /// This is the only authority which may adopt a system-owned `.connecting` wrapper without
    /// an issued-attempt token, and only a strictly newer protocol-2 publication may consume it.
    private var centralRestorationPublicationBoundaryActive = false
    private var centralRestorationPublicationBoundaryOwnerID: UUID?
    private var centralRestorationPublicationBoundaryBaselineNonce: UInt32?
    private var centralRestorationPublicationBoundaryNotBeforeUptime: TimeInterval?
    private var centralRestorationPublicationBoundaryTerminalGeneration: UInt64?
    private var centralRestorationPublicationBoundaryInvalidationGeneration: UInt64?
    private var centralRestorationPublicationAdoptedNonce: UInt32?
    private var centralRestorationPublicationReopenIssued = false
    private var centralRestorationPublicationReopenPhase:
        CentralRestorationPublicationReopenPhase = .none
    private var centralRestorationPublicationReopenIssuedToken: UInt64?
    private var centralRestorationPublicationReopenIssuedTerminalGeneration: UInt64?
    private var centralRestorationPublicationScanWindowWorkItem: DispatchWorkItem?
    private var centralRestorationPublicationScanRestartWorkItem: DispatchWorkItem?
    private var centralRestorationPublicationScanCycleAttempt = 0
    /// CONTRACT_V44_STALE_DUPLICATE_SCAN_WORK_CANNOT_MUTATE_FRESH_SLOT: DispatchWorkItem.cancel()
    /// does not prove a submitted closure cannot begin. Every bounded
    /// scan window/restart therefore carries both a lineage generation and a unique slot token;
    /// a stale closure must match both before it may clear a slot, stop scanning, or rearm.
    private var centralRestorationPublicationScanGeneration: UInt64 = 0
    private var centralRestorationPublicationScanNextToken: UInt64 = 0
    private var centralRestorationPublicationScanWindowToken: UInt64?
    private var centralRestorationPublicationScanRestartToken: UInt64?
    private var centralRestorationRecoveryAttempted = false
    private var centralRestorationRecoveryWorkItem: DispatchWorkItem?
    /// A read-only reconciliation probe is allowed while the restored request is pending. It
    /// never cancels by age; it only observes the system F04 table and arms recovery on proof.
    private var centralRestorationProofProbeWorkItem: DispatchWorkItem?
    private var centralRestorationProofProbeAttempt = 0
    /// Set only after the one evidence-driven cancel was issued. The replacement connect is
    /// deferred until a terminal callback or the same owner's `.disconnected` state proves close.
    private var centralRestorationReconnectPending = false
    /// Core Bluetooth can also omit the terminal delegate callback after canceling a restored
    /// request. Observe CBPeripheral.state without issuing any second cancel/connect; `.disconnected`
    /// is an equivalent terminal boundary for reopening the same app-local owner.
    private var centralRestorationPostCancelProbeWorkItem: DispatchWorkItem?
    private var centralRestorationPostCancelProbeAttempt = 0
    /// A restored B4 subscription is usable only when both managers independently restore the
    /// same persisted owner: Central as already `.connected`, and Peripheral with the exact F05
    /// characteristic object listing that owner in subscribedCentrals. Either callback may arrive
    /// first. The hint is one-shot and never survives a fresh didConnect or disconnect.
    private var centralRestoredConnectedOwner: CBPeripheral?
    private var centralRestoredF05Characteristic: CBMutableCharacteristic?
    private var centralRestoredF05SubscriberIDs: Set<UUID> = []
    private var centralRestoredB4HintConsumed = false
    /// Number of destructive ownership claims in the current restoration lineage. Claim #1
    /// closes the stale restored request. Claim #2 is permitted only after an exact F04 entry in
    /// retrieveConnectedPeripherals proves that a fresh app-local connect reached the physical
    /// link but lost didConnect. No third claim exists.
    private var centralRestoreOwnershipClaimCount = 0
    private let centralRestoreOwnershipClaimLimit = 2
    private var centralRestoreFreshConnectAwaitingCallback = false
    private var centralRestoreFreshConnectProofWorkItem: DispatchWorkItem?
    private var centralRestoreFreshConnectProofToken: UInt64 = 0
    private let centralRestoreFreshConnectCallbackGrace: TimeInterval = 1.5
    private var centralDeferredStopScan = false
    private var centralDeferredCancellations: [UUID: CBPeripheral] = [:]
    private var centralDeferredConnectIntent: DeferredCentralConnectIntent?
    private var centralDeferredConnectWorkItem: DispatchWorkItem?
    private var centralDeferredConnectToken: UInt64 = 0
    /// A hot Status Widget update can terminate Android's F04 server while Core Bluetooth moves
    /// the retained exact owner back to `.connecting` and then omits app-local `didConnect`.
    /// Observe the exact saved identifier through the F04 beacon and system connection table in
    /// parallel. Only physical proof, never elapsed time, may consume this lineage's single claim.
    private var centralDeferredReclaimOwnerID: UUID?
    private var centralDeferredReclaimActive = false
    private var centralDeferredReclaimConsumed = false
    private var centralDeferredReclaimPendingTerminal = false
    /// True only while this ordinary lineage is bound to a real app-local connect token: either a
    /// fresh direct submission or the AutoReconnect descendant of a pre-terminal submission.
    /// Merely queuing/consuming a data intent never sets it.
    private var centralDeferredReclaimIssuedConnectPending = false
    /// Issued-request evidence is created only after the actual `centralManager.connect` call.
    /// The token, exact saved owner and fixed F04 namespace bind a later beacon to that specific
    /// app-local request instead of to a queued intent or a system-owned `.connecting` wrapper.
    private var centralDeferredReclaimIssuedConnectSerial: UInt64 = 0
    /// Global provenance of the most recent real app-local connect. It exists independently of an
    /// ordinary reclaim lineage so a later terminal callback can bind Core Bluetooth's descendant
    /// AutoReconnect `.connecting` generation to the request that this app actually submitted.
    private var centralLastActualIssuedConnectToken: UInt64?
    private var centralLastActualIssuedOwnerID: UUID?
    private var centralLastActualIssuedNamespaceGeneration: UInt16?
    private var centralLastActualIssuedAtUptime: TimeInterval?
    private var centralLastActualIssuedEnabledAutoReconnect = false
    private var centralDeferredReclaimTerminalGeneration: UInt64 = 0
    private var centralDeferredReclaimTerminalAtUptime: TimeInterval = 0
    private var centralDeferredReclaimIssuedConnectToken: UInt64?
    private var centralDeferredReclaimIssuedOwnerID: UUID?
    private var centralDeferredReclaimIssuedNamespaceGeneration: UInt16?
    private var centralDeferredReclaimIssuedTerminalGeneration: UInt64?
    private var centralDeferredReclaimEvidenceNotBeforeUptime: TimeInterval?
    private var centralDeferredReclaimBeaconObserved = false
    private var centralDeferredReclaimBeaconIssuedConnectToken: UInt64?
    private var centralDeferredReclaimBeaconOwnerID: UUID?
    private var centralDeferredReclaimBeaconNamespaceGeneration: UInt16?
    private var centralDeferredReclaimBeaconTerminalGeneration: UInt64?
    private var centralDeferredReclaimSystemProofObserved = false
    private var centralDeferredReclaimProbeWorkItem: DispatchWorkItem?
    private var centralDeferredReclaimGraceWorkItem: DispatchWorkItem?
    private var centralDeferredReclaimPostCancelWorkItem: DispatchWorkItem?
    private var centralDeferredReclaimScanWindowWorkItem: DispatchWorkItem?
    private var centralDeferredReclaimScanRestartWorkItem: DispatchWorkItem?
    private var centralDeferredReclaimScanCycleAttempt = 0
    /// Monotonic epoch captured by every probe/grace closure. Any terminal, didConnect or radio
    /// transition advances it before cancelling work, so stale evidence cannot act on a newer
    /// Core Bluetooth request.
    private var centralDeferredReclaimEvidenceGeneration: UInt64 = 0
    private var centralDeferredReclaimProbeAttempt = 0
    private var centralDeferredReclaimPostCancelAttempt = 0
    private var centralDeferredReclaimWaitLogged = false
    /// v43 separates a real Core Bluetooth attempt token from its recovery-root claim. The
    /// recovery reopen gets a new attempt token but inherits `centralDeferredReclaimConsumed` and
    /// this root token, so it cannot cancel again for the same episode.
    private var centralDeferredReclaimRootClaimSerial: UInt64 = 0
    private var centralDeferredReclaimRootClaimToken: UInt64?
    private var centralDeferredReclaimRootOrigin: CentralMissingConnectRootOrigin?
    private var centralDeferredReclaimPublicationNonce: UInt32?
    private var centralDeferredReclaimBeaconPublicationNonce: UInt32?
    private var centralDeferredReclaimBeaconRootClaimToken: UInt64?
    private var centralDeferredReclaimIssuedInvalidationGeneration: UInt64?
    private var centralDeferredReclaimBeaconInvalidationGeneration: UInt64?
    private var centralPublicationInvalidationGeneration: UInt64 = 0
    private var centralExplicitManualRootPending = false
    private var centralLastFullGreenOwnerID: UUID?
    private var centralLastFullGreenTerminalGeneration: UInt64?
    private var centralLastFullGreenPublicationNonce: UInt32?
    /// The last valid protocol-2 frame is persisted only after it came from the exact saved
    /// CBPeripheral. It is not automatic new-publication authority by itself, but lets an explicit
    /// manual/post-green root combine a known publication identity with later system-table proof
    /// if Android stops advertising as soon as the physical ACL appears.
    private var centralLastObservedPublicationNonce: UInt32?
    private var centralLastObservedPublicationOwnerID: UUID?
    private var centralLastObservedPublicationAtUptime: TimeInterval?
    private var centralLastObservedPublicationTerminalGeneration: UInt64?
    private var centralLastObservedPublicationInvalidationGeneration: UInt64?
    private var centralAutomaticBoundaryRejectedPublicationNonce: UInt32?
    private var centralAutomaticBoundaryRejectedOwnerID: UUID?
    private let centralDeferredReclaimProofGrace: TimeInterval = 1.5
    private let centralDeferredReclaimProbeDelays: [TimeInterval] = [0.25, 0.5, 1, 2, 5]
    private let centralDeferredReclaimPostCancelDelays: [TimeInterval] = [0.25, 0.5, 1, 2, 5]
    private let centralDeferredReclaimScanWindow: TimeInterval = 1.5
    private let centralDeferredReclaimScanRestartDelays: [TimeInterval] = [2, 5, 10, 30]
    /// Manual/hard-reset cancel may cross a Bluetooth power transition before its terminal
    /// callback. Observe only state until the exact owner becomes disconnected, then materialize
    /// one deferred connect intent through the poweredOn/F05 route.
    private var centralPendingTerminalStateProbeWorkItem: DispatchWorkItem?
    private var centralPendingTerminalStateProbeAttempt = 0
    private var centralReconnectFailureCount = 0
    private let centralReconnectDelays: [TimeInterval] = [1, 2, 5, 10, 20, 30]
    private var centralHelperConfirmed = false
    /// Snapshot of CBPeripheral.ancsAuthorized for the retained RequiresANCS owner.
    private var centralAncsAuthorized = false
    /// True only after Android has subscribed both real ANCS CCCDs and returned the matched
    /// F05/B4 ANCS-SUBSCRIBED proof. This is stronger than a transient Core Bluetooth snapshot.
    private var centralAncsAccessProven = false
    private var centralAncsAuthorizationCallbackObserved = false
    /// ANCS-READY is an exact-owner/B3 gate, not an ANCS privacy decision. Keep an explicit bit so
    /// a late authorization callback or duplicate B3 value can never write it twice.
    private var centralAncsReadyWriteIssued = false
    /// True only after CURRENT LINK B3 proved encryption on this exact ATT owner.
    private var centralSecureLinkReady = false
    /// HA1211 Q is durable per exact `(owner, protocol2 publication nonce)` so process restoration
    /// can replay the identical accepted Pair tuple. All object/generation fields remain ephemeral
    /// and must be re-proved before LINK-BOUND or ANCS-SUBSCRIBED is accepted.
    private var centralPairChallenge: Data?
    private var centralPairChallengeOwnerID: UUID?
    private var centralPairChallengeService: CBService?
    private var centralPairChallengeTerminalGeneration: UInt64?
    private var centralPairChallengePublicationNonce: UInt32?
    private var centralPairChallengeInvalidationGeneration: UInt64?
    private var centralPairChallengeRelayCharacteristic: CBMutableCharacteristic?
    private var centralPairChallengeRelayGeneration: UInt64?
    private var centralPairRestorationGeneration: UInt64 = 0
    private var centralPairRestorationCapabilityGeneration: UInt64?
    private var centralPairRestorationOwner: CBPeripheral?
    private var centralPairRestorationRelayCharacteristic: CBMutableCharacteristic?
    private var centralPairRestorationRelayGeneration: UInt64?
    private var centralPairRestorationRelaySubscriberIDs: Set<UUID> = []
    private var centralPairRestorationRelayProvisional = false
    private var centralPairPeripheralRestoreCallbackObserved = false
    private var centralPairAwaitingRelayRebind = false
    private var centralPairRehydratedOwnerID: UUID?
    private var centralPairRehydratedPublicationNonce: UInt32?
    private var centralPairRehydratedService: CBService?
    private var centralPairRehydratedTerminalGeneration: UInt64?
    private var centralPairRehydratedInvalidationGeneration: UInt64?
    private var centralPairRehydratedRelayCharacteristic: CBMutableCharacteristic?
    private var centralPairRehydratedRelayGeneration: UInt64?
    private var centralPairRehydratedCapabilityGeneration: UInt64?
    private var centralAliasBound = false
    private var centralAliasBoundChallenge: Data?
    private var centralAliasBoundOwnerID: UUID?
    private var centralAliasBoundService: CBService?
    private var centralAliasBoundTerminalGeneration: UInt64?
    private var centralAliasBoundPublicationNonce: UInt32?
    private var centralAliasBoundInvalidationGeneration: UInt64?
    private var centralAliasBoundRelayCharacteristic: CBMutableCharacteristic?
    private var centralAliasBoundRelayGeneration: UInt64?
    private var centralB4Subscribed = false
    private var centralAncsCccdConfirmed = false
    private var centralReadinessProofWorkItem: DispatchWorkItem?
    private let centralReadinessProofTimeout: TimeInterval = 30
    private let centralRestorationEvidenceGrace: TimeInterval = 1.5
    private let centralRestorationPublicationScanWindow: TimeInterval = 1.5
    private let centralRestorationPublicationScanRestartDelays: [TimeInterval] = [2, 5, 10, 30]
    private let centralRestorationProofProbeDelays: [TimeInterval] = [0.5, 1, 2, 5, 10]
    private let centralRestorationPostCancelProbeDelays: [TimeInterval] = [0.25, 0.5, 1, 2, 5]
    private let centralPendingTerminalStateProbeDelays: [TimeInterval] = [0.25, 0.5, 1, 2, 5]

    override func viewDidLoad() {
        super.viewDidLoad()
        let defaults = UserDefaults.standard
        if defaults.object(forKey: runPreference) == nil {
            defaults.set(true, forKey: runPreference)
        }
        runRequested = defaults.bool(forKey: runPreference)
        role = BleRole(rawValue: defaults.integer(forKey: rolePreference)) ?? .peripheral
        loadCentralPublicationPersistence(defaults)
        buildInterface()
        roleControl.selectedSegmentIndex = role.rawValue
        updateButtons()

        append("v45 HA1211: P/Q → B3 → READY → L/Q → A/Q; exact-tuple Q is durable")
        append("Restoration adoption persists owner+nonce before one cancel and one reopen")
        append("Каждый actual RequiresANCS connect arm-ит exact-owner proof observers")
        append("Protocol2 F04 nonce связан с issued token + terminal generation + root claim")
        append("Manual и automatic publication budgets разделены; reopen наследует spent root")
        append("Инвалидация F04 не rearm budget до validated replacement object")
        append("Один Central owner: RequiresANCS=true с первого connect")
        append("17-byte P/Q → B3 → READY → L/Q → A/Q на том же owner")
        append("Pending connect не имеет watchdog; rescue только по F04 system/beacon proof")
        append("Зелёный: current alias + ANCS/B4 CCCD + permission/access proof + данные")
        append("Все Central-команды ждут poweredOn; restoration возобновляется автоматически")
        append("iOS 17+ AutoReconnect и ручной backoff взаимоисключающие; второго connect нет")
        append("Central/Peripheral restoration IDs и Android identity сохраняются")
        append("Телеметрия: батарея, сеть и блокировка + B4 READ/NOTIFY")

        startTelemetryMonitoring()
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: .main,
            options: [
                CBPeripheralManagerOptionShowPowerAlertKey: true,
                CBPeripheralManagerOptionRestoreIdentifierKey: restoreIdentifier
            ]
        )
        centralManager = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [
                CBCentralManagerOptionShowPowerAlertKey: true,
                CBCentralManagerOptionRestoreIdentifierKey: centralRestoreIdentifier
            ]
        )
    }

    deinit {
        telemetryTimer?.invalidate()
        settledTelemetryRefresh?.cancel()
        centralReconnectWorkItem?.cancel()
        centralRestorationRecoveryWorkItem?.cancel()
        centralRestorationProofProbeWorkItem?.cancel()
        centralRestorationPostCancelProbeWorkItem?.cancel()
        centralRestorationPublicationScanWindowWorkItem?.cancel()
        centralRestorationPublicationScanRestartWorkItem?.cancel()
        centralDeferredConnectWorkItem?.cancel()
        centralDeferredReclaimScanWindowWorkItem?.cancel()
        centralDeferredReclaimScanRestartWorkItem?.cancel()
        centralPendingTerminalStateProbeWorkItem?.cancel()
        centralSecureRetryWorkItem?.cancel()
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralServiceRediscoveryWorkItem?.cancel()
        centralWakeSubscriptionWorkItem?.cancel()
        centralReadinessProofWorkItem?.cancel()
        NotificationCenter.default.removeObserver(self)
        telephonyInfo.delegate = nil
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    private func buildInterface() {
        view.backgroundColor = UIColor(red: 0.05, green: 0.08, blue: 0.12, alpha: 1)

        let titleLabel = UILabel()
        titleLabel.text = "KX11 ANCS v45 · HA1211"
        titleLabel.font = .boldSystemFont(ofSize: 24)
        titleLabel.textColor = .white

        statusLabel.text = "ЗАПУСК"
        statusLabel.font = .boldSystemFont(ofSize: 15)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = .systemBlue
        statusLabel.textAlignment = .center
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true
        statusLabel.heightAnchor.constraint(equalToConstant: 42).isActive = true

        telemetryLabel.text = "Телеметрия: ожидание iOS"
        telemetryLabel.font = .monospacedSystemFont(ofSize: 13, weight: .semibold)
        telemetryLabel.textColor = .white
        telemetryLabel.numberOfLines = 0
        telemetryLabel.textAlignment = .center

        roleControl.selectedSegmentTintColor = .systemBlue
        roleControl.setTitleTextAttributes([.foregroundColor: UIColor.white],
                                           for: .selected)
        roleControl.setTitleTextAttributes([.foregroundColor: UIColor.systemBlue],
                                           for: .normal)
        roleControl.addTarget(self, action: #selector(roleChanged),
                              for: .valueChanged)
        roleControl.heightAnchor.constraint(equalToConstant: 38).isActive = true

        configureButton(startButton, title: "Запустить единый BLE-сервис",
                        action: #selector(startTapped))
        configureButton(stopButton, title: "Остановить BLE-сервис",
                        action: #selector(stopTapped))
        configureButton(resetButton, title: "Перепубликовать GATT без сброса пары",
                        action: #selector(resetTapped))
        configureButton(clearLogButton, title: "Очистить журнал",
                        action: #selector(clearLogTapped), compact: true)
        configureButton(shareLogButton, title: "Поделиться журналом",
                        action: #selector(shareLogTapped), compact: true)

        let buttons = UIStackView(arrangedSubviews: [startButton, stopButton, resetButton])
        buttons.axis = .vertical
        buttons.spacing = 8
        let logActions = UIStackView(arrangedSubviews: [clearLogButton, shareLogButton])
        logActions.axis = .horizontal
        logActions.spacing = 8
        logActions.distribution = .fillEqually

        logView.backgroundColor = UIColor(white: 0.97, alpha: 1)
        logView.textColor = UIColor(white: 0.08, alpha: 1)
        logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.isEditable = false
        logView.layer.cornerRadius = 10
        logView.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)

        let stack = UIStackView(arrangedSubviews: [
            titleLabel, roleControl, statusLabel, telemetryLabel, buttons, logActions, logView
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                                           constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                                            constant: -16),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                                          constant: -16)
        ])
        loadJournal()
    }

    private func configureButton(_ button: UIButton, title: String, action: Selector,
                                 compact: Bool = false) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.backgroundColor = UIColor(white: 0.93, alpha: 1)
        button.layer.cornerRadius = 8
        button.heightAnchor.constraint(equalToConstant: compact ? 36 : 44).isActive = true
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    @objc private func startTapped() {
        runRequested = true
        UserDefaults.standard.set(true, forKey: runPreference)
        updateButtons()
        startSelectedRouteIfPossible()
    }

    @objc private func stopTapped() {
        runRequested = false
        UserDefaults.standard.set(false, forKey: runPreference)
        stopAllBleRoutes()
        setStatus("ОСТАНОВЛЕНО", color: .systemGray)
        updateButtons()
        append("Оба BLE-маршрута остановлены пользователем")
    }

    @objc private func resetTapped() {
        if role == .peripheral {
            guard peripheralManager != nil, peripheralManager.state == .poweredOn else { return }
            append("Перепубликую локальный GATT; системную LE-пару не удаляю")
            peripheralManager.stopAdvertising()
            peripheralManager.removeAllServices()
            clearPublishedService(allowCentralPairRelayRebind: false)
            publishServiceIfPossible()
        } else {
            append("Ручной reconnect: отменяю только текущий owner и жду didDisconnect")
            clearCentralPairRestorationCapability()
            clearCentralPairTranscript(reason: "explicit manual reconnect")
            cancelCentralReconnect()
            clearCentralRestorationPublicationBoundary()
            clearCentralDeferredConnectIntent()
            clearCentralDeferredReclaimLineage()
            clearCentralPendingTerminalStateObservation()
            clearCentralRestorationRecovery()
            resetCentralRestoreOwnershipClaims()
            clearCentralRestoredB4Hint()
            centralHardResetReason = nil
            // CONTRACT_V40_MANUAL_RECONNECT_IS_INDEPENDENT: a user action may reopen the exact
            // owner even after automatic destructive recovery is spent. It does not re-arm the
            // automatic budget; only a new owner/route, a validated replacement F04 object, or a
            // complete green proof may do that.
            centralDestructiveRecoveryWaitingForFreshF04 = false
            centralManualReconnectPending = true
            centralHelperConfirmed = false
            centralB4Subscribed = false
            centralAncsCccdConfirmed = false
            if let current = geelyPeripheral, current.state != .disconnected {
                cancelCentralConnectionSafely(current, manager: centralManager,
                                                reason: "explicit manual reconnect")
                setStatus("CENTRAL · РУЧНОЙ DISCONNECT", color: .systemOrange)
            } else if let current = geelyPeripheral {
                centralManualReconnectPending = false
                centralOwnerConfiguredForAncs = false
                clearCentralRuntime(keepPeripheral: true)
                centralExplicitManualRootPending = true
                issueCentralConnect(current, reason: "explicit manual reconnect")
            } else {
                centralManualReconnectPending = false
                // CONTRACT_V43_MANUAL_ORIGIN_SURVIVES_MISSING_RETAINED_WRAPPER: scan/retrieve may
                // be asynchronous; the typed marker is consumed only after the actual connect.
                centralExplicitManualRootPending = true
                startCentralRouteIfPossible()
            }
        }
    }

    @objc private func roleChanged() {
        guard let selected = BleRole(rawValue: roleControl.selectedSegmentIndex),
              selected != role else { return }
        stopAllBleRoutes()
        role = selected
        UserDefaults.standard.set(selected.rawValue, forKey: rolePreference)
        clearCentralRuntime(keepPeripheral: false)
        lastPublishedSnapshot = nil
        append("Роль переключена: iPhone \(selected.title). Выберите ту же роль в Status Widget")
        updateButtons()
        updateTelemetryLabel()
        if runRequested { startSelectedRouteIfPossible() }
    }

    @objc private func clearLogTapped() {
        logLines.removeAll()
        logView.text = ""
        persistJournal()
        append("Журнал подключения очищен")
    }

    @objc private func shareLogTapped() {
        let text = logLines.joined(separator: "\n")
        guard !text.isEmpty else { return }
        let controller = UIActivityViewController(activityItems: [text],
                                                  applicationActivities: nil)
        if let popover = controller.popoverPresentationController {
            popover.sourceView = shareLogButton
            popover.sourceRect = shareLogButton.bounds
        }
        present(controller, animated: true)
    }

    private func updateButtons() {
        startButton.isEnabled = !runRequested
        stopButton.isEnabled = runRequested
        resetButton.isEnabled = runRequested
        startButton.setTitle("Запустить \(role.title)", for: .normal)
        stopButton.setTitle("Остановить BLE", for: .normal)
        resetButton.setTitle(role == .peripheral
            ? "Перепубликовать GATT без сброса пары"
            : "Переподключить Central без сброса пары", for: .normal)
    }

    private func startSelectedRouteIfPossible() {
        publishServiceIfPossible()
        if role == .central { startCentralRouteIfPossible() }
    }

    private func stopAllBleRoutes() {
        stopService()
        stopCentralRoute(cancelConnection: true)
    }

    // MARK: - One peripheral service

    private func publishServiceIfPossible() {
        guard runRequested, peripheralManager != nil,
              peripheralManager.state == .poweredOn, !serviceAddPending else { return }
        if servicePublished {
            guard let published = publishedLocalService,
                  publishedLocalServiceGeneration == localServicePublicationGeneration,
                  published.uuid == publishedServiceUUID else {
                append("Local GATT publication lineage invalid; republishing current role")
                clearPublishedService(allowCentralPairRelayRebind: false)
                publishServiceIfPossible()
                return
            }
            if role == .peripheral { startAdvertising() }
            else { updateConnectionStatus() }
            return
        }

        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        let preserveFirstRestorationRelay = role == .central
            && centralPairRestorationCapabilityGeneration != nil
            && centralPairRestorationRelayCharacteristic == nil
        let continueAwaitingRelayConstruction = role == .central
            && !servicePublished
            && !serviceAddPending
            && pendingLocalService == nil
            && publishedLocalService == nil
            && telemetryCharacteristic == nil
            && centralPairAwaitingRelayRebindHasExactPublication()
        if continueAwaitingRelayConstruction {
            append("Continue the one pending F05 construction with retained P/Q+F04; "
                + "no second clear and no Pair replay")
        } else {
            clearPublishedService(
                preserveCentralPairRestorationCapability: preserveFirstRestorationRelay)
        }

        let relay = role == .central
        let telemetry = CBMutableCharacteristic(
            type: relay ? telemetryRelayUUID : telemetryUUID,
            properties: relay ? [.read, .notify, .write] : [.read, .notify], value: nil,
            permissions: relay ? [.readable, .writeable] : [.readable]
        )
        let service = CBMutableService(
            type: relay ? telemetryRelayServiceUUID : serviceUUID, primary: true)
        if relay {
            service.characteristics = [telemetry]
            infoCharacteristic = nil
            controlCharacteristic = nil
            secureCharacteristic = nil
        } else {
            let info = CBMutableCharacteristic(
                type: infoUUID, properties: [.read], value: nil, permissions: [.readable]
            )
            let control = CBMutableCharacteristic(
                type: controlUUID, properties: [.write, .writeWithoutResponse], value: nil,
                permissions: [.writeable]
            )
            // B3 exists only for pairing/bootstrap compatibility. ANCS itself remains protected
            // by iOS. B4 stays readable before ANCS authorization.
            let secure = CBMutableCharacteristic(
                type: secureUUID, properties: [.read, .write, .notify], value: nil,
                permissions: [.readable, .writeable, .readEncryptionRequired,
                              .writeEncryptionRequired]
            )
            service.characteristics = [info, control, secure, telemetry]
            infoCharacteristic = info
            controlCharacteristic = control
            secureCharacteristic = secure
        }
        telemetryCharacteristic = telemetry
        pendingLocalService = service
        pendingLocalServiceGeneration = localServicePublicationGeneration
        serviceAddPending = true
        append(relay
            ? "Публикую B4 telemetry relay generation 5 без отдельной рекламы"
            : "Публикую один GATT \(logicalName), B4=READ+NOTIFY")
        peripheralManager.add(service)
    }

    private func clearPublishedService(
        preserveCentralPairRestorationCapability: Bool = false,
        allowCentralPairRelayRebind: Bool = true
    ) {
        // CONTRACT_V39_LOCAL_SERVICE_GENERATION_INVALIDATES_LATE_DIDADD
        let hadActiveCentralHandshake = role == .central && centralHandshake != .idle
        let preservePairForRelayRebind: Bool
        if allowCentralPairRelayRebind,
           role == .central, runRequested,
           let owner = geelyPeripheral,
           centralPairTranscriptMatches(owner) {
            preservePairForRelayRebind = true
        } else {
            preservePairForRelayRebind = false
        }
        if !preserveCentralPairRestorationCapability && !preservePairForRelayRebind {
            clearCentralPairRestorationCapability()
        }
        if preservePairForRelayRebind {
            clearCentralAliasBinding()
            centralAncsCccdConfirmed = false
            centralAncsAccessProven = false
            centralB4Subscribed = false
            centralPairChallengeRelayCharacteristic = nil
            centralPairChallengeRelayGeneration = nil
            centralPairAwaitingRelayRebind = true
            append("F05 boundary cleared alias/ANCS proof; retained current P/Q+F04 "
                + "transcript without Pair replay")
        } else {
            clearCentralPairTranscript(reason: "local F05 publication boundary")
            if role == .central {
                centralSecureLinkReady = false
                centralAncsReadyWriteIssued = false
                centralHelperConfirmed = false
                centralHandshake = hadActiveCentralHandshake ? .discovering : .idle
            }
        }
        localServicePublicationGeneration &+= 1
        servicePublished = false
        serviceAddPending = false
        pendingLocalService = nil
        pendingLocalServiceGeneration = nil
        publishedLocalService = nil
        publishedLocalServiceGeneration = nil
        infoCharacteristic = nil
        controlCharacteristic = nil
        secureCharacteristic = nil
        telemetryCharacteristic = nil
        publishedServiceUUID = nil
        telemetrySubscribers.removeAll()
        pendingTelemetryFrames.removeAll()
    }

    private func startAdvertising() {
        guard runRequested, role == .peripheral, servicePublished,
              let published = publishedLocalService,
              publishedLocalServiceGeneration == localServicePublicationGeneration,
              published.uuid == serviceUUID,
              publishedServiceUUID == serviceUUID,
              peripheralManager != nil,
              peripheralManager.state == .poweredOn else { return }
        if peripheralManager.isAdvertising {
            updateConnectionStatus()
            return
        }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: logicalName
        ])
    }

    private func stopService() {
        guard peripheralManager != nil else { return }
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        clearPublishedService(allowCentralPairRelayRebind: false)
    }

    // MARK: - iPhone central / Geely_ANCS route

    private func clearCentralRestoredB4Hint() {
        centralRestoredConnectedOwner = nil
        centralRestoredF05Characteristic = nil
        centralRestoredF05SubscriberIDs.removeAll()
        centralRestoredB4HintConsumed = false
    }

    private func consumeRestoredB4HintIfEligible(_ peripheral: CBPeripheral) -> Bool {
        guard !centralRestoredB4HintConsumed,
              centralRestoredConnectedOwner === peripheral,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              let raw = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedIdentifier = UUID(uuidString: raw),
              peripheral.identifier == savedIdentifier,
              let restoredF05 = centralRestoredF05Characteristic,
              restoredF05 === telemetryCharacteristic,
              centralRestoredF05SubscriberIDs.contains(savedIdentifier) else { return false }
        // CONTRACT_V39_TWO_SIDED_RESTORED_B4_HINT
        centralRestoredB4HintConsumed = true
        centralRestoredConnectedOwner = nil
        centralRestoredF05Characteristic = nil
        centralRestoredF05SubscriberIDs.removeAll()
        append("Exact two-sided restoration proof restored B4 subscription for current owner")
        return true
    }

    private func continueCentralConnected(_ peripheral: CBPeripheral,
                                          allowRestoredB4Hint: Bool = false) {
        peripheral.delegate = self
        bindCentralDestructiveRecoveryLineage(peripheral,
                                              source: "connected RequiresANCS owner")
        if centralHandshake == .idle {
            clearCentralPairTranscript(reason: "exact Central owner connected")
            centralAncsAuthorized = peripheral.ancsAuthorized
            centralAncsAuthorizationCallbackObserved = false
            centralAncsAccessProven = false
            centralAncsReadyWriteIssued = false
            centralSecureLinkReady = false
            centralHelperConfirmed = false
            // CONTRACT_V39_NEW_DIDCONNECT_REQUIRES_FRESH_B4_SUBSCRIBE: only the no-new-didConnect
            // `.connected` restoration path may consume the two-sided one-shot hint. Every fresh
            // app-local owner starts false and waits for exact current F05 didSubscribe.
            centralB4Subscribed = allowRestoredB4Hint
                && consumeRestoredB4HintIfEligible(peripheral)
            centralReconnectFailureCount = 0
            centralSystemAutoReconnectActive = false
            append("RequiresANCS owner connected · ancsAuthorized=\(centralAncsAuthorized); "
                + "проверяю current-link encryption через стабильный F04/B3")
            beginCentralDiscovery(peripheral)
        }
    }

    private func startCentralRouteIfPossible() {
        guard runRequested, role == .central, centralManager != nil,
              centralManager.state == .poweredOn else { return }
        publishServiceIfPossible()
        guard servicePublished,
              let published = publishedLocalService,
              publishedLocalServiceGeneration == localServicePublicationGeneration,
              published.uuid == telemetryRelayServiceUUID,
              publishedServiceUUID == telemetryRelayServiceUUID else {
            setStatus("CENTRAL · ПУБЛИКУЮ F05", color: .systemOrange)
            return
        }
        if consumePoweredOnPendingTerminalIntentIfPossible() { return }
        if consumeCentralDeferredConnectIfPossible() { return }
        if holdCentralRestorationPublicationAfterSoleReopenIfNeeded() { return }
        if centralRestoreFreshConnectAwaitingCallback, let peripheral = geelyPeripheral {
            armFreshRestoreConnectProofObservation(peripheral)
        }
        if centralHelperConfirmed && geelyPeripheral?.state == .connected {
            updateConnectionStatus()
            return
        }
        if centralRestorationReconnectPending {
            // CONTRACT_V38_REARM_POST_CANCEL_AFTER_POWER: read-only state observation only.
            armRestoredOwnerPostCancelObservation()
            setStatus("CENTRAL · ЖДУ RESTORE DISCONNECT", color: .systemOrange)
            return
        }
        if let peripheral = geelyPeripheral {
            switch peripheral.state {
            case .connected:
                if centralRestoredPendingOwner {
                    if restoredOwnerHasSystemLinkProof(peripheral) {
                        scheduleRestoredOwnerRecovery(
                            peripheral,
                            evidence: "system F04 table while restored wrapper became connected")
                    }
                    startCentralScan()
                    armRestoredOwnerProofObservation(peripheral)
                    setStatus("CENTRAL · RESTORE ЖДЁТ LOCAL CALLBACK",
                              color: .systemBlue)
                    return
                }
                if centralOwnerConfiguredForAncs {
                    // A process-restored `.connected` owner may not replay F05 didSubscribe.
                    // Only this exact restoration path is allowed to consume the two-sided hint.
                    continueCentralConnected(
                        peripheral,
                        allowRestoredB4Hint: centralRestoredConnectedOwner === peripheral)
                } else {
                    connectCentral(peripheral, reason: "retained system-connected peripheral")
                }
                updateConnectionStatus()
                return
            case .connecting:
                if centralRestorationPublicationOwns(peripheral),
                   centralRestorationPublicationAdoptedNonce == nil {
                    // v44 never lets the legacy restoration claim consume an arbitrary beacon or
                    // a system-table snapshot for this wrapper. Only a strictly newer protocol-2
                    // frame observed by `didDiscover` may enter the durable adoption path.
                    startCentralRestorationPublicationReadOnlyScan(peripheral)
                    setStatus("CENTRAL · RESTORE ЖДЁТ НОВЫЙ NONCE",
                              color: .systemBlue)
                    return
                }
                if centralRestoredPendingOwner {
                    if restoredOwnerHasSystemLinkProof(peripheral) {
                        // The system connection table is stronger evidence than elapsed time.
                        // Some restored CBPeripheral wrappers nevertheless remain `.connecting`,
                        // so run the same one-shot terminal-callback recovery used for a beacon.
                        scheduleRestoredOwnerRecovery(
                            peripheral,
                            evidence: "retrieveConnectedPeripherals(F04), same saved owner")
                    }
                    // A restored pending connect can legitimately wait forever while the car is
                    // absent. Scan in parallel, but do not cancel anything until the exact saved
                    // owner advertises the permanent anchor again.
                    startCentralScan()
                    armRestoredOwnerProofObservation(peripheral)
                    setStatus("CENTRAL · RESTORE ЖДЁТ F04", color: .systemBlue)
                } else {
                    setStatus("CENTRAL · СИСТЕМА ПОДКЛЮЧАЕТ", color: .systemBlue)
                }
                return
            case .disconnecting:
                setStatus("CENTRAL · ЖДУ DISCONNECT CALLBACK", color: .systemOrange)
                return
            case .disconnected:
                connectCentral(peripheral, reason: "retained peripheral")
                return
            @unknown default:
                break
            }
        }

        if let raw = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
           let identifier = UUID(uuidString: raw),
           let remembered = centralManager.retrievePeripherals(
                withIdentifiers: [identifier]).first {
            connectCentral(remembered, reason: "saved Geely_ANCS identity")
            return
        }
        if let connected = centralManager.retrieveConnectedPeripherals(
                withServices: [serviceUUID]).first {
            connectCentral(connected, reason: "system-connected stable anchor")
            return
        }
        startCentralScan()
    }

    /// CONTRACT_V44_SOLE_REOPEN_SURVIVES_POWER_WITHOUT_SECOND_CONNECT: once the one restoration
    /// publication reopen has been submitted, a Bluetooth power transition may erase callbacks
    /// and move the wrapper to any state. The spent root remains an in-memory gate. Only the
    /// original queued intent may materialize; normal routing can neither issue nor queue another
    /// app-local connect for this `(owner, nonce)`.
    private func holdCentralRestorationPublicationAfterSoleReopenIfNeeded() -> Bool {
        guard centralRestorationPublicationReopenIssued,
              centralRestorationPublicationAdoptedNonce != nil,
              centralDeferredReclaimActive,
              centralDeferredReclaimConsumed,
              let peripheral = geelyPeripheral,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralRestorationPublicationOwns(peripheral) else { return false }
        switch centralRestorationPublicationReopenPhase {
        case .intentQueued:
            // A real intent would already have returned from
            // `consumeCentralDeferredConnectIfPossible` above. Missing intent is fail-closed.
            centralRestorationPublicationReopenPhase = .exhausted
            centralOwnerConfiguredForAncs = false
            setStatus("CENTRAL · RESTORE REOPEN INTENT LOST", color: .systemOrange)
        case .requestIssued, .connected:
            if centralSystemAutoReconnectActive {
                // CONTRACT_V44_SYSTEM_AUTORECONNECT_OWNS_DISCONNECTED_SNAPSHOT: the iOS 17
                // callback explicitly says Core Bluetooth is reopening this exact request. A
                // transient read-only `.disconnected` snapshot, including one across radio
                // power, cannot exhaust or demote its RequiresANCS provenance.
                centralOwnerConfiguredForAncs = true
                setStatus("CENTRAL · SYSTEM RECONNECT SOLE OWNER", color: .systemBlue)
                return true
            }
            switch peripheral.state {
            case .disconnected:
                centralRestorationPublicationReopenPhase = .exhausted
                centralOwnerConfiguredForAncs = false
                centralSystemAutoReconnectActive = false
                clearCentralRuntime(keepPeripheral: true)
                setStatus("CENTRAL · RESTORE REOPEN 1/1 ИСЧЕРПАН", color: .systemOrange)
                append("PoweredOn observed sole restoration reopen disconnected; "
                    + "spent owner+nonce blocks a second app connect")
            case .connecting, .connected, .disconnecting:
                setStatus("CENTRAL · ЖДУ CALLBACK SOLE REOPEN", color: .systemBlue)
            @unknown default:
                setStatus("CENTRAL · ЖДУ SOLE REOPEN STATE", color: .systemOrange)
            }
        case .exhausted:
            setStatus("CENTRAL · RESTORE REOPEN 1/1 ИСЧЕРПАН", color: .systemOrange)
        case .none:
            return false
        }
        return true
    }

    private func cancelCentralRestorationPublicationReadOnlyScan(
        stopScan: Bool,
        resetBackoff: Bool
    ) {
        centralRestorationPublicationScanGeneration &+= 1
        centralRestorationPublicationScanWindowWorkItem?.cancel()
        centralRestorationPublicationScanWindowWorkItem = nil
        centralRestorationPublicationScanWindowToken = nil
        centralRestorationPublicationScanRestartWorkItem?.cancel()
        centralRestorationPublicationScanRestartWorkItem = nil
        centralRestorationPublicationScanRestartToken = nil
        if resetBackoff { centralRestorationPublicationScanCycleAttempt = 0 }
        if stopScan, centralManager != nil {
            stopCentralScanSafely(
                centralManager, reason: "restoration publication read-only scan closed")
        }
    }

    /// CONTRACT_V44_RESTORATION_BOUNDARY_DUPLICATE_SCAN_IS_BOUNDED_READ_ONLY: Core Bluetooth may
    /// coalesce an updated manufacturer payload for the same peripheral when the first baseline
    /// frame was seen under AllowDuplicates=false. v44 therefore owns a separate low-duty scan
    /// window while the restored wrapper has no app-issued token. Time only opens/closes scan
    /// windows; it never authorizes persistence, cancel or connect.
    private func startCentralRestorationPublicationReadOnlyScan(
        _ peripheral: CBPeripheral
    ) {
        guard centralRestorationPublicationOwns(peripheral),
              centralRestorationPublicationAdoptedNonce == nil,
              !centralRestorationPublicationReopenIssued,
              peripheral.state == .connecting,
              centralLastActualIssuedConnectToken == nil,
              !centralDeferredReclaimIssuedConnectPending,
              centralRestorationPublicationScanWindowWorkItem == nil,
              centralRestorationPublicationScanRestartWorkItem == nil,
              centralManager != nil,
              centralManager.state == .poweredOn,
              let boundaryNotBefore =
                centralRestorationPublicationBoundaryNotBeforeUptime else { return }
        let scanGeneration = centralRestorationPublicationScanGeneration
        centralRestorationPublicationScanNextToken &+= 1
        let windowToken = centralRestorationPublicationScanNextToken
        if centralManager.isScanning { centralManager.stopScan() }
        centralManager.scanForPeripherals(
            withServices: [managedIncomingBeaconUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            guard self.centralRestorationPublicationScanGeneration == scanGeneration,
                  self.centralRestorationPublicationScanWindowToken == windowToken,
                  self.centralRestorationPublicationBoundaryNotBeforeUptime
                    == boundaryNotBefore,
                  self.runRequested, self.role == .central,
                  self.centralRestorationPublicationOwns(peripheral),
                  self.centralRestorationPublicationAdoptedNonce == nil,
                  !self.centralRestorationPublicationReopenIssued,
                  self.centralManager.state == .poweredOn else { return }
            self.centralRestorationPublicationScanWindowWorkItem = nil
            self.centralRestorationPublicationScanWindowToken = nil
            self.stopCentralScanSafely(
                self.centralManager,
                reason: "restoration publication duplicate scan window expired")
            self.scheduleCentralRestorationPublicationReadOnlyScanRestart(
                peripheral, boundaryNotBefore: boundaryNotBefore)
        }
        centralRestorationPublicationScanWindowWorkItem = item
        centralRestorationPublicationScanWindowToken = windowToken
        DispatchQueue.main.asyncAfter(
            deadline: .now() + centralRestorationPublicationScanWindow,
            execute: item)
    }

    private func scheduleCentralRestorationPublicationReadOnlyScanRestart(
        _ peripheral: CBPeripheral,
        boundaryNotBefore: TimeInterval
    ) {
        guard centralRestorationPublicationScanRestartWorkItem == nil,
              centralRestorationPublicationOwns(peripheral),
              centralRestorationPublicationAdoptedNonce == nil,
              !centralRestorationPublicationReopenIssued,
              centralRestorationPublicationBoundaryNotBeforeUptime
                == boundaryNotBefore else { return }
        let scanGeneration = centralRestorationPublicationScanGeneration
        let index = min(
            centralRestorationPublicationScanCycleAttempt,
            centralRestorationPublicationScanRestartDelays.count - 1)
        let delay = centralRestorationPublicationScanRestartDelays[index]
        centralRestorationPublicationScanCycleAttempt = min(
            centralRestorationPublicationScanCycleAttempt + 1,
            centralRestorationPublicationScanRestartDelays.count - 1)
        centralRestorationPublicationScanNextToken &+= 1
        let restartToken = centralRestorationPublicationScanNextToken
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            guard self.centralRestorationPublicationScanGeneration == scanGeneration,
                  self.centralRestorationPublicationScanRestartToken == restartToken,
                  self.centralRestorationPublicationBoundaryNotBeforeUptime
                    == boundaryNotBefore,
                  self.runRequested, self.role == .central,
                  self.centralRestorationPublicationOwns(peripheral),
                  self.centralRestorationPublicationAdoptedNonce == nil,
                  !self.centralRestorationPublicationReopenIssued,
                  self.centralManager.state == .poweredOn else { return }
            self.centralRestorationPublicationScanRestartWorkItem = nil
            self.centralRestorationPublicationScanRestartToken = nil
            self.startCentralRestorationPublicationReadOnlyScan(peripheral)
        }
        centralRestorationPublicationScanRestartWorkItem = item
        centralRestorationPublicationScanRestartToken = restartToken
        append("Restoration publication duplicate scan sleeps \(Int(delay))s · read-only")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func restoredOwnerHasSystemLinkProof(_ restored: CBPeripheral) -> Bool {
        guard centralManager.state == .poweredOn,
              restored === geelyPeripheral,
              let raw = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedIdentifier = UUID(uuidString: raw),
              restored.identifier == savedIdentifier else { return false }
        return centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
            .contains(where: { $0.identifier == savedIdentifier })
    }

    /// Keep observing while the restored request is pending because Android may establish the
    /// ACL after Helper's first route pass and stop advertising before didDiscover is delivered.
    /// These probes are read-only and increasingly sparse; they never turn elapsed time into a
    /// cancel decision. Only a same-identifier F04 result can arm the one destructive action.
    private func armRestoredOwnerProofObservation(_ restored: CBPeripheral) {
        guard centralRestoredPendingOwner, !centralRestorationRecoveryAttempted,
              !centralRestorationPublicationBoundaryActive,
              centralRestorationProofProbeWorkItem == nil,
              restored === geelyPeripheral else { return }
        let index = min(centralRestorationProofProbeAttempt,
                        centralRestorationProofProbeDelays.count - 1)
        let delay = centralRestorationProofProbeDelays[index]
        let item = DispatchWorkItem { [weak self, weak restored] in
            guard let self = self, let restored = restored else { return }
            self.centralRestorationProofProbeWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestoredPendingOwner,
                  !self.centralRestorationRecoveryAttempted,
                  restored === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if self.restoredOwnerHasSystemLinkProof(restored) {
                self.scheduleRestoredOwnerRecovery(restored,
                    evidence: "read-only F04 system-table observation")
                return
            }
            self.centralRestorationProofProbeAttempt = min(
                self.centralRestorationProofProbeAttempt + 1,
                self.centralRestorationProofProbeDelays.count - 1)
            self.armRestoredOwnerProofObservation(restored)
        }
        centralRestorationProofProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// A pending connection has no elapsed-time watchdog. This one-shot check is armed only by
    /// the system F04 connection table or an advertisement from the exact saved owner.
    private func scheduleRestoredOwnerRecovery(_ restored: CBPeripheral,
                                                evidence: String) {
        guard centralRestoredPendingOwner, !centralRestorationRecoveryAttempted,
              !centralRestorationPublicationBoundaryActive,
              centralRestorationRecoveryWorkItem == nil,
              restored === geelyPeripheral else { return }
        centralRestorationProofProbeWorkItem?.cancel()
        centralRestorationProofProbeWorkItem = nil
        append("Stable F04 owner is present; one restore reconciliation armed"
            + " · \(evidence)")
        let item = DispatchWorkItem { [weak self, weak restored] in
            guard let self = self, let restored = restored else { return }
            self.centralRestorationRecoveryWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestoredPendingOwner,
                  !self.centralRestorationRecoveryAttempted,
                  restored === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            self.centralRestorationRecoveryAttempted = true
            self.centralRestoredPendingOwner = false
            self.stopCentralScanSafely(self.centralManager,
                                        reason: "restore evidence consumed")
            switch restored.state {
            case .connected:
                // retrieveConnectedPeripherals proves a physical system-wide link, not this
                // app's RequiresANCS ownership. Close the stale restored request once and wait
                // for its callback before issuing a fresh app-local connect(options:).
                self.centralRestoreOwnershipClaimCount = max(
                    self.centralRestoreOwnershipClaimCount, 1)
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.clearCentralRuntime(keepPeripheral: true)
                self.setStatus("CENTRAL · ЗАКРЫВАЮ RESTORED OWNER",
                               color: .systemOrange)
                self.append("System-wide F04 link exists but app-local didConnect is absent; "
                    + "cancel restored request once and wait for didDisconnect")
                self.cancelCentralConnectionSafely(restored, manager: self.centralManager,
                    reason: "claim app-local RequiresANCS owner after F04 proof")
                self.armRestoredOwnerPostCancelObservation()
            case .connecting:
                self.centralRestoreOwnershipClaimCount = max(
                    self.centralRestoreOwnershipClaimCount, 1)
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.clearCentralRuntime(keepPeripheral: true)
                self.setStatus("CENTRAL · ЗАКРЫВАЮ RESTORED OWNER",
                               color: .systemOrange)
                self.append("Restored owner is still .connecting after stable F04 proof; "
                    + "cancel once, then wait for terminal callback")
                self.cancelCentralConnectionSafely(restored, manager: self.centralManager,
                    reason: "one-shot restored-owner recovery after stable F04 proof")
                self.armRestoredOwnerPostCancelObservation()
            case .disconnecting:
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.append("Restored owner is already .disconnecting; wait before reconnect")
                self.armRestoredOwnerPostCancelObservation()
            case .disconnected:
                self.centralRestorationReconnectPending = true
                self.centralSystemAutoReconnectActive = false
                self.append("Restored owner became disconnected after stable F04 proof; "
                    + "enter shared terminal reopen")
                self.reopenRestoredOwnerAfterTerminalCallback(restored,
                    callback: "restoration grace observed .disconnected")
            @unknown default:
                self.append("Restored owner has unknown state; no destructive recovery issued")
            }
        }
        centralRestorationRecoveryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + centralRestorationEvidenceGrace,
                                      execute: item)
    }

    private func clearCentralRestorationRecovery() {
        centralRestorationRecoveryWorkItem?.cancel()
        centralRestorationRecoveryWorkItem = nil
        centralRestorationProofProbeWorkItem?.cancel()
        centralRestorationProofProbeWorkItem = nil
        centralRestorationProofProbeAttempt = 0
        centralRestorationPostCancelProbeWorkItem?.cancel()
        centralRestorationPostCancelProbeWorkItem = nil
        centralRestorationPostCancelProbeAttempt = 0
        centralRestoredPendingOwner = false
        centralRestorationRecoveryAttempted = false
        centralRestorationReconnectPending = false
    }

    private func resetCentralRestoreOwnershipClaims() {
        centralRestoreFreshConnectProofToken &+= 1
        centralRestoreFreshConnectProofWorkItem?.cancel()
        centralRestoreFreshConnectProofWorkItem = nil
        centralRestoreFreshConnectAwaitingCallback = false
        centralRestoreOwnershipClaimCount = 0
    }

    /// Claim #1 still closes a stale restored wrapper. Once its replacement becomes a real
    /// app-issued request, v43's publication-bound root is the sole destructive owner; the legacy
    /// restoration claim-2 timer is synchronously retired.
    private func supersedeRestorationClaimTwoWithPublicationRoot(_ reason: String) {
        guard centralRestoreFreshConnectAwaitingCallback
                || centralRestoreFreshConnectProofWorkItem != nil else { return }
        // CONTRACT_V43_ONE_DESTRUCTIVE_OWNER_SUPERSEDES_RESTORE_CLAIM2
        centralRestoreFreshConnectProofToken &+= 1
        centralRestoreFreshConnectProofWorkItem?.cancel()
        centralRestoreFreshConnectProofWorkItem = nil
        centralRestoreFreshConnectAwaitingCallback = false
        append("Publication-root recovery superseded restoration claim #2 · \(reason)")
    }

    /// Polling is read-only. It may wait forever; only an exact same-identifier entry returned by
    /// retrieveConnectedPeripherals(F04) may advance to the callback-grace check below.
    private func armFreshRestoreConnectProofObservation(_ peripheral: CBPeripheral) {
        guard centralRestoreFreshConnectAwaitingCallback,
              !centralDeferredReclaimActive,
              centralRestoreFreshConnectProofWorkItem == nil,
              peripheral === geelyPeripheral else { return }
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralRestoreFreshConnectProofWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestoreFreshConnectAwaitingCallback,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if self.restoredOwnerHasSystemLinkProof(peripheral) {
                self.observeFreshRestoreConnectPhysicalProof(peripheral)
            } else {
                self.armFreshRestoreConnectProofObservation(peripheral)
            }
        }
        centralRestoreFreshConnectProofWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: item)
    }

    /// Reconcile only a fresh post-restoration connect which has exact physical F04 proof but no
    /// app-local didConnect. The physical proof arms a short callback grace; elapsed time or a
    /// beacon can never enter this method on their own. The destructive budget is capped at two.
    private func observeFreshRestoreConnectPhysicalProof(_ peripheral: CBPeripheral) {
        guard centralRestoreFreshConnectAwaitingCallback,
              !centralDeferredReclaimActive,
              centralRestoreOwnershipClaimCount == 1,
              centralRestoreFreshConnectProofWorkItem == nil,
              peripheral === geelyPeripheral,
              centralManager.state == .poweredOn,
              restoredOwnerHasSystemLinkProof(peripheral) else { return }
        centralRestoreFreshConnectProofToken &+= 1
        let token = centralRestoreFreshConnectProofToken
        append("Fresh RequiresANCS connect has exact F04 physical proof; "
            + "waiting briefly for app-local didConnect before claim #2")
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralRestoreFreshConnectProofWorkItem = nil
            guard token == self.centralRestoreFreshConnectProofToken,
                  self.runRequested, self.role == .central,
                  self.centralRestoreFreshConnectAwaitingCallback,
                  !self.centralDeferredReclaimActive,
                  self.centralRestoreOwnershipClaimCount == 1,
                  self.centralRestoreOwnershipClaimCount
                    < self.centralRestoreOwnershipClaimLimit,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn,
                  self.restoredOwnerHasSystemLinkProof(peripheral) else { return }
            // CONTRACT_V39_RESTORE_SECOND_CLAIM_EXACT_F04_ONLY
            self.centralRestoreOwnershipClaimCount = 2
            self.centralRestoreFreshConnectAwaitingCallback = false
            if self.centralDeferredConnectIntent?.peripheral === peripheral {
                self.clearCentralDeferredConnectIntent()
            }
            self.centralRestorationReconnectPending = true
            self.centralOwnerConfiguredForAncs = false
            self.centralSystemAutoReconnectActive = false
            self.clearCentralRuntime(keepPeripheral: true)
            self.setStatus("CENTRAL · F04 OWNER CLAIM #2", color: .systemOrange)
            self.append("Fresh physical F04 link omitted didConnect; bounded ownership claim #2/2")
            self.cancelCentralConnectionSafely(peripheral, manager: self.centralManager,
                reason: "bounded F04 ownership claim #2 after exact system-table proof")
            self.armRestoredOwnerPostCancelObservation()
        }
        centralRestoreFreshConnectProofWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + centralRestoreFreshConnectCallbackGrace, execute: item)
    }

    /// A terminal callback is preferred, but state restoration has already demonstrated that
    /// callbacks can be lost. This read-only observer never makes an age-based decision and never
    /// repeats cancel/connect. It only reuses the terminal path once the same owner reports
    /// `.disconnected`.
    private func armRestoredOwnerPostCancelObservation() {
        guard centralRestorationReconnectPending,
              centralRestorationPostCancelProbeWorkItem == nil,
              let peripheral = geelyPeripheral else { return }
        let index = min(centralRestorationPostCancelProbeAttempt,
                        centralRestorationPostCancelProbeDelays.count - 1)
        let delay = centralRestorationPostCancelProbeDelays[index]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralRestorationPostCancelProbeWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralRestorationReconnectPending,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if peripheral.state == .disconnected {
                self.reopenRestoredOwnerAfterTerminalCallback(peripheral,
                    callback: "read-only post-cancel state=.disconnected")
                return
            }
            self.centralRestorationPostCancelProbeAttempt = min(
                self.centralRestorationPostCancelProbeAttempt + 1,
                self.centralRestorationPostCancelProbeDelays.count - 1)
            self.armRestoredOwnerPostCancelObservation()
        }
        centralRestorationPostCancelProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Complete the evidence-driven handover only after Core Bluetooth confirms that the stale
    /// restored request is closed. This guarantees that two app-local owners are never opened in
    /// parallel and preserves the same CBPeripheral identity and RequiresANCS options.
    private func reopenRestoredOwnerAfterTerminalCallback(_ peripheral: CBPeripheral,
                                                           callback: String) {
        guard centralRestorationReconnectPending,
              peripheral === geelyPeripheral else { return }
        // CONTRACT_V39_ALL_RESTORE_TERMINALS_ENTER_CLAIM_ONE: callback, read-only state and an
        // already-disconnected owner at reconciliation grace all establish the same claim #1
        // lineage before any replacement connect is materialized.
        centralRestoreOwnershipClaimCount = max(centralRestoreOwnershipClaimCount, 1)
        clearCentralRestorationRecovery()
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        append("Restored-owner terminal callback received · \(callback); "
            + "reopen same owner with RequiresANCS")
        guard runRequested, role == .central else { return }
        // Claim #1's replacement is observed for exact physical proof. After claim #2 the final
        // replacement is left to Core Bluetooth; no third destructive action can be armed.
        centralRestoreFreshConnectAwaitingCallback =
            centralRestoreOwnershipClaimCount < centralRestoreOwnershipClaimLimit
        queueCentralConnectIntent(peripheral,
            reason: "same stable owner after bounded restoration claim "
                + "\(centralRestoreOwnershipClaimCount)/\(centralRestoreOwnershipClaimLimit)",
            delay: 0.25)
    }

    /// Manual reconnect uses the same terminal boundary for both possible Core Bluetooth
    /// callbacks. Consuming the flag here prevents a cancelled pending connect's
    /// didFailToConnect from leaking manual intent into a later system reconnect.
    private func reopenManualOwnerAfterTerminalCallback(_ peripheral: CBPeripheral,
                                                         callback: String) {
        guard centralManualReconnectPending,
              peripheral === geelyPeripheral else { return }
        centralManualReconnectPending = false
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        append("Manual reconnect terminal callback received · \(callback); "
            + "reopen same owner")
        guard runRequested, role == .central else { return }
        // Typed provenance survives terminal -> DeferredIntent; issueCentralConnect consumes it
        // only after the actual Core Bluetooth call. No reason-string parsing is involved.
        centralExplicitManualRootPending = true
        queueCentralConnectIntent(peripheral,
            reason: "explicit manual reconnect", delay: 0.25)
    }

    private func startCentralScan() {
        guard runRequested, role == .central, centralManager.state == .poweredOn else { return }
        if centralManager.isScanning {
            updateConnectionStatus()
            return
        }
        centralManager.scanForPeripherals(
            withServices: [managedIncomingBeaconUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        setStatus("ИЩУ GEELY_ANCS", color: .systemBlue)
        append("Central scan: stable beacon \(managedIncomingBeaconUUID.uuidString)")
    }

    /// CBCentralManager logs an API MISUSE (and may assert in a future iOS release) when stop or
    /// cancel is sent before poweredOn. Restoration callbacks are explicitly allowed to precede
    /// that state, therefore every destructive Central command passes through these gates.
    private func stopCentralScanSafely(_ manager: CBCentralManager, reason: String) {
        guard manager.state == .poweredOn else {
            if !centralDeferredStopScan {
                append("Central stopScan отложен до poweredOn · \(reason)")
            }
            centralDeferredStopScan = true
            return
        }
        centralDeferredStopScan = false
        if manager.isScanning { manager.stopScan() }
    }

    private func cancelCentralConnectionSafely(_ peripheral: CBPeripheral,
                                                manager: CBCentralManager,
                                                reason: String) {
        clearCentralPairRestorationCapability()
        guard manager.state == .poweredOn else {
            let firstRequest = centralDeferredCancellations[peripheral.identifier] == nil
            centralDeferredCancellations[peripheral.identifier] = peripheral
            if firstRequest {
                append("Central cancel отложен до poweredOn · \(reason)")
            }
            return
        }
        centralDeferredCancellations.removeValue(forKey: peripheral.identifier)
        if peripheral.state != .disconnected {
            manager.cancelPeripheralConnection(peripheral)
        }
    }

    private func flushDeferredCentralCommands(_ manager: CBCentralManager) {
        guard manager.state == .poweredOn else { return }
        if centralDeferredStopScan {
            centralDeferredStopScan = false
            if manager.isScanning { manager.stopScan() }
            append("Central deferred stopScan выполнен после poweredOn")
        }
        let cancellations = Array(centralDeferredCancellations.values)
        centralDeferredCancellations.removeAll()
        var issued = 0
        for peripheral in cancellations where peripheral.state != .disconnected {
            manager.cancelPeripheralConnection(peripheral)
            issued += 1
        }
        if issued > 0 {
            append("Central deferred cancel выполнен после poweredOn · owners=\(issued)")
        }
    }

    /// Power can return after Core Bluetooth has already moved a cancelled manual/hard-reset
    /// owner to `.disconnected` but before delivering its terminal callback. Consume that state
    /// exactly once before the ordinary retained-owner route can reconnect with stale source flags.
    private func consumePoweredOnPendingTerminalIntentIfPossible() -> Bool {
        guard centralDeferredConnectIntent == nil,
              centralManualReconnectPending || centralHardResetReason != nil,
              let peripheral = geelyPeripheral else { return false }
        guard centralManager != nil, centralManager.state == .poweredOn else { return true }
        switch peripheral.state {
        case .disconnected:
            let source: String
            if centralManualReconnectPending {
                source = "power-resume explicit manual reconnect"
                centralExplicitManualRootPending = true
            } else {
                source = "power-resume hard reset · \(centralHardResetReason ?? "unknown")"
            }
            // CONTRACT_V38_POWER_RESUME_SOURCE_FLAGS_CLEAR_BEFORE_CONNECT
            centralManualReconnectPending = false
            centralHardResetReason = nil
            clearCentralPendingTerminalStateObservation()
            centralOwnerConfiguredForAncs = false
            centralSystemAutoReconnectActive = false
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            append("PoweredOn/F05 observed exact owner disconnected; "
                + "materialize one deferred intent · \(source)")
            guard queueCentralConnectIntent(peripheral, reason: source) else { return true }
            _ = consumeCentralDeferredConnectIfPossible()
            return true
        case .connected, .connecting, .disconnecting:
            armCentralPendingTerminalStateObservation()
            setStatus("CENTRAL · ЖДУ TERMINAL STATE", color: .systemOrange)
            return true
        @unknown default:
            armCentralPendingTerminalStateObservation()
            return true
        }
    }

    /// Read-only state reconciliation for a manual/hard-reset cancellation that crossed power
    /// state. No second cancel/connect is issued; the poweredOn/F05 route owns materialization.
    private func armCentralPendingTerminalStateObservation() {
        guard centralDeferredConnectIntent == nil,
              centralManualReconnectPending || centralHardResetReason != nil,
              centralPendingTerminalStateProbeWorkItem == nil,
              let peripheral = geelyPeripheral else { return }
        let index = min(centralPendingTerminalStateProbeAttempt,
                        centralPendingTerminalStateProbeDelays.count - 1)
        let delay = centralPendingTerminalStateProbeDelays[index]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralPendingTerminalStateProbeWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralDeferredConnectIntent == nil,
                  self.centralManualReconnectPending || self.centralHardResetReason != nil,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            if peripheral.state == .disconnected {
                // CONTRACT_V38_PENDING_SOURCE_STATE_REENTERS_F05_ROUTE
                self.startCentralRouteIfPossible()
                return
            }
            self.centralPendingTerminalStateProbeAttempt = min(
                self.centralPendingTerminalStateProbeAttempt + 1,
                self.centralPendingTerminalStateProbeDelays.count - 1)
            self.armCentralPendingTerminalStateObservation()
        }
        centralPendingTerminalStateProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func clearCentralPendingTerminalStateObservation() {
        centralPendingTerminalStateProbeWorkItem?.cancel()
        centralPendingTerminalStateProbeWorkItem = nil
        centralPendingTerminalStateProbeAttempt = 0
    }

    private func beginCentralRestorationPublicationBoundary(_ restored: CBPeripheral) {
        let savedIdentifier = UserDefaults.standard.string(
            forKey: savedGeelyPeripheralPreference).flatMap(UUID.init(uuidString:))
        guard restored.state == .connecting,
              restored === geelyPeripheral,
              restored.identifier == savedIdentifier else {
            clearCentralRestorationPublicationBoundary()
            return
        }
        cancelCentralRestorationPublicationReadOnlyScan(
            stopScan: false, resetBackoff: true)
        centralRestorationPublicationBoundaryActive = true
        centralRestorationPublicationBoundaryOwnerID = restored.identifier
        if centralLastObservedPublicationOwnerID == restored.identifier {
            centralRestorationPublicationBoundaryBaselineNonce =
                centralLastObservedPublicationNonce
        } else {
            centralRestorationPublicationBoundaryBaselineNonce = nil
        }
        centralRestorationPublicationBoundaryNotBeforeUptime =
            ProcessInfo.processInfo.systemUptime
        centralRestorationPublicationBoundaryTerminalGeneration =
            centralDeferredReclaimTerminalGeneration
        centralRestorationPublicationBoundaryInvalidationGeneration =
            centralPublicationInvalidationGeneration
        centralRestorationPublicationAdoptedNonce = nil
        centralRestorationPublicationReopenIssued = false
        centralRestorationPublicationReopenPhase = .none
        centralRestorationPublicationReopenIssuedToken = nil
        centralRestorationPublicationReopenIssuedTerminalGeneration = nil
        append("Restoration publication boundary armed · exactOwner="
            + restored.identifier.uuidString + " · baselineNonce="
            + (centralRestorationPublicationBoundaryBaselineNonce.map {
                String(format: "%06X", Int($0))
            } ?? "missing/fail-closed"))
    }

    /// A terminal callback can arrive immediately after `willRestoreState` and Core Bluetooth can
    /// then move the same wrapper back to `.connecting` without an app-issued connect. Advance the
    /// evidence boundary synchronously, but do not create or consume any destructive authority.
    private func advanceCentralRestorationPublicationBoundaryAfterTerminal(
        _ peripheral: CBPeripheral
    ) {
        guard centralRestorationPublicationBoundaryActive,
              centralRestorationPublicationAdoptedNonce == nil,
              centralRestorationPublicationBoundaryOwnerID == peripheral.identifier,
              peripheral === geelyPeripheral else { return }
        cancelCentralRestorationPublicationReadOnlyScan(
            stopScan: true, resetBackoff: true)
        centralRestorationPublicationBoundaryNotBeforeUptime =
            ProcessInfo.processInfo.systemUptime
        centralRestorationPublicationBoundaryTerminalGeneration =
            centralDeferredReclaimTerminalGeneration
        centralRestorationPublicationBoundaryInvalidationGeneration =
            centralPublicationInvalidationGeneration
        if centralLastObservedPublicationOwnerID == peripheral.identifier {
            centralRestorationPublicationBoundaryBaselineNonce =
                centralLastObservedPublicationNonce
        }
        append("Restoration publication boundary advanced after exact terminal · baselineNonce="
            + (centralRestorationPublicationBoundaryBaselineNonce.map {
                String(format: "%06X", Int($0))
            } ?? "missing/fail-closed"))
        if centralManager != nil, centralManager.state == .poweredOn,
           peripheral.state == .connecting {
            startCentralRestorationPublicationReadOnlyScan(peripheral)
        }
    }

    private func clearCentralRestorationPublicationBoundary() {
        cancelCentralRestorationPublicationReadOnlyScan(
            stopScan: true, resetBackoff: true)
        centralRestorationPublicationBoundaryActive = false
        centralRestorationPublicationBoundaryOwnerID = nil
        centralRestorationPublicationBoundaryBaselineNonce = nil
        centralRestorationPublicationBoundaryNotBeforeUptime = nil
        centralRestorationPublicationBoundaryTerminalGeneration = nil
        centralRestorationPublicationBoundaryInvalidationGeneration = nil
        centralRestorationPublicationAdoptedNonce = nil
        centralRestorationPublicationReopenIssued = false
        centralRestorationPublicationReopenPhase = .none
        centralRestorationPublicationReopenIssuedToken = nil
        centralRestorationPublicationReopenIssuedTerminalGeneration = nil
    }

    private func centralRestorationPublicationOwns(_ peripheral: CBPeripheral) -> Bool {
        guard centralRestorationPublicationBoundaryActive,
              centralRestorationPublicationBoundaryOwnerID == peripheral.identifier,
              peripheral === geelyPeripheral,
              let savedIdentifier = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference).flatMap(UUID.init(uuidString:)),
              savedIdentifier == peripheral.identifier else { return false }
        return true
    }

    /// CONTRACT_V44_RESTORED_SYSTEM_CONNECTING_NEWER_PUBLICATION_ONLY
    ///
    /// This is deliberately not a second general beacon-recovery path. It applies only to the
    /// exact wrapper restored by Core Bluetooth, while that wrapper is system-owned
    /// `.connecting` and no app-issued token exists. A valid fixed-2F04 protocol-2 nonce must be
    /// strictly newer than the nonce captured at the current restoration/terminal boundary.
    /// Protocol 1, malformed, same, older, foreign and pre-boundary observations are read-only.
    private func observeCentralRestorationPublicationAdoption(
        _ peripheral: CBPeripheral,
        identity: CentralAdvertisementIdentity?
    ) -> Bool {
        let observedAtUptime = ProcessInfo.processInfo.systemUptime
        guard centralRestorationPublicationOwns(peripheral),
              centralRestorationPublicationAdoptedNonce == nil,
              !centralRestorationPublicationReopenIssued,
              peripheral.state == .connecting,
              centralManager.state == .poweredOn,
              centralLastActualIssuedConnectToken == nil,
              !centralDeferredReclaimIssuedConnectPending,
              !centralDeferredReclaimPendingTerminal,
              !centralManualReconnectPending,
              centralHardResetReason == nil,
              !centralRestorationReconnectPending,
              !centralRestorationRecoveryAttempted,
              centralRestoreOwnershipClaimCount == 0,
              centralDeferredCancellations[peripheral.identifier] == nil,
              let identity = identity,
              identity.generation == 0x2F04,
              identity.generation == managedIncomingGeneration(from: serviceUUID),
              let nonce = identity.publicationNonce,
              nonce > 0, nonce < 0x00FF_FFFF,
              let baseline = centralRestorationPublicationBoundaryBaselineNonce,
              publicationNonceIsStrictlyNewer(nonce, than: baseline),
              let notBefore = centralRestorationPublicationBoundaryNotBeforeUptime,
              observedAtUptime >= notBefore,
              let boundaryTerminal =
                centralRestorationPublicationBoundaryTerminalGeneration,
              boundaryTerminal == centralDeferredReclaimTerminalGeneration,
              let boundaryInvalidation =
                centralRestorationPublicationBoundaryInvalidationGeneration,
              centralPublicationInvalidationGeneration >= boundaryInvalidation,
              !automaticPublicationAdoptionAlreadyConsumed(
                ownerID: peripheral.identifier, nonce: nonce) else {
            return false
        }
        if centralLastObservedPublicationOwnerID == peripheral.identifier,
           let current = centralLastObservedPublicationNonce,
           current != nonce,
           !publicationNonceIsStrictlyNewer(nonce, than: current) {
            return false
        }
        if centralDeferredReclaimActive {
            guard centralDeferredReclaimOwnerID == peripheral.identifier,
                  !centralDeferredReclaimConsumed else { return false }
        } else {
            beginCentralDeferredReclaimLineage(
                peripheral,
                reason: "restored system-owned .connecting publication adoption",
                origin: .automaticPublication)
        }
        guard centralDeferredReclaimActive,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              !centralDeferredReclaimConsumed,
              let rootClaim = centralDeferredReclaimRootClaimToken else { return false }

        // CONTRACT_V44_RESTORATION_ADOPTION_DURABLE_BEFORE_CANCEL: the same shared record used
        // by ordinary automatic adoption is the cross-restart spent bit. Failure is fail-closed.
        guard persistAutomaticPublicationAdoption(
                ownerID: peripheral.identifier, nonce: nonce) else {
            append("Restoration publication adoption persistence failed; fail closed without "
                + "cancel · nonce=" + String(format: "%06X", Int(nonce)))
            return false
        }

        // Persist first, then atomically consume all in-memory destructive owners on the main
        // queue, and only then send the single cancel. A crash at any intermediate edge therefore
        // cannot repeat the same `(owner, nonce)` after relaunch.
        centralRestorationPublicationAdoptedNonce = nonce
        centralDeferredReclaimRootOrigin = .automaticPublication
        centralDeferredReclaimPublicationNonce = nonce
        centralDeferredReclaimConsumed = true
        centralDeferredReclaimPendingTerminal = true
        centralDeferredReclaimIssuedConnectPending = false
        centralDeferredReclaimIssuedConnectToken = nil
        centralDeferredReclaimIssuedOwnerID = nil
        centralDeferredReclaimIssuedNamespaceGeneration = nil
        centralDeferredReclaimIssuedTerminalGeneration = nil
        centralDeferredReclaimIssuedInvalidationGeneration = nil
        centralDeferredReclaimEvidenceNotBeforeUptime = nil
        invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: true)
        cancelCentralRestorationPublicationReadOnlyScan(
            stopScan: true, resetBackoff: true)
        // The authority decision above used the immutable restoration baseline. Only after its
        // durable consume may the ordinary last-observed record advance to this publication.
        _ = rememberExactPublicationIdentity(peripheral, identity: identity)
        clearCentralDeferredConnectIntent()
        clearCentralRestorationRecovery()
        supersedeRestorationClaimTwoWithPublicationRoot(
            "restoration publication adoption consumed before cancel")
        centralRestoreFreshConnectAwaitingCallback = false
        stopCentralScanSafely(centralManager,
            reason: "restoration publication adoption consumed")
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        clearCentralRuntime(keepPeripheral: true)
        setStatus("CENTRAL · RESTORED OWNER CLAIM 1/1", color: .systemOrange)
        append("Strictly newer restored-owner publication consumed before one cancel · rootClaim="
            + "\(rootClaim) · baseline=" + String(format: "%06X", Int(baseline))
            + " · nonce=" + String(format: "%06X", Int(nonce)))
        cancelCentralConnectionSafely(peripheral, manager: centralManager,
            reason: "strictly newer restoration publication adoption")
        armCentralDeferredReclaimPostCancelObservation(peripheral)
        return true
    }

    private func loadCentralPublicationPersistence(_ defaults: UserDefaults = .standard) {
        if let rawOwner = defaults.string(
                forKey: centralLastObservedPublicationOwnerPreference),
           let owner = UUID(uuidString: rawOwner) {
            let nonce = UInt32(defaults.integer(
                forKey: centralLastObservedPublicationNoncePreference))
            if nonce > 0, nonce < 0x00FF_FFFF {
                centralLastObservedPublicationOwnerID = owner
                centralLastObservedPublicationNonce = nonce
            }
        }
    }

    private func publicationNonceIsStrictlyNewer(_ candidate: UInt32,
                                                 than current: UInt32) -> Bool {
        // Valid values form a ring 1...0xFFFFFE. Half-range comparison rejects delayed older
        // duplicate advertisements while still accepting Android's documented wrap.
        let modulus: UInt32 = 0x00FF_FFFE
        let candidateIndex = candidate - 1
        let currentIndex = current - 1
        let forward = (candidateIndex + modulus - currentIndex) % modulus
        // Exact half-range is deliberately ambiguous and rejected in both directions.
        return forward > 0 && forward < modulus / 2
    }

    @discardableResult
    private func rememberExactPublicationIdentity(
        _ peripheral: CBPeripheral,
        identity: CentralAdvertisementIdentity
    ) -> Bool {
        let ownerID = peripheral.identifier
        var publicationAdvanced = false
        guard identity.generation == 0x2F04,
              let nonce = identity.publicationNonce,
              nonce > 0, nonce < 0x00FF_FFFF,
              let rawSavedID = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedID = UUID(uuidString: rawSavedID),
              savedID == ownerID else { return false }
        if let currentOwner = geelyPeripheral,
           currentOwner.identifier == ownerID,
           currentOwner !== peripheral {
            return false
        }
        if centralPairChallengeOwnerID != nil,
           (centralPairChallengeOwnerID != ownerID
                || centralPairChallengePublicationNonce != nonce) {
            clearCentralPairTranscript(reason: "exact protocol2 owner/publication changed")
        }
        if centralLastObservedPublicationOwnerID == ownerID,
           let previous = centralLastObservedPublicationNonce {
            if nonce != previous {
                // CONTRACT_V43_OLDER_NONCE_CANNOT_ROLL_BACK_CURRENT_PUBLICATION
                guard publicationNonceIsStrictlyNewer(nonce, than: previous) else { return false }
                clearCentralPairRestorationCapability()
                publicationAdvanced = true
                centralPublicationInvalidationGeneration &+= 1
                if centralDeferredReclaimActive,
                   !centralDeferredReclaimConsumed,
                   centralDeferredReclaimOwnerID == ownerID {
                    invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
                }
            }
        }
        centralLastObservedPublicationOwnerID = ownerID
        centralLastObservedPublicationNonce = nonce
        centralLastObservedPublicationAtUptime = ProcessInfo.processInfo.systemUptime
        centralLastObservedPublicationTerminalGeneration =
            centralDeferredReclaimTerminalGeneration
        centralLastObservedPublicationInvalidationGeneration =
            centralPublicationInvalidationGeneration
        let defaults = UserDefaults.standard
        defaults.set(Int(nonce), forKey: centralLastObservedPublicationNoncePreference)
        defaults.set(ownerID.uuidString,
                     forKey: centralLastObservedPublicationOwnerPreference)
        if publicationAdvanced,
           centralDeferredReclaimActive,
           !centralDeferredReclaimConsumed,
           centralDeferredReclaimOwnerID == ownerID,
           centralLastActualIssuedAtUptime != nil {
            bindCentralDeferredReclaimToLastActualConnect(
                peripheral,
                evidenceNotBeforeUptime: ProcessInfo.processInfo.systemUptime,
                requiresAutoReconnectDescendant: false,
                source: "strictly newer protocol2 publication rebound current actual request")
            // `bind` owns the current invalidation generation. The caller immediately passes the
            // same accepted identity into the proof observer; an older duplicate was rejected.
        }
        return true
    }

    private func automaticPublicationAdoptionAlreadyConsumed(
        ownerID: UUID,
        nonce: UInt32
    ) -> Bool {
        let defaults = UserDefaults.standard
        for key in [centralLastAutomaticPublicationAdoptionPreference,
                    centralLegacyLastAutomaticPublicationAdoptionPreference] {
            guard let record = defaults.string(forKey: key) else { continue }
            let fields = record.split(separator: "|", omittingEmptySubsequences: false)
            guard fields.count == 2,
                  String(fields[0]) == ownerID.uuidString,
                  let consumedNonce = UInt32(fields[1], radix: 16),
                  consumedNonce > 0, consumedNonce < 0x00FF_FFFF else { continue }
            // CONTRACT_V44_V43_SPENT_NONCE_SURVIVES_UPGRADE_AND_RESTART
            if consumedNonce == nonce { return true }
        }
        return false
    }

    @discardableResult
    private func persistAutomaticPublicationAdoption(ownerID: UUID,
                                                     nonce: UInt32) -> Bool {
        let defaults = UserDefaults.standard
        // CONTRACT_V43_AUTOMATIC_ADOPTION_CONSUMED_BEFORE_CANCEL
        // One record prevents owner/nonce tearing. `synchronize` is a deliberate durability
        // barrier here: if it fails, the caller must fail closed and issue no cancel.
        let record = ownerID.uuidString + "|" + String(format: "%06X", Int(nonce))
        defaults.set(record, forKey: centralLastAutomaticPublicationAdoptionPreference)
        return defaults.synchronize()
    }

    private func centralPublicationNonceEligibleForCurrentRoot(
        _ nonce: UInt32,
        ownerID: UUID
    ) -> Bool {
        guard nonce > 0, nonce < 0x00FF_FFFF,
              centralDeferredReclaimOwnerID == ownerID else { return false }
        switch centralDeferredReclaimRootOrigin {
        case .explicitManual, .postGreenReconnect:
            return true
        case .automaticPublication:
            let notAlreadyAdopted =
                !automaticPublicationAdoptionAlreadyConsumed(
                    ownerID: ownerID, nonce: nonce)
            let notRejectedAtBoundary =
                centralAutomaticBoundaryRejectedOwnerID != ownerID
                || centralAutomaticBoundaryRejectedPublicationNonce != nonce
            return notAlreadyAdopted && notRejectedAtBoundary
        case .none:
            return false
        }
    }

    private func normalizePostGreenRootOriginForPublication(
        _ nonce: UInt32,
        ownerID: UUID
    ) {
        guard centralDeferredReclaimActive,
              centralDeferredReclaimOwnerID == ownerID,
              centralDeferredReclaimRootOrigin == .postGreenReconnect else { return }
        let greenNonce = centralLastFullGreenPublicationNonce
        guard greenNonce == nil || greenNonce != nonce else { return }
        centralDeferredReclaimRootOrigin = .automaticPublication
        append("Post-green root observed a different publication nonce; "
            + "upgraded to durable automatic adoption · old="
            + (greenNonce.map { String(format: "%06X", Int($0)) } ?? "unknown")
            + " · new="
            + String(format: "%06X", Int(nonce)))
    }

    private func centralDeferredReclaimHasPublicationAuthority(
        _ peripheral: CBPeripheral
    ) -> Bool {
        guard centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              !centralDeferredReclaimPendingTerminal,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              peripheral === geelyPeripheral,
              let rootClaim = centralDeferredReclaimRootClaimToken,
              rootClaim > 0,
              centralDeferredReclaimIssuedConnectPending,
              let issuedToken = centralDeferredReclaimIssuedConnectToken,
              issuedToken > 0,
              centralDeferredReclaimIssuedOwnerID == peripheral.identifier,
              centralDeferredReclaimIssuedNamespaceGeneration == 0x2F04,
              centralDeferredReclaimIssuedTerminalGeneration
                == centralDeferredReclaimTerminalGeneration,
              centralDeferredReclaimIssuedInvalidationGeneration
                == centralPublicationInvalidationGeneration,
              let nonce = centralDeferredReclaimPublicationNonce,
              centralPublicationNonceEligibleForCurrentRoot(
                nonce, ownerID: peripheral.identifier) else { return false }
        return true
    }

    /// A system-table entry proves the physical link but carries no publication bytes. It may be
    /// combined only with a previously accepted protocol-2 frame for this exact saved owner. For
    /// automatic adoption that frame must belong to the current terminal/invalidation boundary.
    /// Manual and post-green roots may intentionally reuse an earlier frame from this process,
    /// but a value loaded from persistence is never proof after restart or legacy downgrade.
    private func bindKnownPublicationNonceToCurrentRootIfEligible(
        _ peripheral: CBPeripheral
    ) {
        if centralLastObservedPublicationOwnerID == peripheral.identifier,
           let observedNonce = centralLastObservedPublicationNonce {
            normalizePostGreenRootOriginForPublication(
                observedNonce, ownerID: peripheral.identifier)
        }
        guard centralDeferredReclaimSystemProofObserved,
              centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimIssuedConnectPending,
              let issuedTerminal = centralDeferredReclaimIssuedTerminalGeneration,
              issuedTerminal == centralDeferredReclaimTerminalGeneration,
              let issuedInvalidation =
                centralDeferredReclaimIssuedInvalidationGeneration,
              issuedInvalidation == centralPublicationInvalidationGeneration,
              centralLastObservedPublicationOwnerID == peripheral.identifier,
              let nonce = centralLastObservedPublicationNonce,
              centralLastObservedPublicationAtUptime != nil,
              centralPublicationNonceEligibleForCurrentRoot(
                nonce, ownerID: peripheral.identifier) else { return }
        let observedInCurrentBoundary =
            centralLastObservedPublicationTerminalGeneration == issuedTerminal
            && centralLastObservedPublicationInvalidationGeneration == issuedInvalidation
        let explicitSamePublicationEpisode =
            centralDeferredReclaimRootOrigin == .explicitManual
            || centralDeferredReclaimRootOrigin == .postGreenReconnect
        guard observedInCurrentBoundary || explicitSamePublicationEpisode else { return }
        centralDeferredReclaimPublicationNonce = nonce
        append("Exact system F04 proof bound accepted protocol2 publication · nonce="
            + String(format: "%06X", Int(nonce))
            + " · rootClaim=\(centralDeferredReclaimRootClaimToken ?? 0)"
            + " · observedCurrentBoundary=\(observedInCurrentBoundary)")
    }

    /// Invalidate evidence for one submitted ordinary request without changing the lineage's
    /// one-shot budget. Terminal callbacks and Bluetooth power transitions call this routine;
    /// neither event is authority to create a new ownership claim.
    private func invalidateCentralDeferredReclaimRequestEvidence(
        keepIssuedRequest: Bool
    ) {
        centralDeferredReclaimEvidenceGeneration &+= 1
        centralDeferredReclaimProbeWorkItem?.cancel()
        centralDeferredReclaimProbeWorkItem = nil
        centralDeferredReclaimGraceWorkItem?.cancel()
        centralDeferredReclaimGraceWorkItem = nil
        centralDeferredReclaimBeaconObserved = false
        centralDeferredReclaimBeaconIssuedConnectToken = nil
        centralDeferredReclaimBeaconOwnerID = nil
        centralDeferredReclaimBeaconNamespaceGeneration = nil
        centralDeferredReclaimBeaconTerminalGeneration = nil
        centralDeferredReclaimBeaconPublicationNonce = nil
        centralDeferredReclaimBeaconRootClaimToken = nil
        centralDeferredReclaimBeaconInvalidationGeneration = nil
        centralDeferredReclaimSystemProofObserved = false
        centralDeferredReclaimProbeAttempt = 0
        centralDeferredReclaimWaitLogged = false
        cancelCentralDeferredReclaimScanCycle(stopScan: true, resetBackoff: false)
        guard !keepIssuedRequest else { return }
        centralDeferredReclaimIssuedConnectPending = false
        centralDeferredReclaimIssuedConnectToken = nil
        centralDeferredReclaimIssuedOwnerID = nil
        centralDeferredReclaimIssuedNamespaceGeneration = nil
        centralDeferredReclaimIssuedTerminalGeneration = nil
        centralDeferredReclaimIssuedInvalidationGeneration = nil
        centralDeferredReclaimEvidenceNotBeforeUptime = nil
        centralDeferredReclaimPublicationNonce = nil
    }

    private func recordCentralDeferredReclaimTerminalBoundary() {
        // CONTRACT_V42_TERMINAL_INVALIDATES_EVIDENCE_NOT_BUDGET
        clearCentralPairRestorationCapability()
        clearCentralPairTranscript(reason: "exact Central terminal boundary")
        centralDeferredReclaimTerminalGeneration &+= 1
        centralDeferredReclaimTerminalAtUptime = ProcessInfo.processInfo.systemUptime
        if centralDeferredReclaimActive {
            if centralDeferredReclaimConsumed {
                // The bounded cancel's terminal advances attempt lineage, but the spent root and
                // its consumed publication tuple must survive into the one permitted reopen.
                centralDeferredReclaimEvidenceGeneration &+= 1
                centralDeferredReclaimProbeWorkItem?.cancel()
                centralDeferredReclaimProbeWorkItem = nil
                centralDeferredReclaimGraceWorkItem?.cancel()
                centralDeferredReclaimGraceWorkItem = nil
                cancelCentralDeferredReclaimScanCycle(
                    stopScan: true, resetBackoff: false)
            } else {
                invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
            }
        }
    }

    private func clearCentralLastActualIssuedConnectProvenance() {
        centralLastActualIssuedConnectToken = nil
        centralLastActualIssuedOwnerID = nil
        centralLastActualIssuedNamespaceGeneration = nil
        centralLastActualIssuedAtUptime = nil
        centralLastActualIssuedEnabledAutoReconnect = false
    }

    private func bindCentralDeferredReclaimToLastActualConnect(
        _ peripheral: CBPeripheral,
        evidenceNotBeforeUptime: TimeInterval,
        requiresAutoReconnectDescendant: Bool,
        source: String
    ) {
        guard centralDeferredReclaimActive,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimRootClaimToken != nil,
              peripheral === geelyPeripheral,
              centralLastActualIssuedConnectToken != nil,
              centralLastActualIssuedOwnerID == peripheral.identifier,
              let currentGeneration = centralLastActualIssuedNamespaceGeneration,
              currentGeneration == 0x2F04,
              currentGeneration == managedIncomingGeneration(from: serviceUUID),
              let actualIssuedAt = centralLastActualIssuedAtUptime,
              actualIssuedAt <= evidenceNotBeforeUptime,
              !requiresAutoReconnectDescendant
                || centralLastActualIssuedEnabledAutoReconnect else { return }
        invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
        centralDeferredReclaimIssuedConnectPending = true
        centralDeferredReclaimIssuedConnectToken = centralLastActualIssuedConnectToken
        centralDeferredReclaimIssuedOwnerID = peripheral.identifier
        centralDeferredReclaimIssuedNamespaceGeneration = currentGeneration
        centralDeferredReclaimIssuedTerminalGeneration =
            centralDeferredReclaimTerminalGeneration
        centralDeferredReclaimIssuedInvalidationGeneration =
            centralPublicationInvalidationGeneration
        centralDeferredReclaimEvidenceNotBeforeUptime = evidenceNotBeforeUptime
        append("App-issued exact-owner provenance bound to ordinary reclaim · token="
            + "\(centralLastActualIssuedConnectToken ?? 0) · namespace="
            + String(format: "%04X", Int(currentGeneration))
            + " · terminalGeneration=\(centralDeferredReclaimTerminalGeneration)"
            + " · invalidationGeneration=\(centralPublicationInvalidationGeneration)"
            + " · rootClaim=\(centralDeferredReclaimRootClaimToken ?? 0)"
            + " · \(source)")
    }

    /// CONTRACT_V42_ISSUED_LINEAGE_STARTS_AFTER_ACTUAL_CONNECT: this is called only immediately
    /// after `centralManager.connect`. It records global provenance even before an ordinary
    /// lineage exists, allowing a later terminal callback to bind the descendant AutoReconnect
    /// generation. A queued intent or an already `.connecting` wrapper never reaches it.
    private func captureCentralActualIssuedConnect(_ peripheral: CBPeripheral,
                                                   autoReconnectEnabled: Bool) {
        guard peripheral === geelyPeripheral,
              let currentGeneration = managedIncomingGeneration(from: serviceUUID),
              currentGeneration == 0x2F04 else { return }
        if centralRestorationPublicationOwns(peripheral),
           centralRestorationPublicationAdoptedNonce == nil {
            clearCentralRestorationPublicationBoundary()
            append("Actual app-issued request superseded unconsumed restoration boundary")
        }
        centralDeferredReclaimIssuedConnectSerial &+= 1
        centralLastActualIssuedConnectToken = centralDeferredReclaimIssuedConnectSerial
        centralLastActualIssuedOwnerID = peripheral.identifier
        centralLastActualIssuedNamespaceGeneration = currentGeneration
        centralLastActualIssuedAtUptime = ProcessInfo.processInfo.systemUptime
        centralLastActualIssuedEnabledAutoReconnect = autoReconnectEnabled
        if centralRestorationPublicationOwns(peripheral),
           centralRestorationPublicationAdoptedNonce != nil,
           centralRestorationPublicationReopenIssued,
           centralRestorationPublicationReopenPhase == .intentQueued,
           centralDeferredReclaimActive,
           centralDeferredReclaimConsumed {
            // CONTRACT_V44_SOLE_REOPEN_TOKEN_CAPTURE_FOLLOWS_ACTUAL_CONNECT
            centralRestorationPublicationReopenPhase = .requestIssued
            centralRestorationPublicationReopenIssuedToken =
                centralLastActualIssuedConnectToken
            centralRestorationPublicationReopenIssuedTerminalGeneration =
                centralDeferredReclaimTerminalGeneration
            append("Sole restoration publication reopen token captured · token="
                + "\(centralRestorationPublicationReopenIssuedToken ?? 0) · terminalGeneration="
                + "\(centralDeferredReclaimTerminalGeneration)")
        }
        append("Actual app-local connect provenance captured · token="
            + "\(centralDeferredReclaimIssuedConnectSerial) · namespace="
            + String(format: "%04X", Int(currentGeneration))
            + " · AutoReconnect=\(autoReconnectEnabled)")
        let requestedOrigin: CentralMissingConnectRootOrigin
        if centralExplicitManualRootPending {
            requestedOrigin = .explicitManual
            centralExplicitManualRootPending = false
        } else {
            requestedOrigin = consumeCentralAutomaticRootOrigin(for: peripheral)
        }
        if !centralDeferredReclaimActive {
            beginCentralDeferredReclaimLineage(
                peripheral,
                reason: "actual centralManager.connect token "
                    + "\(centralLastActualIssuedConnectToken ?? 0)",
                origin: requestedOrigin)
        } else if case .explicitManual = requestedOrigin,
                  !centralDeferredReclaimConsumed,
                  centralDeferredReclaimOwnerID == peripheral.identifier {
            centralDeferredReclaimRootOrigin = .explicitManual
            append("Pending exact-owner root adopted typed explicit-manual origin")
        }
        if centralDeferredReclaimActive,
           centralDeferredReclaimOwnerID == peripheral.identifier,
           !centralDeferredReclaimConsumed,
           let issuedAt = centralLastActualIssuedAtUptime {
            bindCentralDeferredReclaimToLastActualConnect(
                peripheral,
                evidenceNotBeforeUptime: issuedAt,
                requiresAutoReconnectDescendant: false,
                source: "new actual centralManager.connect")
        } else if centralDeferredReclaimActive,
                  centralDeferredReclaimOwnerID == peripheral.identifier,
                  centralDeferredReclaimConsumed {
            append("Actual recovery reopen inherited spent rootClaim="
                + "\(centralDeferredReclaimRootClaimToken ?? 0); no second proof observer")
        }
    }

    private func consumeCentralAutomaticRootOrigin(
        for peripheral: CBPeripheral
    ) -> CentralMissingConnectRootOrigin {
        guard centralLastFullGreenOwnerID == peripheral.identifier,
              let greenTerminal = centralLastFullGreenTerminalGeneration,
              centralDeferredReclaimTerminalGeneration > greenTerminal else {
            return .automaticPublication
        }
        // One completed green session authorizes one future same-publication radio-loss root.
        // Keep its nonce long enough to distinguish same-publication loss from a hot update.
        centralLastFullGreenOwnerID = nil
        centralLastFullGreenTerminalGeneration = nil
        return .postGreenReconnect
    }

    private func centralDeferredReclaimHasIssuedBeaconProof(
        _ peripheral: CBPeripheral
    ) -> Bool {
        guard centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              !centralDeferredReclaimPendingTerminal,
              !centralSystemAutoReconnectActive,
              centralDeferredReclaimIssuedConnectPending,
              centralDeferredReclaimBeaconObserved,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimIssuedOwnerID == peripheral.identifier,
              peripheral === geelyPeripheral,
              peripheral.state == .connecting,
              let issuedToken = centralDeferredReclaimIssuedConnectToken,
              centralDeferredReclaimBeaconIssuedConnectToken == issuedToken,
              centralDeferredReclaimBeaconOwnerID == peripheral.identifier,
              let issuedTerminalGeneration =
                centralDeferredReclaimIssuedTerminalGeneration,
              issuedTerminalGeneration == centralDeferredReclaimTerminalGeneration,
              centralDeferredReclaimBeaconTerminalGeneration
                == issuedTerminalGeneration,
              let issuedGeneration = centralDeferredReclaimIssuedNamespaceGeneration,
              issuedGeneration == 0x2F04,
              centralDeferredReclaimBeaconNamespaceGeneration == issuedGeneration,
              issuedGeneration == managedIncomingGeneration(from: serviceUUID),
              let rootClaim = centralDeferredReclaimRootClaimToken,
              centralDeferredReclaimBeaconRootClaimToken == rootClaim,
              let issuedInvalidation =
                centralDeferredReclaimIssuedInvalidationGeneration,
              issuedInvalidation == centralPublicationInvalidationGeneration,
              centralDeferredReclaimBeaconInvalidationGeneration == issuedInvalidation,
              let publicationNonce = centralDeferredReclaimPublicationNonce,
              centralDeferredReclaimBeaconPublicationNonce == publicationNonce,
              centralPublicationNonceEligibleForCurrentRoot(
                publicationNonce, ownerID: peripheral.identifier),
              let rawSavedID = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedID = UUID(uuidString: rawSavedID),
              savedID == peripheral.identifier else { return false }
        return true
    }

    /// Begin one evidence-driven ownership lineage after an ordinary terminal callback. The
    /// lineage stays active across its cancel/reopen pair so a failed replacement can never obtain
    /// a second claim merely by producing another terminal callback.
    private func beginCentralDeferredReclaimLineage(
        _ peripheral: CBPeripheral,
        reason: String,
        origin: CentralMissingConnectRootOrigin = .automaticPublication
    ) {
        if centralDeferredReclaimActive,
           centralDeferredReclaimOwnerID == peripheral.identifier {
            return
        }
        invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
        centralDeferredReclaimPostCancelWorkItem?.cancel()
        centralDeferredReclaimPostCancelWorkItem = nil
        centralDeferredReclaimOwnerID = peripheral.identifier
        centralDeferredReclaimActive = true
        centralDeferredReclaimConsumed = false
        centralDeferredReclaimPendingTerminal = false
        centralDeferredReclaimPostCancelAttempt = 0
        centralDeferredReclaimScanCycleAttempt = 0
        centralDeferredReclaimRootClaimSerial &+= 1
        centralDeferredReclaimRootClaimToken = centralDeferredReclaimRootClaimSerial
        centralDeferredReclaimRootOrigin = origin
        centralDeferredReclaimPublicationNonce = nil
        centralDeferredReclaimBeaconPublicationNonce = nil
        centralDeferredReclaimBeaconRootClaimToken = nil
        centralDeferredReclaimIssuedInvalidationGeneration = nil
        centralDeferredReclaimBeaconInvalidationGeneration = nil
        append("Ordinary exact-owner reclaim lineage armed · owner="
            + peripheral.identifier.uuidString + " · rootClaim="
            + "\(centralDeferredReclaimRootClaimToken ?? 0) · origin=\(origin.rawValue)"
            + " · budget=1 · \(reason)")
    }

    private func clearCentralDeferredReclaimLineage() {
        invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
        cancelCentralDeferredReclaimScanCycle(stopScan: true, resetBackoff: true)
        centralDeferredReclaimPostCancelWorkItem?.cancel()
        centralDeferredReclaimPostCancelWorkItem = nil
        centralDeferredReclaimOwnerID = nil
        centralDeferredReclaimActive = false
        centralDeferredReclaimConsumed = false
        centralDeferredReclaimPendingTerminal = false
        centralDeferredReclaimPostCancelAttempt = 0
        centralDeferredReclaimRootClaimToken = nil
        centralDeferredReclaimRootOrigin = nil
        centralDeferredReclaimPublicationNonce = nil
        centralDeferredReclaimBeaconPublicationNonce = nil
        centralDeferredReclaimBeaconRootClaimToken = nil
        centralDeferredReclaimIssuedInvalidationGeneration = nil
        centralDeferredReclaimBeaconInvalidationGeneration = nil
        if centralManager != nil {
            stopCentralScanSafely(centralManager, reason: "ordinary reclaim completed")
        }
    }

    private func centralDeferredReclaimOwnsPendingRequest(_ peripheral: CBPeripheral) -> Bool {
        guard centralDeferredReclaimActive,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimRootClaimToken != nil,
              peripheral === geelyPeripheral else { return false }
        if centralDeferredConnectIntent?.peripheral === peripheral { return true }
        return centralDeferredReclaimIssuedConnectPending
            && centralDeferredReclaimIssuedConnectToken != nil
            && centralDeferredReclaimIssuedOwnerID == peripheral.identifier
            && centralDeferredReclaimIssuedNamespaceGeneration
                == managedIncomingGeneration(from: serviceUUID)
            && centralDeferredReclaimIssuedTerminalGeneration
                == centralDeferredReclaimTerminalGeneration
            && centralDeferredReclaimIssuedInvalidationGeneration
                == centralPublicationInvalidationGeneration
    }

    private func cancelCentralDeferredReclaimScanCycle(stopScan: Bool,
                                                       resetBackoff: Bool) {
        centralDeferredReclaimScanWindowWorkItem?.cancel()
        centralDeferredReclaimScanWindowWorkItem = nil
        centralDeferredReclaimScanRestartWorkItem?.cancel()
        centralDeferredReclaimScanRestartWorkItem = nil
        if resetBackoff { centralDeferredReclaimScanCycleAttempt = 0 }
        if stopScan, centralManager != nil {
            stopCentralScanSafely(centralManager,
                reason: "bounded publication-proof scan window closed")
        }
    }

    private func scheduleCentralDeferredReclaimLowDutyScan(
        _ peripheral: CBPeripheral,
        reason: String
    ) {
        guard centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              !centralDeferredReclaimPendingTerminal,
              centralDeferredReclaimIssuedConnectPending,
              centralDeferredReclaimIssuedConnectToken != nil,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              peripheral === geelyPeripheral else { return }
        centralDeferredReclaimScanWindowWorkItem?.cancel()
        centralDeferredReclaimScanWindowWorkItem = nil
        if centralManager != nil {
            stopCentralScanSafely(centralManager, reason: reason)
        }
        guard centralDeferredReclaimScanRestartWorkItem == nil else { return }
        let index = min(centralDeferredReclaimScanCycleAttempt,
                        centralDeferredReclaimScanRestartDelays.count - 1)
        let delay = centralDeferredReclaimScanRestartDelays[index]
        centralDeferredReclaimScanCycleAttempt = min(
            centralDeferredReclaimScanCycleAttempt + 1,
            centralDeferredReclaimScanRestartDelays.count - 1)
        let evidenceGeneration = centralDeferredReclaimEvidenceGeneration
        let rootClaim = centralDeferredReclaimRootClaimToken
        let issuedToken = centralDeferredReclaimIssuedConnectToken
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralDeferredReclaimScanRestartWorkItem = nil
            guard evidenceGeneration == self.centralDeferredReclaimEvidenceGeneration,
                  rootClaim == self.centralDeferredReclaimRootClaimToken,
                  issuedToken == self.centralDeferredReclaimIssuedConnectToken,
                  self.runRequested, self.role == .central,
                  self.centralDeferredReclaimActive,
                  !self.centralDeferredReclaimConsumed,
                  !self.centralDeferredReclaimPendingTerminal,
                  self.centralDeferredReclaimOwnerID == peripheral.identifier,
                  peripheral === self.geelyPeripheral,
                  self.centralManager.state == .poweredOn else { return }
            self.armCentralDeferredReclaimObservation(peripheral)
        }
        centralDeferredReclaimScanRestartWorkItem = item
        append("Publication-proof duplicate scan sleeps \(Int(delay))s · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func armCentralDeferredReclaimScanWindowTimeout(
        _ peripheral: CBPeripheral
    ) {
        guard centralDeferredReclaimScanWindowWorkItem == nil,
              centralDeferredReclaimGraceWorkItem == nil else { return }
        let evidenceGeneration = centralDeferredReclaimEvidenceGeneration
        let rootClaim = centralDeferredReclaimRootClaimToken
        let issuedToken = centralDeferredReclaimIssuedConnectToken
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralDeferredReclaimScanWindowWorkItem = nil
            guard evidenceGeneration == self.centralDeferredReclaimEvidenceGeneration,
                  rootClaim == self.centralDeferredReclaimRootClaimToken,
                  issuedToken == self.centralDeferredReclaimIssuedConnectToken,
                  self.runRequested, self.role == .central,
                  self.centralDeferredReclaimActive,
                  !self.centralDeferredReclaimConsumed,
                  !self.centralDeferredReclaimPendingTerminal,
                  self.centralDeferredReclaimOwnerID == peripheral.identifier,
                  peripheral === self.geelyPeripheral,
                  self.centralDeferredReclaimGraceWorkItem == nil else { return }
            self.scheduleCentralDeferredReclaimLowDutyScan(
                peripheral, reason: "proof scan window expired without eligible evidence")
        }
        centralDeferredReclaimScanWindowWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + centralDeferredReclaimScanWindow, execute: item)
    }

    /// The beacon observer and system-table observer run in parallel. System-table proof keeps the
    /// v41 route. v42 additionally accepts a later exact saved-ID/current-2F04 beacon, but only for
    /// a request token captured after this app actually called `centralManager.connect`.
    private func armCentralDeferredReclaimObservation(_ peripheral: CBPeripheral) {
        guard centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              !centralDeferredReclaimPendingTerminal,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimOwnsPendingRequest(peripheral),
              !centralSystemAutoReconnectActive,
              peripheral.state == .connecting
                || centralDeferredReclaimIssuedConnectPending,
              centralManager != nil,
              centralManager.state == .poweredOn else { return }
        if !centralDeferredReclaimWaitLogged {
            centralDeferredReclaimWaitLogged = true
            append("Deferred .connecting owner: exact current F04 evidence observers armed; "
                + "protocol2 publication reclaim requires app-issued/root tokens")
        }
        // CONTRACT_V43_SYSTEM_PROBE_CANNOT_BYPASS_SCAN_SLEEP: a parallel read-only F04-table
        // probe may call this method during the 2/5/10/30-second sleep. It may keep probing, but
        // only the captured restart work item may reopen the duplicate scan window.
        let scanSleepActive = centralDeferredReclaimScanRestartWorkItem != nil
        if !scanSleepActive {
            if !centralManager.isScanning {
                centralManager.scanForPeripherals(
                    withServices: [managedIncomingBeaconUUID],
                    // CONTRACT_V43_POST_ISSUE_DUPLICATE_SCAN: the first frame may have triggered
                    // the connect itself. A duplicate after issued/boundary capture is required.
                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
                )
            }
            // CONTRACT_V43_DUPLICATE_SCAN_IS_BOUNDED_LOW_DUTY: elapsed time may only stop/restart
            // read-only scan windows; it never authorizes a destructive command.
            armCentralDeferredReclaimScanWindowTimeout(peripheral)
        }
        if peripheral.state == .connecting,
           centralDeferredReclaimBeaconObserved {
            // CONTRACT_V42_STORED_BEACON_ARMS_WHEN_OWNER_BECOMES_CONNECTING: Core Bluetooth can
            // deliver the exact post-terminal beacon while its wrapper still reports
            // `.disconnected`. Keep that same-token evidence and start the grace when the
            // read-only observer later sees `.connecting`; no duplicate advertisement is needed.
            armCentralDeferredReclaimProofGrace(
                peripheral, source: "stored exact beacon after .connecting transition")
        }
        guard centralDeferredReclaimProbeWorkItem == nil,
              !centralDeferredReclaimSystemProofObserved else { return }
        let index = min(centralDeferredReclaimProbeAttempt,
                        centralDeferredReclaimProbeDelays.count - 1)
        let delay = centralDeferredReclaimProbeDelays[index]
        let evidenceGeneration = centralDeferredReclaimEvidenceGeneration
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralDeferredReclaimProbeWorkItem = nil
            guard evidenceGeneration == self.centralDeferredReclaimEvidenceGeneration,
                  self.runRequested, self.role == .central,
                  self.centralDeferredReclaimActive,
                  !self.centralDeferredReclaimConsumed,
                  !self.centralDeferredReclaimPendingTerminal,
                  self.centralDeferredReclaimOwnerID == peripheral.identifier,
                  self.centralDeferredReclaimOwnsPendingRequest(peripheral),
                  self.centralManager.state == .poweredOn else { return }
            if peripheral.state == .disconnected {
                if self.centralDeferredReclaimIssuedConnectPending {
                    self.centralDeferredReclaimProbeAttempt = min(
                        self.centralDeferredReclaimProbeAttempt + 1,
                        self.centralDeferredReclaimProbeDelays.count - 1)
                    self.armCentralDeferredReclaimObservation(peripheral)
                } else {
                    self.startCentralRouteIfPossible()
                }
                return
            }
            let exactSystemOwner = self.centralManager.retrieveConnectedPeripherals(
                withServices: [self.serviceUUID]
            ).contains(where: { $0.identifier == peripheral.identifier })
            if exactSystemOwner {
                self.observeCentralDeferredReclaimSystemProof(
                    peripheral, source: "retrieveConnectedPeripherals exact F04")
                return
            }
            self.centralDeferredReclaimProbeAttempt = min(
                self.centralDeferredReclaimProbeAttempt + 1,
                self.centralDeferredReclaimProbeDelays.count - 1)
            self.armCentralDeferredReclaimObservation(peripheral)
        }
        centralDeferredReclaimProbeWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Returns true only when a beacon is bound to an app-local request submitted earlier on the
    /// main queue. The advertisement must identify the saved owner and the exact current 2F04
    /// namespace. A queued intent, restoration request or system-owned `.connecting` state has no
    /// issued token and therefore cannot arm this path.
    private func observeCentralDeferredReclaimBeacon(
        _ peripheral: CBPeripheral,
        identity: CentralAdvertisementIdentity?,
        rssi: NSNumber
    ) -> Bool {
        let observedAtUptime = ProcessInfo.processInfo.systemUptime
        guard centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              !centralDeferredReclaimPendingTerminal,
              !centralSystemAutoReconnectActive,
              centralDeferredReclaimIssuedConnectPending,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              let owner = geelyPeripheral,
              peripheral === owner,
              owner.identifier == peripheral.identifier,
              centralDeferredReclaimOwnsPendingRequest(owner),
              let issuedToken = centralDeferredReclaimIssuedConnectToken,
              centralDeferredReclaimIssuedOwnerID == owner.identifier,
              let issuedTerminalGeneration =
                centralDeferredReclaimIssuedTerminalGeneration,
              issuedTerminalGeneration == centralDeferredReclaimTerminalGeneration,
              let issuedGeneration = centralDeferredReclaimIssuedNamespaceGeneration,
              issuedGeneration == 0x2F04,
              let identity = identity,
              identity.generation == issuedGeneration,
              identity.generation == managedIncomingGeneration(from: serviceUUID),
              let publicationNonce = identity.publicationNonce,
              centralPublicationNonceEligibleForCurrentRoot(
                publicationNonce, ownerID: owner.identifier),
              let rootClaim = centralDeferredReclaimRootClaimToken,
              let issuedInvalidation =
                centralDeferredReclaimIssuedInvalidationGeneration,
              issuedInvalidation == centralPublicationInvalidationGeneration,
              let evidenceNotBeforeUptime =
                centralDeferredReclaimEvidenceNotBeforeUptime,
              observedAtUptime >= evidenceNotBeforeUptime,
              let rawSavedID = UserDefaults.standard.string(
                forKey: savedGeelyPeripheralPreference),
              let savedID = UUID(uuidString: rawSavedID),
              savedID == peripheral.identifier else { return false }
        if !centralDeferredReclaimBeaconObserved {
            // CONTRACT_V43_PROTOCOL2_BINDS_ROOT_TOKEN_TERMINAL_AND_PUBLICATION
            centralDeferredReclaimBeaconObserved = true
            centralDeferredReclaimBeaconIssuedConnectToken = issuedToken
            centralDeferredReclaimBeaconOwnerID = owner.identifier
            centralDeferredReclaimBeaconNamespaceGeneration = issuedGeneration
            centralDeferredReclaimBeaconTerminalGeneration = issuedTerminalGeneration
            centralDeferredReclaimPublicationNonce = publicationNonce
            centralDeferredReclaimBeaconPublicationNonce = publicationNonce
            centralDeferredReclaimBeaconRootClaimToken = rootClaim
            centralDeferredReclaimBeaconInvalidationGeneration = issuedInvalidation
            append("Exact saved-owner protocol2 F04 publication observed after app-issued connect "
                + "· token=\(issuedToken) · terminalGeneration="
                + "\(issuedTerminalGeneration) · rootClaim=\(rootClaim)"
                + " · nonce=" + String(format: "%06X", Int(publicationNonce))
                + " · RSSI=\(rssi); short didConnect grace armed")
        }
        armCentralDeferredReclaimProofGrace(
            owner, source: "exact current F04 beacon + app-issued connect token")
        if centralDeferredReclaimGraceWorkItem == nil {
            armCentralDeferredReclaimObservation(owner)
        }
        return true
    }

    private func observeCentralDeferredReclaimSystemProof(_ peripheral: CBPeripheral,
                                                          source: String) {
        guard centralDeferredReclaimActive,
              !centralDeferredReclaimConsumed,
              !centralDeferredReclaimPendingTerminal,
              !centralDeferredReclaimSystemProofObserved,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimOwnsPendingRequest(peripheral),
              peripheral.state == .connecting || peripheral.state == .connected else { return }
        // CONTRACT_V41_ORDINARY_CONNECTING_REQUIRES_EXACT_F04_PROOF
        centralDeferredReclaimSystemProofObserved = true
        centralDeferredReclaimProbeWorkItem?.cancel()
        centralDeferredReclaimProbeWorkItem = nil
        append("Exact same-id F04 physical proof observed; short didConnect grace armed · "
            + source)
        bindKnownPublicationNonceToCurrentRootIfEligible(peripheral)
        armCentralDeferredReclaimProofGrace(peripheral, source: source)
    }

    /// Both eligible evidence routes converge here so the lineage owns exactly one timer and one
    /// destructive command. Beacon-only evidence is revalidated against the still-current issued
    /// token at grace expiry; a late didConnect clears the lineage before this closure can run.
    private func armCentralDeferredReclaimProofGrace(_ peripheral: CBPeripheral,
                                                      source: String) {
        // CONTRACT_V43_SYSTEM_TABLE_DOES_NOT_REPLACE_PUBLICATION_AUTHORITY: a system-table entry
        // can prove the physical ACL, but only protocol 2 supplies the Android publication nonce.
        guard centralDeferredReclaimHasPublicationAuthority(peripheral),
              centralDeferredReclaimSystemProofObserved
                || centralDeferredReclaimHasIssuedBeaconProof(peripheral) else { return }
        guard centralDeferredReclaimGraceWorkItem == nil else { return }
        cancelCentralDeferredReclaimScanCycle(stopScan: false, resetBackoff: false)
        let evidenceGeneration = centralDeferredReclaimEvidenceGeneration
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralDeferredReclaimGraceWorkItem = nil
            if self.centralManager.state != .poweredOn {
                // The old proof cannot authorize a later destructive command across a radio
                // transition. Resume the parallel read-only observers after poweredOn.
                self.centralDeferredReclaimBeaconObserved = false
                self.centralDeferredReclaimBeaconIssuedConnectToken = nil
                self.centralDeferredReclaimBeaconOwnerID = nil
                self.centralDeferredReclaimBeaconNamespaceGeneration = nil
                self.centralDeferredReclaimBeaconTerminalGeneration = nil
                self.centralDeferredReclaimBeaconPublicationNonce = nil
                self.centralDeferredReclaimBeaconRootClaimToken = nil
                self.centralDeferredReclaimBeaconInvalidationGeneration = nil
                self.centralDeferredReclaimSystemProofObserved = false
                return
            }
            guard evidenceGeneration == self.centralDeferredReclaimEvidenceGeneration,
                  self.runRequested, self.role == .central,
                  self.centralDeferredReclaimActive,
                  !self.centralDeferredReclaimConsumed,
                  !self.centralDeferredReclaimPendingTerminal,
                  self.centralDeferredReclaimOwnerID == peripheral.identifier,
                  self.centralDeferredReclaimOwnsPendingRequest(peripheral),
                  !self.centralSystemAutoReconnectActive else { return }
            if peripheral.state == .disconnected {
                self.startCentralRouteIfPossible()
                return
            }
            let systemEvidence = self.centralDeferredReclaimSystemProofObserved
                && (peripheral.state == .connecting || peripheral.state == .connected)
            let issuedBeaconEvidence = self.centralDeferredReclaimHasIssuedBeaconProof(peripheral)
            let publicationAuthority =
                self.centralDeferredReclaimHasPublicationAuthority(peripheral)
            guard publicationAuthority && (systemEvidence || issuedBeaconEvidence) else {
                self.armCentralDeferredReclaimObservation(peripheral)
                return
            }
            // CONTRACT_V41_ORDINARY_RECLAIM_ONE_CANCEL_PER_LINEAGE
            // CONTRACT_V42_BEACON_RECLAIM_ONE_CANCEL_AFTER_GRACE
            guard let consumedNonce = self.centralDeferredReclaimPublicationNonce,
                  let rootClaim = self.centralDeferredReclaimRootClaimToken else { return }
            // CONTRACT_V43_ROOT_CLAIM_CONSUMED_BEFORE_CANCEL: all proof fields and, for automatic
            // publication adoption, persistent `(owner, nonce)` are committed synchronously
            // before Core Bluetooth sees the destructive command.
            if self.centralDeferredReclaimRootOrigin == .automaticPublication {
                guard self.persistAutomaticPublicationAdoption(
                        ownerID: peripheral.identifier, nonce: consumedNonce) else {
                    self.append("Automatic publication adoption persistence failed; "
                        + "fail closed without cancel · nonce="
                        + String(format: "%06X", Int(consumedNonce)))
                    self.scheduleCentralDeferredReclaimLowDutyScan(
                        peripheral, reason: "automatic adoption persistence failed closed")
                    return
                }
            }
            self.supersedeRestorationClaimTwoWithPublicationRoot(
                "publication-root proof consumed before cancel")
            self.centralDeferredReclaimConsumed = true
            self.centralDeferredReclaimPendingTerminal = true
            self.centralDeferredReclaimIssuedConnectPending = false
            self.centralDeferredReclaimIssuedConnectToken = nil
            self.centralDeferredReclaimIssuedOwnerID = nil
            self.centralDeferredReclaimIssuedNamespaceGeneration = nil
            self.centralDeferredReclaimIssuedTerminalGeneration = nil
            self.centralDeferredReclaimIssuedInvalidationGeneration = nil
            self.centralDeferredReclaimEvidenceNotBeforeUptime = nil
            self.centralDeferredReclaimBeaconObserved = false
            self.centralDeferredReclaimBeaconIssuedConnectToken = nil
            self.centralDeferredReclaimBeaconOwnerID = nil
            self.centralDeferredReclaimBeaconNamespaceGeneration = nil
            self.centralDeferredReclaimBeaconTerminalGeneration = nil
            self.centralDeferredReclaimBeaconPublicationNonce = nil
            self.centralDeferredReclaimBeaconRootClaimToken = nil
            self.centralDeferredReclaimBeaconInvalidationGeneration = nil
            self.centralDeferredReclaimSystemProofObserved = false
            self.centralDeferredReclaimProbeWorkItem?.cancel()
            self.centralDeferredReclaimProbeWorkItem = nil
            self.clearCentralDeferredConnectIntent()
            self.stopCentralScanSafely(self.centralManager,
                                        reason: "ordinary exact F04 proof consumed")
            self.centralOwnerConfiguredForAncs = false
            self.centralSystemAutoReconnectActive = false
            self.centralHelperConfirmed = false
            self.centralB4Subscribed = false
            self.centralAncsCccdConfirmed = false
            self.centralReadinessProofWorkItem?.cancel()
            self.centralReadinessProofWorkItem = nil
            self.clearCentralRuntime(keepPeripheral: true)
            self.setStatus("CENTRAL · HOT UPDATE OWNER CLAIM 1/1", color: .systemOrange)
            let acceptedEvidence = issuedBeaconEvidence
                ? "app-issued token + exact current F04 beacon"
                : "exact F04 system table"
            self.append("Eligible F04 proof grace expired without didConnect; one bounded "
                + "ordinary reclaim cancel 1/1 · rootClaim=\(rootClaim) · nonce="
                + String(format: "%06X", Int(consumedNonce))
                + " · origin=\(self.centralDeferredReclaimRootOrigin?.rawValue ?? "unknown")"
                + " · \(acceptedEvidence) · armed by \(source)")
            self.cancelCentralConnectionSafely(
                peripheral, manager: self.centralManager,
                reason: "hot Status update omitted app-local didConnect")
            self.armCentralDeferredReclaimPostCancelObservation(peripheral)
        }
        centralDeferredReclaimGraceWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + centralDeferredReclaimProofGrace, execute: item)
    }

    /// After the single cancel, observe only the exact owner's terminal state. No timeout or
    /// repeated proof may issue another cancel.
    private func armCentralDeferredReclaimPostCancelObservation(_ peripheral: CBPeripheral) {
        // CONTRACT_V41_RECLAIM_STATE_OBSERVER_ONE_SHOT: this observer owns no cancel/connect; it
        // can only hand the already-consumed claim to the shared exact terminal reopen.
        guard centralDeferredReclaimActive,
              centralDeferredReclaimConsumed,
              centralDeferredReclaimPendingTerminal,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              centralDeferredReclaimPostCancelWorkItem == nil else { return }
        let index = min(centralDeferredReclaimPostCancelAttempt,
                        centralDeferredReclaimPostCancelDelays.count - 1)
        let delay = centralDeferredReclaimPostCancelDelays[index]
        let evidenceGeneration = centralDeferredReclaimEvidenceGeneration
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralDeferredReclaimPostCancelWorkItem = nil
            guard evidenceGeneration == self.centralDeferredReclaimEvidenceGeneration,
                  self.runRequested, self.role == .central,
                  self.centralDeferredReclaimActive,
                  self.centralDeferredReclaimConsumed,
                  self.centralDeferredReclaimPendingTerminal,
                  self.centralDeferredReclaimOwnerID == peripheral.identifier else { return }
            if peripheral.state == .disconnected {
                self.reopenCentralDeferredReclaimAfterTerminal(
                    peripheral, callback: "read-only state=.disconnected")
                return
            }
            self.centralDeferredReclaimPostCancelAttempt = min(
                self.centralDeferredReclaimPostCancelAttempt + 1,
                self.centralDeferredReclaimPostCancelDelays.count - 1)
            self.armCentralDeferredReclaimPostCancelObservation(peripheral)
        }
        centralDeferredReclaimPostCancelWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func reopenCentralDeferredReclaimAfterTerminal(_ peripheral: CBPeripheral,
                                                           callback: String) {
        guard centralDeferredReclaimActive,
              centralDeferredReclaimConsumed,
              centralDeferredReclaimPendingTerminal,
              centralDeferredReclaimOwnerID == peripheral.identifier,
              peripheral === geelyPeripheral else { return }
        let restorationPublicationAdoption =
            centralRestorationPublicationAdoptedNonce != nil
            && centralRestorationPublicationOwns(peripheral)
        if restorationPublicationAdoption {
            // CONTRACT_V44_RESTORATION_PUBLICATION_REOPENS_EXACTLY_ONCE
            guard !centralRestorationPublicationReopenIssued else { return }
            centralRestorationPublicationReopenIssued = true
            centralRestorationPublicationReopenPhase = .intentQueued
            centralRestorationPublicationReopenIssuedToken = nil
            centralRestorationPublicationReopenIssuedTerminalGeneration = nil
        }
        recordCentralDeferredReclaimTerminalBoundary()
        centralDeferredReclaimPendingTerminal = false
        centralDeferredReclaimIssuedConnectPending = false
        centralDeferredReclaimIssuedConnectToken = nil
        centralDeferredReclaimIssuedOwnerID = nil
        centralDeferredReclaimIssuedNamespaceGeneration = nil
        centralDeferredReclaimIssuedTerminalGeneration = nil
        centralDeferredReclaimEvidenceNotBeforeUptime = nil
        centralDeferredReclaimPostCancelWorkItem?.cancel()
        centralDeferredReclaimPostCancelWorkItem = nil
        centralDeferredReclaimPostCancelAttempt = 0
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        clearCentralRuntime(keepPeripheral: true)
        append((restorationPublicationAdoption
            ? "Restoration publication terminal observed"
            : "Ordinary reclaim terminal observed")
            + " · \(callback); reopen same owner with RequiresANCS, claim remains spent")
        guard runRequested, role == .central else { return }
        _ = queueCentralConnectIntent(
            peripheral,
            reason: restorationPublicationAdoption
                ? "restoration publication adoption reopen 1/1"
                : "hot-update exact-owner reclaim 1/1",
            delay: 0.25)
    }

    /// After the one v44 reopen has actually been materialized, a failed replacement never
    /// queues another app-local request for the spent `(owner, nonce)`. A system AutoReconnect
    /// descendant may continue because it does not issue a second Helper connect call.
    private func consumeCentralRestorationPublicationPostReopenTerminal(
        _ peripheral: CBPeripheral,
        callback: String,
        systemReconnecting: Bool = false
    ) -> Bool {
        guard centralRestorationPublicationReopenIssued,
              centralRestorationPublicationAdoptedNonce != nil,
              centralRestorationPublicationOwns(peripheral) else { return false }
        if centralRestorationPublicationReopenPhase == .intentQueued {
            append("Late duplicate terminal ignored; exact restoration reopen intent already "
                + "exists · \(callback)")
            return true
        }
        if centralRestorationPublicationReopenPhase == .exhausted { return true }
        guard centralRestorationPublicationReopenPhase == .requestIssued
                || centralRestorationPublicationReopenPhase == .connected,
              let issuedToken = centralRestorationPublicationReopenIssuedToken,
              issuedToken == centralLastActualIssuedConnectToken,
              let issuedTerminal =
                centralRestorationPublicationReopenIssuedTerminalGeneration,
              issuedTerminal == centralDeferredReclaimTerminalGeneration else {
            append("Restoration reopen terminal has no matching issued token; fail closed · "
                + callback)
            return true
        }
        if systemReconnecting || peripheral.state == .connecting
                || peripheral.state == .connected || peripheral.state == .disconnecting {
            // CONTRACT_V44_LATE_OLD_TERMINAL_CANNOT_DEMOTE_NEW_REOPEN: a terminal belonging to
            // the old cancel can arrive after the new request was issued. Current wrapper state
            // wins; retain RequiresANCS provenance and wait for the matching new didConnect.
            centralOwnerConfiguredForAncs = true
            if systemReconnecting { centralSystemAutoReconnectActive = true }
            setStatus("CENTRAL · ЖДУ CALLBACK SOLE REOPEN", color: .systemBlue)
            append("Late old terminal quarantined behind sole reopen token=\(issuedToken) · "
                + "state=\(peripheral.state.rawValue) · \(callback)")
            return true
        }
        guard peripheral.state == .disconnected else { return true }
        centralRestorationPublicationReopenPhase = .exhausted
        recordCentralDeferredReclaimTerminalBoundary()
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        clearCentralRuntime(keepPeripheral: true)
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        setStatus("CENTRAL · RESTORE REOPEN 1/1 ИСЧЕРПАН", color: .systemOrange)
        append("Restoration publication reopen ended in attributable disconnected state; spent "
            + "owner+nonce cannot reopen again automatically · \(callback)")
        return true
    }

    /// Record delayed connect intent synchronously, before returning from a terminal callback.
    /// No Core Bluetooth command is sent here. The first exact-owner intent wins until it is
    /// consumed or explicitly cleared by stop/role change.
    @discardableResult
    private func queueCentralConnectIntent(_ peripheral: CBPeripheral,
                                           reason: String,
                                           delay: TimeInterval = 0) -> Bool {
        guard runRequested, role == .central else { return false }
        if let current = geelyPeripheral,
           current !== peripheral {
            append("Deferred connect ignored for stale owner · \(peripheral.identifier.uuidString)")
            return false
        }
        if let existing = centralDeferredConnectIntent {
            guard existing.peripheral === peripheral else {
                append("Deferred connect already belongs to another exact owner; new intent ignored")
                return false
            }
            geelyPeripheral = existing.peripheral
            existing.peripheral.delegate = self
            armCentralDeferredConnectWake()
            return true
        }
        clearCentralPendingTerminalStateObservation()
        centralDeferredConnectToken &+= 1
        let intent = DeferredCentralConnectIntent(
            peripheral: peripheral,
            reason: reason,
            notBefore: Date().addingTimeInterval(max(0, delay)),
            token: centralDeferredConnectToken
        )
        centralDeferredConnectIntent = intent
        geelyPeripheral = peripheral
        peripheral.delegate = self
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        armCentralDeferredConnectWake()
        setStatus("CENTRAL · CONNECT ОТЛОЖЕН", color: .systemOrange)
        append("Deferred exact-owner connect retained in RAM · "
            + peripheral.identifier.uuidString + " · \(reason)")
        return true
    }

    private func armCentralDeferredConnectWake() {
        guard centralDeferredConnectWorkItem == nil,
              let intent = centralDeferredConnectIntent else { return }
        let delay = max(0, intent.notBefore.timeIntervalSinceNow)
        let token = intent.token
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.centralDeferredConnectWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralDeferredConnectIntent?.token == token else { return }
            // CONTRACT_V38_DEFERRED_WAKE_USES_NORMAL_ROUTE: the route owns the only consume.
            self.startCentralRouteIfPossible()
        }
        centralDeferredConnectWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Atomically remove one intent before entering the ordinary exact-owner connection path.
    /// Returning true means route processing is fully owned by the deferred intent, including
    /// when it is still waiting for its notBefore boundary.
    private func consumeCentralDeferredConnectIfPossible() -> Bool {
        guard let intent = centralDeferredConnectIntent else { return false }
        guard runRequested, role == .central else {
            clearCentralDeferredConnectIntent()
            return false
        }
        guard centralManager != nil, centralManager.state == .poweredOn else { return true }
        guard geelyPeripheral == nil
                || geelyPeripheral === intent.peripheral else {
            append("Deferred connect dropped: retained owner identity changed")
            clearCentralDeferredConnectIntent()
            return false
        }
        let remaining = intent.notBefore.timeIntervalSinceNow
        if remaining > 0 {
            armCentralDeferredConnectWake()
            setStatus("CENTRAL · CONNECT ОТЛОЖЕН", color: .systemOrange)
            return true
        }
        if intent.peripheral.state == .disconnecting {
            centralDeferredConnectWorkItem?.cancel()
            centralDeferredConnectWorkItem = nil
            centralDeferredConnectToken &+= 1
            let replacement = DeferredCentralConnectIntent(
                peripheral: intent.peripheral,
                reason: intent.reason,
                notBefore: Date().addingTimeInterval(0.25),
                token: centralDeferredConnectToken
            )
            centralDeferredConnectIntent = replacement
            armCentralDeferredConnectWake()
            setStatus("CENTRAL · ЖДУ DISCONNECT", color: .systemOrange)
            return true
        }
        if intent.peripheral.state == .connecting {
            // CONTRACT_V39_CONNECTING_RETAINS_SOLE_INTENT: state alone proves neither app-local
            // ownership nor a terminal boundary. CONTRACT_V41_ORDINARY_CONNECTING_EXACT_F04_PROOF
            // adds parallel read-only beacon/system-table evidence for ordinary terminal lineages;
            // elapsed time still cannot cancel or open a second owner.
            centralDeferredConnectWorkItem?.cancel()
            centralDeferredConnectWorkItem = nil
            centralDeferredConnectToken &+= 1
            let replacement = DeferredCentralConnectIntent(
                peripheral: intent.peripheral,
                reason: intent.reason,
                notBefore: Date().addingTimeInterval(1),
                token: centralDeferredConnectToken
            )
            centralDeferredConnectIntent = replacement
            armCentralDeferredConnectWake()
            if centralRestorationPublicationOwns(intent.peripheral),
               centralRestorationPublicationAdoptedNonce == nil {
                startCentralRestorationPublicationReadOnlyScan(intent.peripheral)
            } else {
                armCentralDeferredReclaimObservation(intent.peripheral)
            }
            setStatus("CENTRAL · ЖДУ CALLBACK PENDING OWNER", color: .systemOrange)
            return true
        }
        // CONTRACT_V38_POWERED_ON_SINGLE_CONSUME: clear before the normal connect path.
        centralDeferredConnectIntent = nil
        centralDeferredConnectToken &+= 1
        centralDeferredConnectWorkItem?.cancel()
        centralDeferredConnectWorkItem = nil
        geelyPeripheral = intent.peripheral
        append("Deferred exact-owner connect consumed once after poweredOn · \(intent.reason)")
        // CONTRACT_V42_CONSUMED_INTENT_IS_NOT_ISSUED_CONNECT: `connectCentral` may discover that
        // Core Bluetooth is already `.connecting` and submit no command. Only
        // `issueCentralConnect`, after its actual `centralManager.connect` call, may capture an
        // issued token. This prevents a queued/consumed intent from authorizing beacon reclaim.
        connectCentral(intent.peripheral, reason: "deferred after poweredOn · \(intent.reason)")
        return true
    }

    private func clearCentralDeferredConnectIntent() {
        centralDeferredConnectToken &+= 1
        centralDeferredConnectWorkItem?.cancel()
        centralDeferredConnectWorkItem = nil
        centralDeferredConnectIntent = nil
    }

    /// HA1208's manufacturer frame is the only publication-authoritative identity. Android keeps
    /// protocol 1 in scan-response service data solely so Helper v42 can still find F04. v43 may
    /// use that legacy frame to connect, but never to claim that a new Android publication exists.
    private func advertisedCentralIdentity(
        _ advertisementData: [String: Any]
    ) -> CentralAdvertisementIdentity? {
        if let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey]
                as? Data,
           let identity = decodeCentralPublicationIdentity(
                manufacturer, includesCompanyID: true) {
            return identity
        }
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey]
                as? [CBUUID: Data],
           let payload = serviceData[managedIncomingBeaconUUID],
           let generation = decodeCentralLegacyNamespace(
                payload, includesCompanyID: false) {
            return CentralAdvertisementIdentity(
                generation: generation, publicationNonce: nil)
        }
        return nil
    }

    private func manufacturerPayloadOffset(_ bytes: [UInt8],
                                           includesCompanyID: Bool) -> Int? {
        guard includesCompanyID else { return 0 }
        guard bytes.count >= 2 else { return nil }
        let companyID = UInt16(bytes[0]) | (UInt16(bytes[1]) << 8)
        return companyID == managedIncomingManufacturerID ? 2 : nil
    }

    private func decodeCentralPublicationIdentity(
        _ data: Data,
        includesCompanyID: Bool
    ) -> CentralAdvertisementIdentity? {
        let bytes = [UInt8](data)
        guard let offset = manufacturerPayloadOffset(
                bytes, includesCompanyID: includesCompanyID),
              // CONTRACT_V43_PROTOCOL2_FRAME_IS_EXACT: company ID is outside Android's six-byte
              // payload. Trailing bytes cannot be silently interpreted as the same protocol.
              bytes.count == offset + 6,
              bytes[offset] == managedIncomingPublicationProtocol else { return nil }
        let generation = (UInt16(bytes[offset + 1]) << 8) | UInt16(bytes[offset + 2])
        let nonce = (UInt32(bytes[offset + 3]) << 16) |
            (UInt32(bytes[offset + 4]) << 8) |
            UInt32(bytes[offset + 5])
        guard generation == 0x2F04,
              nonce > 0, nonce < 0x00FF_FFFF else { return nil }
        return CentralAdvertisementIdentity(
            generation: generation, publicationNonce: nonce)
    }

    private func decodeCentralLegacyNamespace(_ data: Data,
                                              includesCompanyID: Bool) -> UInt16? {
        let bytes = [UInt8](data)
        guard let offset = manufacturerPayloadOffset(
                bytes, includesCompanyID: includesCompanyID),
              bytes.count >= offset + 3,
              bytes[offset] == managedIncomingLegacyProtocol else { return nil }
        let generation = (UInt16(bytes[offset + 1]) << 8) | UInt16(bytes[offset + 2])
        return generation == 0 || generation == 0xFFFF ? nil : generation
    }

    private func applyCentralNamespace(_ generation: UInt16, persist: Bool = true) {
        centralNamespaceGeneration = generation
        centralServiceUUID = managedIncomingUUID(kind: 0, generation: generation)
        centralControlUUID = managedIncomingUUID(kind: 2, generation: generation)
        centralSecureUUID = managedIncomingUUID(kind: 3, generation: generation)
        centralWakeUUID = managedIncomingUUID(kind: 4, generation: generation)
        centralNamespaceResolved = true
        if persist {
            UserDefaults.standard.set(Int(generation), forKey: centralNamespacePreference)
        }
        append("Geely_ANCS namespace resolved · generation="
            + String(format: "%04X", Int(generation))
            + " · service=" + centralServiceUUID.uuidString)
    }

    private func managedIncomingUUID(kind: Int, generation: UInt16) -> CBUUID {
        CBUUID(string: String(format:
            "D2D9E4B%X-47F1-4E44-A8BB-A932FD5A%04X",
            kind, Int(generation)))
    }

    private func managedIncomingGeneration(from serviceUUID: CBUUID) -> UInt16? {
        let value = serviceUUID.uuidString.uppercased()
        let prefix = "D2D9E4B0-47F1-4E44-A8BB-A932FD5A"
        guard value.hasPrefix(prefix), value.count == 36,
              let generation = UInt16(value.suffix(4), radix: 16),
              generation != 0, generation != 0xFFFF else { return nil }
        return generation
    }

    private func connectCentral(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central else { return }
        guard centralManager != nil else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        guard centralManager.state == .poweredOn else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        cancelCentralReconnect()
        stopCentralScanSafely(centralManager, reason: "connect owner")
        if let restoredOwner = centralPairRestorationOwner,
           restoredOwner !== peripheral {
            clearCentralPairRestorationCapability()
        }
        if let previous = geelyPeripheral, previous.identifier != peripheral.identifier,
           previous.state != .disconnected {
            cancelCentralConnectionSafely(previous, manager: centralManager,
                                            reason: "replace previous owner")
        }
        geelyPeripheral = peripheral
        peripheral.delegate = self
        clearCentralRuntime(keepPeripheral: true)
        if peripheral.state == .connected {
            guard centralOwnerConfiguredForAncs else {
                // Apple explicitly requires an app-local connect even when another app/system
                // already owns the physical link. Do not cancel that healthy system connection.
                issueCentralConnect(peripheral,
                    reason: "app-local ownership of already-connected anchor · \(reason)")
                return
            }
            continueCentralConnected(peripheral)
            return
        }
        if peripheral.state == .connecting {
            // CONTRACT_V39_CONNECTING_NEVER_DUPLICATES_CONNECT: `.connecting` already represents
            // a pending Core Bluetooth request. Restoration may reconcile it only through exact
            // F04 proof -> bounded cancel -> terminal boundary -> one fresh connect.
            setStatus(centralOwnerConfiguredForAncs
                ? "CENTRAL · ЖДУ СИСТЕМНОЕ ПОДКЛЮЧЕНИЕ"
                : "CENTRAL · ЖДУ CALLBACK PENDING OWNER", color: .systemOrange)
            armCentralDeferredReclaimObservation(peripheral)
            return
        }
        if peripheral.state == .disconnecting {
            setStatus("CENTRAL · ЖДУ DISCONNECT CALLBACK", color: .systemOrange)
            return
        }

        issueCentralConnect(peripheral, reason: reason)
    }

    private func issueCentralConnect(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central else { return }
        guard centralManager != nil else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        // CONTRACT_V38_ISSUE_CONNECT_POWER_GATE: never call Core Bluetooth while unavailable.
        guard centralManager.state == .poweredOn else {
            queueCentralConnectIntent(peripheral, reason: reason)
            return
        }
        // Any app-issued request is a fresh physical ownership attempt, never a continuation of
        // the one wrapper/generation delivered by willRestoreState.
        clearCentralPairRestorationCapability()
        clearCentralPairTranscript(reason: "app-issued RequiresANCS connect")
        if let intent = centralDeferredConnectIntent {
            guard intent.peripheral === peripheral else {
                append("connect blocked: deferred intent belongs to another exact owner")
                return
            }
            clearCentralDeferredConnectIntent()
        }
        var options: [String: Any] = [
            CBConnectPeripheralOptionRequiresANCS: true,
            CBConnectPeripheralOptionNotifyOnConnectionKey: false,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: false
        ]
        let systemAutoReconnect: Bool
        if #available(iOS 17.0, *) {
            options[CBConnectPeripheralOptionEnableAutoReconnect] = true
            systemAutoReconnect = true
        } else {
            systemAutoReconnect = false
        }
        centralOwnerConfiguredForAncs = true
        centralSystemAutoReconnectActive = false
        supersedeRestorationClaimTwoWithPublicationRoot(
            "actual RequiresANCS request now owns one root claim")
        centralManager.connect(peripheral, options: options)
        // CONTRACT_V42_TOKEN_CAPTURE_FOLLOWS_ACTUAL_CONNECT_CALL. Delegate callbacks are on the
        // same main queue, so no beacon/didConnect callback can interleave before this capture.
        captureCentralActualIssuedConnect(
            peripheral, autoReconnectEnabled: systemAutoReconnect)
        if centralDeferredReclaimIssuedConnectPending {
            armCentralDeferredReclaimObservation(peripheral)
        }
        if centralRestoreFreshConnectAwaitingCallback {
            armFreshRestoreConnectProofObservation(peripheral)
        }
        setStatus("CENTRAL · ПОДКЛЮЧЕНИЕ", color: .systemBlue)
        append("connect Geely_ANCS · singleOwner=true · RequiresANCS=true"
            + " · AutoReconnect=" + (systemAutoReconnect ? "system" : "manual")
            + " · pendingConnect=system-owned/no-timeout"
            + " · \(reason)")
    }

    private func beginCentralDiscovery(_ peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .idle else { return }
        centralHandshake = .discovering
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        cancelCentralDiscoveryWork()
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        centralHelperConfirmed = false
        centralAncsCccdConfirmed = false
        lastAndroidReadAt = nil
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        peripheral.discoverServices([serviceUUID])
        setStatus("REQUIRES_ANCS · ИЩУ F04", color: .systemOrange)
        append("Single owner connected; targeted discovery stable F04 "
            + serviceUUID.uuidString)
    }

    private func stopCentralRoute(cancelConnection: Bool) {
        clearCentralPairRestorationCapability()
        cancelCentralReconnect()
        clearCentralRestorationPublicationBoundary()
        clearCentralDeferredConnectIntent()
        clearCentralDeferredReclaimLineage()
        clearCentralLastActualIssuedConnectProvenance()
        clearCentralPendingTerminalStateObservation()
        clearCentralRestorationRecovery()
        resetCentralRestoreOwnershipClaims()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        clearCentralRestoredB4Hint()
        centralHardResetReason = nil
        clearCentralDestructiveRecoveryLineage()
        centralManualReconnectPending = false
        centralExplicitManualRootPending = false
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralNamespaceResolved = false
        centralRequireFreshAdvertisement = false
        centralRejectedNamespaceGeneration = nil
        centralRestorationAwaitingPower = false
        centralReconnectFailureCount = 0
        guard centralManager != nil else {
            clearCentralRuntime(keepPeripheral: false)
            return
        }
        stopCentralScanSafely(centralManager, reason: "stop Central route")
        let previous = geelyPeripheral
        clearCentralRuntime(keepPeripheral: false)
        if cancelConnection, let previous = previous,
           previous.state != .disconnected {
            cancelCentralConnectionSafely(previous, manager: centralManager,
                                            reason: "stop Central route")
        }
    }

    private func clearCentralRuntime(keepPeripheral: Bool) {
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        cancelCentralDiscoveryWork()
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralWakeSubscriptionAttempt = 0
        centralHandshake = .idle
        centralAncsAuthorized = false
        centralAncsAccessProven = false
        centralAncsAuthorizationCallbackObserved = false
        centralAncsReadyWriteIssued = false
        centralSecureLinkReady = false
        clearCentralPairTranscript(reason: "Central runtime boundary")
        centralSecureReadAttempt = 0
        centralLinkSecurityChallengeObserved = false
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        if !keepPeripheral { geelyPeripheral = nil }
    }

    private func clearCentralAliasBinding() {
        centralAliasBound = false
        centralAliasBoundChallenge = nil
        centralAliasBoundOwnerID = nil
        centralAliasBoundService = nil
        centralAliasBoundTerminalGeneration = nil
        centralAliasBoundPublicationNonce = nil
        centralAliasBoundInvalidationGeneration = nil
        centralAliasBoundRelayCharacteristic = nil
        centralAliasBoundRelayGeneration = nil
    }

    private func clearCentralPairRehydratedLineage() {
        centralPairRehydratedOwnerID = nil
        centralPairRehydratedPublicationNonce = nil
        centralPairRehydratedService = nil
        centralPairRehydratedTerminalGeneration = nil
        centralPairRehydratedInvalidationGeneration = nil
        centralPairRehydratedRelayCharacteristic = nil
        centralPairRehydratedRelayGeneration = nil
        centralPairRehydratedCapabilityGeneration = nil
    }

    /// The capability is issued only by the exact CBCentralManager restoration callback. It is
    /// deliberately separate from the durable Q record: persisted bytes alone are never enough
    /// to claim that an arbitrary retrieved wrapper belongs to the still-live ATT publication.
    private func issueCentralPairRestorationCapability(_ peripheral: CBPeripheral) {
        centralPairRestorationGeneration &+= 1
        centralPairRestorationCapabilityGeneration = centralPairRestorationGeneration
        centralPairRestorationOwner = peripheral
        centralPairRestorationRelayCharacteristic = nil
        centralPairRestorationRelayGeneration = nil
        centralPairRestorationRelaySubscriberIDs.removeAll()
        centralPairRestorationRelayProvisional = false
        centralPairAwaitingRelayRebind = false
        clearCentralPairRehydratedLineage()
        bindFirstCentralPairRestorationRelayIfEligible()
    }

    @discardableResult
    private func bindFirstCentralPairRestorationRelayIfEligible() -> Bool {
        guard centralPairRestorationCapabilityGeneration
                == centralPairRestorationGeneration,
              centralPairRestorationRelayCharacteristic == nil,
              centralPairRestorationRelayGeneration == nil,
              centralPairRestorationOwner != nil,
              let relayLineage = exactCurrentRelayLineage() else { return false }
        centralPairRestorationRelayCharacteristic = relayLineage.characteristic
        centralPairRestorationRelayGeneration = relayLineage.generation
        centralPairRestorationRelayProvisional = !centralPairPeripheralRestoreCallbackObserved
        // This is provenance only. A subscriber snapshot is never promoted to B4/alias/ANCS.
        // If non-empty it must later contain exactly the restored owner or rehydration fails.
        centralPairRestorationRelaySubscriberIDs = telemetrySubscribers
        return true
    }

    private func clearCentralPairRestorationCapability() {
        centralPairRestorationGeneration &+= 1
        centralPairRestorationCapabilityGeneration = nil
        centralPairRestorationOwner = nil
        centralPairRestorationRelayCharacteristic = nil
        centralPairRestorationRelayGeneration = nil
        centralPairRestorationRelaySubscriberIDs.removeAll()
        centralPairRestorationRelayProvisional = false
        centralPairAwaitingRelayRebind = false
        clearCentralPairRehydratedLineage()
    }

    private func clearProvisionalCentralPairRelayBinding() {
        centralPairRestorationRelayCharacteristic = nil
        centralPairRestorationRelayGeneration = nil
        centralPairRestorationRelaySubscriberIDs.removeAll()
        centralPairRestorationRelayProvisional = false
        centralPairAwaitingRelayRebind = false
    }

    /// Clears only ephemeral transcript state. The durable Q record intentionally survives
    /// process/radio/role boundaries and is reused only if the exact owner + protocol2 nonce is
    /// proved again. Neither Q nor any derived frame is written to the journal.
    private func clearCentralPairTranscript(reason: String) {
        let hadTranscript = centralPairChallenge != nil || centralAliasBound
        centralPairChallenge = nil
        centralPairChallengeOwnerID = nil
        centralPairChallengeService = nil
        centralPairChallengeTerminalGeneration = nil
        centralPairChallengePublicationNonce = nil
        centralPairChallengeInvalidationGeneration = nil
        centralPairChallengeRelayCharacteristic = nil
        centralPairChallengeRelayGeneration = nil
        centralPairAwaitingRelayRebind = false
        clearCentralAliasBinding()
        clearCentralPairRehydratedLineage()
        centralAncsCccdConfirmed = false
        centralAncsAccessProven = false
        if hadTranscript { append("HA1211 Pair/alias transcript cleared · \(reason)") }
    }

    private func currentCentralTranscriptPublicationNonce(
        _ peripheral: CBPeripheral
    ) -> UInt32? {
        if centralLastObservedPublicationOwnerID == peripheral.identifier,
           let nonce = centralLastObservedPublicationNonce,
           nonce > 0, nonce < 0x00FF_FFFF,
           centralLastObservedPublicationAtUptime != nil,
           centralLastObservedPublicationTerminalGeneration
                == centralDeferredReclaimTerminalGeneration,
           centralLastObservedPublicationInvalidationGeneration
                == centralPublicationInvalidationGeneration {
            return nonce
        }
        if let nonce = currentRehydratedCentralPairPublicationNonce(peripheral) {
            return nonce
        }
        return nil
    }

    private func exactCurrentRelayLineage() -> (
        characteristic: CBMutableCharacteristic,
        generation: UInt64
    )? {
        guard runRequested, role == .central, servicePublished,
              let relay = telemetryCharacteristic,
              relay.uuid == telemetryRelayUUID,
              let published = publishedLocalService,
              published.uuid == telemetryRelayServiceUUID,
              let publishedGeneration = publishedLocalServiceGeneration,
              publishedGeneration == localServicePublicationGeneration,
              publishedServiceUUID == telemetryRelayServiceUUID,
              published.characteristics?.contains(where: { $0 === relay }) == true else {
            return nil
        }
        return (relay, publishedGeneration)
    }

    private func constantTimeChallengeMatches(_ left: Data, _ right: Data) -> Bool {
        guard left.count == centralPairChallengeLength,
              right.count == centralPairChallengeLength else { return false }
        let leftBytes = [UInt8](left)
        let rightBytes = [UInt8](right)
        var difference: UInt8 = 0
        for index in 0..<centralPairChallengeLength {
            difference |= leftBytes[index] ^ rightBytes[index]
        }
        return difference == 0
    }

    private func decodeCentralPairChallengeHex(_ raw: String) -> Data? {
        guard raw.count == centralPairChallengeLength * 2,
              raw == raw.uppercased() else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(centralPairChallengeLength)
        for offset in stride(from: 0, to: raw.count, by: 2) {
            let start = raw.index(raw.startIndex, offsetBy: offset)
            let end = raw.index(start, offsetBy: 2)
            guard let byte = UInt8(raw[start..<end], radix: 16) else { return nil }
            bytes.append(byte)
        }
        guard bytes.count == centralPairChallengeLength else { return nil }
        return Data(bytes)
    }

    private func storedCentralPairChallengeRecord(
        _ defaults: UserDefaults = .standard
    ) -> (ownerID: UUID, publicationNonce: UInt32, challenge: Data)? {
        guard let record = defaults.string(forKey: centralPairChallengePreference) else {
            return nil
        }
        let fields = record.split(separator: "|", omittingEmptySubsequences: false)
        guard fields.count == 3,
              let recordedOwner = UUID(uuidString: String(fields[0])),
              fields[1].count == 6,
              String(fields[1]) == String(fields[1]).uppercased(),
              let recordedNonce = UInt32(fields[1], radix: 16),
              recordedNonce > 0, recordedNonce < 0x00FF_FFFF,
              let recordedChallenge = decodeCentralPairChallengeHex(String(fields[2])),
              recordedChallenge.count == centralPairChallengeLength else { return nil }
        return (recordedOwner, recordedNonce, recordedChallenge)
    }

    private func currentRehydratedCentralPairPublicationNonce(
        _ peripheral: CBPeripheral
    ) -> UInt32? {
        guard peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralOwnerConfiguredForAncs,
              centralPairRehydratedOwnerID == peripheral.identifier,
              let nonce = centralPairRehydratedPublicationNonce,
              nonce > 0, nonce < 0x00FF_FFFF,
              let service = centralPairRehydratedService,
              service === centralService,
              service.uuid == serviceUUID,
              !(centralInvalidatedF04Service.map { $0 === service } ?? false),
              peripheral.services?.contains(where: { $0 === service }) == true,
              centralPairRehydratedTerminalGeneration
                == centralDeferredReclaimTerminalGeneration,
              centralPairRehydratedInvalidationGeneration
                == centralPublicationInvalidationGeneration,
              centralPairRehydratedCapabilityGeneration
                == centralPairRestorationGeneration,
              let relayLineage = exactCurrentRelayLineage(),
              centralPairRehydratedRelayCharacteristic === relayLineage.characteristic,
              centralPairRehydratedRelayGeneration == relayLineage.generation else {
            return nil
        }
        return nonce
    }

    /// Rehydrates only transient lineage for the exact wrapper delivered by willRestoreState.
    /// It neither invents advertisement evidence nor allocates a Q. Absence/ambiguity is a
    /// fail-closed wait for a real publication or physical reconnect boundary.
    private func rehydrateCentralPairPublicationIfEligible(
        _ peripheral: CBPeripheral
    ) -> UInt32? {
        guard let capabilityGeneration = centralPairRestorationCapabilityGeneration,
              capabilityGeneration == centralPairRestorationGeneration,
              let restoredOwner = centralPairRestorationOwner,
              restoredOwner === peripheral,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralOwnerConfiguredForAncs,
              let service = centralService,
              service.uuid == serviceUUID,
              !(centralInvalidatedF04Service.map { $0 === service } ?? false),
              peripheral.services?.contains(where: { $0 === service }) == true,
              let control = centralControlCharacteristic,
              let secure = centralSecureCharacteristic,
              let wake = centralWakeCharacteristic,
              let characteristics = service.characteristics,
              characteristics.contains(where: { $0 === control }),
              characteristics.contains(where: { $0 === secure }),
              characteristics.contains(where: { $0 === wake }),
              control.uuid == centralControlUUID,
              control.properties.contains(.write),
              secure.uuid == centralSecureUUID,
              secure.properties.contains(.read),
              secure.properties.contains(.write),
              wake.uuid == centralWakeUUID,
              wake.properties.contains(.notify),
              let relayLineage = exactCurrentRelayLineage(),
              let durable = storedCentralPairChallengeRecord(),
              durable.ownerID == peripheral.identifier,
              centralLastObservedPublicationOwnerID == peripheral.identifier,
              centralLastObservedPublicationNonce == durable.publicationNonce else {
            return nil
        }
        guard let restoredRelay = centralPairRestorationRelayCharacteristic,
              restoredRelay === relayLineage.characteristic,
              centralPairRestorationRelayGeneration == relayLineage.generation else { return nil }
        if !centralPairRestorationRelaySubscriberIDs.isEmpty {
            guard centralPairRestorationRelaySubscriberIDs == Set([peripheral.identifier]) else {
                return nil
            }
        }
        centralPairRehydratedOwnerID = peripheral.identifier
        centralPairRehydratedPublicationNonce = durable.publicationNonce
        centralPairRehydratedService = service
        centralPairRehydratedTerminalGeneration = centralDeferredReclaimTerminalGeneration
        centralPairRehydratedInvalidationGeneration = centralPublicationInvalidationGeneration
        centralPairRehydratedRelayCharacteristic = relayLineage.characteristic
        centralPairRehydratedRelayGeneration = relayLineage.generation
        centralPairRehydratedCapabilityGeneration = capabilityGeneration
        // One-shot consume. Keep the generation value so the rehydrated tuple remains comparable,
        // but no second wrapper or later object can consume the callback again.
        centralPairRestorationCapabilityGeneration = nil
        centralPairRestorationOwner = nil
        clearCentralAliasBinding()
        return durable.publicationNonce
    }

    private func durableCentralPairChallenge(
        ownerID: UUID,
        publicationNonce: UInt32
    ) -> Data? {
        let defaults = UserDefaults.standard
        let previousRecord = defaults.string(forKey: centralPairChallengePreference)
        if previousRecord != nil {
            guard let recorded = storedCentralPairChallengeRecord(defaults) else {
                append("HA1211 durable Pair challenge malformed; fail closed without B2 write")
                return nil
            }
            if recorded.ownerID == ownerID,
               recorded.publicationNonce == publicationNonce {
                return recorded.challenge
            }
        }

        var bytes = [UInt8](repeating: 0, count: centralPairChallengeLength)
        let randomStatus = bytes.withUnsafeMutableBytes { buffer -> Int32 in
            guard let baseAddress = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, centralPairChallengeLength, baseAddress)
        }
        guard randomStatus == errSecSuccess else {
            append("HA1211 secure Pair challenge generation failed; fail closed without B2 write")
            return nil
        }
        let challenge = Data(bytes)
        let challengeHex = bytes.map { String(format: "%02X", $0) }.joined()
        let record = ownerID.uuidString + "|"
            + String(format: "%06X", Int(publicationNonce)) + "|" + challengeHex
        defaults.set(record, forKey: centralPairChallengePreference)
        let synchronized = defaults.synchronize()
        let verified = storedCentralPairChallengeRecord(defaults)
        guard synchronized,
              let verified = verified,
              verified.ownerID == ownerID,
              verified.publicationNonce == publicationNonce,
              constantTimeChallengeMatches(verified.challenge, challenge) else {
            if let previousRecord = previousRecord {
                defaults.set(previousRecord, forKey: centralPairChallengePreference)
            } else {
                defaults.removeObject(forKey: centralPairChallengePreference)
            }
            _ = defaults.synchronize()
            append("HA1211 Pair challenge persist/reread barrier failed; "
                + "fail closed without B2 write")
            return nil
        }
        return verified.challenge
    }

    private func centralPairTranscriptMatches(_ peripheral: CBPeripheral) -> Bool {
        guard peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralOwnerConfiguredForAncs,
              let challenge = centralPairChallenge,
              challenge.count == centralPairChallengeLength,
              centralPairChallengeOwnerID == peripheral.identifier,
              let service = centralPairChallengeService,
              service === centralService,
              service.uuid == serviceUUID,
              peripheral.services?.contains(where: { $0 === service }) == true,
              centralPairChallengeTerminalGeneration
                == centralDeferredReclaimTerminalGeneration,
              let publicationNonce = currentCentralTranscriptPublicationNonce(peripheral),
              centralPairChallengePublicationNonce == publicationNonce,
              centralPairChallengeInvalidationGeneration
                == centralPublicationInvalidationGeneration,
              let relayLineage = exactCurrentRelayLineage(),
              centralPairChallengeRelayCharacteristic === relayLineage.characteristic,
              centralPairChallengeRelayGeneration == relayLineage.generation else { return false }
        return true
    }

    private func ensureCentralPairChallenge(_ peripheral: CBPeripheral) -> Data? {
        if centralPairTranscriptMatches(peripheral) { return centralPairChallenge }
        clearCentralPairTranscript(reason: "new exact owner/F04 publication transcript")
        guard peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralOwnerConfiguredForAncs,
              let service = centralService,
              service.uuid == serviceUUID,
              peripheral.services?.contains(where: { $0 === service }) == true,
              let publicationNonce = currentCentralTranscriptPublicationNonce(peripheral)
                ?? rehydrateCentralPairPublicationIfEligible(peripheral),
              let relayLineage = exactCurrentRelayLineage(),
              let challenge = durableCentralPairChallenge(
                ownerID: peripheral.identifier,
                publicationNonce: publicationNonce) else { return nil }
        centralPairChallenge = challenge
        centralPairChallengeOwnerID = peripheral.identifier
        centralPairChallengeService = service
        centralPairChallengeTerminalGeneration = centralDeferredReclaimTerminalGeneration
        centralPairChallengePublicationNonce = publicationNonce
        centralPairChallengeInvalidationGeneration = centralPublicationInvalidationGeneration
        centralPairChallengeRelayCharacteristic = relayLineage.characteristic
        centralPairChallengeRelayGeneration = relayLineage.generation
        return challenge
    }

    private func centralAliasBindingMatches(
        _ peripheral: CBPeripheral,
        challenge: Data
    ) -> Bool {
        guard centralAliasBound,
              centralPairTranscriptMatches(peripheral),
              let boundChallenge = centralAliasBoundChallenge,
              constantTimeChallengeMatches(boundChallenge, challenge),
              centralAliasBoundOwnerID == peripheral.identifier,
              centralAliasBoundService === centralPairChallengeService,
              centralAliasBoundTerminalGeneration
                == centralPairChallengeTerminalGeneration,
              centralAliasBoundPublicationNonce == centralPairChallengePublicationNonce,
              centralAliasBoundInvalidationGeneration
                == centralPairChallengeInvalidationGeneration,
              centralAliasBoundRelayCharacteristic
                === centralPairChallengeRelayCharacteristic,
              centralAliasBoundRelayGeneration == centralPairChallengeRelayGeneration else {
            return false
        }
        return true
    }

    @discardableResult
    private func bindCentralAlias(
        _ peripheral: CBPeripheral,
        challenge: Data
    ) -> Bool {
        if centralAliasBound {
            return centralAliasBindingMatches(peripheral, challenge: challenge)
        }
        guard centralPairTranscriptMatches(peripheral),
              let currentChallenge = centralPairChallenge,
              constantTimeChallengeMatches(currentChallenge, challenge),
              !centralAncsAccessProven,
              !centralAncsCccdConfirmed else { return false }
        centralAliasBound = true
        centralAliasBoundChallenge = currentChallenge
        centralAliasBoundOwnerID = peripheral.identifier
        centralAliasBoundService = centralPairChallengeService
        centralAliasBoundTerminalGeneration = centralPairChallengeTerminalGeneration
        centralAliasBoundPublicationNonce = centralPairChallengePublicationNonce
        centralAliasBoundInvalidationGeneration = centralPairChallengeInvalidationGeneration
        centralAliasBoundRelayCharacteristic = centralPairChallengeRelayCharacteristic
        centralAliasBoundRelayGeneration = centralPairChallengeRelayGeneration
        return true
    }

    private func centralProofChallenge(
        from value: Data?,
        opcode: UInt8
    ) -> Data? {
        guard let value = value,
              value.count == centralProofFrameLength,
              value.first == opcode else { return nil }
        let challenge = Data(value.dropFirst())
        return challenge.count == centralPairChallengeLength ? challenge : nil
    }

    private func centralProofRequestIsCurrent(
        _ request: CBATTRequest,
        challenge: Data
    ) -> Bool {
        guard runRequested, role == .central,
              request.offset == 0,
              let owner = geelyPeripheral,
              owner.state == .connected,
              centralOwnerConfiguredForAncs,
              request.central.identifier == owner.identifier,
              telemetrySubscribers.contains(owner.identifier),
              let relayLineage = exactCurrentRelayLineage(),
              request.characteristic === relayLineage.characteristic,
              centralPairTranscriptMatches(owner),
              centralPairChallengeRelayGeneration == relayLineage.generation,
              let currentChallenge = centralPairChallenge,
              constantTimeChallengeMatches(currentChallenge, challenge),
              centralSecureLinkReady,
              centralHandshake == .ready,
              centralAncsReadyWriteIssued,
              centralHelperConfirmed,
              centralB4Subscribed else { return false }
        return true
    }

    private func centralTelemetryValidity() -> (battery: Bool, network: Bool) {
        let snapshot = captureTelemetrySnapshot()
        return (snapshot.batteryLevel <= 100, snapshot.networkCode != 0)
    }

    private func centralReadyForGreen() -> Bool {
        let valid = centralTelemetryValidity()
        let effectiveAncsAccess = centralAncsAuthorized || centralAncsAccessProven
        let exactAliasBound: Bool
        if let peripheral = geelyPeripheral,
           let challenge = centralPairChallenge {
            exactAliasBound = centralAliasBindingMatches(
                peripheral, challenge: challenge)
        } else {
            exactAliasBound = false
        }
        return geelyPeripheral?.state == .connected
            && centralSecureLinkReady
            && centralHandshake == .ready
            && centralAncsReadyWriteIssued
            && centralHelperConfirmed
            && exactAliasBound
            && effectiveAncsAccess
            && centralAncsCccdConfirmed
            && centralB4Subscribed
            && valid.battery
            && valid.network
    }

    private func refreshCentralReadiness(_ source: String) {
        guard runRequested, role == .central else { return }
        let valid = centralTelemetryValidity()
        if centralReadyForGreen() {
            // CONTRACT_V40_FULL_PROOF_REARMS_BUDGET: didConnect, service discovery, B3 alone, or
            // Helper ACK alone cannot re-arm cancellation. Only the complete current-owner proof
            // accepted by `centralReadyForGreen` starts another automatic recovery lineage.
            if let peripheral = geelyPeripheral {
                rearmCentralDestructiveRecoveryAfterFullProof(
                    peripheral, source: "B3 + ANCS-READY + Android ANCS/B4 CCCD proof")
            }
            centralReconnectFailureCount = 0
            centralReadinessProofWorkItem?.cancel()
            centralReadinessProofWorkItem = nil
            setStatus("ANCS + B4 + ДАННЫЕ АКТИВНЫ", color: .systemGreen)
        } else {
            var missing: [String] = []
            if !centralHelperConfirmed { missing.append("Helper ACK") }
            if !centralAliasBound { missing.append("LINK-BOUND") }
            if !(centralAncsAuthorized || centralAncsAccessProven) {
                missing.append(centralAncsAuthorizationCallbackObserved
                    ? "iPhone ANCS permission" : "ANCS access proof")
            }
            if !centralAncsCccdConfirmed { missing.append("ANCS CCCD") }
            if !centralB4Subscribed { missing.append("B4 CCCD") }
            if !valid.battery { missing.append("battery") }
            if !valid.network { missing.append("network") }
            setStatus("ЖДУ: " + missing.joined(separator: " + "), color: .systemOrange)
        }
        append("Readiness · \(source) · helper=\(centralHelperConfirmed)"
            + " · aliasBound=\(centralAliasBound)"
            + " · authorized=\(centralAncsAuthorized)"
            + " · accessProof=\(centralAncsAccessProven)"
            + " · ancsCCCD=\(centralAncsCccdConfirmed) · b4CCCD=\(centralB4Subscribed)"
            + " · battery=\(valid.battery) · network=\(valid.network)")
        updateTelemetryLabel()
    }

    private func confirmCentralB4Subscription(_ source: String) {
        guard runRequested, role == .central else { return }
        let firstProof = !centralB4Subscribed
        centralB4Subscribed = true
        if firstProof { append("Android подписался на B4 CCCD · \(source)") }
        refreshCentralReadiness(source)
    }

    private func confirmCentralAncsReady(_ source: String) {
        guard runRequested, role == .central,
              let peripheral = geelyPeripheral,
              peripheral.state == .connected,
              let challenge = centralPairChallenge,
              centralAliasBindingMatches(peripheral, challenge: challenge),
              centralSecureLinkReady,
              centralHandshake == .ready,
              centralAncsReadyWriteIssued,
              centralHelperConfirmed,
              centralB4Subscribed else { return }
        let firstProof = !centralAncsCccdConfirmed
        centralAncsCccdConfirmed = true
        centralAncsAccessProven = true
        if firstProof { append("Android подтвердил обе ANCS CCCD · \(source)") }
        refreshCentralReadiness(source)
    }

    private func observeCentralReadiness(_ reason: String,
                                         timeout: TimeInterval? = nil) {
        guard runRequested, role == .central, centralHelperConfirmed else { return }
        centralReadinessProofWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.centralReadinessProofWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralHelperConfirmed, !self.centralReadyForGreen() else { return }
            self.append("Полная готовность ещё не доказана после \(reason); один owner сохраняю")
            self.refreshCentralReadiness("timeout after \(reason)")
        }
        centralReadinessProofWorkItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + (timeout ?? centralReadinessProofTimeout), execute: item)
    }

    private func scheduleCentralReconnect(reason: String) {
        guard runRequested, role == .central, centralReconnectWorkItem == nil else { return }
        clearCentralRuntime(keepPeripheral: true)
        let delayIndex = min(centralReconnectFailureCount,
                             centralReconnectDelays.count - 1)
        let delay = centralReconnectDelays[delayIndex]
        centralReconnectFailureCount = min(centralReconnectFailureCount + 1,
                                           centralReconnectDelays.count - 1)
        centralReconnectToken &+= 1
        let token = centralReconnectToken
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            guard token == self.centralReconnectToken else { return }
            self.centralReconnectWorkItem = nil
            guard self.runRequested, self.role == .central else { return }
            self.startCentralRouteIfPossible()
        }
        centralReconnectWorkItem = item
        setStatus("CENTRAL · ПЕРЕПОДКЛЮЧЕНИЕ", color: .systemOrange)
        append("Central reconnect через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// A non-auto-reconnecting terminal callback ends the previous connect request. Materialize
    /// the replacement as exact-owner data immediately; a matching late didConnect may consume
    /// it before its backoff expires.
    private func queueTerminalCentralReconnect(_ peripheral: CBPeripheral, reason: String) {
        // A callback produced by our own bounded reclaim must not create a new budget. That path
        // is consumed by `reopenCentralDeferredReclaimAfterTerminal` before reaching this method.
        // Any later terminal before a successful didConnect retains the already-spent lineage.
        if !centralDeferredReclaimActive {
            let origin: CentralMissingConnectRootOrigin
            if centralExplicitManualRootPending {
                origin = .explicitManual
                centralExplicitManualRootPending = false
            } else {
                origin = consumeCentralAutomaticRootOrigin(for: peripheral)
            }
            beginCentralDeferredReclaimLineage(
                peripheral,
                reason: reason,
                origin: origin)
        }
        // CONTRACT_V42_TERMINAL_BINDS_DESCENDANT_AUTORECONNECT_PROVENANCE: the actual app-local
        // connect may predate this ordinary lineage. Keep its global token and require a fresh
        // exact beacon observed after this terminal generation before reclaim is eligible.
        bindCentralDeferredReclaimToLastActualConnect(
            peripheral,
            evidenceNotBeforeUptime: centralDeferredReclaimTerminalAtUptime,
            requiresAutoReconnectDescendant: true,
            source: "descendant AutoReconnect after ordinary terminal")
        let delayIndex = min(centralReconnectFailureCount,
                             centralReconnectDelays.count - 1)
        let delay = centralReconnectDelays[delayIndex]
        centralReconnectFailureCount = min(centralReconnectFailureCount + 1,
                                           centralReconnectDelays.count - 1)
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        _ = queueCentralConnectIntent(peripheral,
            reason: "terminal exact-owner reconnect · \(reason)", delay: delay)
        append("Terminal callback materialized one exact-owner connect intent через "
            + "\(Int(delay)) с · \(reason)")
    }

    private func cancelCentralReconnect() {
        centralReconnectToken &+= 1
        centralReconnectWorkItem?.cancel()
        centralReconnectWorkItem = nil
    }

    private func clearCentralFreshF04ValidationState() {
        centralFreshF04ValidationWorkItem?.cancel()
        centralFreshF04ValidationWorkItem = nil
        centralFreshF04ValidationAttempt = 0
        centralFreshF04ValidationLastLogAt = Date.distantPast
        centralInvalidatedF04Service = nil
        centralFreshF04ValidationPending = false
    }

    private func clearCentralDestructiveRecoveryLineage() {
        centralDestructiveRecoveryOwnerID = nil
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        clearCentralFreshF04ValidationState()
    }

    /// CONTRACT_V42_RECLAIM_BUDGET_REARMS_ONLY_TRUSTED_BOUNDARY: ordinary terminal, power and
    /// didConnect callbacks preserve the current one-shot budget. Only a fully proven session,
    /// validated replacement F04, explicit manual action, or a genuinely different owner/route
    /// clears it.
    private func rearmCentralDeferredReclaimAfterTrustedBoundary(
        _ peripheral: CBPeripheral,
        source: String
    ) {
        guard centralDeferredReclaimActive,
              centralDeferredReclaimOwnerID == peripheral.identifier else { return }
        clearCentralDeferredReclaimLineage()
        append("Trusted boundary re-armed ordinary exact-owner reclaim budget · \(source)")
    }

    /// A different persisted CBPeripheral is a genuinely new physical lineage. Ordinary
    /// terminal callbacks for the same identifier are not: Core Bluetooth can keep returning the
    /// same stale F04 database through an arbitrary number of disconnect/reconnect callbacks.
    private func bindCentralDestructiveRecoveryLineage(_ peripheral: CBPeripheral,
                                                       source: String) {
        guard centralDestructiveRecoveryOwnerID != peripheral.identifier else { return }
        if centralDeferredReclaimActive,
           centralDeferredReclaimOwnerID != peripheral.identifier {
            clearCentralDeferredReclaimLineage()
        }
        centralDestructiveRecoveryOwnerID = peripheral.identifier
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        clearCentralFreshF04ValidationState()
        append("Destructive recovery lineage bound · owner="
            + peripheral.identifier.uuidString + " · budget=1 · \(source)")
    }

    /// Service Changed is the only automatic signal that the retained owner can expose a new
    /// exact F04 publication. It starts a new namespace lineage without replacing the owner.
    private func rearmCentralDestructiveRecoveryForFreshF04(_ peripheral: CBPeripheral,
                                                            source: String) {
        bindCentralDestructiveRecoveryLineage(peripheral, source: source)
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        append("Fresh exact F04 publication re-armed destructive recovery budget · \(source)")
    }

    /// Invalidation alone cannot create a new recovery budget. Validate a different exact
    /// CBService object and its complete required characteristic set first.
    private func rearmCentralDestructiveRecoveryForValidatedFreshF04(
        _ peripheral: CBPeripheral,
        service: CBService,
        source: String
    ) {
        guard centralFreshF04ValidationPending,
              let invalidated = centralInvalidatedF04Service,
              service !== invalidated else { return }
        // CONTRACT_V41_VALIDATED_NEW_F04_OBJECT_REARMS_BUDGET
        clearCentralFreshF04ValidationState()
        rearmCentralDeferredReclaimAfterTrustedBoundary(
            peripheral, source: "validated new exact CBService + B2/B3/B4")
        rearmCentralDestructiveRecoveryForFreshF04(
            peripheral, source: "validated new exact CBService + B2/B3/B4 · \(source)")
    }

    /// A completed current session is also a safe lineage boundary: B3 proved encryption,
    /// ANCS-READY was accepted, Android proved both real ANCS CCCDs on the same owner, F05/B4 is
    /// subscribed, and live telemetry is valid. Mere didConnect/F04 discovery never calls this.
    private func rearmCentralDestructiveRecoveryAfterFullProof(_ peripheral: CBPeripheral,
                                                               source: String) {
        bindCentralDestructiveRecoveryLineage(peripheral, source: source)
        clearCentralFreshF04ValidationState()
        centralLastFullGreenOwnerID = peripheral.identifier
        centralLastFullGreenTerminalGeneration = centralDeferredReclaimTerminalGeneration
        if centralDeferredReclaimOwnerID == peripheral.identifier,
           let nonce = centralDeferredReclaimPublicationNonce {
            centralLastFullGreenPublicationNonce = nonce
        } else if centralLastObservedPublicationOwnerID == peripheral.identifier {
            centralLastFullGreenPublicationNonce = centralLastObservedPublicationNonce
        } else {
            centralLastFullGreenPublicationNonce = nil
        }
        if centralRestorationPublicationOwns(peripheral),
           centralRestorationPublicationReopenPhase == .connected {
            append("Full green proof retired sole-reopen late-terminal quarantine")
            clearCentralRestorationPublicationBoundary()
        }
        rearmCentralDeferredReclaimAfterTrustedBoundary(peripheral, source: source)
        guard centralDestructiveRecoveryConsumed
                || centralDestructiveRecoveryWaitingForFreshF04 else { return }
        centralDestructiveRecoveryConsumed = false
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = nil
        append("Current owner fully proven; destructive recovery budget re-armed · \(source)")
    }

    /// Once the single automatic cancel has been spent, keep the physical owner intact. A manual
    /// reconnect remains available, but automatic code waits for an exact F04 invalidation/new
    /// publication instead of producing a didConnect -> UUID-not-allowed -> cancel storm.
    private func waitForFreshCentralF04Publication(reason: String) {
        cancelCentralReconnect()
        cancelCentralDiscoveryWork()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralHardResetReason = nil
        centralDestructiveRecoveryWaitingForFreshF04 = true
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralAncsAccessProven = false
        centralAncsReadyWriteIssued = false
        centralSecureLinkReady = false
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralHandshake = .discovering
        setStatus("CENTRAL · ЖДУ SERVICE CHANGED", color: .systemOrange)
        append("Destructive recovery budget 1/1 исчерпан; owner сохранён без cancel · "
            + "жду Service Changed/new exact F04 publication или ручной reconnect · \(reason)"
            + " · first=" + (centralDestructiveRecoveryFirstReason ?? "unknown"))
    }

    /// A protocol failure after didConnect may justify one controlled reconnect of this same
    /// stable owner. Identity, pending reconnect contract, F04/F05 UUIDs and the saved pair are
    /// retained; ordinary `.connecting` and radio-loss paths never call this method.
    private func resetCentralLink(reason: String) {
        guard runRequested, role == .central, centralManager != nil else { return }
        if centralHardResetReason != nil { return }
        if let peripheral = geelyPeripheral {
            bindCentralDestructiveRecoveryLineage(peripheral, source: "protocol recovery")
        }
        guard !centralDestructiveRecoveryConsumed else {
            waitForFreshCentralF04Publication(reason: reason)
            return
        }
        // CONTRACT_V40_ONE_DESTRUCTIVE_RECOVERY_PER_LINEAGE: this flag is deliberately not
        // cleared by didDisconnect, didFailToConnect, or the replacement didConnect.
        centralDestructiveRecoveryConsumed = true
        centralDestructiveRecoveryWaitingForFreshF04 = false
        centralDestructiveRecoveryFirstReason = reason
        cancelCentralReconnect()
        clearCentralRestorationRecovery()
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralHardResetReason = reason
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        setStatus("CENTRAL · ВОССТАНАВЛИВАЮ OWNER", color: .systemOrange)
        append("Один controlled reconnect 1/1 того же F04 owner · \(reason)")
        guard let peripheral = geelyPeripheral else {
            centralHardResetReason = nil
            scheduleCentralReconnect(reason: "resolve stable owner after \(reason)")
            return
        }
        if peripheral.state == .disconnected {
            centralHardResetReason = nil
            centralOwnerConfiguredForAncs = false
            issueCentralConnect(peripheral, reason: "controlled recovery · \(reason)")
            return
        }
        cancelCentralConnectionSafely(peripheral, manager: centralManager,
                                        reason: "controlled stable-owner recovery · \(reason)")
    }

    private func isCentralEncryptionError(_ error: Error) -> Bool {
        let value = error as NSError
        guard value.domain == CBATTErrorDomain else { return false }
        return value.code == CBATTError.Code.insufficientEncryption.rawValue
            || value.code == CBATTError.Code.insufficientEncryptionKeySize.rawValue
            || value.code == CBATTError.Code.insufficientAuthentication.rawValue
    }

    private func isCentralInvalidHandleError(_ error: Error) -> Bool {
        let value = error as NSError
        if value.domain == CBATTErrorDomain {
            return value.code == CBATTError.Code.invalidHandle.rawValue
                || value.code == CBATTError.Code.attributeNotFound.rawValue
        }
        return value.domain == CBErrorDomain
            && value.code == CBError.Code.invalidHandle.rawValue
    }

    private func isCentralUuidNotAllowedError(_ error: Error) -> Bool {
        let value = error as NSError
        return value.domain == CBErrorDomain
            && value.code == CBError.Code.uuidNotAllowed.rawValue
    }

    private func cancelCentralDiscoveryWork() {
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralCharacteristicDiscoveryWorkItem = nil
        centralServiceRediscoveryWorkItem?.cancel()
        centralServiceRediscoveryWorkItem = nil
        centralFreshF04ValidationWorkItem?.cancel()
        centralFreshF04ValidationWorkItem = nil
        centralFreshF04ValidationAttempt = 0
    }

    /// Core Bluetooth rejects overlapping discovery calls with CBError.uuidNotAllowed on this
    /// firmware. Always leave the delegate callback first, then issue one unfiltered request.
    private func scheduleCentralCharacteristicDiscovery(_ peripheral: CBPeripheral,
                                                        service: CBService,
                                                        reason: String,
                                                        delay: TimeInterval = 0.25) {
        guard runRequested, role == .central,
              (!centralDestructiveRecoveryWaitingForFreshF04
                || centralFreshF04ValidationPending),
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              centralService === service else { return }
        centralCharacteristicDiscoveryWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self, weak peripheral, weak service] in
            guard let self = self, let peripheral = peripheral, let service = service else {
                return
            }
            self.centralCharacteristicDiscoveryWorkItem = nil
            guard self.runRequested, self.role == .central,
                  (!self.centralDestructiveRecoveryWaitingForFreshF04
                    || self.centralFreshF04ValidationPending),
                  peripheral === self.geelyPeripheral,
                  peripheral.state == .connected,
                  self.centralHandshake == .discovering,
                  self.centralService === service,
                  peripheral.services?.contains(where: { $0 === service }) == true else { return }
            self.centralCharacteristicDiscoveryAttempt += 1
            self.append("D2D9 unfiltered characteristic discovery · attempt "
                + "\(self.centralCharacteristicDiscoveryAttempt) · \(reason)")
            peripheral.discoverCharacteristics(nil, for: service)
        }
        centralCharacteristicDiscoveryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func recoverCentralCharacteristicDiscovery(_ peripheral: CBPeripheral,
                                                       service: CBService,
                                                       reason: String) {
        if centralCharacteristicDiscoveryAttempt < centralCharacteristicDiscoveryLimit {
            scheduleCentralCharacteristicDiscovery(
                peripheral, service: service, reason: reason, delay: 0.5)
            return
        }
        scheduleCentralServiceRediscovery(
            peripheral, reason: "characteristic discovery exhausted · \(reason)")
    }

    /// A hot Android package update may leave the old service invalidated for many seconds while
    /// the physical ACL stays connected. Continue sparse read-only discovery until a different
    /// exact F04 object is available. The capped cadence never consumes or re-arms a budget and
    /// never issues cancel/connect.
    private func scheduleCentralFreshF04Validation(_ peripheral: CBPeripheral,
                                                   reason: String) {
        guard runRequested, role == .central,
              centralFreshF04ValidationPending,
              centralInvalidatedF04Service != nil,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralFreshF04ValidationWorkItem == nil else { return }
        let index = min(centralFreshF04ValidationAttempt,
                        centralFreshF04ValidationDelays.count - 1)
        let delay = centralFreshF04ValidationDelays[index]
        let attempt = centralFreshF04ValidationAttempt + 1
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralFreshF04ValidationWorkItem = nil
            guard self.runRequested, self.role == .central,
                  self.centralFreshF04ValidationPending,
                  self.centralInvalidatedF04Service != nil,
                  peripheral === self.geelyPeripheral,
                  peripheral.state == .connected else { return }
            self.centralFreshF04ValidationAttempt = min(
                self.centralFreshF04ValidationAttempt + 1,
                self.centralFreshF04ValidationDelays.count - 1)
            self.centralService = nil
            self.centralControlCharacteristic = nil
            self.centralSecureCharacteristic = nil
            self.centralWakeCharacteristic = nil
            self.centralHandshake = .discovering
            self.centralCharacteristicDiscoveryAttempt = 0
            if attempt == 1
                    || Date().timeIntervalSince(self.centralFreshF04ValidationLastLogAt) >= 30 {
                self.centralFreshF04ValidationLastLogAt = Date()
                self.append("Sparse validated-F04 rediscovery · attempt \(attempt) · \(reason)")
            }
            // CONTRACT_V41_FRESH_F04_VALIDATION_IS_READ_ONLY_SPARSE
            peripheral.discoverServices([self.serviceUUID])
        }
        centralFreshF04ValidationWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Abandon an unusable replacement candidate without changing the retained owner or the
    /// already-spent destructive budget. Validation mode is deliberately discovery-only: an
    /// error, incomplete characteristic set, or the old exact object simply resumes the sparse
    /// service loop.
    private func continueCentralFreshF04Validation(_ peripheral: CBPeripheral,
                                                   reason: String) {
        guard centralFreshF04ValidationPending,
              centralInvalidatedF04Service != nil,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        // CONTRACT_V41_INVALID_F04_CANDIDATE_STAYS_READ_ONLY
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralHandshake = .discovering
        centralCharacteristicDiscoveryAttempt = 0
        setStatus("CENTRAL · ЖДУ VALIDATED NEW F04", color: .systemOrange)
        scheduleCentralFreshF04Validation(peripheral, reason: reason)
    }

    /// An invalidated CBService must never be reused. Refresh the service list on the same
    /// connected ATT link before considering a destructive disconnect/reconnect cycle.
    private func scheduleCentralServiceRediscovery(_ peripheral: CBPeripheral,
                                                   reason: String,
                                                   delay: TimeInterval = 0.75) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        if centralFreshF04ValidationPending {
            // Validation owns its long-lived capped cadence; the short two-attempt path below is
            // reserved for an ordinary current namespace.
            scheduleCentralFreshF04Validation(
                peripheral, reason: "validated-F04 mode · \(reason)")
            return
        }
        guard !centralDestructiveRecoveryWaitingForFreshF04 else { return }
        guard centralServiceRediscoveryAttempt < centralServiceRediscoveryLimit else {
            setStatus("CENTRAL · ЖДУ F04 SERVICE CHANGED", color: .systemOrange)
            append("F04 пока не готов после \(centralServiceRediscoveryAttempt) попыток; "
                + "owner сохраняю и жду service invalidation/ручную диагностику · \(reason)")
            return
        }
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralCharacteristicDiscoveryWorkItem = nil
        centralServiceRediscoveryWorkItem?.cancel()
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralHandshake = .discovering
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt += 1
        let attempt = centralServiceRediscoveryAttempt
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            self.centralServiceRediscoveryWorkItem = nil
            guard self.runRequested, self.role == .central,
                  peripheral === self.geelyPeripheral,
                  peripheral.state == .connected else { return }
            if self.centralFreshF04ValidationPending {
                self.scheduleCentralFreshF04Validation(
                    peripheral, reason: "validation superseded short rediscovery · \(reason)")
                return
            }
            guard !self.centralDestructiveRecoveryWaitingForFreshF04,
                  self.centralHandshake == .discovering else { return }
            self.append("D2D9 targeted service rediscovery · attempt \(attempt) · \(reason)")
            peripheral.discoverServices([self.serviceUUID])
        }
        centralServiceRediscoveryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Uses characteristics only from the CBService instance delivered by the current
    /// didDiscoverServices callback. Core Bluetooth often keeps this already-valid list across a
    /// reconnect; asking it to rediscover the same UUIDs can itself return uuidNotAllowed.
    @discardableResult
    private func useCurrentCentralCharacteristics(_ peripheral: CBPeripheral,
                                                  service: CBService,
                                                  source: String) -> Bool {
        if centralFreshF04ValidationPending,
           let invalidated = centralInvalidatedF04Service,
           service === invalidated {
            // CONTRACT_V41_INVALIDATED_F04_OBJECT_NEVER_WRITES_PAIR: even a populated cache for
            // the invalidated object remains read-only. Only a different validated object exits.
            continueCentralFreshF04Validation(
                peripheral, reason: "same invalidated CBService object returned")
            return true
        }
        let characteristics = service.characteristics ?? []
        guard let secure = characteristics.first(where: { $0.uuid == centralSecureUUID }) else {
            return false
        }
        let control = characteristics.first(where: { $0.uuid == centralControlUUID })
        let wake = characteristics.first(where: { $0.uuid == centralWakeUUID })
        centralControlCharacteristic = control
        centralSecureCharacteristic = secure
        guard let currentControl = control,
              let currentWake = wake,
              currentControl.properties.contains(.write),
              secure.properties.contains(.read),
              secure.properties.contains(.write),
              currentWake.properties.contains(.notify) else {
            if centralFreshF04ValidationPending {
                continueCentralFreshF04Validation(
                    peripheral, reason: "replacement F04 properties incomplete · \(source)")
                return true
            }
            append("Geely_ANCS properties invalid: B2 WRITE, B3 READ/WRITE, B4 NOTIFY required")
            setStatus("НЕСОВМЕСТИМЫЙ F04 GATT", color: .systemRed)
            return true
        }
        centralControlCharacteristic = currentControl
        centralWakeCharacteristic = currentWake
        cancelCentralDiscoveryWork()
        centralCharacteristicDiscoveryAttempt = 0
        centralServiceRediscoveryAttempt = 0
        rearmCentralDestructiveRecoveryForValidatedFreshF04(
            peripheral, service: service, source: source)
        if centralDestructiveRecoveryWaitingForFreshF04 {
            // A successful explicit manual reconnect may prove a usable namespace. Keep the
            // automatic budget spent for this lineage, but leave the waiting state.
            centralDestructiveRecoveryWaitingForFreshF04 = false
            append("Exact F04 characteristics usable after explicit recovery; "
                + "automatic destructive budget remains spent")
        }
        append("D2D9 single-owner characteristics ready · \(source)")
        writeCentralPair(peripheral)
        return true
    }

    private func centralErrorDescription(_ error: Error) -> String {
        let value = error as NSError
        return "\(value.localizedDescription) [\(value.domain):\(value.code)]"
    }

    private func resumeCentralPairAfterCurrentPublicationProof(_ peripheral: CBPeripheral) {
        guard peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              centralPairChallenge == nil,
              centralService != nil,
              centralControlCharacteristic != nil,
              centralSecureCharacteristic != nil,
              centralWakeCharacteristic != nil,
              currentCentralTranscriptPublicationNonce(peripheral) != nil,
              exactCurrentRelayLineage() != nil else { return }
        stopCentralScanSafely(centralManager, reason: "HA1211 current publication proved")
        writeCentralPair(peripheral)
    }

    private func resumeCentralPairAfterFirstRelayPublication(_ peripheral: CBPeripheral) {
        guard peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              centralPairChallenge == nil,
              !centralAliasBound,
              !centralAncsAccessProven,
              !centralAncsCccdConfirmed,
              centralService != nil,
              centralControlCharacteristic != nil,
              centralSecureCharacteristic != nil,
              centralWakeCharacteristic != nil,
              exactCurrentRelayLineage() != nil else { return }
        writeCentralPair(peripheral)
    }

    /// Validates the P/Q + F04 half while local F05 is intentionally absent between remove and
    /// the one replacement add. This ignores only F05 object/generation; all durable and physical
    /// publication authority remains exact.
    private func centralPairAwaitingRelayRebindHasExactPublication() -> Bool {
        guard centralPairAwaitingRelayRebind,
              let peripheral = geelyPeripheral,
              peripheral.state == .connected,
              centralOwnerConfiguredForAncs,
              let challenge = centralPairChallenge,
              challenge.count == centralPairChallengeLength,
              centralPairChallengeOwnerID == peripheral.identifier,
              let service = centralPairChallengeService,
              service === centralService,
              service.uuid == serviceUUID,
              peripheral.services?.contains(where: { $0 === service }) == true,
              centralPairChallengeTerminalGeneration
                == centralDeferredReclaimTerminalGeneration,
              let nonce = centralPairChallengePublicationNonce,
              centralPairChallengeInvalidationGeneration
                == centralPublicationInvalidationGeneration,
              !centralAliasBound,
              !centralAncsAccessProven,
              !centralAncsCccdConfirmed,
              let durable = storedCentralPairChallengeRecord(),
              durable.ownerID == peripheral.identifier,
              durable.publicationNonce == nonce,
              constantTimeChallengeMatches(durable.challenge, challenge) else { return false }
        let freshPublication = centralLastObservedPublicationOwnerID == peripheral.identifier
            && centralLastObservedPublicationNonce == nonce
            && centralLastObservedPublicationAtUptime != nil
            && centralLastObservedPublicationTerminalGeneration
                == centralDeferredReclaimTerminalGeneration
            && centralLastObservedPublicationInvalidationGeneration
                == centralPublicationInvalidationGeneration
        let restoredPublication = centralPairRehydratedOwnerID == peripheral.identifier
            && centralPairRehydratedPublicationNonce == nonce
            && centralPairRehydratedService === service
            && centralPairRehydratedTerminalGeneration
                == centralDeferredReclaimTerminalGeneration
            && centralPairRehydratedInvalidationGeneration
                == centralPublicationInvalidationGeneration
            && centralPairRehydratedCapabilityGeneration
                == centralPairRestorationGeneration
        return freshPublication || restoredPublication
    }

    @discardableResult
    private func rebindCurrentCentralPairRelayIfEligible() -> Bool {
        guard centralPairAwaitingRelayRebind,
              let peripheral = geelyPeripheral,
              peripheral.state == .connected,
              centralOwnerConfiguredForAncs,
              let challenge = centralPairChallenge,
              challenge.count == centralPairChallengeLength,
              centralPairChallengeOwnerID == peripheral.identifier,
              let service = centralPairChallengeService,
              service === centralService,
              service.uuid == serviceUUID,
              peripheral.services?.contains(where: { $0 === service }) == true,
              centralPairChallengeTerminalGeneration
                == centralDeferredReclaimTerminalGeneration,
              let nonce = centralPairChallengePublicationNonce,
              centralPairChallengeInvalidationGeneration
                == centralPublicationInvalidationGeneration,
              !centralAliasBound,
              !centralAncsAccessProven,
              !centralAncsCccdConfirmed,
              let durable = storedCentralPairChallengeRecord(),
              durable.ownerID == peripheral.identifier,
              durable.publicationNonce == nonce,
              constantTimeChallengeMatches(durable.challenge, challenge),
              (telemetrySubscribers.isEmpty
                || telemetrySubscribers == Set([peripheral.identifier])),
              let relayLineage = exactCurrentRelayLineage() else { return false }
        let freshPublication = centralLastObservedPublicationOwnerID == peripheral.identifier
            && centralLastObservedPublicationNonce == nonce
            && centralLastObservedPublicationAtUptime != nil
            && centralLastObservedPublicationTerminalGeneration
                == centralDeferredReclaimTerminalGeneration
            && centralLastObservedPublicationInvalidationGeneration
                == centralPublicationInvalidationGeneration
        let restoredPublication = centralPairRehydratedOwnerID == peripheral.identifier
            && centralPairRehydratedPublicationNonce == nonce
            && centralPairRehydratedService === service
            && centralPairRehydratedTerminalGeneration
                == centralDeferredReclaimTerminalGeneration
            && centralPairRehydratedInvalidationGeneration
                == centralPublicationInvalidationGeneration
            && centralPairRehydratedCapabilityGeneration
                == centralPairRestorationGeneration
        guard freshPublication || restoredPublication else { return false }
        centralPairChallengeRelayCharacteristic = relayLineage.characteristic
        centralPairChallengeRelayGeneration = relayLineage.generation
        if restoredPublication {
            centralPairRehydratedRelayCharacteristic = relayLineage.characteristic
            centralPairRehydratedRelayGeneration = relayLineage.generation
            centralPairRestorationRelayCharacteristic = relayLineage.characteristic
            centralPairRestorationRelayGeneration = relayLineage.generation
            centralPairRestorationRelaySubscriberIDs = telemetrySubscribers
            centralPairRestorationRelayProvisional = false
        }
        centralPairAwaitingRelayRebind = false
        return true
    }

    private func writeCentralPair(_ peripheral: CBPeripheral) {
        guard let control = centralControlCharacteristic else {
            resetCentralLink(reason: "CONTROL B2 missing")
            return
        }
        guard let challenge = ensureCentralPairChallenge(peripheral) else {
            centralHandshake = .discovering
            setStatus("CENTRAL · ЖДУ CURRENT PROTOCOL2", color: .systemOrange)
            append("HA1211 Pair deferred; exact durable owner/publication challenge unavailable")
            startCentralScan()
            return
        }
        var frame = Data([centralPairChallengeOpcode])
        frame.append(challenge)
        guard frame.count == centralProofFrameLength,
              frame.count <= 20 else {
            append("HA1211 Pair frame rejected locally; invalid ATT payload length")
            return
        }
        centralHandshake = .writingPair
        peripheral.writeValue(frame, for: control, type: .withResponse)
        setStatus("CENTRAL · PAIR / CURRENT LINK", color: .systemOrange)
        append("WRITE HA1211 P/Q → Geely_ANCS CONTROL · 17 bytes")
    }

    private func readCentralSecure(_ peripheral: CBPeripheral) {
        guard let secure = centralSecureCharacteristic else {
            resetCentralLink(reason: "SECURE B3 missing")
            return
        }
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralSecureReadAttempt += 1
        centralHandshake = .readingSecure
        peripheral.readValue(for: secure)
        append("READ CURRENT LINK B3 · attempt \(centralSecureReadAttempt)")
    }

    private func retryCentralSecure(_ peripheral: CBPeripheral, error: Error) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral else { return }
        let reason = centralErrorDescription(error)
        if isCentralInvalidHandleError(error) {
            resetCentralLink(reason: "SECURE stale handle · \(reason)")
            return
        }
        let expectedChallenge = (error as NSError).domain == CBATTErrorDomain
            && (error as NSError).code == CBATTError.Code.insufficientAuthentication.rawValue
        if expectedChallenge && !centralLinkSecurityChallengeObserved {
            centralLinkSecurityChallengeObserved = true
            append("B3 current-link challenge получен; Core Bluetooth восстанавливает LE security")
        }
        // HA1176 deliberately returns one status-5 challenge on every new physical link. A second
        // B3 read confirms that the callback still belongs to that link. Persistent auth/key-size
        // errors mean the session did not advance; reconnect instead of repeating for 90 seconds.
        if isCentralEncryptionError(error) && centralSecureReadAttempt >= 5 {
            resetCentralLink(reason: "current-link security did not advance · \(reason)")
            return
        }
        guard centralSecureReadAttempt < 15 else {
            append("CURRENT LINK не подтверждён за 30 с · \(reason)")
            resetCentralLink(reason: "current-link confirmation timeout")
            return
        }
        centralSecureRetryWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral,
                  self.runRequested, self.role == .central,
                  peripheral === self.geelyPeripheral,
                  peripheral.state == .connected else { return }
            self.centralSecureRetryWorkItem = nil
            self.readCentralSecure(peripheral)
        }
        centralSecureRetryWorkItem = item
        let delay: TimeInterval = expectedChallenge ? 1 : 2
        setStatus("CENTRAL · ВОССТАНАВЛИВАЮ LE SECURITY", color: .systemOrange)
        append("CURRENT LINK B3 повтор через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func markCentralReady(_ peripheral: CBPeripheral, value: Data?) {
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        UserDefaults.standard.set(peripheral.identifier.uuidString,
                                  forKey: savedGeelyPeripheralPreference)
        let text = value.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        append("CURRENT LINK OK · saved Geely identity \(peripheral.identifier.uuidString)"
            + (text.isEmpty ? "" : " · `\(text)`"))
        centralSecureLinkReady = true
        centralAncsAuthorized = peripheral.ancsAuthorized
        publishTelemetry(reason: "central secure ready", force: true)
        continueCentralAfterSecurity(peripheral)
    }

    private func continueCentralAfterSecurity(_ peripheral: CBPeripheral) {
        centralAncsAuthorized = peripheral.ancsAuthorized
        if !centralAncsAuthorized {
            setStatus("CURRENT LINK OK · ПРОВЕРЯЮ ANCS", color: .systemOrange)
            append("CURRENT LINK защищён; ancsAuthorized snapshot=false пока диагностический. "
                + "ANCS-READY отправляю, фактический доступ подтвердят Android CCCD")
        }
        // CONTRACT_V39_B3_ALWAYS_WRITES_READY: exact-owner B3 is the protocol gate. The Boolean
        // ANCS snapshot may still be stale while iOS resolves privacy after pairing, so it must
        // never deadlock Android's actual ANCS discovery/subscription attempt.
        writeCentralAncsReady(peripheral)
    }

    private func writeCentralAncsReady(_ peripheral: CBPeripheral) {
        guard centralSecureLinkReady, !centralAncsReadyWriteIssued else { return }
        guard let secure = centralSecureCharacteristic else {
            resetCentralLink(reason: "SECURE B3 missing before ANCS proof")
            return
        }
        centralAncsReadyWriteIssued = true
        centralHandshake = .writingAncsReady
        peripheral.writeValue(Data("ANCS-READY".utf8), for: secure, type: .withResponse)
        setStatus("REQUIRES_ANCS · ПОДТВЕРЖДАЮ OWNER", color: .systemOrange)
        append("WRITE ANCS-READY → same encrypted B3 owner")
    }

    /// Subscribe after PAIR/B3/ANCS-READY so Core Bluetooth can wake the Helper in background.
    /// Android's one-byte B4 notification contains no phone data; it only triggers a fresh public
    /// UIKit/CoreTelephony snapshot which is returned through the iPhone-owned F05/B4 relay.
    private func enableCentralWakeSubscription(_ peripheral: CBPeripheral, reason: String) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .ready,
              let wake = centralWakeCharacteristic else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        if wake.isNotifying {
            centralWakeSubscriptionAttempt = 0
            append("Android B4 wake CCCD active · \(reason)")
            return
        }
        centralWakeSubscriptionAttempt += 1
        let attempt = centralWakeSubscriptionAttempt
        peripheral.setNotifyValue(true, for: wake)
        append("Enable Android B4 wake CCCD · attempt \(attempt) · \(reason)")
        let watchdog = DispatchWorkItem { [weak self, weak peripheral, weak wake] in
            guard let self = self, let peripheral = peripheral, let wake = wake else { return }
            self.centralWakeSubscriptionWorkItem = nil
            guard self.runRequested, self.role == .central,
                  peripheral === self.geelyPeripheral,
                  peripheral.state == .connected,
                  self.centralHandshake == .ready,
                  self.centralWakeCharacteristic === wake,
                  !wake.isNotifying else { return }
            self.scheduleCentralWakeSubscriptionRetry(
                peripheral, reason: "CCCD callback timeout")
        }
        centralWakeSubscriptionWorkItem = watchdog
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: watchdog)
    }

    private func scheduleCentralWakeSubscriptionRetry(_ peripheral: CBPeripheral,
                                                      reason: String) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .ready else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        let delays: [TimeInterval] = [1, 2, 5, 10, 30, 60]
        let delay = delays[min(max(0, centralWakeSubscriptionAttempt - 1), delays.count - 1)]
        let item = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral else { return }
            guard self.runRequested, self.role == .central,
                  peripheral === self.geelyPeripheral else { return }
            self.centralWakeSubscriptionWorkItem = nil
            self.enableCentralWakeSubscription(peripheral, reason: "retry after \(reason)")
        }
        centralWakeSubscriptionWorkItem = item
        append("Android B4 wake CCCD retry через \(Int(delay)) с · \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func responseData(for characteristic: CBCharacteristic) -> Data? {
        switch characteristic.uuid {
        case infoUUID:
            return Data("\(logicalName)/32/realtime-single-owner".utf8)
        case secureUUID:
            return Data("SECURE IPHONE OK".utf8)
        case telemetryUUID, telemetryRelayUUID:
            let snapshot = captureTelemetrySnapshot()
            lastPublishedSnapshot = snapshot
            lastTelemetryPublishAt = Date()
            return makeTelemetryFrame(for: snapshot, incrementSequence: true)
        default:
            return nil
        }
    }

    private func isLocalTelemetryUUID(_ uuid: CBUUID) -> Bool {
        return uuid == telemetryUUID || uuid == telemetryRelayUUID
    }

    // MARK: - Exact Helper telemetry

    private func startTelemetryMonitoring() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        phoneLocked = !UIApplication.shared.isProtectedDataAvailable
        telephonyInfo.delegate = self
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(batteryLevelDidChange),
                           name: UIDevice.batteryLevelDidChangeNotification, object: nil)
        center.addObserver(self, selector: #selector(batteryStateDidChange),
                           name: UIDevice.batteryStateDidChangeNotification, object: nil)
        center.addObserver(self, selector: #selector(radioTechnologyDidChange),
                           name: .CTServiceRadioAccessTechnologyDidChange, object: nil)
        center.addObserver(self, selector: #selector(applicationBecameActive),
                           name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(phoneDidLock),
                           name: UIApplication.protectedDataWillBecomeUnavailableNotification,
                           object: nil)
        center.addObserver(self, selector: #selector(phoneDidUnlock),
                           name: UIApplication.protectedDataDidBecomeAvailableNotification,
                           object: nil)
        let timer = Timer(timeInterval: telemetrySampleInterval, repeats: true) {
            [weak self] _ in self?.publishTelemetry(reason: "1s control")
        }
        timer.tolerance = 0.1
        telemetryTimer = timer
        RunLoop.main.add(timer, forMode: .common)
        publishTelemetry(reason: "startup", force: true)
    }

    @objc private func applicationBecameActive() {
        phoneLocked = false
        startSelectedRouteIfPossible()
        publishTelemetry(reason: "foreground refresh", force: true)
        scheduleSettledTelemetryRefresh(reason: "foreground settled")
    }

    @objc private func batteryLevelDidChange() {
        publishTelemetryOnMain(reason: "battery level changed")
    }

    @objc private func batteryStateDidChange() {
        publishTelemetryOnMain(reason: "power state changed")
    }

    @objc private func radioTechnologyDidChange(_ notification: Notification) {
        let service = (notification.object as? String) ?? "unknown service"
        publishTelemetryOnMain(reason: "radio changed \(service)")
    }

    @objc private func phoneDidLock() {
        phoneLocked = true
        publishTelemetryOnMain(reason: "phone locked")
    }

    @objc private func phoneDidUnlock() {
        phoneLocked = false
        publishTelemetryOnMain(reason: "phone unlocked")
    }

    private func publishTelemetryOnMain(reason: String) {
        if Thread.isMainThread {
            publishTelemetry(reason: reason, force: true)
            scheduleSettledTelemetryRefresh(reason: "\(reason) settled")
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.publishTelemetry(reason: reason, force: true)
                self?.scheduleSettledTelemetryRefresh(reason: "\(reason) settled")
            }
        }
    }

    /// Some iOS notifications arrive in the same run-loop turn in which the underlying public
    /// property changes. Send immediately, then re-read once after 250 ms to catch that edge
    /// without waiting for the one-second safety sampler.
    private func scheduleSettledTelemetryRefresh(reason: String) {
        settledTelemetryRefresh?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.publishTelemetry(reason: reason)
        }
        settledTelemetryRefresh = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: item)
    }

    private func publishTelemetry(reason: String, force: Bool = false) {
        guard runRequested else { return }
        let snapshot = captureTelemetrySnapshot()
        let changed = snapshot != lastPublishedSnapshot
        let heartbeatDue = Date().timeIntervalSince(lastTelemetryPublishAt)
            >= telemetryHeartbeatInterval
        if UIApplication.shared.applicationState == .active { updateTelemetryLabel() }
        guard force || changed || heartbeatDue else { return }
        let frame = makeTelemetryFrame(for: snapshot, incrementSequence: true)
        lastPublishedSnapshot = snapshot
        lastTelemetryPublishAt = Date()
        if role == .central, centralHelperConfirmed, changed {
            refreshCentralReadiness("telemetry changed")
        }
        let backgroundWake = reason == "KX11 background wake poll"
        let backgroundLogDue = Date().timeIntervalSince(lastBackgroundWakeLogAt) >= 60
        if changed || force && (!backgroundWake || backgroundLogDue) {
            if backgroundWake { lastBackgroundWakeLogAt = Date() }
            let power = String(snapshot.powerFlags, radix: 16, uppercase: true)
            append("B4 snapshot · \(reason) · battery=\(snapshot.batteryLevel)"
                + " · powerFlags=0x\(power) · network=\(currentNetworkType())"
                + " · locked=\(phoneLocked)"
                + " · seq=\(telemetrySequence) · service=\(servicePublished)"
                + " · subscribers=\(telemetrySubscribers.count)")
        }
        guard servicePublished, telemetryCharacteristic != nil,
              !telemetrySubscribers.isEmpty else { return }
        // A periodic heartbeat may be coalesced while the BLE transmit queue is blocked, but a
        // changed battery/power/network snapshot is always appended and kept in order.
        if changed || force || pendingTelemetryFrames.isEmpty {
            pendingTelemetryFrames.append(frame)
        } else {
            pendingTelemetryFrames[pendingTelemetryFrames.count - 1] = frame
        }
        drainTelemetryNotification()
    }

    private func drainTelemetryNotification() {
        guard runRequested, peripheralManager != nil,
              peripheralManager.state == .poweredOn,
              let characteristic = telemetryCharacteristic,
              !telemetrySubscribers.isEmpty else { return }
        while let frame = pendingTelemetryFrames.first {
            guard peripheralManager.updateValue(frame, for: characteristic,
                                                onSubscribedCentrals: nil) else {
                let now = Date()
                if now.timeIntervalSince(lastTelemetryBackpressureLogAt) >= 1 {
                    lastTelemetryBackpressureLogAt = now
                    append("B4 notify backpressure · queue=\(pendingTelemetryFrames.count)"
                        + " · subscribers=\(telemetrySubscribers.count)")
                }
                return
            }
            pendingTelemetryFrames.removeFirst()
        }
    }

    /// Fixed frame: A5, version, level, power/lock flags, network, sequence LE, CRC-8/ATM.
    private func makeTelemetryFrame(for snapshot: TelemetrySnapshot,
                                    incrementSequence: Bool) -> Data {
        if incrementSequence { telemetrySequence &+= 1 }
        var bytes: [UInt8] = [
            0xA5, 0x01, snapshot.batteryLevel, snapshot.powerFlags,
            snapshot.networkCode,
            UInt8(truncatingIfNeeded: telemetrySequence),
            UInt8(truncatingIfNeeded: telemetrySequence >> 8)
        ]
        bytes.append(crc8(bytes))
        return Data(bytes)
    }

    private func captureTelemetrySnapshot() -> TelemetrySnapshot {
        let device = UIDevice.current
        let level: UInt8
        if device.batteryLevel >= 0 {
            // Convert the public UIKit value once at the source. Android receives this exact
            // integer and never estimates, smooths or quantizes it.
            let percent = max(0, min(100,
                Int((Double(device.batteryLevel) * 100.0).rounded())))
            level = UInt8(percent)
        } else {
            level = 0xFF
        }

        var flags: UInt8 = 0
        switch device.batteryState {
        case .charging:
            flags = 0x01 | 0x02 | 0x04 | 0x08
        case .full:
            flags = 0x01 | 0x02 | 0x04 | 0x10
        case .unplugged:
            flags = 0x01 | 0x04
        case .unknown:
            flags = 0
        @unknown default:
            flags = 0
        }
        // Bit 5 is independent of the battery-valid bits and is backward compatible with v20.
        if phoneLocked { flags |= 0x20 }

        return TelemetrySnapshot(
            batteryLevel: level,
            powerFlags: flags,
            networkCode: currentNetworkCode()
        )
    }

    private func crc8(_ bytes: [UInt8]) -> UInt8 {
        var crc: UInt8 = 0
        for byte in bytes {
            crc ^= byte
            for _ in 0..<8 {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1
            }
        }
        return crc
    }

    private func currentNetworkCode() -> UInt8 {
        switch currentNetworkType() {
        case "5G": return 1
        case "LTE": return 2
        case "4G": return 3
        case "3G": return 4
        case "E": return 5
        case "G": return 6
        case "1X": return 7
        case "SOS": return 8
        case "SAT": return 9
        default: return 0
        }
    }

    private func currentNetworkType() -> String {
        let technologies: [String]
        if #available(iOS 13.0, *) {
            // A retained CoreTelephony object can keep its last foreground cache while the app is
            // suspended.  Every BLE wake therefore asks a fresh public snapshot first and falls
            // back to the delegate-owned instance only if iOS has not populated it yet.
            let liveInfo = CTTelephonyNetworkInfo()
            let liveByService = liveInfo.serviceCurrentRadioAccessTechnology ?? [:]
            let byService = liveByService.isEmpty
                ? (telephonyInfo.serviceCurrentRadioAccessTechnology ?? [:])
                : liveByService
            let dataIdentifier = liveInfo.dataServiceIdentifier
                ?? telephonyInfo.dataServiceIdentifier
            if let identifier = dataIdentifier,
               let current = byService[identifier] {
                technologies = [current]
            } else {
                technologies = Array(byService.values)
            }
        } else if let current = telephonyInfo.currentRadioAccessTechnology {
            technologies = [current]
        } else {
            technologies = []
        }
        let labels = technologies.map(networkLabel)
        return ["5G", "LTE", "4G", "3G", "E", "G", "1X"]
            .first(where: labels.contains) ?? "—"
    }

    private func networkLabel(_ technology: String) -> String {
        if #available(iOS 14.1, *),
           technology == CTRadioAccessTechnologyNR
            || technology == CTRadioAccessTechnologyNRNSA {
            return "5G"
        }
        switch technology {
        case CTRadioAccessTechnologyLTE:
            return "LTE"
        case CTRadioAccessTechnologyWCDMA,
             CTRadioAccessTechnologyHSDPA,
             CTRadioAccessTechnologyHSUPA,
             CTRadioAccessTechnologyCDMAEVDORev0,
             CTRadioAccessTechnologyCDMAEVDORevA,
             CTRadioAccessTechnologyCDMAEVDORevB,
             CTRadioAccessTechnologyeHRPD:
            return "3G"
        case CTRadioAccessTechnologyEdge:
            return "E"
        case CTRadioAccessTechnologyGPRS:
            return "G"
        case CTRadioAccessTechnologyCDMA1x:
            return "1X"
        default:
            return "—"
        }
    }

    private func batteryDescription() -> String {
        let level = UIDevice.current.batteryLevel >= 0
            ? "\(Int((Double(UIDevice.current.batteryLevel) * 100.0).rounded()))%" : "—%"
        switch UIDevice.current.batteryState {
        case .charging: return "\(level), питание подключено, зарядка"
        case .full: return "\(level), питание подключено, полный"
        case .unplugged: return "\(level), питание отключено"
        case .unknown: return "\(level), состояние питания неизвестно"
        @unknown default: return "\(level), состояние питания неизвестно"
        }
    }

    private func updateTelemetryLabel() {
        if role == .central {
            let relayRead = lastAndroidReadAt.map {
                let formatter = DateFormatter()
                formatter.dateFormat = "HH:mm:ss"
                return formatter.string(from: $0)
            } ?? "ещё не было"
            let valid = centralTelemetryValidity()
            telemetryLabel.text = "\(batteryDescription()) · \(currentNetworkType()) · "
                + (phoneLocked ? "заблокирован" : "разблокирован") + "\n"
                + "B4 RELAY: READ \(relayRead) · подписок: \(telemetrySubscribers.count) · "
                + "helper=\(centralHelperConfirmed ? "OK" : "—") · "
                + "ANCS=\(centralAncsCccdConfirmed ? "OK" : "—") · "
                + "B4=\(centralB4Subscribed ? "OK" : "—") · "
                + "data=\(valid.battery && valid.network ? "OK" : "—")"
            return
        }
        let read = lastAndroidReadAt.map {
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            return formatter.string(from: $0)
        } ?? "ещё не было"
        telemetryLabel.text = "\(batteryDescription()) · \(currentNetworkType()) · "
            + (phoneLocked ? "заблокирован" : "разблокирован") + "\n"
            + "Android READ: \(read) · подписок: \(telemetrySubscribers.count)"
    }

    private func updateConnectionStatus() {
        guard runRequested else { return }
        if role == .central {
            if centralDestructiveRecoveryWaitingForFreshF04 {
                setStatus("CENTRAL · ЖДУ SERVICE CHANGED", color: .systemOrange)
            } else if centralHandshake == .ready || centralHelperConfirmed {
                refreshCentralReadiness("status refresh")
            } else if geelyPeripheral?.state == .connected {
                setStatus("ПОДКЛЮЧЕНО · SINGLE-OWNER HANDSHAKE", color: .systemOrange)
            } else if geelyPeripheral?.state == .connecting {
                setStatus("CENTRAL · СИСТЕМА ПОДКЛЮЧАЕТ", color: .systemBlue)
            } else if centralManager?.isScanning == true {
                setStatus("ИЩУ GEELY_ANCS", color: .systemBlue)
            } else {
                setStatus("CENTRAL · ОЖИДАНИЕ KX11", color: .systemBlue)
            }
            return
        }
        if !telemetrySubscribers.isEmpty {
            setStatus("KX11 ПОДКЛЮЧЁН · ЕДИНЫЙ GATT", color: .systemGreen)
        } else if servicePublished {
            setStatus("ГОТОВ · ЖДУ KX11", color: .systemBlue)
        }
    }

    private func setStatus(_ text: String, color: UIColor) {
        statusLabel.text = text
        statusLabel.backgroundColor = color
    }

    private func append(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let line = "\(formatter.string(from: Date()))  \(message)"
        logLines.append(line)
        if logLines.count > maximumLogLines {
            logLines.removeFirst(logLines.count - maximumLogLines)
        }
        logView.text = logLines.joined(separator: "\n")
        persistJournal()
        guard !logView.text.isEmpty else { return }
        let end = NSRange(location: max(0, logView.text.utf16.count - 1), length: 1)
        logView.scrollRangeToVisible(end)
    }

    private var journalURL: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("KX11-phone-connection.log")
    }

    private func loadJournal() {
        guard let url = journalURL,
              let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        logLines = text.split(separator: "\n", omittingEmptySubsequences: true)
            .suffix(maximumLogLines).map(String.init)
        logView.text = logLines.joined(separator: "\n")
    }

    private func persistJournal() {
        guard let url = journalURL else { return }
        try? logLines.joined(separator: "\n").write(
            to: url, atomically: true, encoding: .utf8)
    }
}

extension ViewController: CTTelephonyNetworkInfoDelegate {
    /// Radio technology and the SIM currently carrying mobile data are separate CoreTelephony
    /// events. Listening to both prevents a stale EDGE/3G label after iOS moves data to LTE/5G
    /// on another line.
    func dataServiceIdentifierDidChange(_ identifier: String) {
        publishTelemetryOnMain(reason: "data service changed \(identifier)")
    }
}

extension ViewController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        append("CBCentralManager state=\(central.state.rawValue)")
        guard central.state == .poweredOn else {
            clearCentralPairRestorationCapability()
            clearCentralPairTranscript(reason: "Central radio unavailable")
            centralRestorationRecoveryWorkItem?.cancel()
            centralRestorationRecoveryWorkItem = nil
            centralRestorationProofProbeWorkItem?.cancel()
            centralRestorationProofProbeWorkItem = nil
            centralRestorationPostCancelProbeWorkItem?.cancel()
            centralRestorationPostCancelProbeWorkItem = nil
            cancelCentralRestorationPublicationReadOnlyScan(
                stopScan: false, resetBackoff: false)
            centralRestoreFreshConnectProofToken &+= 1
            centralRestoreFreshConnectProofWorkItem?.cancel()
            centralRestoreFreshConnectProofWorkItem = nil
            centralPendingTerminalStateProbeWorkItem?.cancel()
            centralPendingTerminalStateProbeWorkItem = nil
            // CONTRACT_V41_POWER_OFF_PRESERVES_RECLAIM_OWNER: invalidate all old physical proof
            // and delayed work, but retain the exact owner, lineage budget, pending-issued-connect,
            // and post-cancel terminal ownership. No BLE command is issued while unavailable.
            // CONTRACT_V42_POWER_OFF_INVALIDATES_BEACON_GRACE_NOT_BUDGET
            centralDeferredReclaimEvidenceGeneration &+= 1
            centralDeferredReclaimProbeWorkItem?.cancel()
            centralDeferredReclaimProbeWorkItem = nil
            centralDeferredReclaimGraceWorkItem?.cancel()
            centralDeferredReclaimGraceWorkItem = nil
            centralDeferredReclaimPostCancelWorkItem?.cancel()
            centralDeferredReclaimPostCancelWorkItem = nil
            centralDeferredReclaimScanWindowWorkItem?.cancel()
            centralDeferredReclaimScanWindowWorkItem = nil
            centralDeferredReclaimScanRestartWorkItem?.cancel()
            centralDeferredReclaimScanRestartWorkItem = nil
            centralDeferredReclaimScanCycleAttempt = 0
            centralDeferredReclaimBeaconObserved = false
            centralDeferredReclaimBeaconIssuedConnectToken = nil
            centralDeferredReclaimBeaconOwnerID = nil
            centralDeferredReclaimBeaconNamespaceGeneration = nil
            centralDeferredReclaimBeaconTerminalGeneration = nil
            centralDeferredReclaimBeaconPublicationNonce = nil
            centralDeferredReclaimBeaconRootClaimToken = nil
            centralDeferredReclaimBeaconInvalidationGeneration = nil
            centralDeferredReclaimSystemProofObserved = false
            if !centralDeferredReclaimConsumed {
                centralDeferredReclaimPublicationNonce = nil
            }
            centralDeferredReclaimProbeAttempt = 0
            centralDeferredReclaimPostCancelAttempt = 0
            let preserveSoleSystemReconnectAcrossPower =
                centralSystemAutoReconnectActive
                && centralRestorationPublicationReopenIssued
                && centralRestorationPublicationAdoptedNonce != nil
                && centralDeferredReclaimActive
                && centralDeferredReclaimConsumed
                && (centralRestorationPublicationReopenPhase == .requestIssued
                    || centralRestorationPublicationReopenPhase == .connected)
            if !preserveSoleSystemReconnectAcrossPower {
                centralSystemAutoReconnectActive = false
            }
            // willRestoreState may arrive before this state callback. Preserve that exact owner;
            // it is the only one known to have the RequiresANCS contract.
            if !centralRestorationAwaitingPower && !centralRestoredPendingOwner
                && !centralRestorationReconnectPending
                && !centralRestoreFreshConnectAwaitingCallback
                && !centralManualReconnectPending
                && centralHardResetReason == nil
                && centralDeferredConnectIntent == nil
                && !centralDeferredReclaimActive
                && !centralDeferredReclaimPendingTerminal
                && !centralDeferredReclaimIssuedConnectPending {
                centralOwnerConfiguredForAncs = false
                clearCentralRuntime(keepPeripheral: false)
            } else if centralDeferredReclaimActive
                        || centralDeferredReclaimPendingTerminal
                        || centralDeferredReclaimIssuedConnectPending {
                clearCentralRuntime(keepPeripheral: true)
                append("Bluetooth unavailable; exact hot-update reclaim owner/lineage retained")
            } else if centralDeferredConnectIntent != nil {
                // CONTRACT_V38_POWER_OFF_PRESERVES_DEFERRED_EXACT_OWNER
                clearCentralRuntime(keepPeripheral: true)
                append("Bluetooth unavailable; deferred exact-owner connect retained in RAM")
            }
            if runRequested { setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed) }
            return
        }
        flushDeferredCentralCommands(central)
        guard role == .central else {
            centralRestorationAwaitingPower = false
            clearCentralDeferredConnectIntent()
            clearCentralDeferredReclaimLineage()
            clearCentralRestorationRecovery()
            stopCentralScanSafely(central, reason: "role is not Central")
            return
        }
        if centralRestorationAwaitingPower {
            centralRestorationAwaitingPower = false
            append("Central restoration продолжен после poweredOn")
        }
        if runRequested {
            // CONTRACT_V41_POWERED_ON_REARMS_ONLY_RECLAIM_OBSERVER: pending cancel owns only the
            // read-only terminal observer; an issued request owns only fresh F04 proof observers.
            if centralDeferredReclaimPendingTerminal, let owner = geelyPeripheral {
                armCentralDeferredReclaimPostCancelObservation(owner)
                return
            }
            if centralDeferredReclaimActive,
               centralDeferredReclaimIssuedConnectPending,
               let owner = geelyPeripheral {
                if owner.state == .disconnected {
                    // CONTRACT_V41_POWER_RESUME_DISCONNECTED_REMATERIALIZES_EXACT_INTENT:
                    // powering Bluetooth off ended the already-issued Core Bluetooth request.
                    // Convert that same lineage back into data before normal routing; this is
                    // neither a cancel nor a new reclaim claim/budget.
                    invalidateCentralDeferredReclaimRequestEvidence(
                        keepIssuedRequest: false)
                    _ = queueCentralConnectIntent(
                        owner, reason: "same hot-update request after Bluetooth poweredOn")
                    startCentralRouteIfPossible()
                    return
                }
                armCentralDeferredReclaimObservation(owner)
                return
            }
            // CONTRACT_V38_POWERED_ON_ENTERS_SINGLE_CONSUME_ROUTE
            startCentralRouteIfPossible()
        }
    }

    func centralManager(_ central: CBCentralManager,
                        willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey]
            as? [CBPeripheral] ?? []
        append("Central restore: peripherals=\(peripherals.count)")
        guard runRequested, role == .central else {
            peripherals.forEach {
                cancelCentralConnectionSafely($0, manager: central,
                                                reason: "restored while route is disabled")
            }
            return
        }
        let savedIdentifier = UserDefaults.standard.string(
            forKey: savedGeelyPeripheralPreference).flatMap(UUID.init(uuidString:))
        guard let savedIdentifier = savedIdentifier,
              let restored = peripherals.first(where: {
                $0.identifier == savedIdentifier
              }) else {
            append("Central restore ignored: no peripheral matches persisted Geely identity; "
                + "fallback owner is not persisted")
            return
        }
        stopCentralScanSafely(central, reason: "retain restored owner")
        cancelCentralReconnect()
        clearCentralRestorationRecovery()
        resetCentralRestoreOwnershipClaims()
        centralHardResetReason = nil
        centralRequireFreshAdvertisement = false
        centralOwnerConfiguredForAncs = true
        centralSystemAutoReconnectActive = restored.state == .connecting
        centralRestoredPendingOwner = restored.state == .connecting
        geelyPeripheral = restored
        restored.delegate = self
        UserDefaults.standard.set(restored.identifier.uuidString,
                                  forKey: savedGeelyPeripheralPreference)
        clearCentralRuntime(keepPeripheral: true)
        if restored.state == .connected || restored.state == .connecting {
            issueCentralPairRestorationCapability(restored)
        } else {
            clearCentralPairRestorationCapability()
        }
        if restored.state == .connecting {
            beginCentralRestorationPublicationBoundary(restored)
        } else {
            clearCentralRestorationPublicationBoundary()
        }
        if restored.state == .connected {
            // CONTRACT_V39_RESTORED_CONNECTED_OWNER_HALF: do not infer B4 from the central
            // restoration callback alone. Preserve this exact wrapper until the independently
            // restored F05 characteristic proves that the same persisted owner was subscribed.
            centralRestoredConnectedOwner = restored
            centralRestoredB4HintConsumed = false
        } else {
            // `.connecting` restoration, scan/retrieve and future didConnect paths must start B4
            // false. They can only regain it through an exact current didSubscribe callback.
            clearCentralRestoredB4Hint()
        }
        append("Central restore owner retained · "
            + restored.identifier.uuidString + " · state=\(restored.state.rawValue)"
            + " · stable link-anchor")
        if centralRestoredPendingOwner {
            append("Restored .connecting owner retained without timeout; "
                + "waiting for same-owner F04 system/beacon proof")
        }
        guard central.state == .poweredOn else {
            centralRestorationAwaitingPower = true
            setStatus("CENTRAL · ЖДУ POWERED ON", color: .systemOrange)
            append("Central restore сохранён; BLE-команды отложены до poweredOn")
            return
        }
        centralRestorationAwaitingPower = false
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested, self.role == .central else { return }
            self.startCentralRouteIfPossible()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard runRequested, role == .central else { return }
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey]
            as? [CBUUID] ?? []
        guard advertisedServices.contains(managedIncomingBeaconUUID) else { return }
        let decodedIdentity = advertisedCentralIdentity(advertisementData)
        // CONTRACT_V44_ADOPTION_PRECEDES_LAST_OBSERVED_MUTATION: compare protocol 2 against the
        // immutable restoration/terminal baseline before ordinary observation updates it.
        if observeCentralRestorationPublicationAdoption(
            peripheral, identity: decodedIdentity) {
            return
        }
        let advertisedIdentity: CentralAdvertisementIdentity?
        if let decodedIdentity = decodedIdentity,
           decodedIdentity.hasPublicationAuthority {
            let previousOwnerID = centralLastObservedPublicationOwnerID
            let previousNonce = centralLastObservedPublicationNonce
            let remembered = rememberExactPublicationIdentity(
                peripheral, identity: decodedIdentity)
            advertisedIdentity = remembered ? decodedIdentity : nil
            if let nonce = advertisedIdentity?.publicationNonce {
                normalizePostGreenRootOriginForPublication(
                    nonce, ownerID: peripheral.identifier)
                let isCurrentOrInitialPublication = previousOwnerID == nil
                    || (previousOwnerID == peripheral.identifier
                        && (previousNonce == nil || previousNonce == nonce))
                if isCurrentOrInitialPublication {
                    resumeCentralPairAfterCurrentPublicationProof(peripheral)
                }
            }
        } else {
            // Legacy protocol 1 remains useful for discovery/connect only.
            advertisedIdentity = decodedIdentity
        }
        if centralRestorationPublicationOwns(peripheral),
           centralRestorationPublicationAdoptedNonce == nil {
            // CONTRACT_V44_RESTORATION_V1_SAME_OLDER_FOREIGN_ARE_READ_ONLY: the exact restored
            // wrapper remains pending, but no legacy restoration timer and no ordinary discovery
            // may translate an ineligible frame into a cancel or a duplicate connect.
            // The dedicated bounded AllowDuplicates observer remains read-only and will see a
            // later N+1 payload from this same wrapper even after baseline/same/older frames.
            return
        }
        if observeCentralDeferredReclaimBeacon(
            peripheral, identity: advertisedIdentity, rssi: RSSI) {
            return
        }
        if centralDeferredReclaimActive,
           centralDeferredReclaimOwnerID == peripheral.identifier,
           let retainedOwner = geelyPeripheral,
           retainedOwner === peripheral {
            // CONTRACT_V43_RECOVERY_DUPLICATE_NEVER_REENTERS_CONNECT: protocol2 may be
            // ineligible (same automatic nonce), or didConnect may already have cleared the
            // issued attempt while the duplicate scan is winding down. Either way this is the
            // retained root's advertisement, not a new ordinary discovery event. Never call
            // connectCentral/clearCentralRuntime from it.
            // CONTRACT_V43_INELIGIBLE_DUPLICATE_STOPS_DUPLICATE_SCAN: same adopted nonce, legacy
            // v1 and malformed/stale proof cannot re-arm this automatic episode. Do not keep an
            // allowDuplicates scan alive forever; a genuine terminal/new publication root will
            // explicitly arm its own observer later.
            scheduleCentralDeferredReclaimLowDutyScan(
                retainedOwner,
                reason: "retained root advertisement is not eligible publication proof")
            return
        }
        guard geelyPeripheral == nil || peripheral === geelyPeripheral else { return }
        append("Найден Geely_ANCS · id=\(peripheral.identifier.uuidString) · RSSI=\(RSSI)")
        centralRequireFreshAdvertisement = false
        if centralRestoredPendingOwner, let restored = geelyPeripheral,
           restored === peripheral {
            scheduleRestoredOwnerRecovery(restored,
                evidence: "matching stable beacon, RSSI=\(RSSI)")
            return
        }
        connectCentral(peripheral, reason: "stable anchor beacon")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral else {
            cancelCentralConnectionSafely(peripheral, manager: central,
                                            reason: "didConnect for inactive owner")
            return
        }
        let hasSoleRestorationPublicationReopen =
            centralRestorationPublicationOwns(peripheral)
            && centralRestorationPublicationAdoptedNonce != nil
            && centralRestorationPublicationReopenIssued
        let matchesSoleRestorationPublicationReopen =
            hasSoleRestorationPublicationReopen
            && (centralRestorationPublicationReopenPhase == .requestIssued
                || centralRestorationPublicationReopenPhase == .exhausted)
            && ((centralRestorationPublicationReopenIssuedToken != nil
                    && centralRestorationPublicationReopenIssuedToken
                        == centralLastActualIssuedConnectToken)
                || (centralRestorationPublicationReopenPhase == .exhausted
                    && centralRestorationPublicationReopenIssuedToken == nil))
        if hasSoleRestorationPublicationReopen,
           !matchesSoleRestorationPublicationReopen,
           centralRestorationPublicationReopenPhase != .intentQueued {
            // CONTRACT_V44_UNATTRIBUTED_LATE_DIDCONNECT_NEVER_SECOND_CANCELS: the automatic
            // `(owner, nonce)` budget is already spent. An unexpected duplicate callback may be
            // read-only, but it can never fall through to `didConnect without RequiresANCS` and
            // send another cancel.
            append("Unattributed late didConnect quarantined behind spent sole-reopen root")
            return
        }
        if matchesSoleRestorationPublicationReopen {
            // A powered-on read-only snapshot may have marked the request exhausted immediately
            // before its delayed valid didConnect. Exact token/restoration provenance wins.
            centralOwnerConfiguredForAncs = true
        }
        let hadOrdinaryReclaimLineage = centralDeferredReclaimActive
            && centralDeferredReclaimOwnerID == peripheral.identifier
        cancelCentralDeferredReclaimScanCycle(stopScan: false, resetBackoff: true)
        stopCentralScanSafely(central,
            reason: "matching didConnect atomically stops recovery duplicate scan")
        if hadOrdinaryReclaimLineage && !centralDeferredReclaimPendingTerminal {
            // CONTRACT_V42_DIDCONNECT_INVALIDATES_GRACE_NOT_BUDGET
            invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
        }
        if centralRestorationReconnectPending {
            append("Late didConnect arrived after one-shot cancel; wait for didDisconnect")
            armRestoredOwnerPostCancelObservation()
            setStatus("CENTRAL · ЖДУ RESTORE DISCONNECT", color: .systemOrange)
            return
        }
        if centralDeferredReclaimPendingTerminal {
            append("Late exact didConnect arrived after ordinary reclaim cancel; "
                + "wait for terminal boundary")
            armCentralDeferredReclaimPostCancelObservation(peripheral)
            setStatus("CENTRAL · ЖДУ HOT UPDATE DISCONNECT", color: .systemOrange)
            return
        }
        if centralManualReconnectPending || centralHardResetReason != nil {
            append("Late didConnect arrived while manual/hard terminal state is pending; wait")
            armCentralPendingTerminalStateObservation()
            setStatus("CENTRAL · ЖДУ TERMINAL STATE", color: .systemOrange)
            return
        }
        if hadOrdinaryReclaimLineage {
            // CONTRACT_V41_LATE_EXACT_DIDCONNECT_CANCELS_RECLAIM: this synchronous callback
            // cancels any proof grace before accepting the exact current owner. v42 keeps the
            // same unspent/spent budget until full proof, validated F04, manual reset or a new
            // owner/route; didConnect alone cannot re-arm a storm.
            append("Matching late exact didConnect consumed ordinary reclaim evidence; "
                + (centralDeferredReclaimConsumed
                    ? "claim remains spent"
                    : "unchanged one-shot budget retained until full proof"))
        }
        if let intent = centralDeferredConnectIntent,
           intent.peripheral === peripheral {
            // CONTRACT_V39_LATE_DIDCONNECT_CONSUMES_EXACT_INTENT: a terminal callback may be
            // followed by an already-queued didConnect from the previous RequiresANCS request.
            // Accept that exact owner and atomically suppress the replacement instead of
            // cancelling a healthy physical link.
            clearCentralDeferredConnectIntent()
            centralOwnerConfiguredForAncs = true
            append("Matching late didConnect consumed deferred exact-owner reconnect intent")
        }
        guard centralOwnerConfiguredForAncs else {
            append("didConnect не принят: owner не был открыт/восстановлен с RequiresANCS")
            cancelCentralConnectionSafely(peripheral, manager: central,
                                            reason: "didConnect without RequiresANCS")
            return
        }
        cancelCentralReconnect()
        clearCentralRestorationRecovery()
        resetCentralRestoreOwnershipClaims()
        if matchesSoleRestorationPublicationReopen {
            // Retain the token quarantine until full green proof. A delayed old-cancel terminal
            // after this didConnect must not demote the newly accepted RequiresANCS owner.
            centralRestorationPublicationReopenPhase = .connected
            append("Sole restoration publication reopen didConnect accepted; "
                + "late old-terminal quarantine retained until full green")
        } else {
            clearCentralRestorationPublicationBoundary()
        }
        centralSystemAutoReconnectActive = false
        centralRequireFreshAdvertisement = false
        // CONTRACT_V39_FRESH_DIDCONNECT_CLEARS_RESTORED_B4_HINT
        clearCentralRestoredB4Hint()
        append("Central connected · \(peripheral.identifier.uuidString)")
        continueCentralConnected(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        guard peripheral === geelyPeripheral else { return }
        clearCentralRestoredB4Hint()
        append("Central connect failed · \(error?.localizedDescription ?? "без ошибки")")
        // CONTRACT_V41_RECLAIM_DIDFAIL_TERMINAL_REOPENS_ONCE: our bounded cancel owns this
        // terminal callback before restoration, manual, hard-reset, or ordinary reconnect paths.
        if centralDeferredReclaimPendingTerminal {
            clearCentralPairRestorationCapability()
            clearCentralPairTranscript(reason: "accepted reclaim didFail terminal")
            reopenCentralDeferredReclaimAfterTerminal(
                peripheral, callback: "didFailToConnect")
            return
        }
        if consumeCentralRestorationPublicationPostReopenTerminal(
            peripheral, callback: "didFailToConnect") {
            return
        }
        recordCentralDeferredReclaimTerminalBoundary()
        advanceCentralRestorationPublicationBoundaryAfterTerminal(peripheral)
        if centralRestorationReconnectPending {
            reopenRestoredOwnerAfterTerminalCallback(peripheral,
                callback: "didFailToConnect")
            return
        }
        if centralManualReconnectPending {
            reopenManualOwnerAfterTerminalCallback(peripheral,
                callback: "didFailToConnect")
            return
        }
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralOwnerConfiguredForAncs = false
        centralSystemAutoReconnectActive = false
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        if let hardReset = hardReset {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            centralOwnerConfiguredForAncs = false
            clearCentralPendingTerminalStateObservation()
            queueCentralConnectIntent(peripheral,
                reason: "same stable owner after \(hardReset)", delay: 0.5)
        } else {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            queueTerminalCentralReconnect(peripheral,
                reason: "same owner after didFailToConnect")
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        handleCentralDisconnect(peripheral, isReconnecting: false, error: error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        handleCentralDisconnect(peripheral, isReconnecting: isReconnecting, error: error)
    }

    private func handleCentralDisconnect(_ peripheral: CBPeripheral, isReconnecting: Bool,
                                         error: Error?) {
        guard peripheral === geelyPeripheral else { return }
        clearCentralRestoredB4Hint()
        append("Central disconnected · systemReconnect=\(isReconnecting) · "
            + (error?.localizedDescription ?? "без ошибки"))
        // CONTRACT_V41_RECLAIM_DIDDISCONNECT_TERMINAL_REOPENS_ONCE: the cancel terminal is
        // consumed before any source flag can queue an ordinary replacement or reset this lineage.
        if centralDeferredReclaimPendingTerminal {
            clearCentralPairRestorationCapability()
            clearCentralPairTranscript(reason: "accepted reclaim disconnect terminal")
            reopenCentralDeferredReclaimAfterTerminal(
                peripheral, callback: "didDisconnect")
            return
        }
        if consumeCentralRestorationPublicationPostReopenTerminal(
            peripheral,
            callback: "didDisconnect",
            systemReconnecting: isReconnecting) {
            return
        }
        recordCentralDeferredReclaimTerminalBoundary()
        advanceCentralRestorationPublicationBoundaryAfterTerminal(peripheral)
        let hardReset = centralHardResetReason
        centralHardResetReason = nil
        centralHelperConfirmed = false
        centralB4Subscribed = false
        centralAncsCccdConfirmed = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        if centralRestorationReconnectPending {
            reopenRestoredOwnerAfterTerminalCallback(peripheral,
                callback: "didDisconnect")
            return
        }
        if centralManualReconnectPending {
            reopenManualOwnerAfterTerminalCallback(peripheral,
                callback: "didDisconnect")
            return
        }
        if let hardReset = hardReset {
            centralRequireFreshAdvertisement = false
            clearCentralRuntime(keepPeripheral: true)
            guard runRequested, role == .central else { return }
            centralOwnerConfiguredForAncs = false
            clearCentralPendingTerminalStateObservation()
            queueCentralConnectIntent(peripheral,
                reason: "same stable owner after \(hardReset)", delay: 0.5)
            return
        }

        // An ordinary radio loss keeps the restored CBPeripheral, F05 GATT server and Core
        // Bluetooth auto-reconnect request. Never cancel a system-owned attempt.
        centralRequireFreshAdvertisement = false
        clearCentralRuntime(keepPeripheral: true)
        guard runRequested, role == .central else { return }
        if isReconnecting {
            centralOwnerConfiguredForAncs = true
            centralSystemAutoReconnectActive = true
            append("Core Bluetooth auto-reconnect retained; no cancel and no second connect")
            setStatus("CENTRAL · SYSTEM RECONNECT", color: .systemBlue)
            return
        }
        // CONTRACT_V39_TERMINAL_MATERIALIZES_EXACT_INTENT: isReconnecting=false is a terminal
        // boundary. Keep the identity, pair and F04/F05 services, but represent the next request
        // explicitly. A matching late didConnect atomically consumes this intent above.
        queueTerminalCentralReconnect(peripheral,
            reason: "ordinary disconnect; retained exact owner")
    }

    func centralManager(_ central: CBCentralManager,
                        didUpdateANCSAuthorizationFor peripheral: CBPeripheral) {
        guard peripheral === geelyPeripheral else { return }
        centralAncsAuthorized = peripheral.ancsAuthorized
        centralAncsAuthorizationCallbackObserved = true
        if !centralAncsAuthorized {
            // CONTRACT_V39_EXPLICIT_AUTH_FALSE_INVALIDATES_CURRENT_PROOF: an observed revoke is
            // stronger than the previous Android CCCD proof. Keep the physical link and B3/READY
            // state, but require a new current-owner ANCS-SUBSCRIBED before green can return.
            centralAncsAccessProven = false
            centralAncsCccdConfirmed = false
        }
        append("ANCS authorization changed · allowed=\(centralAncsAuthorized)"
            + " · accessProof=\(centralAncsAccessProven)"
            + " · handshake=\(centralHandshake); link retained")
        // CONTRACT_V39_AUTH_UPDATE_NEVER_TEARS_LINK: Android's current-owner CCCD result proves
        // data-path availability. A false privacy callback revokes that old proof, but never
        // cancels this owner or suppresses the already B3-gated ANCS-READY write.
        refreshCentralReadiness(centralAncsAuthorized
            ? "iPhone ANCS authorization allowed"
            : "iPhone ANCS authorization denied; waiting for new Android proof")
        updateConnectionStatus()
    }
}

extension ViewController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .discovering else { return }
        if let error = error {
            let detail = centralErrorDescription(error)
            append("Discover services error: \(detail)")
            if centralFreshF04ValidationPending {
                continueCentralFreshF04Validation(
                    peripheral, reason: "replacement service discovery failed · \(detail)")
                return
            }
            scheduleCentralServiceRediscovery(
                peripheral, reason: "service discovery failed · \(detail)")
            return
        }
        guard let service = peripheral.services?.first(where: {
            $0.uuid == serviceUUID
        }) else {
            append("Stable F04 service отсутствует после подключения")
            if centralFreshF04ValidationPending {
                continueCentralFreshF04Validation(
                    peripheral, reason: "replacement exact F04 not published yet")
                return
            }
            scheduleCentralServiceRediscovery(peripheral, reason: "stable F04 missing")
            return
        }
        if let previousService = centralService,
           previousService !== service {
            clearCentralPairRestorationCapability()
            clearCentralPairTranscript(reason: "exact F04 service object changed")
        } else if let transcriptService = centralPairChallengeService,
                  transcriptService !== service {
            clearCentralPairTranscript(reason: "exact F04 service object changed")
        }
        if centralFreshF04ValidationPending,
           let invalidated = centralInvalidatedF04Service,
           service === invalidated {
            // CONTRACT_V41_DID_DISCOVER_SERVICES_REJECTS_INVALIDATED_OBJECT_EARLY: never inspect
            // characteristics or issue discovery/PAIR against the exact object Service Changed
            // invalidated, including when Core Bluetooth returns it with `characteristics=nil`.
            continueCentralFreshF04Validation(
                peripheral, reason: "didDiscoverServices returned invalidated exact object")
            return
        }
        centralService = service
        if useCurrentCentralCharacteristics(peripheral, service: service,
                                            source: "current Core Bluetooth cache") {
            return
        }
        scheduleCentralCharacteristicDiscovery(
            peripheral, service: service, reason: "current service callback")
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .discovering,
              service.uuid == serviceUUID,
              let activeService = centralService,
              activeService === service,
              peripheral.services?.contains(where: { $0 === service }) == true else {
            append("Игнорирую stale characteristic callback от прошлого D2D9 поколения")
            return
        }
        if let error = error {
            let detail = centralErrorDescription(error)
            if centralFreshF04ValidationPending {
                append("Replacement F04 characteristic discovery remains read-only · \(detail)")
                continueCentralFreshF04Validation(
                    peripheral, reason: "replacement characteristic error · \(detail)")
                return
            }
            if isCentralUuidNotAllowedError(error) {
                if useCurrentCentralCharacteristics(
                    peripheral, service: service,
                    source: "current cache after uuidNotAllowed"
                ) {
                    return
                }
                // Repeating the identical discovery on this ATT owner reproduced CBError 8
                // indefinitely in the in-car trace. This is an exceptional corrupt/stale GATT
                // database, so request a new namespace; ordinary radio loss never takes this path.
                append("Characteristic discovery запрещён на текущем owner; "
                    + "не повторяю ту же операцию, запрашиваю свежий namespace")
                resetCentralLink(reason: "uuidNotAllowed on current ATT owner · \(detail)")
                return
            }
            append("Discover characteristics error: \(detail)")
            resetCentralLink(reason: "characteristic discovery failed · "
                + detail)
            return
        }
        guard useCurrentCentralCharacteristics(peripheral, service: service,
                                               source: "fresh characteristic callback") else {
            if centralFreshF04ValidationPending {
                append("Replacement F04 incomplete; continue sparse exact-service validation")
                continueCentralFreshF04Validation(
                    peripheral, reason: "replacement B2/B3/B4 incomplete")
                return
            }
            append("Geely_ANCS incomplete: CONTROL/SECURE/WAKE required; повторяю без overlap")
            recoverCentralCharacteristicDiscovery(
                peripheral, service: service, reason: "B2/B3 incomplete")
            return
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        if let currentControl = centralControlCharacteristic,
           characteristic === currentControl,
           centralHandshake == .writingPair {
            if let error = error {
                let detail = centralErrorDescription(error)
                append("WRITE PAIR error: \(detail)")
                resetCentralLink(reason: "PAIR write failed · \(detail)")
            } else {
                append("WRITE PAIR accepted; проверяю текущий ATT link")
                readCentralSecure(peripheral)
            }
            return
        }
        if let currentSecure = centralSecureCharacteristic,
           characteristic === currentSecure,
           centralHandshake == .writingAncsReady {
            if let error = error {
                let detail = centralErrorDescription(error)
                append("WRITE ANCS-READY error: \(detail)")
                resetCentralLink(reason: "same-owner ANCS-READY failed · \(detail)")
            } else {
                centralHandshake = .ready
                centralHelperConfirmed = true
                publishServiceIfPossible()
                publishTelemetry(reason: "single-owner helper confirmed", force: true)
                append("ANCS-READY accepted on the original RequiresANCS owner; link retained")
                enableCentralWakeSubscription(peripheral, reason: "same-owner ANCS-READY")
                refreshCentralReadiness("Helper ANCS-READY")
                observeCentralReadiness("same-owner ANCS-READY")
            }
            return
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected else { return }
        if let currentWake = centralWakeCharacteristic,
           characteristic === currentWake,
           centralHandshake == .ready {
            if let error = error {
                append("Android B4 wake notification error: \(centralErrorDescription(error))")
                scheduleCentralWakeSubscriptionRetry(
                    peripheral, reason: "wake notification error")
            } else {
                publishTelemetry(reason: "KX11 background wake poll", force: true)
            }
            return
        }
        guard let currentSecure = centralSecureCharacteristic,
              characteristic === currentSecure,
              centralHandshake == .readingSecure else { return }
        if let error = error {
            retryCentralSecure(peripheral, error: error)
            return
        }
        markCentralReady(peripheral, value: characteristic.value)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              centralHandshake == .ready,
              let currentWake = centralWakeCharacteristic,
              characteristic === currentWake else { return }
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        if let error = error {
            append("Android B4 wake CCCD error: \(centralErrorDescription(error))")
            scheduleCentralWakeSubscriptionRetry(peripheral, reason: "CCCD error")
            return
        }
        if characteristic.isNotifying {
            centralWakeSubscriptionAttempt = 0
            append("Android B4 wake CCCD confirmed; background telemetry wake active")
            publishTelemetry(reason: "wake CCCD confirmed", force: true)
        } else {
            scheduleCentralWakeSubscriptionRetry(peripheral, reason: "CCCD disabled")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard runRequested, role == .central,
              peripheral === geelyPeripheral,
              peripheral.state == .connected,
              let currentService = centralService,
              invalidatedServices.contains(where: { $0 === currentService }) else { return }
        // CONTRACT_V41_SERVICE_CHANGED_INVALIDATION_ALONE_NEVER_REARMS: exact identity proves
        // which old object died, but not that Android has already published a replacement.
        // CONTRACT_V41_SERVICE_CHANGED_INVALIDATES_OLD_PROTOCOL_PROOF: do this synchronously
        // before any replacement discovery. The physical owner and both recovery budgets remain
        // intact, but no readiness fact from the dead exact F04 object may cross generations.
        // CONTRACT_V43_INVALIDATION_IS_EVIDENCE_BOUNDARY_NOT_A_BUDGET: old nonce/token proof
        // cannot cross this edge. A later actual/descendant request must bind protocol 2 again.
        centralAutomaticBoundaryRejectedOwnerID = peripheral.identifier
        if centralDeferredReclaimOwnerID == peripheral.identifier,
           let boundNonce = centralDeferredReclaimPublicationNonce {
            centralAutomaticBoundaryRejectedPublicationNonce = boundNonce
        } else if centralLastFullGreenOwnerID == peripheral.identifier,
                  let greenNonce = centralLastFullGreenPublicationNonce {
            centralAutomaticBoundaryRejectedPublicationNonce = greenNonce
        } else {
            centralAutomaticBoundaryRejectedPublicationNonce =
                centralLastObservedPublicationNonce
        }
        centralPublicationInvalidationGeneration &+= 1
        clearCentralPairRestorationCapability()
        clearCentralPairTranscript(reason: "exact F04 service invalidated")
        if centralDeferredReclaimActive && !centralDeferredReclaimConsumed {
            invalidateCentralDeferredReclaimRequestEvidence(keepIssuedRequest: false)
        }
        centralAncsReadyWriteIssued = false
        centralSecureLinkReady = false
        centralSecureReadAttempt = 0
        centralLinkSecurityChallengeObserved = false
        centralHelperConfirmed = false
        centralAncsCccdConfirmed = false
        centralB4Subscribed = false
        centralAncsAccessProven = false
        centralReadinessProofWorkItem?.cancel()
        centralReadinessProofWorkItem = nil
        centralInvalidatedF04Service = currentService
        centralFreshF04ValidationPending = true
        centralFreshF04ValidationWorkItem?.cancel()
        centralFreshF04ValidationWorkItem = nil
        centralFreshF04ValidationAttempt = 0
        centralFreshF04ValidationLastLogAt = Date.distantPast
        append("Stable F04 invalidated; destructive budget unchanged until a different exact "
            + "CBService with valid B2/B3/B4 is observed")
        centralSecureRetryWorkItem?.cancel()
        centralSecureRetryWorkItem = nil
        centralWakeSubscriptionWorkItem?.cancel()
        centralWakeSubscriptionWorkItem = nil
        centralWakeSubscriptionAttempt = 0
        centralCharacteristicDiscoveryWorkItem?.cancel()
        centralCharacteristicDiscoveryWorkItem = nil
        centralServiceRediscoveryWorkItem?.cancel()
        centralServiceRediscoveryWorkItem = nil
        centralService = nil
        centralControlCharacteristic = nil
        centralSecureCharacteristic = nil
        centralWakeCharacteristic = nil
        centralHandshake = .discovering
        centralServiceRediscoveryAttempt = 0
        scheduleCentralFreshF04Validation(peripheral, reason: "stable F04 invalidated")
    }
}

extension ViewController: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        append("CBPeripheralManager state=\(peripheral.state.rawValue)")
        guard peripheral.state == .poweredOn else {
            clearPublishedService(allowCentralPairRelayRebind: false)
            if runRequested { setStatus("BLUETOOTH НЕДОСТУПЕН", color: .systemRed) }
            return
        }
        if runRequested { publishServiceIfPossible() }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           willRestoreState dict: [String: Any]) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey]
            as? [CBMutableService] ?? []
        centralPairPeripheralRestoreCallbackObserved = true
        append("Peripheral restore: services=\(services.count)")
        guard runRequested else {
            peripheral.stopAdvertising()
            peripheral.removeAllServices()
            clearPublishedService(allowCentralPairRelayRebind: false)
            return
        }
        // CONTRACT_V39_RESTORED_SERVICE_INSTALLS_EXACT_LINEAGE: restoration supersedes any
        // pre-callback local add attempt and owns a fresh monotonic publication generation.
        let expectedService = role == .central ? telemetryRelayServiceUUID : serviceUUID
        if centralPairRestorationCapabilityGeneration != nil,
           centralPairRestorationRelayCharacteristic != nil {
            if centralPairRestorationRelayProvisional {
                // CBCentralManager restoration may arrive first and temporarily bind the local
                // F05 object visible at that instant. The paired Peripheral restoration callback
                // is the same launch episode, so replace that provisional half without consuming
                // owner/F04/Q authority or replaying Pair.
                clearProvisionalCentralPairRelayBinding()
            } else {
                clearCentralPairRestorationCapability()
            }
        }
        clearPublishedService(
            preserveCentralPairRestorationCapability: role == .central
                && centralPairRestorationCapabilityGeneration != nil
                && centralPairRestorationRelayCharacteristic == nil)
        if let service = services.first(where: { $0.uuid == expectedService }) {
            for characteristic in service.characteristics ?? [] {
                guard let mutable = characteristic as? CBMutableCharacteristic else { continue }
                switch characteristic.uuid {
                case infoUUID: infoCharacteristic = mutable
                case controlUUID: controlCharacteristic = mutable
                case secureUUID: secureCharacteristic = mutable
                case telemetryUUID, telemetryRelayUUID:
                    telemetryCharacteristic = mutable
                    telemetrySubscribers = Set(
                        (mutable.subscribedCentrals ?? []).map { $0.identifier })
                    if role == .central,
                       service.uuid == telemetryRelayServiceUUID,
                       mutable.uuid == telemetryRelayUUID {
                        // CONTRACT_V39_RESTORED_F05_SUBSCRIBER_HALF: retain the exact restored
                        // characteristic object and its restored central IDs. This half never
                        // sets B4 by itself and is safe whether it arrives before or after the
                        // CBCentralManager restoration callback.
                        centralRestoredF05Characteristic = mutable
                        centralRestoredF05SubscriberIDs = Set(
                            (mutable.subscribedCentrals ?? []).map { $0.identifier })
                    }
                default: break
                }
            }
            servicePublished = role == .central
                ? telemetryCharacteristic != nil
                : infoCharacteristic != nil && controlCharacteristic != nil
                    && secureCharacteristic != nil && telemetryCharacteristic != nil
            if servicePublished {
                publishedLocalService = service
                publishedLocalServiceGeneration = localServicePublicationGeneration
                publishedServiceUUID = service.uuid
                if role == .central {
                    _ = bindFirstCentralPairRestorationRelayIfEligible()
                    if centralPairAwaitingRelayRebind,
                       !rebindCurrentCentralPairRelayIfEligible() {
                        clearCentralPairTranscript(
                            reason: "restored F05 relay rebind lineage mismatch")
                    }
                }
            }
        }
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.runRequested else { return }
            if self.servicePublished {
                if self.role == .peripheral { self.startAdvertising() }
                else {
                    if !self.centralRestoredB4HintConsumed {
                        self.centralB4Subscribed = false
                    }
                    self.append("Restored F05 subscriber list retained as one half of exact "
                        + "two-sided restoration proof; it cannot establish readiness alone")
                    self.updateConnectionStatus()
                    self.startCentralRouteIfPossible()
                }
            } else { self.publishServiceIfPossible() }
            self.publishTelemetry(reason: "state restoration", force: true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService,
                           error: Error?) {
        guard let pending = pendingLocalService,
              let pendingGeneration = pendingLocalServiceGeneration,
              service === pending,
              pendingGeneration == localServicePublicationGeneration else {
            // CONTRACT_V39_STALE_DIDADD_IS_OBSERVATION_ONLY: never clear flags, remove services,
            // publish a route, or disturb the exact newer generation for a late same-UUID add.
            append("Ignoring stale local GATT didAdd callback · uuid=\(service.uuid.uuidString)")
            return
        }
        let expectedService = role == .central ? telemetryRelayServiceUUID : serviceUUID
        guard runRequested, service.uuid == expectedService else {
            peripheral.remove(pending)
            clearPublishedService(allowCentralPairRelayRebind: false)
            return
        }
        serviceAddPending = false
        pendingLocalService = nil
        pendingLocalServiceGeneration = nil
        if let failure = error {
            append("didAdd service error: \(failure.localizedDescription)")
            setStatus("ОШИБКА GATT", color: .systemRed)
            clearPublishedService(allowCentralPairRelayRebind: false)
            return
        }
        servicePublished = true
        publishedLocalService = pending
        publishedLocalServiceGeneration = pendingGeneration
        publishedServiceUUID = service.uuid
        if role == .central {
            append("B4 telemetry relay опубликован на iPhone GATT owner")
            _ = bindFirstCentralPairRestorationRelayIfEligible()
            if rebindCurrentCentralPairRelayIfEligible() {
                append("Current P/Q+F04 transcript rebound to new exact F05; "
                    + "waiting for fresh L/Q without Pair replay")
            } else if centralPairAwaitingRelayRebind {
                clearCentralPairTranscript(reason: "F05 relay rebind lineage mismatch")
            } else if let owner = geelyPeripheral {
                resumeCentralPairAfterFirstRelayPublication(owner)
            }
            updateConnectionStatus()
            DispatchQueue.main.async { [weak self] in
                self?.startCentralRouteIfPossible()
            }
        } else {
            append("Единый GATT \(logicalName) опубликован")
            startAdvertising()
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager,
                                               error: Error?) {
        guard runRequested, role == .peripheral else {
            peripheral.stopAdvertising()
            return
        }
        if let failure = error {
            append("Advertising error: \(failure.localizedDescription)")
            setStatus("ОШИБКА BLE-РЕКЛАМЫ", color: .systemRed)
            return
        }
        append("\(logicalName) advertising active")
        updateConnectionStatus()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        guard runRequested else { return }
        if role == .central {
            guard let currentTelemetry = telemetryCharacteristic,
                  characteristic === currentTelemetry else { return }
        } else {
            guard isLocalTelemetryUUID(characteristic.uuid) else { return }
        }
        telemetrySubscribers.insert(central.identifier)
        append("KX11 subscribed B4 · \(central.identifier.uuidString)")
        if role != .central || central.identifier == geelyPeripheral?.identifier {
            confirmCentralB4Subscription("CCCD subscription on current owner")
        } else {
            append("B4 subscription сохранена, но не относится к current Central owner")
        }
        updateConnectionStatus()
        publishTelemetry(reason: "B4 subscribed", force: true)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        if role == .central {
            guard let currentTelemetry = telemetryCharacteristic,
                  characteristic === currentTelemetry else { return }
        } else {
            guard isLocalTelemetryUUID(characteristic.uuid) else { return }
        }
        telemetrySubscribers.remove(central.identifier)
        append("KX11 unsubscribed B4 · \(central.identifier.uuidString)")
        if role == .central && central.identifier == geelyPeripheral?.identifier {
            // Rebuild only the reverse Android client. The protected iPhone Central owner and
            // its auto-reconnect request remain untouched.
            clearCentralPairTranscript(reason: "current owner B4 unsubscribe")
            centralB4Subscribed = false
            centralAncsCccdConfirmed = false
            centralAncsAccessProven = false
            append("Current P/Q + alias/B4/ANCS transcript invalidated; owner retained. "
                + "No Pair replay until attributable F04/publication/terminal boundary")
            refreshCentralReadiness("B4 unsubscribe")
        }
        updateTelemetryLabel()
        updateConnectionStatus()
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainTelemetryNotification()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveRead request: CBATTRequest) {
        guard runRequested else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        if role == .central {
            guard let currentTelemetry = telemetryCharacteristic,
                  request.characteristic === currentTelemetry else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
                return
            }
        }
        guard let fullValue = responseData(for: request.characteristic) else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        guard request.offset >= 0, request.offset <= fullValue.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = fullValue.subdata(in: request.offset..<fullValue.count)
        peripheral.respond(to: request, withResult: .success)
        if isLocalTelemetryUUID(request.characteristic.uuid) {
            let now = Date()
            lastAndroidReadAt = now
            updateTelemetryLabel()
            // The label shows every one-second read. Keep the on-screen journal bounded enough
            // for long drives by writing a proof line only once per 30 seconds.
            if now.timeIntervalSince(lastAndroidReadLogAt) >= 30 {
                lastAndroidReadLogAt = now
                append("KX11 READ B4 LIVE · 8 bytes · \(request.central.identifier.uuidString)")
            }
            updateConnectionStatus()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        guard runRequested else {
            requests.forEach { peripheral.respond(to: $0, withResult: .requestNotSupported) }
            return
        }
        for request in requests {
            if role == .central {
                let linkChallenge = centralProofChallenge(
                    from: request.value, opcode: centralLinkBoundOpcode)
                let ancsChallenge = centralProofChallenge(
                    from: request.value, opcode: centralAncsSubscribedOpcode)
                var accepted = false
                var label = "UNKNOWN"
                var confirmsAncs = false
                if let challenge = linkChallenge {
                    label = "L/Q"
                    if centralProofRequestIsCurrent(request, challenge: challenge),
                       !centralAncsAccessProven,
                       !centralAncsCccdConfirmed,
                       let owner = geelyPeripheral {
                        accepted = bindCentralAlias(owner, challenge: challenge)
                    }
                } else if let challenge = ancsChallenge {
                    label = "A/Q"
                    if centralProofRequestIsCurrent(request, challenge: challenge),
                       let owner = geelyPeripheral,
                       centralAliasBindingMatches(owner, challenge: challenge) {
                        accepted = true
                        confirmsAncs = true
                    }
                }
                peripheral.respond(to: request,
                                   withResult: accepted ? .success : .requestNotSupported)
                append("KX11 WRITE F05/B4 \(label) "
                    + (accepted ? "OK" : "REJECTED"))
                if accepted && confirmsAncs {
                    confirmCentralAncsReady("F05/B4 A/Q after exact LINK-BOUND")
                }
                continue
            }

            // Legacy ASCII commands are diagnostic/bootstrap compatibility for Peripheral role
            // only. Managed Central proof above never decodes or accepts a plain-text command.
            let command = request.value.flatMap { String(data: $0, encoding: .utf8) }?
                .trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
            let bootstrapCommand = role == .peripheral
                && ((request.characteristic.uuid == controlUUID && command == "PAIR")
                    || (request.characteristic.uuid == secureUUID && command == "ANCS"))
            let accepted = request.offset == 0 && bootstrapCommand
            peripheral.respond(to: request,
                               withResult: accepted ? .success : .requestNotSupported)
            append("KX11 WRITE \(request.characteristic.uuid.uuidString) `\(command)` "
                + (accepted ? "OK" : "REJECTED"))
        }
    }
}
