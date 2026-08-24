import SwiftUI

@main
struct ClawIslandMacApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            DashboardView()
                .environmentObject(model)
        } label: {
            HStack(spacing: 3) {
                Image(systemName: menuSymbol)
                if !model.payload.active.isEmpty {
                    Text("\(model.payload.active.count)")
                        .monospacedDigit()
                }
            }
            .accessibilityLabel(menuTitle)
        }
        .menuBarExtraStyle(.window)
    }

    private var menuTitle: String {
        model.payload.active.isEmpty ? "Claw Island · idle" : "Claw Island · \(model.payload.active.count) active"
    }

    private var menuSymbol: String {
        if !model.isConnected { return "antenna.radiowaves.left.and.right.slash" }
        return model.payload.active.isEmpty ? "moon.zzz" : "bolt.horizontal.circle.fill"
    }
}
