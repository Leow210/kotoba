import Cocoa
import CoreAudio
import AudioToolbox

/// Live subtitles for a show in the browser (Viki without subtitles in the language spoken). When the browser
/// helper's Live subs button is on and the video plays, the core says so (captions.want); this taps the browsers'
/// audio only (never the screen: that freezes DRM video), streams it at 16 kHz mono to a Qwen3-ASR worker (desktop/asr/live_asr.py, in the
/// Subtitle_Generator environment on the T7), and hands each line the worker hears back to the core.
final class LiveCaptions: NSObject {
    weak var app: AppDelegate?
    var timer: Timer?
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
                    if !self.capturing && !self.starting && self.ready { self.startCapture() }
                } else {
                    // Paused: stop listening soon, and let the model go after a while (it loads again in a second or two).
                    if self.idleSince == nil { self.idleSince = Date() }
                    if self.capturing, Date().timeIntervalSince(self.idleSince!) > 2 { self.stopCapture() }
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

    // MARK: audio capture (a Core Audio process tap: the browsers' sound only, never the screen)
    // Screen capture of a browser window playing DRM video (Viki) makes the browser freeze the picture; a process tap
    // hears the sound without touching the screen. macOS asks once for "audio from other apps".

    var tapID = AudioObjectID(kAudioObjectUnknown)
    var aggregateID = AudioObjectID(kAudioObjectUnknown)
    var ioProc: AudioDeviceIOProcID?
    var tapFormat = AudioStreamBasicDescription()
    var resamplePos = 0.0
    var capturing: Bool { ioProc != nil }

    func startCapture() {
        guard #available(macOS 14.2, *) else { NSLog("Kotoba live subtitles: needs macOS 14.2"); return }
        starting = true
        defer { starting = false }
        let procs = browserAudioProcesses()
        guard !procs.isEmpty else { return }
        let desc = CATapDescription(stereoMixdownOfProcesses: procs)
        desc.uuid = UUID()
        desc.isPrivate = true
        desc.muteBehavior = .unmuted
        var tap = AudioObjectID(kAudioObjectUnknown)
        guard AudioHardwareCreateProcessTap(desc, &tap) == noErr else { NSLog("Kotoba live subtitles: no process tap (permission?)"); return }
        tapID = tap
        var addr = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        AudioObjectGetPropertyData(tap, &addr, 0, nil, &size, &tapFormat)
        let output = defaultOutputUID()
        var dict: [String: Any] = [
            kAudioAggregateDeviceNameKey: "Kotoba live subtitles",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapUIDKey: desc.uuid.uuidString, kAudioSubTapDriftCompensationKey: true]],
        ]
        if let output {
            dict[kAudioAggregateDeviceMainSubDeviceKey] = output
            dict[kAudioAggregateDeviceSubDeviceListKey] = [[kAudioSubDeviceUIDKey: output]]
        }
        var agg = AudioObjectID(kAudioObjectUnknown)
        guard AudioHardwareCreateAggregateDevice(dict as CFDictionary, &agg) == noErr else { stopCapture(); return }
        aggregateID = agg
        resamplePos = 0
        var proc: AudioDeviceIOProcID?
        let status = AudioDeviceCreateIOProcIDWithBlock(&proc, agg, audioQueue) { [weak self] _, input, _, _, _ in
            self?.received(input)
        }
        guard status == noErr, let proc else { stopCapture(); return }
        ioProc = proc
        if AudioDeviceStart(agg, proc) != noErr { stopCapture() }
    }

    func stopCapture() {
        if let proc = ioProc { AudioDeviceStop(aggregateID, proc); AudioDeviceDestroyIOProcID(aggregateID, proc) }
        ioProc = nil
        if aggregateID != kAudioObjectUnknown { AudioHardwareDestroyAggregateDevice(aggregateID); aggregateID = AudioObjectID(kAudioObjectUnknown) }
        if tapID != kAudioObjectUnknown, #available(macOS 14.2, *) { AudioHardwareDestroyProcessTap(tapID); tapID = AudioObjectID(kAudioObjectUnknown) }
    }

    /// The Core Audio process objects of the browsers (their audio helpers: Chrome's, Firefox's, Safari's WebKit).
    func browserAudioProcesses() -> [AudioObjectID] {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr else { return [] }
        let prefixes = Self.browsers + ["com.apple.WebKit", "org.mozilla.plugincontainer"]
        return ids.filter { id in
            var a = AudioObjectPropertyAddress(mSelector: kAudioProcessPropertyBundleID, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            var s = UInt32(MemoryLayout<CFString?>.size)
            var bundle: Unmanaged<CFString>?
            guard AudioObjectGetPropertyData(id, &a, 0, nil, &s, &bundle) == noErr, let b = bundle?.takeRetainedValue() as String? else { return false }
            return prefixes.contains { b.hasPrefix($0) }
        }
    }

    func defaultOutputUID() -> String? {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultSystemOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var dev = AudioObjectID(0); var size = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &dev) == noErr else { return nil }
        var uaddr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceUID, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var uid: Unmanaged<CFString>?; var usize = UInt32(MemoryLayout<CFString?>.size)
        guard AudioObjectGetPropertyData(dev, &uaddr, 0, nil, &usize, &uid) == noErr else { return nil }
        return uid?.takeRetainedValue() as String?
    }

    /// Tap audio (float, usually 48 kHz stereo) → mono 16 kHz → the worker, with the wall-clock time of its first sample.
    func received(_ input: UnsafePointer<AudioBufferList>) {
        guard ready, let out = workerIn else { return }
        let abl = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
        guard abl.count > 0 else { return }
        let rate = tapFormat.mSampleRate > 0 ? tapFormat.mSampleRate : 48000
        let interleaved = tapFormat.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0
        var mono: [Float] = []
        if interleaved {
            let ch = max(1, Int(abl[0].mNumberChannels))
            guard let p = abl[0].mData?.assumingMemoryBound(to: Float.self) else { return }
            let frames = Int(abl[0].mDataByteSize) / 4 / ch
            mono.reserveCapacity(frames)
            for f in 0..<frames { var sum: Float = 0; for c in 0..<ch { sum += p[f * ch + c] }; mono.append(sum / Float(ch)) }
        } else {
            let frames = Int(abl[0].mDataByteSize) / 4
            mono = [Float](repeating: 0, count: frames)
            for b in abl { guard let p = b.mData?.assumingMemoryBound(to: Float.self) else { continue }; for f in 0..<min(frames, Int(b.mDataByteSize) / 4) { mono[f] += p[f] / Float(abl.count) } }
        }
        // Box-filter down to 16 kHz.
        let step = rate / 16000
        var down: [Float] = []
        down.reserveCapacity(Int(Double(mono.count) / step) + 1)
        var pos = resamplePos
        while pos + step <= Double(mono.count) {
            let a = Int(pos), b = min(mono.count, Int(pos + step))
            var s: Float = 0; for i in a..<b { s += mono[i] }
            down.append(s / Float(max(1, b - a)))
            pos += step
        }
        resamplePos = pos - Double(mono.count)
        guard !down.isEmpty else { return }
        let wall = Date().timeIntervalSince1970 * 1000 - Double(down.count) / 16.0
        var header = Data()
        var n = UInt32(down.count), w = wall
        withUnsafeBytes(of: &n) { header.append(contentsOf: $0) }
        withUnsafeBytes(of: &w) { header.append(contentsOf: $0) }
        down.withUnsafeBufferPointer { header.append(Data(buffer: $0)) }
        do { try out.write(contentsOf: header) } catch { }
    }
}
