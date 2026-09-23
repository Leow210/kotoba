import Cocoa
import WebKit
import CMpv

/// A video window: mpv underneath, and a transparent web layer (player.html) on top with the subtitles you can
/// hover to look words up, the dictionary popup, and the controls. The web layer drives mpv through messages.
final class PlayerWindow: NSWindowController, NSWindowDelegate, WKScriptMessageHandler {
    let url: URL
    let video = MpvView(frame: NSRect(x: 0, y: 0, width: 960, height: 540))
    var overlay: WKWebView!
    var timer: Timer?
    weak var app: AppDelegate?
    var onClose: (() -> Void)?

    init(url: URL, base: URL, app: AppDelegate) {
        self.url = url
        self.app = app
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 660),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
        window.title = url.lastPathComponent
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.backgroundColor = .black
        window.minSize = NSSize(width: 480, height: 300)
        window.collectionBehavior = [.fullScreenPrimary]
        super.init(window: window)
        window.delegate = self

        let container = NSView(frame: window.contentRect(forFrameRect: window.frame))
        container.autoresizingMask = [.width, .height]
        video.frame = container.bounds
        video.autoresizingMask = [.width, .height]
        container.addSubview(video)

        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "player")
        config.preferences.setValue(true, forKey: "developerExtrasEnabled")
        overlay = WKWebView(frame: container.bounds, configuration: config)
        overlay.autoresizingMask = [.width, .height]
        overlay.setValue(false, forKey: "drawsBackground")
        if #available(macOS 13.3, *) { overlay.isInspectable = true }
        container.addSubview(overlay)
        window.contentView = container
        window.center()
        window.setFrameAutosaveName("KotobaPlayer")

        video.onEvent = { [weak self] e in
            let id = e.pointee.event_id
            DispatchQueue.main.async { self?.mpvEvent(id) }
        }
        video.start()

        var comps = URLComponents(url: base.appendingPathComponent("player.html"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "video", value: url.path)]
        overlay.load(URLRequest(url: comps.url!))
        video.command(["loadfile", url.path])
        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in self?.pushState() }
    }
    required init?(coder: NSCoder) { fatalError() }

    func mpvEvent(_ id: mpv_event_id) {
        switch id {
        case MPV_EVENT_FILE_LOADED:
            if let s = video.get("width"), let h = video.get("height"), let w = Double(s), let hh = Double(h), w > 0, hh > 0, let win = window, !win.styleMask.contains(.fullScreen) {
                // Fit the window to the video's shape, within the screen.
                let screen = win.screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
                var width = min(w, screen.width * 0.8), height = width * hh / w
                if height > screen.height * 0.85 { height = screen.height * 0.85; width = height * w / hh }
                win.setContentSize(NSSize(width: width, height: height))
                win.center()
            }
            js("window.playerLoaded&&window.playerLoaded(\(tracksJSON()))")
        case MPV_EVENT_END_FILE:
            js("window.playerEnded&&window.playerEnded()")
        default: break
        }
    }

    /// Audio tracks for the menu (subtitle tracks are read by the core with ffmpeg, so the page has their text).
    func tracksJSON() -> String {
        let count = Int(video.get("track-list/count") ?? "0") ?? 0
        var audio: [[String: Any]] = []
        for i in 0..<count where video.get("track-list/\(i)/type") == "audio" {
            audio.append(["id": Int(video.get("track-list/\(i)/id") ?? "0") ?? 0, "lang": video.get("track-list/\(i)/lang") ?? "",
                          "title": video.get("track-list/\(i)/title") ?? "", "selected": video.get("track-list/\(i)/selected") == "yes"])
        }
        let d = try! JSONSerialization.data(withJSONObject: ["audio": audio, "duration": Double(video.get("duration") ?? "0") ?? 0])
        return String(data: d, encoding: .utf8)!
    }

    /// Time, pause state and the like, 20 times a second, for the subtitles and the control bar.
    func pushState() {
        guard window?.isVisible == true else { return }
        let t = video.get("time-pos") ?? "0", d = video.get("duration") ?? "0"
        let paused = video.get("pause") == "yes"
        let speed = video.get("speed") ?? "1", vol = video.get("volume") ?? "100"
        js("window.playerState&&window.playerState(\(Double(t) ?? 0),\(Double(d) ?? 0),\(paused),\(Double(speed) ?? 1),\(Double(vol) ?? 100))")
    }

    func js(_ s: String) { overlay.evaluateJavaScript(s, completionHandler: nil) }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let m = message.body as? [String: Any], let cmd = m["cmd"] as? String else { return }
        switch cmd {
        case "toggle": video.command(["cycle", "pause"])
        case "pause": video.set("pause", "yes")
        case "play": video.set("pause", "no")
        case "seek": if let t = m["t"] as? Double { video.command(["seek", String(t), "absolute+exact"]) }
        case "seekBy": if let t = m["t"] as? Double { video.command(["seek", String(t), "relative+exact"]) }
        case "speed": if let v = m["v"] as? Double { video.set("speed", String(v)) }
        case "volume": if let v = m["v"] as? Double { video.set("volume", String(v)) }
        case "mute": video.command(["cycle", "mute"])
        case "audio": if let id = m["id"] as? Int { video.set("aid", String(id)) }
        case "fullscreen": window?.toggleFullScreen(nil)
        case "frame": video.command(["frame-step"])
        case "lookupInMain": if let w = m["word"] as? String { app?.showInMain(word: w) }
        case "title": if let t = m["title"] as? String { window?.title = t }
        default: break
        }
    }

    func windowWillClose(_ notification: Notification) {
        js("window.playerClosing&&window.playerClosing()")
        timer?.invalidate()
        video.shutdown()
        overlay.configuration.userContentController.removeScriptMessageHandler(forName: "player")
        onClose?()
    }
}
