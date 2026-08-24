import Foundation
import UserNotifications

final class NotificationManager {
    static let shared = NotificationManager()
    private init() {}

    func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }

    func deliver(events: [SessionEvent]) {
        guard !events.isEmpty else { return }
        if events.count > 5 {
            let body = events.prefix(6).map { event in
                "\(symbol(event.type)) \(event.session?.repo ?? "Unknown") — \(event.session?.title ?? event.type)"
            }.joined(separator: "\n")
            post(id: "claw.summary.\(events.last?.seq ?? 0)", title: "\(events.count) session events", body: body)
            return
        }
        for event in events {
            let session = event.session
            let repo = session?.repo.isEmpty == false ? session!.repo : "Unknown"
            let title: String
            let body: String
            switch event.type {
            case "session_started":
                title = "Session started · \(repo)"
                body = "\(session?.title ?? "Unknown") (\(session?.model ?? "unknown model"))"
            case "session_resumed":
                title = "Session resumed · \(repo)"
                body = "\(session?.title ?? "Unknown") (\(session?.model ?? "unknown model"))"
            case "session_finished":
                title = "Session finished · \(repo)"
                body = "\(session?.title ?? "Unknown") · \(SessionFormatting.duration(session?.elapsedSeconds ?? 0))"
            case "session_exited":
                title = "Session exited · \(repo)"
                body = "\(session?.title ?? "Unknown") · \(SessionFormatting.duration(session?.elapsedSeconds ?? 0))"
            default:
                title = "Session event · \(repo)"
                body = session?.title ?? event.type
            }
            post(id: "claw.event.\(event.id)", title: title, body: body)
        }
    }

    func deliverTest() {
        post(
            id: "claw.test.\(Int(Date.now.timeIntervalSince1970))",
            title: "Claw Island notifications are ready",
            body: "Lifecycle alerts from OpenCode and Codex will appear here."
        )
    }

    private func post(id: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: id, content: content, trigger: nil)
        )
    }

    private func symbol(_ type: String) -> String {
        switch type {
        case "session_started": return "🆕"
        case "session_resumed": return "▶️"
        case "session_finished": return "✅"
        case "session_exited": return "⚠️"
        default: return "•"
        }
    }
}
