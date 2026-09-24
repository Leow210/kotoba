import Cocoa
import ScreenCaptureKit
import CoreMedia

/// Live subtitles for a show in the browser (Viki without subtitles in the language spoken). When the browser
/// helper's Live subs button is on and the video plays, the core says so (captions.want); this captures the browsers'
/// audio only (16 kHz mono, no picture), streams it to a Qwen3-ASR worker (desktop/asr/live_asr.py, in the
/// Subtitle_Generator environment on the T7), and hands each line the worker hears back to the core.
final class LiveCaptions: NSObject, SCStreamOutput, SCStreamDelegate {
    weak var app: AppDelegate?
    var timer: Timer?
    var stream: SCStream?
    var worker: Process?
    var workerIn: FileHandle?
    var workerLang = ""
    var ready = false
    var idleSince: Date?
    var starting = false
    let audioQueue = DispatchQueue(label: "kotoba.live-captions.audio")
    static let browsers = ["com.google.Chrome", "org.mozilla.firefox", "com.apple.Safari", "company.thebrowser.Browser",
                           "com.microsoft.edgemac", "com.brave.Browser", "com.vivaldi.Vivaldi", "com.operasoftware.Opera"]
    var python: String { UserDefaults.standard.string(forKey: "LiveCaptionsPython") ?? "/Volumes/T7/subtitle-generator-qwen/.venv/bin/python" }

    init(app: AppDelegate) { self.app = app; super.init() }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.poll() }
    }

    /// Once a second: should audio be captured now, and in which language?
    func poll() {
        app?.coreCall("captions.want", [:]) { [weak self] r in
            guard let self, let r else { return }
            let active = r["active"] as? Bool ?? false, keep = r["keep"] as? Bool ?? false
            let lang = r["lang"] as? String ?? "ko"
            DispatchQueue.main.async {
                if active {
                    self.idleSince = nil
                    if self.worker == nil || self.workerLang != lang { self.startWorker(lang) }
                    if self.stream == nil && !self.starting { self.startCapture() }
                } else {
                    // Paused: stop listening soon, and let the model go after a while (it loads again in a second or two).
                    if self.idleSince == nil { self.idleSince = Date() }
                    if self.stream != nil, Date().timeIntervalSince(self.idleSince!) > 2 { self.stopCapture() }
                    if self.worker != nil, !keep, Date().timeIntervalSince(self.idleSince!) > 60 { self.stopWorker() }
                }
            }
        }
    }

    // MARK: worker

    func startWorker(_ lang: String) {
        stopWorker()
        guard FileManager.default.isExecutableFile(atPath: python) else {
            NSLog("Kotoba live subtitles: no Python at \(python) (is the T7 connected?)"); return
        }
        guard let script = app?.liveAsrScript() else { return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: python)
        p.arguments = [script, "--language", lang]
        let inPipe = Pipe(), outPipe = Pipe()
        p.standardInput = inPipe
        p.standardOutput = outPipe
        p.standardError = FileHandle(forWritingAtPath: "/dev/null") ?? FileHandle.standardError
        var buffer = Data()
        outPipe.fileHandleForReading.readabilityHandler = { [weak self] h in
            let chunk = h.availableData
            if chunk.isEmpty { return }
            buffer.append(chunk)
            while let nl = buffer.firstIndex(of: 0x0A) {
                let line = buffer.subdata(in: buffer.startIndex..<nl)
                buffer.removeSubrange(buffer.startIndex...nl)
                guard let obj = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { continue }
                if obj["ready"] != nil { self?.ready = true; continue }
                if obj["text"] != nil { self?.app?.coreCall("captions.add", obj) { _ in } }
            }
        }
        do { try p.run() } catch { NSLog("Kotoba live subtitles: \(error)"); return }
        worker = p; workerIn = inPipe.fileHandleForWriting; workerLang = lang; ready = false
    }

    func stopWorker() {
        try? workerIn?.close()
        worker?.terminate()
        worker = nil; workerIn = nil; ready = false
    }

    // MARK: audio capture

    func startCapture() {
        starting = true
        Task { @MainActor in
            defer { self.starting = false }
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
                let apps = content.applications.filter { Self.browsers.contains($0.bundleIdentifier) }
                guard let display = content.displays.first, !apps.isEmpty else { return }
                let filter = SCContentFilter(display: display, including: apps, exceptingWindows: [])
                let config = SCStreamConfiguration()
                config.capturesAudio = true
                config.sampleRate = 16000
                config.channelCount = 1
                config.excludesCurrentProcessAudio = true
                // Audio is what's wanted; the picture is kept as small and rare as allowed.
                config.width = 2; config.height = 2
                config.minimumFrameInterval = CMTime(value: 1, timescale: 1)
                let s = SCStream(filter: filter, configuration: config, delegate: self)
                try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: audioQueue)
                try await s.startCapture()
                self.stream = s
            } catch {
                NSLog("Kotoba live subtitles: capture failed: \(error)")
            }
        }
    }

    func stopCapture() {
        let s = stream; stream = nil
        Task { try? await s?.stopCapture() }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) { DispatchQueue.main.async { self.stream = nil } }

    /// Each audio buffer: float samples with the wall-clock time of the first, to the worker.
    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, ready, let input = workerIn, sb.isValid else { return }
        let frames = CMSampleBufferGetNumSamples(sb)
        guard frames > 0 else { return }
        var list = AudioBufferList()
        var block: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sb, bufferListSizeNeededOut: nil, bufferListOut: &list,
            bufferListSize: MemoryLayout<AudioBufferList>.size, blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block)
        guard status == noErr, let data = list.mBuffers.mData else { return }
        let bytes = Int(list.mBuffers.mDataByteSize)
        let count = bytes / 4
        // The buffer's first sample was heard this long ago.
        let wall = Date().timeIntervalSince1970 * 1000 - Double(count) / 16.0
        var header = Data()
        var n = UInt32(count), w = wall
        withUnsafeBytes(of: &n) { header.append(contentsOf: $0) }
        withUnsafeBytes(of: &w) { header.append(contentsOf: $0) }
        header.append(Data(bytes: data, count: bytes))
        do { try input.write(contentsOf: header) } catch { }
    }
}
