import CryptoKit
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
        let newestBySession = Self.coalescedEvents(events)
        let isReplay = events.count > 5
        let newestEventID = newestBySession.max(by: { $0.seq < $1.seq })?.id

        for event in newestBySession {
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
            let sessionKey = Self.notificationKey(for: event)
            post(
                id: "claw.session.\(sessionKey)",
                threadID: "claw.session.\(sessionKey)",
                title: title,
                body: body,
                // A reconnect replay may update many session rows at once.
                // Keep every row correct but alert only for the newest event.
                sound: !isReplay || event.id == newestEventID
            )
        }
    }

    func deliverTest() {
        post(
            id: "claw.test.\(Int(Date.now.timeIntervalSince1970))",
            threadID: "claw.test",
            title: "Claw Island notifications are ready",
            body: "Lifecycle alerts from OpenCode and Codex will appear here.",
            sound: true
        )
    }

    private func post(id: String, threadID: String, title: String, body: String, sound: Bool) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.threadIdentifier = threadID
        content.sound = sound ? .default : nil
        let center = UNUserNotificationCenter.current()
        // Be explicit for already delivered Notification Center rows. The
        // subsequent request still carries a sound, so a later lifecycle
        // transition alerts again while leaving only one row per session.
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.add(
            UNNotificationRequest(identifier: id, content: content, trigger: nil)
        )
    }

    static func notificationKey(for event: SessionEvent) -> String {
        // Keep request identifiers compact and safe even when providers use
        // long opaque session IDs. The same source ID always yields the same
        // request/thread key, while a malformed event still gets an isolated
        // fallback rather than coalescing every unknown session together.
        let raw = event.groupingSessionID ?? "event:\(event.id)"
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    static func coalescedEvents(_ events: [SessionEvent]) -> [SessionEvent] {
        var newest: [String: SessionEvent] = [:]
        for event in events {
            let key = event.groupingSessionID ?? "event:\(event.id)"
            if let previous = newest[key],
               (previous.seq > event.seq || (previous.seq == event.seq && previous.ts > event.ts)) {
                continue
            }
            newest[key] = event
        }
        return newest.values.sorted {
            $0.seq == $1.seq ? $0.ts < $1.ts : $0.seq < $1.seq
        }
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
