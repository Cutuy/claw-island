import Foundation

struct SSEParser {
    private(set) var dataLines: [String] = []

    mutating func consume(line: String) -> String? {
        if line.isEmpty {
            guard !dataLines.isEmpty else { return nil }
            defer { dataLines.removeAll(keepingCapacity: true) }
            return dataLines.joined(separator: "\n")
        }
        if line.hasPrefix("data:") {
            var value = String(line.dropFirst(5))
            if value.first == " " { value.removeFirst() }
            dataLines.append(value)
        }
        return nil
    }
}
