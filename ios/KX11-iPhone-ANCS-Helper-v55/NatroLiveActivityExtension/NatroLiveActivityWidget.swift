import ActivityKit
import AppIntents
import Foundation
import SwiftUI
import WidgetKit

@main
struct NatroLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        NatroLiveActivityWidget()
    }
}

struct NatroLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NatroLiveActivityAttributes.self) { context in
            NatroLockScreenView(state: context.state)
                .activityBackgroundTint(Color(red: 0.025, green: 0.035, blue: 0.055))
                .activitySystemActionForegroundColor(.white)
                .widgetURL(URL(string: "natrohelper://live-activity"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    NatroMark()
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(temperature(context.state.targetTemperatureHundredths))
                        .font(.system(size: 20, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                }
                DynamicIslandExpandedRegion(.center) {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(context.state.ancsConnected ? Color.green : Color.gray)
                            .frame(width: 7, height: 7)
                        Text(context.state.status)
                            .font(.caption2)
                            .lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 7) {
                        ForEach(context.state.controls, id: \.self) { control in
                            NatroControlButton(control: control, state: context.state, compact: true)
                        }
                    }
                    .padding(.top, 2)
                }
            } compactLeading: {
                NatroMark(compact: true)
            } compactTrailing: {
                Text(temperature(context.state.targetTemperatureHundredths))
                    .font(.caption.bold())
                    .monospacedDigit()
            } minimal: {
                Image(systemName: context.state.ancsConnected ? "car.fill" : "car")
                    .foregroundStyle(context.state.ancsConnected ? .green : .gray)
            }
            .keylineTint(.cyan)
        }
    }
}

private struct NatroLockScreenView: View {
    let state: NatroLiveActivityAttributes.ContentState

    var body: some View {
        VStack(spacing: 4) {
            header
            vehicle
            HStack(spacing: 7) {
                ForEach(state.controls, id: \.self) { control in
                    NatroControlButton(control: control, state: state, compact: false)
                }
            }
            .frame(height: 43)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .foregroundStyle(.white)
    }

    private var header: some View {
        HStack(spacing: 7) {
            NatroMark()
            VStack(alignment: .leading, spacing: 0) {
                Text("NATRO")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                HStack(spacing: 4) {
                    Circle()
                        .fill(state.ancsConnected ? Color.green : Color.gray)
                        .frame(width: 6, height: 6)
                    Text(state.status)
                        .font(.system(size: 9, weight: .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            if state.isDemo {
                Text("ДЕМО")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(.orange.opacity(0.16), in: Capsule())
            }
            Spacer(minLength: 6)
            Text(state.vehicleName.uppercased())
                .font(.system(size: 9, weight: .semibold, design: .rounded))
                .tracking(1.2)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .frame(maxWidth: 105, alignment: .trailing)
        }
        .frame(height: 24)
    }

    private var vehicle: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color.white.opacity(0.06), Color.black.opacity(0.02)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
            if state.showVehicle {
                Image("Monjaro")
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 182, maxHeight: 62)
                    .opacity(state.ancsConnected ? 1 : 0.62)
            }
            HStack {
                commandButton(action: "temperature:-1", symbol: "minus")
                Spacer()
                VStack(spacing: 0) {
                    Text(temperature(state.targetTemperatureHundredths))
                        .font(.system(size: 27, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .shadow(color: .black, radius: 3)
                    Text("задано")
                        .font(.system(size: 8))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                commandButton(action: "temperature:1", symbol: "plus")
            }
            .padding(.horizontal, 9)
            .padding(.bottom, 1)

            HStack {
                Text("Салон \(smallTemperature(state.cabinTemperatureTenths))")
                Spacer()
                Text("Улица \(smallTemperature(state.outdoorTemperatureTenths))")
            }
            .font(.system(size: 8, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 7)
            .frame(maxHeight: .infinity, alignment: .bottom)
            .padding(.bottom, 3)
        }
        .frame(height: 62)
    }

    @ViewBuilder
    private func commandButton(action: String, symbol: String) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            Button(intent: NatroLiveControlIntent(action: action)) {
                circleButton(symbol: symbol)
            }
            .buttonStyle(.plain)
            .disabled(!state.ancsConnected || !state.availableControlIDs.contains(11))
        } else {
            circleButton(symbol: symbol)
        }
    }

    private func circleButton(symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 15, weight: .semibold))
            .frame(width: 34, height: 34)
            .background(.black.opacity(0.56), in: Circle())
            .overlay(Circle().stroke(.white.opacity(0.08), lineWidth: 1))
    }
}

private struct NatroControlButton: View {
    let control: NatroLiveControl
    let state: NatroLiveActivityAttributes.ContentState
    let compact: Bool

    private var active: Bool { state.activeControlIDs.contains(Int(control.controlID)) }
    private var available: Bool {
        state.ancsConnected && state.availableControlIDs.contains(Int(control.controlID))
    }
    private var activeColor: Color {
        control == .driverSeatHeat ? .orange : Color(red: 0.19, green: 0.62, blue: 1)
    }

    var body: some View {
        Group {
            if #available(iOSApplicationExtension 17.0, *) {
                Button(intent: NatroLiveControlIntent(action: control.commandAction)) {
                    tile
                }
                .buttonStyle(.plain)
                .disabled(!available)
            } else {
                tile
            }
        }
        .opacity(available ? 1 : 0.48)
    }

    private var tile: some View {
        VStack(spacing: compact ? 2 : 3) {
            Image(systemName: control.systemImage)
                .font(.system(size: compact ? 15 : 17, weight: .medium))
                .foregroundStyle(active ? activeColor : Color.white)
            Text(control.title)
                .font(.system(size: compact ? 7 : 8, weight: .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.65)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, compact ? 5 : 4)
        .background(
            active ? activeColor.opacity(0.18) : Color.white.opacity(0.055),
            in: RoundedRectangle(cornerRadius: compact ? 10 : 12, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: compact ? 10 : 12, style: .continuous)
                .stroke(active ? activeColor.opacity(0.35) : .white.opacity(0.06), lineWidth: 1)
        )
    }
}

private struct NatroMark: View {
    var compact = false

    var body: some View {
        Text("N")
            .font(.system(size: compact ? 13 : 17, weight: .black, design: .rounded))
            .italic()
            .foregroundStyle(
                LinearGradient(colors: [.white, .cyan], startPoint: .top, endPoint: .bottom)
            )
            .frame(width: compact ? 18 : 25, height: compact ? 18 : 25)
    }
}

private func temperature(_ hundredths: Int?) -> String {
    guard let hundredths else { return "—°" }
    return String(format: "%.1f°", Double(hundredths) / 100)
}

private func smallTemperature(_ tenths: Int?) -> String {
    guard let tenths else { return "—°" }
    let value = Double(tenths) / 10
    return String(format: value.rounded() == value ? "%.0f°" : "%.1f°", value)
}
