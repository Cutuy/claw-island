import SwiftUI

struct SessionDetailView: View {
    let session: SessionInfo
    let now: Date
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                SourceBadgeDetail(source: session.source)
                VStack(alignment: .leading) {
                    Text(session.repo.isEmpty ? "Unknown repo" : session.repo).font(.headline)
                    Text(session.model.isEmpty ? "Unknown model" : session.model).font(.caption).foregroundStyle(.secondary)
                }
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
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(session.title).font(.title3.weight(.semibold)).textSelection(.enabled)
                    if !session.lastActivity.isEmpty {
                        DetailSection(title: "Latest activity") {
                            Text(session.lastActivity).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                        }
                    }
                    HStack {
                        DetailMetric(label: "Model", value: session.model.isEmpty ? "—" : session.model)
                        DetailMetric(label: session.isActive ? "Elapsed" : "Updated", value: session.isActive ? SessionFormatting.duration(liveElapsed) : SessionFormatting.age(from: session.updatedAt, now: now))
                        DetailMetric(label: "Tokens", value: SessionFormatting.compactNumber(SessionFormatting.effectiveTokens(session)))
                        DetailMetric(label: "Turns", value: "\(session.messageCount)")
                    }
                    DetailSection(title: "Usage") {
                        if session.tokensTotal > 0 {
                            LabeledContent("Used tokens", value: SessionFormatting.compactNumber(session.tokensTotal))
                            Text("Codex reports one combined per-session token count.")
                                .font(.caption).foregroundStyle(.secondary)
                        } else {
                            LabeledContent("Input", value: SessionFormatting.compactNumber(session.tokensInput))
                            LabeledContent("Output", value: SessionFormatting.compactNumber(session.tokensOutput))
                            LabeledContent("Cache read", value: SessionFormatting.compactNumber(session.tokensCacheRead))
                            LabeledContent("Cost", value: "$\(SessionFormatting.cost(session.cost))")
                        }
                    }
                    if session.todo.total > 0 {
                        DetailSection(title: "Plan · \(session.todo.completed)/\(session.todo.total)") {
                            ProgressView(value: Double(session.todo.completed), total: Double(session.todo.total)).tint(.green)
                            Text("\(session.todo.inProgress) in progress · \(session.todo.pending) pending")
                                .font(.caption).foregroundStyle(.secondary)
                            ForEach(session.todo.items) { item in
                                HStack(alignment: .top) {
                                    Text(todoSymbol(item.status))
                                        .font(.callout.weight(.bold))
                                        .foregroundStyle(todoColor(item.status))
                                        .frame(width: 18, alignment: .leading)
                                    Text(item.content)
                                        .font(.callout.weight(item.status == "in_progress" ? .semibold : .regular))
                                        .foregroundStyle(item.status == "in_progress" ? .primary : .secondary)
                                        .strikethrough(item.status == "completed")
                                        .textSelection(.enabled)
                                    Spacer()
                                    if item.priority != "medium" {
                                        Text(item.priority).font(.caption2).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            if session.todo.total > session.todo.items.count {
                                Text("+\(session.todo.total - session.todo.items.count) additional steps")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    DetailSection(title: "Metadata") {
                        LabeledContent("Source", value: session.source)
                        LabeledContent("Session", value: session.id)
                        if let branch = session.gitBranch, !branch.isEmpty { LabeledContent("Branch", value: branch) }
                        if let effort = session.reasoningEffort, !effort.isEmpty { LabeledContent("Reasoning", value: effort) }
                        if let phase = session.phase, !phase.isEmpty { LabeledContent("Phase", value: phase) }
                        LabeledContent("Updated", value: SessionFormatting.age(from: session.updatedAt, now: now))
                    }
                }.padding(18)
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var liveElapsed: Int {
        session.elapsedSeconds + (session.isActive ? max(0, Int(now.timeIntervalSince1970 - Double(session.updatedAt) / 1_000)) : 0)
    }
    private func todoSymbol(_ status: String) -> String {
        switch status { case "completed": return "✓"; case "in_progress": return "●"; default: return "○" }
    }
    private func todoColor(_ status: String) -> Color {
        switch status { case "completed": return .green; case "in_progress": return .orange; default: return .secondary }
    }
}

private struct SourceBadgeDetail: View {
    let source: String
    var body: some View {
        Image(systemName: source == "codex" ? "terminal.fill" : "hammer.fill")
            .font(.title2).foregroundStyle(source == "codex" ? .blue : .orange)
            .frame(width: 40, height: 40).background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct DetailSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            content
        }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct DetailMetric: View {
    let label: String, value: String
    var body: some View {
        VStack(spacing: 3) {
            Text(value).font(.headline.monospacedDigit()).lineLimit(1)
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity)
    }
}
