import Foundation

/// Owns the long-lived URLSession and performs byte-level SSE framing away
/// from MainActor. Only complete event payloads cross back to the app model.
actor SSETransport {
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 7 * 24 * 60 * 60
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration)
    }

    func fetch(_ url: URL) async throws -> Data {
        let (data, response) = try await session.data(from: url)
        try validate(response)
        return data
    }

    func consume(
        request: URLRequest,
        onOpen: @Sendable () async -> Void,
        onEvent: @Sendable (String) async throws -> Void
    ) async throws {
        let (bytes, response) = try await session.bytes(for: request)
        try validate(response)
        await onOpen()

        var parser = SSEParser()
        var lineBytes: [UInt8] = []
        lineBytes.reserveCapacity(8_192)

        for try await byte in bytes {
            try Task.checkCancellation()
            if byte == 0x0A {
                var line = String(decoding: lineBytes, as: UTF8.self)
                if line.last == "\r" { line.removeLast() }
                lineBytes.removeAll(keepingCapacity: true)
                if let data = parser.consume(line: line) {
                    try await onEvent(data)
                }
            } else {
                lineBytes.append(byte)
            }
        }
    }

    private func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
    }
}
