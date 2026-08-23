import ActivityKit
import AppIntents
import Foundation
import SwiftUI
import WidgetKit

@main
struct NatroLiveActivityBundle: WidgetBundle {
    var body: some Widget { NatroLiveActivityWidget() }
}

struct NatroLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NatroLiveActivityAttributes.self) { context in
            NatroLockScreenView(
                panel: context.attributes.resolvedPanel,
                controls: context.attributes.resolvedControls,
                vehicleName: context.attributes.resolvedVehicleName,
                showVehicle: context.attributes.resolvedShowVehicle,
                state: context.state
            )
            .activityBackgroundTint(Color(
                .sRGB, red: 0.022, green: 0.029, blue: 0.045, opacity: 1
            ))
            .activitySystemActionForegroundColor(.white)
            .widgetURL(URL(string: "natrohelper://live-activity"))
        } dynamicIsland: { context in
            let panel = context.attributes.resolvedPanel
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    NatroMark(compact: true)
                        .frame(width: 24, height: 24, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if panel == .climate {
                        Text(temperature(context.state.targetTemperatureHundredths))
                            .font(.system(size: 20, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.68)
                            .frame(maxWidth: 72, alignment: .trailing)
                    } else {
                        Image(systemName: "slider.horizontal.3")
                            .foregroundStyle(.cyan)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    StatusLine(state: context.state, compact: true)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 7) {
                        ForEach(context.attributes.resolvedControls.indices, id: \.self) { index in
                            let control = context.attributes.resolvedControls[index]
                            NatroControlTile(
                                control: control,
                                index: index,
                                state: context.state,
                                island: true
                            )
                        }
                    }
                    .frame(minHeight: 58)
                    .padding(.top, 3)
                }
            } compactLeading: {
                NatroMark(compact: true)
            } compactTrailing: {
                if panel == .climate {
                    Text(temperature(context.state.targetTemperatureHundredths))
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)
                        .frame(width: 43, alignment: .trailing)
                } else {
                    Image(systemName: "slider.horizontal.3")
                }
            } minimal: {
                Image(systemName: panel == .climate ? "fanblades.fill" : "car.fill")
                    .foregroundStyle(context.state.vehicleConnected ? .cyan : .gray)
            }
            .keylineTint(panel == .climate ? .cyan : .orange)
        }
    }
}

private struct NatroLockScreenView: View {
    let panel: NatroLivePanel
    let controls: [NatroLiveControl]
    let vehicleName: String
    let showVehicle: Bool
    let state: NatroLiveActivityAttributes.ContentState

    var body: some View {
        Group {
            if panel == .climate {
                ClimateActivityView(
                    controls: controls,
                    vehicleName: vehicleName,
                    showVehicle: showVehicle,
                    state: state
                )
            } else {
                FunctionsActivityView(
                    controls: controls,
                    vehicleName: vehicleName,
                    state: state
                )
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .foregroundStyle(.white)
    }
}

private struct ClimateActivityView: View {
    let controls: [NatroLiveControl]
    let vehicleName: String
    let showVehicle: Bool
    let state: NatroLiveActivityAttributes.ContentState

    var body: some View {
        VStack(spacing: 5) {
            NatroHeader(panelTitle: "КЛИМАТ", vehicleName: vehicleName, state: state)
            ZStack {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color.white.opacity(0.075), Color.black.opacity(0.01)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                if showVehicle {
                    Image("Monjaro")
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 190, maxHeight: 60)
                        .opacity(state.vehicleConnected ? 1 : 0.55)
                        .offset(x: 26)
                }
                HStack {
                    TemperatureButton(action: "temperature:-1", symbol: "minus", state: state)
                    Spacer()
                    VStack(spacing: 0) {
                        Text(temperature(state.targetTemperatureHundredths))
                            .font(.system(size: 31, weight: .medium, design: .rounded))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                            .frame(maxWidth: 112)
                            .shadow(color: .black.opacity(0.8), radius: 3)
                        Text("задано")
                            .font(.system(size: 9, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    TemperatureButton(action: "temperature:1", symbol: "plus", state: state)
                }
                .padding(.horizontal, 9)
                HStack {
                    Text("Салон \(smallTemperature(state.cabinTemperatureTenths))")
                    Spacer()
                    Text("Улица \(smallTemperature(state.outdoorTemperatureTenths))")
                }
                .font(.system(size: 9, weight: .medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 7)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 3)
            }
            .frame(height: 65)

            HStack(spacing: 6) {
                ForEach(controls.indices, id: \.self) { index in
                    let control = controls[index]
                    NatroControlTile(control: control, index: index, state: state)
                }
            }
            .frame(height: 56)
        }
    }
}

private struct FunctionsActivityView: View {
    let controls: [NatroLiveControl]
    let vehicleName: String
    let state: NatroLiveActivityAttributes.ContentState
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 4)

    var body: some View {
        VStack(spacing: 6) {
            NatroHeader(panelTitle: "БЫСТРЫЕ ФУНКЦИИ", vehicleName: vehicleName, state: state)
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(controls.indices, id: \.self) { index in
                    let control = controls[index]
                    NatroControlTile(
                        control: control,
                        index: index,
                        state: state,
                        functionGrid: true
                    )
                    .frame(height: 94)
                }
            }
        }
    }
}

private struct NatroHeader: View {
    let panelTitle: String
    let vehicleName: String
    let state: NatroLiveActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 7) {
            NatroMark()
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 5) {
                    Text("NATRO")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                    Text(panelTitle)
                        .font(.system(size: 10, weight: .bold, design: .rounded))
                        .foregroundStyle(.cyan)
                }
                StatusLine(state: state, compact: false)
            }
            if state.isDemo {
                Text("ДЕМО")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(.orange.opacity(0.16), in: Capsule())
            }
            Spacer(minLength: 6)
            Text(vehicleName.uppercased())
                .font(.system(size: 9, weight: .semibold, design: .rounded))
                .tracking(1.1)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .frame(maxWidth: 104, alignment: .trailing)
        }
        .frame(height: 22)
    }
}

