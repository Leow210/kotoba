import Cocoa
import WebKit

/// Kotoba for Mac. The interface is the same HTML/JS as the phone app, served by the Java core (DesktopServer) on
/// 127.0.0.1; this app starts the core, shows the interface in a window, and answers the interface's requests for
/// native things: folder/file pickers, opening links, Finder, and the video player.
@main
final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    var window: NSWindow!
    var web: WKWebView!
    var core: Process?
    var coreInput: Pipe?
    var base: URL?
    var players: [PlayerWindow] = []
    var pendingOpen: [URL] = []

    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.regular)
        app.run()
    }

    // MARK: paths

    /// Resources inside Kotoba.app, or the repository when run from the build folder.
    lazy var resources: URL = {
        if let r = Bundle.main.resourceURL, FileManager.default.fileExists(atPath: r.appendingPathComponent("core/kotoba-core.jar").path) { return r }
        var dir = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
        for _ in 0..<8 {
            if FileManager.default.fileExists(atPath: dir.appendingPathComponent("desktop/build/kotoba-core.jar").path) { return dir }
            dir = dir.deletingLastPathComponent()
        }
        return URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    }()
    var bundled: Bool { FileManager.default.fileExists(atPath: resources.appendingPathComponent("core/kotoba-core.jar").path) }
    func res(_ bundledPath: String, _ repoPath: String) -> String {
        resources.appendingPathComponent(bundled ? bundledPath : repoPath).path
    }
    static let dataDir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Kotoba")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    // MARK: launch

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildMenu()
        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "kotoba")
        config.preferences.setValue(true, forKey: "developerExtrasEnabled")
        web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        web.uiDelegate = self
        if #available(macOS 13.3, *) { web.isInspectable = true }
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1180, height: 820),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
        window.title = "Kotoba"
        window.titlebarAppearsTransparent = true
        window.minSize = NSSize(width: 640, height: 480)
        window.contentView = web
        window.center()
        window.setFrameAutosaveName("KotobaMain")
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        startCore()
        if UserDefaults.standard.bool(forKey: "DevHooks") { installDevHooks() }
    }

    /// Development only (defaults write app.kotoba.desktop DevHooks -bool true): tools/mac.py asks for snapshots
    /// of the window and evaluates JavaScript in the page, like tools/cdp.py does for the phone.
    func installDevHooks() {
        let center = DistributedNotificationCenter.default()
        center.addObserver(forName: .init("app.kotoba.desktop.snapshot"), object: nil, queue: .main) { [weak self] n in
            guard let self, let path = n.object as? String else { return }
            self.web.takeSnapshot(with: nil) { image, _ in
                guard let image, let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
                      let png = rep.representation(using: .png, properties: [:]) else { return }
                try? png.write(to: URL(fileURLWithPath: path))
            }
        }
        center.addObserver(forName: .init("app.kotoba.desktop.eval"), object: nil, queue: .main) { [weak self] n in
            guard let self, let spec = n.object as? String, let nl = spec.firstIndex(of: "\n") else { return }
            var out = String(spec[..<nl]), script = String(spec[spec.index(after: nl)...])
            // "player:" evaluates in the newest video window's layer instead of the main window.
            var target: WKWebView = self.web
            if out.hasPrefix("player:"), let p = self.players.last { out.removeFirst(7); target = p.overlay }
            if script.hasPrefix("mpv:"), let p = self.players.last {
                let name = String(script.dropFirst(4))
                try? (p.video.get(name) ?? "null").write(toFile: out, atomically: true, encoding: .utf8)
                return
            }
            target.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
                var text: String
                switch result {
                case .success(let v):
                    if JSONSerialization.isValidJSONObject(v), let d = try? JSONSerialization.data(withJSONObject: v, options: [.prettyPrinted]) { text = String(data: d, encoding: .utf8) ?? "" }
                    else { text = "\(v)" }
                case .failure(let e): text = "ERROR " + e.localizedDescription
                }
                try? text.write(toFile: out, atomically: true, encoding: .utf8)
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationWillTerminate(_ notification: Notification) { players.forEach { $0.close() }; core?.terminate() }

    /// Starts the Java core and loads the interface once it prints its port and session token.
    func startCore() {
        let jars = bundled
            ? ["core/kotoba-core.jar", "core/json-20250517.jar", "core/sqlite-jdbc-3.50.3.0.jar", "core/slf4j-api-2.0.17.jar"]
            : ["desktop/build/kotoba-core.jar", "desktop/libs/json-20250517.jar", "desktop/libs/sqlite-jdbc-3.50.3.0.jar", "desktop/libs/slf4j-api-2.0.17.jar"]
        let p = Process()
        p.executableURL = URL(fileURLWithPath: javaPath())
        p.arguments = ["-Xmx3g", "-Dapple.awt.UIElement=true", "-cp", jars.map { resources.appendingPathComponent($0).path }.joined(separator: ":"),
                       "app.kotoba.reader.DesktopServer", Self.dataDir.path, res("assets", "android/assets"), res("web", "desktop/web")]
        let out = Pipe()
        p.standardOutput = out
        // Kept open for the core's lifetime: if this app dies, the pipe closes and the core exits.
        let stdin = Pipe()
        p.standardInput = stdin
        coreInput = stdin
        p.standardError = FileHandle(forWritingAtPath: "/dev/null") ?? FileHandle.standardError
        var buffer = ""
        out.fileHandleForReading.readabilityHandler = { [weak self] h in
            guard let s = String(data: h.availableData, encoding: .utf8), !s.isEmpty else { return }
            buffer += s
            guard let line = buffer.split(separator: "\n").first(where: { $0.hasPrefix("KOTOBA PORT") }) else { return }
            let parts = line.split(separator: " ")
            guard parts.count >= 5, let port = Int(parts[2]) else { return }
            h.readabilityHandler = nil
            DispatchQueue.main.async {
                let url = URL(string: "http://127.0.0.1:\(port)/?t=\(parts[4])")!
                self?.base = URL(string: "http://127.0.0.1:\(port)")
                self?.web.load(URLRequest(url: url))
                if let pending = self?.pendingOpen { self?.pendingOpen = []; pending.forEach { self?.openVideo($0) } }
            }
        }
        p.terminationHandler = { proc in
            DispatchQueue.main.async { [weak self] in
                guard let self, NSApp.isRunning, proc.terminationReason != .exit || proc.terminationStatus != 15 else { return }
                if self.base == nil { self.fail("Kotoba’s dictionary core didn’t start (exit \(proc.terminationStatus)). Java 21 is required.") }
            }
        }
        do { try p.run(); core = p } catch { fail("Couldn’t start Java: \(error.localizedDescription)") }
    }

    func javaPath() -> String {
        for candidate in ["/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java", "/opt/homebrew/opt/openjdk/bin/java", "/usr/bin/java"]
        where FileManager.default.isExecutableFile(atPath: candidate) { return candidate }
        return "/usr/bin/java"
    }

    func fail(_ message: String) {
        let a = NSAlert()
        a.messageText = "Kotoba can’t start"
        a.informativeText = message
        a.runModal()
        NSApp.terminate(nil)
    }

    // MARK: requests from the interface

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let m = message.body as? [String: Any], let type = m["type"] as? String else { return }
        switch type {
        case "pickFolder":
            let panel = NSOpenPanel()
            panel.canChooseDirectories = true
            panel.canChooseFiles = false
            panel.message = "Choose a folder with .mdx/.mdd dictionaries or Yomitan .zip dictionaries"
            panel.beginSheetModal(for: window) { [weak self] r in
                guard r == .OK, let url = panel.url else { return }
                self?.event("folder", url.path)
            }
        case "pickFile":
            let panel = NSOpenPanel()
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            if let ext = m["extensions"] as? [String] { panel.allowedContentTypes = ext.compactMap { .init(filenameExtension: $0) } }
            let purpose = m["purpose"] as? String ?? ""
            panel.beginSheetModal(for: window) { [weak self] r in
                guard r == .OK, let url = panel.url else { return }
                self?.js("window.desktopPicked(\(Self.quote(purpose)),\(Self.quote(url.path)))")
            }
        case "open":
            if let s = m["url"] as? String, let url = URL(string: s) { NSWorkspace.shared.open(url) }
        case "reveal":
            if let p = m["path"] as? String { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: p)]) }
        case "copy":
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(m["text"] as? String ?? "", forType: .string)
        case "openVideo":
            if let p = m["path"] as? String { openVideo(URL(fileURLWithPath: p)) } else { chooseVideo() }
        default:
            break
        }
    }

    // MARK: video

    static let videoTypes = ["mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "flv", "wmv", "mpg", "mpeg", "ogv", "3gp"]

    @objc func chooseVideo() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = Self.videoTypes.compactMap { .init(filenameExtension: $0) }
        panel.message = "Choose a video. Subtitles beside it (and inside it) are found automatically."
        panel.begin { [weak self] r in if r == .OK, let url = panel.url { self?.openVideo(url) } }
    }

    func openVideo(_ url: URL) {
        guard let base else { pendingOpen.append(url); return }
        let p = PlayerWindow(url: url, base: base, app: self)
        p.onClose = { [weak self, weak p] in self?.players.removeAll { $0 === p } }
        players.append(p)
        p.showWindow(nil)
        NSDocumentController.shared.noteNewRecentDocumentURL(url)
    }

    /// "Open in Kotoba" from a player: the word opens in the main window's dictionary.
    func showInMain(word: String) {
        window.makeKeyAndOrderFront(nil)
        js("window.externalLookup&&window.externalLookup(\(Self.quote(word)))")
    }

    func application(_ sender: NSApplication, openFile filename: String) -> Bool {
        openVideo(URL(fileURLWithPath: filename)); return true
    }
    func application(_ application: NSApplication, open urls: [URL]) { urls.forEach(openVideo) }

    /// Delivers an event the way the Android app does (window.__event).
    func event(_ type: String, _ data: Any) {
        guard let json = try? JSONSerialization.data(withJSONObject: ["type": type, "data": data]),
              let s = String(data: json, encoding: .utf8) else { return }
        js("window.__event&&window.__event(\(Self.quote(s)))")
    }
    func js(_ script: String) { web.evaluateJavaScript(script, completionHandler: nil) }
    static func quote(_ s: String) -> String {
        let d = try! JSONSerialization.data(withJSONObject: [s])
        let a = String(data: d, encoding: .utf8)!
        return String(a.dropFirst().dropLast())
    }

    // Links inside entries are handled by the page; nothing navigates the app away. Web links open in the browser.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, let base, url.host == base.host, url.port == base.port { decisionHandler(.allow); return }
        if action.targetFrame?.isMainFrame == false, action.request.url?.scheme == "about" { decisionHandler(.allow); return }
        if let url = action.request.url, ["http", "https"].contains(url.scheme ?? ""), action.navigationType == .linkActivated { NSWorkspace.shared.open(url) }
        decisionHandler(action.targetFrame == nil || action.navigationType == .other ? .allow : .cancel)
    }

    // MARK: menu

    func buildMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem(); main.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "About Kotoba", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Hide Kotoba", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(withTitle: "Quit Kotoba", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        let fileItem = NSMenuItem(); main.addItem(fileItem)
        let file = NSMenu(title: "File")
        file.addItem(withTitle: "Open Video…", action: #selector(chooseVideo), keyEquivalent: "o")
        fileItem.submenu = file
        let editItem = NSMenuItem(); main.addItem(editItem)
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        let viewItem = NSMenuItem(); main.addItem(viewItem)
        let view = NSMenu(title: "View")
        view.addItem(withTitle: "Reload", action: #selector(reload), keyEquivalent: "r")
        view.addItem(withTitle: "Enter Full Screen", action: #selector(NSWindow.toggleFullScreen(_:)), keyEquivalent: "f")
        viewItem.submenu = view
        let windowItem = NSMenuItem(); main.addItem(windowItem)
        let windowMenu = NSMenu(title: "Window")
        windowMenu.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        windowMenu.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        windowItem.submenu = windowMenu
        NSApp.mainMenu = main
        NSApp.windowsMenu = windowMenu
    }
    @objc func reload() { web.reload() }
}
