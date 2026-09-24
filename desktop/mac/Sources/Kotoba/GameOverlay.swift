import Cocoa
import WebKit
import Vision
import ScreenCaptureKit
import Carbon.HIToolbox

/// A panel that can take the keyboard without activating Kotoba, so a full-screen game keeps its Space.
final class OverlayPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

/// Screen text over games and other apps (⌃` by default, see HotKey.overlayChoices): a screenshot of the display under the pointer (screen capture only —
/// nothing reads or touches the game itself), read by Apple Vision, and a see-through panel above everything with a
/// box per line over the live game (or the frozen shot). The capture stays in memory: nothing is written to disk. A line opens Kotoba's text sheet: tap words to look up, translate, save cards and sentence cards.
final class GameOverlay: NSObject, WKScriptMessageHandler {
    weak var app: AppDelegate?
    var panel: OverlayPanel?
    var web: WKWebView?
    var pageReady = false
    var queued: [String] = []
    var image: CGImage?
    var busy = false
    /// The app that was in front (the game): it gets the keyboard and mouse back when the overlay closes.
    var previousApp: NSRunningApplication?
    static let languages = ["ja": ["ja-JP", "en-US"], "ko": ["ko-KR", "en-US"], "zh": ["zh-Hans", "zh-Hant", "en-US"], "en": ["en-US"]]
    var lang: String {
        get { UserDefaults.standard.string(forKey: "OverlayLang") ?? "ja" }
        set { UserDefaults.standard.set(newValue, forKey: "OverlayLang") }
    }

    init(app: AppDelegate) { self.app = app }

    @objc func toggle() {
        if panel?.isVisible == true { hide() } else { capture() }
    }

    func capture() {
        guard !busy, app?.base != nil else { return }
        busy = true
        let front = NSWorkspace.shared.frontmostApplication
        if front?.processIdentifier != ProcessInfo.processInfo.processIdentifier { previousApp = front }
        let source = previousApp?.localizedName ?? ""
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(mouse, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        Task { @MainActor in
            do {
                let cg = try await Self.grab(screen)
                self.image = cg
                self.show(on: screen)
                self.send("window.gameOverlay&&gameOverlay.show(\(Self.json(["image": Self.jpeg(cg), "w": cg.width, "h": cg.height, "app": source, "lang": self.lang])))")
                self.recognize()
            } catch {
                self.busy = false
                self.permissionHelp(error)
            }
        }
    }

    /// The display as it looks, minus Kotoba's own windows.
    static func grab(_ screen: NSScreen) async throws -> CGImage {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID
        guard let display = content.displays.first(where: { $0.displayID == id }) ?? content.displays.first else {
            throw NSError(domain: "Kotoba", code: 1, userInfo: [NSLocalizedDescriptionKey: "No display to capture"])
        }
        let mine = content.applications.filter { $0.processID == ProcessInfo.processInfo.processIdentifier }
        let filter = SCContentFilter(display: display, excludingApplications: mine, exceptingWindows: [])
        let config = SCStreamConfiguration()
        config.width = Int(CGFloat(display.width) * screen.backingScaleFactor)
        config.height = Int(CGFloat(display.height) * screen.backingScaleFactor)
        config.showsCursor = false
        return try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
    }

    /// Lines of text in pixels of the capture, top-left origin.
    func recognize() {
        guard let cg = image else { busy = false; return }
        let languages = Self.languages[lang] ?? ["ja-JP"]
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let req = VNRecognizeTextRequest()
            req.recognitionLevel = .accurate
            req.recognitionLanguages = languages
            req.usesLanguageCorrection = true
            var lines: [[String: Any]] = []
            let w = CGFloat(cg.width), h = CGFloat(cg.height)
            do {
                try VNImageRequestHandler(cgImage: cg, options: [:]).perform([req])
                for o in req.results ?? [] {
                    guard let top = o.topCandidates(1).first else { continue }
                    let b = o.boundingBox
                    // Each character's box too, so Shift over the picture can find the character under the pointer.
                    var chars: [[Double]] = []
                    let text = top.string
                    var i = text.startIndex
                    while i < text.endIndex {
                        let next = text.index(after: i)
                        if let r = try? top.boundingBox(for: i..<next)?.boundingBox {
                            chars.append([Double(r.minX * w), Double((1 - r.maxY) * h), Double(r.width * w), Double(r.height * h)])
                        } else { chars.append([]) }
                        i = next
                    }
                    lines.append(["x": b.minX * w, "y": (1 - b.maxY) * h, "w": b.width * w, "h": b.height * h, "text": text, "conf": top.confidence, "chars": chars])
                }
            } catch {}
            DispatchQueue.main.async {
                self?.busy = false
                self?.send("window.gameOverlay&&gameOverlay.lines(\(Self.json(lines)))")
            }
        }
    }

