import Combine
import AppKit
import Foundation
import Network
import ServiceManagement

@MainActor
final class AppModel: ObservableObject {
    enum ConnectionState: Equatable {
        case stopped, connecting, connected, offline(String)
    }

    @Published private(set) var payload = SessionsPayload()
    @Published private(set) var lifecycleEvents: [SessionEvent] = []
    @Published private(set) var connectionState: ConnectionState = .stopped
    @Published private(set) var lastMessageAt: Date?
    @Published private(set) var lastSnapshotAt: Date?
    @Published private(set) var reconnectAttempt = 0
    @Published var notificationsEnabled: Bool {
        didSet { defaults.set(notificationsEnabled, forKey: Keys.notificationsEnabled) }
    }
    @Published var serverURL: String {
        didSet { defaults.set(serverURL, forKey: Keys.serverURL) }
    }
    @Published private(set) var launchAtLogin = false
    @Published var lastConfigurationError: String?

    private let defaults: UserDefaults
    private var monitorTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var lastSequence: Int64
    private var seenEventIDs: [String]
    private var seenEventIDSet: Set<String>
    private let decoder = JSONDecoder()
    private let transport = SSETransport()
    private let networkMonitor = NWPathMonitor()
    private let networkQueue = DispatchQueue(label: "com.cutuy.clawisland.network")
    private var wakeObserver: NSObjectProtocol?
    private enum Keys {
        static let serverURL = "serverURL"
        static let notificationsEnabled = "notificationsEnabled"
        static let lastSequence = "lastSequence"
        static let seenEventIDs = "seenEventIDs"
    }

