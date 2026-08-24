import Foundation

struct TodoItem: Codable, Hashable, Identifiable {
    var content = ""
    var status = "pending"
    var priority = "medium"
    var id: String { "\(status):\(priority):\(content)" }
}

struct TodoStats: Codable, Hashable {
    var total = 0
    var completed = 0
    var inProgress = 0
    var pending = 0
    var items: [TodoItem] = []

    enum CodingKeys: String, CodingKey {
        case total, completed, pending, items
        case inProgress = "in_progress"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        total = try box.decodeIfPresent(Int.self, forKey: .total) ?? 0
        completed = try box.decodeIfPresent(Int.self, forKey: .completed) ?? 0
        inProgress = try box.decodeIfPresent(Int.self, forKey: .inProgress) ?? 0
        pending = try box.decodeIfPresent(Int.self, forKey: .pending) ?? 0
        items = try box.decodeIfPresent([TodoItem].self, forKey: .items) ?? []
    }
}

struct SessionInfo: Codable, Hashable, Identifiable {
    var id = ""
    var source = "opencode"
    var title = "Unknown"
    var repo = ""
    var model = ""
    var isActive = false
    var elapsedSeconds = 0
    var formattedTime = ""
    var messageCount = 0
    var tokensTotal: Int64 = 0
    var tokensInput: Int64 = 0
    var tokensOutput: Int64 = 0
    var tokensCacheRead: Int64 = 0
    var cost = 0.0
    var updatedAt: Int64 = 0
    var lastActivity = ""
    var todo = TodoStats()
    var procCount = 0
    var gitBranch: String?
    var reasoningEffort: String?
    var phase: String?
    var lastAction: String?

    enum CodingKeys: String, CodingKey {
        case id, source, title, repo, model, cost, todo, phase
        case isActive = "is_active"
        case elapsedSeconds = "elapsed_seconds"
        case formattedTime = "formatted_time"
        case messageCount = "message_count"
        case tokensTotal = "tokens_total"
        case tokensInput = "tokens_input"
        case tokensOutput = "tokens_output"
        case tokensCacheRead = "tokens_cache_read"
        case updatedAt = "updated_at"
        case lastActivity = "last_activity"
        case procCount = "proc_count"
        case gitBranch = "git_branch"
        case reasoningEffort = "reasoning_effort"
        case lastAction = "last_action"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        id = try box.decodeIfPresent(String.self, forKey: .id) ?? ""
        source = try box.decodeIfPresent(String.self, forKey: .source) ?? "opencode"
        title = try box.decodeIfPresent(String.self, forKey: .title) ?? "Unknown"
        repo = try box.decodeIfPresent(String.self, forKey: .repo) ?? ""
        model = try box.decodeIfPresent(String.self, forKey: .model) ?? ""
        isActive = try box.decodeIfPresent(Bool.self, forKey: .isActive) ?? false
        elapsedSeconds = try box.decodeIfPresent(Int.self, forKey: .elapsedSeconds) ?? 0
        formattedTime = try box.decodeIfPresent(String.self, forKey: .formattedTime) ?? ""
        messageCount = try box.decodeIfPresent(Int.self, forKey: .messageCount) ?? 0
        tokensTotal = try box.decodeIfPresent(Int64.self, forKey: .tokensTotal) ?? 0
        tokensInput = try box.decodeIfPresent(Int64.self, forKey: .tokensInput) ?? 0
        tokensOutput = try box.decodeIfPresent(Int64.self, forKey: .tokensOutput) ?? 0
        tokensCacheRead = try box.decodeIfPresent(Int64.self, forKey: .tokensCacheRead) ?? 0
        cost = try box.decodeIfPresent(Double.self, forKey: .cost) ?? 0
        updatedAt = try box.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        lastActivity = try box.decodeIfPresent(String.self, forKey: .lastActivity) ?? ""
        todo = try box.decodeIfPresent(TodoStats.self, forKey: .todo) ?? TodoStats()
        procCount = try box.decodeIfPresent(Int.self, forKey: .procCount) ?? 0
        gitBranch = try box.decodeIfPresent(String.self, forKey: .gitBranch)
        reasoningEffort = try box.decodeIfPresent(String.self, forKey: .reasoningEffort)
        phase = try box.decodeIfPresent(String.self, forKey: .phase)
        lastAction = try box.decodeIfPresent(String.self, forKey: .lastAction)
    }
}