private struct StatusLine: View {
    let state: NatroLiveActivityAttributes.ContentState
    let compact: Bool

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(state.vehicleConnected ? Color.green : Color.gray)
                .frame(width: compact ? 7 : 6, height: compact ? 7 : 6)
            Text(state.status)
                .font(.system(size: compact ? 13 : 10, weight: .medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
    }
}

private struct NatroControlTile: View {
    let control: NatroLiveControl
    let index: Int
    let state: NatroLiveActivityAttributes.ContentState
    var island = false
    var functionGrid = false

    private var available: Bool { state.isAvailable(at: index) }
    private var active: Bool { state.isActive(at: index) }
    private var snapshot: NatroLiveControlSnapshot? {
        state.snapshot(for: control.controlID, at: index)
    }

    var body: some View {
        Group {
            if #available(iOSApplicationExtension 17.0, *) {
                Button(intent: NatroLiveControlIntent(action: control.commandAction)) {
                    label
                }
                .buttonStyle(.plain)
            } else {
                label
            }
        }
        .disabled(!state.vehicleConnected || !available)
        .opacity(available ? 1 : 0.35)
    }

    private var label: some View {
        VStack(spacing: functionGrid ? 3 : 2) {
            HStack(spacing: 2) {
                Image(systemName: control.systemImage)
                    .font(.system(size: island ? 18 : (functionGrid ? 21 : 20), weight: .bold))
                    .foregroundStyle(active ? accent : Color.white.opacity(0.9))
                if control.requiresConfirmation {
                    Image(systemName: "lock.fill")
                        .font(.system(size: island ? 9 : 10, weight: .bold))
                        .foregroundStyle(.secondary)
                }
            }
            Text(control.title)
                .font(.system(size: island ? 12 : 14, weight: .bold, design: .rounded))
                .multilineTextAlignment(.center)
                .lineLimit(functionGrid ? 2 : 1)
                .minimumScaleFactor(0.82)
            if control.isThreeStage {
                StageIndicator(
                    level: snapshot?.level ?? 0,
                    automatic: snapshot?.automatic == true,
                    color: accent
                )
            } else if functionGrid, snapshot?.known == true {
                Text(control.compactValue(snapshot?.value ?? 0, active: active))
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(active ? accent : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 2)
        .padding(.vertical, island ? 4 : 3)
        .background(
            RoundedRectangle(cornerRadius: island ? 10 : 11, style: .continuous)
                .fill(active ? accent.opacity(0.17) : Color.white.opacity(0.055))
        )
        .overlay(
            RoundedRectangle(cornerRadius: island ? 10 : 11, style: .continuous)
                .stroke(active ? accent.opacity(0.36) : Color.white.opacity(0.06), lineWidth: 0.7)
        )
    }

    private var accent: Color { control.isVentilation ? .cyan : .orange }
}

private struct StageIndicator: View {
    let level: Int
    let automatic: Bool
    let color: Color

    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...3, id: \.self) { index in
                Capsule()
                    .fill(index <= level ? color : Color.white.opacity(0.13))
                    .frame(height: 4)
            }
            if automatic {
                Text("A")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(color)
            }
        }
        .frame(maxWidth: 38)
    }
}

private struct TemperatureButton: View {
    let action: String
    let symbol: String
    let state: NatroLiveActivityAttributes.ContentState

    var body: some View {
        Group {
            if #available(iOSApplicationExtension 17.0, *) {
                Button(intent: NatroLiveControlIntent(action: action)) { circle }
                    .buttonStyle(.plain)
            } else {
                circle
            }
        }
        .disabled(!state.vehicleConnected || state.targetTemperatureHundredths == nil)
    }

    private var circle: some View {
        Image(systemName: symbol)
            .font(.system(size: 18, weight: .semibold))
            .frame(width: 40, height: 40)
            .background(.black.opacity(0.56), in: Circle())
            .overlay(Circle().stroke(.white.opacity(0.08), lineWidth: 1))
    }
}

private struct NatroMark: View {
    var compact = false

    var body: some View {
        Text("N")
            .font(.system(size: compact ? 15 : 18, weight: .black, design: .rounded))
            .italic()
            .foregroundStyle(
                LinearGradient(colors: [.cyan, .white], startPoint: .topLeading,
                               endPoint: .bottomTrailing)
            )
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .frame(width: compact ? 20 : 26, height: compact ? 20 : 24)
    }
}

private func temperature(_ hundredths: Int16?) -> String {
    guard let hundredths else { return "—°" }
    return String(format: "%.1f°", Double(hundredths) / 100)
}

private func smallTemperature(_ tenths: Int16?) -> String {
    guard let tenths else { return "—°" }
    return String(format: "%.0f°", Double(tenths) / 10)
}
