import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var draftURL = ""
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Settings & Diagnostics").font(.headline)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.semibold))
                        .frame(width: 26, height: 26)
                        .background(.quaternary, in: Circle())
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.cancelAction)
                .help("Back to overview")
            }.padding(16)
            Divider()
            Form {
                Section("Gateway") {
                    TextField("Server URL", text: $draftURL)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(saveURL)
                    HStack {
                        Button("Save & Reconnect", action: saveURL)
                        Spacer()
                        Text(connectionDescription).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section("Behavior") {
                    Toggle("Lifecycle notifications", isOn: $model.notificationsEnabled)
                    Button("Send Test Notification") {
                        Task {
                            await NotificationManager.shared.requestAuthorization()
                            NotificationManager.shared.deliverTest()
                        }
                    }
                    .disabled(!model.notificationsEnabled)
                    Toggle("Launch at login", isOn: Binding(
                        get: { model.launchAtLogin },
                        set: { model.setLaunchAtLogin($0) }
                    ))
                    if let error = model.lastConfigurationError {
                        Text(error).font(.caption).foregroundStyle(.orange)
                    }
                }
                Section("Connection") {
                    LabeledContent("Status", value: connectionDescription)
                    LabeledContent("Reconnect attempts", value: "\(model.reconnectAttempt)")
                    LabeledContent("Last SSE message", value: model.lastMessageAt.map { $0.formatted(date: .omitted, time: .standard) } ?? "Never")
                    LabeledContent("Last full snapshot", value: model.lastSnapshotAt.map { $0.formatted(date: .omitted, time: .standard) } ?? "Never")
                    LabeledContent("Active / recent", value: "\(model.payload.active.count) / \(model.payload.recent.count)")
                    if !model.payload.warnings.isEmpty {
                        ForEach(model.payload.warnings, id: \.self) { Text($0).font(.caption).foregroundStyle(.orange) }
                    }
                }
                Section("Preview") {
                    HStack {
                        Button("Idle") { model.loadSimulation(activeCount: 0) }
                        Button("One session") { model.loadSimulation(activeCount: 1) }
                        Button("Two sessions") { model.loadSimulation(activeCount: 2) }
                    }
                    Text("Preview data is replaced by the next full SSE update.").font(.caption).foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .padding(.horizontal, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { draftURL = model.serverURL }
    }

    private func saveURL() {
        model.serverURL = draftURL
        model.reconnect()
    }

    private var connectionDescription: String {
        switch model.connectionState {
        case .stopped: return "Paused"
        case .connecting: return "Connecting"
        case .connected: return "Connected"
        case .offline(let reason): return "Offline · \(reason)"
        }
    }
}