struct SessionEvent: Codable, Hashable, Identifiable {
    var id = ""
    var seq: Int64 = 0
    var type = "session_started"
    var ts: Int64 = 0
    var sessionID: String?
    var session: SessionInfo?

    enum CodingKeys: String, CodingKey {
        case id, seq, type, ts, session
        case sessionID = "session_id"
    }

    var groupingSessionID: String? {
        if let sessionID, !sessionID.isEmpty { return sessionID }
        if let nested = session?.id, !nested.isEmpty { return nested }
        return nil
    }
}

struct SessionsPayload: Codable, Hashable {
    var nowTs: Int64 = 0
    var active: [SessionInfo] = []
    var recent: [SessionInfo] = []
    var events: [SessionEvent] = []
    var delta = true
    var maxSeq: Int64 = 0
    var warnings: [String] = []

    enum CodingKeys: String, CodingKey {
        case active, recent, events, delta, warnings
        case nowTs = "now_ts"
        case maxSeq = "max_seq"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        nowTs = try box.decodeIfPresent(Int64.self, forKey: .nowTs) ?? 0
        active = try box.decodeIfPresent([SessionInfo].self, forKey: .active) ?? []
        recent = try box.decodeIfPresent([SessionInfo].self, forKey: .recent) ?? []
        events = try box.decodeIfPresent([SessionEvent].self, forKey: .events) ?? []
        delta = try box.decodeIfPresent(Bool.self, forKey: .delta) ?? true
        maxSeq = try box.decodeIfPresent(Int64.self, forKey: .maxSeq) ?? 0
        warnings = try box.decodeIfPresent([String].self, forKey: .warnings) ?? []
    }
}

enum SessionFormatting {
    static func liveElapsed(
        _ session: SessionInfo,
        payloadNowTs: Int64,
        now: Date = .now
    ) -> Int {
        guard session.isActive else { return session.elapsedSeconds }
        guard payloadNowTs > 0 else { return session.elapsedSeconds }
        let sinceSnapshot = max(
            0,
            Int(now.timeIntervalSince1970 - Double(payloadNowTs) / 1_000)
        )
        return session.elapsedSeconds + sinceSnapshot
    }

    static func effectiveTokens(_ session: SessionInfo) -> Int64 {
        session.tokensTotal > 0 ? session.tokensTotal : session.tokensInput + session.tokensOutput
    }

    static func hasUsage(_ session: SessionInfo) -> Bool {
        effectiveTokens(session) > 0 || session.tokensCacheRead > 0 || session.cost > 0
    }

    static func usageSummary(_ session: SessionInfo, includeCache: Bool = true) -> String {
        if session.tokensTotal > 0 {
            return "tokens \(compactNumber(session.tokensTotal))"
        }
        var parts = [
            "in \(compactNumber(session.tokensInput))",
            "out \(compactNumber(session.tokensOutput))"
        ]
        if includeCache { parts.append("cache \(compactNumber(session.tokensCacheRead))") }
        parts.append("$\(cost(session.cost))")
        return parts.joined(separator: " · ")
    }

    static func duration(_ seconds: Int) -> String {
        let safe = max(0, seconds)
        let hours = safe / 3_600
        let minutes = (safe % 3_600) / 60
        let remainder = safe % 60
        return hours > 0
            ? String(format: "%02d:%02d:%02d", hours, minutes, remainder)
            : String(format: "%02d:%02d", minutes, remainder)
    }

    static func compactNumber(_ value: Int64) -> String {
        switch value {
        case 1_000_000...: return String(format: "%.1fM", Double(value) / 1_000_000)
        case 1_000...: return String(format: "%.1fk", Double(value) / 1_000)
        default: return "\(value)"
        }
    }

    static func cost(_ value: Double) -> String {
        if value >= 1 { return String(format: "%.2f", value) }
        if value >= 0.01 { return String(format: "%.3f", value) }
        return String(format: "%.4f", value)
    }

    static func age(from timestamp: Int64, now: Date = .now) -> String {
        guard timestamp > 0 else { return "unknown" }
        let seconds = max(0, Int(now.timeIntervalSince1970 - Double(timestamp) / 1_000))
        if seconds < 60 { return "\(seconds)s ago" }
        if seconds < 3_600 { return "\(seconds / 60)m ago" }
        if seconds < 86_400 { return "\(seconds / 3_600)h ago" }
        return "\(seconds / 86_400)d ago"
    }
}
