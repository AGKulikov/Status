import Foundation

/// Always-on, bounded and privacy-filtered connection journal.
/// It never stores notification text, raw protocol bytes, binding material or device identifiers.
final class ANCSConnectionJournal {
    static let shared = ANCSConnectionJournal()
    static let changed = Notification.Name("ANCSConnectionJournalChanged")
    static let showLogsKey = "ancs.connectionJournal.visible.v56"

    private let lock = NSLock()
    private let writer = DispatchQueue(label: "ru.natro.helper.connection-journal")
    private let maximumLines = 1_600
    private let sessionID = String(UUID().uuidString.prefix(8)).lowercased()
    private var sequence: UInt64 = 0
    private var lines: [String] = []
    private let fileURL: URL
    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let directory = base.appendingPathComponent("NatroDiagnostics", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory,
                                                 withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("ancs-connection.log")
        if let text = try? String(contentsOf: fileURL, encoding: .utf8) {
            lines = Array(text.split(separator: "\n", omittingEmptySubsequences: true)
                .suffix(maximumLines).map(String.init))
        }
    }

    func append(_ component: String, _ message: String) {
        let cleanComponent = sanitize(component)
        let cleanMessage = sanitize(message)
        lock.lock()
        sequence &+= 1
        // DateFormatter is not Sendable/thread-safe; keep it behind the same journal lock.
        let line = "\(formatter.string(from: Date()))  [s=\(sessionID) #\(sequence)]"
            + "  [\(cleanComponent)]  \(cleanMessage)"
        lines.append(line)
        if lines.count > maximumLines { lines.removeFirst(lines.count - maximumLines) }
        let snapshot = lines.joined(separator: "\n") + "\n"
        let destination = fileURL
        lock.unlock()
        writer.async {
            try? snapshot.write(to: destination, atomically: true, encoding: .utf8)
        }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: Self.changed, object: self)
        }
    }

    func tailText(maximum: Int = 500) -> String {
        lock.lock()
        defer { lock.unlock() }
        return lines.suffix(max(1, maximum)).joined(separator: "\n")
    }

    func clear() {
        lock.lock()
        lines.removeAll()
        lock.unlock()
        append("journal", "журнал подключения очищен пользователем")
    }

    func exportURL() throws -> URL {
        let header = "Natro ANCS Helper 65\n"
            + "OS: \(ProcessInfo.processInfo.operatingSystemVersionString)\n"
            + "Session: \(sessionID)\n"
            + "Privacy: identifiers, keys, raw bytes and notification text are redacted\n\n"
        let text = header + tailText(maximum: maximumLines)
        let stamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("Natro-ANCS-\(stamp).log")
        try text.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private func sanitize(_ raw: String) -> String {
        var clean = raw
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\t", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let patterns = [
            "(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b",
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
            "(?i)(token|password|secret|authorization|bearer|key)\\s*[:=]\\s*[^\\s,;]+",
            "(?i)(payload|hex|bytes|notification|title|message|body)\\s*[:=].*"
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(clean.startIndex..<clean.endIndex, in: clean)
            clean = regex.stringByReplacingMatches(
                in: clean,
                range: range,
                withTemplate: pattern.contains("0-9a-f]{8}") ? "<device>" : "<redacted>"
            )
        }
        return clean.count <= 2_000 ? clean : String(clean.prefix(2_000)) + "…"
    }
}