    func show(on screen: NSScreen) {
        if panel == nil, let base = app?.base {
            let p = OverlayPanel(contentRect: screen.frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            // Above full-screen games, in every Space, and never a reason to switch Spaces.
            p.level = .screenSaver
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = false
            p.hidesOnDeactivate = false
            p.isFloatingPanel = true
            p.becomesKeyOnlyIfNeeded = false
            // See-through, yet it takes every click (explicitly false: otherwise clear areas would pass clicks to the game).
            p.ignoresMouseEvents = false
            let config = WKWebViewConfiguration()
            config.userContentController.add(self, name: "overlay")
            if let app { config.userContentController.add(app, name: "kotoba") }
            config.preferences.setValue(true, forKey: "developerExtrasEnabled")
            let w = WKWebView(frame: NSRect(origin: .zero, size: screen.frame.size), configuration: config)
            w.autoresizingMask = [.width, .height]
            w.setValue(false, forKey: "drawsBackground")
            if #available(macOS 13.3, *) { w.isInspectable = true }
            p.contentView = w
            w.load(URLRequest(url: base.appendingPathComponent("/").appending(queryItems: [URLQueryItem(name: "overlay", value: "1")])))
            panel = p
            web = w
        }
        guard let panel else { return }
        panel.setFrame(screen.frame, display: true)
        panel.orderFrontRegardless()
        // Games hide and lock the cursor while they're in front (WuWa: Option shows it). Bringing Kotoba forward gives
        // the overlay a normal, free cursor; the panel is on every Space, so this doesn't switch away from the game.
        NSApp.activate(ignoringOtherApps: true)
        CGAssociateMouseAndMouseCursorPosition(1)
        NSCursor.unhide()
        NSCursor.arrow.set()
        panel.makeKey()
        if let web { panel.makeFirstResponder(web) }
    }

    func hide() {
        send("window.gameOverlay&&gameOverlay.hide()")
        panel?.orderOut(nil)
        image = nil
        // Back to the game, which takes the mouse again.
        if let app = previousApp, !app.isTerminated { app.activate() }
    }

    func send(_ script: String) {
        if pageReady { web?.evaluateJavaScript(script, completionHandler: nil) } else { queued.append(script) }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let m = message.body as? [String: Any], let cmd = m["cmd"] as? String else { return }
        switch cmd {
        case "ready":
            pageReady = true
            let q = queued; queued = []
            q.forEach { web?.evaluateJavaScript($0, completionHandler: nil) }
        case "close": hide()
        case "lang":
            if let l = m["lang"] as? String, Self.languages[l] != nil { lang = l; busy = true; recognize() }
        case "rescan":
            hide()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.capture() }
        default: break
        }
    }

    func permissionHelp(_ error: Error) {
        let a = NSAlert()
        a.messageText = "Kotoba can’t see the screen yet"
        a.informativeText = "Allow Kotoba in System Settings › Privacy & Security › Screen & System Audio Recording, then quit and reopen Kotoba.\n\n(\(error.localizedDescription))"
        a.addButton(withTitle: "Open System Settings")
        a.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)
        if a.runModal() == .alertFirstButtonReturn,
           let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") { NSWorkspace.shared.open(url) }
    }

    static func jpeg(_ cg: CGImage) -> String {
        let rep = NSBitmapImageRep(cgImage: cg)
        let data = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.82]) ?? Data()
        return "data:image/jpeg;base64," + data.base64EncodedString()
    }
    static func json(_ v: Any) -> String {
        String(data: (try? JSONSerialization.data(withJSONObject: v)) ?? Data("null".utf8), encoding: .utf8) ?? "null"
    }
}

/// A system-wide shortcut (Carbon hot keys work while another app, such as a game, has the keyboard, and need no
/// Accessibility permission).
final class HotKey {
    static var actions: [UInt32: () -> Void] = [:]
    static var installed = false
    var ref: EventHotKeyRef?

    init(keyCode: Int, modifiers: Int, id: UInt32, action: @escaping () -> Void) {
        HotKey.actions[id] = action
        if !HotKey.installed {
            HotKey.installed = true
            var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
                var key = EventHotKeyID()
                GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), nil, MemoryLayout<EventHotKeyID>.size, nil, &key)
                let id = key.id
                DispatchQueue.main.async { HotKey.actions[id]?() }
                return noErr
            }, 1, &spec, nil, nil)
        }
        RegisterEventHotKey(UInt32(keyCode), UInt32(modifiers), EventHotKeyID(signature: OSType(0x4B544241), id: id), GetApplicationEventTarget(), 0, &ref)
    }
    deinit { if let ref { UnregisterEventHotKey(ref) } }

    /// Shortcut choices for the screen-text overlay (Settings › Reading). Two keys, easy to press mid-dialogue.
    static let overlayChoices: [String: (key: Int, mods: Int, label: String)] = [
        "ctrl-grave": (kVK_ANSI_Grave, controlKey, "⌃`"),
        "ctrl-1": (kVK_ANSI_1, controlKey, "⌃1"),
        "ctrl-q": (kVK_ANSI_Q, controlKey, "⌃Q"),
        "ctrl-cmd-o": (kVK_ANSI_O, controlKey | cmdKey, "⌃⌘O"),
    ]
    static var overlayChoice: String {
        let v = UserDefaults.standard.string(forKey: "OverlayShortcut") ?? "ctrl-grave"
        return overlayChoices[v] == nil ? "ctrl-grave" : v
    }
}