    init(defaults: UserDefaults = .standard, autoStart: Bool = true) {
        self.defaults = defaults
        serverURL = defaults.string(forKey: Keys.serverURL) ?? "http://100.64.0.4:18818"
        notificationsEnabled = defaults.object(forKey: Keys.notificationsEnabled) as? Bool ?? true
        lastSequence = defaults.object(forKey: Keys.lastSequence) as? Int64 ?? 0
        seenEventIDs = defaults.stringArray(forKey: Keys.seenEventIDs) ?? []
        seenEventIDSet = Set(seenEventIDs)
        launchAtLogin = SMAppService.mainApp.status == .enabled
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.reconnect() }
        }
        networkMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor [weak self] in
                guard let self, case .offline = self.connectionState else { return }
                self.reconnect()
            }
        }
        networkMonitor.start(queue: networkQueue)
        if autoStart {
            Task { [weak self] in
                self?.startMonitoring()
                self?.startWatchdog()
            }
        }
    }

    deinit {
        monitorTask?.cancel()
        watchdogTask?.cancel()
        networkMonitor.cancel()
        if let wakeObserver { NSWorkspace.shared.notificationCenter.removeObserver(wakeObserver) }
    }

    var isMonitoring: Bool { monitorTask != nil }
    var isConnected: Bool { connectionState == .connected }

    func startMonitoring() {
        guard monitorTask == nil else { return }
        guard normalizedBaseURL() != nil else {
            connectionState = .offline("Invalid server URL")
            return
        }
        connectionState = .connecting
        if notificationsEnabled {
            Task { await NotificationManager.shared.requestAuthorization() }
        }
        monitorTask = Task { [weak self] in await self?.monitorLoop() }
    }

    func stopMonitoring() {
        monitorTask?.cancel()
        monitorTask = nil
        reconnectAttempt = 0
        connectionState = .stopped
    }

    func reconnect() {
        stopMonitoring()
        startMonitoring()
    }

    private func startWatchdog() {
        guard watchdogTask == nil else { return }
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, let self else { return }
                guard self.isMonitoring, let last = self.lastMessageAt else { continue }
                if Date.now.timeIntervalSince(last) > 12 {
                    self.reconnect()
                }
            }
        }
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            launchAtLogin = SMAppService.mainApp.status == .enabled
            lastConfigurationError = nil
        } catch {
            launchAtLogin = SMAppService.mainApp.status == .enabled
            lastConfigurationError = error.localizedDescription
        }
    }

    func loadSimulation(activeCount: Int) {
        var sample = SessionsPayload()
        sample.nowTs = Int64(Date.now.timeIntervalSince1970 * 1_000)
        sample.active = (0..<activeCount).map { index in
            var session = SessionInfo()
            session.id = "sim:\(index)"
            session.source = index == 0 ? "codex" : "opencode"
            session.repo = index == 0 ? "claw-island" : "network-dashboard"
            session.title = index == 0 ? "Building the macOS menu-bar client" : "Refining session observability"
            session.model = index == 0 ? "gpt-5.6-sol" : "glm-5.3"
            session.isActive = true
            session.elapsedSeconds = 754 + index * 1_400
            session.messageCount = 18 + index * 7
            session.tokensTotal = 82_410 + Int64(index * 22_000)
            session.tokensInput = 75_200 + Int64(index * 20_000)
            session.tokensOutput = 7_210 + Int64(index * 2_000)
            session.tokensCacheRead = 31_000
            session.cost = 0.18 + Double(index) * 0.12
            session.updatedAt = sample.nowTs - Int64(index * 18_000)
            session.lastActivity = "Implementing the live status view, resilient SSE connection, and native lifecycle notifications."
            var todo = TodoStats()
            todo.total = 5
            todo.completed = 3
            todo.inProgress = 1
            todo.pending = 1
            todo.items = [
                TodoItem(content: "Decode the session wire format", status: "completed", priority: "high"),
                TodoItem(content: "Render active and recent sessions", status: "completed", priority: "high"),
                TodoItem(content: "Validate lifecycle notifications", status: "in_progress", priority: "high")
            ]
            session.todo = todo
            return session
        }
        sample.recent = payload.recent
        payload = sample
    }

    private func monitorLoop() async {
        await fetchSnapshot(reportFailure: true)
        var delaySeconds: UInt64 = 2
        while !Task.isCancelled {
            let messageBeforeConnection = lastMessageAt
            do {
                try await consumeSSE()
                guard !Task.isCancelled else { break }
                throw URLError(.networkConnectionLost)
            } catch is CancellationError {
                break
            } catch {
                guard !Task.isCancelled else { break }
                reconnectAttempt += 1
                connectionState = .offline(error.localizedDescription)
                // A stream that delivered data was healthy. Restart its
                // backoff from the fast path instead of retaining an old 30s
                // delay after the Mac wakes or changes networks.
                if lastMessageAt != messageBeforeConnection { delaySeconds = 2 }
                try? await Task.sleep(for: .seconds(delaySeconds))
                delaySeconds = min(delaySeconds * 2, 30)
            }
        }
    }

    private func fetchSnapshot(reportFailure: Bool) async {
        guard let base = normalizedBaseURL(), let url = URL(string: "\(base)/api/sessions") else { return }
        do {
            let data = try await transport.fetch(url)
            var snapshot = try decoder.decode(SessionsPayload.self, from: data)
            snapshot.events = []
            payload = snapshot
            lastSnapshotAt = .now
        } catch {
            if reportFailure { connectionState = .offline(error.localizedDescription) }
        }
    }

    private func consumeSSE() async throws {
        guard let base = normalizedBaseURL() else { throw URLError(.badURL) }
        let suffix = lastSequence > 0 ? "?after=\(lastSequence)" : ""
        guard let url = URL(string: "\(base)/api/sessions/events\(suffix)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.timeoutInterval = 15
        connectionState = .connecting
        try await transport.consume(
            request: request,
            onOpen: { [weak self] in
                await self?.didOpenSSE()
            },
            onEvent: { [weak self] data in
                guard let self else { return }
                try await self.applySSEData(data)
            }
        )
    }

    private func didOpenSSE() {
        connectionState = .connected
        reconnectAttempt = 0
        // Give the new stream one watchdog interval to deliver its immediate
        // first frame before considering it stale.
        lastMessageAt = .now
    }

    private func applySSEData(_ string: String) async throws {
        let incoming = try decoder.decode(SessionsPayload.self, from: Data(string.utf8))
        lastMessageAt = .now
        if incoming.maxSeq > 0, lastSequence > incoming.maxSeq {
            lastSequence = incoming.maxSeq
            defaults.set(lastSequence, forKey: Keys.lastSequence)
        }
        process(events: incoming.events)
        if incoming.delta {
            payload = incoming
            lastSnapshotAt = .now
        } else if lastSnapshotAt.map({ Date.now.timeIntervalSince($0) >= 120 }) ?? true {
            // Compact heartbeats normally update liveness only. A slow safety
            // reconciliation guards against an unlikely missed full frame
            // without defeating the bridge's compact SSE data model.
            await fetchSnapshot(reportFailure: false)
        }
    }

    private func process(events: [SessionEvent]) {
        guard !events.isEmpty else { return }
        let fresh = events.filter { event in
            guard !event.id.isEmpty else { return true }
            return !seenEventIDSet.contains(event.id)
        }
        for event in events where event.seq > lastSequence { lastSequence = event.seq }
        defaults.set(lastSequence, forKey: Keys.lastSequence)
        guard !fresh.isEmpty else { return }
        for event in fresh where !event.id.isEmpty {
            seenEventIDs.append(event.id)
            seenEventIDSet.insert(event.id)
        }
        if seenEventIDs.count > 100 {
            seenEventIDs = Array(seenEventIDs.suffix(100))
            seenEventIDSet = Set(seenEventIDs)
        }
        defaults.set(seenEventIDs, forKey: Keys.seenEventIDs)
        lifecycleEvents = Array((fresh.reversed() + lifecycleEvents).prefix(50))
        if notificationsEnabled { NotificationManager.shared.deliver(events: fresh) }
    }

    private func normalizedBaseURL() -> String? {
        let trimmed = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: trimmed), let scheme = url.scheme,
              ["http", "https"].contains(scheme), url.host != nil else { return nil }
        return trimmed
    }
}
