import Foundation

enum CheckError: Error, CustomStringConvertible {
    case failed(String)
    var description: String { switch self { case .failed(let message): return message } }
}

@main
struct SelfTest {
    static func main() async throws {
        try testSnapshotAndPartialEvent()
        try testHeartbeat()
        try testSSEParser()
        try testFormatting()
        try await testLiveGateway()
        print("All Claw Island self-tests passed")
    }

    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) throws {
        guard condition() else { throw CheckError.failed(message) }
    }

    static func testSnapshotAndPartialEvent() throws {
        let data = Data(#"{"now_ts":1787531850111,"active":[{"id":"codex:abc","source":"codex","title":"Build client","repo":"claw-island","model":"gpt-5.6-sol","is_active":true,"elapsed_seconds":42,"message_count":5,"tokens_total":1200,"tokens_input":1000,"tokens_output":200,"tokens_cache_read":500,"cost":0.12,"updated_at":1787531800000,"last_activity":"Writing Swift","todo":{"total":3,"completed":1,"in_progress":1,"pending":1,"items":[{"content":"Inspect server contract","status":"completed","priority":"medium"},{"content":"Render named checklist","status":"in_progress","priority":"high"},{"content":"Package the app","status":"pending","priority":"medium"}]}}],"recent":[],"events":[{"id":"evt_1","seq":9,"type":"session_finished","ts":1787531850000,"session":{"id":"opencode:x","repo":"demo","title":"Done","elapsed_seconds":61}}],"delta":true,"max_seq":9}"#.utf8)
        let payload = try JSONDecoder().decode(SessionsPayload.self, from: data)
        try expect(payload.active.first?.tokensTotal == 1_200, "tokens_total did not decode")
        try expect(payload.active.first?.todo.inProgress == 1, "todo in_progress did not decode")
        try expect(payload.active.first?.todo.pending == 1, "todo pending count did not decode")
        try expect(payload.active.first?.todo.items.count == 3, "named plan items did not decode")
        try expect(payload.active.first?.todo.items[1].content == "Render named checklist", "current plan step name did not decode")
        try expect(payload.active.first?.todo.items[1].status == "in_progress", "current plan step status did not decode")
        try expect(payload.events.first?.session?.repo == "demo", "partial lifecycle session did not decode")
        try expect(payload.events.first?.session?.model == "", "missing partial-session fields did not default")
    }

    static func testHeartbeat() throws {
        let payload = try JSONDecoder().decode(SessionsPayload.self, from: Data(#"{"now_ts":1,"delta":false,"max_seq":12,"events":[]}"#.utf8))
        try expect(!payload.delta && payload.active.isEmpty && payload.maxSeq == 12, "heartbeat envelope did not decode")
    }

    static func testSSEParser() throws {
        var parser = SSEParser()
        _ = parser.consume(line: ": keepalive")
        _ = parser.consume(line: "data: {\"one\":")
        _ = parser.consume(line: "data: 1}")
        try expect(parser.consume(line: "") == "{\"one\":\n1}", "multi-line SSE data parsing failed")
    }

    static func testFormatting() throws {
        try expect(SessionFormatting.duration(65) == "01:05", "short duration formatting failed")
        try expect(SessionFormatting.duration(3_661) == "01:01:01", "long duration formatting failed")
        try expect(SessionFormatting.compactNumber(1_250) == "1.2k", "compact-number formatting failed")
        var codex = SessionInfo()
        codex.tokensTotal = 4_200
        try expect(SessionFormatting.effectiveTokens(codex) == 4_200, "Codex total-token selection failed")
        try expect(SessionFormatting.usageSummary(codex) == "tokens 4.2k", "Codex token summary failed")
        var openCode = SessionInfo()
        openCode.tokensInput = 1_000
        openCode.tokensOutput = 250
        openCode.cost = 0.0919
        try expect(SessionFormatting.effectiveTokens(openCode) == 1_250, "OpenCode token sum failed")
        try expect(SessionFormatting.usageSummary(openCode, includeCache: false) == "in 1.0k · out 250 · $0.092", "OpenCode usage summary failed")
    }

    static func testLiveGateway() async throws {
        let url = URL(string: "http://100.64.0.4:18818/api/sessions")!
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse else { throw CheckError.failed("live gateway returned no HTTP response") }
        try expect(http.statusCode == 200, "live gateway returned HTTP \(http.statusCode)")
        let payload = try JSONDecoder().decode(SessionsPayload.self, from: data)
        try expect(payload.nowTs > 0, "live gateway payload has no timestamp")
        print("Live gateway decoded: \(payload.active.count) active, \(payload.recent.count) recent")
    }
}
