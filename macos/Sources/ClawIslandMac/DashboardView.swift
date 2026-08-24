import AppKit
import SwiftUI

private enum DashboardPane: String, CaseIterable, Identifiable {
    case active = "Active"
    case recent = "Recent"
    case events = "Events"
    var id: String { rawValue }
}

struct DashboardView: View {
    @EnvironmentObject private var model: AppModel
    @State private var pane = DashboardPane.active
    @State private var selectedSessionID: String?
    @State private var showSettings = false
    @State private var now = Date.now

    var body: some View {
        Group {
            if showSettings {
                SettingsView {
                    showSettings = false
                    pane = .active
                }
                .environmentObject(model)
            } else if let selectedSession {
                SessionDetailView(
                    session: selectedSession,
                    payloadNowTs: model.payload.nowTs,
                    now: now
                ) {
                    selectedSessionID = nil
                    pane = .active
                }
            } else {
                overviewPane
            }
        }
        .frame(width: 440, height: 620)
        .background(.regularMaterial)
        .onDisappear {
            // Permission prompts and login-item registration temporarily move
            // focus away from the menu extra. Keep Settings alive across that
            // transition, but never preserve a session detail card.
            if !showSettings { resetSessionPresentation() }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
            if !showSettings { resetSessionPresentation() }
        }
        .task {
            while !Task.isCancelled {
                now = .now
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    // Keep only the stable identity in navigation state. SessionInfo is a
    // value type, so storing the whole object here would freeze the detail
    // pane at the snapshot from the moment it was opened. Resolving it from
    // the published payload makes todos, phase, latest activity, tokens, and
    // elapsed baselines update with every full SSE snapshot.
    private var selectedSession: SessionInfo? {
        guard let selectedSessionID else { return nil }
        return model.payload.active.first(where: { $0.id == selectedSessionID })
            ?? model.payload.recent.first(where: { $0.id == selectedSessionID })
    }

    private var overviewPane: some View {
        VStack(spacing: 0) {
            header
            Divider()
            overview
            Picker("Pane", selection: $pane) {
                ForEach(DashboardPane.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 14)
            .padding(.bottom, 10)

            ScrollView {
                LazyVStack(spacing: 10) {
                    switch pane {
                    case .active: activePane
                    case .recent: recentPane
                    case .events: eventsPane
                    }
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 14)
            }
            .frame(maxHeight: .infinity)
            Divider()
            footer
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "pawprint.circle.fill")
                .font(.system(size: 28))
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 1) {
                Text("Claw Island").font(.headline)
                Text("OpenCode & Codex sessions").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            ConnectionBadge(state: model.connectionState, active: model.payload.active.count)
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .buttonStyle(.plain)
                .help("Settings and diagnostics")
        }
        .padding(14)
    }

    private var overview: some View {
        HStack(spacing: 0) {
            MetricView(value: "\(model.payload.active.count)", label: "Active", color: .green)
            MetricView(value: "\(model.payload.recent.count)", label: "Recent", color: .blue)
            MetricView(value: SessionFormatting.compactNumber(model.payload.active.reduce(0) { $0 + SessionFormatting.effectiveTokens($1) }), label: "Tokens", color: .purple)
            MetricView(value: model.lastMessageAt.map { SessionFormatting.age(from: Int64($0.timeIntervalSince1970 * 1_000), now: now) } ?? "—", label: "SSE", color: .orange)
        }
        .padding(.vertical, 11)
    }

    @ViewBuilder private var activePane: some View {
        if model.payload.active.isEmpty {
            EmptyPane(symbol: model.isConnected ? "moon.zzz" : "wifi.slash", title: model.isConnected ? "All agents idle" : "Gateway offline", detail: model.isConnected ? "Standing by for new sessions" : "Monitoring will reconnect automatically")
        } else {
            ForEach(model.payload.active) { session in
                ActiveSessionCard(
                    session: session,
                    payloadNowTs: model.payload.nowTs,
                    now: now
                ) { selectedSessionID = session.id }
            }
        }
    }

    @ViewBuilder private var recentPane: some View {
        if model.payload.recent.isEmpty {
            EmptyPane(symbol: "clock", title: "No recent sessions", detail: "Completed sessions will appear here")
        } else {
            ForEach(model.payload.recent) { session in
                RecentSessionRow(session: session, now: now) { selectedSessionID = session.id }
            }
        }
    }

    @ViewBuilder private var eventsPane: some View {
        if model.lifecycleEvents.isEmpty {
            EmptyPane(symbol: "bell", title: "No events this run", detail: "Start, resume, finish, and exit events appear here")
        } else {
            ForEach(model.lifecycleEvents) { event in EventRow(event: event, now: now) }
        }
    }

    private var footer: some View {
        HStack {
            Label("Connection automatic", systemImage: "arrow.trianglehead.2.clockwise.rotate.90")
                .font(.caption).foregroundStyle(.secondary)
            Button("Reconnect now") { model.reconnect() }
            Spacer()
            Button("Quit") { NSApplication.shared.terminate(nil) }
        }
        .padding(12)
    }

    private func resetSessionPresentation() {
        selectedSessionID = nil
        pane = .active
    }
}

private struct ConnectionBadge: View {
    let state: AppModel.ConnectionState
    let active: Int
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(label).font(.caption2.weight(.semibold))
        }
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(color.opacity(0.14), in: Capsule())
    }
    private var color: Color {
        switch state { case .connected: return active > 0 ? .green : .yellow; case .connecting: return .orange; default: return .secondary }
    }
    private var label: String {
        switch state { case .connected: return active > 0 ? "LIVE" : "IDLE"; case .connecting: return "CONNECTING"; case .offline: return "OFFLINE"; case .stopped: return "PAUSED" }
    }
}

private struct MetricView: View {
    let value: String
    let label: String
    let color: Color
    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.headline.monospacedDigit()).foregroundStyle(color).lineLimit(1)
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity)
    }
}

private struct ActiveSessionCard: View {
    let session: SessionInfo
    let payloadNowTs: Int64
    let now: Date
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    SourceBadge(source: session.source)
                    Text(session.repo.isEmpty ? "Unknown repo" : session.repo).font(.caption.weight(.semibold))
                    Spacer()
                    Text(SessionFormatting.duration(liveElapsed)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                Text(session.title).font(.headline).lineLimit(2).multilineTextAlignment(.leading)
                if !session.lastActivity.isEmpty {
                    Text(session.lastActivity).font(.caption).foregroundStyle(.secondary).lineLimit(2).multilineTextAlignment(.leading)
                }
                if session.todo.total > 0 {
                    ProgressView(value: Double(session.todo.completed), total: Double(session.todo.total)).tint(.green)
                    Text("✓ \(session.todo.completed)/\(session.todo.total) · ● \(session.todo.inProgress) in progress")
                        .font(.caption2).foregroundStyle(.secondary)
                    if let current = session.todo.items.first(where: { $0.status == "in_progress" }) {
                        HStack(alignment: .firstTextBaseline, spacing: 5) {
                            Text("●").foregroundStyle(Color.accentColor)
                            Text(current.content)
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.primary)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                }
                HStack {
                    Text(session.model).lineLimit(1)
                    Spacer()
                    Text(SessionFormatting.usageSummary(session))
                }.font(.caption2).foregroundStyle(.secondary)
                if staleness > 30 {
                    Label("last updated \(SessionFormatting.age(from: session.updatedAt, now: now))", systemImage: "clock.badge.exclamationmark")
                        .font(.caption2).foregroundStyle(.orange)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }
    private var liveElapsed: Int {
        SessionFormatting.liveElapsed(session, payloadNowTs: payloadNowTs, now: now)
    }
    private var staleness: Int { max(0, Int(now.timeIntervalSince1970 - Double(session.updatedAt) / 1_000)) }
}

private struct RecentSessionRow: View {
    let session: SessionInfo
    let now: Date
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                SourceBadge(source: session.source)
                VStack(alignment: .leading, spacing: 2) {
                    Text(session.title).lineLimit(1)
                    Text(recentMetadata).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if SessionFormatting.hasUsage(session) {
                        Text(SessionFormatting.usageSummary(session, includeCache: false))
                            .font(.caption2).foregroundStyle(.purple).lineLimit(1)
                    }
                }
                Spacer()
                Text(SessionFormatting.age(from: session.updatedAt, now: now)).font(.caption2).foregroundStyle(.secondary)
            }
            .padding(10).background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
        }.buttonStyle(.plain)
    }

    private var recentMetadata: String {
        var parts = [session.repo, session.model]
        if session.todo.total > 0 { parts.append("✓\(session.todo.completed)/\(session.todo.total)") }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

private struct SourceBadge: View {
    let source: String
    var body: some View {
        Image(systemName: source == "codex" ? "terminal.fill" : "hammer.fill")
            .font(.caption).foregroundStyle(source == "codex" ? .blue : .orange)
            .frame(width: 24, height: 24).background(.quaternary, in: RoundedRectangle(cornerRadius: 6))
    }
}

private struct EventRow: View {
    let event: SessionEvent
    let now: Date
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).foregroundStyle(color).frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.medium))
                Text(event.session?.title ?? event.type).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer()
            Text(SessionFormatting.age(from: event.ts, now: now)).font(.caption2).foregroundStyle(.secondary)
        }.padding(10).background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
    }
    private var title: String { "\(event.type.replacingOccurrences(of: "session_", with: "").capitalized) · \(event.session?.repo ?? "Unknown")" }
    private var symbol: String { switch event.type { case "session_started": "sparkles"; case "session_resumed": "play.fill"; case "session_finished": "checkmark.circle.fill"; case "session_exited": "exclamationmark.triangle.fill"; default: "bell.fill" } }
    private var color: Color { switch event.type { case "session_finished": .green; case "session_exited": .orange; default: .blue } }
}

private struct EmptyPane: View {
    let symbol: String, title: String, detail: String
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: symbol).font(.system(size: 30)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity).padding(.vertical, 70)
    }
}
